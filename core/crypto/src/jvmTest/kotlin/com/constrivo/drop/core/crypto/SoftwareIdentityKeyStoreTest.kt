package com.constrivo.drop.core.crypto

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** F-B1 identity keys as software Ed25519 keys in [SecretStorage] (spec change N11). */
class SoftwareIdentityKeyStoreTest {
    private val crypto = TestFixtures.deterministicCrypto(seed = 11)

    @Test
    fun fB1_createsOnFirstUseAndPersists() {
        val storage = InMemorySecretStorage()
        val store = SoftwareIdentityKeyStore(storage, crypto)
        val key = store.loadOrCreate()
        assertEquals(32, key.publicKey.size)
        assertEquals(setOf(SoftwareIdentityKeyStore.STORAGE_NAME), storage.names())
        assertEquals(65, storage.get(SoftwareIdentityKeyStore.STORAGE_NAME)!!.size)
        assertSame(key, store.loadOrCreate(), "the store caches the loaded key")

        // A new store over the same storage (an app restart or update) loads the same identity.
        val reloaded = SoftwareIdentityKeyStore(storage, crypto).loadOrCreate()
        assertContentEquals(key.publicKey, reloaded.publicKey)
        val message = "hello".encodeToByteArray()
        assertTrue(crypto.ed25519Verify(key.publicKey, message, reloaded.sign(message)))
        assertContentEquals(key.sign(message), reloaded.sign(message), "Ed25519 is deterministic: same seed")
    }

    @Test
    fun fB1_resetReplacesTheKey() {
        val storage = InMemorySecretStorage()
        val store = SoftwareIdentityKeyStore(storage, crypto)
        val old = store.loadOrCreate().publicKey
        val fresh = store.reset()
        assertFalse(old.contentEquals(fresh.publicKey))
        assertContentEquals(fresh.publicKey, store.loadOrCreate().publicKey)
        assertContentEquals(fresh.publicKey, SoftwareIdentityKeyStore(storage, crypto).loadOrCreate().publicKey)
        assertTrue(crypto.ed25519Verify(fresh.publicKey, byteArrayOf(1), fresh.sign(byteArrayOf(1))))
    }

    @Test
    fun corruptedEntriesAreReportedNotReplaced() {
        val good = InMemorySecretStorage().also { SoftwareIdentityKeyStore(it, crypto).loadOrCreate() }
        val entry = good.get(SoftwareIdentityKeyStore.STORAGE_NAME)!!
        val corruptions =
            listOf(
                entry.copyOf(64),
                entry + byteArrayOf(0),
                entry.copyOf().also { it[0] = 2 },
                entry.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }, // public key no longer matches the seed
                entry.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }, // seed no longer matches the public key
                ByteArray(0),
            )
        for (bad in corruptions) {
            val storage = InMemorySecretStorage().also { it.put(SoftwareIdentityKeyStore.STORAGE_NAME, bad) }
            assertFailsWith<CryptoException> { SoftwareIdentityKeyStore(storage, crypto).loadOrCreate() }
            assertContentEquals(bad, storage.get(SoftwareIdentityKeyStore.STORAGE_NAME), "the entry is left for the user to reset")
        }
    }

    @Test
    fun publicKeyIsADefensiveCopy() {
        val key = SoftwareIdentityKeyStore(InMemorySecretStorage(), crypto).loadOrCreate()
        val copy = key.publicKey
        copy.fill(0)
        assertFalse(key.publicKey.all { it == 0.toByte() })
        assertFalse(key.toString().contains(copy.toHex()))
    }

    @Test
    fun concurrentFirstUseCreatesOneIdentity() {
        val storage = InMemorySecretStorage()
        val store = SoftwareIdentityKeyStore(storage, JcaCryptoProvider())
        val pool = Executors.newFixedThreadPool(8)
        val keys = pool.invokeAll((0 until 8).map { Callable { store.loadOrCreate().publicKey.toHex() } }).map { it.get() }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(1, keys.toSet().size)
        assertEquals(keys.first(), SoftwareIdentityKeyStore(storage, crypto).loadOrCreate().publicKey.toHex())
    }

    @Test
    fun inMemoryStorageCopiesValues() {
        val storage = InMemorySecretStorage()
        val value = byteArrayOf(1, 2, 3)
        storage.put("a", value)
        value[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), storage.get("a"))
        storage.get("a")!![1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), storage.get("a"))
        storage.put("a", byteArrayOf(4))
        assertContentEquals(byteArrayOf(4), storage.get("a"))
        storage.delete("a")
        assertNull(storage.get("a"))
        storage.delete("missing")
        assertTrue(storage.names().isEmpty())
    }
}
