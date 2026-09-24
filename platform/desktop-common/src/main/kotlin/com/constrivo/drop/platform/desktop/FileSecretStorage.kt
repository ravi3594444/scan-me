package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.crypto.SecretStorage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * A secret at rest could not be read or written: an I/O failure, a file that is not in [FileSecretStorage]'s format
 * (truncated, another magic or version, a length that disagrees), or a value sealed by a different [SecretWrap] than
 * the configured one. The stored file is left as it was, so the app can report it ("Reset identity") rather than
 * silently replace an identity.
 */
class SecretStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The OS keychain seam (spec change N11, architecture §13): seals each secret with a non-exportable key the OS holds
 * (Windows DPAPI / CNG, the macOS Keychain, libsecret on Linux), implemented per OS in WP10b–d. [id] is stored with
 * each value so a store opened with another wrap refuses it instead of misreading it.
 *
 * Implementations must be thread-safe and must throw [SecretStorageException] from [open] for a value they cannot
 * open (tampered, sealed under a key that is gone).
 */
interface SecretWrap {
    /** 1–255 for a real wrap; 0 is reserved for [NONE]. */
    val id: Int

    fun seal(
        name: String,
        plaintext: ByteArray,
    ): ByteArray

    fun open(
        name: String,
        sealed: ByteArray,
    ): ByteArray

    companion object {
        /**
         * No keychain: values are stored as they are, protected only by the owner-only file permissions of
         * [FileSecretStorage]. The WP10a default until each OS brings its keychain.
         */
        val NONE: SecretWrap =
            object : SecretWrap {
                override val id: Int = 0

                override fun seal(
                    name: String,
                    plaintext: ByteArray,
                ): ByteArray = plaintext.copyOf()

                override fun open(
                    name: String,
                    sealed: ByteArray,
                ): ByteArray = sealed.copyOf()

                override fun toString(): String = "SecretWrap.NONE"
            }
    }
}

/**
 * [SecretStorage] as one owner-only file per name in [directory] (spec change N11; architecture §13): the identity
 * seed, the advertising secret `k_adv` and the database key. Each value passes [wrap] (the OS keychain, WP10b–d)
 * before it reaches the disk.
 *
 * File format, `<encoded name>.secret`: `"DSEC"` ‖ version `0x01` ‖ wrap id (1 byte) ‖ u32 big-endian payload length ‖
 * payload (the sealed value). Names are stored readably: `[a-z0-9._-]` as they are, every other UTF-8 byte as `%XX`.
 *
 * - [put] is atomic: the value goes to a temporary owner-only file in the same directory, is `fsync`ed, then moved
 *   over the old file (an atomic rename where the file system has one), so a reader sees the old value or the new one.
 * - A value stored with [SecretWrap.NONE] is sealed again with a real [wrap] the next time it is read, so a release
 *   that adds the keychain upgrades existing installs; a value sealed by another real wrap is refused.
 * - Every array is copied on the way in and out. Thread-safe within one process; the app's single-instance guard keeps
 *   a second process away.
 *
 * @throws SecretStorageException from [get], [put] and [delete] on I/O errors and malformed files.
 */
class FileSecretStorage(
    val directory: Path,
    private val wrap: SecretWrap = SecretWrap.NONE,
) : SecretStorage {
    private val lock = Any()
    private val random = SecureRandom()

    init {
        require(wrap.id in 0..MAX_WRAP_ID) { "wrap id ${wrap.id} out of range" }
        try {
            OwnerOnlyFiles.createDirectories(directory)
        } catch (e: IOException) {
            throw SecretStorageException("cannot create the secret directory $directory", e)
        }
    }

    override fun get(name: String): ByteArray? =
        synchronized(lock) {
            val path = pathOf(name)
            val bytes =
                try {
                    Files.readAllBytes(path)
                } catch (_: NoSuchFileException) {
                    return null
                } catch (e: IOException) {
                    throw SecretStorageException("cannot read secret '$name'", e)
                }
            val (wrapId, payload) = decode(name, bytes)
            val value =
                when (wrapId) {
                    wrap.id -> {
                        wrap.open(name, payload)
                    }

                    SecretWrap.NONE.id -> {
                        // Stored before a keychain wrap existed: seal it now (best effort; the value is returned anyway).
                        payload.copyOf().also { runCatching { write(name, it) } }
                    }

                    else -> {
                        throw SecretStorageException("secret '$name' was sealed by wrap $wrapId, this store uses ${wrap.id}")
                    }
                }
            payload.fill(0)
            value
        }

    override fun put(
        name: String,
        value: ByteArray,
    ) {
        val copy = value.copyOf()
        try {
            synchronized(lock) { write(name, copy) }
        } finally {
            copy.fill(0)
        }
    }

    override fun delete(name: String) {
        synchronized(lock) {
            try {
                Files.deleteIfExists(pathOf(name))
            } catch (e: IOException) {
                throw SecretStorageException("cannot delete secret '$name'", e)
            }
        }
    }

    /** The names stored, decoded; files that are not secrets of this store are skipped. */
    fun names(): Set<String> =
        synchronized(lock) {
            try {
                Files.list(directory).use { entries ->
                    entries
                        .map { it.fileName.toString() }
                        .filter { it.endsWith(SUFFIX) }
                        .map { decodeName(it.removeSuffix(SUFFIX)) }
                        .toList()
                        .filterNotNull()
                        .toSet()
                }
            } catch (e: IOException) {
                throw SecretStorageException("cannot list $directory", e)
            }
        }

    /** The file that holds [name]. */
    fun pathOf(name: String): Path = directory.resolve(encodeName(name) + SUFFIX)

    private fun write(
        name: String,
        value: ByteArray,
    ) {
        val sealed = wrap.seal(name, value)
        val target = pathOf(name)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + sealed.size)
        buffer.put(MAGIC).put(VERSION).put(wrap.id.toByte()).putInt(sealed.size).put(sealed)
        sealed.fill(0)
        val temp = directory.resolve(".${encodeName(name)}.${java.lang.Long.toHexString(random.nextLong())}.tmp")
        try {
            Files.createFile(temp, *OwnerOnlyFiles.fileAttributes())
            OwnerOnlyFiles.restrict(temp)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                buffer.flip()
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            throw SecretStorageException("cannot store secret '$name'", e)
        } finally {
            buffer.array().fill(0)
        }
    }

    private fun decode(
        name: String,
        bytes: ByteArray,
    ): Pair<Int, ByteArray> {
        try {
            if (bytes.size < HEADER_SIZE) throw SecretStorageException("secret '$name' is truncated (${bytes.size} bytes)")
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw SecretStorageException("secret '$name' is not a secret file")
            if (bytes[MAGIC.size] != VERSION) throw SecretStorageException("secret '$name' has unknown version ${bytes[MAGIC.size]}")
            val wrapId = bytes[MAGIC.size + 1].toInt() and 0xFF
            val length = ByteBuffer.wrap(bytes, MAGIC.size + 2, 4).int
            if (length < 0 || length != bytes.size - HEADER_SIZE) {
                throw SecretStorageException("secret '$name' declares $length bytes but holds ${bytes.size - HEADER_SIZE}")
            }
            return wrapId to bytes.copyOfRange(HEADER_SIZE, bytes.size)
        } finally {
            bytes.fill(0)
        }
    }

    override fun toString(): String = "FileSecretStorage($directory, $wrap)"

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 4 + 1 + 1 + 4
        private const val MAX_WRAP_ID = 255
        private const val SUFFIX = ".secret"

        /** Longest name accepted (encoded names must stay well within file-name limits). */
        const val MAX_NAME_LENGTH: Int = 64

        /**
         * The file-name form of [name]: `[a-z0-9._-]` kept, every other UTF-8 byte `%XX` (upper-case hex), so names
         * differing only in case never collide on a case-insensitive file system.
         *
         * @throws IllegalArgumentException for an empty name or one longer than [MAX_NAME_LENGTH] characters.
         */
        fun encodeName(name: String): String {
            require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "secret names are 1–$MAX_NAME_LENGTH characters" }
            val out = StringBuilder()
            for (byte in name.encodeToByteArray()) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
            // A leading dot would make it a hidden temporary file of this store.
            if (out.startsWith('.')) out.replace(0, 1, "%2E")
            return out.toString()
        }

        /** The name [encodeName] made [encoded] from, or null when [encoded] is not such a name. */
        fun decodeName(encoded: String): String? {
            val bytes = ArrayList<Byte>(encoded.length)
            var i = 0
            while (i < encoded.length) {
                val c = encoded[i]
                if (c == '%') {
                    val hex = encoded.substring(i + 1, minOf(i + 3, encoded.length))
                    if (hex.length != 2) return null
                    val value = hex.toIntOrNull(16) ?: return null
                    bytes += value.toByte()
                    i += 3
                } else {
                    if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-')) return null
                    bytes += c.code.toByte()
                    i++
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
