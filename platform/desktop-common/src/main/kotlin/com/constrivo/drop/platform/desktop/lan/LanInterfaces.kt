package com.constrivo.drop.platform.desktop.lan

import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * One network interface as [LanInterfaces.select] sees it (a snapshot of [NetworkInterface]).
 *
 * @property displayName what the OS calls the adapter: on Windows the JDK names every adapter generically (`eth7`,
 *   `wlan2`, `net3`), so only this tells a Hyper-V switch or a VPN adapter from the Wi‑Fi card. Equal to [name] where
 *   the OS has no separate display name.
 * @property hardwareAddress the MAC address, or null (loopback, tunnels, or not readable).
 */
data class LanInterfaceInfo(
    val name: String,
    val index: Int,
    val up: Boolean,
    val loopback: Boolean,
    val virtual: Boolean,
    val pointToPoint: Boolean,
    val supportsMulticast: Boolean,
    val addresses: List<InetAddress>,
    val displayName: String = name,
    val hardwareAddress: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is LanInterfaceInfo && name == other.name && index == other.index && up == other.up && loopback == other.loopback &&
            virtual == other.virtual && pointToPoint == other.pointToPoint && supportsMulticast == other.supportsMulticast &&
            addresses == other.addresses && displayName == other.displayName && hardwareAddress.contentEquals(other.hardwareAddress)

    override fun hashCode(): Int = (name.hashCode() * 31 + index) * 31 + addresses.hashCode()
}

/** The interface the LAN path uses (architecture §5.4, §8 "LAN discovery"): its name and the IPv4 address to bind. */
data class LanSelection(
    val interfaceName: String,
    val address: InetAddress,
)

/**
 * Picks the network interface for mDNS and the LAN sockets (architecture §8; F‑A3, F‑H4). The discovery socket, the
 * control listener, the LAN data links and the browser receive server all bind this one address, so nothing is
 * announced or served on another network the computer is on (a VPN, a container bridge, a VM's virtual switch).
 *
 * An interface qualifies when it is up, not loopback, not point-to-point (VPN tunnels), not a JVM sub-interface,
 * supports multicast and has an IPv4 address, and neither its name nor its display name marks it as virtual (Docker,
 * libvirt, VMware, VirtualBox, Hyper-V and WSL switches, TAP, WireGuard, Tailscale, ZeroTier, Npcap, Apple's AWDL, …).
 * Among the rest the interface that carries the default route wins (the address the OS would use to reach the
 * internet, found with a connected UDP socket, which sends nothing), then a private address (RFC 1918) beats a public
 * one, which beats a link-local one (169.254/16, no DHCP), then the lowest interface index, so the choice is stable
 * across calls. A [preferredName] that qualifies wins outright (a setting for multi-homed machines).
 */
object LanInterfaces {
    private val VIRTUAL_PREFIXES =
        listOf(
            "docker",
            "veth",
            "br-",
            "virbr",
            "vmnet",
            "vboxnet",
            "vnic",
            "utun",
            "tun",
            "tap",
            "zt",
            "tailscale",
            "wg",
            "ham",
            "lxc",
            "lxd",
            "cni",
            "flannel",
            "cali",
            "kube",
            "awdl",
            "llw",
            "anpi",
            "bridge",
            "podman",
        )

    /**
     * Words in an adapter's display name that mark a virtual switch or a tunnel (Windows names every adapter
     * generically, so the display name is all there is). A VM guest's own adapter ("Microsoft Hyper-V Network Adapter",
     * "vmxnet3 Ethernet Adapter") is the real network there and does not match.
     */
    private val VIRTUAL_DISPLAY_WORDS =
        listOf(
            "vethernet",
            "hyper-v virtual",
            "virtualbox host-only",
            "vmware virtual",
            "vmware network adapter",
            "tap-windows",
            "tap-",
            "wireguard",
            "wintun",
            "tailscale",
            "zerotier",
            "npcap",
            "openvpn",
            "nordlynx",
            "wi-fi direct virtual",
            "hosted network virtual",
            "bluetooth",
            "fortinet",
            "anyconnect",
            "globalprotect",
            "pangp",
            "juniper",
            "pulse secure",
            "cloudflare warp",
            "docker",
            "loopback",
        )

    /** A documentation address (TEST-NET-1): connecting a UDP socket to it only asks the OS which route it would take. */
    private val ROUTE_PROBE: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private const val ROUTE_PROBE_PORT = 9

    /** The interfaces of this machine; empty when the JVM cannot list them. */
    fun current(): List<LanInterfaceInfo> =
        try {
            NetworkInterface.networkInterfaces().toList().mapNotNull { nic ->
                try {
                    LanInterfaceInfo(
                        name = nic.name,
                        index = nic.index,
                        up = nic.isUp,
                        loopback = nic.isLoopback,
                        virtual = nic.isVirtual,
                        pointToPoint = nic.isPointToPoint,
                        supportsMulticast = nic.supportsMulticast(),
                        addresses = nic.inetAddresses().toList(),
                        displayName = nic.displayName ?: nic.name,
                        hardwareAddress = runCatching { nic.hardwareAddress }.getOrNull(),
                    )
                } catch (_: SocketException) {
                    null
                }
            }
        } catch (_: SocketException) {
            emptyList()
        }

    /**
     * The local address the OS would send internet traffic from (the default route), or null without one. A UDP
     * `connect` only looks the route up; no packet leaves the machine.
     */
    fun defaultRouteAddress(): InetAddress? =
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(ROUTE_PROBE, ROUTE_PROBE_PORT))
                socket.localAddress?.takeIf { !it.isAnyLocalAddress && !it.isLoopbackAddress }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Whether an interface name marks a virtual or tunnel device (case-insensitive prefix match). */
    fun isVirtualName(name: String): Boolean {
        val lower = name.lowercase()
        return VIRTUAL_PREFIXES.any { lower.startsWith(it) }
    }

    /** Whether an adapter's display name marks a virtual switch, a tunnel or a VPN (case-insensitive). */
    fun isVirtualDisplayName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return VIRTUAL_DISPLAY_WORDS.any { lower.contains(it) }
    }

    private fun isVirtual(nic: LanInterfaceInfo): Boolean =
        isVirtualName(nic.name) || (nic.displayName != nic.name && isVirtualDisplayName(nic.displayName))

    /**
     * The interface and address to use, or null when none qualifies (no network: the radar shows no LAN peers).
     * [defaultRoute] is the address of the default route ([defaultRouteAddress]); the interface that owns it wins.
     */
    fun select(
        interfaces: List<LanInterfaceInfo>,
        preferredName: String? = null,
        defaultRoute: InetAddress? = null,
    ): LanSelection? {
        val eligible =
            interfaces.mapNotNull { nic ->
                if (!nic.up || nic.loopback || nic.virtual || nic.pointToPoint || !nic.supportsMulticast) return@mapNotNull null
                val v4 = nic.addresses.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                val best = (v4.firstOrNull { it == defaultRoute } ?: v4.minByOrNull(::rank)) ?: return@mapNotNull null
                Candidate(nic, best, rank(best), best == defaultRoute)
            }
        preferredName?.let { wanted ->
            eligible.firstOrNull { it.nic.name == wanted }?.let { return LanSelection(it.nic.name, it.address) }
        }
        return eligible
            .filter { !isVirtual(it.nic) }
            .minWithOrNull(compareBy<Candidate> { if (it.defaultRoute) 0 else 1 }.thenBy { it.rank }.thenBy { it.nic.index })
            ?.let { LanSelection(it.nic.name, it.address) }
    }

    /** The interface and address of this machine, as [select] picks them. */
    fun selectCurrent(preferredName: String? = null): LanSelection? = select(current(), preferredName, defaultRouteAddress())

    private class Candidate(
        val nic: LanInterfaceInfo,
        val address: Inet4Address,
        val rank: Int,
        val defaultRoute: Boolean,
    )

    /** 0 for a private address, 1 for a public one, 2 for link-local. */
    private fun rank(address: Inet4Address): Int =
        when {
            address.isSiteLocalAddress -> 0
            address.isLinkLocalAddress -> 2
            else -> 1
        }
}
