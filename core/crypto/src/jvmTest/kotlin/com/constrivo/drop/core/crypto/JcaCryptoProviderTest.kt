package com.constrivo.drop.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JcaCryptoProviderTest {
    private val crypto = JcaCryptoProvider()

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm =
            crypto.hkdfSha256(
                ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
                salt = "000102030405060708090a0b0c".hexToBytes(),
                info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
                length = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun x25519MatchesRfc7748Section61() {
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val bobPub = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToBytes()
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            crypto.x25519(alicePriv, bobPub).toHex(),
        )
    }

    @Test
    fun x25519GeneratedPairsAgree() {
        val a = crypto.generateX25519()
        val b = crypto.generateX25519()
        assertContentEquals(crypto.x25519(a.privateKey, b.publicKey), crypto.x25519(b.privateKey, a.publicKey))
    }

    @Test
    fun ed25519MatchesRfc8032Test1() {
        val priv = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val pub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes()
        val sig = crypto.ed25519Sign(priv, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.toHex(),
        )
        assertTrue(crypto.ed25519Verify(pub, ByteArray(0), sig))
        assertFalse(crypto.ed25519Verify(pub, byteArrayOf(1), sig))
    }

    @Test
    fun aeadRoundTripsAndRejectsTampering() {
        for (alg in AeadAlgorithm.entries) {
            val aead = crypto.aead(alg, crypto.randomBytes(32))
            val nonce = crypto.randomBytes(12)
            val sealed = aead.seal(nonce, "hello".encodeToByteArray(), "aad".encodeToByteArray())
            assertEquals(5 + 16, sealed.size)
            assertEquals("hello", aead.open(nonce, sealed, "aad".encodeToByteArray()).decodeToString())
            sealed[0] = (sealed[0].toInt() xor 1).toByte()
            assertFailsWith<CryptoException> { aead.open(nonce, sealed, "aad".encodeToByteArray()) }
        }
    }

    @Test
    fun aeadSelectionIsSymmetricAndPrefersAesOnlyWhenBothHaveIt() {
        val aes = AeadAlgorithm.AES_256_GCM
        val chacha = AeadAlgorithm.CHACHA20_POLY1305
        assertEquals(aes, AeadAlgorithm.negotiate(aes, aes))
        assertEquals(chacha, AeadAlgorithm.negotiate(aes, chacha))
        assertEquals(chacha, AeadAlgorithm.negotiate(chacha, aes))
        assertEquals(chacha, AeadAlgorithm.negotiate(chacha, chacha))
        assertEquals(aes, AeadAlgorithm.preferred(hasAesHardware = true))
        assertEquals(chacha, AeadAlgorithm.preferred(hasAesHardware = false))
        for (algorithm in AeadAlgorithm.entries) assertEquals(algorithm, AeadAlgorithm.fromWireId(algorithm.wireId))
        assertEquals(null, AeadAlgorithm.fromWireId(0))
        assertEquals(null, AeadAlgorithm.fromWireId(3))
    }

    @Test
    fun malformedKeysRaiseCryptoException() {
        assertFailsWith<CryptoException> { crypto.ed25519Sign(ByteArray(31), ByteArray(0)) }
        assertFailsWith<CryptoException> { crypto.x25519(ByteArray(32), ByteArray(31)) }
        assertFailsWith<CryptoException> { crypto.x25519(ByteArray(32) { 1 }, ByteArray(32)) }
        assertFalse(crypto.ed25519Verify(ByteArray(31), ByteArray(0), ByteArray(64)))
        assertFalse(crypto.ed25519Verify(ByteArray(32), ByteArray(0), ByteArray(63)))
    }

    @Test
    fun smallOrderEd25519KeysNeverVerify() {
        val message = "any message".encodeToByteArray()
        for (key in TestFixtures.SMALL_ORDER_KEYS) {
            assertTrue(Ed25519PublicKeys.isSmallOrder(key), key.toHex())
            // Signatures anyone can make for such a key: R = the neutral element or the key itself, S = 0.
            val forgeries = listOf(TestFixtures.NEUTRAL_POINT + ByteArray(32), key + ByteArray(32), ByteArray(64))
            for (signature in forgeries) assertFalse(crypto.ed25519Verify(key, message, signature), key.toHex())
        }
        // The attack is real on this JDK: raw JCA accepts the forgery for the neutral element.
        assertTrue(TestFixtures.rawJcaEd25519Verify(TestFixtures.NEUTRAL_POINT, message, TestFixtures.NEUTRAL_POINT + ByteArray(32)))

        for (honest in listOf(TestFixtures.IDENTITY_A.publicKey, TestFixtures.IDENTITY_B.publicKey, crypto.generateEd25519().publicKey)) {
            assertFalse(Ed25519PublicKeys.isSmallOrder(honest))
        }
        assertFalse(Ed25519PublicKeys.isSmallOrder(ByteArray(31)))
        assertFalse(Ed25519PublicKeys.isSmallOrder(TestFixtures.NEUTRAL_POINT.copyOf().also { it[1] = 1 }))
    }

    @Test
    fun aeadInstanceIsReusableAfterAFailureAndRefusesARepeatedSealNonce() {
        for (alg in AeadAlgorithm.entries) {
            val aead = crypto.aead(alg, crypto.randomBytes(32))
            val n1 = ByteArray(12) { 1 }
            val n2 = ByteArray(12) { 2 }
            val sealed = aead.seal(n1, ByteArray(20), Aead.EMPTY)
            assertFailsWith<CryptoException> { aead.open(n1, sealed.copyOf().also { it[0] = 9 }, Aead.EMPTY) }
            assertContentEquals(ByteArray(20), aead.open(n1, sealed, Aead.EMPTY))
            aead.seal(n2, ByteArray(1), Aead.EMPTY)
            // The cached cipher remembers the last nonce, and SunJCE refuses to seal under it again.
            assertFailsWith<CryptoException> { aead.seal(n2, ByteArray(1), Aead.EMPTY) }
            assertFailsWith<CryptoException> { aead.seal(n2, ByteArray(1), Aead.EMPTY) }
            aead.seal(n1.copyOf().also { it[0] = 7 }, ByteArray(1), Aead.EMPTY)
            assertContentEquals(ByteArray(20), aead.open(n1, sealed, Aead.EMPTY))
        }
    }

    @Test
    fun aeadOffsetFormsAreCopySafe() {
        for (alg in AeadAlgorithm.entries) {
            val aead = crypto.aead(alg, ByteArray(32) { 5 })
            val nonce = ByteArray(12) { 3 }
            val plaintext = ByteArray(300) { it.toByte() }
            val expected = crypto.aead(alg, ByteArray(32) { 5 }).seal(nonce, plaintext, byteArrayOf(1))
            val buffer = ByteArray(400).also { plaintext.copyInto(it, 50) }
            assertEquals(316, aead.seal(nonce, buffer, 50, 300, byteArrayOf(1), buffer, 40))
            assertContentEquals(expected, buffer.copyOfRange(40, 356))
            assertEquals(300, aead.open(nonce, buffer, 40, 316, byteArrayOf(1), buffer, 60))
            assertContentEquals(plaintext, buffer.copyOfRange(60, 360))
            assertFailsWith<CryptoException> { aead.open(nonce, buffer, 0, 15, byteArrayOf(1), buffer, 0) }
            assertFailsWith<IllegalArgumentException> { aead.seal(ByteArray(12) { 4 }, buffer, 0, 300, Aead.EMPTY, ByteArray(315), 0) }
        }
    }

    @Test
    fun deviceIdIsFirst16BytesOfSha256() {
        val pk = crypto.generateEd25519().publicKey
        assertContentEquals(crypto.sha256(pk).copyOf(16), crypto.deviceId(pk))
    }
}
