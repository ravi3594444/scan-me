package com.constrivo.drop.web

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Where files sent back from the browser go ("Send files back", design §10, P1). Android backs it with a MediaStore
 * `IS_PENDING` item, desktops with the Received folder ([DirectoryUploadSink]).
 *
 * Calls happen on an IO thread, one [UploadWriter] per uploaded file.
 */
fun interface UploadSink {
    /**
     * Opens a destination for one file of exactly [size] bytes.
     *
     * @param name already [FileNames.sanitize]d; the sink only has to make it unique where it saves it.
     * @param mimeType already [MimeTypes.sanitize]d.
     * @throws IOException when the destination cannot be created; the browser gets 500.
     */
    fun open(
        name: String,
        size: Long,
        mimeType: String,
    ): UploadWriter
}

/** One file being received. Exactly one of [commit] or [abort] is called, after the writes. */
interface UploadWriter {
    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Every byte arrived: make the file visible. Returns the name it was saved under. */
    fun commit(): String

    /** The upload failed or was cancelled: remove what was written. Must not throw. */
    fun abort()
}

/**
 * Uploads are on when the server has a sink, with a cap on the bytes accepted over the whole session (the phone
 * passes, for example, the free space minus a margin). A file that would go past the cap is refused before any byte is
 * stored.
 */
class UploadSettings(
    val sink: UploadSink,
    val maxTotalBytes: Long,
) {
    init {
        require(maxTotalBytes > 0) { "the upload cap must be positive" }
    }

    private val used = AtomicLong()

    /** Bytes still accepted. */
    val remainingBytes: Long get() = maxTotalBytes - used.get()

    /** Reserves [bytes] of the cap; false when they do not fit. */
    internal fun reserve(bytes: Long): Boolean {
        while (true) {
            val current = used.get()
            if (bytes > maxTotalBytes - current) return false
            if (used.compareAndSet(current, current + bytes)) return true
        }
    }

    /** Returns a reservation of a failed upload. */
    internal fun release(bytes: Long) {
        used.addAndGet(-bytes)
    }
}

/**
 * An [UploadSink] writing into [directory]: each file goes to a hidden `.part` file first and is moved to a unique
 * name (`name`, `name (2)`, …) on [UploadWriter.commit], so a half-received file never appears under its real name.
 */
class DirectoryUploadSink(
    private val directory: Path,
) : UploadSink {
    override fun open(
        name: String,
        size: Long,
        mimeType: String,
    ): UploadWriter {
        Files.createDirectories(directory)
        val part = Files.createTempFile(directory, ".upload-", ".part")
        val out = Files.newOutputStream(part, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        return PathUploadWriter(directory, part, out, FileNames.sanitize(name))
    }

    private class PathUploadWriter(
        private val directory: Path,
        private val part: Path,
        private val out: OutputStream,
        private val name: String,
    ) : UploadWriter {
        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) = out.write(buffer, offset, length)

        override fun commit(): String {
            out.close()
            for (n in 1..MAX_ATTEMPTS) {
                val candidate = if (n == 1) name else FileNames.numbered(name, n)
                val target = directory.resolve(candidate)
                check(target.parent == directory) { "sanitised names never leave the directory" }
                try {
                    // Reserve the name first: a plain rename would replace a file that already has it.
                    Files.createFile(target)
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
                try {
                    try {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: IOException) {
                    runCatching { Files.deleteIfExists(target) }
                    throw e
                }
                return candidate
            }
            throw IOException("no free name for $name in $directory")
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { Files.deleteIfExists(part) }
        }

        private companion object {
            const val MAX_ATTEMPTS = 10_000
        }
    }
}
