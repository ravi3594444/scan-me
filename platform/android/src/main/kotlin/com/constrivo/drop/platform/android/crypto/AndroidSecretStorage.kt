package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.SecretStorage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** A value sealed by a [SecretCipher]: the IV the cipher chose and ciphertext ‖ tag. */
class SealedSecret(
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivBytes = iv.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    val iv: ByteArray get() = ivBytes.copyOf()
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()
}

/**
 * Encrypts secrets at rest for [AndroidSecretStorage]. On a device this is [KeystoreSecretCipher], a non-exportable
 * AES-256-GCM key in the Android Keystore; tests use a software cipher.
 *
 * Implementations authenticate [aad] and choose a fresh IV per [seal]. They may throw [GeneralSecurityException] or
 * [CryptoException] for any failure; [AndroidSecretStorage] reports both as [SecretStorageException].
 */
interface SecretCipher {
    fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): SealedSecret

    fun open(
        sealed: SealedSecret,
        aad: ByteArray,
    ): ByteArray
}

/**
 * A secret could not be read or written: the file is corrupted or truncated, it does not authenticate (tampered, moved
 * under another name, or the Keystore key is gone after a device reset), or the file system failed. A
 * [CryptoException], so `SoftwareIdentityKeyStore` and `AdvertisingSecretStore` report it as a corrupted entry and the
 * app can offer "Reset identity" (F-B1).
 */
class SecretStorageException(
    message: String,
    cause: Throwable? = null,
) : CryptoException(message, cause)

/**
 * [SecretStorage] for Android (spec change N11, architecture §13): each value sealed with [cipher] (a non-exportable
 * Keystore AES-256-GCM key on a device) and kept in its own file under [directory], which is app-private and excluded
 * from backups (`noBackupFilesDir/secrets`, see [AndroidSecrets.storage]).
 *
 * File format, version 1 (all fields in order):
 *
 * | Bytes | Field |
 * | --- | --- |
 * | 4 | magic `"DRSS"` |
 * | 1 | format version `0x01` |
 * | 1 | IV length *n* (12–32) |
 * | *n* | IV |
 * | ≥ 16 | ciphertext ‖ GCM tag |
 *
 * The associated data is `"drop-secret-v1" ‖ 0x00 ‖ UTF-8(name)`, so a file copied or renamed to another entry does not
 * open. The file name is `SHA-256(UTF-8(name))` in hex plus `.secret`, which keeps any name a valid, fixed-length file
 * name without path traversal.
 *
 * [put] is atomic and durable: the sealed value goes to a temporary file in the same directory, is `fsync`ed, and
 * replaces the entry with an atomic rename, after which the directory is synced where the platform allows it. A crash
 * leaves the old value or the new one, never a torn file; leftover temporary files are removed when the storage opens.
 * Calls are serialised within the process; values are copied in and out.
 *
 * @throws SecretStorageException from [get], [put] and [delete] as documented on the class.
 */
class AndroidSecretStorage(
    private val directory: File,
    private val cipher: SecretCipher,
) : SecretStorage {
    private val lock = Any()

    init {
        synchronized(lock) {
            ensureDirectory()
            directory.listFiles()?.filter { it.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        }
    }

    override fun get(name: String): ByteArray? =
        synchronized(lock) {
            val file = fileFor(name)
            if (!file.exists()) return@synchronized null
            val bytes =
                try {
                    val length = file.length()
                    if (length > MAX_FILE_BYTES) throw SecretStorageException("secret '$name' is $length bytes, over $MAX_FILE_BYTES")
                    file.readBytes()
                } catch (e: IOException) {
                    throw SecretStorageException("cannot read secret '$name'", e)
                }
            val sealed = decode(name, bytes)
            try {
                cipher.open(sealed, aad(name))
            } catch (e: GeneralSecurityException) {
                throw SecretStorageException("secret '$name' does not authenticate", e)
            } catch (e: CryptoException) {
                throw SecretStorageException("secret '$name' does not authenticate", e)
            }
        }

    override fun put(
        name: String,
        value: ByteArray,
    ) {
        require(value.size <= MAX_VALUE_BYTES) { "a secret is at most $MAX_VALUE_BYTES bytes, was ${value.size}" }
        val plaintext = value.copyOf()
        try {
            synchronized(lock) {
                val sealed =
                    try {
                        cipher.seal(plaintext, aad(name))
                    } catch (e: GeneralSecurityException) {
                        throw SecretStorageException("cannot seal secret '$name'", e)
                    } catch (e: CryptoException) {
                        throw SecretStorageException("cannot seal secret '$name'", e)
                    }
                writeAtomically(fileFor(name), encode(sealed))
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override fun delete(name: String) {
        synchronized(lock) {
            val file = fileFor(name)
            if (!file.exists()) return
            if (!file.delete() && file.exists()) throw SecretStorageException("cannot delete secret '$name'")
            syncDirectory()
        }
    }

    /** The file that holds [name] (exposed for tests of the format). */
    internal fun fileFor(name: String): File = File(directory, fileName(name))

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw SecretStorageException("cannot create $directory")
        }
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        ensureDirectory()
        val temp = File(directory, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // Same directory, so this only happens on exotic file systems; rename(2) is still atomic there.
                if (!temp.renameTo(target)) throw IOException("rename to $target failed", e)
            }
            syncDirectory()
        } catch (e: IOException) {
            temp.delete()
            throw SecretStorageException("cannot write ${target.name}", e)
        }
    }

    /** Makes the rename durable. Not every platform can open a directory as a channel; the rename is atomic anyway. */
    private fun syncDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Directory sync is best effort.
        } catch (e: UnsupportedOperationException) {
            // Directory sync is best effort.
        } catch (e: SecurityException) {
            // Directory sync is best effort.
        }
    }

    companion object {
        /** Largest value accepted by [put]; identity seeds, `k_adv` and database keys are well below it. */
        const val MAX_VALUE_BYTES: Int = 16 * 1024

        private const val MAX_FILE_BYTES: Long = MAX_VALUE_BYTES + 1024L
        private val MAGIC = byteArrayOf(0x44, 0x52, 0x53, 0x53) // "DRSS"
        private const val FORMAT_VERSION: Byte = 1
        private const val MIN_IV = 12
        private const val MAX_IV = 32
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 6
        private const val TEMP_SUFFIX = ".tmp"
        private const val FILE_SUFFIX = ".secret"
        private val AAD_PREFIX = "drop-secret-v1".encodeToByteArray() + byteArrayOf(0)

        internal fun aad(name: String): ByteArray = AAD_PREFIX + name.encodeToByteArray()

        internal fun fileName(name: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(name.encodeToByteArray())
            return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + FILE_SUFFIX
        }

        internal fun encode(sealed: SealedSecret): ByteArray {
            val iv = sealed.iv
            val ciphertext = sealed.ciphertext
            require(iv.size in MIN_IV..MAX_IV) { "IV must be $MIN_IV–$MAX_IV bytes, was ${iv.size}" }
            require(ciphertext.size >= TAG_BYTES) { "ciphertext must include a $TAG_BYTES-byte tag" }
            return MAGIC + byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
        }

        /** @throws SecretStorageException for anything but a well-formed version 1 file. */
        internal fun decode(
            name: String,
            bytes: ByteArray,
        ): SealedSecret {
            if (bytes.size < HEADER_BYTES) throw SecretStorageException("secret '$name' is truncated (${bytes.size} bytes)")
            if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw SecretStorageException("secret '$name' has no DRSS header")
            if (bytes[4] != FORMAT_VERSION) throw SecretStorageException("secret '$name' has unknown format version ${bytes[4]}")
            val ivLength = bytes[5].toInt() and 0xFF
            if (ivLength !in MIN_IV..MAX_IV) throw SecretStorageException("secret '$name' has an IV of $ivLength bytes")
            if (bytes.size < HEADER_BYTES + ivLength + TAG_BYTES) throw SecretStorageException("secret '$name' is truncated")
            return SealedSecret(
                bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength),
                bytes.copyOfRange(HEADER_BYTES + ivLength, bytes.size),
            )
        }
    }
}
