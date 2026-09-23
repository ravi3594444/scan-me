package com.constrivo.drop.core.protocol

/**
 * UTF-8 length of [value] without allocating, or -1 when it contains an unpaired surrogate (which would not survive
 * a round trip through UTF-8).
 */
internal fun utf8Length(value: String): Int {
    var total = 0
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c.code < 0x80 -> {
                total += 1
            }

            c.code < 0x800 -> {
                total += 2
            }

            c.isHighSurrogate() -> {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return -1
                total += 4
                i++
            }

            c.isLowSurrogate() -> {
                return -1
            }

            else -> {
                total += 3
            }
        }
        i++
    }
    return total
}

/** Requires [value] to be well-formed UTF-16 whose UTF-8 form has between [min] and [max] bytes. */
internal fun requireText(
    value: String,
    what: String,
    max: Int,
    min: Int = 1,
) {
    val length = utf8Length(value)
    require(length >= 0) { "$what contains an unpaired surrogate" }
    require(length in min..max) { "$what must be $min..$max UTF-8 bytes, got $length" }
}

internal fun requireFileIndex(
    index: Int,
    what: String = "file_index",
    allowBundle: Boolean = false,
) {
    val ok = index in 0 until ProtocolConstants.MAX_FILES_PER_TRANSFER || (allowBundle && index == ProtocolConstants.BUNDLE_FILE_INDEX)
    require(ok) { "$what $index out of range" }
}

internal fun requireStreamId(
    streamId: Int,
    what: String = "stream_id",
) {
    require(streamId >= 0) { "$what must be non-negative, got $streamId" }
}

/** Requires [values] to be strictly increasing. */
internal fun requireStrictlyIncreasing(
    values: List<Int>,
    what: String,
) {
    for (i in 1 until values.size) {
        require(values[i] > values[i - 1]) { "$what must be strictly increasing" }
    }
}

/** Unsigned 32-bit order, in which [ProtocolConstants.BUNDLE_FILE_INDEX] (0xFFFFFFFF) sorts last. */
internal fun Int.asU32(): Long = toLong() and 0xFFFF_FFFFL
