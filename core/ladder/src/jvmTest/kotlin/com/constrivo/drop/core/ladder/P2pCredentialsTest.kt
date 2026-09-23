package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Wi-Fi Direct credentials (architecture §8 notes, S5, N7). */
class P2pCredentialsTest {
    private val jca = JcaCryptoProvider()

    /** JCA for everything but randomness, which comes from [random] so the test is deterministic. */
    private class SeededCrypto(
        private val random: (Int) -> ByteArray,
        delegate: CryptoProvider = JcaCryptoProvider(),
    ) : CryptoProvider by delegate {
        override fun randomBytes(size: Int): ByteArray = random(size)
    }

    private val symbol = Regex("[abcdefghijkmnpqrstuvwxyz23456789]")
    private val randomName = Regex("^DIRECT-${symbol.pattern}{2}-Drop-${symbol.pattern}{4}$")

    @Test
    fun fE2_randomCredentialsAreValidAndUseTheUnambiguousAlphabet() {
        val seeded = Random(7)
        val crypto = SeededCrypto({ seeded.nextBytes(it) })
        val seen = HashSet<String>()
        repeat(1_000) {
            val c = P2pCredentials.random(crypto)
            assertTrue(randomName.matches(c.ssid), c.ssid)
            assertTrue(c.ssid.encodeToByteArray().size <= P2pCredentials.MAX_SSID_BYTES)
            assertEquals(P2pCredentials.RANDOM_PASSPHRASE_LENGTH, c.passphrase.length)
            assertTrue(c.passphrase.all { symbol.matches(it.toString()) }, c.passphrase)
            assertTrue(P2pCredentials.isValidNetworkName(c.ssid))
            assertTrue(P2pCredentials.isValidPassphrase(c.passphrase))
            seen += c.ssid + c.passphrase
        }
        assertEquals(1_000, seen.size)
    }

    @Test
    fun fE2_randomBytesMapToSymbolsByTheirLowFiveBits() {
        val counter = SeededCrypto({ size -> ByteArray(size) { it.toByte() } })
        assertEquals(WifiCredentials("DIRECT-ab-Drop-cdef", "abcdefghijkm"), P2pCredentials.random(counter))
        val highBits = SeededCrypto({ size -> ByteArray(size) { (0xE0 or it).toByte() } })
        assertEquals(WifiCredentials("DIRECT-ab-Drop-cdef", "abcdefghijkm"), P2pCredentials.random(highBits))
        val top = SeededCrypto({ size -> ByteArray(size) { 0x1F } })
        assertEquals(WifiCredentials("DIRECT-99-Drop-9999", "999999999999"), P2pCredentials.random(top))
    }

    @Test
    fun fE2_realRandomSourceProducesValidCredentials() {
        repeat(50) { P2pCredentials.requireValidGroup(P2pCredentials.random(jca)) }
    }

    @Test
    fun n7_trustedPairCredentialsAreStableAndMatchAnIndependentHkdf() {
        val secret = ByteArray(32) { it.toByte() }
        // Computed with Python's hmac/hashlib (RFC 5869, empty salt).
        val expected = WifiCredentials("DIRECT-ww-Drop-ym64", "sxn2fufzfxnf862e")
        assertEquals(expected, P2pCredentials.forTrustedPair(jca, secret))
        // The other device of the pair, with its own provider instance, derives the same values.
        assertEquals(expected, P2pCredentials.forTrustedPair(JcaCryptoProvider(), secret.copyOf()))
        assertEquals(
            WifiCredentials("DIRECT-yd-Drop-uvvv", "8ft44af4bjar6sxg"),
            P2pCredentials.forTrustedPair(
                jca,
                ByteArray(32) {
                    0xA5.toByte()
                },
            ),
        )
        assertEquals(P2pCredentials.PAIR_PASSPHRASE_LENGTH, expected.passphrase.length)
    }

    @Test
    fun n7_differentPairsGetDifferentCredentials() {
        val a = P2pCredentials.forTrustedPair(jca, ByteArray(32) { 1 })
        val b = P2pCredentials.forTrustedPair(jca, ByteArray(32) { 2 })
        assertNotEquals(a.ssid, b.ssid)
        assertNotEquals(a.passphrase, b.passphrase)
    }

    @Test
    fun n7_forTransferPicksStableForTrustedAndRandomOtherwise() {
        val secret = ByteArray(32) { 9 }
        assertEquals(P2pCredentials.forTrustedPair(jca, secret), P2pCredentials.forTransfer(jca, secret))
        val counter = SeededCrypto({ size -> ByteArray(size) { it.toByte() } })
        assertEquals(WifiCredentials("DIRECT-ab-Drop-cdef", "abcdefghijkm"), P2pCredentials.forTransfer(counter, null))
    }

    @Test
    fun badInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { P2pCredentials.forTrustedPair(jca, ByteArray(31)) }
        val short = SeededCrypto({ size -> ByteArray(size - 1) })
        assertFailsWith<IllegalStateException> { P2pCredentials.random(short) }
    }

    @Test
    fun groupCredentialsFollowAndroidsRules() {
        fun valid(
            ssid: String,
            pass: String = "abcdefgh",
        ) = runCatching { P2pCredentials.requireValidGroup(WifiCredentials(ssid, pass)) }.isSuccess

        assertTrue(valid("DIRECT-ab"))
        assertTrue(valid("DIRECT-Z9-anything goes here"))
        assertTrue(valid("DIRECT-ab-" + "x".repeat(22))) // exactly 32 bytes
        assertFalse(valid("DIRECT-ab-" + "x".repeat(23))) // 33 bytes
        assertFalse(valid("DIRECT-a"))
        assertFalse(valid("DIRECT-a!"))
        assertFalse(valid("direct-ab"))
        assertFalse(valid("DIRECTab"))
        assertFalse(valid("xDIRECT-ab"))
        assertFalse(valid("DIRECT-ab\nx"))
        assertFalse(valid("DIRECT-ab-" + "é".repeat(12))) // 34 bytes of UTF-8

        assertTrue(valid("DIRECT-ab", "a".repeat(63)))
        assertTrue(valid("DIRECT-ab", "pass word!~ 123"))
        assertFalse(valid("DIRECT-ab", "a".repeat(64)))
        assertFalse(valid("DIRECT-ab", "pässwort1"))
        assertFalse(valid("DIRECT-ab", "abcdefg\u0001"))
    }

    @Test
    fun invalidCredentialsNeverLeakThePassphrase() {
        val secret = "päss-secret-value"
        val error = assertFailsWith<LinkCredentialsException> { P2pCredentials.requireValidGroup(WifiCredentials("DIRECT-ab", secret)) }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun hotspotCredentialsAcceptSystemGeneratedValues() {
        fun valid(
            ssid: String,
            pass: String,
        ) = runCatching { P2pCredentials.requireValidHotspot(WifiCredentials(ssid, pass)) }.isSuccess

        assertTrue(valid("AndroidShare_4821", "k3v9-2mxq"))
        assertTrue(valid("x", "12345678"))
        assertTrue(valid("Pixel", "0123456789abcdefABCDEF0123456789abcdefABCDEF0123456789abcdef0123")) // raw 64-hex PSK
        assertFalse(valid("Pixel", "0123456789abcdefABCDEF0123456789abcdefABCDEF0123456789abcdef012g"))
        assertFalse(valid("Pixel", "pässwort1"))
        assertFalse(valid("Pixel", "a".repeat(65)))
    }
}
