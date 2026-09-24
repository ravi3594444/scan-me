package com.constrivo.drop.platform.android.capability

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.IpAddress
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.NetworkLinkInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** N6: the network hint inputs from Android link properties (gateways, DHCP server, IPv6 /64 prefixes). */
class NetworkLinkExtractionTest {
    private val crypto = JcaCryptoProvider()

    private fun ip(text: String): ByteArray = IpAddress.parse(text).toByteArray()

    private val routes =
        listOf(
            RouteFacts(isDefault = true, gateway = ip("192.168.1.1")),
            RouteFacts(isDefault = true, gateway = ip("fe80::1")),
            // The on-link subnet route has no gateway; a non-default route's gateway is not the network's router.
            RouteFacts(isDefault = false, gateway = null),
            RouteFacts(isDefault = false, gateway = ip("10.9.9.9")),
            RouteFacts(isDefault = true, gateway = null),
        )

    private val addresses = listOf(ip("192.168.1.23"), ip("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), ip("fe80::1234"))

    @Test
    fun extractsDefaultGatewaysTheDhcpServerAndIpv6Addresses() {
        val info = NetworkLinkExtraction.linkInfo(routes, ip("192.168.1.1"), addresses)
        assertEquals(listOf(IpAddress.parse("192.168.1.1"), IpAddress.parse("fe80::1")), info.gateways)
        assertEquals(IpAddress.parse("192.168.1.1"), info.dhcpServer)
        // IPv4 link addresses carry no prefix entropy and are left out; link-local IPv6 is filtered by the derivation.
        assertEquals(listOf(IpAddress.parse("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), IpAddress.parse("fe80::1234")), info.ipv6Addresses)
    }

    @Test
    fun malformedAddressBytesAreSkippedNeverThrown() {
        val info =
            NetworkLinkExtraction.linkInfo(
                routes = listOf(RouteFacts(true, ByteArray(5)), RouteFacts(true, ByteArray(0)), RouteFacts(true, ip("10.0.0.1"))),
                dhcpServer = ByteArray(3),
                linkAddresses = listOf(ByteArray(15), ByteArray(17), ip("2001:db8::1")),
            )
        assertEquals(listOf(IpAddress.parse("10.0.0.1")), info.gateways)
        assertNull(info.dhcpServer)
        assertEquals(listOf(IpAddress.parse("2001:db8::1")), info.ipv6Addresses)
    }

    @Test
    fun theHintDoesNotDependOnTheOrderAndroidListsThingsIn() {
        val a = NetworkLinkExtraction.linkInfo(routes, ip("192.168.1.1"), addresses)
        val b = NetworkLinkExtraction.linkInfo(routes.reversed(), ip("192.168.1.1"), addresses.reversed())
        assertEquals(NetworkHint.derive(crypto, a), NetworkHint.derive(crypto, b))
        // Another /64 on the same router is another network.
        val other = NetworkLinkExtraction.linkInfo(routes, ip("192.168.1.1"), listOf(ip("2001:db8:1:3::1")))
        assertNotEquals(NetworkHint.derive(crypto, a), NetworkHint.derive(crypto, other))
    }

    @Test
    fun anEmptyLinkGivesNoHint() {
        val info = NetworkLinkExtraction.linkInfo(emptyList(), null, emptyList())
        assertTrue(info.gateways.isEmpty() && info.ipv6Addresses.isEmpty())
        assertEquals(NetworkHint.NONE, NetworkHint.derive(crypto, info))
    }

    @Test
    fun theStationIsTheDefaultNetworkElseTheLowestHandle() {
        val one = WifiNetworkSnapshot(handle = 700, frequencyMhz = 2412, link = NetworkLinkInfo())
        val two = WifiNetworkSnapshot(handle = 300, frequencyMhz = 5180, link = null)
        assertEquals(one, NetworkLinkExtraction.chooseStation(listOf(one, two), defaultHandle = 700))
        assertEquals(two, NetworkLinkExtraction.chooseStation(listOf(one, two), defaultHandle = 999))
        assertEquals(two, NetworkLinkExtraction.chooseStation(listOf(one, two), defaultHandle = null))
        assertNull(NetworkLinkExtraction.chooseStation(emptyList(), defaultHandle = 700))
    }

    @Test
    fun stationFactsFillInAMissingLink() {
        val facts = NetworkLinkExtraction.stationFacts(WifiNetworkSnapshot(1, 5500, null))!!
        assertEquals(5500, facts.frequencyMhz)
        assertTrue(facts.link.gateways.isEmpty())
        assertNull(NetworkLinkExtraction.stationFacts(null))
    }
}
