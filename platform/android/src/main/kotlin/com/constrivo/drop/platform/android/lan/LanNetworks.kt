package com.constrivo.drop.platform.android.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.constrivo.drop.platform.android.wifi.AndroidNetworkRequester
import com.constrivo.drop.platform.android.wifi.AndroidNetworkSocketBinder
import com.constrivo.drop.platform.android.wifi.InterfaceAddressInfo
import com.constrivo.drop.platform.android.wifi.IpLiteral
import com.constrivo.drop.platform.android.wifi.IpPrefix
import com.constrivo.drop.platform.android.wifi.SocketBinder
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** The transport of a LAN network. */
enum class LanTransport {
    WIFI,
    ETHERNET,
}

/**
 * One network the LAN rung can use: a Wi-Fi station or Ethernet network with internet capability (a router with its
 * internet unplugged still counts, T-16; a network joined for a transfer is local-only and never does).
 *
 * @property binder binds sockets to this network (`Network.bindSocket`), so LAN traffic never leaves over mobile data.
 */
class LanNetworkSnapshot(
    val handle: Long,
    val transport: LanTransport,
    val interfaceName: String?,
    val addresses: List<InterfaceAddressInfo>,
    val binder: SocketBinder,
) {
    val prefixes: List<IpPrefix> get() = addresses.mapNotNull { it.prefix }

    /** The private IPv4 address the LAN host listens on and announces. */
    val privateIpv4: InetAddress? get() = addresses.firstOrNull { it.isIpv4 && IpLiteral.isPrivate(it.address) }?.address

    override fun toString(): String =
        "LanNetworkSnapshot($handle, $transport, $interfaceName, ${addresses.map {
            "${it.address.hostAddress}/${it.prefixLength}"
        }})"
}

/** The LAN networks now, a seam so [AndroidLanLinkProvider] runs in JVM tests; [AndroidLanNetworks] is the platform one. */
fun interface LanNetworks {
    fun current(): List<LanNetworkSnapshot>
}

/** Picks the network of the LAN rung (architecture §4, F-E4), a pure function. */
object LanNetworkSelector {
    /**
     * The network the LAN host listens on: one with a private IPv4 address (the address announced in `LinkReady`),
     * Wi-Fi before Ethernet, then the lowest handle, so the choice is stable. Null when there is none.
     */
    fun forHost(networks: List<LanNetworkSnapshot>): LanNetworkSnapshot? =
        networks.filter { it.privateIpv4 != null }.minWithOrNull(compareBy({ it.transport.ordinal }, { it.handle }))

    /**
     * The network that reaches [target] directly: one of whose prefixes contains it, Wi-Fi before Ethernet. Null when
     * the target is on none of this device's networks (the LAN rung does not route).
     */
    fun forTarget(
        networks: List<LanNetworkSnapshot>,
        target: InetAddress,
    ): LanNetworkSnapshot? =
        networks.filter { network ->
            network.prefixes.any { it.contains(target) }
        }.minWithOrNull(compareBy({ it.transport.ordinal }, { it.handle }))
}

/**
 * [LanNetworks] from connectivity callbacks: Wi-Fi and Ethernet networks with internet capability, with their interface
 * and addresses. Needs `ACCESS_NETWORK_STATE`. Call [start] when the radio session starts, [close] when it ends; both
 * are idempotent.
 */
class AndroidLanNetworks(
    context: Context,
) : LanNetworks,
    AutoCloseable {
    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var registered = false
    private val known = ConcurrentHashMap<Long, Entry>()

    private class Entry(
        val network: Network,
        val transport: LanTransport?,
        val properties: LinkProperties?,
    )

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val transport =
                    when {
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanTransport.WIFI
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanTransport.ETHERNET
                        else -> null
                    }
                known.compute(network.networkHandle) { _, old -> Entry(network, transport, old?.properties) }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                known.compute(network.networkHandle) { _, old -> Entry(network, old?.transport, linkProperties) }
            }

            override fun onLost(network: Network) {
                known.remove(network.networkHandle)
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
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            try {
                manager.registerNetworkCallback(request, callback)
                registered = true
            } catch (_: RuntimeException) {
                // Without ACCESS_NETWORK_STATE or with too many callbacks: no LAN network is known.
            }
        }
    }

    override fun current(): List<LanNetworkSnapshot> =
        known.entries.mapNotNull { (handle, entry) ->
            val transport = entry.transport ?: return@mapNotNull null
            val properties = entry.properties ?: return@mapNotNull null
            LanNetworkSnapshot(
                handle,
                transport,
                properties.interfaceName,
                AndroidNetworkRequester.addressesOf(properties),
                AndroidNetworkSocketBinder(entry.network),
            )
        }

    override fun close() {
        synchronized(lock) {
            if (!registered) return
            registered = false
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
            known.clear()
        }
    }
}
