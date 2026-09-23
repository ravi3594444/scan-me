package com.constrivo.drop.core.crypto

/**
 * Named secret blobs kept at rest by the platform (spec change N11, architecture §13).
 *
 * Android Keystore (API 31–32), Windows CNG and the Secure Enclave cannot hold Ed25519 or X25519 keys, so the
 * identity seed, the advertising secret `k_adv` and the database key are ordinary byte arrays. Platform
 * implementations encrypt each value with a non-exportable AES key held by the Keystore or the OS keychain
 * before writing it to disk; this interface only sees plaintext in memory.
 *
 * Implementations must be thread-safe, must copy arrays on the way in and out, and must make [put] atomic
 * (a reader sees the old value or the new one, never a torn write).
 */
interface SecretStorage {
    /** The value stored under [name], or null if there is none. */
    fun get(name: String): ByteArray?

    /** Stores [value] under [name], replacing any previous value. */
    fun put(
        name: String,
        value: ByteArray,
    )

    /** Removes [name]; does nothing if it is absent. */
    fun delete(name: String)
}

/**
 * [SecretStorage] held in process memory only. For tests, and for sessions that must not persist anything;
 * values are lost when the object is garbage-collected.
 */
class InMemorySecretStorage : SecretStorage {
    private val lock = SynchronizedLock()
    private val values = HashMap<String, ByteArray>()

    override fun get(name: String): ByteArray? = lock.withLock { values[name]?.copyOf() }

    override fun put(
        name: String,
        value: ByteArray,
    ) {
        val copy = value.copyOf()
        lock.withLock { values[name] = copy }
    }

    override fun delete(name: String) {
        lock.withLock { values.remove(name) }?.fill(0)
    }

    /** Names currently stored, for tests. */
    fun names(): Set<String> = lock.withLock { values.keys.toSet() }
}
