package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.RawCbor.array
import com.constrivo.drop.core.protocol.RawCbor.map
import com.constrivo.drop.core.protocol.RawCbor.text
import com.constrivo.drop.core.protocol.RawCbor.uint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CborProfileTest {
    private fun valid(hex: String) = CborProfile.validate(hex.unhex())

    private fun invalid(
        hex: String,
        what: String,
    ) = assertProtocolError(what) { CborProfile.validate(hex.unhex()) }

    @Test
    fun acceptsTheProfile() {
        valid("00")
        valid("17")
        valid("1818")
        valid("1901f4")
        valid("1a00010000")
        valid("1b0000000100000000")
        valid("1b7fffffffffffffff")
        valid("3b7fffffffffffffff") // most negative signed 64-bit
        valid("40")
        valid("4401020304")
        valid("60")
        valid("63e282ac") // "€"
        valid("64f09f9880") // an emoji (4-byte UTF-8)
        valid("80")
        valid("83010203")
        valid("a0")
        valid("a2016161026162") // {1: "a", 2: "b"}
        valid("a2616101616202") // {"a": 1, "b": 2}
        valid("f4")
        valid("f5")
        valid("f6")
        valid("f93c00")
        valid("fa3f800000")
        valid("fb3ff0000000000000")
    }

    @Test
    fun rejectsNonShortestHeads() {
        invalid("1817", "23 in two bytes")
        invalid("190017", "23 in three bytes")
        invalid("1a0000ffff", "65535 in five bytes")
        invalid("1b00000000ffffffff", "u32 in nine bytes")
        invalid("5800", "empty string with a length byte")
    }

    @Test
    fun rejectsOutOfRangeIntegers() {
        invalid("1b8000000000000000", "2^63")
        invalid("3b8000000000000000", "-2^63 - 1")
    }

    @Test
    fun rejectsIndefiniteLengthsTagsAndOtherSimpleValues() {
        invalid("5f4101ff", "indefinite byte string")
        invalid("7f6161ff", "indefinite text")
        invalid("9f01ff", "indefinite array")
        invalid("bf0101ff", "indefinite map")
        invalid("c11a5f5e1000", "tag")
        invalid("f7", "undefined")
        invalid("f820", "one-byte simple value")
        invalid("e0", "simple value 0")
        invalid("ff", "lone break")
        invalid("1c", "reserved additional information")
        invalid("3e", "reserved additional information on a negative")
    }

    @Test
    fun rejectsTruncationAndTrailingBytes() {
        invalid("", "empty")
        invalid("19", "truncated head")
        invalid("4401", "truncated byte string")
        invalid("8201", "array missing an item")
        invalid("a101", "map missing a value")
        invalid("fa3f80", "truncated float")
        invalid("0000", "trailing byte")
    }

    @Test
    fun rejectsDeclaredSizesLargerThanTheInput() {
        invalid("5a7fffffff", "2 GiB byte string")
        invalid("9a7fffffff00", "2^31 array items")
        invalid("ba40000000", "2^30 map pairs")
        invalid("bb7fffffffffffffff", "2^63 map pairs")
    }

    @Test
    fun rejectsInvalidUtf8() {
        invalid("61ff", "0xFF")
        invalid("61c3", "truncated sequence")
        invalid("62c0af", "overlong '/'")
        invalid("63e08080", "overlong three-byte")
        invalid("63eda080", "UTF-16 surrogate")
        invalid("64f4908080", "above U+10FFFF")
        invalid("6180", "bare continuation byte")
        val sample = "h\u00e9llo w\u00f6rld \u2713 \uD83D\uDE00".encodeToByteArray()
        assertTrue(CborProfile.isValidUtf8(sample, 0, sample.size))
        assertFalse(CborProfile.isValidUtf8(byteArrayOf(0xE2.toByte(), 0x82.toByte()), 0, 2))
    }

    @Test
    fun rejectsBadMapKeys() {
        invalid("a1400000", "byte-string key")
        invalid("a1200000", "negative key")
        invalid("a180000000", "array key")
        invalid("a2010101020000", "duplicate integer key")
        invalid("a2616101616102", "duplicate text key")
        invalid("a2016161616101", "integer and text keys mixed")
        valid("a2616101616201")
    }

    @Test
    fun limitsNesting() {
        var item = uint(0)
        repeat(ProtocolConstants.MAX_CBOR_DEPTH) { item = array(item) }
        CborProfile.validate(item)
        assertProtocolError { CborProfile.validate(array(item)) }
        var maps = uint(0)
        repeat(ProtocolConstants.MAX_CBOR_DEPTH) { maps = map(uint(1) to maps) }
        CborProfile.validate(maps)
        assertProtocolError { CborProfile.validate(map(uint(1) to maps)) }
        // Deep nesting of a hostile size fails fast instead of overflowing the stack.
        val hostile = ByteArray(100_000) { 0x81.toByte() }
        assertProtocolError { CborProfile.validate(hostile) }
    }

    @Test
    fun canonicalModeChecksKeyOrder() {
        CborProfile.validate(map(uint(1) to uint(0), uint(2) to uint(0), uint(24) to uint(0)), canonical = true)
        CborProfile.validate(map(text("b") to uint(0), text("aa") to uint(0)), canonical = true) // shorter first
        assertProtocolError { CborProfile.validate(map(uint(2) to uint(0), uint(1) to uint(0)), canonical = true) }
        assertProtocolError { CborProfile.validate(map(text("aa") to uint(0), text("b") to uint(0)), canonical = true) }
        // Text keys sort after integer keys, and a map may not mix them anyway.
        assertProtocolError { CborProfile.validate(map(text("a") to uint(0), uint(1) to uint(0)), canonical = true) }
        // Order is not required when decoding.
        CborProfile.validate(map(uint(2) to uint(0), uint(1) to uint(0)))
    }

    @Test
    fun validatesASubrange() {
        val bytes = "ff0102ff".unhex()
        CborProfile.validate(bytes, 1, 2)
        assertProtocolError { CborProfile.validate(bytes, 1, 3) }
        assertProtocolError { CborProfile.validate(bytes, 3, 2) }
        assertProtocolError { CborProfile.validate(bytes, 0, 5) }
    }
}
