package com.constrivo.drop.core.data

import com.constrivo.drop.core.data.db.Chunk_manifest
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The receiver's resume manifests (architecture §7.6 `chunk_manifest`, with S1, N5), one row per tracking key.
 *
 * **Durability rule (N5).** A manifest may say a unit is received only once that unit's bytes were written to the
 * `.part` file and `fsync`ed. Sync the partial file first, then build the manifest with
 * [ChunkManifest.withReceived], then [put] it. The database commit itself may lag (WAL with `synchronous=NORMAL`
 * can lose the last commits on power loss), which is safe: a lost update only makes the receiver ask for a unit
 * it already has; the unsafe direction, a bit without its bytes, cannot happen when this order is kept.
 *
 * Only an unfinished transfer takes manifests ([put]): once a transfer is finished its partial files may be deleted
 * at any time ([ResumeDataCleaner]), so a late write-behind flush must not recreate a manifest that claims their bytes.
 *
 * Rows go away with [deleteForTransfer] when a transfer completes, with their transfer
 * ([TransferRepository.delete]), and through [ResumeDataCleaner] (or [purgeInactive]) 24 hours after the transfer's
 * last activity. Every function is main-safe.
 */
class ChunkManifestRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
    private val clock: WallClock,
) {
    private val queries get() = database.chunkManifestQueries

    suspend fun get(
        transferId: TransferId,
        trackingKey: Int,
    ): ChunkManifest? =
        withContext(context) {
            queries.selectOne(transferId.toDb(), trackingKey.toLong()).executeAsOneOrNull()?.let(::toManifest)
        }

    /** Every manifest of the transfer, ordered by tracking key (the bundle key -1 first). */
    suspend fun forTransfer(transferId: TransferId): List<ChunkManifest> =
        withContext(context) { queries.selectForTransfer(transferId.toDb()).executeAsList().map(::toManifest) }

    /**
     * Stores [manifest], replacing the stored one for its key; `updated_at` is [ChunkManifest.updatedAtMillis].
     * See the durability rule in the class comment.
     *
     * @return true when stored; false when the transfer is already finished (a flush that lost the race with
     *   [TransferRepository.finish] or with the 24 h expiry), in which case nothing is stored.
     * @throws NoSuchRecordException if the transfer is not stored.
     */
    suspend fun put(manifest: ChunkManifest): Boolean = putAll(listOf(manifest)) == 1

    /**
     * [put] for several manifests in one transaction: one write-behind flush (≤ 100 ms, §7.6). Returns how many were
     * stored; the manifests of finished transfers are skipped.
     *
     * @throws NoSuchRecordException if a transfer is not stored; nothing is stored then.
     */
    suspend fun putAll(manifests: Collection<ChunkManifest>): Int {
        if (manifests.isEmpty()) return 0
        return withContext(context) {
            database.transactionWithResult {
                // Per transfer: whether it still takes manifests.
                val open = HashMap<String, Boolean>()
                var stored = 0
                for (manifest in manifests) {
                    val id = manifest.transferId.toDb()
                    val unfinished =
                        open.getOrPut(id) {
                            val row =
                                database.transferQueries.selectFinishedAt(id).executeAsOneOrNull()
                                    ?: throw NoSuchRecordException("no transfer $id")
                            row.finished_at == null
                        }
                    if (!unfinished) continue
                    queries.put(
                        transfer_id = id,
                        file_index = manifest.trackingKey.toLong(),
                        unit_count = manifest.unitCount.toLong(),
                        received_bitmap = manifest.receivedBitmap(),
                        chunk_hashes = manifest.unitHashes(),
                        partial_unit = manifest.partialUnit?.toLong(),
                        partial_bytes = manifest.partialBytes.toLong(),
                        updated_at = manifest.updatedAtMillis,
                    )
                    stored++
                }
                stored
            }
        }
    }

    suspend fun delete(
        transferId: TransferId,
        trackingKey: Int,
    ): Boolean = withContext(context) { queries.deleteOne(transferId.toDb(), trackingKey.toLong()).value > 0 }

    /** Deletes every manifest of the transfer (it completed, or its partials were cleared); returns how many. */
    suspend fun deleteForTransfer(transferId: TransferId): Int =
        withContext(context) { queries.deleteForTransfer(transferId.toDb()).value.toInt() }

    /**
     * Transfers with manifests whose last activity, on any manifest and on the transfer row, is before [cutoffMillis],
     * in id order. The partial files of these transfers may be deleted.
     */
    suspend fun inactiveTransfers(cutoffMillis: Long): List<TransferId> =
        withContext(context) { queries.selectInactiveTransfers(cutoffMillis).executeAsList().map(::transferIdFromDb) }

    /**
     * Deletes the transfer's manifests if it is still inactive since [cutoffMillis]: the check and the delete are one
     * statement, so a manifest written after [inactiveTransfers] ran survives. Returns how many rows went.
     */
    suspend fun deleteIfInactive(
        transferId: TransferId,
        cutoffMillis: Long,
    ): Int = withContext(context) { queries.deleteIfInactive(transferId.toDb(), cutoffMillis).value.toInt() }

    /**
     * Deletes every manifest of the transfer if it is finished; returns how many rows went. A finished transfer takes
     * no new manifest ([put]), so after its partial files were deleted nothing can bring one back.
     */
    internal suspend fun deleteOfFinished(transferId: TransferId): Int =
        withContext(context) { database.transferQueries.deleteManifestsOfFinished(transferId.toDb()).value.toInt() }

    /**
     * Deletes the manifests of every transfer inactive for more than [maxInactiveMillis] (architecture §7.6: 24 h)
     * and returns those transfers. The partial files are the caller's: [ResumeDataCleaner] deletes them first.
     */
    suspend fun purgeInactive(
        nowMillis: Long = clock.nowMillis(),
        maxInactiveMillis: Long = ResumeDataCleaner.RETENTION_MILLIS,
    ): List<TransferId> {
        require(maxInactiveMillis >= 0) { "maxInactiveMillis must be non-negative" }
        val cutoff = nowMillis - maxInactiveMillis
        return inactiveTransfers(cutoff).filter { deleteIfInactive(it, cutoff) > 0 }
    }

    private fun toManifest(row: Chunk_manifest): ChunkManifest {
        val where = "manifest ${row.file_index} of transfer ${row.transfer_id}"
        return try {
            ChunkManifest(
                transferId = transferIdFromDb(row.transfer_id),
                trackingKey = intFromDb(row.file_index, where),
                unitCount = intFromDb(row.unit_count, where),
                receivedBitmap = row.received_bitmap,
                unitHashes = row.chunk_hashes,
                partialUnit = row.partial_unit?.let { intFromDb(it, where) },
                partialBytes = intFromDb(row.partial_bytes, where),
                updatedAtMillis = row.updated_at,
            )
        } catch (e: IllegalArgumentException) {
            throw DataCorruptionException("$where: ${e.message}", e)
        }
    }
}
