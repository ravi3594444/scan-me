package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage

/**
 * The database master key (F-J2, architecture §13 with N11): 32 random bytes kept in the platform's
 * Keystore / keychain-backed [SecretStorage] under [STORAGE_NAME] as `0x01 ‖ key`, created on first use.
 *
 * Platforms derive two independent keys from it with HKDF-SHA256, so no key serves two algorithms:
 * [sqlCipherKey] for whole-file encryption in their [SqlDriverFactory], and [fieldKey] for an
 * [AeadSecretFieldCipher]. A stored entry that is malformed is reported, never replaced, because replacing it would
 * make the existing database unreadable.
 *
 * Thread-safe across instances: concurrent first calls create one key, even from separate `DatabaseKeys` over the
 * same storage (one lock for the process), and the key returned is always the one read back from storage. Sharing one
 * instance is still simplest.
 */
class DatabaseKeys(
    private val storage: SecretStorage,
    private val crypto: CryptoProvider,
) {
    /**
     * The master key, created and persisted on first use.
     *
     * @throws DataCorruptionException if the stored entry is not `0x01 ‖ 32 bytes`; the entry is left untouched.
     */
    fun masterKey(): ByteArray = LOCK.withLock { loadOrCreate() }

    private fun loadOrCreate(): ByteArray {
        load()?.let { return it }
        val key = crypto.randomBytes(KEY_SIZE)
        val entry = byteArrayOf(FORMAT) + key
        try {
            storage.put(STORAGE_NAME, entry)
        } finally {
            entry.fill(0)
            key.fill(0)
        }
        // Read back rather than trusting the put, so every caller gets the key the database will be opened with.
        return load() ?: throw DataCorruptionException("the database key was not kept by the secret storage")
    }

    private fun load(): ByteArray? {
        val entry = storage.get(STORAGE_NAME) ?: return null
        try {
            if (entry.size != KEY_SIZE + 1 || entry[0] != FORMAT) {
                throw DataCorruptionException("stored database key is malformed (${entry.size} bytes)")
            }
            return entry.copyOfRange(1, entry.size)
        } finally {
            entry.fill(0)
        }
    }

    /** The 32-byte key a platform passes to SQLCipher (`HKDF(master, "drop-db-sqlcipher-v1")`). */
    fun sqlCipherKey(): ByteArray = derive(SQLCIPHER_INFO)

    /** The 32-byte key of an [AeadSecretFieldCipher] (`HKDF(master, "drop-db-fields-v1")`). */
    fun fieldKey(): ByteArray = derive(FIELD_INFO)

    private fun derive(info: ByteArray): ByteArray {
        val master = masterKey()
        try {
            return crypto.hkdfSha256(master, ByteArray(0), info, KEY_SIZE)
        } finally {
            master.fill(0)
        }
    }

    companion object {
        /** The [SecretStorage] entry holding the master key. */
        const val STORAGE_NAME: String = "drop.database-key.v1"
        const val KEY_SIZE: Int = 32

        private const val FORMAT: Byte = 1

        /** One lock for every instance: first use is a get-then-put on shared storage. */
        private val LOCK = DataLock()
        private val SQLCIPHER_INFO = "drop-db-sqlcipher-v1".encodeToByteArray()
        private val FIELD_INFO = "drop-db-fields-v1".encodeToByteArray()
    }
}
