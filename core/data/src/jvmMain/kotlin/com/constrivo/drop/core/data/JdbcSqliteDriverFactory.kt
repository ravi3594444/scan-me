package com.constrivo.drop.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * [SqlDriverFactory] on the SQLite JDBC driver (`org.xerial:sqlite-jdbc`), for the desktop apps and the tests.
 *
 * Every connection gets `foreign_keys = ON` and a 5 s busy timeout; a file database also gets WAL journaling with
 * `synchronous = NORMAL` (durable against app crashes, may lose the last commits on power loss, which the
 * manifest durability rule tolerates, [ChunkManifestRepository]). [open] creates or migrates the schema
 * ([DropSchema.createOrMigrate]) and refuses a newer database with [DatabaseVersionException].
 *
 * Migrations run without foreign-key enforcement ([SqlDriverFactory] contract). The in-memory driver keeps its one
 * connection, so `createOrMigrate` switches enforcement off around the migration. The file driver opens a connection
 * per statement outside transactions, where a pragma set before `BEGIN` would never reach the migrating connection,
 * so a file is migrated through a separate driver whose connections do not enforce foreign keys, and only then is
 * the enforcing driver opened.
 *
 * Encryption (F-J2) plugs in through [extraProperties]: with a SQLCipher-capable JDBC build on the classpath, pass
 * its cipher and key properties (keyed from [DatabaseKeys.sqlCipherKey]).
 *
 * `core/data` depends on the driver `compileOnly`, so its native libraries stay out of the Android APK: a desktop
 * module that uses this factory adds `libs.sqldelight.sqlite.driver` (`app.cash.sqldelight:sqlite-driver`) to its
 * own dependencies.
 */
class JdbcSqliteDriverFactory private constructor(
    private val url: String,
    private val properties: Map<String, String>,
    private val file: File?,
) : SqlDriverFactory {
    override fun open(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
        if (file == null) {
            val driver = JdbcSqliteDriver(url, properties.toProperties())
            try {
                DropSchema.createOrMigrate(driver, schema)
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
            return driver
        }
        file.absoluteFile.parentFile?.mkdirs()
        val migrating = JdbcSqliteDriver(url, (properties + (FOREIGN_KEYS to "false")).toProperties())
        try {
            DropSchema.createOrMigrate(migrating, schema)
        } finally {
            migrating.close()
        }
        return JdbcSqliteDriver(url, properties.toProperties())
    }

    override fun toString(): String = "JdbcSqliteDriverFactory(${file?.path ?: "in-memory"})"

    companion object {
        private const val FOREIGN_KEYS = "foreign_keys"
        private val COMMON = mapOf(FOREIGN_KEYS to "true", "busy_timeout" to "5000")
        private val FILE = COMMON + mapOf("journal_mode" to "WAL", "synchronous" to "NORMAL")

        /**
         * A private in-memory database, gone when the driver closes (tests). It lives on a single connection, which
         * [DropData]'s one-task-at-a-time database context never uses concurrently.
         */
        fun inMemory(): JdbcSqliteDriverFactory = JdbcSqliteDriverFactory(IN_MEMORY_URL, COMMON, null)

        /**
         * A true in-memory database. (`JdbcSqliteDriver.IN_MEMORY`, an empty file name, is a private temporary file
         * in SQLite.) The driver keeps it on one static connection.
         */
        private const val IN_MEMORY_URL = "jdbc:sqlite::memory:"

        /**
         * A database file at [path], created with its parent folders when missing.
         *
         * @param extraProperties more sqlite-jdbc connection properties (a SQLCipher build's `cipher` / `key`); they
         *   override the defaults above.
         */
        fun file(
            path: String,
            extraProperties: Map<String, String> = emptyMap(),
        ): JdbcSqliteDriverFactory {
            require(path.isNotBlank() && path != ":memory:") { "use inMemory() for an in-memory database" }
            val file = File(path)
            return JdbcSqliteDriverFactory("jdbc:sqlite:${file.path}", FILE + extraProperties, file)
        }
    }
}
