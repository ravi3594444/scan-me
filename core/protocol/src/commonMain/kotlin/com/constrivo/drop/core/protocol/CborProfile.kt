package com.constrivo.drop.core.protocol

/**
 * The CBOR subset control messages use (architecture §7.2), checked before `kotlinx-serialization` sees the bytes.
 *
 * Accepted: unsigned and negative integers within the signed 64-bit range, byte strings, UTF-8 text strings, arrays,
 * maps whose keys are all unsigned integers (records) or all text strings (string maps), `false`, `true`, `null`,
 * and half/single/double floats. Every head uses the shortest form (RFC 8949 §4.2.1). Rejected: indefinite lengths,
 * tags, other simple values, reserved additional-information values, invalid UTF-8, duplicate map keys, maps mixing
 * integer and text keys, nesting deeper than [ProtocolConstants.MAX_CBOR_DEPTH], declared lengths larger than the
 * remaining input, and trailing bytes.
 *
 * The mixed-key rule matters because `kotlinx-serialization` also matches a text key equal to a Kotlin property name,
 * so `{1: a, "transferId": b}` would otherwise carry two values for one field.
 *
 * The walk is iterative and allocates only a stack bounded by the depth limit and per-map key sets bounded by the
 * input size, so hostile input cannot overflow the stack or trigger large allocations. With `canonical = true` it
 * also requires map keys in the bytewise order of their encodings, which is how the encoder's output is checked.
 */
internal object CborProfile {
    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
    private const val MAJOR_TAG = 6
    private const val MAJOR_SIMPLE = 7

    private class Level(
        var remaining: Long,
        val isMap: Boolean,
    ) {
        var expectingKey: Boolean = isMap
        var keys: HashSet<Any>? = null
        var keyMajor: Int = -1
        var lastKeyStart: Int = -1
        var lastKeyEnd: Int = -1
    }

    /**
     * Checks that `bytes[offset until end]` is exactly one data item of the profile.
     * Throws [ProtocolException] naming the first violation.
     */
    fun validate(
        bytes: ByteArray,
        offset: Int = 0,
        end: Int = bytes.size,
        maxDepth: Int = ProtocolConstants.MAX_CBOR_DEPTH,
        canonical: Boolean = false,
    ) {
        if (offset < 0 || end > bytes.size || offset > end) throw ProtocolException("CBOR range out of bounds")
        val stack = ArrayList<Level>()
        var pos = offset
        do {
            if (pos >= end) throw ProtocolException("CBOR input ends inside an item at byte $pos")
            val parent = stack.lastOrNull()
            val isKey = parent != null && parent.isMap && parent.expectingKey
            val itemStart = pos
            val initial = bytes[pos++].toInt() and 0xFF
            val major = initial ushr 5
            val info = initial and 0x1F
            var opensContainer = false

            if (major == MAJOR_SIMPLE) {
                pos = skipSimple(bytes, pos, end, info)
            } else {
                if (major == MAJOR_TAG) throw ProtocolException("CBOR tags are not allowed (byte $itemStart)")
                if (info == 31) throw ProtocolException("indefinite-length CBOR items are not allowed (byte $itemStart)")
                if (info in 28..30) throw ProtocolException("reserved CBOR additional information $info (byte $itemStart)")
                val argSize = argumentSize(info)
                if (end - pos < argSize) throw ProtocolException("CBOR head truncated at byte $itemStart")
                val arg = readArgument(bytes, pos, info)
                pos += argSize
                if (arg < 0) throw ProtocolException("CBOR head at byte $itemStart exceeds the 64-bit signed range")
                if (argSize > 0 && arg < minimumForSize(argSize)) {
                    throw ProtocolException("CBOR head at byte $itemStart is not in shortest form")
                }
                when (major) {
                    MAJOR_BYTES, MAJOR_TEXT -> {
                        if (arg > end - pos) {
                            throw ProtocolException("CBOR string at byte $itemStart declares $arg bytes, ${end - pos} left")
                        }
                        val length = arg.toInt()
                        if (major == MAJOR_TEXT && !isValidUtf8(bytes, pos, pos + length)) {
                            throw ProtocolException("CBOR text at byte $itemStart is not valid UTF-8")
                        }
                        pos += length
                    }

                    MAJOR_ARRAY, MAJOR_MAP -> {
                        val items = if (major == MAJOR_MAP) 2.0 * arg else arg.toDouble()
                        if (items > (end - pos).toDouble()) {
                            throw ProtocolException("CBOR container at byte $itemStart declares more items than bytes left")
                        }
                        if (arg > 0) {
                            if (stack.size >= maxDepth) throw ProtocolException("CBOR nesting deeper than $maxDepth")
                            opensContainer = true
                            stack += Level(if (major == MAJOR_MAP) 2 * arg else arg, major == MAJOR_MAP)
                        }
                    }
                }
            }

            if (isKey) checkKey(parent, bytes, itemStart, pos, major, canonical)
            if (!opensContainer) completeItem(stack)
        } while (stack.isNotEmpty())
        if (pos != end) throw ProtocolException("${end - pos} trailing bytes after the CBOR item")
    }

    private fun completeItem(stack: ArrayList<Level>) {
        while (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            if (top.isMap) top.expectingKey = !top.expectingKey
            top.remaining--
            if (top.remaining > 0) return
            stack.removeAt(stack.size - 1)
        }
    }

    private fun checkKey(
        map: Level,
        bytes: ByteArray,
        start: Int,
        end: Int,
        major: Int,
        canonical: Boolean,
    ) {
        if (major != MAJOR_UNSIGNED && major != MAJOR_TEXT) {
            throw ProtocolException("CBOR map key at byte $start must be an unsigned integer or text")
        }
        if (map.keyMajor >= 0 && map.keyMajor != major) throw ProtocolException("CBOR map at byte $start mixes integer and text keys")
        map.keyMajor = major
        val keys = map.keys ?: HashSet<Any>().also { map.keys = it }
        val info = bytes[start].toInt() and 0x1F
        val key: Any =
            if (major == MAJOR_UNSIGNED) {
                readArgument(bytes, start + 1, info)
            } else {
                bytes.decodeToString(start + 1 + argumentSize(info), end)
            }
        if (!keys.add(key)) throw ProtocolException("duplicate CBOR map key at byte $start")
        if (canonical && map.lastKeyStart >= 0 && compareBytes(bytes, map.lastKeyStart, map.lastKeyEnd, start, end) >= 0) {
            throw ProtocolException("CBOR map keys are not in canonical order at byte $start")
        }
        map.lastKeyStart = start
        map.lastKeyEnd = end
    }

    private fun skipSimple(
        bytes: ByteArray,
        pos: Int,
        end: Int,
        info: Int,
    ): Int {
        val extra =
            when (info) {
                20, 21, 22 -> 0

                // false, true, null
                25 -> 2

                // half float
                26 -> 4

                // single float
                27 -> 8

                // double float
                31 -> throw ProtocolException("unexpected CBOR break (byte ${pos - 1})")

                else -> throw ProtocolException("CBOR simple value $info is not allowed (byte ${pos - 1})")
            }
        if (end - pos < extra) throw ProtocolException("CBOR float truncated at byte ${pos - 1}")
        return pos + extra
    }

    private fun argumentSize(info: Int): Int =
        when (info) {
            24 -> 1
            25 -> 2
            26 -> 4
            27 -> 8
            else -> 0
        }

    private fun minimumForSize(size: Int): Long =
        when (size) {
            1 -> 24
            2 -> 0x100
            4 -> 0x1_0000
            else -> 0x1_0000_0000
        }

    /** The head's argument; for 8-byte arguments with the top bit set the result is negative (out of range). */
    private fun readArgument(
        bytes: ByteArray,
        pos: Int,
        info: Int,
    ): Long {
        val size = argumentSize(info)
        if (size == 0) return info.toLong()
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
        return value
    }

    private fun compareBytes(
        bytes: ByteArray,
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int,
    ): Int {
        val common = minOf(aEnd - aStart, bEnd - bStart)
        for (i in 0 until common) {
            val a = bytes[aStart + i].toInt() and 0xFF
            val b = bytes[bStart + i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return (aEnd - aStart) - (bEnd - bStart)
    }

    /** Strict UTF-8 (RFC 3629): no overlong forms, no surrogates, nothing above U+10FFFF. */
    fun isValidUtf8(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        var i = start
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val need: Int
            val min: Int
            var cp: Int
            when {
                b0 in 0xC2..0xDF -> {
                    need = 1
                    min = 0x80
                    cp = b0 and 0x1F
                }

                b0 in 0xE0..0xEF -> {
                    need = 2
                    min = 0x800
                    cp = b0 and 0x0F
                }

                b0 in 0xF0..0xF4 -> {
                    need = 3
                    min = 0x10000
                    cp = b0 and 0x07
                }

                else -> {
                    return false
                }
            }
            if (end - i - 1 < need) return false
            for (k in 1..need) {
                val b = bytes[i + k].toInt() and 0xFF
                if (b and 0xC0 != 0x80) return false
                cp = (cp shl 6) or (b and 0x3F)
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
            i += need + 1
        }
        return true
    }
}
