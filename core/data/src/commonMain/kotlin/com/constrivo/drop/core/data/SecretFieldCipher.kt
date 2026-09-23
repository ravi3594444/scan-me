package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider

/**
 * Seals the secrets the device table holds (recognition secrets and peer advertising secrets, architecture §13, N11)
 * before they reach the database, and opens them on the way out.
 *
 * Whole-database encryption (SQLCipher behind [SqlDriverFactory], F-J2) protects the file; this layer adds
 * per-value protection, so a copy of the file without the key yields no secret, and a sealed value cannot be moved
 * to another row or column: [context] names the row and column and must be the same to open it.
 *
 * Choose the cipher at first install and keep it: values sealed by one cipher do not open with another.
 */
interface SecretFieldCipher {
    /** Returns the stored form of [plaintext] for the value named by [context]. */
    fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray

    /**
     * Returns the plaintext of a value [seal] produced for the same [context].
     *
     * @throws DataCorruptionException if [sealed] is malformed, was sealed for another context or with another key.
     */
    fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray

    companion object {
        /**
         * Stores secrets as `0x00 ‖ plaintext`, relying on whole-database encryption and app-private storage. The
         * default until a platform supplies an [AeadSecretFieldCipher].
         */
        val PLAINTEXT: SecretFieldCipher = PlaintextSecretFieldCipher
    }
}

private object PlaintextSecretFieldCipher : SecretFieldCipher {
    private const val FORMAT: Byte = 0

    override fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray = byteArrayOf(FORMAT) + plaintext

    override fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray {
        if (sealed.isEmpty() || sealed[0] != FORMAT) throw DataCorruptionException("$context: not a plaintext secret value")
        return sealed.copyOfRange(1, sealed.size)
    }

    override fun toString(): String = "SecretFieldCipher.PLAINTEXT"
}

/**
 * AES-256-GCM sealing with a 32-byte [key] the platform keeps in its Keystore / keychain-backed `SecretStorage`
 * (derive it with [DatabaseKeys.fieldKey]).
 *
 * Stored form: `0x01 ‖ nonce (12 random bytes) ‖ ciphertext ‖ tag (16)`, with associated data
 * `"drop-data-secret-v1" ‖ 0x00 ‖ context` (UTF-8). Random nonces are safe here: a device seals a few values per
 * pairing, far below the 2^32 limit for one key.
 */
class AeadSecretFieldCipher(
    private val crypto: CryptoProvider,
    key: ByteArray,
) : SecretFieldCipher {
    private val aead: Aead

    init {
        require(key.size == AeadAlgorithm.AES_256_GCM.keySize) { "the field key must be ${AeadAlgorithm.AES_256_GCM.keySize} bytes" }
        aead = crypto.aead(AeadAlgorithm.AES_256_GCM, key.copyOf())
    }

    override fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray {
        val nonce = crypto.randomBytes(NONCE_SIZE)
        val sealed = aead.seal(nonce, plaintext, associatedData(context))
        return byteArrayOf(FORMAT) + nonce + sealed
    }

    override fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray {
        if (sealed.size < 1 + NONCE_SIZE + TAG_SIZE || sealed[0] != FORMAT) {
            throw DataCorruptionException("$context: not an AEAD-sealed secret value")
        }
        val nonce = sealed.copyOfRange(1, 1 + NONCE_SIZE)
        val body = sealed.copyOfRange(1 + NONCE_SIZE, sealed.size)
        return try {
            aead.open(nonce, body, associatedData(context))
        } catch (e: CryptoException) {
            throw DataCorruptionException("$context: sealed secret does not authenticate", e)
        }
    }

    override fun toString(): String = "AeadSecretFieldCipher(<key redacted>)"

    private fun associatedData(context: String): ByteArray = LABEL + byteArrayOf(0) + context.encodeToByteArray()

    private companion object {
        const val FORMAT: Byte = 1
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        val LABEL = "drop-data-secret-v1".encodeToByteArray()
    }
}
