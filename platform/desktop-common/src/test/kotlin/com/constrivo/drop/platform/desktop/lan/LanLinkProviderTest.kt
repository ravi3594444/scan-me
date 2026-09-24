package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanLinkProviderTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun `the host listens on the LAN address, the joiner connects to it, and teardown closes both`() =
        runBlocking<Unit> {
            withTimeout(10_000) {
                val host = LanLinkProvider(loopback)
                val joiner = LanLinkProvider(loopback)
                assertEquals(LinkKind.LAN, host.kind)
                assertTrue(host.supports(LinkMode.LAN, LinkRole.HOST) && host.supports(LinkMode.LAN, LinkRole.JOIN))
                assertFalse(host.supports(LinkMode.P2P_LEGACY, LinkRole.JOIN))

                var hostedUp: ActiveLink? = null
                val hosted = host.host(HostRequest(LinkMode.LAN)) { hostedUp = it }
                assertEquals(hosted, hostedUp, "onUp gets the link before host returns")
                assertEquals(LinkRole.HOST, hosted.role)
                assertEquals(loopback.hostAddress, hosted.localAddress)
                val port = assertNotNull(hosted.localPort)
                assertNull(hosted.frequencyMhz)
                assertNull(hosted.credentials)

                val joined = joiner.join(JoinRequest(LinkMode.LAN, hostAddress = loopback.hostAddress, hostPort = port)) {}
                assertEquals(LinkRole.JOIN, joined.role)
                assertNull(joined.localPort)

                val accepted = async { hosted.accept(port) }
                val out = joined.connect(loopback.hostAddress, port)
                val inbound = accepted.await()
                out.write("hello".toByteArray())
                val buffer = ByteArray(5)
                var read = 0
                while (read < 5) read += inbound.read(buffer, read, 5 - read)
                assertContentEquals("hello".toByteArray(), buffer)
                assertEquals(1, host.activeLinks.size)

                assertFailsWith<IOException> { hosted.accept(port + 1) }
                assertFailsWith<IOException> { joined.accept(port) }

                hosted.teardown()
                hosted.teardown()
                joined.teardown()
                assertTrue(host.activeLinks.isEmpty() && joiner.activeLinks.isEmpty())
                assertEquals(-1, inbound.read(buffer, 0, 1), "streams are closed with the link")
                assertFailsWith<IOException> { joined.connect(loopback.hostAddress, port) }
            }
        }

    @Test
    fun `only the LAN rung is served and never on the wildcard address`() =
        runBlocking<Unit> {
            val provider = LanLinkProvider(loopback)
            assertFailsWith<IllegalArgumentException> { provider.host(HostRequest(LinkMode.HOTSPOT)) {} }
            assertFailsWith<IllegalArgumentException> { LanLinkProvider(InetAddress.getByName("0.0.0.0")) }
        }
}
