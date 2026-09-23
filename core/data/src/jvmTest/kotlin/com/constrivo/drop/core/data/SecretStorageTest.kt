package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.InMemorySecretStorage
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Secrets at rest (architecture §13 with N11, F-J2): the field cipher, the database key, and the zone calendar. */
class SecretStorageTest {
    private val context = "device:${"a".repeat(32)}:recognition_secret"

    @Test
    fun plaintextCipherFramesTheValue() {
        val cipher = SecretFieldCipher.PLAINTEXT
        val sealed = cipher.seal(secret(1), context)
        assertEquals(33, sealed.size)
        assertEquals(0, sealed[0].toInt())
        assertContentEquals(secret(1), cipher.open(sealed, context))
        assertFailsWith<DataCorruptionException> { cipher.open(ByteArray(0), context) }
        assertFailsWith<DataCorruptionException> { cipher.open(byteArrayOf(1) + secret(1), context) }
    }

    @Test
    fun aeadCipherRoundTripsAndBindsTheContext() {
        val crypto = SeededCrypto(3)
        val cipher = AeadSecretFieldCipher(crypto, ByteArray(32) { 9 })
        val sealed = cipher.seal(secret(1), context)
        assertEquals(1 + 12 + 32 + 16, sealed.size)
        assertEquals(1, sealed[0].toInt())
        assertContentEquals(secret(1), cipher.open(sealed, context))
        assertNotEquals(sealed.toList(), cipher.seal(secret(1), context).toList(), "a fresh nonce per seal")

        assertFailsWith<DataCorruptionException>("another row") { cipher.open(sealed, "device:${"b".repeat(32)}:recognition_secret") }
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<DataCorruptionException>("byte $i") { cipher.open(tampered, context) }
        }
        assertFailsWith<DataCorruptionException> { cipher.open(sealed.copyOf(20), context) }
        assertFailsWith<DataCorruptionException>("another key") { AeadSecretFieldCipher(crypto, ByteArray(32) { 8 }).open(sealed, context) }
        assertFailsWith<DataCorruptionException>("plaintext values are not accepted") {
            cipher.open(SecretFieldCipher.PLAINTEXT.seal(secret(1), context), context)
        }
        assertFailsWith<DataCorruptionException> { SecretFieldCipher.PLAINTEXT.open(sealed, context) }
        assertFailsWith<IllegalArgumentException> { AeadSecretFieldCipher(crypto, ByteArray(16)) }
        assertFalse("09" in cipher.toString())
    }

    @Test
    fun databaseKeyIsCreatedOnceAndKeptInSecretStorage() {
        val storage = InMemorySecretStorage()
        val keys = DatabaseKeys(storage, SeededCrypto(4))
        val master = keys.masterKey()
        assertEquals(32, master.size)
        assertEquals(setOf(DatabaseKeys.STORAGE_NAME), storage.names())
        assertContentEquals(byteArrayOf(1) + master, storage.get(DatabaseKeys.STORAGE_NAME))
        assertContentEquals(master, DatabaseKeys(storage, SeededCrypto(99)).masterKey(), "a second instance reads the stored key")

        val sqlCipher = keys.sqlCipherKey()
        val field = keys.fieldKey()
        assertEquals(32, sqlCipher.size)
        assertEquals(32, field.size)
        assertFalse(sqlCipher.contentEquals(field), "one key per purpose")
        assertFalse(sqlCipher.contentEquals(master))
        assertContentEquals(field, keys.fieldKey(), "derivation is deterministic")
    }

    @Test
    fun aMalformedStoredKeyIsReportedNotReplaced() {
        val storage = InMemorySecretStorage()
        storage.put(DatabaseKeys.STORAGE_NAME, byteArrayOf(2) + ByteArray(32))
        assertFailsWith<DataCorruptionException> { DatabaseKeys(storage, SeededCrypto(4)).masterKey() }
        assertContentEquals(byteArrayOf(2) + ByteArray(32), storage.get(DatabaseKeys.STORAGE_NAME))
        storage.put(DatabaseKeys.STORAGE_NAME, byteArrayOf(1) + ByteArray(31))
        assertFailsWith<DataCorruptionException> { DatabaseKeys(storage, SeededCrypto(4)).masterKey() }
    }

    @Test
    fun concurrentFirstUseCreatesOneKey() {
        val storage = InMemorySecretStorage()
        val keys = DatabaseKeys(storage, JcaCryptoProvider())
        val results = ConcurrentLinkedQueue<List<Byte>>()
        val threads = (0 until 8).map { Thread { results += keys.masterKey().toList() } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(1, results.toSet().size)
    }

    @Test
    fun concurrentFirstUseThroughSeparateInstancesCreatesOneKey() {
        // What `DatabaseKeys(secretStorage, crypto).sqlCipherKey()` inline at two call sites does.
        repeat(20) {
            val storage = SlowSecretStorage(InMemorySecretStorage())
            val start = CountDownLatch(1)
            val results = ConcurrentLinkedQueue<List<Byte>>()
            val threads =
                (0 until 8).map {
                    Thread {
                        start.await()
                        results += DatabaseKeys(storage, JcaCryptoProvider()).masterKey().toList()
                    }
                }
            threads.forEach(Thread::start)
            start.countDown()
            threads.forEach(Thread::join)
            assertEquals(1, results.toSet().size, "every instance got the same key")
            assertEquals(results.first(), storage.get(DatabaseKeys.STORAGE_NAME)!!.drop(1), "and it is the stored one")
        }
    }

    /** Widens the get-then-put window of first use, so an unguarded race would show. */
    private class SlowSecretStorage(
        private val delegate: SecretStorage,
    ) : SecretStorage by delegate {
        override fun get(name: String): ByteArray? = delegate.get(name).also { Thread.sleep(1) }
    }

    @Test
    fun zoneCalendarMatchesJavaTime() {
        val random = Random(5)
        // Lord Howe shifts by 30 minutes; Santiago moves its clocks at midnight, so some days start at 01:00.
        for (zoneName in listOf("UTC", "Asia/Kolkata", "America/New_York", "Europe/Berlin", "Australia/Lord_Howe", "America/Santiago")) {
            val zone = ZoneId.of(zoneName)
            val calendar = ZoneCalendar(zone)
            repeat(2_000) {
                val millis = random.nextLong(-2_000_000_000_000L, 4_000_000_000_000L)
                val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
                assertEquals(day, calendar.epochDayOf(millis), "$zoneName $millis")
                val start = calendar.startOfDayMillis(day)
                assertTrue(start <= millis && calendar.epochDayOf(start) == day, "$zoneName $millis")
                assertEquals(LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli(), start)
            }
        }
        assertEquals(ZoneCalendar(ZoneId.systemDefault()).epochDayOf(T0), SystemZoneCalendar.epochDayOf(T0))
        assertEquals(ZoneCalendar(ZoneId.systemDefault()).startOfDayMillis(20_000), SystemZoneCalendar.startOfDayMillis(20_000))
    }
}
