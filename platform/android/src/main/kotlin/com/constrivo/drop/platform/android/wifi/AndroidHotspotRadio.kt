package com.constrivo.drop.platform.android.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.util.forEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [HotspotRadio] over `WifiManager.startLocalOnlyHotspot` (architecture §8, hotspot host): callbacks on [handler]'s
 * thread (the main thread by default), the `SoftApConfiguration` read field by field with the API level each needs
 * (SSID bytes from 33, the band from 36; [HotspotConfigValues]).
 *
 * [AndroidHotspotLinkProvider] checks the permissions first ([WifiPermissions]); a `SecurityException` from a permission
 * revoked meanwhile, or from Android 12's location-mode check, is mapped there, which is why lint's MissingPermission
 * is suppressed here.
 */
@SuppressLint("MissingPermission")
class AndroidHotspotRadio(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : HotspotRadio {
    private val appContext = context.applicationContext
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)

    /** False without a Wi-Fi service (the provider then cannot host). */
    val isAvailable: Boolean get() = wifi != null

    override fun start(callback: HotspotCallback) {
        val manager = wifi ?: throw IllegalStateException("no Wi-Fi service")
        manager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    callback.onStarted(PlatformReservation(reservation))
                }

                override fun onStopped() = callback.onStopped()

                override fun onFailed(reason: Int) = callback.onFailed(reason)
            },
            handler,
        )
    }

    @Suppress("DEPRECATION") // getAllNetworks: the interfaces of every known network at this moment, not a stream.
    override fun knownNetworkInterfaces(): Set<String> {
        val manager = connectivity ?: return emptySet()
        return try {
            manager.allNetworks.mapNotNullTo(HashSet()) { network -> manager.getLinkProperties(network)?.interfaceName }
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    /** A platform reservation; [close] once. */
    private class PlatformReservation(
        private val reservation: WifiManager.LocalOnlyHotspotReservation,
    ) : HotspotReservation {
        private val closed = AtomicBoolean(false)

        override val config: HotspotConfigValues = valuesOf(reservation.softApConfiguration)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                reservation.close()
            } catch (_: RuntimeException) {
                // The service is gone; the hotspot went with it.
            }
        }
    }

    private companion object {
        @Suppress("DEPRECATION") // getSsid: the only SSID accessor below API 33.
        fun valuesOf(config: SoftApConfiguration): HotspotConfigValues {
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) config.wifiSsid?.bytes else null
            val text = if (bytes == null) config.ssid else null
            val channels =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    LinkedHashMap<Int, Int>().also { map -> config.channels.forEach { band, channel -> map[band] = channel } }
                } else {
                    null
                }
            return HotspotConfigValues(
                ssidBytes = bytes,
                ssidText = text,
                passphrase = config.passphrase,
                securityType = config.securityType,
                bssid = config.bssid?.toString(),
                channels = channels,
            )
        }
    }
}
