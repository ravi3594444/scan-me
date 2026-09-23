package com.constrivo.drop.core.discovery

/**
 * An IPv4 or IPv6 address in canonical form, for the network hint (spec change N6). Platforms usually have the raw
 * bytes (`InetAddress.getAddress()`); [parse] exists for desktop tools that report text.
 *
 * Canonical means: IPv4-mapped IPv6 addresses (`::ffff:a.b.c.d`) become the IPv4 address, zone IDs are dropped, and
 * [toString] prints the RFC 5952 form. Ordering is by length (IPv4 first), then unsigned byte order.
 */
class IpAddress private constructor(
    private val bytes: ByteArray,
) : Comparable<IpAddress> {
    val isIpv4: Boolean get() = bytes.size == 4

    fun toByteArray(): ByteArray = bytes.copyOf()

    /** `0.0.0.0` or `::`. */
    val isUnspecified: Boolean get() = bytes.all { it == 0.toByte() }

    /** `127.0.0.0/8` or `::1`. */
    val isLoopback: Boolean
        get() = if (isIpv4) bytes[0] == 127.toByte() else bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte()

    /** `224.0.0.0/4` or `ff00::/8`. */
    val isMulticast: Boolean
        get() = if (isIpv4) (bytes[0].toInt() and 0xF0) == 0xE0 else bytes[0] == 0xFF.toByte()

    /** `169.254.0.0/16` or `fe80::/10`. */
    val isLinkLocal: Boolean
        get() =
            if (isIpv4) {
                bytes[0] == 169.toByte() && bytes[1] == 254.toByte()
            } else {
                bytes[0] == 0xFE.toByte() && (bytes[1].toInt() and 0xC0) == 0x80
            }

    override fun compareTo(other: IpAddress): Int {
        if (bytes.size != other.bytes.size) return bytes.size - other.bytes.size
        for (i in bytes.indices) {
            val d = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is IpAddress && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = if (isIpv4) bytes.joinToString(".") { (it.toInt() and 0xFF).toString() } else ipv6Text()

    private fun ipv6Text(): String {
        val groups = IntArray(8) { ((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLen) {
                bestStart = i
                bestLen = j - i
            }
            i = j
        }
        if (bestLen < 2) return groups.joinToString(":") { it.toString(16) }
        val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val tail = (bestStart + bestLen until 8).joinToString(":") { groups[it].toString(16) }
        return "$head::$tail"
    }

    companion object {
        /**
         * An address from its 4 or 16 raw bytes (network order). IPv4-mapped IPv6 bytes become IPv4.
         *
         * @throws IllegalArgumentException for any other length.
         */
        fun of(bytes: ByteArray): IpAddress {
            require(bytes.size == 4 || bytes.size == 16) { "an IP address is 4 or 16 bytes, was ${bytes.size}" }
            if (bytes.size == 16 && isV4Mapped(bytes)) return IpAddress(bytes.copyOfRange(12, 16))
            return IpAddress(bytes.copyOf())
        }

        /**
         * Parses dotted-quad IPv4 (no leading zeros) or RFC 4291 IPv6 text (with `::`, an embedded IPv4 tail and an
         * optional `%zone`, which is dropped).
         *
         * @throws DiscoveryFormatException for anything else.
         */
        fun parse(text: String): IpAddress {
            val bytes =
                if (':' in text) {
                    parseV6(text)
                } else {
                    parseV4(text)
                        ?: throw DiscoveryFormatException("not an IPv4 address")
                }
            return of(bytes)
        }

        private fun isV4Mapped(b: ByteArray): Boolean {
            for (i in 0 until 10) if (b[i] != 0.toByte()) return false
            return b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        }

        private fun parseV4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val v = Bytes.parseDecimal(parts[i], 3) ?: return null
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun parseV6(input: String): ByteArray {
            val fail = { DiscoveryFormatException("not an IPv6 address") }
            val percent = input.indexOf('%')
            if (percent == 0 || percent == input.length - 1) throw fail()
            val text = if (percent > 0) input.substring(0, percent) else input
            val doubleColon = text.indexOf("::")
            if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) throw fail()
            val headText = if (doubleColon >= 0) text.substring(0, doubleColon) else text
            val tailText = if (doubleColon >= 0) text.substring(doubleColon + 2) else ""
            val head = if (headText.isEmpty()) emptyList() else headText.split(':')
            val tail = if (tailText.isEmpty()) emptyList() else tailText.split(':')
            val groups = ArrayList<Int>(8)
            val all = head + tail
            // An embedded IPv4 address may only be the final group, and "::" may not follow it.
            val v4Allowed = if (doubleColon >= 0) tail.isNotEmpty() else true
            for ((index, g) in all.withIndex()) {
                val isLast = index == all.size - 1
                if ('.' in g) {
                    if (!isLast || !v4Allowed) throw fail()
                    val v4 = parseV4(g) ?: throw fail()
                    groups += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                    groups += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                } else {
                    if (g.isEmpty() || g.length > 4) throw fail()
                    groups += Bytes.parseHex(g.padStart(4, '0'), 4)?.toInt() ?: throw fail()
                }
            }
            val headGroups = groups.size - tailGroupCount(tail)
            val out = ByteArray(16)
            if (doubleColon >= 0) {
                if (groups.size > 7) throw fail()
                val tailCount = groups.size - headGroups
                for (i in 0 until headGroups) writeGroup(out, i, groups[i])
                for (i in 0 until tailCount) writeGroup(out, 8 - tailCount + i, groups[headGroups + i])
            } else {
                if (groups.size != 8) throw fail()
                for (i in 0 until 8) writeGroup(out, i, groups[i])
            }
            return out
        }

        private fun tailGroupCount(tail: List<String>): Int = tail.sumOf { if ('.' in it) 2 else 1 }

        private fun writeGroup(
            out: ByteArray,
            index: Int,
            value: Int,
        ) {
            out[2 * index] = (value ushr 8).toByte()
            out[2 * index + 1] = value.toByte()
        }
    }
}
