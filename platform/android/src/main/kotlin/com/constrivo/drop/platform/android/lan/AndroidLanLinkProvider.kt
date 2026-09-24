package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.platform.android.wifi.IpLiteral
import com.constrivo.drop.platform.android.wifi.LinkDetails
import com.constrivo.drop.platform.android.wifi.LinkDialPolicy
import com.constrivo.drop.platform.android.wifi.LinkListenerFactory
import com.constrivo.drop.platform.android.wifi.NetworkBoundSocketFactory
import com.constrivo.drop.platform.android.wifi.SocketActiveLink
import com.constrivo.drop.platform.android.wifi.WifiLinkError
import com.constrivo.drop.platform.android.wifi.WifiLinkEvent
import com.constrivo.drop.platform.android.wifi.WifiLinkException
import com.constrivo.drop.platform.android.wifi.WifiLinkListener
import com.constrivo.drop.platform.android.wifi.WifiOperation
import com.constrivo.drop.platform.android.wifi.WifiPermissionContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress

/**
 * The LAN rung on Android (F-E4, F-A3; architecture §4, §7.4, §8; T-15, T-16): TCP over the Wi-Fi (or Ethernet) network
 * both devices are on. The sender hosts (S5): it listens on its private IPv4 address of that network with an ephemeral
 * port, which its `LinkReady` announces; the receiver joins and connects to that address. Nothing changes networks, so
 * teardown only closes the listener and the streams (F-E11 has nothing to restore).
 *
 * Every socket is bound to the chosen `Network` ([NetworkBoundSocketFactory]), so LAN traffic can never leave over
 * mobile data, and a joiner dials only an address on that network's own prefixes, or exactly [peerAddress] when the
 * session's peer address is known (a LAN control connection: create the provider per session then), so an accepted
 * stranger cannot make this phone connect elsewhere (§13). A joiner whose host address is known picks the network
 * that reaches it; one that is on none of this device's networks fails with [WifiLinkError.NO_NETWORK].
 *
 * On Android 17 an app targeting API 37 needs `ACCESS_LOCAL_NETWORK` for the local network:
 * [WifiLinkError.PERMISSION_MISSING] before anything is opened. [listenerFactory] binds the host's listener (a seam for
 * JVM tests).
 */
class AndroidLanLinkProvider(
    private val networks: LanNetworks,
    private val permissions: WifiPermissionContext,
    private val clock: MonotonicClock,
    private val peerAddress: InetAddress? = null,
    private val listener: WifiLinkListener = WifiLinkListener.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val listenerFactory: LinkListenerFactory = LinkListenerFactory.DEFAULT,
) : WifiLinkProvider {
    override val kind: LinkKind = LinkKind.LAN

    override fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean = mode == LinkMode.LAN

    /**
     * Listens on the LAN network's address.
     *
     * @throws WifiLinkException [WifiLinkError.PERMISSION_MISSING] or [WifiLinkError.NO_NETWORK].
     * @throws java.io.IOException when the port cannot be bound.
     */
    override suspend fun host(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        require(request.mode == LinkMode.LAN) { "the LAN provider hosts only the LAN rung, not ${request.mode}" }
        val startedAt = clock.elapsedMillis()
        permissions.require(WifiOperation.LOCAL_NETWORK)
        val network =
            LanNetworkSelector.forHost(networks.current())
                ?: throw WifiLinkException(WifiLinkError.NO_NETWORK, "not on a Wi-Fi or Ethernet network with a private IPv4 address")
        val address =
            network.privateIpv4 ?: throw WifiLinkException(WifiLinkError.NO_NETWORK, "the LAN network has no private IPv4 address")
        val link = link(network, LinkRole.HOST, address)
        onUp(link)
        listener.onEvent(WifiLinkEvent.Up(kind, LinkMode.LAN, LinkRole.HOST, null, clock.elapsedMillis() - startedAt, link.details))
        return link
    }

    /**
     * The LAN needs no joining: the link exists at once, on the network that reaches the host's `LinkReady` address when
     * it is known, else on the LAN network, and connects when the engine opens its streams.
     *
     * @throws WifiLinkException [WifiLinkError.PERMISSION_MISSING] or [WifiLinkError.NO_NETWORK].
     */
    override suspend fun join(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        require(request.mode == LinkMode.LAN) { "the LAN provider joins only the LAN rung, not ${request.mode}" }
        val startedAt = clock.elapsedMillis()
        permissions.require(WifiOperation.LOCAL_NETWORK)
        val current = networks.current()
        val target = request.hostAddress?.let(IpLiteral::parse)
        val network =
            if (target != null) {
                LanNetworkSelector.forTarget(current, target)
                    ?: throw WifiLinkException(
                        WifiLinkError.NO_NETWORK,
                        "the host's address ${request.hostAddress} is on none of this device's networks",
                    )
            } else {
                LanNetworkSelector.forHost(current)
                    ?: throw WifiLinkException(WifiLinkError.NO_NETWORK, "not on a Wi-Fi or Ethernet network")
            }
        val link = link(network, LinkRole.JOIN, null)
        onUp(link)
        listener.onEvent(WifiLinkEvent.Up(kind, LinkMode.LAN, LinkRole.JOIN, null, clock.elapsedMillis() - startedAt, link.details))
        return link
    }

    private fun link(
        network: LanNetworkSnapshot,
        role: LinkRole,
        listenAddress: InetAddress?,
    ): SocketActiveLink =
        SocketActiveLink(
            kind = kind,
            mode = LinkMode.LAN,
            role = role,
            frequencyMhz = null,
            credentials = null,
            details = LinkDetails.Lan(network.handle, network.interfaceName),
            listenAddress = listenAddress,
            socketFactory = NetworkBoundSocketFactory(binder = network.binder),
            dialPolicy = LinkDialPolicy(network.prefixes, expected = peerAddress),
            release = {},
            io = io,
            connectTimeoutMillis = connectTimeoutMillis,
            listenerFactory = listenerFactory,
        )
}
