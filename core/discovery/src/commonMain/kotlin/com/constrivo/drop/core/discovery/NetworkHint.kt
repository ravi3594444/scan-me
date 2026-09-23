package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlin.jvm.JvmInline

/**
 * The 4-byte "same network?" hint of the beacon (architecture §5.1 offset 20 as changed by spec change N6).
 *
 * All-zero ([NONE]) means "not connected to a Wi‑Fi network, or nothing to derive a hint from". A non-zero hint
 * always comes with capability bit [Capabilities.Flag.CONNECTED_TO_WIFI] and vice versa (§5.2 bit 11).
 *
 * The hint is a weak prior, never evidence, in both directions:
 * - Equal hints do not mean a shared network. On an IPv4-only network the input is just the gateway and DHCP server
 *   addresses, so every network on the same factory default (`192.168.1.1`, `192.168.0.1`, `10.0.0.1`) and every
 *   Android or iPhone hotspot (`192.168.43.1`, `172.20.10.1`) gives the same hint. Collisions are systematic, not
 *   1 in 2³².
 * - Different hints do not mean different networks: two devices on one LAN disagree when one of them lacks IPv6, has
 *   not learnt the DHCP server address, or lists a different gateway.
 *
 * So the transport ladder (WP5) may use it only to order its attempts; whether two devices share a LAN is decided by
 * mDNS reachability (N6). Per-network entropy that needs no location permission would reduce the collisions (the
 * DHCP domain, the router's IPv6 link-local address, which already enters as a gateway when IPv6 is present) but not
 * the disagreements, so the derivation stays as it is.
 */
@JvmInline
value class NetworkHint(
    val bits: Int,
) {
    val isNone: Boolean get() = bits == 0

    /** The four bytes as they appear in the beacon (big-endian, i.e. the first four bytes of the digest in order). */
    fun toByteArray(): ByteArray = ByteArray(SIZE).also { Bytes.writeBigEndian(bits.toLong(), it, 0, SIZE) }

    fun toHex(): String = Bytes.hex(bits.toLong() and 0xFFFF_FFFFL, SIZE * 2)

    override fun toString(): String = "NetworkHint(${toHex()})"

    companion object {
        const val SIZE: Int = 4
        val NONE: NetworkHint = NetworkHint(0)

        /** Reads four bytes at [offset]. The caller guarantees `offset + 4 <= bytes.size`. */
        fun fromBytes(
            bytes: ByteArray,
            offset: Int = 0,
        ): NetworkHint {
            require(offset >= 0 && offset + SIZE <= bytes.size) { "need $SIZE bytes at offset $offset" }
            return NetworkHint(Bytes.readBigEndian(bytes, offset, SIZE).toInt())
        }

        private val DOMAIN = "drop-net-v1".encodeToByteArray()
        private const val TAG_GATEWAY: Byte = 1
        private const val TAG_DHCP_SERVER: Byte = 2
        private const val TAG_IPV6_PREFIX: Byte = 3

        /**
         * Derives the hint from link properties that need no location permission (N6), unlike the BSSID of the
         * original §5.1, which Android, recent Windows and macOS hide without it.
         *
         * `hint = SHA‑256("drop-net-v1" ‖ items)[0..4]`, where the items are, in this order and each encoded as
         * `tag ‖ length ‖ bytes`:
         * 1. tag `0x01`: every default-route gateway (4 or 16 bytes), sorted and de-duplicated;
         * 2. tag `0x02`: the DHCP server (4 or 16 bytes);
         * 3. tag `0x03`: every IPv6 /64 prefix of the link's global or unique-local addresses (8 bytes), sorted and
         *    de-duplicated.
         *
         * Absent items are simply left out, so the input is unambiguous and independent of the order in which the
         * platform lists addresses. Unspecified, loopback and multicast addresses are ignored everywhere; link-local
         * and IPv4 addresses are ignored for the prefix. With nothing left, or when [link] is null (not connected),
         * the result is [NONE]. A digest whose first four bytes are all zero maps to `00000001`, so a connected
         * device never looks disconnected.
         */
        fun derive(
            crypto: CryptoProvider,
            link: NetworkLinkInfo?,
        ): NetworkHint {
            if (link == null) return NONE
            val items = ArrayList<ByteArray>()

            fun add(
                tag: Byte,
                value: ByteArray,
            ) {
                items += byteArrayOf(tag, value.size.toByte()) + value
            }
            link.gateways.filter(::usable).distinct().sorted().forEach { add(TAG_GATEWAY, it.toByteArray()) }
            link.dhcpServer?.takeIf(::usable)?.let { add(TAG_DHCP_SERVER, it.toByteArray()) }
            link.ipv6Addresses
                .filter { !it.isIpv4 && usable(it) && !it.isLinkLocal }
                .map { Prefix64(it.toByteArray().copyOfRange(0, 8)) }
                .distinct()
                .sorted()
                .forEach { add(TAG_IPV6_PREFIX, it.bytes) }
            if (items.isEmpty()) return NONE
            var input = DOMAIN
            for (item in items) input += item
            val bits = Bytes.readBigEndian(crypto.sha256(input), 0, SIZE).toInt()
            return NetworkHint(if (bits == 0) 1 else bits)
        }

        private fun usable(a: IpAddress): Boolean = !a.isUnspecified && !a.isLoopback && !a.isMulticast
    }

    private class Prefix64(
        val bytes: ByteArray,
    ) : Comparable<Prefix64> {
        override fun compareTo(other: Prefix64): Int {
            for (i in bytes.indices) {
                val d = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return 0
        }

        override fun equals(other: Any?): Boolean = other is Prefix64 && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/**
 * The link facts a platform reads for [NetworkHint.derive] without location permission: on Android from
 * `LinkProperties` (default routes, `getDhcpServerAddress()`, link addresses), on desktops from the OS routing
 * table and DHCP lease. Pass what is known; every part may be empty.
 */
class NetworkLinkInfo(
    gateways: Collection<IpAddress> = emptyList(),
    val dhcpServer: IpAddress? = null,
    ipv6Addresses: Collection<IpAddress> = emptyList(),
) {
    val gateways: List<IpAddress> = gateways.toList()
    val ipv6Addresses: List<IpAddress> = ipv6Addresses.toList()
}
