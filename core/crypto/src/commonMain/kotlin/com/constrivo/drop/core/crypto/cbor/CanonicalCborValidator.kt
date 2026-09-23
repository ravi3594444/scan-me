package com.constrivo.drop.core.crypto.cbor

/**
 * Walks one CBOR data item and rejects anything outside the deterministic subset used on the wire
 * (RFC 8949 §4.2.1 "core deterministic encoding requirements", restricted further):
 *
 * - exactly one top-level item and no trailing bytes;
 * - arguments in their shortest form; no indefinite lengths; no reserved additional-information values;
 * - no tags, no floating-point values and no simple values other than `false`, `true` and `null`;
 * - text strings are valid UTF-8;
 * - map keys strictly increase in the bytewise order of their encodings (so no duplicates);
 * - declared lengths and element counts never exceed the bytes that remain, and nesting is at most [maxDepth].
 *
 * Running this before a kotlinx-serialization decoder means the decoder only ever sees small, well-formed input,
 * so it cannot be driven into huge allocations or deep recursion.
 */
internal class CanonicalCborValidator(
    private val bytes: ByteArray,
    private val maxDepth: Int,
) {
    private var pos = 0

    fun validate() {
        readItem(depth = 0)
        if (pos != bytes.size) fail("${bytes.size - pos} trailing bytes after the top-level item")
    }

    private fun readItem(depth: Int) {
        if (depth > maxDepth) fail("nesting deeper than $maxDepth")
        val start = pos
        val initial = readByte()
        val major = initial ushr 5
        val info = initial and 0x1F
        when (major) {
            MAJOR_UNSIGNED, MAJOR_NEGATIVE -> {
                readArgument(info, start)
            }

            MAJOR_BYTES -> {
                skip(readLength(info, start, perElement = 1))
            }

            MAJOR_TEXT -> {
                val length = readLength(info, start, perElement = 1)
                val textStart = pos
                skip(length)
                try {
                    bytes.decodeToString(textStart, textStart + length, throwOnInvalidSequence = true)
                } catch (e: CharacterCodingException) {
                    fail("text string at offset $start is not valid UTF-8")
                }
            }

            MAJOR_ARRAY -> {
                val count = readLength(info, start, perElement = 1)
                repeat(count) { readItem(depth + 1) }
            }

            MAJOR_MAP -> {
                readMap(info, start, depth)
            }

            MAJOR_TAG -> {
                fail("tags are not allowed (offset $start)")
            }

            else -> {
                when (info) {
                    SIMPLE_FALSE, SIMPLE_TRUE, SIMPLE_NULL -> Unit
                    else -> fail("simple value or float with additional information $info is not allowed (offset $start)")
                }
            }
        }
    }

    private fun readMap(
        info: Int,
        start: Int,
        depth: Int,
    ) {
        val count = readLength(info, start, perElement = 2)
        var previousKeyStart = -1
        var previousKeyEnd = -1
        repeat(count) {
            val keyStart = pos
            readItem(depth + 1)
            val keyEnd = pos
            if (previousKeyStart >= 0 && compareBytes(previousKeyStart, previousKeyEnd, keyStart, keyEnd) >= 0) {
                fail("map keys at offset $start are not in strictly increasing canonical order")
            }
            previousKeyStart = keyStart
            previousKeyEnd = keyEnd
            readItem(depth + 1)
        }
    }

    /** Reads the argument of the head starting at [headStart]; enforces the shortest encoding. */
    private fun readArgument(
        info: Int,
        headStart: Int,
    ): ULong {
        val value: ULong
        val minimum: ULong
        when (info) {
            in 0..23 -> {
                return info.toULong()
            }

            24 -> {
                value = readUnsigned(1)
                minimum = 24u
            }

            25 -> {
                value = readUnsigned(2)
                minimum = 0x100u
            }

            26 -> {
                value = readUnsigned(4)
                minimum = 0x1_0000u
            }

            27 -> {
                value = readUnsigned(8)
                minimum = 0x1_0000_0000u
            }

            31 -> {
                fail("indefinite-length items are not allowed (offset $headStart)")
            }

            else -> {
                fail("reserved additional information $info (offset $headStart)")
            }
        }
        if (value < minimum) fail("argument at offset $headStart is not in its shortest form")
        return value
    }

    /** A length or element count that must fit in the remaining input, at [perElement] bytes per element at least. */
    private fun readLength(
        info: Int,
        headStart: Int,
        perElement: Int,
    ): Int {
        val value = readArgument(info, headStart)
        val remaining = (bytes.size - pos).toULong()
        if (value > remaining / perElement.toULong()) fail("length $value at offset $headStart exceeds the remaining input")
        return value.toInt()
    }

    private fun readUnsigned(size: Int): ULong {
        if (bytes.size - pos < size) fail("truncated input at offset $pos")
        var value = 0uL
        repeat(size) { value = (value shl 8) or (bytes[pos++].toInt() and 0xFF).toULong() }
        return value
    }

    private fun readByte(): Int {
        if (pos >= bytes.size) fail("truncated input at offset $pos")
        return bytes[pos++].toInt() and 0xFF
    }

    private fun skip(count: Int) {
        if (bytes.size - pos < count) fail("truncated input at offset $pos")
        pos += count
    }

    private fun compareBytes(
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int,
    ): Int {
        val aLength = aEnd - aStart
        val bLength = bEnd - bStart
        for (i in 0 until minOf(aLength, bLength)) {
            val diff = (bytes[aStart + i].toInt() and 0xFF) - (bytes[bStart + i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return aLength - bLength
    }

    private fun fail(message: String): Nothing = throw MalformedCborException(message)

    private companion object {
        const val MAJOR_UNSIGNED = 0
        const val MAJOR_NEGATIVE = 1
        const val MAJOR_BYTES = 2
        const val MAJOR_TEXT = 3
        const val MAJOR_ARRAY = 4
        const val MAJOR_MAP = 5
        const val MAJOR_TAG = 6
        const val SIMPLE_FALSE = 20
        const val SIMPLE_TRUE = 21
        const val SIMPLE_NULL = 22
    }
}
