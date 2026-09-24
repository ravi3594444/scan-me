package com.constrivo.drop.platform.desktop.lan

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** One network interface as [LanInterfaces.select] sees it (a snapshot of [NetworkInterface]). */
data class LanInterfaceInfo(
    val name: String,
    val index: Int,
    val up: Boolean,
    val loopback: Boolean,
    val virtual: Boolean,
    val pointToPoint: Boolean,
    val supportsMulticast: Boolean,
    val addresses: List<InetAddress>,
)

/** The interface the LAN path uses (architecture §5.4, §8 "LAN discovery"): its name and the IPv4 address to bind. */
data class LanSelection(
    val interfaceName: String,
    val address: InetAddress,
)

/**
 * Picks the network interface for mDNS and the LAN sockets (architecture §8; F‑A3, F‑H4). The discovery socket, the
 * control listener, the LAN data links and the browser receive server all bind this one address, so nothing is
 * announced or served on another network the computer is on (a VPN, a container bridge).
 *
 * An interface qualifies when it is up, not loopback, not point-to-point (VPN tunnels), not a JVM sub-interface,
 * supports multicast and has an IPv4 address; interfaces whose names mark them as virtual (Docker, libvirt, VMware,
 * VirtualBox, WireGuard, Tailscale, ZeroTier, Apple's AWDL, …) are skipped. Among the rest a private address
 * (RFC 1918) beats a public one, which beats a link-local one (169.254/16, no DHCP), then the lowest interface index
 * wins, so the choice is stable across calls. A [preferredName] that qualifies wins outright (a setting for
 * multi-homed machines).
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
                    )
                } catch (_: SocketException) {
                    null
                }
            }
        } catch (_: SocketException) {
            emptyList()
        }

    /** Whether an interface name marks a virtual or tunnel device (case-insensitive prefix match). */
    fun isVirtualName(name: String): Boolean {
        val lower = name.lowercase()
        return VIRTUAL_PREFIXES.any { lower.startsWith(it) }
    }

    /** The interface and address to use, or null when none qualifies (no network: the radar shows no LAN peers). */
    fun select(
        interfaces: List<LanInterfaceInfo>,
        preferredName: String? = null,
    ): LanSelection? {
        val eligible =
            interfaces.mapNotNull { nic ->
                if (!nic.up || nic.loopback || nic.virtual || nic.pointToPoint || !nic.supportsMulticast) return@mapNotNull null
                val v4 = nic.addresses.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                val best = v4.minByOrNull(::rank) ?: return@mapNotNull null
                Triple(nic, best, rank(best))
            }
        preferredName?.let { wanted ->
            eligible.firstOrNull { it.first.name == wanted }?.let { return LanSelection(it.first.name, it.second) }
        }
        return eligible
            .filter { !isVirtualName(it.first.name) }
            .minWithOrNull(compareBy<Triple<LanInterfaceInfo, Inet4Address, Int>> { it.third }.thenBy { it.first.index })
            ?.let { LanSelection(it.first.name, it.second) }
    }

    /** The interface and address of this machine, as [select] picks them. */
    fun selectCurrent(preferredName: String? = null): LanSelection? = select(current(), preferredName)

    /** 0 for a private address, 1 for a public one, 2 for link-local. */
    private fun rank(address: Inet4Address): Int =
        when {
            address.isSiteLocalAddress -> 0
            address.isLinkLocalAddress -> 2
            else -> 1
        }
}
