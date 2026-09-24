package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N11: the file format, atomic replacement and failure reporting of [AndroidSecretStorage], with a software AES-GCM
 * cipher standing in for the Keystore key (the Keystore itself is exercised by the instrumented test).
 */
class AndroidSecretStorageTest {
    private val dir: File = Files.createTempDirectory("drop-secrets").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** AES-256-GCM with a fixed key and a counter IV: deterministic, and it authenticates the AAD like the Keystore key. */
    private class FakeCipher(
        private val key: ByteArray = ByteArray(32) { 42 },
    ) : SecretCipher {
        var counter = 0L
        var failNext: Exception? = null

        override fun seal(
            plaintext: ByteArray,
            aad: ByteArray,
        ): SealedSecret {
            failNext?.let {
                failNext = null
                throw it
            }
            val iv = ByteArray(12).also { iv -> for (i in 0 until 8) iv[4 + i] = (++counter ushr (8 * (7 - i))).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return SealedSecret(iv, cipher.doFinal(plaintext))
        }

        override fun open(
            sealed: SealedSecret,
            aad: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed.ciphertext)
        }
    }

    private val cipher = FakeCipher()
    private val storage = AndroidSecretStorage(dir, cipher)

    @Test
    fun storesReplacesAndDeletes() {
        assertNull(storage.get("a"))
        storage.put("a", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), storage.get("a"))
        storage.put("a", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), storage.get("a"))
        storage.put("b", ByteArray(0))
        assertContentEquals(ByteArray(0), storage.get("b"))
        storage.delete("a")
        assertNull(storage.get("a"))
        storage.delete("a")
        storage.delete("never-stored")
        assertContentEquals(ByteArray(0), storage.get("b"))
    }

    @Test
    fun valuesSurviveANewInstanceAndAreCopiedInAndOut() {
        val value = byteArrayOf(5, 6, 7)
        storage.put("k", value)
        value[0] = 0
        val read = storage.get("k")!!
        assertContentEquals(byteArrayOf(5, 6, 7), read)
        read[1] = 0
        assertContentEquals(byteArrayOf(5, 6, 7), AndroidSecretStorage(dir, FakeCipher()).get("k"))
    }

    @Test
    fun fileFormatIsVersionedAndHoldsNoPlaintext() {
        val secret = "correct horse battery staple".encodeToByteArray()
        storage.put("drop.identity.ed25519.v1", secret)
        val file = storage.fileFor("drop.identity.ed25519.v1")
        assertEquals(dir, file.parentFile)
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.secret")), file.name)
        val bytes = file.readBytes()
        assertContentEquals("DRSS".encodeToByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt())
        assertEquals(12, bytes[5].toInt())
        assertEquals(6 + 12 + secret.size + 16, bytes.size)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("horse"))
        assertEquals(listOf(file.name), dir.list()!!.toList())
    }

    @Test
    fun namesNeverEscapeTheDirectory() {
        for (name in listOf("../../etc/passwd", "/abs", "a/b", "", "日本語", "x".repeat(500))) {
            storage.put(name, name.encodeToByteArray())
            assertEquals(dir, storage.fileFor(name).parentFile)
            assertContentEquals(name.encodeToByteArray(), storage.get(name))
        }
        assertEquals(6, dir.list()!!.size)
    }

    @Test
    fun anEntryMovedToAnotherNameDoesNotOpen() {
        storage.put("alpha", byteArrayOf(1))
        storage.put("beta", byteArrayOf(2))
        storage.fileFor("alpha").copyTo(storage.fileFor("beta"), overwrite = true)
        val e = assertFailsWith<SecretStorageException> { storage.get("beta") }
        assertTrue(e.cause is GeneralSecurityException)
        // The failure is a CryptoException, which the identity store reports as a corrupted entry.
        assertTrue(e is CryptoException)
    }

    @Test
    fun corruptedFilesAreReportedNotThrownAsIndexErrors() {
        storage.put("x", byteArrayOf(1, 2, 3, 4))
        val file = storage.fileFor("x")
        val good = file.readBytes()
        val damaged =
            listOf(
                ByteArray(0),
                good.copyOf(5),
                good.copyOf(6 + 12 + 15),
                good.copyOf().also { it[0] = 'X'.code.toByte() },
                good.copyOf().also { it[4] = 2 },
                good.copyOf().also { it[5] = 0 },
                good.copyOf().also { it[5] = 200.toByte() },
                good.copyOf().also { it[good.size - 1] = (it[good.size - 1] + 1).toByte() },
                good.copyOf().also { it[10] = (it[10] + 1).toByte() },
                ByteArray(AndroidSecretStorage.MAX_VALUE_BYTES + 4096),
            )
        for ((index, bytes) in damaged.withIndex()) {
            file.writeBytes(bytes)
            assertFailsWith<SecretStorageException>("case $index") { storage.get("x") }
        }
        file.writeBytes(good)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), storage.get("x"))
    }

    @Test
    fun cipherFailuresBecomeSecretStorageExceptionsAndLeaveTheOldValue() {
        storage.put("k", byteArrayOf(1))
        cipher.failNext = GeneralSecurityException("keystore locked")
        assertFailsWith<SecretStorageException> { storage.put("k", byteArrayOf(2)) }
        cipher.failNext = CryptoException("key gone")
        assertFailsWith<SecretStorageException> { storage.put("k", byteArrayOf(3)) }
        assertContentEquals(byteArrayOf(1), storage.get("k"))
        // A different key (a Keystore reset) cannot open the old entry.
        assertFailsWith<SecretStorageException> { AndroidSecretStorage(dir, FakeCipher(ByteArray(32) { 7 })).get("k") }
    }

    @Test
    fun oversizedValuesAreRefused() {
        assertFailsWith<IllegalArgumentException> { storage.put("big", ByteArray(AndroidSecretStorage.MAX_VALUE_BYTES + 1)) }
        storage.put("max", ByteArray(AndroidSecretStorage.MAX_VALUE_BYTES))
        assertEquals(AndroidSecretStorage.MAX_VALUE_BYTES, storage.get("max")!!.size)
    }

    @Test
    fun leftoverTemporaryFilesAreRemovedWhenTheStorageOpens() {
        storage.put("k", byteArrayOf(1))
        val stray = File(dir, storage.fileFor("k").name + ".tmp").apply { writeBytes(byteArrayOf(0)) }
        AndroidSecretStorage(dir, FakeCipher())
        assertFalse(stray.exists())
        assertContentEquals(byteArrayOf(1), AndroidSecretStorage(dir, FakeCipher()).get("k"))
    }

    @Test
    fun createsItsDirectory() {
        val nested = File(dir, "a/b/secrets")
        val s = AndroidSecretStorage(nested, FakeCipher())
        s.put("k", byteArrayOf(4))
        assertTrue(nested.isDirectory)
        assertContentEquals(byteArrayOf(4), s.get("k"))
    }

    @Test
    fun concurrentWritersNeverTearAValue() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val values = (0 until 8).map { i -> ByteArray(1024) { i.toByte() } }
        try {
            repeat(8) { i ->
                pool.execute {
                    start.await()
                    repeat(25) { storage.put("shared", values[i]) }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val read = storage.get("shared")!!
        assertTrue(values.any { it.contentEquals(read) })
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun backsTheIdentityAndAdvertisingSecretStores() {
        val crypto = AndroidCryptoProvider(backends = BackendSelection.FALLBACK_ONLY, aesHardware = { true })
        val identity = SoftwareIdentityKeyStore(storage, crypto).loadOrCreate()
        val kAdv = AdvertisingSecretStore(storage, crypto).current()
        val reopened = AndroidSecretStorage(dir, FakeCipher())
        assertContentEquals(identity.publicKey, SoftwareIdentityKeyStore(reopened, crypto).loadOrCreate().publicKey)
        assertEquals(kAdv, AdvertisingSecretStore(reopened, crypto).current())
        // A damaged identity entry is reported, not replaced (F-B1).
        storage.fileFor(SoftwareIdentityKeyStore.STORAGE_NAME).writeBytes(ByteArray(10))
        assertFailsWith<CryptoException> { SoftwareIdentityKeyStore(AndroidSecretStorage(dir, FakeCipher()), crypto).loadOrCreate() }
    }
}
