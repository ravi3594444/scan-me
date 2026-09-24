package com.constrivo.drop.core.transfer.store

import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageException
import com.constrivo.drop.core.transfer.StorageFullException
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * A [FileStore] on `java.nio.file` for desktops and tests (architecture §7.6, §10.2; design §9; F-D5):
 *
 * - **Sources** are files on disk ([source], or [openSource] with a path or `file:` URI).
 * - **Partials** live under [partialRoot] (app-private) as `<transfer_id>/<file_index>.part`.
 * - **Publishing** moves a verified partial into [destination] (the Received folder) by an atomic rename, under the
 *   sanitised name the engine passes (sanitised again here), never replacing a file: the name is reserved with an
 *   exclusive create first and a taken name gets ` (1)`, ` (2)`, … before its extension. A drop of more than
 *   [subfolderThreshold] files goes into one subfolder named after the sender and the time (design §9). Every target
 *   is checked to stay inside [destination].
 * - **Free space** is the destination file system's usable space; a write that fails for lack of space throws
 *   [StorageFullException].
 */
class DirectoryFileStore(
    val partialRoot: Path,
    val destination: Path,
    private val removable: Boolean = false,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val subfolderThreshold: Int = DEFAULT_SUBFOLDER_THRESHOLD,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : FileStore {
    private val publishLock = Mutex()
    private val dropFolders = ConcurrentHashMap<String, Path>()
    private val open = ConcurrentHashMap.newKeySet<PathPartialFile>()

    init {
        Files.createDirectories(partialRoot)
        Files.createDirectories(destination)
    }

    override val destinationIsRemovable: Boolean get() = removable

    /** A [SourceFile] for [path], named [name] (a relative path for folder sends). */
    fun source(
        path: Path,
        name: String = path.fileName.toString(),
        mimeType: String? = probeMime(path),
    ): SourceFile = PathSourceFile(path, name, mimeType, io)

    override suspend fun openSource(uri: String): SourceFile {
        val path = if (uri.startsWith("file:")) Paths.get(URI(uri)) else Paths.get(uri)
        if (!Files.isRegularFile(path)) throw StorageException("no such file: $uri")
        return source(path)
    }

    override suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile {
        require(fileIndex >= 0) { "file index must be non-negative" }
        val path = partialPath(transferId, fileIndex)
        return withContext(io) {
            try {
                Files.createDirectories(path.parent)
                val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                PathPartialFile(transferId, fileIndex, path, channel, this@DirectoryFileStore, io).also { open += it }
            } catch (e: IOException) {
                throw storageError("cannot open $path", e)
            }
        }
    }

    override suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile {
        partial.close()
        val source = partialPath(partial.transferId, partial.fileIndex)
        val safe = FileNameSanitizer.sanitize(name)
        return publishLock.withLock {
            withContext(io) {
                val folder = folderFor(drop)
                val target = reserve(folder, safe)
                try {
                    try {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        // Another volume (a removable card): copy, then remove the partial.
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                        Files.delete(source)
                    }
                } catch (e: IOException) {
                    runCatching { Files.deleteIfExists(target) }
                    throw storageError("cannot publish $safe", e)
                }
                PublishedFile(target.toUri().toString(), target.fileName.toString())
            }
        }
    }

    override suspend fun deletePartial(
        transferId: String,
        fileIndex: Int,
    ) {
        val path = partialPath(transferId, fileIndex)
        open.filter { it.path == path }.forEach { it.close() }
        withContext(io) { Files.deleteIfExists(path) }
    }

    override suspend fun deletePartials(transferId: String) {
        val dir = transferDir(transferId)
        open.filter { it.path.parent == dir }.forEach { it.close() }
        withContext(io) {
            if (!Files.isDirectory(dir)) return@withContext
            Files.list(dir).use { entries -> entries.forEach { runCatching { Files.deleteIfExists(it) } } }
            runCatching { Files.deleteIfExists(dir) }
        }
    }

    override suspend fun freeBytes(): Long = withContext(io) { Files.getFileStore(destination).usableSpace }

    /** Closes every partial this store has open (a simulated app kill leaves them open otherwise). */
    suspend fun closeAll() {
        open.toList().forEach { it.close() }
    }

    /** The partial of ([transferId], [fileIndex]). */
    fun partialPath(
        transferId: String,
        fileIndex: Int,
    ): Path = transferDir(transferId).resolve("$fileIndex.part")

    private fun transferDir(transferId: String): Path {
        require(transferId.isNotEmpty() && transferId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "transfer id must be a plain token"
        }
        return partialRoot.resolve(transferId)
    }

    internal fun closed(file: PathPartialFile) {
        open.remove(file)
    }

    /** The destination folder of [drop]: the Received folder, or one per-drop subfolder above the threshold. */
    private fun folderFor(drop: DropInfo): Path {
        if (drop.fileCount <= subfolderThreshold) return destination
        dropFolders[drop.transferId]?.let { if (Files.isDirectory(it)) return it }
        val stamp = FOLDER_TIME.format(Instant.ofEpochMilli(drop.receivedAtMillis).atZone(zone))
        val label = FileNameSanitizer.sanitize(listOfNotNull(drop.senderName?.takeIf { it.isNotBlank() }, stamp).joinToString(" "))
        var n = 0
        while (true) {
            val candidate = inside(if (n == 0) label else FileNameSanitizer.withCollisionSuffix(label, n))
            try {
                Files.createDirectory(candidate)
                dropFolders[drop.transferId] = candidate
                return candidate
            } catch (e: FileAlreadyExistsException) {
                n++
            }
        }
    }

    /** Reserves a free name for [safe] in [folder] with an exclusive create and returns it. */
    private fun reserve(
        folder: Path,
        safe: String,
    ): Path {
        var n = 0
        while (true) {
            val name = if (n == 0) safe else FileNameSanitizer.withCollisionSuffix(safe, n)
            val candidate = inside(folder, name)
            try {
                Files.createFile(candidate)
                return candidate
            } catch (e: FileAlreadyExistsException) {
                n++
                if (n > MAX_COLLISIONS) throw StorageException("too many files named $safe")
            } catch (e: IOException) {
                throw storageError("cannot create $candidate", e)
            }
        }
    }

    private fun inside(name: String): Path = inside(destination, name)

    private fun inside(
        folder: Path,
        name: String,
    ): Path {
        val target = folder.resolve(name).normalize()
        val root = destination.toAbsolutePath().normalize()
        if (!target.toAbsolutePath().normalize().startsWith(root) ||
            target.parent?.toAbsolutePath()?.normalize() != folder.toAbsolutePath().normalize()
        ) {
            throw StorageException("$name would leave the destination folder")
        }
        return target
    }

    internal fun storageError(
        message: String,
        cause: IOException,
    ): StorageException {
        val full =
            (cause is FileSystemException && cause.reason?.contains("space", ignoreCase = true) == true) ||
                cause.message?.contains("No space", ignoreCase = true) == true ||
                cause.message?.contains("not enough space", ignoreCase = true) == true ||
                runCatching { Files.getFileStore(destination).usableSpace < LOW_SPACE_BYTES }.getOrDefault(false)
        return if (full) {
            StorageFullException(
                "$message: destination is full",
                cause,
            )
        } else {
            StorageException("$message: ${cause.message}", cause)
        }
    }

    companion object {
        /** Design §9: a drop of more than 20 files goes into its own subfolder. */
        const val DEFAULT_SUBFOLDER_THRESHOLD: Int = 20
        private const val MAX_COLLISIONS = 10_000
        private const val LOW_SPACE_BYTES = 1024L * 1024
        private val FOLDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

        private val MIME_BY_EXTENSION =
            mapOf(
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "png" to "image/png",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "heic" to "image/heic",
                "mp4" to "video/mp4",
                "mov" to "video/quicktime",
                "mp3" to "audio/mpeg",
                "pdf" to "application/pdf",
                "txt" to "text/plain",
                "zip" to "application/zip",
                "apk" to "application/vnd.android.package-archive",
            )

        fun probeMime(path: Path): String? {
            val probed = runCatching { Files.probeContentType(path) }.getOrNull()
            if (probed != null) return probed
            val extension = FileNameSanitizer.extensionOf(path.fileName.toString()) ?: return null
            return MIME_BY_EXTENSION[extension]
        }
    }
}

/**
 * Largest single file read or write: the JDK stages heap-buffer I/O through a per-thread direct buffer of the request's
 * size, so slicing keeps native memory flat (§15). Reads return at most this much; callers loop.
 */
private const val IO_SLICE: Int = 256 * 1024

/** A picked file on disk, read with positional reads so retransmits can read any unit again. */
internal class PathSourceFile(
    private val path: Path,
    override val name: String,
    override val mimeType: String?,
    private val io: CoroutineDispatcher,
) : SourceFile {
    private val lock = Mutex()
    private var channel: FileChannel? = null

    override val size: Long = Files.size(path)

    override val modifiedMillis: Long? = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()

    override suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        val ch = lock.withLock { channel ?: withContext(io) { FileChannel.open(path, StandardOpenOption.READ) }.also { channel = it } }
        return withContext(io) { ch.read(ByteBuffer.wrap(buffer, offset, minOf(length, IO_SLICE)), position) }
    }

    override suspend fun close() {
        lock.withLock {
            channel?.let { runCatching { it.close() } }
            channel = null
        }
    }
}

/** A `.part` file on disk. Positional reads and writes, `force` for `sync` (N5). */
internal class PathPartialFile(
    override val transferId: String,
    override val fileIndex: Int,
    val path: Path,
    private val channel: FileChannel,
    private val store: DirectoryFileStore,
    private val io: CoroutineDispatcher,
) : PartialFile {
    override suspend fun write(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        withContext(io) {
            try {
                var from = offset
                var at = position
                val end = offset + length
                while (from < end) {
                    val bytes = ByteBuffer.wrap(buffer, from, minOf(IO_SLICE, end - from))
                    while (bytes.hasRemaining()) at += channel.write(bytes, at)
                    from = bytes.position()
                }
            } catch (e: IOException) {
                throw store.storageError("writing $path failed", e)
            }
        }
    }

    override suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        return withContext(io) {
            try {
                channel.read(ByteBuffer.wrap(buffer, offset, minOf(length, IO_SLICE)), position)
            } catch (e: IOException) {
                throw store.storageError("reading $path failed", e)
            }
        }
    }

    override suspend fun length(): Long =
        withContext(io) {
            try {
                if (channel.isOpen) channel.size() else Files.size(path)
            } catch (e: NoSuchFileException) {
                0L
            } catch (e: IOException) {
                throw store.storageError("reading $path failed", e)
            }
        }

    override suspend fun sync() {
        withContext(io) {
            try {
                if (channel.isOpen) channel.force(false)
            } catch (e: IOException) {
                throw store.storageError("syncing $path failed", e)
            }
        }
    }

    override suspend fun close() {
        runCatching { channel.close() }
        store.closed(this)
    }
}
