package com.constrivo.drop.platform.android.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [StationMonitor] from connectivity callbacks: connected while at least one Wi-Fi network with internet capability is
 * available (the station; networks joined for a transfer are local-only and never count). Start it before a link that
 * may displace the station comes up, so the teardown knows there is a previous network to wait for (F-E11). Needs
 * `ACCESS_NETWORK_STATE`. [start] and [close] are idempotent.
 */
class AndroidStationMonitor(
    context: Context,
) : StationMonitor,
    AutoCloseable {
    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap.newKeySet<Long>()
    private val mutable = MutableStateFlow(false)
    private val lock = Any()
    private var registered = false

    override val connected: StateFlow<Boolean> = mutable.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks += network.networkHandle
                mutable.value = networks.isNotEmpty()
            }

            override fun onLost(network: Network) {
                networks -= network.networkHandle
                mutable.value = networks.isNotEmpty()
            }
        }

    fun start() {
        synchronized(lock) {
            if (registered) return
            val manager = connectivity ?: return
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            try {
                manager.registerNetworkCallback(request, callback)
                registered = true
            } catch (_: RuntimeException) {
                // Without ACCESS_NETWORK_STATE or with too many callbacks: nothing is known, nothing is waited for.
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!registered) return
            registered = false
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
            networks.clear()
            mutable.value = false
        }
    }
}
