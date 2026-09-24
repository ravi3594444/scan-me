package com.constrivo.drop.platform.android.wifi

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/** IP address literals, parsed without ever looking a name up (architecture §13: links dial addresses, not names). */
object IpLiteral {
    private const val IPV4_PARTS = 4
    private const val OCTET_MAX = 255
    private const val MAX_OCTET_DIGITS = 3

    /**
     * [text] as an address when it is a dotted-quad IPv4 literal or an IPv6 literal (optionally in brackets, with a
     * `%scope` suffix); null for anything else, a host name included. Never throws.
     */
    fun parse(text: String): InetAddress? {
        val bare = text.trim().removePrefix("[").removeSuffix("]")
        if (bare.isEmpty()) return null
        ipv4(bare)?.let { return InetAddress.getByAddress(it) }
        val core = bare.substringBefore('%')
        val ipv6 = core.contains(':') && core.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!ipv6) return null
        val scope = bare.substringAfter('%', "")
        if (bare.contains('%') && (scope.isEmpty() || !scope.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' })) {
            return null
        }
        return try {
            // With a ':' the platform parses a numeric IPv6 literal and never queries a resolver; an IPv4-mapped form
            // comes back as the IPv4 address.
            InetAddress.getByName(bare)
        } catch (_: UnknownHostException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Equal addresses, an IPv4 address and its IPv4-mapped IPv6 form included; scopes are ignored. */
    fun sameAddress(
        a: InetAddress,
        b: InetAddress,
    ): Boolean {
        val x = canonicalBytes(a)
        val y = canonicalBytes(b)
        return x.contentEquals(y)
    }

    /** True for addresses that only exist on a local network: RFC 1918, IPv4 and IPv6 link-local, IPv6 unique local. */
    fun isPrivate(address: InetAddress): Boolean {
        val bytes = canonicalBytes(address)
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return if (bytes.size == IPV4_PARTS) {
            b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
        } else {
            (b0 == 0xFE && (b1 and 0xC0) == 0x80) || (b0 and 0xFE) == 0xFC
        }
    }

    /** The 4 bytes of an IPv4 address (also when given in IPv4-mapped IPv6 form), else the 16 bytes. */
    fun canonicalBytes(address: InetAddress): ByteArray {
        val bytes = address.address
        if (bytes.size == 16) {
            val mapped = (0 until 10).all { bytes[it].toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
            if (mapped) return bytes.copyOfRange(12, 16)
        }
        return bytes
    }

    /** The four bytes of a dotted-quad IPv4 literal (each part 0–255, at most three digits), else null. */
    private fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != IPV4_PARTS) return null
        val bytes = ByteArray(IPV4_PARTS)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > MAX_OCTET_DIGITS || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > OCTET_MAX) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }
}

/**
 * An address prefix of a link (a `LinkAddress` of Android's `LinkProperties`, an `InterfaceAddress`): [address] and
 * its [length] in bits. [contains] tells whether another address is on the same subnet.
 *
 * @throws IllegalArgumentException for a length outside `0..32` (IPv4) or `0..128` (IPv6).
 */
class IpPrefix(
    address: InetAddress,
    val length: Int,
) {
    private val bytes: ByteArray = IpLiteral.canonicalBytes(address)

    init {
        require(length in 0..bytes.size * 8) { "prefix length $length out of range for ${bytes.size * 8}-bit addresses" }
    }

    val isIpv4: Boolean get() = bytes.size == 4

    /** The prefix's address as given (host bits kept), for logs and for binding. */
    val address: InetAddress = InetAddress.getByAddress(bytes)

    /** True when [other] has the same family and the same first [length] bits. */
    fun contains(other: InetAddress): Boolean {
        val candidate = IpLiteral.canonicalBytes(other)
        if (candidate.size != bytes.size) return false
        val full = length / 8
        for (i in 0 until full) if (candidate[i] != bytes[i]) return false
        val rest = length % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (candidate[full].toInt() and mask) == (bytes[full].toInt() and mask)
    }

    override fun equals(other: Any?): Boolean = other is IpPrefix && other.length == length && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + length

    override fun toString(): String = "${address.hostAddress}/$length"

    companion object {
        /** The prefix of [address] with [length], or null when the length does not fit its family. */
        fun of(
            address: InetAddress,
            length: Int,
        ): IpPrefix? = if (length in 0..IpLiteral.canonicalBytes(address).size * 8) IpPrefix(address, length) else null

        /** True for an IPv4 address; for filters over mixed address lists. */
        fun isIpv4(address: InetAddress): Boolean = address is Inet4Address || IpLiteral.canonicalBytes(address).size == 4
    }
}
