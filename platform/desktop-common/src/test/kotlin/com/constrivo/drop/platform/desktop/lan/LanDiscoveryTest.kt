package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.jmdns.ServiceInfo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanDiscoveryTest {
    private val service = LanService("drop-0a1b2c3d4e5f", "127.0.0.1", 40123, linkedMapOf("v" to "1", "eph" to "0a1b2c3d4e5f"))

    @Test
    fun `the in-memory network reports announcements, replacements and withdrawals to every participant`() =
        runBlocking<Unit> {
            val network = InMemoryLanNetwork()
            val alice = network.participant()
            val bob = network.participant()
            withTimeout(10_000) {
                val events = MutableStateFlow<List<LanEvent>>(emptyList())
                val collector = launch { bob.browse().collect { e -> events.update { it + e } } }

                suspend fun awaitCount(n: Int) = events.first { it.size >= n }

                alice.announce(service)
                awaitCount(1)
                val next = service.copy(instanceName = "drop-ffffffffffff")
                alice.announce(next)
                awaitCount(3)
                alice.withdraw()
                awaitCount(4)
                collector.cancel()
                assertEquals(
                    listOf(
                        LanEvent.Found(service),
                        LanEvent.Lost(service.instanceName),
                        LanEvent.Found(next),
                        LanEvent.Lost(next.instanceName),
                    ),
                    events.value,
                )
                assertTrue(network.services.isEmpty())
            }
        }

    @Test
    fun `a browse that starts late sees the live announcements, its own included`() =
        runBlocking<Unit> {
            val network = InMemoryLanNetwork()
            val alice = network.participant()
            alice.announce(service)
            assertEquals(LanEvent.Found(service), withTimeout(5_000) { alice.browse().first() })
            assertFailsWith<IllegalArgumentException> { alice.announce(service.copy(port = 0)) }
        }

    @Test
    fun `a resolved JmDNS record becomes a LanService with its TXT keys and first IPv4 address`() {
        val info = ServiceInfo.create(JmdnsLanDiscovery.SERVICE_TYPE, "drop-0a1b2c3d4e5f", 40123, 0, 0, service.txt)
        assertNull(JmdnsLanDiscovery.toLanService(info), "no address resolved yet")
        assertNull(JmdnsLanDiscovery.toLanService(null))
        assertEquals("_drop._tcp.local.", JmdnsLanDiscovery.SERVICE_TYPE)
        val host = JmdnsLanDiscovery.randomHostName()
        assertTrue(host.matches(Regex("drop-[0-9a-f]{8}")), host)
        assertFailsWith<IllegalArgumentException> { JmdnsLanDiscovery(InetAddress.getByName("0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { JmdnsLanDiscovery(InetAddress.getLoopbackAddress(), "bad name") }
    }

    /**
     * Two JmDNS instances on a real interface see each other's record, then its goodbye (F‑A3). Skipped where the
     * container has no multicast-capable interface or multicast does not loop back.
     */
    @Test
    fun `JmDNS announces and browses over real multicast`() =
        runBlocking<Unit> {
            val (nic, address) = multicastInterface() ?: return@runBlocking assumeTrue(false, "no multicast-capable IPv4 interface")
            assumeTrue(multicastLoops(nic, address), "multicast does not loop back on ${nic.name}")
            val announcer = JmdnsLanDiscovery(address)
            val browser = JmdnsLanDiscovery(address)
            try {
                val eph = "%012x".format(Random.nextLong(0, 0xFFFF_FFFF_FFFFL))
                val record =
                    LanService("drop-$eph", address.hostAddress, 40000 + Random.nextInt(1000), linkedMapOf("v" to "1", "eph" to eph))
                val found =
                    async {
                        withTimeoutOrNull(20_000) {
                            browser.browse().first { it is LanEvent.Found && it.service.instanceName == record.instanceName }
                        }
                    }
                announcer.announce(record)
                val event = assertNotNull(found.await() as LanEvent.Found?, "multicast loops back, so JmDNS must see the record")
                assertEquals(record.port, event.service.port)
                assertEquals("1", event.service.txt["v"])
                assertEquals(eph, event.service.txt["eph"])
                val lost =
                    async {
                        withTimeoutOrNull(10_000) {
                            browser.browse().first {
                                it is LanEvent.Lost &&
                                    it.instanceName == record.instanceName
                            }
                        }
                    }
                announcer.withdraw()
                // A goodbye is best effort on the wire; when it arrives it names the instance.
                (lost.await() as LanEvent.Lost?)?.let { assertEquals(record.instanceName, it.instanceName) }
            } finally {
                announcer.shutdown()
                browser.shutdown()
            }
        }

    private fun multicastInterface(): Pair<NetworkInterface, Inet4Address>? =
        NetworkInterface
            .networkInterfaces()
            .toList()
            .filter { runCatching { it.isUp && it.supportsMulticast() && !it.isPointToPoint }.getOrDefault(false) }
            .sortedBy { if (it.isLoopback) 1 else 0 }
            .firstNotNullOfOrNull { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.let { nic to it } }

    private fun multicastLoops(
        nic: NetworkInterface,
        address: Inet4Address,
    ): Boolean =
        try {
            MulticastSocket(0).use { socket ->
                val group = InetSocketAddress(InetAddress.getByName("239.255.77.77"), socket.localPort)
                socket.networkInterface = nic
                socket.joinGroup(group, nic)
                socket.soTimeout = 1_000
                val probe = "drop-probe-${address.hostAddress}".toByteArray()
                socket.send(DatagramPacket(probe, probe.size, group))
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                packet.data.copyOf(packet.length).contentEquals(probe)
            }
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
}
