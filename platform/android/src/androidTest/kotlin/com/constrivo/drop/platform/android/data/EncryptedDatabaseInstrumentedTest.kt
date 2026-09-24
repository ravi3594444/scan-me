package com.constrivo.drop.platform.android.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.data.DatabaseKeys
import com.constrivo.drop.core.data.DatabaseVersionException
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.platform.android.crypto.AndroidCryptoProvider
import com.constrivo.drop.platform.android.crypto.AndroidSecretStorage
import com.constrivo.drop.platform.android.crypto.KeystoreSecretCipher
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a device (lab, WP7e; F-J2, architecture §12): the database is a SQLCipher file in `noBackupFilesDir`, keyed from
 * the Keystore-wrapped secrets, that keeps its data across opens, and a database written by a newer app is refused.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "drop-test-${System.nanoTime()}.db"
    private val secretsDir = File(context.noBackupFilesDir, "secrets-db-test-${System.nanoTime()}")
    private val crypto = AndroidCryptoProvider()
    private val secrets = AndroidSecretStorage(secretsDir, KeystoreSecretCipher(alias = "drop.test.database"))

    @After
    fun cleanUp() {
        val file = SqlCipherDriverFactory.databaseFile(context, name)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        secretsDir.deleteRecursively()
    }

    @Test
    fun theDatabaseIsEncryptedAndKeepsItsDataAcrossOpens() {
        val data = AndroidDatabase.open(context, secrets, crypto, AndroidClocks.wall, name = name)
        runBlocking { data.settings.set(SettingKeys.NICKNAME, "Lab phone") }
        data.close()

        val file = SqlCipherDriverFactory.databaseFile(context, name)
        assertTrue(file.exists(), "the database lives in noBackupFilesDir")
        val header = file.readBytes().copyOf(PLAIN_HEADER.length).decodeToString()
        assertFalse(header == PLAIN_HEADER, "SQLCipher encrypts the file header too")

        val again = AndroidDatabase.open(context, secrets, crypto, AndroidClocks.wall, name = name)
        try {
            assertEquals("Lab phone", runBlocking { again.settings.snapshot().nickname })
        } finally {
            again.close()
        }
    }

    @Test
    fun aDatabaseOfANewerAppIsRefusedNotDowngraded() {
        // A database at a schema version from the future, written with the same key.
        System.loadLibrary("sqlcipher")
        val key = DatabaseKeys(secrets, crypto).sqlCipherKey()
        val passphrase = SqlCipherKeys.rawKeyPassphrase(key)
        val helper =
            SupportOpenHelperFactory(passphrase, null, true).create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(SqlCipherDriverFactory.databaseFile(context, name).absolutePath)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(FUTURE_VERSION) {
                            override fun onCreate(db: SupportSQLiteDatabase) = Unit

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        helper.writableDatabase.version
        helper.close()

        val failure =
            assertFailsWith<Exception> {
                AndroidDatabase.open(context, secrets, crypto, AndroidClocks.wall, name = name).use { it.settings }
            }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is DatabaseVersionException }, "$failure")
    }

    private companion object {
        const val PLAIN_HEADER = "SQLite format 3"
        const val FUTURE_VERSION = 999
    }
}
