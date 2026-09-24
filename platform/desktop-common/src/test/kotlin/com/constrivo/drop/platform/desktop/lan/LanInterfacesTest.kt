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
        display: String = name,
    ) = LanInterfaceInfo(
        name,
        index,
        up,
        loopback,
        virtual,
        pointToPoint,
        multicast,
        addresses.map { InetAddress.getByName(it) },
        displayName = display,
    )

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

    /** Windows names every adapter generically (eth7, wlan2, net3): the display name tells the switches from the Wi‑Fi. */
    @Test
    fun `on Windows, Hyper-V, WSL, VirtualBox, VMware and VPN adapters are recognised by their display names`() {
        val windows =
            listOf(
                nic("eth1", 3, "172.28.16.1", display = "Hyper-V Virtual Ethernet Adapter"),
                nic("eth2", 4, "172.19.0.1", display = "vEthernet (WSL)"),
                nic("eth3", 5, "192.168.56.1", display = "VirtualBox Host-Only Ethernet Adapter"),
                nic("eth4", 6, "192.168.139.1", display = "VMware Virtual Ethernet Adapter for VMnet8"),
                nic("net5", 7, "10.6.0.2", display = "TAP-Windows Adapter V9"),
                nic("net6", 8, "100.101.102.103", display = "Tailscale Tunnel"),
                nic("net7", 9, "10.147.17.5", display = "ZeroTier One [8056c2e21c000001] Port"),
                nic("net8", 10, "10.66.66.2", display = "WireGuard Tunnel #1"),
                nic("wlan2", 14, "192.168.1.40", display = "Intel(R) Wi-Fi 6 AX201 160MHz"),
            )
        assertEquals(LanSelection("wlan2", InetAddress.getByName("192.168.1.40")), LanInterfaces.select(windows))
        assertTrue(LanInterfaces.isVirtualDisplayName("Npcap Loopback Adapter"))
        assertEquals(false, LanInterfaces.isVirtualDisplayName("Realtek PCIe GbE Family Controller"))
        assertEquals(false, LanInterfaces.isVirtualDisplayName("Microsoft Hyper-V Network Adapter"), "a VM guest's own network")
        // Only virtual adapters: no network rather than a VM switch nobody else is on.
        assertNull(LanInterfaces.select(windows.dropLast(1)))
    }

    @Test
    fun `the interface of the default route wins over other private addresses`() {
        val list = listOf(nic("eth0", 2, "10.0.0.5"), nic("wlan0", 3, "192.168.1.23"), nic("eth9", 4, "172.16.5.5"))
        assertEquals("eth0", LanInterfaces.select(list)?.interfaceName, "lowest index without a route")
        val routed = LanInterfaces.select(list, defaultRoute = InetAddress.getByName("192.168.1.23"))
        assertEquals(LanSelection("wlan0", InetAddress.getByName("192.168.1.23")), routed)
        // A route through a virtual adapter (a VPN) does not pull the LAN path onto it.
        val vpn = list + nic("eth5", 1, "10.8.0.2", display = "WireGuard Tunnel")
        assertEquals("eth0", LanInterfaces.select(vpn, defaultRoute = InetAddress.getByName("10.8.0.2"))?.interfaceName)
        LanInterfaces.defaultRouteAddress()
    }

    @Test
    fun `listing this machine's interfaces never throws`() {
        LanInterfaces.current()
        LanInterfaces.selectCurrent()
    }
}
