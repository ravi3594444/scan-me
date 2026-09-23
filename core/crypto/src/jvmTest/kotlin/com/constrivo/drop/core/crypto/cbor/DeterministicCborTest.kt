@file:OptIn(ExperimentalSerializationApi::class)

package com.constrivo.drop.core.crypto.cbor

import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/** The deterministic CBOR subset used for signed and hashed messages (RFC 8949 §4.2.1; N2). */
class DeterministicCborTest {
    @Serializable
    class Sample(
        @CborLabel(1) val number: Long,
        @CborLabel(2) val bytes: ByteArray,
        @CborLabel(3) val text: String,
        @CborLabel(4) val flag: Boolean,
        @CborLabel(5) val inner: Inner? = null,
        @CborLabel(6) val optional: Int? = null,
    ) {
        init {
            require(text.length <= 16) { "text too long" }
        }
    }

    @Serializable
    class Inner(
        @CborLabel(1) val a: Int,
    )

    private fun sample(
        number: Long = 500,
        inner: Inner? = Inner(-3),
        optional: Int? = null,
    ) = Sample(number, byteArrayOf(1, 2), "hé", true, inner, optional)

    @Test
    fun encodesTheCanonicalFormAndRoundTrips() {
        val bytes = DeterministicCbor.encode(Sample.serializer(), sample())
        // {1: 500, 2: h'0102', 3: "hé", 4: true, 5: {1: -3}}; key 6 (null) omitted.
        assertEquals("a5011901f402420102036368c3a904f505a10122", bytes.toHex())
        val decoded = DeterministicCbor.decode(Sample.serializer(), bytes, 64)
        assertEquals(500, decoded.number)
        assertContentEquals(byteArrayOf(1, 2), decoded.bytes)
        assertEquals("hé", decoded.text)
        assertEquals(-3, decoded.inner!!.a)
        assertNull(decoded.optional)
    }

    @Test
    fun integersUseTheirShortestForm() {
        val cases =
            mapOf(
                0L to "00",
                23L to "17",
                24L to "1818",
                255L to "18ff",
                256L to "190100",
                65536L to "1a00010000",
                1L shl 32 to "1b0000000100000000",
            )
        for ((value, hex) in cases) {
            val bytes = DeterministicCbor.encode(Sample.serializer(), sample(number = value, inner = null))
            assertEquals("a401$hex", bytes.toHex().substring(0, 4 + hex.length), "value $value")
            DeterministicCbor.decode(Sample.serializer(), bytes, 64)
        }
    }

    @Test
    fun validatorRejectsEverythingOutsideTheSubset() {
        val cases =
            mapOf(
                "empty" to "",
                "trailing byte" to "0000",
                "non-minimal 1-byte" to "1805",
                "non-minimal 2-byte" to "1900ff",
                "non-minimal 4-byte" to "1a0000ffff",
                "non-minimal 8-byte" to "1b00000000ffffffff",
                "reserved info" to "1c",
                "indefinite bytes" to "5f4101ff",
                "indefinite map" to "bf0101ff",
                "indefinite array" to "9f01ff",
                "tag" to "c11a514b67b0",
                "half float" to "f93c00",
                "double" to "fb3ff0000000000000",
                "undefined" to "f7",
                "simple 16" to "f0",
                "byte string past end" to "5a7fffffff00",
                "text past end" to "6568656c6c",
                "huge array count" to "9b7fffffffffffffff",
                "huge map count" to "bb00000000ffffffff",
                "array count past end" to "8301",
                "invalid utf-8" to "62c328",
                "utf-8 surrogate" to "63eda080",
                "unsorted keys" to "a2020001 00".replace(" ", ""),
                "duplicate keys" to "a201000100",
                "truncated head" to "19",
                "truncated map" to "a101",
            )
        for ((name, hex) in cases) {
            assertFailsWith<MalformedCborException>(name) { DeterministicCbor.checkWellFormed(hex.hexToBytes()) }
        }
        DeterministicCbor.checkWellFormed("a2010002f6".hexToBytes())
        DeterministicCbor.checkWellFormed("20".hexToBytes()) // -1
    }

    @Test
    fun nestingDepthIsLimited() {
        val nine = "81".repeat(9) + "00"
        DeterministicCbor.checkWellFormed(("81".repeat(8) + "00").hexToBytes())
        assertFailsWith<MalformedCborException> { DeterministicCbor.checkWellFormed(nine.hexToBytes()) }
        assertFailsWith<MalformedCborException> { DeterministicCbor.checkWellFormed("8100".hexToBytes(), maxDepth = 0) }
    }

    @Test
    fun decodeRejectsSchemaAndCanonicalViolations() {
        val good = DeterministicCbor.encode(Sample.serializer(), sample())
        val cases =
            mapOf(
                "explicit null optional" to RawCbor.map(1 to 500, 2 to byteArrayOf(1, 2), 3 to "hé", 4 to true, 6 to null),
                "unknown key" to RawCbor.map(1 to 500, 2 to byteArrayOf(1, 2), 3 to "hé", 4 to true, 7 to 1),
                "missing field" to RawCbor.map(1 to 500, 2 to byteArrayOf(1, 2), 3 to "hé"),
                "wrong type" to RawCbor.map(1 to "500", 2 to byteArrayOf(1, 2), 3 to "hé", 4 to true),
                "bytes as array" to RawCbor.map(1 to 500, 2 to listOf(1, 2), 3 to "hé", 4 to true),
                "init validation" to RawCbor.map(1 to 500, 2 to byteArrayOf(1, 2), 3 to "x".repeat(17), 4 to true),
                "integer overflow" to RawCbor.map(1 to 500, 2 to byteArrayOf(1, 2), 3 to "hé", 4 to true, 6 to (1L shl 33)),
                "top-level array" to listOf(1, 2),
            )
        for ((name, value) in cases) {
            assertFailsWith<MalformedCborException>(name) { DeterministicCbor.decode(Sample.serializer(), RawCbor.encode(value), 64) }
        }
        assertFailsWith<MalformedCborException> { DeterministicCbor.decode(Sample.serializer(), good, good.size - 1) }
    }

    @Test
    fun randomInputOnlyRaisesMalformedCborException() {
        val random = Random(8949)
        val good = DeterministicCbor.encode(Sample.serializer(), sample(optional = 7))
        repeat(20_000) { i ->
            val sample =
                if (i % 2 == 0) {
                    random.nextBytes(random.nextInt(0, 64))
                } else {
                    good.copyOf().also { b -> repeat(random.nextInt(1, 4)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
                }
            try {
                DeterministicCbor.decode(Sample.serializer(), sample, 128)
            } catch (e: MalformedCborException) {
                // expected
            } catch (e: Exception) {
                fail("expected MalformedCborException for ${sample.toHex()}, got $e")
            }
        }
    }
}
