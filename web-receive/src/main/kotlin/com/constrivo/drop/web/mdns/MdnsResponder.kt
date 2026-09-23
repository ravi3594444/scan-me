package com.constrivo.drop.web.mdns

import com.constrivo.drop.core.discovery.AppIdentity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey

/**
 * A minimal mDNS responder so that `drop.local` resolves on a computer that joined the phone's network (spec change
 * N15: `NsdManager` cannot publish a custom host name before Android 15, and desktops need the same answer when they
 * host the page). It answers A queries for one host name with one IPv4 address and nothing else
 * ([MdnsHostAnswerer] has the rules).
 *
 * It listens on 224.0.0.251:[port] on [networkInterface] only: the socket joins the group on that interface, sends
 * with that interface as the multicast interface and TTL 255 (RFC 6762 §11), and drops packets whose source is not in
 * one of the interface's IPv4 subnets (a wildcard-bound socket can see multicast that arrived on other interfaces).
 * The port is shared with any other responder on the host (`SO_REUSEADDR`, and `SO_REUSEPORT` where available).
 *
 * On [start] it announces the record once (RFC 6762 §8.3); on [close] it sends a goodbye with TTL 0 (§10.1). It does
 * not probe for conflicts: the name lives on the phone's own hotspot or group, where it is the only responder.
 *
 * @param hostName the name to answer, [AppIdentity.MDNS_HOST] by default.
 * @param address the IPv4 address of [networkInterface] that the page is served on.
 * @param port 5353 in production; tests pass another port.
 */
class MdnsResponder(
    private val address: Inet4Address,
    private val networkInterface: NetworkInterface,
    hostName: String = AppIdentity.MDNS_HOST,
    private val port: Int = MDNS_PORT,
    ttlSeconds: Long = MdnsHostAnswerer.DEFAULT_TTL_SECONDS,
    private val announce: Boolean = true,
) : AutoCloseable {
    private val answerer = MdnsHostAnswerer(DnsName.of(hostName), address, ttlSeconds, port)
    private val group = InetSocketAddress(MDNS_GROUP, port)
    private val subnets: List<Pair<ByteArray, Int>> =
        networkInterface.interfaceAddresses
            .filter { it.address is Inet4Address }
            .map { it.address.address to it.networkPrefixLength.toInt() }

    @Volatile private var channel: DatagramChannel? = null
    private var membership: MembershipKey? = null
    private var thread: Thread? = null

    /** Packets answered since [start]; for tests and the support log. */
    @Volatile var answeredCount: Int = 0
        private set

    /** Packets dropped as malformed since [start]. */
    @Volatile var malformedCount: Int = 0
        private set

    /**
     * Opens the socket, joins the group on the interface and starts answering on a daemon thread.
     *
     * @throws IOException when the socket cannot be opened, bound or joined to the group (for example when the
     *   interface does not support multicast).
     * @throws IllegalStateException when already started.
     */
    @Synchronized
    fun start() {
        check(channel == null) { "already started" }
        val ch = DatagramChannel.open(StandardProtocolFamily.INET)
        try {
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            if (StandardSocketOptions.SO_REUSEPORT in ch.supportedOptions()) {
                runCatching { ch.setOption(StandardSocketOptions.SO_REUSEPORT, true) }
            }
            // Multicast is only delivered to a socket bound to the wildcard address (or the group) on most systems.
            ch.bind(InetSocketAddress(port))
            ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_TTL, MULTICAST_TTL)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
            membership = ch.join(MDNS_GROUP, networkInterface)
        } catch (e: IOException) {
            ch.close()
            throw e
        } catch (e: UnsupportedOperationException) {
            ch.close()
            throw IOException("multicast is not supported here", e)
        }
        channel = ch
        thread =
            Thread({ receiveLoop(ch) }, "drop-mdns").apply {
                isDaemon = true
                start()
            }
        if (announce) send(ch, DnsCodec.encode(answerer.announcement()), group)
    }

    /** Sends the goodbye, leaves the group and stops the thread. Idempotent. */
    @Synchronized
    override fun close() {
        val ch = channel ?: return
        channel = null
        if (announce) send(ch, DnsCodec.encode(answerer.goodbye()), group)
        runCatching { membership?.drop() }
        runCatching { ch.close() }
        thread?.join(JOIN_TIMEOUT_MILLIS)
        thread = null
    }

    private fun receiveLoop(ch: DatagramChannel) {
        val buffer = ByteBuffer.allocate(MAX_PACKET)
        while (true) {
            buffer.clear()
            val source =
                try {
                    ch.receive(buffer) as? InetSocketAddress ?: continue
                } catch (_: AsynchronousCloseException) {
                    return
                } catch (_: ClosedChannelException) {
                    return
                } catch (_: IOException) {
                    if (!ch.isOpen) return
                    continue
                }
            if (!isOnLink(source.address)) continue
            val query =
                try {
                    DnsCodec.decode(buffer.array(), 0, buffer.position())
                } catch (_: DnsFormatException) {
                    malformedCount++
                    continue
                }
            val reply = answerer.answer(query, source.port) ?: continue
            send(ch, DnsCodec.encode(reply.message), if (reply.unicast) source else group)
            answeredCount++
        }
    }

    /** Whether [source] is in one of the interface's IPv4 subnets. */
    internal fun isOnLink(source: InetAddress): Boolean {
        val bytes = (source as? Inet4Address)?.address ?: return false
        return subnets.any { (network, prefix) -> samePrefix(bytes, network, prefix) }
    }

    private fun send(
        ch: DatagramChannel,
        bytes: ByteArray,
        target: InetSocketAddress,
    ) {
        try {
            ch.send(ByteBuffer.wrap(bytes), target)
        } catch (_: IOException) {
            // Best effort, like every mDNS packet: the querier asks again.
        }
    }

    companion object {
        const val MDNS_PORT = 5353
        val MDNS_GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        private const val MULTICAST_TTL = 255
        private const val MAX_PACKET = 9000
        private const val JOIN_TIMEOUT_MILLIS = 2000L

        internal fun samePrefix(
            a: ByteArray,
            b: ByteArray,
            prefixLength: Int,
        ): Boolean {
            if (a.size != b.size) return false
            val bits = prefixLength.coerceIn(0, a.size * 8)
            for (i in 0 until bits / 8) if (a[i] != b[i]) return false
            val rest = bits % 8
            if (rest == 0) return true
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (a[bits / 8].toInt() and mask) == (b[bits / 8].toInt() and mask)
        }
    }
}
