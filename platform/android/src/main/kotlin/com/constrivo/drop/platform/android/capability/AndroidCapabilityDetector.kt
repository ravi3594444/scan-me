package com.constrivo.drop.platform.android.capability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.platform.android.ble.BluetoothPower
import com.constrivo.drop.platform.android.ble.BluetoothPowerMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability detection (F-A4, architecture §5.2): reads what the hardware and the current network allow, maps it with
 * [CapabilityMapping] and publishes [LocalRadioFacts] on [facts], updated on connectivity callbacks (the Wi-Fi station
 * network, its link properties and frequency, N6 and N9), on Bluetooth and Wi-Fi state broadcasts, and when the caller
 * changes the persisted inputs ([setVerifiedP2p5GhzHost], [setSaveVolume]).
 *
 * The station is the connected Wi-Fi network with internet capability (a local-only network a later join creates has
 * none, so it never counts); its frequency comes from the network's `WifiInfo` transport info and its hint inputs from
 * `LinkProperties`, neither of which needs location permission (N6). Every platform call is guarded: a missing
 * permission or a vendor quirk leaves the affected bit clear instead of failing.
 *
 * Detection runs on one background coroutine ([ConflatedRefresh] on [io]): the callbacks and receivers only signal it,
 * so the main and connectivity threads never wait for the binder calls (some of which the Wi-Fi service answers on its
 * own thread, slowly while Wi-Fi changes state), and detections never overlap, so one that read the networks before an
 * `onLost` cannot publish after the one that followed it. The hardware answers (bands, standards, Wi-Fi Direct,
 * concurrency, the Bluetooth features, the save volume) are read again only after a Wi-Fi or Bluetooth state broadcast,
 * [start] or [setSaveVolume]; a network callback, RSSI updates included, only re-reads the station.
 *
 * Needs `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` (declared by this module). Call [start] when the radio session
 * starts and [stop] when it ends; both are idempotent.
 */
class AndroidCapabilityDetector(
    context: Context,
    private val crypto: CryptoProvider,
    private val power: BluetoothPowerMonitor = BluetoothPowerMonitor(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val storage: StorageManager? = appContext.getSystemService(StorageManager::class.java)
    private val packages: PackageManager = appContext.packageManager

    private val lock = Any()
    private val networks = ConcurrentHashMap<Long, WifiNetworkSnapshot>()
    private val mutable = MutableStateFlow(LocalRadioFacts.UNKNOWN)
    private var started = false
    private var scope: CoroutineScope? = null

    @Volatile private var refresher: ConflatedRefresh? = null

    /** Everything but the station, from the last hardware read; used by the detection worker only. */
    private var hardware: CapabilityInputs? = null

    /** A state broadcast (or [start], [setSaveVolume]) changed what the hardware answers: read it again. */
    @Volatile private var hardwareStale = true

    @Volatile private var verifiedP2p5Ghz = false

    @Volatile private var saveVolumeName: String? = null

    // Bluetooth feature answers read while the adapter was on (some builds answer false while it is off).
    @Volatile private var lastExtendedAdvertising = false

    @Volatile private var lastCodedPhy = false

    @Volatile private var last2mPhy = false

    /** The latest detection; [LocalRadioFacts.UNKNOWN] before [start]. */
    val facts: StateFlow<LocalRadioFacts> = mutable.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val frequency = (capabilities.transportInfo as? WifiInfo)?.frequency ?: -1
                networks.compute(network.networkHandle) { handle, old ->
                    WifiNetworkSnapshot(handle, frequency, old?.link)
                }
                refresh()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                val link = linkInfo(linkProperties)
                networks.compute(network.networkHandle) { handle, old ->
                    WifiNetworkSnapshot(handle, old?.frequencyMhz ?: -1, link)
                }
                refresh()
            }

            override fun onLost(network: Network) {
                networks.remove(network.networkHandle)
                refresh()
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Wi-Fi or Bluetooth changed state: some builds answer the feature questions differently now.
                hardwareStale = true
                refresh()
            }
        }

    /** Registers the callbacks and starts a first detection (published on [facts] shortly after). */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            hardwareStale = true
            val worker = CoroutineScope(SupervisorJob() + io)
            scope = worker
            refresher = ConflatedRefresh(worker, ::detectAndPublish)
            power.start()
            // The power monitor's own receiver may run after ours: follow its state too, so the Bluetooth bits and the
            // Bluetooth-enabled fact never lag a toggle.
            worker.launch {
                power.state.collect {
                    hardwareStale = true
                    refresh()
                }
            }
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            try {
                connectivity?.registerNetworkCallback(request, callback)
            } catch (e: RuntimeException) {
                // SecurityException without ACCESS_NETWORK_STATE, or too many callbacks: no station facts.
            }
            val filter =
                IntentFilter().apply {
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        refresh()
    }

    /** Unregisters everything; [facts] keeps its last value. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
            runCatching { appContext.unregisterReceiver(receiver) }
            networks.clear()
            power.stop()
            refresher = null
            scope?.cancel()
            scope = null
        }
    }

    /** Bit 4: a Wi-Fi Direct group this device hosted came up on 5 GHz (persisted by WP7c). */
    fun setVerifiedP2p5GhzHost(verified: Boolean) {
        verifiedP2p5Ghz = verified
        refresh()
    }

    /** The MediaStore volume received files go to (`MediaStore.VOLUME_EXTERNAL_PRIMARY` when null), for bit 10. */
    fun setSaveVolume(mediaStoreVolumeName: String?) {
        saveVolumeName = mediaStoreVolumeName
        hardwareStale = true
        refresh()
    }

    /**
     * Asks for a detection; it runs on the background worker after any detection in progress and publishes on [facts].
     * Returns at once; safe from any thread. Does nothing before [start] (the settings above are kept for it).
     */
    fun refresh() {
        refresher?.request()
    }

    /** The worker's detection: the hardware answers when they may have changed, the station every time. */
    private fun detectAndPublish() {
        val known = hardware
        val base =
            if (hardwareStale || known == null) {
                hardwareStale = false
                readHardware().also { hardware = it }
            } else {
                known
            }
        mutable.value = CapabilityMapping.facts(withStation(base), crypto)
    }

    /**
     * The raw inputs of a detection made now, on the caller's thread, for the diagnostics log and the lab's F-A4
     * comparison. It makes a dozen binder calls: not for the main thread.
     */
    fun readInputs(): CapabilityInputs = withStation(readHardware())

    private fun withStation(hardware: CapabilityInputs): CapabilityInputs =
        hardware.copy(station = readStation(hardware.hasWifi), verifiedP2p5GhzHost = verifiedP2p5Ghz)

    private fun readStation(hasWifi: Boolean): StationFacts? {
        if (!hasWifi) return null
        val default = runCatching { connectivity?.activeNetwork?.networkHandle }.getOrNull()
        return NetworkLinkExtraction.stationFacts(NetworkLinkExtraction.chooseStation(networks.values.toList(), default))
    }

    /** Everything but the station ([CapabilityInputs.station] is null here). */
    private fun readHardware(): CapabilityInputs {
        val wifi = wifiManager?.takeIf { packages.hasSystemFeature(PackageManager.FEATURE_WIFI) }
        val hasWifi = wifi != null

        fun wifiFlag(read: (WifiManager) -> Boolean): Boolean = wifi != null && guard { read(wifi) }
        val adapter = power.adapter
        val bluetoothOn = power.state.value == BluetoothPower.ON
        if (adapter != null && bluetoothOn) {
            lastExtendedAdvertising = guard { adapter.isLeExtendedAdvertisingSupported }
            lastCodedPhy = guard { adapter.isLeCodedPhySupported }
            last2mPhy = guard { adapter.isLe2MPhySupported }
        }
        return CapabilityInputs(
            hasWifi = hasWifi,
            wifiEnabled = wifiFlag { it.isWifiEnabled },
            wifi5GhzSupported = wifiFlag { it.is5GHzBandSupported },
            wifi6GhzSupported = wifiFlag { it.is6GHzBandSupported },
            wifiStandard11ax = wifiFlag { it.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) },
            wifiStandard11be =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    wifiFlag { it.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) },
            wifiDirectFeature = packages.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            p2pSupported = wifiFlag { it.isP2pSupported },
            wifiAwareFeature = packages.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            bluetoothClassicFeature = packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            bluetoothLeFeature = packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            bluetoothAdapterPresent = adapter != null,
            bluetoothEnabled = bluetoothOn,
            leExtendedAdvertising = lastExtendedAdvertising,
            leCodedPhy = lastCodedPhy,
            le2mPhy = last2mPhy,
            saveLocationRemovable = saveVolumeRemovable(),
            staApConcurrency = wifiFlag { it.isStaApConcurrencySupported },
            dualBandSimultaneous =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && wifiFlag { it.isDualBandSimultaneousSupported },
        )
    }

    private fun saveVolumeRemovable(): Boolean {
        val manager = storage ?: return false
        return try {
            val name = saveVolumeName
            val volume =
                if (name == null) {
                    manager.primaryStorageVolume
                } else {
                    manager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == name } ?: manager.primaryStorageVolume
                }
            volume.isRemovable
        } catch (e: RuntimeException) {
            false
        }
    }

    private inline fun guard(read: () -> Boolean): Boolean =
        try {
            read()
        } catch (e: RuntimeException) {
            // A SecurityException or a vendor quirk: report the capability as absent.
            false
        }

    private fun linkInfo(properties: LinkProperties) =
        NetworkLinkExtraction.linkInfo(
            routes =
                properties.routes.map { route ->
                    RouteFacts(isDefault = route.isDefaultRoute, gateway = route.gateway?.takeIf { route.hasGateway() }?.address)
                },
            dhcpServer = properties.dhcpServerAddress?.address,
            linkAddresses = properties.linkAddresses.mapNotNull { it.address?.address },
        )
}
