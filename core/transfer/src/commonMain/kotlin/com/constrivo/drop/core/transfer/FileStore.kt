package com.constrivo.drop.core.transfer

/**
 * A file the user picked for sending (a SAF/MediaStore URI on Android, a path on desktop).
 *
 * The engine reads each file sequentially while it streams it (hashing it with SHA-256 on the way, spec change S2) and
 * reads single units again at random positions for retransmits and resumes. A source that changes while it is sent
 * fails the transfer with `Cancel(source)` when a read comes up short; the receiver's SHA-256 check catches the rest.
 */
interface SourceFile {
    /** Display name, sent as `FileEntry.name` (a relative path with `/` separators for folder sends). */
    val name: String

    /** Size in bytes when the transfer starts. */
    val size: Long

    val mimeType: String?

    /** Last-modified time, epoch milliseconds, when known (`FileEntry.modifiedMillis`). */
    val modifiedMillis: Long? get() = null

    /** Reads up to [length] bytes at [position]; returns the count, or -1 at end of file. */
    suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /**
     * Releases the file handle. The engine closes a source once its file is sent and acknowledged, and all of them when
     * the transfer ends or parks; a later [read] (a retransmit, a resume) must open it again, and a close must not break
     * a read that is in progress.
     */
    suspend fun close()
}

/** A partial file in app-private storage: `<transfer_id>/<file_index>.part` (architecture §7.6). */
interface PartialFile {
    val transferId: String
    val fileIndex: Int

    suspend fun write(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Reads back bytes already written (whole-file hash verification, resume checks). */
    suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /** Current length of the partial in bytes (resume: a unit whose bytes lie beyond it is not on disk). */
    suspend fun length(): Long

    /**
     * Makes every byte written so far durable (`fsync`); the resume manifest marks units only after this (N5). Throws
     * when it cannot, including on a closed handle.
     */
    suspend fun sync()

    /** Closes the handle; the partial stays on disk. Idempotent. */
    suspend fun close()
}

/**
 * The transfer a published file belongs to: [fileCount] decides whether the files go into a per-drop subfolder
 * (design §9: more than 20 files), named after [senderName] and [receivedAtMillis].
 */
data class DropInfo(
    val transferId: String,
    val fileCount: Int,
    val senderName: String?,
    val receivedAtMillis: Long,
)

/** Where [FileStore.publish] put a file: its [uri] and the final, de-duplicated [name] the user sees. */
data class PublishedFile(
    val uri: String,
    val name: String,
)

/** Storage failed; [StorageFullException] when the destination ran out of space (§7.8). */
open class StorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The destination is full: the receiver cancels with `reason=storage` and clears its partials (§7.8, T-27). */
class StorageFullException(
    message: String,
    cause: Throwable? = null,
) : StorageException(message, cause)

/**
 * Storage as the engine sees it. Platform modules implement it on MediaStore/Downloads (Android, N14) or the Received
 * folder (desktop: `DirectoryFileStore`). Write failures from a full disk are reported as [StorageFullException].
 */
interface FileStore {
    suspend fun openSource(uri: String): SourceFile

    /** Creates or reopens the partial for ([transferId], [fileIndex]). */
    suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile

    /**
     * Moves a verified partial to its user-visible destination and returns where it went (F-D4, F-D5).
     *
     * [name] is already sanitised by the engine (`FileNameSanitizer`); the store keeps it inside the destination, adds a
     * ` (1)`, ` (2)`, … suffix before the extension when the name is taken, never replaces an existing file, and puts
     * the files of a drop with more than 20 files into one subfolder ([drop]). The partial is closed first.
     */
    suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile

    /** Deletes the partial of one file (it failed verification for good). */
    suspend fun deletePartial(
        transferId: String,
        fileIndex: Int,
    )

    /** Deletes every partial of the transfer. */
    suspend fun deletePartials(transferId: String)

    /** Free bytes at the destination; the receiver declines an Offer that does not fit (§7.8). */
    suspend fun freeBytes(): Long

    /** True when the destination is removable (microSD) — drives the `sdcard` hint (F-D4). */
    val destinationIsRemovable: Boolean
}
