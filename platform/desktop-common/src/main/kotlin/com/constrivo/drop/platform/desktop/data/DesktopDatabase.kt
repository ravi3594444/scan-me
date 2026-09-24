package com.constrivo.drop.platform.desktop.data

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.data.AeadSecretFieldCipher
import com.constrivo.drop.core.data.DatabaseKeys
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.LocalCalendar
import com.constrivo.drop.core.data.SystemZoneCalendar
import com.constrivo.drop.core.data.openJvm
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
