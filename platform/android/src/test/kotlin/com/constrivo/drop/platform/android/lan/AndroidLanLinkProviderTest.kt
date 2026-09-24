package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.platform.android.wifi.InterfaceAddressInfo
import com.constrivo.drop.platform.android.wifi.IpLiteral
import com.constrivo.drop.platform.android.wifi.LinkAddressRefusedException
import com.constrivo.drop.platform.android.wifi.LinkDetails
import com.constrivo.drop.platform.android.wifi.LinkListenerFactory
import com.constrivo.drop.platform.android.wifi.SocketActiveLink
import com.constrivo.drop.platform.android.wifi.SocketBinder
import com.constrivo.drop.platform.android.wifi.WifiLinkError
import com.constrivo.drop.platform.android.wifi.WifiLinkException
import com.constrivo.drop.platform.android.wifi.WifiPermissionContext
import com.constrivo.drop.platform.android.wifi.WifiPermissions
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The LAN rung on Android (F-E4, F-A3, T-15, T-16): which network, bound sockets, dial checks, permissions. */
class AndroidLanLinkProviderTest {
    private class Binder : SocketBinder {
        val bound = CopyOnWriteArrayList<Socket>()

        override fun bind(socket: Socket) {
            bound += socket
        }
    }

    private fun ip(text: String): InetAddress = IpLiteral.parse(text)!!

    private fun network(
        handle: Long,
        transport: LanTransport = LanTransport.WIFI,
        vararg addresses: Pair<String, Int>,
        binder: SocketBinder = Binder(),
    ): LanNetworkSnapshot {
        val name = if (transport == LanTransport.WIFI) "wlan0" else "eth0"
        return LanNetworkSnapshot(handle, transport, name, addresses.map { InterfaceAddressInfo(ip(it.first), it.second) }, binder)
    }

    /** Listeners bind loopback here; the phone would bind the announced address. */
    private val loopbackListeners = LinkListenerFactory { _, kind, io -> TcpListener(InetSocketAddress("127.0.0.1", 0), kind, io) }

    private fun provider(
        networks: List<LanNetworkSnapshot>,
        permissions: WifiPermissionContext = WifiPermissionContext.allGranted(34, 37),
        peer: InetAddress? = null,
    ) = AndroidLanLinkProvider(
        LanNetworks {
            networks
        },
        permissions,
        SystemMonotonicClock,
        peerAddress = peer,
        listenerFactory = loopbackListeners,
    )

    // ---- Selection ----

    @Test
    fun theHostListensOnWifiBeforeEthernetAndNeedsAPrivateIpv4Address() {
        val ethernet = network(1, LanTransport.ETHERNET, "10.0.0.5" to 24)
        val wifi = network(9, LanTransport.WIFI, "192.168.1.23" to 24)
        val wifiLow = network(4, LanTransport.WIFI, "192.168.7.23" to 24)
        val publicOnly = network(2, LanTransport.WIFI, "81.2.69.160" to 24, "2001:db8::5" to 64)
        assertEquals(4, LanNetworkSelector.forHost(listOf(ethernet, wifi, wifiLow))?.handle)
        assertEquals(1, LanNetworkSelector.forHost(listOf(ethernet, publicOnly))?.handle)
        assertNull(LanNetworkSelector.forHost(listOf(publicOnly)))
        assertNull(LanNetworkSelector.forHost(emptyList()))
    }

    @Test
    fun aJoinerPicksTheNetworkThatReachesTheHost() {
        val wifi = network(9, LanTransport.WIFI, "192.168.1.23" to 24)
        val ethernet = network(1, LanTransport.ETHERNET, "10.0.0.5" to 24, "192.168.1.99" to 24)
        assertEquals(9, LanNetworkSelector.forTarget(listOf(ethernet, wifi), ip("192.168.1.40"))?.handle)
        assertEquals(1, LanNetworkSelector.forTarget(listOf(ethernet, wifi), ip("10.0.0.77"))?.handle)
        assertNull(LanNetworkSelector.forTarget(listOf(ethernet, wifi), ip("172.20.1.1")))
    }

    // ---- Links ----

    @Test
    fun theSenderHostsOnItsLanAddress() =
        runBlocking {
            val link = provider(listOf(network(9, LanTransport.WIFI, "192.168.1.23" to 24))).host(HostRequest(LinkMode.LAN)) {}
            assertEquals(LinkKind.LAN, link.kind)
            assertEquals(LinkRole.HOST, link.role)
            assertEquals("192.168.1.23", link.localAddress)
            assertTrue(link.localPort!! > 0)
            assertNull(link.frequencyMhz)
            assertEquals(LinkDetails.Lan(9, "wlan0"), assertIs<SocketActiveLink>(link).details)
            link.teardown()
        }

    @Test
    fun withoutANetworkTheRungFailsTyped() =
        runBlocking {
            val none = provider(emptyList())
            assertEquals(WifiLinkError.NO_NETWORK, assertFailsWith<WifiLinkException> { none.host(HostRequest(LinkMode.LAN)) {} }.error)
            assertEquals(WifiLinkError.NO_NETWORK, assertFailsWith<WifiLinkException> { none.join(JoinRequest(LinkMode.LAN)) {} }.error)
            val elsewhere = provider(listOf(network(9, LanTransport.WIFI, "192.168.1.23" to 24)))
            val e =
                assertFailsWith<WifiLinkException> {
                    elsewhere.join(JoinRequest(LinkMode.LAN, hostAddress = "10.1.1.1", hostPort = 4000)) {}
                }
            assertEquals(WifiLinkError.NO_NETWORK, e.error)
        }

    @Test
    fun android17NeedsTheLocalNetworkPermission() =
        runBlocking {
            val provider = provider(listOf(network(9, LanTransport.WIFI, "192.168.1.23" to 24)), WifiPermissionContext(37, 37, { false }))
            val e = assertFailsWith<WifiLinkException> { provider.host(HostRequest(LinkMode.LAN)) {} }
            assertEquals(WifiLinkError.PERMISSION_MISSING, e.error)
            assertEquals(listOf(WifiPermissions.ACCESS_LOCAL_NETWORK), e.missingPermissions)
        }

    @Test
    fun hostAndJoinerExchangeBytesOnSocketsBoundToTheNetwork() =
        runBlocking {
            val binder = Binder()
            // The station's LAN address plus loopback, which stands in for the LAN on this machine.
            val lan = network(9, LanTransport.WIFI, "192.168.1.23" to 24, "127.0.0.1" to 8, binder = binder)
            val host = provider(listOf(lan)).host(HostRequest(LinkMode.LAN)) {}
            val joiner = provider(listOf(lan)).join(JoinRequest(LinkMode.LAN, hostAddress = "127.0.0.1", hostPort = host.localPort)) {}
            val accepted = async { host.accept(host.localPort!!) }
            val out = joiner.connect("127.0.0.1", host.localPort!!)
            val incoming = accepted.await()
            out.write("lan".encodeToByteArray())
            val buffer = ByteArray(3)
            var read = 0
            while (read < 3) read += incoming.read(buffer, read, 3 - read).also { if (it < 0) throw IOException("closed") }
            assertContentEquals("lan".encodeToByteArray(), buffer)
            assertEquals(1, binder.bound.size, "the joiner's socket was bound to the LAN network")
            assertFailsWith<LinkAddressRefusedException>("not on the LAN") { joiner.connect("172.20.1.1", 4000) }
            joiner.teardown()
            host.teardown()
        }

    @Test
    fun aKnownSessionPeerIsTheOnlyAddressDialled() =
        runBlocking {
            val lan = network(9, LanTransport.WIFI, "192.168.1.23" to 24)
            val joiner = provider(listOf(lan), peer = ip("192.168.1.40")).join(JoinRequest(LinkMode.LAN)) {}
            assertFailsWith<LinkAddressRefusedException> { joiner.connect("192.168.1.41", 4000) }
            joiner.teardown()
        }

    @Test
    fun onlyTheLanRungIsServed() =
        runBlocking {
            val provider = provider(emptyList())
            assertTrue(provider.supports(LinkMode.LAN, LinkRole.HOST))
            assertTrue(provider.supports(LinkMode.LAN, LinkRole.JOIN))
            assertTrue(!provider.supports(LinkMode.HOTSPOT, LinkRole.JOIN))
            assertFailsWith<IllegalArgumentException> { provider.host(HostRequest(LinkMode.HOTSPOT)) {} }
        }
}
