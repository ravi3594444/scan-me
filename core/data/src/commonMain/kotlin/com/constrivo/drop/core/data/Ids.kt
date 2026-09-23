package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.protocol.TransferId

/**
 * The text form of `device.id` (architecture §12, §5.3): `hex(SHA-256(identity_pk)[0..16])`, 32 lower-case hex
 * digits. The same string is `TrustedPeer.deviceId` in `core/discovery`.
 */
object DeviceIds {
    const val LENGTH: Int = 32

    /** Size of an Ed25519 identity public key. */
    const val IDENTITY_KEY_SIZE: Int = 32

    /** The device id of [identityKey]. */
    fun of(
        crypto: CryptoProvider,
        identityKey: ByteArray,
    ): String {
        require(identityKey.size == IDENTITY_KEY_SIZE) { "an identity key is $IDENTITY_KEY_SIZE bytes, got ${identityKey.size}" }
        return crypto.deviceId(identityKey).toHex()
    }

    fun isValid(id: String): Boolean = id.length == LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    /** Returns [id] if it is well formed; otherwise throws `IllegalArgumentException`. */
    fun requireValid(id: String): String {
        require(isValid(id)) { "a device id is $LENGTH lower-case hex digits" }
        return id
    }
}

/** `transfer.id` as stored: the lower-case hex of the 16 `transfer_id` bytes. */
internal fun TransferId.toDb(): String = toHex()

/** Reads a stored `transfer.id`. */
internal fun transferIdFromDb(text: String): TransferId {
    if (text.length != TransferId.SIZE * 2 || !text.all { it in '0'..'9' || it in 'a'..'f' }) {
        throw DataCorruptionException("malformed transfer id '$text'")
    }
    return TransferId.fromHex(text)
}

/** Reads a stored `device.id`. */
internal fun deviceIdFromDb(text: String): String {
    if (!DeviceIds.isValid(text)) throw DataCorruptionException("malformed device id '$text'")
    return text
}

/** Throws [DataCorruptionException] with [message] unless a stored value satisfies [condition]. */
internal inline fun requireStored(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw DataCorruptionException(message())
}

internal fun Boolean.toDb(): Long = if (this) 1L else 0L

internal fun booleanFromDb(
    value: Long,
    column: String,
): Boolean =
    when (value) {
        0L -> false
        1L -> true
        else -> throw DataCorruptionException("$column holds $value, not 0 or 1")
    }

/** Narrows a stored INTEGER that the schema keeps within Int range. */
internal fun intFromDb(
    value: Long,
    column: String,
): Int {
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw DataCorruptionException("$column holds $value, out of range")
    return value.toInt()
}

/** Decodes [text] with [codec], naming the row in the exception. */
internal fun <T> ColumnCodec<T>.decodeColumn(
    text: String,
    where: String,
): T =
    try {
        decode(text)
    } catch (e: DataCorruptionException) {
        throw DataCorruptionException("$where: ${e.message}", e)
    }
