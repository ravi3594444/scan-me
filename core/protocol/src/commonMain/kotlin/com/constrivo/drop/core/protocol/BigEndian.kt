package com.constrivo.drop.core.protocol

/** Big-endian integer helpers for the binary layouts of §7.1 and §7.3. Callers check bounds first. */
internal object BigEndian {
    fun putU32(
        destination: ByteArray,
        offset: Int,
        value: Int,
    ) {
        destination[offset] = (value ushr 24).toByte()
        destination[offset + 1] = (value ushr 16).toByte()
        destination[offset + 2] = (value ushr 8).toByte()
        destination[offset + 3] = value.toByte()
    }

    /** Reads a u32 as a Long in 0..0xFFFFFFFF. */
    fun getU32(
        source: ByteArray,
        offset: Int,
    ): Long =
        ((source[offset].toLong() and 0xFF) shl 24) or
            ((source[offset + 1].toLong() and 0xFF) shl 16) or
            ((source[offset + 2].toLong() and 0xFF) shl 8) or
            (source[offset + 3].toLong() and 0xFF)

    /** Reads a u32 as Int bits (0xFFFFFFFF becomes -1). */
    fun getU32Bits(
        source: ByteArray,
        offset: Int,
    ): Int = getU32(source, offset).toInt()
}

/** Throws [ProtocolException] unless `offset..offset+length` lies inside an array of [size] bytes. */
internal fun checkRange(
    size: Int,
    offset: Int,
    length: Int,
    what: String,
) {
    if (offset < 0 || length < 0 || offset > size - length) {
        throw ProtocolException("$what: range $offset+$length outside $size bytes")
    }
}
