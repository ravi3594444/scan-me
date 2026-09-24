package com.constrivo.drop.platform.android.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding the interface addresses the links bind to (§8): the group's by name, the hotspot's by elimination. */
class LinkInterfacesTest {
    private val station = iface("wlan0", addr("192.168.1.23", 24), addr("fe80::1", 64))
    private val cellular = iface("rmnet_data0", addr("10.44.0.9", 30))
    private val loopback = iface("lo", addr("127.0.0.1", 8), loopback = true)

    @Test
    fun findsInterfacesByNameAndBySubnet() {
        val group = iface("p2p-wlan0-0", addr("192.168.49.1", 24))
        val snapshot = listOf(loopback, station, group)
        assertEquals(group, LinkInterfaces.find(snapshot, "p2p-wlan0-0"))
        assertNull(LinkInterfaces.find(snapshot, "p2p-wlan0-9"))
        assertNull(LinkInterfaces.find(snapshot, null))
        assertEquals(group, LinkInterfaces.containing(snapshot, ip("192.168.49.77")))
        assertNull(LinkInterfaces.containing(snapshot, ip("172.20.0.1")))
        assertEquals(listOf(IpPrefix(ip("192.168.1.23"), 24), IpPrefix(ip("fe80::1"), 64)), LinkInterfaces.prefixes(station))
        assertEquals(emptyList(), LinkInterfaces.prefixes(null))
        assertEquals(addr("192.168.1.23", 24), station.ipv4)
    }

    @Test
    fun theHotspotIsTheInterfaceThatGainedAPrivateAddress() {
        val before = listOf(loopback, station, cellular)
        val hotspot = iface("swlan0", addr("192.168.87.1", 24))
        val after = before + hotspot
        assertEquals(hotspot, LinkInterfaces.hotspotInterface(before, after, excluded = setOf("wlan0", "rmnet_data0")))
    }

    @Test
    fun knownNetworksAndNonWifiDevicesAreNeverTheHotspot() {
        val group = iface("p2p-wlan0-0", addr("192.168.49.1", 24))
        val usb = iface("rndis0", addr("192.168.42.129", 24))
        val tunnel = iface("tun0", addr("10.8.0.2", 24))
        val newStation = iface("wlan0", addr("192.168.5.5", 24))
        val after = listOf(loopback, newStation, group, usb, tunnel, cellular)
        assertNull(LinkInterfaces.hotspotInterface(listOf(loopback), after, excluded = setOf("wlan0")))
    }

    @Test
    fun aNewAddressWinsOverAnOldOneAndAnApNameOverAnother() {
        val shared = iface("wlan1", addr("192.168.60.1", 24))
        val fresh = iface("ap0", addr("192.168.61.1", 24))
        val before = listOf(station, shared)
        assertEquals(fresh, LinkInterfaces.hotspotInterface(before, listOf(station, shared, fresh), setOf("wlan0")))

        val oddName = iface("xyz0", addr("192.168.62.1", 24))
        val apName = iface("wlan2", addr("192.168.63.1", 24))
        assertEquals(apName, LinkInterfaces.hotspotInterface(emptyList(), listOf(oddName, apName), emptySet()))
        // Same rank: the lowest name, so the choice does not depend on the kernel's order.
        val a = iface("wlan3", addr("192.168.64.1", 24))
        val b = iface("wlan2", addr("192.168.65.1", 24))
        assertEquals(b, LinkInterfaces.hotspotInterface(emptyList(), listOf(a, b), emptySet()))
    }

    @Test
    fun aHotspotSharedWithAnotherAppIsStillFound() {
        val shared = iface("wlan1", addr("192.168.60.1", 24))
        assertEquals(shared, LinkInterfaces.hotspotInterface(listOf(station, shared), listOf(station, shared), setOf("wlan0")))
    }

    @Test
    fun downLoopbackAndPublicInterfacesAreNotCandidates() {
        val down = iface("wlan1", addr("192.168.60.1", 24), up = false)
        val public = iface("wlan2", addr("81.2.69.160", 24))
        val v6only = iface("wlan3", addr("fd00::1", 64))
        assertNull(LinkInterfaces.hotspotInterface(emptyList(), listOf(loopback, down, public, v6only), emptySet()))
    }

    @Test
    fun theSystemLookupNeverThrows() {
        val snapshot = SystemInterfaceLookup.snapshot()
        assertTrue(snapshot.none { it.name.isEmpty() })
        snapshot.firstOrNull { it.isLoopback }?.let { lo -> assertTrue(lo.addresses.any { it.address.isLoopbackAddress }) }
    }
}
