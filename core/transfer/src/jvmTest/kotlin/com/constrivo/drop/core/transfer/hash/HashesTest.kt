package com.constrivo.drop.core.transfer.hash

import com.constrivo.drop.core.transfer.TestSupport
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §7.3: XXH3-128 in canonical form (matches `xxhsum -H2`), streaming SHA-256 (S2). */
class HashesTest {
    private val xxh3 = xxh3ChunkHasher()

    @Test
    fun `XXH3-128 matches the reference vectors in canonical byte order`() {
        assertEquals("99aa06d3014798d86001c324468d497f", xxh3.hash(ByteArray(0), 0, 0).toHex())
        val abc = "abc".encodeToByteArray()
        assertEquals("06b05ab6733a618578af5f94892f3950", xxh3.hash(abc, 0, abc.size).toHex())
        val framed = byteArrayOf(9, 9) + abc + byteArrayOf(9)
        assertEquals(xxh3.hash(abc, 0, 3), xxh3.hash(framed, 2, 3), "only the range counts")
    }

    @Test
    fun `matches detects a single flipped bit`() {
        val data = TestSupport.randomBytes(4 * 1024 * 1024, 21)
        val hash = xxh3.hash(data, 0, data.size)
        assertTrue(xxh3.matches(hash, data, 0, data.size))
        data[1_000_000] = (data[1_000_000].toInt() xor 0x10).toByte()
        assertFalse(xxh3.matches(hash, data, 0, data.size))
    }

    @Test
    fun `streaming SHA-256 equals the one-shot digest and resets after each digest`() {
        val data = TestSupport.randomBytes(1_000_003, 22)
        val digest = sha256Digest()
        var at = 0
        for (step in listOf(1, 63, 64, 65_536, 400_000)) {
            digest.update(data, at, step)
            at += step
        }
        digest.update(data, at, data.size - at)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), digest.digest())
        assertContentEquals(EMPTY_SHA256, digest.digest(), "digest() resets the instance")
        val emptyHex = EMPTY_SHA256.joinToString("") { "%02x".format(it) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHex)
        digest.update(data, 0, 10)
        digest.reset()
        assertContentEquals(EMPTY_SHA256, digest.digest())
    }
}
