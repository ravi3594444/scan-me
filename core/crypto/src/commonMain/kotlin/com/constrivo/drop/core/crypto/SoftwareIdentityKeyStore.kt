package com.constrivo.drop.core.crypto

/**
 * The device identity as a software Ed25519 key (F-B1; spec change N11, architecture §13).
 *
 * The 32-byte seed and the 32-byte public key are kept together in one [SecretStorage] entry, [STORAGE_NAME]:
 * `version (0x01) ‖ seed ‖ public key`, 65 bytes. The public key is stored because JCA cannot derive it from a seed;
 * keeping both in a single entry makes creation and [reset] atomic. On every load the pair is checked by signing and
 * verifying a probe message, so a corrupted or mismatched entry is reported instead of silently producing a
 * different identity.
 *
 * The key survives app updates because the platform's [SecretStorage] does; it is lost on uninstall (F-B1).
 * "Reset identity" is [reset] together with [com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore.rotate]
 * and forgetting every trusted peer.
 *
 * Thread-safe: concurrent callers of [loadOrCreate] get the same key.
 */
class SoftwareIdentityKeyStore(
    private val storage: SecretStorage,
    private val crypto: CryptoProvider,
) : IdentityKeyStore {
    private val lock = SynchronizedLock()
    private var cached: SoftwareIdentityKey? = null

    /**
     * Returns the stored identity, creating and persisting one on first use.
     *
     * @throws CryptoException if the stored entry is corrupted (wrong length or version, or the seed does not match
     *   the stored public key). The entry is left untouched so the app can offer "Reset identity".
     */
    override fun loadOrCreate(): IdentityKey =
        lock.withLock {
            cached ?: (load() ?: create()).also { cached = it }
        }

    /** Replaces the identity with a new key and persists it ("Reset identity"). Trusted peers no longer recognise it. */
    override fun reset(): IdentityKey =
        lock.withLock {
            create().also { cached = it }
        }

    private fun load(): SoftwareIdentityKey? {
        val entry = storage.get(STORAGE_NAME) ?: return null
        try {
            if (entry.size != ENTRY_SIZE || entry[0] != FORMAT_VERSION) {
                throw CryptoException("stored identity key is corrupted (${entry.size} bytes)")
            }
            val seed = entry.copyOfRange(1, 33)
            val publicKey = entry.copyOfRange(33, ENTRY_SIZE)
            val probe = PROBE_MESSAGE
            val signature =
                try {
                    crypto.ed25519Sign(seed, probe)
                } catch (e: CryptoException) {
                    throw CryptoException("stored identity key is corrupted", e)
                }
            if (!crypto.ed25519Verify(publicKey, probe, signature)) {
                throw CryptoException("stored identity seed does not match its public key")
            }
            return SoftwareIdentityKey(publicKey, seed, crypto)
        } finally {
            entry.fill(0)
        }
    }

    private fun create(): SoftwareIdentityKey {
        val pair = crypto.generateEd25519()
        check(pair.publicKey.size == 32 && pair.privateKey.size == 32) { "provider returned a malformed Ed25519 key pair" }
        val entry = byteArrayOf(FORMAT_VERSION) + pair.privateKey + pair.publicKey
        try {
            storage.put(STORAGE_NAME, entry)
        } finally {
            entry.fill(0)
        }
        return SoftwareIdentityKey(pair.publicKey.copyOf(), pair.privateKey.copyOf(), crypto).also { pair.privateKey.fill(0) }
    }

    companion object {
        /** The [SecretStorage] entry holding the identity. */
        const val STORAGE_NAME: String = "drop.identity.ed25519.v1"

        private const val FORMAT_VERSION: Byte = 1
        private const val ENTRY_SIZE = 65
        private val PROBE_MESSAGE = "drop-identity-probe-v1".encodeToByteArray()
    }
}

/** An Ed25519 identity whose seed lives in memory; signs through [CryptoProvider.ed25519Sign]. */
private class SoftwareIdentityKey(
    private val publicKeyBytes: ByteArray,
    private val seed: ByteArray,
    private val crypto: CryptoProvider,
) : IdentityKey {
    override val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    override fun sign(message: ByteArray): ByteArray = crypto.ed25519Sign(seed, message)

    override fun toString(): String = "IdentityKey(${publicKeyBytes.copyOf(8).toHex()}…)"
}
