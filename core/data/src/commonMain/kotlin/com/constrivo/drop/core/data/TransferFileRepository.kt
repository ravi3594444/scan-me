package com.constrivo.drop.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.data.db.Transfer_file
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A file to list under a transfer: from the receiver's `FileList` pages (N12) or the sender's picks.
 *
 * @property uri the sender's source URI (for History re-send and open, F-G2); null on the receiver until published.
 * @property sha256 known up front only on a sender that hashed before sending; otherwise set on `FileDone` (S2).
 */
data class NewTransferFile(
    val index: Int,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val sha256: Sha256Digest? = null,
    val uri: String? = null,
    val status: TransferFileStatus = TransferFileStatus.PENDING,
) {
    init {
        require(index in 0 until ProtocolConstants.MAX_FILES_PER_TRANSFER) { "file index $index out of range" }
        require(name.isNotEmpty()) { "a file name must not be empty" }
        require(size >= 0) { "file size must be non-negative" }
    }
}

/**
 * One file of a transfer (architecture §12 `transfer_file`).
 *
 * @property savedUri where a received file was published (MediaStore, Downloads, the Received folder), or the source
 *   a sent file was read from; what the History detail sheet opens (F-D3, F-G2).
 * @property sha256 the whole-file SHA-256 from `FileDone` (S2), null until then.
 */
data class TransferFile(
    val transferId: TransferId,
    val index: Int,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val sha256: Sha256Digest?,
    val savedUri: String?,
    val status: TransferFileStatus,
)

/**
 * The files of each transfer, their status and saved URI (architecture §12 `transfer_file`). Rows go away with their
 * transfer ([TransferRepository.delete], [TransferRepository.clearHistory]). Updates return false when the file is
 * not listed. Every function is main-safe.
 */
class TransferFileRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
) {
    private val queries get() = database.transferFileQueries

    /**
     * Lists [files] under [transferId] in one transaction (a whole `FileList` page, or all of a sender's picks).
     *
     * @throws NoSuchRecordException if the transfer is not stored.
     * @throws DuplicateRecordException if an index is already listed, or appears twice in [files]; nothing is added.
     * @throws IllegalArgumentException if an index is not below the transfer's file count.
     */
    suspend fun add(
        transferId: TransferId,
        files: List<NewTransferFile>,
    ) {
        if (files.isEmpty()) return
        val key = transferId.toDb()
        withContext(context) {
            database.transaction {
                val totals =
                    database.transferQueries.selectBytesTotal(key).executeAsOneOrNull()
                        ?: throw NoSuchRecordException("no transfer $key")
                val seen = HashSet<Int>(files.size)
                for (file in files) {
                    require(file.index < totals.file_count) { "file index ${file.index} is not below the file count ${totals.file_count}" }
                    if (!seen.add(file.index) || queries.selectOne(key, file.index.toLong()).executeAsOneOrNull() != null) {
                        throw DuplicateRecordException("transfer $key already lists file ${file.index}")
                    }
                    queries.insert(
                        transfer_id = key,
                        file_index = file.index.toLong(),
                        name = file.name,
                        mime_type = file.mimeType,
                        size = file.size,
                        sha256 = file.sha256?.toByteArray(),
                        saved_uri = file.uri,
                        status = TransferFileStatus.encode(file.status),
                    )
                }
            }
        }
    }

    suspend fun file(
        transferId: TransferId,
        index: Int,
    ): TransferFile? = withContext(context) { queries.selectOne(transferId.toDb(), index.toLong()).executeAsOneOrNull()?.let(::toFile) }

    /** Every file of the transfer in index order. For very large transfers prefer [page]. */
    suspend fun files(transferId: TransferId): List<TransferFile> =
        withContext(context) { queries.selectForTransfer(transferId.toDb()).executeAsList().map(::toFile) }

    /** Up to [limit] files with index at least [fromIndex], in index order (the detail sheet's list). */
    suspend fun page(
        transferId: TransferId,
        fromIndex: Int,
        limit: Int,
    ): List<TransferFile> {
        require(fromIndex >= 0) { "fromIndex must be non-negative" }
        require(limit in 1..TransferRepository.MAX_PAGE_SIZE) { "page size $limit out of range" }
        return withContext(context) {
            queries.selectPage(transferId.toDb(), fromIndex.toLong(), limit.toLong()).executeAsList().map(::toFile)
        }
    }

    /** The transfer's files, re-emitted on every change of the file table. */
    fun observeFiles(transferId: TransferId): Flow<List<TransferFile>> =
        queries
            .selectForTransfer(transferId.toDb())
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::toFile) }
            .distinctUntilChanged()

    /** How many files are in each status; statuses with no file are absent. */
    suspend fun statusCounts(transferId: TransferId): Map<TransferFileStatus, Int> =
        withContext(context) {
            queries.countByStatus(transferId.toDb()).executeAsList().associate { row ->
                TransferFileStatus.decodeColumn(row.status, "files of ${transferId.toDb()}") to intFromDb(row.files, "file count")
            }
        }

    suspend fun updateStatus(
        transferId: TransferId,
        index: Int,
        status: TransferFileStatus,
    ): Boolean =
        withContext(context) {
            queries.updateStatus(TransferFileStatus.encode(status), transferId.toDb(), index.toLong()).value > 0
        }

    /** Records the whole-file hash from `FileDone` (S2). */
    suspend fun setSha256(
        transferId: TransferId,
        index: Int,
        sha256: Sha256Digest,
    ): Boolean = withContext(context) { queries.updateSha256(sha256.toByteArray(), transferId.toDb(), index.toLong()).value > 0 }

    suspend fun setSavedUri(
        transferId: TransferId,
        index: Int,
        uri: String?,
    ): Boolean = withContext(context) { queries.updateSavedUri(uri, transferId.toDb(), index.toLong()).value > 0 }

    /**
     * Marks a file [TransferFileStatus.DONE]: verified and published at [savedUri] (receiver), or confirmed by the
     * receiver (sender). Null arguments keep the stored URI and hash.
     */
    suspend fun complete(
        transferId: TransferId,
        index: Int,
        savedUri: String? = null,
        sha256: Sha256Digest? = null,
    ): Boolean =
        withContext(context) {
            val changed = queries.complete(savedUri, sha256?.toByteArray(), transferId.toDb(), index.toLong()).value
            changed > 0
        }

    private fun toFile(row: Transfer_file): TransferFile {
        val where = "file ${row.file_index} of transfer ${row.transfer_id}"
        val hash =
            row.sha256?.let {
                if (it.size != Sha256Digest.SIZE) throw DataCorruptionException("$where: SHA-256 has ${it.size} bytes")
                Sha256Digest(it)
            }
        return TransferFile(
            transferId = transferIdFromDb(row.transfer_id),
            index = intFromDb(row.file_index, "$where index"),
            name = row.name,
            mimeType = row.mime_type,
            size = row.size,
            sha256 = hash,
            savedUri = row.saved_uri,
            status = TransferFileStatus.decodeColumn(row.status, where),
        )
    }
}
