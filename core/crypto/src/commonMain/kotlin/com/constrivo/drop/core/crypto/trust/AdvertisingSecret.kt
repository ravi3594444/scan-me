package com.constrivo.drop.core.crypto.trust

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.crypto.SynchronizedLock
import com.constrivo.drop.core.crypto.constantTimeEquals

/**
 * The per-device advertising secret `k_adv` (architecture §5.3; spec change S3): 32 random bytes from which the
 * rotating beacon ID is derived (`eph_id = HMAC-SHA256(k_adv, epoch)[0..6]`, computed in `core/discovery`).
 *
 * S3: every trusted peer receives the same `k_adv` inside an encrypted session, so all of them resolve the same
 * rotating ID. It is rotated on "Reset identity" and on "Forget" (so a forgotten device can no longer resolve the
 * beacon) and re-shared with the remaining trusted peers at their next contact. The protocol message that carries it
 * is defined in `core/protocol`; this type only validates and holds the bytes.
 *
 * Equality is by content, in constant time. [toString] never prints the secret.
 */
class AdvertisingSecret(
    bytes: ByteArray,
) {
    private val value: ByteArray

    init {
        require(bytes.size == SIZE) { "k_adv must be $SIZE bytes, was ${bytes.size}" }
        value = bytes.copyOf()
    }

    /** A copy of the 32 secret bytes (the HMAC key for `eph_id`, and the payload of the share message). */
    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is AdvertisingSecret && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AdvertisingSecret(<redacted>)"

    companion object {
        const val SIZE: Int = 32

        /** A fresh random secret. */
        fun generate(crypto: CryptoProvider): AdvertisingSecret = AdvertisingSecret(crypto.randomBytes(SIZE))

        /**
         * Parses a secret received from a peer (the payload of the protocol's share message).
         *
         * @throws CryptoException if [bytes] is not exactly [SIZE] bytes.
         */
        fun fromPeer(bytes: ByteArray): AdvertisingSecret {
            if (bytes.size != SIZE) throw CryptoException("peer k_adv must be $SIZE bytes, was ${bytes.size}")
            return AdvertisingSecret(bytes)
        }
    }
}

/**
 * Keeps this device's [AdvertisingSecret] in [SecretStorage] under [STORAGE_NAME] (`0x01 ‖ k_adv`).
 * Thread-safe.
 */
class AdvertisingSecretStore(
    private val storage: SecretStorage,
    private val crypto: CryptoProvider,
) {
    private val lock = SynchronizedLock()
    private var cached: AdvertisingSecret? = null

    /**
     * The current secret, created and persisted on first use.
     *
     * @throws CryptoException if the stored entry is corrupted; the entry is left untouched.
     */
    fun current(): AdvertisingSecret =
        lock.withLock {
            cached ?: (load() ?: store(AdvertisingSecret.generate(crypto))).also { cached = it }
        }

    /**
     * Replaces the secret with a fresh one and persists it. Call on "Reset identity" and on "Forget"; afterwards
     * share the new value with every remaining trusted peer at its next session (S3).
     */
    fun rotate(): AdvertisingSecret =
        lock.withLock {
            store(AdvertisingSecret.generate(crypto)).also { cached = it }
        }

    private fun load(): AdvertisingSecret? {
        val entry = storage.get(STORAGE_NAME) ?: return null
        try {
            if (entry.size != AdvertisingSecret.SIZE + 1 || entry[0] != FORMAT_VERSION) {
                throw CryptoException("stored advertising secret is corrupted (${entry.size} bytes)")
            }
            return AdvertisingSecret(entry.copyOfRange(1, entry.size))
        } finally {
            entry.fill(0)
        }
    }

    private fun store(secret: AdvertisingSecret): AdvertisingSecret {
        val raw = secret.bytes()
        val entry = byteArrayOf(FORMAT_VERSION) + raw
        try {
            storage.put(STORAGE_NAME, entry)
        } finally {
            raw.fill(0)
            entry.fill(0)
        }
        return secret
    }

    companion object {
        /** The [SecretStorage] entry holding `k_adv`. */
        const val STORAGE_NAME: String = "drop.advertising-secret.v1"

        private const val FORMAT_VERSION: Byte = 1
    }
}
