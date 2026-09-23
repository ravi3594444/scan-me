package com.constrivo.drop.core.crypto.trust

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.InMemorySecretStorage
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shared advertising secret (spec change S3) and the trusted proof (architecture §5.3). */
class TrustTest {
    private val crypto = TestFixtures.deterministicCrypto(seed = 3)

    @Test
    fun s3_advertisingSecretIsCreatedOnceAndPersisted() {
        val storage = InMemorySecretStorage()
        val store = AdvertisingSecretStore(storage, crypto)
        val first = store.current()
        assertEquals(first, store.current())
        assertEquals(first, AdvertisingSecretStore(storage, crypto).current())
        assertEquals(AdvertisingSecret.SIZE + 1, storage.get(AdvertisingSecretStore.STORAGE_NAME)!!.size)
    }

    @Test
    fun s3_rotationOnForgetOrResetReplacesTheSecret() {
        val storage = InMemorySecretStorage()
        val store = AdvertisingSecretStore(storage, crypto)
        val before = store.current()
        val after = store.rotate()
        assertNotEquals(before, after)
        assertEquals(after, store.current())
        assertEquals(after, AdvertisingSecretStore(storage, crypto).current())
    }

    @Test
    fun corruptedAdvertisingSecretIsReported() {
        for (bad in listOf(ByteArray(32), ByteArray(33).also { it[0] = 2 }, ByteArray(34))) {
            val storage = InMemorySecretStorage().also { it.put(AdvertisingSecretStore.STORAGE_NAME, bad) }
            assertFailsWith<CryptoException> { AdvertisingSecretStore(storage, crypto).current() }
        }
    }

    @Test
    fun advertisingSecretValueSemantics() {
        val bytes = ByteArray(32) { it.toByte() }
        val secret = AdvertisingSecret(bytes)
        bytes[0] = 99
        assertEquals(0, secret.bytes()[0], "constructor copies")
        secret.bytes()[1] = 99
        assertEquals(1, secret.bytes()[1], "bytes() copies")
        assertEquals(secret, AdvertisingSecret.fromPeer(ByteArray(32) { it.toByte() }))
        assertEquals(secret.hashCode(), AdvertisingSecret(ByteArray(32) { it.toByte() }).hashCode())
        assertFalse(secret.toString().contains(secret.bytes().toHex()))
        assertFailsWith<IllegalArgumentException> { AdvertisingSecret(ByteArray(31)) }
        assertFailsWith<CryptoException> { AdvertisingSecret.fromPeer(ByteArray(33)) }
        assertNotEquals(AdvertisingSecret.generate(crypto), AdvertisingSecret.generate(crypto))
    }

    @Test
    fun trustedProofMatchesItsDefinition() {
        val secret = ByteArray(32) { (0xA0 + it).toByte() }
        val identity = TestFixtures.IDENTITY_A.publicKey
        val commitment = "ba67c4f1d15b27d8b598935d9b089def94e6be1d54a401323b44c6ef3d218f3a".hexToBytes()
        val expected = hmac(secret, "drop-proof-v1".encodeToByteArray() + identity + commitment)
        val proof = TrustedProof.proof(crypto, secret, identity, commitment)
        assertContentEquals(expected, proof)
        // The golden Hello-with-proof carries exactly this value (HandshakeTest.GOLDEN_HELLO_WITH_PROOF, key 8).
        assertEquals("3665bb9861d4ed4963418ef9f3d9fc1a3551e8edba85ff5d16e28b205ef2230e", proof.toHex())
        assertTrue(TrustedProof.verifyProof(crypto, secret, identity, commitment, proof))
        assertFalse(TrustedProof.verifyProof(crypto, secret, TestFixtures.IDENTITY_B.publicKey, commitment, proof))
        assertFalse(TrustedProof.verifyProof(crypto, secret, identity, ByteArray(32), proof))
        assertFalse(TrustedProof.verifyProof(crypto, ByteArray(32), identity, commitment, proof))
        assertFalse(TrustedProof.verifyProof(crypto, secret, identity, commitment, proof.copyOf(31)))
        assertFalse(TrustedProof.verifyProof(crypto, secret.copyOf(16), identity, commitment, proof))

        val ack = TrustedProof.ack(crypto, secret, TestFixtures.IDENTITY_B.publicKey, commitment)
        assertContentEquals(hmac(secret, "drop-proof-ack-v1".encodeToByteArray() + TestFixtures.IDENTITY_B.publicKey + commitment), ack)
        assertTrue(TrustedProof.verifyAck(crypto, secret, TestFixtures.IDENTITY_B.publicKey, commitment, ack))
        assertFalse(TrustedProof.verifyAck(crypto, secret, TestFixtures.IDENTITY_B.publicKey, commitment, proof), "proof ≠ ack")
        assertFailsWith<IllegalArgumentException> { TrustedProof.proof(crypto, ByteArray(31), identity, commitment) }
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
