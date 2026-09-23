package com.constrivo.drop.core.transfer

/** A file the user picked for sending (a SAF/MediaStore URI on Android, a path on desktop). */
interface SourceFile {
    val name: String
    val size: Long
    val mimeType: String?

    /** Reads up to [length] bytes at [position]; returns the count, or -1 at end of file. */
    suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

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

    suspend fun sync()

    suspend fun close()
}

/** Storage as the engine sees it. Platform modules implement it on MediaStore/Downloads or the Received folder. */
interface FileStore {
    suspend fun openSource(uri: String): SourceFile

    /** Creates or reopens the partial for ([transferId], [fileIndex]). */
    suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile

    /**
     * Moves a verified partial to its user-visible destination under a sanitised [name] (F-D5) and returns the
     * saved URI. Media goes to the gallery, documents to Downloads (F-D4).
     */
    suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
    ): String

    suspend fun deletePartials(transferId: String)

    /** Free bytes at the destination; the receiver cancels with `reason=storage` when an Offer does not fit. */
    suspend fun freeBytes(): Long

    /** True when the destination is removable (microSD) — drives the `sdcard` hint (F-D4). */
    val destinationIsRemovable: Boolean
}
