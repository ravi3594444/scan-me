package com.constrivo.drop.platform.desktop.data

import com.constrivo.drop.core.data.ChunkManifest
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.NewTransferFile
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferFile
import com.constrivo.drop.core.data.TransferFileStatus
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.receive.FileResumeState
import com.constrivo.drop.core.transfer.receive.FileResumeStatus
import com.constrivo.drop.core.transfer.receive.ResumeRecord
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.receive.ResumeSummary
import com.constrivo.drop.core.transfer.receive.UnitManifest
import kotlinx.coroutines.CancellationException

/**
 * The receiver's [ResumeStore] (architecture §7.6 with N5) over `core/data`: the app-layer adapter the WP4 and WP8
 * carry-forward asks for, shared by the desktops and, being free of desktop APIs, by Android (WP7e).
 *
 * | Resume record | Stored in |
 * | --- | --- |
 * | the sender's identity (N3) | `transfer.peer_device_id` → `device.identity_pk` |
 * | the file list (`FileList` pages, N12) | `transfer_file` rows (name, size, MIME type) |
 * | unit manifests: bitmap, XXH3 per unit, the Bluetooth-block prefix (S1, N5) | `chunk_manifest` rows, field for field |
 * | per-file state: pending, done with its saved URI and SHA-256 (S2), failed | `transfer_file.status`, `saved_uri`, `sha256` |
 * | the unit plan (chunk size, bundling, bundle count, S4) | [plans] (no column exists for it) |
 *
 * The transfer row itself is written by the app when the `Offer` arrives (it is History), before the engine calls
 * [create]. A record exists while its plan does and its transfer is an unfinished receive: [delete] removes the plan and
 * the manifests but keeps the rows for History, and a finished transfer never resumes. [create] makes a record only for
 * the peer the row names (N3: another identity offering the same `transfer_id` gets none), and a record that replaces
 * an earlier one starts the listed files over (pending, no saved URI); a hash an earlier `FileDone` stored stays until
 * the new `FileDone` replaces it, since the row can only be told a new one.
 *
 * Failures never reach the engine: a store may lag (a lost write only makes the receiver ask for a unit it already
 * has, [ResumeStore]), so a failed write is reported to [onError] and dropped, and a record that cannot be read back
 * completely loads as null (the transfer starts over). Nothing is ever stored ahead of the engine's calls, which is the
 * direction N5 forbids.
 */
class DataResumeStore(
    private val data: DropData,
    private val plans: ResumePlanStore,
    private val onError: (message: String, error: Throwable) -> Unit = { _, _ -> },
) : ResumeStore {
    override suspend fun load(transferId: TransferId): ResumeRecord? =
        guarded("loading the resume record of ${transferId.toHex()}", null) {
            val summary = plans.read(transferId) ?: return@guarded null
            val transfer = data.transfers.get(transferId) ?: return@guarded null
            if (!transfer.isActive || transfer.direction != TransferDirection.RECEIVE) return@guarded null
            val peer = data.devices.find(transfer.peerDeviceId)?.identityKey() ?: return@guarded null
            val rows = data.transferFiles.files(transferId)
            if (rows.size != summary.fileCount || rows.indices.any { rows[it].index != it }) return@guarded null
            val files = rows.map { FileEntry(it.index, it.name, it.size, it.mimeType) }
            val manifests =
                data.manifests
                    .forTransfer(transferId)
                    .associate { it.trackingKey to it.toUnitManifest() }
            val states = rows.mapNotNull { row -> row.resumeState()?.let { row.index to it } }.toMap()
            ResumeRecord(transferId, summary, peer, files, manifests, states, transfer.updatedAtMillis)
        }

    override suspend fun create(
        transferId: TransferId,
        summary: ResumeSummary,
        files: List<FileEntry>,
        atMillis: Long,
        peerIdentityKey: ByteArray,
    ) {
        guarded("creating the resume record of ${transferId.toHex()}", Unit) {
            val transfer = data.transfers.get(transferId)
            if (transfer == null || !transfer.isActive || transfer.direction != TransferDirection.RECEIVE) {
                onError("no unfinished transfer ${transferId.toHex()} to resume into", IllegalStateException("missing transfer row"))
                return@guarded
            }
            // N3: a record belongs to the peer that started the transfer; another identity with the same id gets none.
            val owner = data.devices.find(transfer.peerDeviceId)?.identityKey()
            if (owner == null || !owner.contentEquals(peerIdentityKey)) {
                onError(
                    "transfer ${transferId.toHex()} belongs to another device; no resume record is kept",
                    IllegalStateException("resume record for another peer"),
                )
                return@guarded
            }
            // A new record replaces any old one: its manifests go before the plan names the new layout, and the files of
            // an earlier attempt start over (a done file of the old layout is not done in the new one).
            data.manifests.deleteForTransfer(transferId)
            plans.write(transferId, summary)
            val listed = data.transferFiles.files(transferId)
            for (row in listed) {
                if (row.status !=
                    TransferFileStatus.PENDING
                ) {
                    data.transferFiles.updateStatus(transferId, row.index, TransferFileStatus.PENDING)
                }
                if (row.savedUri != null) data.transferFiles.setSavedUri(transferId, row.index, null)
            }
            val indices = listed.mapTo(HashSet()) { it.index }
            files
                .filter { it.index !in indices }
                .map { NewTransferFile(it.index, it.name, it.mime, it.size) }
                .chunked(FILE_BATCH)
                .forEach { data.transferFiles.add(transferId, it) }
        }
    }

    override suspend fun putManifests(manifests: Collection<UnitManifest>) {
        if (manifests.isEmpty()) return
        guarded("storing ${manifests.size} resume manifests", Unit) {
            data.manifests.putAll(manifests.map { it.toChunkManifest() })
        }
    }

    override suspend fun putFileState(
        transferId: TransferId,
        fileIndex: Int,
        state: FileResumeState,
        atMillis: Long,
    ) {
        guarded("storing the state of file $fileIndex of ${transferId.toHex()}", Unit) {
            when (state.status) {
                FileResumeStatus.DONE -> {
                    data.transferFiles.complete(transferId, fileIndex, state.savedUri, state.sha256)
                }

                FileResumeStatus.FAILED -> {
                    data.transferFiles.updateStatus(transferId, fileIndex, TransferFileStatus.FAILED)
                }

                FileResumeStatus.PENDING -> {
                    data.transferFiles.updateStatus(transferId, fileIndex, TransferFileStatus.PENDING)
                    state.sha256?.let { data.transferFiles.setSha256(transferId, fileIndex, it) }
                }
            }
        }
    }

    override suspend fun delete(transferId: TransferId) {
        guarded("deleting the resume record of ${transferId.toHex()}", Unit) {
            plans.delete(transferId)
            data.manifests.deleteForTransfer(transferId)
        }
    }

    private inline fun <T> guarded(
        what: String,
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(what, e)
            fallback
        }

    companion object {
        /** `transfer_file` rows added per transaction. */
        private const val FILE_BATCH = 1000

        /** The engine's view of a stored manifest (the same fields). */
        fun ChunkManifest.toUnitManifest(): UnitManifest =
            UnitManifest(
                transferId = transferId,
                trackingKey = trackingKey,
                unitCount = unitCount,
                receivedBitmap = receivedBitmap(),
                unitHashes = unitHashes(),
                partialUnit = partialUnit,
                partialBytes = partialBytes,
                updatedAtMillis = updatedAtMillis,
            )

        /** The stored form of an engine manifest (the same fields). */
        fun UnitManifest.toChunkManifest(): ChunkManifest =
            ChunkManifest(
                transferId = transferId,
                trackingKey = trackingKey,
                unitCount = unitCount,
                receivedBitmap = receivedBitmap(),
                unitHashes = unitHashes(),
                partialUnit = partialUnit,
                partialBytes = partialBytes,
                updatedAtMillis = updatedAtMillis,
            )

        /** The engine's file state for a stored row; null for a pending file without a hash (the default). */
        private fun TransferFile.resumeState(): FileResumeState? =
            when (status) {
                TransferFileStatus.DONE -> FileResumeState(FileResumeStatus.DONE, sha256, savedUri)
                TransferFileStatus.FAILED -> FileResumeState(FileResumeStatus.FAILED, sha256)
                else -> sha256?.let { FileResumeState(FileResumeStatus.PENDING, it) }
            }
    }
}
