package com.constrivo.drop.core.discovery

/** Lower-case hex and fixed-width big-endian helpers shared by the discovery codecs. */
internal object Bytes {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[2 * i] = DIGITS[v ushr 4]
            out[2 * i + 1] = DIGITS[v and 0x0F]
        }
        return out.concatToString()
    }

    /** [value] as exactly [digits] lower-case hex digits (the value must fit). */
    fun hex(
        value: Long,
        digits: Int,
    ): String {
        val out = CharArray(digits)
        var v = value
        for (i in digits - 1 downTo 0) {
            out[i] = DIGITS[(v and 0xF).toInt()]
            v = v ushr 4
        }
        return out.concatToString()
    }

    /**
     * Parses exactly [digits] hex digits (either case) into a non-negative value, or returns null when [text] has
     * another length or any other character. Never throws.
     */
    fun parseHex(
        text: String,
        digits: Int,
    ): Long? {
        if (text.length != digits || digits > 15) return null
        var v = 0L
        for (c in text) {
            val d =
                when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
            v = (v shl 4) or d.toLong()
        }
        return v
    }

    /** Reads [size] bytes (at most 8) at [offset] as an unsigned big-endian value. The caller checks bounds. */
    fun readBigEndian(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    /** Writes the low [size] bytes of [value] big-endian at [offset]. */
    fun writeBigEndian(
        value: Long,
        out: ByteArray,
        offset: Int,
        size: Int,
    ) {
        for (i in 0 until size) out[offset + i] = (value ushr (8 * (size - 1 - i))).toByte()
    }

    fun u64BigEndian(value: Long): ByteArray = ByteArray(8).also { writeBigEndian(value, it, 0, 8) }

    /** Parses a canonical unsigned decimal (no sign, no leading zeros, at most [maxDigits] digits); null otherwise. */
    fun parseDecimal(
        text: String,
        maxDigits: Int,
    ): Long? {
        if (text.isEmpty() || text.length > maxDigits || maxDigits > 18) return null
        if (text.length > 1 && text[0] == '0') return null
        var v = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return v
    }
}
