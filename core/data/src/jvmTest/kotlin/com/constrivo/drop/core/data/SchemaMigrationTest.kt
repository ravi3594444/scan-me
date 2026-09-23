package com.constrivo.drop.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.data.db.DropDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema versioning (architecture §12): the version is pinned, the created schema matches the checked-in golden of
 * its version, every older golden migrates to exactly the current schema with its data, and newer files are refused.
 *
 * Changing the schema means: add `N.sqm`, bump [DropSchema.VERSION], and add `schema/v<N+1>.sql` (the dump of the
 * new schema, [schemaDump]). Shipped `.sqm` files and goldens never change.
 */
class SchemaMigrationTest {
    private val tempDir: File = Files.createTempDirectory("drop-schema").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private fun golden(version: Long): String? = javaClass.getResource("/schema/v$version.sql")?.readText()

    private fun rawDriver(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite::memory:", Properties().apply { put("foreign_keys", "true") })

    @Test
    fun schemaVersionIsPinnedToTheGeneratedSchema() {
        assertEquals(1L, DropSchema.VERSION)
        assertEquals(DropSchema.VERSION, DropDatabase.Schema.version)
        for (v in 1..DropSchema.VERSION) assertNotNull(golden(v), "missing golden schema/v$v.sql")
        assertEquals(null, golden(DropSchema.VERSION + 1), "a golden exists for an unreleased version; bump DropSchema.VERSION")
    }

    @Test
    fun freshDatabaseMatchesTheGoldenOfItsVersion() {
        val driver = JdbcSqliteDriverFactory.inMemory().open(DropSchema.schema)
        assertEquals(DropSchema.VERSION, DropSchema.userVersion(driver))
        assertEquals(golden(DropSchema.VERSION), driver.schemaDump())
        driver.close()
    }

    @Test
    fun migratingFromEmptyEqualsCreating() {
        val driver = rawDriver()
        DropSchema.schema.migrate(driver, 0, DropSchema.VERSION)
        assertEquals(golden(DropSchema.VERSION), driver.schemaDump())
        driver.close()
    }

    @Test
    fun everyOlderSchemaMigratesToTheCurrentOneKeepingItsData() {
        for (version in 1 until DropSchema.VERSION) {
            val driver = rawDriver()
            schemaStatements(golden(version)!!).forEach { driver.execute(null, it, 0) }
            driver.execute(null, "PRAGMA user_version = $version", 0)
            driver.execute(null, "INSERT INTO settings (key, value) VALUES ('probe', 'kept')", 0)
            DropSchema.createOrMigrate(driver)
            assertEquals(DropSchema.VERSION, DropSchema.userVersion(driver), "v$version")
            assertEquals(golden(DropSchema.VERSION), driver.schemaDump(), "v$version migrated")
            assertEquals(listOf("kept"), driver.column("SELECT value FROM settings WHERE key = 'probe'"))
            driver.close()
        }
    }

    @Test
    fun goldensReplayToTheSameSchema() {
        for (version in 1..DropSchema.VERSION) {
            val driver = rawDriver()
            schemaStatements(golden(version)!!).forEach { driver.execute(null, it, 0) }
            assertEquals(golden(version), driver.schemaDump())
            driver.close()
        }
    }

    @Test
    fun createOrMigrateIsIdempotent() {
        val driver = rawDriver()
        DropSchema.createOrMigrate(driver)
        driver.execute(null, "INSERT INTO settings (key, value) VALUES ('a', 'b')", 0)
        DropSchema.createOrMigrate(driver)
        assertEquals(listOf("b"), driver.column("SELECT value FROM settings"))
        driver.close()
    }

    @Test
    fun newerDatabaseIsRefusedAndLeftUntouched() {
        val path = File(tempDir, "newer.db").path
        val raw = JdbcSqliteDriver("jdbc:sqlite:$path", Properties())
        raw.execute(null, "CREATE TABLE future (x INTEGER)", 0)
        raw.execute(null, "PRAGMA user_version = ${DropSchema.VERSION + 1}", 0)
        raw.close()

        val error = assertFailsWith<DatabaseVersionException> { JdbcSqliteDriverFactory.file(path).open(DropSchema.schema) }
        assertEquals(DropSchema.VERSION + 1, error.found)
        assertEquals(DropSchema.VERSION, error.supported)

        val check = JdbcSqliteDriver("jdbc:sqlite:$path", Properties())
        assertEquals(DropSchema.VERSION + 1, DropSchema.userVersion(check))
        assertEquals(listOf("future"), check.column("SELECT name FROM sqlite_master WHERE type = 'table'"))
        check.close()
    }

    @Test
    fun fileDatabaseKeepsDataAcrossReopens() =
        runTest {
            val path = File(tempDir, "nested/dir/drop.db").path
            val clock = FakeClock()
            val first = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, JcaCryptoProvider(), clock, LocalCalendar.UTC)
            first.settings.set(SettingKeys.NICKNAME, "Asha")
            val id = first.devices.recordPeer(identityKey(1), "Dev", com.constrivo.drop.core.discovery.DevicePlatform.LAPTOP).id
            first.close()

            val second = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, JcaCryptoProvider(), clock, LocalCalendar.UTC)
            assertEquals("Asha", second.settings.get(SettingKeys.NICKNAME))
            assertEquals("Dev", second.devices.find(id)?.nickname)
            assertEquals(listOf("wal"), second.driver.column("PRAGMA journal_mode"))
            second.close()
        }

    @Test
    fun openRefusesADriverWithoutForeignKeys() {
        val factory =
            SqlDriverFactory { schema ->
                JdbcSqliteDriver("jdbc:sqlite::memory:", Properties()).also { DropSchema.createOrMigrate(it, schema) }
            }
        val error =
            assertFailsWith<IllegalStateException> {
                DropData.open(factory, Dispatchers.IO, JcaCryptoProvider(), FakeClock(), LocalCalendar.UTC)
            }
        assertTrue("foreign keys" in error.message.orEmpty())
    }

    @Test
    fun openRefusesAFactoryThatDidNotCreateTheSchema() {
        val factory = SqlDriverFactory { _ -> rawDriver() }
        assertFailsWith<IllegalStateException> {
            DropData.open(factory, Dispatchers.IO, JcaCryptoProvider(), FakeClock(), LocalCalendar.UTC)
        }
    }

    @Test
    fun openReportsANewerDatabaseFromAFactoryThatDoesNotCheck() {
        val factory =
            SqlDriverFactory { _: SqlSchema<QueryResult.Value<Unit>> ->
                rawDriver().also { it.execute(null, "PRAGMA user_version = 7", 0) }
            }
        val error =
            assertFailsWith<DatabaseVersionException> {
                DropData.open(factory, Dispatchers.IO, JcaCryptoProvider(), FakeClock(), LocalCalendar.UTC)
            }
        assertEquals(7, error.found)
    }

    private fun insertDevice(
        id: String,
        key: String?,
        platform: String,
    ): String =
        "INSERT INTO device (id, identity_pk, nickname, platform, first_seen, last_seen) " +
            "VALUES ('$id', ${key?.let { "X'$it'" } ?: "NULL"}, 'n', '$platform', 0, 0)"

    private fun insertTransfer(
        id: String,
        peer: String,
        status: String,
        bytesDone: Long = 0,
    ): String =
        "INSERT INTO transfer (id, peer_device_id, direction, started_at, updated_at, bytes_total, bytes_done, status, file_count) " +
            "VALUES ('$id', '$peer', 'send', 0, 0, 10, $bytesDone, '$status', 1)"

    private fun insertManifest(
        transfer: String,
        key: Int,
        units: Int,
        bitmapHex: String,
        hashBytes: Int,
        partialUnit: Int? = null,
    ): String {
        val hashes = "00".repeat(hashBytes)
        return "INSERT INTO chunk_manifest VALUES ('$transfer', $key, $units, X'$bitmapHex', X'$hashes', $partialUnit, 0, 0)"
    }

    @Test
    fun checkConstraintsRejectRowsTheRepositoriesCouldNotReadBack() {
        val driver = JdbcSqliteDriverFactory.inMemory().open(DropSchema.schema)
        val device = "0".repeat(32)
        val transfer = "7".repeat(32)
        driver.execute(null, insertDevice(device, "11".repeat(32), "phone"), 0)
        driver.execute(null, insertTransfer(transfer, device, "streaming"), 0)
        val refused =
            mapOf(
                "upper-case id" to insertDevice("A".repeat(32), null, "browser"),
                "unknown platform" to insertDevice("1".repeat(32), null, "toaster"),
                "a non-browser without an identity key" to insertDevice("2".repeat(32), null, "phone"),
                "trusted without a recognition secret" to "UPDATE device SET trusted = 1, trusted_at = 0 WHERE id = '$device'",
                "auto-accept on an untrusted device" to "UPDATE device SET auto_accept = 1 WHERE id = '$device'",
                "unknown status" to insertTransfer("3".repeat(32), device, "paused"),
                "finished without a finish time" to insertTransfer("4".repeat(32), device, "done"),
                "more bytes done than total" to insertTransfer("5".repeat(32), device, "offered", bytesDone = 11),
                "unknown peer (foreign key)" to insertTransfer("6".repeat(32), "f".repeat(32), "offered"),
                "bitmap of the wrong length" to insertManifest(transfer, 0, 9, "00", 144),
                "hashes of the wrong length" to insertManifest(transfer, 0, 8, "00", 15),
                "partial unit without bytes" to insertManifest(transfer, 0, 8, "00", 128, partialUnit = 3),
                "tracking key below the bundle key" to insertManifest(transfer, -2, 0, "", 0),
                "manifest of an unknown transfer (foreign key)" to insertManifest("8".repeat(32), 0, 0, "", 0),
            )
        for ((what, sql) in refused) {
            assertFailsWith<Exception>(what) { driver.execute(null, sql, 0) }
        }
        driver.execute(null, insertManifest(transfer, -1, 9, "0000", 144), 0)
        assertEquals(listOf(1L), driver.longs("SELECT count(*) FROM chunk_manifest"))
        driver.close()
    }
}
