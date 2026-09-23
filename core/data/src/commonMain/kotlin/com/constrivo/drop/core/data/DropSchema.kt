package com.constrivo.drop.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.constrivo.drop.core.data.db.DropDatabase

/**
 * Opens the SQLite database `drop` keeps its history, trust and resume state in (architecture §12, F-J2).
 *
 * This is the hook for encryption at rest: Android returns a SQLCipher-backed `AndroidSqliteDriver` keyed with
 * [DatabaseKeys.sqlCipherKey] (WP7 / WP12), desktops a SQLCipher JDBC build keyed from the OS keychain (WP10), and
 * tests and the unencrypted desktop build the JVM `JdbcSqliteDriverFactory`.
 *
 * Contract for implementations:
 * - the returned driver's database is at [SqlSchema.version] of [schema]: created when new, migrated when older
 *   ([DropSchema.createOrMigrate] does both for drivers that do not manage versions themselves), and refused with
 *   [DatabaseVersionException] when newer;
 * - every connection has `PRAGMA foreign_keys = ON` ([DropData.open] checks this);
 * - a file-backed database uses WAL journaling where the platform supports it.
 */
fun interface SqlDriverFactory {
    fun open(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver
}

/**
 * The schema version and the create-or-migrate step (architecture §12).
 *
 * Migrations are `N.sqm` files under `src/commonMain/sqldelight`, where `N.sqm` upgrades version N to N + 1 and
 * `0.sqm` creates version 1 in an empty database (whose `user_version` is 0). The schema is derived from them.
 */
object DropSchema {
    /** The schema version this build reads and writes; `SchemaMigrationTest` pins it to the generated schema. */
    const val VERSION: Long = 1

    /** The generated schema: `create` builds [VERSION] from nothing, `migrate` replays the `.sqm` files. */
    val schema: SqlSchema<QueryResult.Value<Unit>> get() = DropDatabase.Schema

    /** `PRAGMA user_version` of the database behind [driver]; 0 for a new database. */
    fun userVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
                parameters = 0,
            ).value

    /**
     * Brings the database behind [driver] to [schema]'s version in one transaction: creates it when
     * `user_version` is 0, runs the migrations when it is older, does nothing when it is current.
     *
     * @throws DatabaseVersionException if the database is newer than [schema]; nothing is changed.
     */
    fun createOrMigrate(
        driver: SqlDriver,
        schema: SqlSchema<QueryResult.Value<Unit>> = this.schema,
    ) {
        val target = schema.version
        val found = userVersion(driver)
        if (found > target) throw DatabaseVersionException(found, target)
        if (found == target) return
        DropDatabase(driver).transaction {
            // Re-read inside the transaction: another process may have created the file in between.
            val current = userVersion(driver)
            if (current > target) throw DatabaseVersionException(current, target)
            if (current < target) {
                if (current == 0L) schema.create(driver) else schema.migrate(driver, current, target)
                driver.execute(null, "PRAGMA user_version = $target", 0)
            }
        }
    }

    /** Whether foreign-key enforcement is on for the connection [driver] uses on this thread. */
    internal fun foreignKeysEnabled(driver: SqlDriver): Boolean =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA foreign_keys",
                mapper = { cursor -> QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L) },
                parameters = 0,
            ).value
}
