package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.InMemorySecretStorage
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP7b: the Android provider on both backends, against the vectors `core/crypto`'s JDK provider is tested with
 * (RFC 5869, RFC 7748, RFC 8032) plus the AEAD vectors (GCM test case 16, RFC 8439). On the JVM the "platform" is the
 * JDK, which has every algorithm; [BackendSelection.FALLBACK_ONLY] runs the BouncyCastle path a phone without them uses.
 */
class AndroidCryptoProviderTest {
    private val fallback = AndroidCryptoProvider(backends = BackendSelection.FALLBACK_ONLY, aesHardware = { false })
    private val platform = AndroidCryptoProvider(backends = BackendSelection.PLATFORM_IF_VERIFIED, aesHardware = { true })
    private val providers = listOf(fallback, platform)

    @Test
    fun backendsAreReportedPerSelection() {
        assertEquals(CryptoBackend.BOUNCY_CASTLE, fallback.x25519Backend)
        assertEquals(CryptoBackend.BOUNCY_CASTLE, fallback.ed25519Backend)
        assertEquals(CryptoBackend.BOUNCY_CASTLE, fallback.chaChaBackend)
        // JDK 21 has X25519, Ed25519 and ChaCha20-Poly1305, and they pass the probes.
        assertEquals(CryptoBackend.PLATFORM, platform.x25519Backend)
        assertEquals(CryptoBackend.PLATFORM, platform.ed25519Backend)
        assertEquals(CryptoBackend.PLATFORM, platform.chaChaBackend)
    }

    @Test
    fun sha256AndHmacMatchKnownVectors() {
        for (crypto in providers) {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                crypto.sha256("abc".encodeToByteArray()).toHex(),
            )
            // RFC 4231 test case 2.
            assertEquals(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
            )
            // An empty key is legal and equals a key of zero bytes.
            assertContentEquals(crypto.hmacSha256(ByteArray(1), byteArrayOf(1)), crypto.hmacSha256(ByteArray(0), byteArrayOf(1)))
        }
    }

    @Test
    fun hkdfMatchesRfc5869TestCases1And3() {
        for (crypto in providers) {
            val okm =
                crypto.hkdfSha256(
                    ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
                    salt = "000102030405060708090a0b0c".hexToBytes(),
                    info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
                    length = 42,
                )
            assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
            val empty = crypto.hkdfSha256("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(), ByteArray(0), ByteArray(0), 42)
            assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", empty.toHex())
            assertFailsWith<IllegalArgumentException> { crypto.hkdfSha256(ByteArray(1), ByteArray(0), ByteArray(0), 0) }
            assertFailsWith<IllegalArgumentException> { crypto.hkdfSha256(ByteArray(1), ByteArray(0), ByteArray(0), 255 * 32 + 1) }
        }
    }

    @Test
    fun x25519MatchesRfc7748() {
        for (crypto in providers) {
            val alicePrivate = Rfc7748.ALICE_PRIVATE.hexToBytes()
            val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes()
            assertEquals(Rfc7748.SHARED, crypto.x25519(alicePrivate, Rfc7748.BOB_PUBLIC.hexToBytes()).toHex())
            assertEquals(Rfc7748.SHARED, crypto.x25519(bobPrivate, Rfc7748.ALICE_PUBLIC.hexToBytes()).toHex())
            assertEquals(Rfc7748.OUT_5_2, crypto.x25519(Rfc7748.SCALAR_5_2.hexToBytes(), Rfc7748.U_5_2.hexToBytes()).toHex())
        }
    }

    @Test
    fun x25519GeneratedPairsAgreeAcrossBackends() {
        for (a in providers) {
            for (b in providers) {
                val pairA = a.generateX25519()
                val pairB = b.generateX25519()
                assertContentEquals(a.x25519(pairA.privateKey, pairB.publicKey), b.x25519(pairB.privateKey, pairA.publicKey))
            }
        }
        // And with core/crypto's JDK provider, which desktops use.
        val jdk = JcaCryptoProvider()
        val phone = fallback.generateX25519()
        val desktop = jdk.generateX25519()
        assertContentEquals(fallback.x25519(phone.privateKey, desktop.publicKey), jdk.x25519(desktop.privateKey, phone.publicKey))
    }

    @Test
    fun x25519RefusesTheAllZeroSecretAndWrongSizes() {
        for (crypto in providers) {
            // The neutral point u = 0 gives an all-zero shared secret for any scalar.
            assertFailsWith<CryptoException> { crypto.x25519(ByteArray(32) { 7 }, ByteArray(32)) }
            assertFailsWith<CryptoException> { crypto.x25519(ByteArray(31), ByteArray(32)) }
            assertFailsWith<CryptoException> { crypto.x25519(ByteArray(32), ByteArray(33)) }
        }
    }

    @Test
    fun ed25519MatchesRfc8032Tests1To3() {
        val vectors =
            Rfc8032.TESTS +
                Rfc8032.Vector(
                    seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                    publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                    message = "af82",
                    signature =
                        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
                )
        for (crypto in providers) {
            for (v in vectors) {
                val signature = crypto.ed25519Sign(v.seed.hexToBytes(), v.message.hexToBytes())
                assertEquals(v.signature, signature.toHex())
                assertTrue(crypto.ed25519Verify(v.publicKey.hexToBytes(), v.message.hexToBytes(), signature))
                assertFalse(crypto.ed25519Verify(v.publicKey.hexToBytes(), v.message.hexToBytes() + 1, signature))
                val tampered = signature.copyOf().also { it[40] = (it[40].toInt() xor 0x10).toByte() }
                assertFalse(crypto.ed25519Verify(v.publicKey.hexToBytes(), v.message.hexToBytes(), tampered))
            }
        }
    }

    @Test
    fun ed25519KeyGenerationDerivesTheRfcPublicKeyFromTheSeed() {
        for (v in Rfc8032.TESTS) {
            val seed = v.seed.hexToBytes()
            val provider = AndroidCryptoProvider(random = FixedRandom(seed), aesHardware = { true })
            val pair = provider.generateEd25519()
            assertContentEquals(seed, pair.privateKey)
            assertEquals(v.publicKey, pair.publicKey.toHex())
        }
        // X25519: 32 random bytes, public key X25519(k, 9).
        val alice =
            AndroidCryptoProvider(random = FixedRandom(Rfc7748.ALICE_PRIVATE.hexToBytes()), backends = BackendSelection.FALLBACK_ONLY)
        assertEquals(Rfc7748.ALICE_PUBLIC, alice.generateX25519().publicKey.toHex())
    }

    @Test
    fun signaturesVerifyAcrossBackendsAndWithTheJdkProvider() {
        val jdk = JcaCryptoProvider()
        val message = "drop-interop".encodeToByteArray()
        for (signer in providers + jdk) {
            val pair = signer.generateEd25519()
            val signature = signer.ed25519Sign(pair.privateKey, message)
            for (verifier in providers + jdk) assertTrue(verifier.ed25519Verify(pair.publicKey, message, signature), "$signer → $verifier")
        }
    }

    @Test
    fun smallOrderKeysAndWrongSizesNeverVerify() {
        val neutral = "0100000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val message = "anything".encodeToByteArray()
        for (crypto in providers) {
            // R = neutral, S = 0 is a valid signature under the neutral key for every message without the cofactor check.
            assertFalse(crypto.ed25519Verify(neutral, message, neutral + ByteArray(32)))
            assertFalse(crypto.ed25519Verify(ByteArray(32), message, ByteArray(64)))
            assertFalse(crypto.ed25519Verify(ByteArray(31), message, ByteArray(64)))
            assertFalse(crypto.ed25519Verify(Rfc8032.TESTS[0].publicKey.hexToBytes(), message, ByteArray(63)))
            // A public key that is not a point decodes to nothing and verifies nothing (y = 2 is not on the curve).
            val notAPoint = ByteArray(32).also { it[0] = 2 }
            assertFalse(crypto.ed25519Verify(notAPoint, message, ByteArray(64) { 1 }))
            assertFailsWith<CryptoException> { crypto.ed25519Sign(ByteArray(31), message) }
        }
    }

    @Test
    fun aeadsMatchTheirKnownAnswerVectors() {
        for (crypto in providers) {
            for ((algorithm, v) in listOf(
                AeadAlgorithm.AES_256_GCM to AeadVectors.AES_GCM,
                AeadAlgorithm.CHACHA20_POLY1305 to AeadVectors.CHACHA,
            )) {
                val aead = crypto.aead(algorithm, v.key.hexToBytes())
                assertEquals(algorithm, aead.algorithm)
                val sealed = aead.seal(v.nonce.hexToBytes(), v.plaintext.hexToBytes(), v.aad.hexToBytes())
                assertEquals(v.sealed, sealed.toHex())
                assertContentEquals(v.plaintext.hexToBytes(), aead.open(v.nonce.hexToBytes(), sealed, v.aad.hexToBytes()))
            }
        }
    }

    @Test
    fun aeadsRejectTamperingWrongAadAndShortInput() {
        for (crypto in providers) {
            for (algorithm in AeadAlgorithm.entries) {
                val aead = crypto.aead(algorithm, ByteArray(32) { it.toByte() })
                val nonce = ByteArray(12) { 9 }
                val sealed = aead.seal(nonce, "hello".encodeToByteArray(), "aad".encodeToByteArray())
                assertEquals(5 + 16, sealed.size)
                assertFailsWith<CryptoException> { aead.open(nonce, sealed, "aad!".encodeToByteArray()) }
                assertFailsWith<CryptoException> {
                    aead.open(nonce, sealed.copyOf().also { it[2] = (it[2] + 1).toByte() }, "aad".encodeToByteArray())
                }
                assertFailsWith<CryptoException> { aead.open(nonce, ByteArray(15), Aead.EMPTY) }
                assertFailsWith<CryptoException> { aead.seal(ByteArray(11), ByteArray(1), Aead.EMPTY) }
                // Still usable after failures.
                assertEquals("hello", aead.open(nonce, sealed, "aad".encodeToByteArray()).decodeToString())
            }
            assertFailsWith<CryptoException> { crypto.aead(AeadAlgorithm.AES_256_GCM, ByteArray(16)) }
        }
    }

    @Test
    fun aeadOffsetFormsWorkInPlaceAndMatchTheArrayForms() {
        for (crypto in providers) {
            for (algorithm in AeadAlgorithm.entries) {
                val key = ByteArray(32) { 5 }
                val nonce = ByteArray(12) { 3 }
                val plaintext = ByteArray(300) { it.toByte() }
                val expected = crypto.aead(algorithm, key).seal(nonce, plaintext, byteArrayOf(1))
                val aead = crypto.aead(algorithm, key)
                // Overlapping ranges: the output starts 10 bytes before the input.
                val buffer = ByteArray(400).also { plaintext.copyInto(it, 50) }
                assertEquals(316, aead.seal(nonce, buffer, 50, 300, byteArrayOf(1), buffer, 40))
                assertContentEquals(expected, buffer.copyOfRange(40, 356))
                assertEquals(300, aead.open(nonce, buffer, 40, 316, byteArrayOf(1), buffer, 60))
                assertContentEquals(plaintext, buffer.copyOfRange(60, 360))
                // Exactly in place.
                val same = expected.copyOf(expected.size)
                assertEquals(300, aead.open(nonce, same, 0, 316, byteArrayOf(1), same, 0))
                assertContentEquals(plaintext, same.copyOfRange(0, 300))
                assertFailsWith<IllegalArgumentException> { aead.seal(ByteArray(12) { 4 }, buffer, 0, 300, Aead.EMPTY, ByteArray(315), 0) }
                assertFailsWith<CryptoException> { aead.open(nonce, buffer, 0, 15, Aead.EMPTY, buffer, 0) }
            }
        }
    }

    @Test
    fun framesSealedByOneBackendOpenOnTheOther() {
        val jdk = JcaCryptoProvider()
        for (algorithm in AeadAlgorithm.entries) {
            val key = ByteArray(32) { (it * 7).toByte() }
            val nonce = ByteArray(12) { (it + 1).toByte() }
            val message = ByteArray(70_000) { (it % 251).toByte() }
            val sealed = fallback.aead(algorithm, key).seal(nonce, message, byteArrayOf(4, 2))
            assertContentEquals(message, jdk.aead(algorithm, key).open(nonce, sealed, byteArrayOf(4, 2)))
            assertContentEquals(message, platform.aead(algorithm, key).open(nonce, sealed, byteArrayOf(4, 2)))
        }
    }

    @Test
    fun probesRejectEnginesThatDoNotReproduceTheVectors() {
        val broken =
            object : X25519Engine {
                override val backend = CryptoBackend.PLATFORM

                override fun agree(
                    privateKey: ByteArray,
                    peerPublicKey: ByteArray,
                ) = ByteArray(32) { 1 }
            }
        assertFalse(CurveProbe.passesX25519Vectors(broken))
        assertTrue(CurveProbe.passesX25519Vectors(BouncyCastleX25519))
        val throwing =
            object : Ed25519Engine {
                override val backend = CryptoBackend.PLATFORM

                override fun sign(
                    seed: ByteArray,
                    message: ByteArray,
                ): ByteArray = throw CryptoException("no Ed25519 here")

                override fun verify(
                    publicKey: ByteArray,
                    message: ByteArray,
                    signature: ByteArray,
                ) = true
            }
        assertFalse(CurveProbe.passesEd25519Vectors(throwing))
        assertTrue(CurveProbe.passesEd25519Vectors(BouncyCastleEd25519))
        // An engine that accepts everything fails the tampered-signature check.
        val lenient =
            object : Ed25519Engine {
                override val backend = CryptoBackend.PLATFORM

                override fun sign(
                    seed: ByteArray,
                    message: ByteArray,
                ) = BouncyCastleEd25519.sign(seed, message)

                override fun verify(
                    publicKey: ByteArray,
                    message: ByteArray,
                    signature: ByteArray,
                ) = true
            }
        assertFalse(CurveProbe.passesEd25519Vectors(lenient))
        // An unknown algorithm name does not pass either.
        assertFalse(CurveProbe.passesX25519Vectors(JcaX25519("NoSuchCurve")))
        assertFalse(CurveProbe.passesEd25519Vectors(JcaEd25519("NoSuchCurve")))
    }

    @Test
    fun aesHardwareIsDecidedOnceByTheInjectedProbe() {
        var calls = 0
        val provider = AndroidCryptoProvider(aesHardware = { calls++ == 0 })
        assertTrue(provider.hasAesHardware)
        assertTrue(provider.hasAesHardware)
        assertEquals(1, calls)
        assertEquals(AeadAlgorithm.CHACHA20_POLY1305, AeadAlgorithm.preferred(fallback.hasAesHardware))
    }

    @Test
    fun randomBytesComeFromTheInjectedSource() {
        val provider = AndroidCryptoProvider(random = FixedRandom(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3, 1), provider.randomBytes(4))
        assertEquals(0, provider.randomBytes(0).size)
        assertFailsWith<IllegalArgumentException> { provider.randomBytes(-1) }
    }

    @Test
    fun identityStoreWorksOnTheFallbackProvider() {
        val storage = InMemorySecretStorage()
        val first = SoftwareIdentityKeyStore(storage, fallback).loadOrCreate()
        val again = SoftwareIdentityKeyStore(storage, platform).loadOrCreate()
        assertContentEquals(first.publicKey, again.publicKey)
        val message = "hello".encodeToByteArray()
        assertTrue(JcaCryptoProvider().ed25519Verify(first.publicKey, message, again.sign(message)))
        assertContentEquals(fallback.sha256(first.publicKey).copyOf(16), fallback.deviceId(first.publicKey))
    }

    /** A "random" source that repeats [bytes], so key generation is deterministic in tests. */
    private class FixedRandom(
        private val bytes: ByteArray,
    ) : SecureRandom() {
        private var index = 0

        override fun nextBytes(out: ByteArray) {
            for (i in out.indices) out[i] = bytes[index++ % bytes.size]
        }
    }
}
