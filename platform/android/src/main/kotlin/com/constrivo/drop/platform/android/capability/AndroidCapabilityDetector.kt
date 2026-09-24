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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Needs `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` (declared by this module). Call [start] when the radio session
 * starts and [stop] when it ends; both are idempotent.
 */
class AndroidCapabilityDetector(
    context: Context,
    private val crypto: CryptoProvider,
    private val power: BluetoothPowerMonitor = BluetoothPowerMonitor(context),
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
                refresh()
            }
        }

    /** Registers the callbacks and publishes a first detection. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            power.start()
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
        refresh()
    }

    /** Detects again now and publishes the result. Cheap; safe from any thread. */
    fun refresh() {
        val inputs = readInputs()
        mutable.value = CapabilityMapping.facts(inputs, crypto)
    }

    /** The raw inputs of the current detection, for the diagnostics log and the lab's F-A4 comparison. */
    fun readInputs(): CapabilityInputs {
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
        val station =
            if (hasWifi) {
                val default = runCatching { connectivity?.activeNetwork?.networkHandle }.getOrNull()
                NetworkLinkExtraction.stationFacts(NetworkLinkExtraction.chooseStation(networks.values.toList(), default))
            } else {
                null
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
            verifiedP2p5GhzHost = verifiedP2p5Ghz,
            station = station,
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
