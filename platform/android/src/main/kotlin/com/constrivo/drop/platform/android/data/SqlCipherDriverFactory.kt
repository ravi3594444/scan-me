package com.constrivo.drop.platform.android.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.data.AeadSecretFieldCipher
import com.constrivo.drop.core.data.DatabaseKeys
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.LocalCalendar
import com.constrivo.drop.core.data.SchemaMigrationException
import com.constrivo.drop.core.data.SqlDriverFactory
import com.constrivo.drop.core.data.SystemZoneCalendar
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * `core/data`'s [SqlDriverFactory] on SQLCipher (F-J2, architecture §12 "Encryption at rest"): the whole database file
 * is encrypted with the 32-byte key [key] returns (from [DatabaseKeys.sqlCipherKey], whose master key lives in the
 * Keystore-wrapped `AndroidSecretStorage`, spec change N11), passed as SQLCipher's raw key ([SqlCipherKeys]).
 *
 * The factory keeps the [SqlDriverFactory] contract with its own open-helper callback ([DropSchemaCallback]):
 * a new database is created and an older one migrated with foreign-key enforcement off (SQLite ignores the pragma
 * inside the helper's transaction, so it is simply never turned on before `onOpen`), a migration that leaves a dangling
 * reference rolls back with [SchemaMigrationException], a database written by a newer app is refused with
 * `DatabaseVersionException` and never downgraded, and every connection enforces foreign keys once the schema is
 * current (`setForeignKeyConstraintsEnabled` in `onOpen`, which the framework applies to every pooled connection).
 * The file lives in `noBackupFilesDir` with write-ahead logging.
 *
 * The native library loads on the first [open]. Opening does blocking I/O: call it off the main thread.
 */
class SqlCipherDriverFactory(
    context: Context,
    private val name: String = DATABASE_NAME,
    private val key: () -> ByteArray,
) : SqlDriverFactory {
    private val appContext = context.applicationContext

    override fun open(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
        loadNativeLibrary()
        val raw = key()
        val passphrase =
            try {
                SqlCipherKeys.rawKeyPassphrase(raw)
            } finally {
                raw.fill(0)
            }
        // SQLCipher keeps its own copy of the passphrase for the connections it opens later.
        val factory = SupportOpenHelperFactory(passphrase, null, true)
        return AndroidSqliteDriver(
            schema = schema,
            context = appContext,
            // SQLCipher's helper ignores the configuration's no-backup flag, so the path is given in full.
            name = databaseFile(appContext, name).absolutePath,
            factory = factory,
            callback = DropSchemaCallback(schema),
            useNoBackupDirectory = false,
        )
    }

    companion object {
        /** The database file under `noBackupFilesDir` (architecture §12; excluded from every backup, §10.1 note). */
        const val DATABASE_NAME: String = "drop.db"

        /** Where the database named [name] lives: `noBackupFilesDir/<name>` (its `-wal` and `-shm` files beside it). */
        fun databaseFile(
            context: Context,
            name: String = DATABASE_NAME,
        ): File {
            require(name.isNotEmpty() && '/' !in name) { "a database name is a plain file name" }
            return File(context.noBackupFilesDir, name)
        }

        @Volatile
        private var loaded = false

        private fun loadNativeLibrary() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("sqlcipher")
                    loaded = true
                }
            }
        }
    }
}

/**
 * The open-helper callback of [SqlCipherDriverFactory]: `core/data`'s schema rules ([SchemaOpenPolicy]) on the
 * `SupportSQLiteOpenHelper` life cycle. The helper runs [onCreate] and [onUpgrade] inside its own transaction, with
 * foreign keys off, then sets `user_version`; [onOpen] runs afterwards, outside any transaction.
 */
internal class DropSchemaCallback(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
) : SupportSQLiteOpenHelper.Callback(schema.version.toInt()) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        // Not foreign keys: onUpgrade runs in a transaction, where a table rebuild would cascade-delete child rows.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        check(SchemaOpenPolicy.decide(0, schema.version) == SchemaAction.CREATE)
        schema.create(AndroidSqliteDriver(db))
    }

    override fun onUpgrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (SchemaOpenPolicy.decide(oldVersion.toLong(), newVersion.toLong()) != SchemaAction.MIGRATE) return
        schema.migrate(AndroidSqliteDriver(db), oldVersion.toLong(), newVersion.toLong())
        val violations = ArrayList<String>()
        db.query("PRAGMA foreign_key_check").use { cursor ->
            while (violations.size < MAX_REPORTED && cursor.moveToNext()) {
                violations += "${cursor.getString(0)} row ${cursor.getLong(1)} -> ${cursor.getString(2)}"
            }
        }
        // Thrown inside the helper's transaction, so the whole migration rolls back.
        if (violations.isNotEmpty()) throw SchemaMigrationException(oldVersion.toLong(), newVersion.toLong(), violations)
    }

    override fun onDowngrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Throws DatabaseVersionException: a database of a newer app version is refused, never downgraded.
        SchemaOpenPolicy.decide(oldVersion.toLong(), newVersion.toLong())
        error("downgrade from $oldVersion to $newVersion was not refused")
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private companion object {
        const val MAX_REPORTED = 10
    }
}

/**
 * Opens the app's database (architecture §12, F-J2): SQLCipher keyed from [DatabaseKeys] over the Keystore-wrapped
 * [secrets], with device secrets (recognition secrets, peers' `k_adv`) additionally sealed field by field under the
 * separate field key ([AeadSecretFieldCipher]), as on the desktops.
 */
object AndroidDatabase {
    /**
     * Opens (creating or migrating) the database. Blocking I/O: call it off the main thread.
     *
     * @throws com.constrivo.drop.core.data.DatabaseVersionException for a database written by a newer app.
     * @throws com.constrivo.drop.core.data.DataCorruptionException when the stored database key is malformed.
     */
    fun open(
        context: Context,
        secrets: SecretStorage,
        crypto: CryptoProvider,
        clock: WallClock,
        calendar: LocalCalendar = SystemZoneCalendar,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        name: String = SqlCipherDriverFactory.DATABASE_NAME,
    ): DropData {
        val keys = DatabaseKeys(secrets, crypto)
        val fieldKey = keys.fieldKey()
        val cipher =
            try {
                AeadSecretFieldCipher(crypto, fieldKey)
            } finally {
                fieldKey.fill(0)
            }
        return DropData.open(
            driverFactory = SqlCipherDriverFactory(context, name) { keys.sqlCipherKey() },
            dispatcher = dispatcher,
            crypto = crypto,
            clock = clock,
            calendar = calendar,
            secretCipher = cipher,
        )
    }
}
