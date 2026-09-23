package com.constrivo.drop.core.data

/**
 * Base class of the exceptions `core/data` throws for problems with stored data (architecture §12).
 *
 * Programming errors, such as a malformed device id or a negative byte count passed to a repository, are
 * `IllegalArgumentException`s instead and are never caught here.
 */
open class DataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A stored value cannot be decoded: text that no column codec writes, a blob of the wrong size, a malformed id, or a
 * sealed secret that fails to open. The database was changed outside this module, or is damaged. Reading the same
 * row again raises the same exception; nothing is repaired silently.
 */
class DataCorruptionException(
    message: String,
    cause: Throwable? = null,
) : DataException(message, cause)

/**
 * The database file was written by a newer app version: its `PRAGMA user_version` [found] is above the [supported]
 * schema version ([DropSchema.VERSION]). Downgrades are not supported; the file is left untouched.
 */
class DatabaseVersionException(
    val found: Long,
    val supported: Long,
) : DataException("database schema version $found is newer than the supported version $supported")

/**
 * A device announced an identity key other than the one stored under its device id. Device ids are derived from the
 * key (`SHA-256(identity_pk)[0..16]`), so this means a 128-bit collision or a caller that computed the id wrongly;
 * the stored row is left untouched.
 */
class IdentityConflictException(
    val deviceId: String,
) : DataException("device $deviceId is stored with a different identity key")

/** A write referenced a row that does not exist, such as a transfer with an unknown peer or a manifest of an unknown transfer. */
class NoSuchRecordException(
    message: String,
) : DataException(message)

/**
 * A create named a key that is already stored: a transfer id, or a file index of a transfer. Transfer ids come from
 * peers, so the engine treats this as a protocol error (decline the offer), not as a crash.
 */
class DuplicateRecordException(
    message: String,
) : DataException(message)
