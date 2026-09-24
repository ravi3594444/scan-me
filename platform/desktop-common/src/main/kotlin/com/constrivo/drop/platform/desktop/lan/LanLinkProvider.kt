package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.core.transfer.net.TcpSocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The LAN rung of the transport ladder on a desktop (architecture §4 LAN check, §7.4 streams, §8): the device that
 * hosts (the sender, S5) listens on the LAN interface [bindAddress] with an ephemeral port, which its `LinkReady`
 * announces; the joiner connects to the address and port of that `LinkReady` (never to an unauthenticated mDNS
 * endpoint, [JoinRequest]). Every connection is a [TcpDataChannel] with the §7.4 socket options; the engine
 * authenticates each one with its `StreamOpen` (S7).
 *
 * Nothing changes networks on the LAN, so [ActiveLink.teardown] only closes the listener and the streams (F‑E11 has
 * nothing to restore). Only [LinkMode.LAN] is supported, as host and joiner.
 */
class LanLinkProvider(
    private val bindAddress: InetAddress,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: TcpSocketFactory = TcpSocketFactory.DEFAULT,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
) : WifiLinkProvider {
    init {
        require(!bindAddress.isAnyLocalAddress) { "bind the LAN link to the interface address, never the wildcard" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
    }

    override val kind: LinkKind = LinkKind.LAN

    /** Links this provider brought up and has not torn down yet. */
    val activeLinks: List<ActiveLink> get() = links.filter { !it.isTornDown }

    private val links = CopyOnWriteArrayList<LanLink>()

    override fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean = mode == LinkMode.LAN

    /**
     * Listens on [bindAddress] with an ephemeral port.
     *
     * @throws IOException when the port cannot be bound.
     */
    override suspend fun host(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        require(request.mode == LinkMode.LAN) { "the LAN provider hosts only the LAN rung, not ${request.mode}" }
        val listener = TcpListener(InetSocketAddress(bindAddress, 0), LinkKind.LAN, io)
        val link = LanLink(LinkRole.HOST, listener)
        links += link
        onUp(link)
        return link
    }

    /** The LAN needs no joining: the link exists at once and connects to the host's `LinkReady` address. */
    override suspend fun join(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        require(request.mode == LinkMode.LAN) { "the LAN provider joins only the LAN rung, not ${request.mode}" }
        val link = LanLink(LinkRole.JOIN, null)
        links += link
        onUp(link)
        return link
    }

    /** One LAN link: a listener when hosting, and every stream opened or accepted over it. */
    private inner class LanLink(
        override val role: LinkRole,
        private val listener: TcpListener?,
    ) : ActiveLink {
        private val channels = CopyOnWriteArrayList<DataChannel>()
        private val tornDown = AtomicBoolean(false)

        val isTornDown: Boolean get() = tornDown.get()

        override val kind: LinkKind = LinkKind.LAN
        override val mode: LinkMode = LinkMode.LAN
        override val frequencyMhz: Int? = null
        override val credentials: WifiCredentials? = null
        override val localAddress: String? = listener?.let { bindAddress.hostAddress }
        override val localPort: Int? = listener?.port

        override suspend fun connect(
            address: String,
            port: Int,
        ): DataChannel {
            if (tornDown.get()) throw IOException("the LAN link is gone")
            return track(TcpDataChannel.connect(address, port, LinkKind.LAN, connectTimeoutMillis, factory = socketFactory, io = io))
        }

        override suspend fun accept(port: Int): DataChannel {
            val server = listener ?: throw IOException("a joined LAN link accepts no streams")
            if (port != server.port) throw IOException("port $port is not this link's ${server.port}")
            return track(server.accept())
        }

        override suspend fun teardown() {
            if (!tornDown.compareAndSet(false, true)) return
            listener?.close()
            for (channel in channels) runCatching { channel.close() }
            channels.clear()
            links.remove(this)
        }

        private suspend fun track(channel: DataChannel): DataChannel {
            channels += channel
            if (tornDown.get()) {
                channels.remove(channel)
                channel.close()
                throw IOException("the LAN link is gone")
            }
            return channel
        }

        override fun toString(): String = "LanLink($role, ${localAddress ?: "join"}:${localPort ?: "-"})"
    }
}
