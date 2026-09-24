package com.constrivo.drop.platform.android.wifi

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** One address of a network interface with its prefix length (`java.net.InterfaceAddress`, `android.net.LinkAddress`). */
data class InterfaceAddressInfo(
    val address: InetAddress,
    val prefixLength: Int,
) {
    val isIpv4: Boolean get() = IpPrefix.isIpv4(address)

    /** The subnet of this address, or null when the prefix length does not fit the family. */
    val prefix: IpPrefix? get() = IpPrefix.of(address, prefixLength)
}

/** One network interface as the kernel reports it (`java.net.NetworkInterface`). */
data class InterfaceSnapshot(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<InterfaceAddressInfo>,
) {
    /** The first IPv4 address, the one a link binds to and announces. */
    val ipv4: InterfaceAddressInfo? get() = addresses.firstOrNull { it.isIpv4 }
}

/** Reads the interfaces; a seam over `java.net.NetworkInterface` for the JVM tests. */
fun interface InterfaceLookup {
    /** Every interface now; empty when the platform refuses to list them. Never throws. */
    fun snapshot(): List<InterfaceSnapshot>
}

/** [InterfaceLookup] over `java.net.NetworkInterface`, which works the same on Android and the JVM. */
object SystemInterfaceLookup : InterfaceLookup {
    override fun snapshot(): List<InterfaceSnapshot> =
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull(::snapshotOf)
        } catch (_: SocketException) {
            emptyList()
        } catch (_: RuntimeException) {
            // A vendor quirk (or a SecurityException on some builds) must not take the link down: no interfaces known.
            emptyList()
        }

    private fun snapshotOf(nif: NetworkInterface): InterfaceSnapshot? =
        try {
            InterfaceSnapshot(
                name = nif.name,
                isUp = nif.isUp,
                isLoopback = nif.isLoopback,
                addresses =
                    nif.interfaceAddresses.mapNotNull { ia ->
                        ia?.address?.let { InterfaceAddressInfo(it, ia.networkPrefixLength.toInt()) }
                    },
            )
        } catch (_: SocketException) {
            null
        }
}

/**
 * Finds the interface addresses a link binds to (architecture §8): the Wi-Fi Direct group's interface by the name the
 * group reports, and the local-only hotspot's, which Android does not name to apps. Pure functions over snapshots.
 */
object LinkInterfaces {
    /**
     * Name prefixes of interfaces that are never a local-only hotspot: Wi-Fi Direct groups, cellular modems, VPN and
     * tunnel devices, 464xlat, Bluetooth and USB tethering, loopback and dummies.
     */
    val NOT_HOTSPOT_PREFIXES: List<String> =
        listOf(
            "p2p",
            "rmnet",
            "ccmni",
            "ccemni",
            "rev_rmnet",
            "seth",
            "tun",
            "ppp",
            "ipsec",
            "v4-",
            "clat",
            "dummy",
            "lo",
            "sit",
            "ip6",
            "bt-",
            "rndis",
            "usb",
            "ncm",
            "eth",
        )

    /** Names that local-only hotspots use on Android builds (`wlan1`, `swlan0`, `ap0`, `softap0`, `wifi_ap`). */
    private val WIFI_AP_NAME = Regex("^(wlan|swlan|ap|softap|wifi|wigig)[a-z_]*\\d*$")

    /** [name]'s interface in [snapshot], or null. */
    fun find(
        snapshot: List<InterfaceSnapshot>,
        name: String?,
    ): InterfaceSnapshot? = name?.let { n -> snapshot.firstOrNull { it.name == n } }

    /** The on-link prefixes of [iface] (both families), for [LinkDialPolicy]. */
    fun prefixes(iface: InterfaceSnapshot?): List<IpPrefix> = iface?.addresses?.mapNotNull { it.prefix }.orEmpty()

    /** The up, non-loopback interface one of whose prefixes contains [address] (a group's subnet), or null. */
    fun containing(
        snapshot: List<InterfaceSnapshot>,
        address: InetAddress,
    ): InterfaceSnapshot? =
        snapshot.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback && iface.addresses.any { it.prefix?.contains(address) == true }
        }

    /**
     * The interface a local-only hotspot added, from snapshots taken [before] it was started and [after] it came up.
     *
     * Candidates are interfaces that are up, not loopback, carry a private IPv4 address and are neither in
     * [excluded] (the interfaces of the networks Android knows: the station, cellular, a joined network, since a
     * hotspot is not one) nor named like a non-Wi-Fi device ([NOT_HOTSPOT_PREFIXES]). An interface whose IPv4 address
     * is new since [before] wins over one that had it already (a hotspot shared with another app was up before), and a
     * Wi-Fi access-point name over another name; then the lowest name, so the choice is deterministic. Null when there
     * is no candidate.
     */
    fun hotspotInterface(
        before: List<InterfaceSnapshot>,
        after: List<InterfaceSnapshot>,
        excluded: Set<String>,
    ): InterfaceSnapshot? {
        val previous =
            before.associate { iface ->
                iface.name to
                    iface.addresses.map { IpLiteral.canonicalBytes(it.address).toList() }.toSet()
            }
        val candidates =
            after.filter { iface ->
                iface.isUp && !iface.isLoopback && iface.name !in excluded && NOT_HOTSPOT_PREFIXES.none { iface.name.startsWith(it) } &&
                    iface.addresses.any { it.isIpv4 && IpLiteral.isPrivate(it.address) }
            }
        return candidates.minWithOrNull(
            compareBy<InterfaceSnapshot>(
                { iface ->
                    val had = previous[iface.name].orEmpty()
                    val fresh = iface.addresses.any { it.isIpv4 && IpLiteral.canonicalBytes(it.address).toList() !in had }
                    if (fresh) 0 else 1
                },
                { iface -> if (WIFI_AP_NAME.matches(iface.name)) 0 else 1 },
                { iface -> iface.name },
            ),
        )
    }
}
