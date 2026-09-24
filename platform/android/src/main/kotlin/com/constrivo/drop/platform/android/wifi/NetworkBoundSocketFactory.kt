package com.constrivo.drop.platform.android.wifi

import android.net.Network
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpSocketFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel

/**
 * Binds a socket to a network before it connects: on Android `android.net.Network.bindSocket(Socket)`, so all traffic
 * of the socket goes over that network whatever the process default is, and a connection fails instead of drifting to
 * mobile data when the network is gone (architecture §7.4, T-15). A seam, so the socket factory is tested on the JVM.
 */
fun interface SocketBinder {
    /** Binds [socket], which is not connected yet. */
    @Throws(IOException::class)
    fun bind(socket: Socket)
}

/** [SocketBinder] for an `android.net.Network` (the joined specifier network, the LAN station network). */
class AndroidNetworkSocketBinder(
    val network: Network,
) : SocketBinder {
    override fun bind(socket: Socket) = network.bindSocket(socket)

    override fun toString(): String = "AndroidNetworkSocketBinder($network)"
}

/**
 * The [TcpSocketFactory] of every Android [com.constrivo.drop.core.ladder.ActiveLink] (architecture §7.4, §8; T-15):
 * each socket is opened in blocking mode, gets the §7.4 buffers of [bufferBytes] (4 MiB) for both directions before
 * it connects, so the TCP window is negotiated large, is bound to the link's network through [binder] and, when
 * given, to the link's own interface address [localAddress].
 *
 * - **Network binding** ([binder]): the joined `WifiNetworkSpecifier` network and the LAN station have an
 *   `android.net.Network`; binding to it keeps the socket on that network.
 * - **Address binding** ([localAddress]): a Wi-Fi Direct group and a local-only hotspot are not networks apps can bind
 *   to. The socket is bound to the link interface's address instead: the kernel routes the group's subnet over that
 *   interface, and once the interface is gone the bind fails (`EADDRNOTAVAIL`), so a connection can never leave over
 *   mobile data with the link's address.
 *
 * At least one binding is required: an unbound link socket would follow the default network. `TCP_NODELAY` stays off:
 * only the engine turns it on, for the connections that carry control ([TcpDataChannel.setLowLatency]).
 *
 * @throws IllegalArgumentException from the constructor without a binding, or for a non-positive buffer size.
 */
class NetworkBoundSocketFactory(
    private val binder: SocketBinder? = null,
    private val localAddress: InetAddress? = null,
    private val bufferBytes: Int = TcpDataChannel.BUFFER_BYTES,
    private val open: () -> SocketChannel = { SocketChannel.open() },
) : TcpSocketFactory {
    init {
        require(binder != null || localAddress != null) { "a link socket must be bound to the link's network or address" }
        require(bufferBytes > 0) { "buffer size must be positive" }
        require(localAddress == null || !localAddress.isAnyLocalAddress) { "bind to the link's address, never the wildcard" }
    }

    /**
     * A new unconnected socket, tuned and bound as the class comment says.
     *
     * @throws IOException when the network or the address cannot be bound (the link is gone); the socket is closed.
     */
    override fun create(): SocketChannel {
        val channel = open()
        try {
            channel.configureBlocking(true)
            channel.setOption(StandardSocketOptions.SO_SNDBUF, bufferBytes)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, bufferBytes)
            binder?.bind(channel.socket())
            localAddress?.let { channel.bind(InetSocketAddress(it, 0)) }
        } catch (e: Throwable) {
            runCatching { channel.close() }
            throw e
        }
        return channel
    }

    override fun toString(): String = "NetworkBoundSocketFactory(binder=$binder, localAddress=${localAddress?.hostAddress})"
}
