package com.constrivo.drop.web.mdns

import com.constrivo.drop.web.FakeMonotonicClock
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The responder on a real socket: it joins the mDNS group on a multicast-capable interface (with multicast loopback
 * on) and answers a query sent from this process. Uses a random port instead of 5353, so it neither disturbs nor is
 * disturbed by a system responder. Skipped when no interface can loop multicast back (containers without multicast).
 */
class MdnsResponderLoopbackTest {
    private val port = 20_000 + Random.nextInt(20_000)

    @Test
    fun answersLegacyAndMulticastQueries() {
        val (iface, address) = multicastInterface() ?: return assumeTrue(false, "no multicast-capable IPv4 interface")
        assumeTrue(multicastLoops(iface), "multicast does not loop back on ${iface.name}")
        val clock = FakeMonotonicClock()
        MdnsResponder(address, iface, "drop.local", port = port, announce = true, clock = clock).use { responder ->
            responder.start()

            // Legacy unicast query from an ephemeral port: the reply comes back to this socket with our id.
            DatagramChannel.open(StandardProtocolFamily.INET).use { client ->
                client.bind(InetSocketAddress(address, 0))
                client.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
                client.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
                val query = DnsMessage(0x4242, 0, listOf(DnsQuestion(DnsName.of("drop.local"), DnsType.A)))
                client.send(ByteBuffer.wrap(DnsCodec.encode(query)), InetSocketAddress(MdnsResponder.MDNS_GROUP, port))
                val reply = receive(client) { it.id == 0x4242 }
                assertEquals(0x8400, reply.flags)
                assertEquals(address.hostAddress, reply.answers.single().data.toString())
                assertEquals(10L, reply.answers.single().ttl)
            }

            // Standard query from the mDNS port: the reply is multicast to the group (a second after the announcement,
            // which multicast the same records).
            clock.advance(1_000)
            DatagramChannel.open(StandardProtocolFamily.INET).use { member ->
                member.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                member.bind(InetSocketAddress(port))
                member.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
                member.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
                member.join(MdnsResponder.MDNS_GROUP, iface)
                val query = DnsMessage(0, 0, listOf(DnsQuestion(DnsName.of("DROP.local"), DnsType.A)))
                member.send(ByteBuffer.wrap(DnsCodec.encode(query)), InetSocketAddress(MdnsResponder.MDNS_GROUP, port))
                val reply = receive(member) { it.isResponse && it.answers.any { a -> a.type == DnsType.A && a.ttl == 120L } }
                assertTrue(reply.answers.single().cacheFlush)
                assertEquals(DnsType.NSEC, reply.additionals.single().type)
            }
            assertTrue(responder.answeredCount >= 2)

            // Garbage is counted and dropped; the responder keeps answering.
            DatagramChannel.open(StandardProtocolFamily.INET).use { client ->
                client.bind(InetSocketAddress(address, 0))
                client.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
                client.send(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), InetSocketAddress(MdnsResponder.MDNS_GROUP, port))
                val query = DnsMessage(7, 0, listOf(DnsQuestion(DnsName.of("drop.local"), DnsType.A)))
                client.send(ByteBuffer.wrap(DnsCodec.encode(query)), InetSocketAddress(MdnsResponder.MDNS_GROUP, port))
                receive(client) { it.id == 7 }
            }
            assertTrue(responder.malformedCount >= 1)
        }
    }

    @Test
    fun startingTwiceFailsAndCloseIsIdempotent() {
        val (iface, address) = multicastInterface() ?: return assumeTrue(false, "no multicast-capable IPv4 interface")
        val responder = MdnsResponder(address, iface, port = port, announce = false)
        try {
            responder.start()
        } catch (e: IOException) {
            assumeTrue(false, "cannot join the group: ${e.message}")
        }
        kotlin.test.assertFailsWith<IllegalStateException> { responder.start() }
        responder.close()
        responder.close()
    }

    private fun receive(
        channel: DatagramChannel,
        accept: (DnsMessage) -> Boolean,
    ): DnsMessage {
        channel.configureBlocking(false)
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_READ)
            val deadline = System.nanoTime() + 5_000_000_000L
            val buffer = ByteBuffer.allocate(9000)
            while (System.nanoTime() < deadline) {
                selector.select(200)
                selector.selectedKeys().clear()
                while (true) {
                    buffer.clear()
                    channel.receive(buffer) ?: break
                    val message =
                        try {
                            DnsCodec.decode(buffer.array(), 0, buffer.position())
                        } catch (_: DnsFormatException) {
                            continue
                        }
                    if (accept(message)) return message
                }
            }
        }
        throw SocketTimeoutException("no reply within 5 s")
    }

    private fun multicastInterface(): Pair<NetworkInterface, Inet4Address>? =
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && it.supportsMulticast() && !it.isPointToPoint }.getOrDefault(false) }
            .sortedBy { if (it.isLoopback) 1 else 0 }
            .firstNotNullOfOrNull { iface ->
                iface.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.let { iface to it }
            }

    /** Whether a datagram sent to the group on [iface] comes back to a member socket on this host. */
    private fun multicastLoops(iface: NetworkInterface): Boolean =
        try {
            DatagramChannel.open(StandardProtocolFamily.INET).use { member ->
                member.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                member.bind(InetSocketAddress(port + 1))
                member.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface)
                member.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
                member.join(MdnsResponder.MDNS_GROUP, iface)
                member.send(ByteBuffer.wrap(PROBE), InetSocketAddress(MdnsResponder.MDNS_GROUP, port + 1))
                member.configureBlocking(false)
                val buffer = ByteBuffer.allocate(64)
                val deadline = System.nanoTime() + 1_000_000_000L
                var looped = false
                while (!looped && System.nanoTime() < deadline) {
                    buffer.clear()
                    if (member.receive(buffer) != null) {
                        looped = buffer.position() == PROBE.size
                    } else {
                        Thread.sleep(10)
                    }
                }
                looped
            }
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

    private companion object {
        val PROBE = "drop-mdns-probe".toByteArray()
    }
}
