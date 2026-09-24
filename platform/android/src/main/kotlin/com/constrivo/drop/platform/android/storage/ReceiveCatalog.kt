package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.receive.ResumeRecord
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.receive.ResumeSummary
import java.util.concurrent.ConcurrentHashMap

/** What the store needs to know about one received file before its first byte arrives: its name and type. */
data class CatalogEntry(
    val name: String,
    val mime: String?,
)

/** What the store needs to know about a received drop: how many files (the subfolder rule) and who sent it. */
data class CatalogDrop(
    val fileCount: Int,
    val senderName: String?,
)

/**
 * The names and types of the files of running receives, by transfer id, for [MediaStoreFileStore]: the engine's
 * `FileStore.openPartial` names only the file index, but a MediaStore item must be created with its name, type and
 * collection (N14: the pending item is created at the file's first write). The engine creates the resume record from
 * the complete file list before it places any byte (`ResumeStore.create`), and loads it before a resume; [recording]
 * wraps the resume store so both land here first. Thread-safe; entries go with [forget].
 */
class ReceiveCatalog {
    private class Transfer(
        val files: List<FileEntry>,
        val senderName: String?,
    )

    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val senders = ConcurrentHashMap<String, String>()

    /** The entry of [fileIndex] of [transferId] (hex), or null when the file list is not known here. */
    fun entry(
        transferId: String,
        fileIndex: Int,
    ): CatalogEntry? {
        val files = transfers[transferId]?.files ?: return null
        val file =
            files.getOrNull(fileIndex)?.takeIf { it.index == fileIndex } ?: files.firstOrNull { it.index == fileIndex } ?: return null
        return CatalogEntry(file.name, file.mime)
    }

    /** The drop facts of [transferId] (hex), or null when its file list is not known here. */
    fun drop(transferId: String): CatalogDrop? {
        val transfer = transfers[transferId] ?: return null
        return CatalogDrop(transfer.files.size, transfer.senderName ?: senders[transferId])
    }

    /** Records the complete file list of [transferId]. */
    fun record(
        transferId: TransferId,
        files: List<FileEntry>,
    ) {
        val hex = transferId.toHex()
        transfers[hex] = Transfer(files.sortedBy { it.index }, senders[hex])
    }

    /** Remembers who sends [transferId], for the drop subfolder's name (known from the handshake, before the list). */
    fun setSender(
        transferId: TransferId,
        name: String?,
    ) {
        val hex = transferId.toHex()
        if (name.isNullOrBlank()) senders.remove(hex) else senders[hex] = name
        transfers.computeIfPresent(hex) { _, t -> Transfer(t.files, name?.takeIf { it.isNotBlank() }) }
    }

    /** Drops what is known about [transferId] (it ended). */
    fun forget(transferId: String) {
        transfers.remove(transferId)
        senders.remove(transferId)
    }

    /** [delegate] with every file list that passes through it recorded here first. */
    fun recording(delegate: ResumeStore): ResumeStore =
        object : ResumeStore by delegate {
            override suspend fun load(transferId: TransferId): ResumeRecord? =
                delegate.load(transferId)?.also { record(transferId, it.files) }

            override suspend fun create(
                transferId: TransferId,
                summary: ResumeSummary,
                files: List<FileEntry>,
                atMillis: Long,
                peerIdentityKey: ByteArray,
            ) {
                record(transferId, files)
                delegate.create(transferId, summary, files, atMillis, peerIdentityKey)
            }
        }
}
