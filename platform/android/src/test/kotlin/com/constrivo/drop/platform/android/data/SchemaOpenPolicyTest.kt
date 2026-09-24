package com.constrivo.drop.platform.android.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.constrivo.drop.core.data.DatabaseVersionException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The encrypted database's open rules (F-J2, architecture §12): the version decisions, the SQLCipher raw key, and the
 * open-helper callback's foreign keys (on in `onOpen`, never in `onConfigure`) and refused downgrade.
 */
class SchemaOpenPolicyTest {
    @Test
    fun `a new database is created, an older one migrated, a newer one refused`() {
        assertEquals(SchemaAction.CREATE, SchemaOpenPolicy.decide(0, 3))
        assertEquals(SchemaAction.MIGRATE, SchemaOpenPolicy.decide(1, 3))
        assertEquals(SchemaAction.NONE, SchemaOpenPolicy.decide(3, 3))
        val newer = assertFailsWith<DatabaseVersionException> { SchemaOpenPolicy.decide(4, 3) }
        assertTrue("4" in newer.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { SchemaOpenPolicy.decide(-1, 3) }
        assertFailsWith<IllegalArgumentException> { SchemaOpenPolicy.decide(0, 0) }
    }

    @Test
    fun `the key is passed in SQLCipher's raw form, so no PBKDF2 runs`() {
        val key = ByteArray(SqlCipherKeys.KEY_SIZE) { it.toByte() }
        val passphrase = SqlCipherKeys.rawKeyPassphrase(key)
        assertEquals("x'000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F'", passphrase.decodeToString())
        assertEquals(67, passphrase.size)
        assertContentEquals("x'FF".encodeToByteArray(), SqlCipherKeys.rawKeyPassphrase(ByteArray(32) { -1 }).copyOf(4))
        assertFailsWith<IllegalArgumentException> { SqlCipherKeys.rawKeyPassphrase(ByteArray(16)) }
    }

    private object Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 3

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    /** A database that records the calls it gets and answers nothing else. */
    private fun recordingDatabase(calls: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase

    @Test
    fun `foreign keys are enforced once the database is open, not while it is configured`() {
        val callback = DropSchemaCallback(Schema)
        assertEquals(3, callback.version)
        val calls = ArrayList<String>()
        val db = recordingDatabase(calls)
        callback.onConfigure(db)
        assertEquals(listOf("enableWriteAheadLogging"), calls)
        calls.clear()
        callback.onOpen(db)
        assertEquals(listOf("setForeignKeyConstraintsEnabled(true)"), calls)
    }

    @Test
    fun `a database written by a newer app is refused, never downgraded`() {
        val calls = ArrayList<String>()
        assertFailsWith<DatabaseVersionException> { DropSchemaCallback(Schema).onDowngrade(recordingDatabase(calls), 4, 3) }
        assertEquals(emptyList(), calls, "nothing touched the database")
    }
}
