package com.constrivo.drop.platform.desktop.data

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.data.AeadSecretFieldCipher
import com.constrivo.drop.core.data.DatabaseKeys
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.LocalCalendar
import com.constrivo.drop.core.data.ResumeDataCleaner
import com.constrivo.drop.core.data.SystemZoneCalendar
import com.constrivo.drop.core.data.openJvm
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The desktop database (architecture §12, F‑J2): `core/data` on the SQLite JDBC driver, which this module brings in
 * (`core/data` only has it `compileOnly`, WP6 note). Device secrets (recognition secrets, peers' `k_adv`) are sealed
 * field by field with AES-256-GCM under [DatabaseKeys.fieldKey], whose master key lives in the [SecretStorage] (the
 * OS keychain once WP10b–d wrap it). Whole-file encryption needs a SQLCipher JDBC build and is not part of WP10a.
 */
object DesktopDatabase {
    /**
     * Opens (creating or migrating) the database at [path]. Blocking I/O: call it off the UI thread.
     *
     * @throws com.constrivo.drop.core.data.DatabaseVersionException for a database written by a newer app.
     */
    fun open(
        path: Path,
        secrets: SecretStorage,
        crypto: CryptoProvider = JcaCryptoProvider(),
        clock: WallClock = SystemWallClock,
        calendar: LocalCalendar = SystemZoneCalendar,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DropData = DropData.openJvm(path.toString(), cipherFor(secrets, crypto), dispatcher, crypto, clock, calendar)

    /** An in-memory database with the same field sealing (tests, a session that must not persist anything). */
    fun inMemory(
        secrets: SecretStorage,
        crypto: CryptoProvider = JcaCryptoProvider(),
        clock: WallClock = SystemWallClock,
        calendar: LocalCalendar = SystemZoneCalendar,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DropData = DropData.openJvm(null, cipherFor(secrets, crypto), dispatcher, crypto, clock, calendar)

    private fun cipherFor(
        secrets: SecretStorage,
        crypto: CryptoProvider,
    ): AeadSecretFieldCipher {
        val key = DatabaseKeys(secrets, crypto).fieldKey()
        try {
            return AeadSecretFieldCipher(crypto, key)
        } finally {
            key.fill(0)
        }
    }
}

/**
 * The 24-hour sweep of resume state for transfers that are never resumed (architecture §7.6, §7.7 with S8; the WP4
 * and WP8 carry-forward): `core/data`'s [ResumeDataCleaner] with the platform's [FileStore.deletePartials], run once
 * at start and then every [intervalMillis]. The partial directory holds the resume plan too ([FileResumePlanStore]),
 * so it goes in the same step. Free of desktop APIs, like [DataResumeStore].
 */
class PartialsSweeper(
    data: DropData,
    private val fileStore: FileStore,
    clock: WallClock,
    retentionMillis: Long = ResumeDataCleaner.RETENTION_MILLIS,
    private val intervalMillis: Long = ResumeDataCleaner.DEFAULT_INTERVAL_MILLIS,
    private val onReport: (ResumeDataCleaner.Report) -> Unit = {},
    private val onError: (Exception) -> Unit = {},
) {
    /** The cleaner; also serves "Clear partial files" ([ResumeDataCleaner.clearPartials]). */
    val cleaner: ResumeDataCleaner = data.resumeDataCleaner(clock, retentionMillis) { id -> deletePartials(id) }

    /** Deletes the partial files (and resume plan) of [id]; idempotent. */
    suspend fun deletePartials(id: TransferId) = fileStore.deletePartials(id.toHex())

    /** Runs the sweep in [scope] until the scope is cancelled. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { cleaner.runPeriodically(intervalMillis, onReport, onError) }
}
