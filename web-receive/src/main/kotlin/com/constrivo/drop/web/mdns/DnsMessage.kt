package com.constrivo.drop.web.mdns

import java.io.IOException

/**
 * The one exception [DnsCodec.decode] throws for a malformed packet: truncated sections, bad label types, compression
 * pointers that do not point backwards, names longer than 255 bytes, record data that does not fit its type. The
 * mDNS responder catches it and drops the packet; nothing else escapes the decoder for bad input.
 */
class DnsFormatException(
    message: String,
) : IOException(message)

/** DNS record types used here (RFC 1035, RFC 3596, RFC 4034, RFC 6891). */
object DnsType {
    const val A = 1
    const val PTR = 12
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41
    const val NSEC = 47
    const val ANY = 255
}

/** DNS classes; mDNS uses the top bit of the class field as the QU / cache-flush flag (RFC 6762 §5.4, §10.2). */
object DnsClass {
    const val IN = 1
    const val ANY = 255
}

/**
 * A domain name as a list of labels (UTF-8 text, as mDNS uses it, RFC 6762 §16). Each label is 1–63 bytes and the
 * wire form at most 255 bytes. Names are compared case-insensitively for ASCII letters with [matches].
 */
class DnsName private constructor(
    val labels: List<String>,
) {
    /** Wire length: one length byte per label, the label bytes, and the root byte. */
    internal val wireLength: Int = labels.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size } + 1

    /** Whether this is the same name as [other], ignoring ASCII case (RFC 6762 §16). */
    fun matches(other: DnsName): Boolean =
        labels.size == other.labels.size && labels.indices.all { asciiLower(labels[it]) == asciiLower(other.labels[it]) }

    override fun equals(other: Any?): Boolean = other is DnsName && labels == other.labels

    override fun hashCode(): Int = labels.hashCode()

    override fun toString(): String = if (labels.isEmpty()) "." else labels.joinToString(".")

    companion object {
        const val MAX_LABEL_BYTES = 63
        const val MAX_NAME_BYTES = 255

        /** The root name. */
        val ROOT = DnsName(emptyList())

        /**
         * Parses dotted text such as `drop.local` (a trailing dot is allowed).
         *
         * @throws IllegalArgumentException for an empty label or a label or name over the length limits.
         */
        fun of(text: String): DnsName {
            val trimmed = text.removeSuffix(".")
            if (trimmed.isEmpty()) return ROOT
            return fromLabels(trimmed.split('.'))
        }

        /** @throws IllegalArgumentException when a label is empty or a label or the name is too long. */
        fun fromLabels(labels: List<String>): DnsName {
            for (label in labels) {
                val n = label.toByteArray(Charsets.UTF_8).size
                require(n in 1..MAX_LABEL_BYTES) { "label must be 1..$MAX_LABEL_BYTES bytes: '$label'" }
            }
            val name = DnsName(labels.toList())
            require(name.wireLength <= MAX_NAME_BYTES) { "name longer than $MAX_NAME_BYTES bytes" }
            return name
        }

        internal fun decoded(labels: List<String>): DnsName = DnsName(labels)

        private fun asciiLower(s: String): String {
            val chars = s.toCharArray()
            for (i in chars.indices) if (chars[i] in 'A'..'Z') chars[i] = chars[i] + ('a' - 'A')
            return String(chars)
        }
    }
}

/**
 * One question. [qclass] is the class without the top bit; [unicastResponse] is that bit, the mDNS "QU" flag
 * (RFC 6762 §5.4).
 */
data class DnsQuestion(
    val name: DnsName,
    val type: Int,
    val qclass: Int = DnsClass.IN,
    val unicastResponse: Boolean = false,
) {
    init {
        require(type in 0..0xFFFF && qclass in 0..0x7FFF) { "type is 16 bits and class 15 bits" }
    }
}

/** Record data: parsed for the types the responder reads or writes, raw bytes for everything else. */
sealed interface DnsRData {
    /** An IPv4 address (type A). */
    class A(
        address: ByteArray,
    ) : DnsRData {
        val address: ByteArray = address.copyOf()

        init {
            require(address.size == 4) { "an A record holds 4 bytes" }
        }

        override fun equals(other: Any?): Boolean = other is A && address.contentEquals(other.address)

        override fun hashCode(): Int = address.contentHashCode()

        override fun toString(): String = address.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    /** An IPv6 address (type AAAA). */
    class Aaaa(
        address: ByteArray,
    ) : DnsRData {
        val address: ByteArray = address.copyOf()

        init {
            require(address.size == 16) { "an AAAA record holds 16 bytes" }
        }

        override fun equals(other: Any?): Boolean = other is Aaaa && address.contentEquals(other.address)

        override fun hashCode(): Int = address.contentHashCode()
    }

    /**
     * NSEC (RFC 4034 §4): the next name and the set of types that exist. mDNS uses it for negative answers
     * (RFC 6762 §6.1), with [nextName] equal to the record's own name.
     */
    data class Nsec(
        val nextName: DnsName,
        val types: Set<Int>,
    ) : DnsRData {
        init {
            require(types.all { it in 0..0xFFFF }) { "types must be 16-bit values" }
        }
    }

    /** Any other type, kept as the raw record data (names inside it may be compressed against the message). */
    class Raw(
        bytes: ByteArray,
    ) : DnsRData {
        val bytes: ByteArray = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is Raw && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Raw(${bytes.size} bytes)"
    }
}

/**
 * One resource record. [rclass] is the class without the top bit; [cacheFlush] is that bit (RFC 6762 §10.2). For an
 * OPT record (RFC 6891) the class field is the sender's UDP payload size, so it is kept whole in [rclass] and
 * [cacheFlush] is false.
 */
data class DnsRecord(
    val name: DnsName,
    val type: Int,
    val rclass: Int,
    val cacheFlush: Boolean,
    val ttl: Long,
    val data: DnsRData,
) {
    init {
        require(ttl in 0..0xFFFFFFFFL) { "ttl must be an unsigned 32-bit value" }
        require(type in 0..0xFFFF) { "type must be a 16-bit value" }
        require(rclass in 0..(if (type == DnsType.OPT) 0xFFFF else 0x7FFF)) { "class out of range" }
    }
}

/** A DNS message (RFC 1035 §4.1). [flags] is the 16-bit word after the id (QR, opcode, AA, TC, RD, RA, Z, rcode). */
data class DnsMessage(
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion> = emptyList(),
    val answers: List<DnsRecord> = emptyList(),
    val authorities: List<DnsRecord> = emptyList(),
    val additionals: List<DnsRecord> = emptyList(),
) {
    init {
        require(id in 0..0xFFFF && flags in 0..0xFFFF) { "id and flags are 16-bit values" }
    }

    val isResponse: Boolean get() = flags and FLAG_RESPONSE != 0
    val opcode: Int get() = (flags ushr 11) and 0x0F
    val responseCode: Int get() = flags and 0x0F

    companion object {
        const val FLAG_RESPONSE = 0x8000
        const val FLAG_AUTHORITATIVE = 0x0400
        const val FLAG_TRUNCATED = 0x0200
    }
}
