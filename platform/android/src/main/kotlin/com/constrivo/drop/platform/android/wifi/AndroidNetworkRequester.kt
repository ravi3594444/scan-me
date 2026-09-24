package com.constrivo.drop.platform.android.wifi

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [NetworkRequester] over `ConnectivityManager.requestNetwork` with a `WifiNetworkSpecifier` (architecture §8, hotspot
 * join): the request asks for a Wi-Fi network without internet capability (a local-only network), with the SSID, the
 * WPA2 or WPA3 passphrase and, when known, the BSSID of [SpecifierRequestSpec]. The callback's `Network` becomes the
 * [SocketBinder] of the link's sockets (`Network.bindSocket`, T-15); its `WifiInfo` gives the channel and its
 * `LinkProperties` the interface and addresses. Releasing the handle unregisters the callback, which withdraws the
 * request, so the system disconnects the network and rejoins the previous one (F-E11).
 */
class AndroidNetworkRequester(
    context: Context,
) : NetworkRequester {
    private val connectivity: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("no connectivity service")

    override fun request(
        spec: SpecifierRequestSpec,
        events: (NetworkEvent) -> Unit,
    ): NetworkRequestHandle {
        val specifier =
            WifiNetworkSpecifier
                .Builder()
                .setSsid(spec.ssid)
                .apply {
                    when (spec.security) {
                        SpecifierSecurity.WPA2_PSK -> setWpa2Passphrase(spec.passphrase)
                        SpecifierSecurity.WPA3_SAE -> setWpa3Passphrase(spec.passphrase)
                    }
                    spec.bssid?.let { setBssid(MacAddress.fromString(it)) }
                }.build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) =
                    events(NetworkEvent.Available(network.networkHandle, AndroidNetworkSocketBinder(network)))

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val frequency = (networkCapabilities.transportInfo as? WifiInfo)?.frequency
                    events(NetworkEvent.CapabilitiesChanged(network.networkHandle, WifiFrequencies.valid(frequency)))
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) = events(
                    NetworkEvent.LinkPropertiesChanged(
                        network.networkHandle,
                        linkProperties.interfaceName,
                        addressesOf(linkProperties),
                    ),
                )

                override fun onLost(network: Network) = events(NetworkEvent.Lost(network.networkHandle))

                override fun onUnavailable() = events(NetworkEvent.Unavailable)
            }
        connectivity.requestNetwork(request, callback)
        val released = AtomicBoolean(false)
        return NetworkRequestHandle {
            if (released.compareAndSet(false, true)) {
                try {
                    connectivity.unregisterNetworkCallback(callback)
                } catch (_: RuntimeException) {
                    // Already unregistered (the request ended with onUnavailable).
                }
            }
        }
    }

    companion object {
        /** The addresses of [properties] with their prefix lengths. */
        fun addressesOf(properties: LinkProperties): List<InterfaceAddressInfo> =
            properties.linkAddresses.mapNotNull { la -> la.address?.let { InterfaceAddressInfo(it, la.prefixLength) } }
    }
}

/**
 * Whether Android accepts a network-specifier request from this app now: its process is in the foreground or runs a
 * foreground service (importance `IMPORTANCE_FOREGROUND_SERVICE` or better), the rule Android applies (N7).
 */
object AppForeground {
    fun isForegroundOrService(): Boolean =
        try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        } catch (_: RuntimeException) {
            // Cannot tell: let the request decide (a background request then ends in onUnavailable).
            true
        }
}
