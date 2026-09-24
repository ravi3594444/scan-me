package com.constrivo.drop.platform.desktop.lan

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanInterfacesTest {
    private fun nic(
        name: String,
        index: Int,
        vararg addresses: String,
        up: Boolean = true,
        loopback: Boolean = false,
        virtual: Boolean = false,
        pointToPoint: Boolean = false,
        multicast: Boolean = true,
    ) = LanInterfaceInfo(name, index, up, loopback, virtual, pointToPoint, multicast, addresses.map { InetAddress.getByName(it) })

    @Test
    fun `a private IPv4 address on a physical interface wins`() {
        val picked =
            LanInterfaces.select(
                listOf(
                    nic("lo", 1, "127.0.0.1", loopback = true),
                    nic("docker0", 2, "172.17.0.1"),
                    nic("wg0", 3, "10.8.0.2"),
                    nic("tun0", 4, "10.9.0.2", pointToPoint = true),
                    nic("eth0", 5, "fe80::1", "203.0.113.7"),
                    nic("wlan0", 6, "fe80::2", "192.168.1.23"),
                ),
            )
        assertEquals(LanSelection("wlan0", InetAddress.getByName("192.168.1.23")), picked)
    }

    @Test
    fun `public beats link-local, and the lowest index breaks ties`() {
        assertEquals(
            "eth1",
            LanInterfaces.select(listOf(nic("eth0", 2, "169.254.10.1"), nic("eth1", 3, "203.0.113.9")))?.interfaceName,
        )
        assertEquals(
            "eth0",
            LanInterfaces.select(listOf(nic("eth1", 3, "192.168.1.5"), nic("eth0", 2, "10.0.0.5")))?.interfaceName,
        )
    }

    @Test
    fun `down, multicast-less, IPv6-only and virtual interfaces never qualify`() {
        val none =
            listOf(
                nic("eth0", 2, "192.168.1.5", up = false),
                nic("eth1", 3, "192.168.1.6", multicast = false),
                nic("eth2", 4, "fe80::5"),
                nic("eth3", 5, "192.168.1.8", virtual = true),
                nic("vboxnet0", 6, "192.168.56.1"),
            )
        assertNull(LanInterfaces.select(none))
    }

    @Test
    fun `a preferred interface that qualifies wins, even one with a virtual-looking name`() {
        val list = listOf(nic("wlan0", 2, "192.168.1.23"), nic("br-lan", 3, "10.1.1.1"))
        assertEquals("br-lan", LanInterfaces.select(list, preferredName = "br-lan")?.interfaceName)
        assertEquals("wlan0", LanInterfaces.select(list, preferredName = "missing")?.interfaceName)
    }

    @Test
    fun `virtual names are recognised case-insensitively`() {
        assertTrue(LanInterfaces.isVirtualName("Docker0"))
        assertTrue(LanInterfaces.isVirtualName("utun3"))
        assertEquals(false, LanInterfaces.isVirtualName("en0"))
        assertEquals(false, LanInterfaces.isVirtualName("wlp2s0"))
    }

    @Test
    fun `listing this machine's interfaces never throws`() {
        LanInterfaces.current()
        LanInterfaces.selectCurrent()
    }
}
