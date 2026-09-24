package com.constrivo.drop.platform.android.data

import com.constrivo.drop.core.data.DatabaseVersionException

/** What opening the database must do about its schema version (architecture §12, WP6 note "Versions"). */
enum class SchemaAction {
    /** A new database (`user_version` 0): create the current schema. */
    CREATE,

    /** An older schema: run the numbered migrations with foreign-key enforcement off, then check the references. */
    MIGRATE,

    /** Already current. */
    NONE,
}

/**
 * The version rules of `core/data`'s `SqlDriverFactory` contract as a pure function, so they are tested off-device:
 * `0` creates, an older version migrates, the current version opens as it is, and a newer one (written by a later app
 * version) is refused with [DatabaseVersionException]; nothing is ever downgraded.
 */
object SchemaOpenPolicy {
    /**
     * @throws DatabaseVersionException when [found] is newer than [supported].
     * @throws IllegalArgumentException for a negative version (a damaged header).
     */
    fun decide(
        found: Long,
        supported: Long,
    ): SchemaAction {
        require(found >= 0) { "user_version $found is negative" }
        require(supported >= 1) { "the schema version starts at 1" }
        return when {
            found > supported -> throw DatabaseVersionException(found, supported)
            found == supported -> SchemaAction.NONE
            found == 0L -> SchemaAction.CREATE
            else -> SchemaAction.MIGRATE
        }
    }
}

/**
 * The SQLCipher key string for a 32-byte key (F-J2): SQLCipher's raw-key form `x'<64 hex digits>'` passed as the
 * passphrase bytes, so the key is used as it is instead of going through PBKDF2 (256,000 iterations per open in
 * SQLCipher 4, about half a second on a midrange phone, and no strength gained for a random key).
 */
object SqlCipherKeys {
    /** Key size SQLCipher's AES-256 page cipher takes. */
    const val KEY_SIZE: Int = 32

    /**
     * The passphrase bytes of [key]. The caller owns both arrays and should zero them after use.
     *
     * @throws IllegalArgumentException unless [key] is [KEY_SIZE] bytes.
     */
    fun rawKeyPassphrase(key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "a SQLCipher key is $KEY_SIZE bytes, was ${key.size}" }
        val out = ByteArray(3 + KEY_SIZE * 2)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in key.indices) {
            val b = key[i].toInt() and 0xFF
            out[2 + i * 2] = HEX[b ushr 4].code.toByte()
            out[3 + i * 2] = HEX[b and 0x0F].code.toByte()
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    private const val HEX = "0123456789ABCDEF"
}
