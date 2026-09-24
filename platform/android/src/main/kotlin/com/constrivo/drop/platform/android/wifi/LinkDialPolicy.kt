package com.constrivo.drop.platform.android.wifi

import java.net.InetAddress

/**
 * Which addresses a link may dial (architecture §13, the same rule as the desktop LAN rung). The address comes from the
 * peer's `LinkReady`: the peer is authenticated but not necessarily trusted, so a stranger whose offer was accepted
 * must not be able to make this phone connect to internet hosts or probe other machines on the network.
 *
 * An address passes when it is an IP literal (no name is ever looked up), neither the wildcard nor a multicast group,
 * and:
 * - equals [expected] when that is known (a Wi-Fi Direct client dials only its group owner);
 * - otherwise lies in one of [onLink], the link's own prefixes (a hotspot joiner dials only inside the hotspot's
 *   subnet, a LAN joiner only inside the station's);
 * - with neither known, is a private or link-local address ([IpLiteral.isPrivate]).
 *
 * Loopback is therefore dialable only on a link that is itself on loopback (the JVM tests); a link on a real interface
 * never reaches this phone's own services through a peer's address.
 */
class LinkDialPolicy(
    val onLink: List<IpPrefix> = emptyList(),
    val expected: InetAddress? = null,
) {
    /**
     * The address to dial for [address].
     *
     * @throws LinkAddressRefusedException naming the rule it breaks.
     */
    fun check(address: String): InetAddress {
        val target = IpLiteral.parse(address) ?: throw LinkAddressRefusedException("the LinkReady address $address is not an IP address")
        if (target.isAnyLocalAddress || target.isMulticastAddress) {
            throw LinkAddressRefusedException("the LinkReady address $address is not a host")
        }
        val peer = expected
        when {
            peer != null -> {
                if (!IpLiteral.sameAddress(target, peer)) {
                    throw LinkAddressRefusedException("the LinkReady address $address is not the peer's (${peer.hostAddress})")
                }
            }

            onLink.isNotEmpty() -> {
                if (onLink.none { it.contains(target) }) {
                    throw LinkAddressRefusedException("the LinkReady address $address is not on this link (${onLink.joinToString()})")
                }
            }

            !IpLiteral.isPrivate(target) -> {
                throw LinkAddressRefusedException("the LinkReady address $address is not on a local network")
            }
        }
        return target
    }

    override fun toString(): String = "LinkDialPolicy(onLink=$onLink, expected=${expected?.hostAddress})"
}
