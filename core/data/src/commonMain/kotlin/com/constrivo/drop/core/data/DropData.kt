package com.constrivo.drop.core.data

import app.cash.sqldelight.db.SqlDriver
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The data layer (architecture §12): one SQLite database and the repositories over it. Create one per process with
 * [open] and share it; [close] it on shutdown.
 *
 * Threading: every repository function runs on one database context, a view of the dispatcher passed to [open]
 * limited to one task at a time. SQLite takes one writer at a time anyway, so this costs nothing, and it keeps a
 * transaction on the thread that started it for drivers that bind connections to threads. Calls are main-safe.
 * Flows run their queries on the same context and re-query after every commit that touches their tables.
 */
class DropData private constructor(
    internal val driver: SqlDriver,
    internal val database: DropDatabase,
    val devices: DeviceRepository,
    val transfers: TransferRepository,
    val transferFiles: TransferFileRepository,
    val manifests: ChunkManifestRepository,
    val settings: SettingsRepository,
    val stats: StatsRepository,
) : AutoCloseable {
    /**
     * A [ResumeDataCleaner] over this database; [deletePartials] is `FileStore.deletePartials` of the platform. The same
     * function goes to [TransferRepository.delete] and [TransferRepository.clearHistory].
     */
    fun resumeDataCleaner(
        clock: WallClock,
        retentionMillis: Long = ResumeDataCleaner.RETENTION_MILLIS,
        deletePartials: suspend (TransferId) -> Unit,
    ): ResumeDataCleaner = ResumeDataCleaner(transfers, manifests, clock, retentionMillis, deletePartials)

    /** Closes the driver. The repositories must not be used afterwards. */
    override fun close() {
        driver.close()
    }

    companion object {
        /**
         * Opens the database from [driverFactory] (which creates or migrates it, [SqlDriverFactory]) and builds the
         * repositories. Blocking I/O: call it off the main thread.
         *
         * @param dispatcher where database work runs (`Dispatchers.IO` in production).
         * @param crypto derives device ids from identity keys ([DeviceRepository.recordPeer]).
         * @param clock the wall clock for every stored timestamp.
         * @param calendar the user's calendar for Stats weeks ([StatsRepository]).
         * @param secretCipher how device secrets are stored ([SecretFieldCipher]). Secrets stored with
         *   [SecretFieldCipher.PLAINTEXT] are re-sealed with it here, so a later release may switch from PLAINTEXT to an
         *   [AeadSecretFieldCipher]; keep the same non-PLAINTEXT cipher afterwards.
         * @param firstDayOfWeek the first day of a Stats week (the locale's; ISO Monday by default).
         * @throws DatabaseVersionException if the file was written by a newer app version.
         * @throws IllegalStateException if the driver does not enforce foreign keys ([SqlDriverFactory] contract).
         */
        fun open(
            driverFactory: SqlDriverFactory,
            dispatcher: CoroutineDispatcher,
            crypto: CryptoProvider,
            clock: WallClock,
            calendar: LocalCalendar,
            secretCipher: SecretFieldCipher = SecretFieldCipher.PLAINTEXT,
            firstDayOfWeek: Weekday = Weekday.MONDAY,
        ): DropData {
            val driver = driverFactory.open(DropSchema.schema)
            try {
                val version = DropSchema.userVersion(driver)
                if (version != DropSchema.VERSION) {
                    if (version > DropSchema.VERSION) throw DatabaseVersionException(version, DropSchema.VERSION)
                    error("driver factory left the database at version $version, expected ${DropSchema.VERSION}")
                }
                check(DropSchema.foreignKeysEnabled(driver)) { "the SqlDriver must enforce foreign keys (PRAGMA foreign_keys = ON)" }
                val database = DropDatabase(driver)
                val context = dispatcher.limitedParallelism(1)
                val devices = DeviceRepository(database, context, clock, crypto, secretCipher)
                devices.resealPlaintextSecrets()
                return DropData(
                    driver = driver,
                    database = database,
                    devices = devices,
                    transfers = TransferRepository(database, context, clock),
                    transferFiles = TransferFileRepository(database, context),
                    manifests = ChunkManifestRepository(database, context, clock),
                    settings = SettingsRepository(database, context, clock),
                    stats = StatsRepository(database, context, clock, calendar, firstDayOfWeek),
                )
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
        }
    }
}
