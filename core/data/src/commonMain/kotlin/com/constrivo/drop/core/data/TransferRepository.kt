package com.constrivo.drop.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.data.db.Transfer_row
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Transfers sent and received (architecture §12 `transfer`): the engine writes their lifecycle, History (F-G2) and
 * the Live tab (F-G1) read them, and Stats (F-G4) aggregates them.
 *
 * Lifecycle: [create] on the `Offer`, then [updateStatus], [updateProgress], [recordLink] and [addHint] while it runs,
 * and exactly one [finish]. Every update of a finished transfer is refused (returns false), so a late event cannot
 * reopen it. Each update also moves `updated_at`, the "last activity" the 24 h clean-up measures from (§7.6).
 * Progress is cheap to write but is a database write: the engine should record it at its display rate (250 ms,
 * F-F1) or slower, not per chunk.
 *
 * Every function is main-safe (runs on the database context).
 */
class TransferRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
    private val clock: WallClock,
) {
    private val queries get() = database.transferQueries

    /**
     * Inserts [transfer], started at [startedAtMillis], and returns it.
     *
     * @throws NoSuchRecordException if the peer device is not stored (record it with `DeviceRepository.recordPeer`).
     * @throws DuplicateRecordException if a transfer with this id exists: a peer reused a `transfer_id`.
     */
    suspend fun create(
        transfer: NewTransfer,
        startedAtMillis: Long = clock.nowMillis(),
    ): TransferRecord {
        val id = transfer.id.toDb()
        val histogram = MimeHistogramColumn.encode(transfer.mimeHistogram).ifEmpty { null }
        return withContext(context) {
            database.transactionWithResult {
                if (database.deviceQueries.selectById(transfer.peerDeviceId).executeAsOneOrNull() == null) {
                    throw NoSuchRecordException("no device ${transfer.peerDeviceId}")
                }
                if (queries.exists(id).executeAsOne() > 0) throw DuplicateRecordException("transfer $id already exists")
                queries.insert(
                    id = id,
                    peerDeviceId = transfer.peerDeviceId,
                    direction = TransferDirection.encode(transfer.direction),
                    status = TransferStatus.encode(transfer.status),
                    startedAt = startedAtMillis,
                    bytesTotal = transfer.bytesTotal,
                    fileCount = transfer.fileCount.toLong(),
                    mimeHistogram = histogram,
                )
                toRecord(queries.selectRow(id).executeAsOne())
            }
        }
    }

    suspend fun get(id: TransferId): TransferRecord? =
        withContext(context) {
            queries.selectRow(id.toDb()).executeAsOneOrNull()?.let(::toRecord)
        }

    /** The transfer, re-emitted when it (or its peer's name) changes; null while it does not exist. */
    fun observe(id: TransferId): Flow<TransferRecord?> =
        queries
            .selectRow(id.toDb())
            .asFlow()
            .mapToOneOrNull(context)
            .map { row -> row?.let(::toRecord) }
            .distinctUntilChanged()

    /** Unfinished transfers, newest first: what the engine resumes after a restart and the Live tab lists. */
    suspend fun active(): List<TransferRecord> = withContext(context) { queries.selectActive().executeAsList().map(::toRecord) }

    fun observeActive(): Flow<List<TransferRecord>> =
        queries
            .selectActive()
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::toRecord) }
            .distinctUntilChanged()

    /**
     * Moves an unfinished transfer to a non-terminal [status] (use [finish] for done, failed and cancelled).
     * Returns false when the transfer is missing or already finished.
     */
    suspend fun updateStatus(
        id: TransferId,
        status: TransferStatus,
        atMillis: Long = clock.nowMillis(),
    ): Boolean {
        require(!status.isTerminal) { "finish a transfer with finish(), not updateStatus($status)" }
        return withContext(context) {
            queries.updateStatus(status = TransferStatus.encode(status), updatedAt = atMillis, id = id.toDb()).value > 0
        }
    }

    /**
     * Records [bytesDone] (file bytes received and verified, or acknowledged by the receiver). Values outside
     * `0..bytesTotal` are stored clamped. Returns false when the transfer is missing or finished.
     */
    suspend fun updateProgress(
        id: TransferId,
        bytesDone: Long,
        atMillis: Long = clock.nowMillis(),
    ): Boolean =
        withContext(context) {
            database.transactionWithResult {
                val total = queries.selectBytesTotal(id.toDb()).executeAsOneOrNull()?.bytes_total ?: return@transactionWithResult false
                queries.updateProgress(bytesDone = bytesDone.coerceIn(0, total), updatedAt = atMillis, id = id.toDb()).value > 0
            }
        }

    /**
     * Records the link data now flows over and its band (the badge, F-F2): at the first Bluetooth chunk and after each
     * hop. Returns false when the transfer is missing or finished.
     */
    suspend fun recordLink(
        id: TransferId,
        transport: LinkKind,
        band: WifiBand?,
        atMillis: Long = clock.nowMillis(),
    ): Boolean =
        withContext(context) {
            queries
                .updateLink(
                    transport = LinkKindColumn.encode(transport),
                    band = band?.let { WifiBand.encode(it) },
                    updatedAt = atMillis,
                    id = id.toDb(),
                ).value > 0
        }

    /**
     * Adds [hint] to the transfer's hints (F-F3) unless already there. Returns true when the transfer is unfinished
     * and now carries the hint; false when it is missing or finished.
     */
    suspend fun addHint(
        id: TransferId,
        hint: HintCode,
        atMillis: Long = clock.nowMillis(),
    ): Boolean =
        withContext(context) {
            database.transactionWithResult {
                val row = queries.selectHints(id.toDb()).executeAsOneOrNull() ?: return@transactionWithResult false
                val hints = row.hint_codes?.let { HintCodesColumn.decodeColumn(it, "transfer ${id.toDb()}") }.orEmpty()
                if (hint in hints) return@transactionWithResult true
                queries.updateHints(hintCodes = HintCodesColumn.encode(hints + hint), updatedAt = atMillis, id = id.toDb()).value > 0
            }
        }

    /**
     * Finishes the transfer with [outcome] at [finishedAtMillis]: status, bytes, link, band, average speed and hints
     * (merged with those recorded). Files still pending or in progress become [TransferFileStatus.CANCELLED], so the
     * detail sheet never shows them waiting. Returns false when the transfer is missing or already finished.
     *
     * @throws IllegalArgumentException if [TransferOutcome.failedFiles] exceeds the transfer's file count.
     */
    suspend fun finish(
        id: TransferId,
        outcome: TransferOutcome,
        finishedAtMillis: Long = clock.nowMillis(),
    ): Boolean {
        val key = id.toDb()
        return withContext(context) {
            database.transactionWithResult {
                val totals = queries.selectBytesTotal(key).executeAsOneOrNull() ?: return@transactionWithResult false
                val row = queries.selectHints(key).executeAsOneOrNull() ?: return@transactionWithResult false
                require(outcome.failedFiles <= totals.file_count) {
                    "failed files ${outcome.failedFiles} exceed the file count ${totals.file_count}"
                }
                val hints = row.hint_codes?.let { HintCodesColumn.decodeColumn(it, "transfer $key") }.orEmpty()
                val merged = (hints + outcome.hints).distinct()
                val updated =
                    queries
                        .finish(
                            status = TransferStatus.encode(outcome.status),
                            finishedAt = finishedAtMillis,
                            bytesDone = outcome.bytesDone.coerceAtMost(totals.bytes_total),
                            transport = outcome.transport?.let { LinkKindColumn.encode(it) },
                            band = outcome.band?.let { WifiBand.encode(it) },
                            avgSpeedBps = outcome.avgSpeedBytesPerSecond,
                            hintCodes = HintCodesColumn.encode(merged).ifEmpty { null },
                            failedFiles = outcome.failedFiles.toLong(),
                            id = key,
                        ).value > 0
                if (updated) {
                    database.transferFileQueries.updateUnfinishedStatus(
                        status = TransferFileStatus.encode(TransferFileStatus.CANCELLED),
                        transferId = key,
                    )
                }
                updated
            }
        }
    }

    /**
     * One page of History (F-G2), newest first: the first page with [after] null, then the page after each
     * [HistoryPage.next]. Keyset paging, so a page costs the same at any depth and rows inserted meanwhile never
     * shift or repeat entries.
     */
    suspend fun historyPage(
        after: HistoryCursor? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): HistoryPage {
        require(limit in 1..MAX_PAGE_SIZE) { "page size $limit out of range 1..$MAX_PAGE_SIZE" }
        val rows =
            withContext(context) {
                val query =
                    if (after == null) {
                        queries.historyFirstPage(limit + 1L)
                    } else {
                        queries.historyPageAfter(startedAt = after.startedAtMillis, id = after.id.toDb(), limit = limit + 1L)
                    }
                query.executeAsList()
            }
        val records = rows.take(limit).map(::toRecord)
        return HistoryPage(records, if (rows.size > limit) records.last().cursor else null)
    }

    /** The newest [limit] transfers, re-emitted on every change: the top of History, live. */
    fun observeHistory(limit: Int = DEFAULT_PAGE_SIZE): Flow<List<TransferRecord>> {
        require(limit in 1..MAX_PAGE_SIZE) { "page size $limit out of range 1..$MAX_PAGE_SIZE" }
        return queries
            .historyFirstPage(limit.toLong())
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::toRecord) }
            .distinctUntilChanged()
    }

    /** The newest [limit] transfers with one peer, newest first. */
    suspend fun historyWith(
        peerDeviceId: String,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<TransferRecord> {
        DeviceIds.requireValid(peerDeviceId)
        require(limit in 1..MAX_PAGE_SIZE) { "page size $limit out of range 1..$MAX_PAGE_SIZE" }
        return withContext(context) { queries.historyForPeer(peerDeviceId, limit.toLong()).executeAsList().map(::toRecord) }
    }

    /**
     * Deletes a finished transfer with its files and manifests (History "delete"). Returns false when it is missing
     * or still running: the engine owns a running transfer.
     *
     * A received transfer that failed or was cancelled may still have partial files, and once its row and manifests
     * are gone nothing would find them again (§7.6 wants them deleted): [deletePartials] (`FileStore.deletePartials`,
     * idempotent) runs first, and only when it succeeded are the rows deleted. If it throws, nothing is deleted and
     * the exception propagates; deleting again is safe.
     */
    suspend fun delete(
        id: TransferId,
        deletePartials: suspend (TransferId) -> Unit,
    ): Boolean {
        val key = id.toDb()
        val direction = withContext(context) { queries.selectFinishedDirection(key).executeAsOneOrNull() } ?: return false
        if (direction == TransferDirection.RECEIVE.dbValue) deletePartials(id)
        // Finished is final, so the transfer is still finished here; a concurrent delete makes this return false.
        return withContext(context) { database.transactionWithResult { deleteFinishedRows(key) } }
    }

    /**
     * History "clear" (F-G2): deletes every finished transfer with its files and manifests; returns how many.
     * Running transfers stay. As in [delete], the partial files of every finished received transfer are deleted first
     * with [deletePartials]; if it throws, nothing is deleted and the exception propagates. A transfer that finishes
     * while this runs is left for the next clear, since its partial files were not deleted.
     */
    suspend fun clearHistory(deletePartials: suspend (TransferId) -> Unit): Int {
        val finished = withContext(context) { queries.selectFinished().executeAsList() }
        for (row in finished) {
            if (row.direction == TransferDirection.RECEIVE.dbValue) deletePartials(transferIdFromDb(row.id))
        }
        return withContext(context) {
            database.transactionWithResult { finished.count { row -> deleteFinishedRows(row.id) } }
        }
    }

    /** Deletes a finished transfer's rows, children first so no orphan remains even on a driver without foreign keys. */
    private fun deleteFinishedRows(key: String): Boolean {
        queries.deleteFilesOfFinished(key)
        queries.deleteManifestsOfFinished(key)
        return queries.deleteFinished(key).value > 0
    }

    /**
     * Cancels every unfinished transfer with no activity since [cutoffMillis], neither on its row nor in a manifest:
     * the "Interrupted → Cancelled after 24 h" rule of §7.7 for transfers no engine is holding any more (the app
     * was killed while one was parked). Their finish time is [atMillis]; their last activity stays what it was, so
     * the manifest purge that follows still sees them idle. Returns their ids, for partial-file clean-up.
     */
    suspend fun expireInactive(
        cutoffMillis: Long,
        atMillis: Long = clock.nowMillis(),
    ): List<TransferId> =
        withContext(context) {
            database.transactionWithResult {
                val ids = queries.selectInactiveUnfinished(cutoffMillis).executeAsList()
                ids.filter { id -> queries.expireIfInactive(finishedAt = atMillis, id = id, cutoff = cutoffMillis).value > 0 }
                    .onEach { id ->
                        database.transferFileQueries.updateUnfinishedStatus(
                            status = TransferFileStatus.encode(TransferFileStatus.CANCELLED),
                            transferId = id,
                        )
                    }.map(::transferIdFromDb)
            }
        }

    /** Whether the transfer is finished; null when it does not exist. */
    internal suspend fun isFinished(id: TransferId): Boolean? =
        withContext(context) { queries.selectFinishedAt(id.toDb()).executeAsOneOrNull()?.let { it.finished_at != null } }

    /**
     * Cancels those of [ids] that are unfinished, at [atMillis] (their resume state is being cleared, so they cannot
     * resume), and returns them. Their unfinished files become cancelled.
     */
    internal suspend fun cancelUnfinished(
        ids: Collection<TransferId>,
        atMillis: Long,
    ): List<TransferId> =
        withContext(context) {
            database.transactionWithResult {
                ids.distinct().filter { id ->
                    val cancelled = queries.cancelUnfinished(finishedAt = atMillis, id = id.toDb()).value > 0
                    if (cancelled) {
                        database.transferFileQueries.updateUnfinishedStatus(
                            status = TransferFileStatus.encode(TransferFileStatus.CANCELLED),
                            transferId = id.toDb(),
                        )
                    }
                    cancelled
                }
            }
        }

    /** Finished transfers that may still have partial files: every received one, and any with a manifest left. */
    internal suspend fun finishedWithResumeData(): List<TransferId> =
        withContext(context) { queries.selectFinishedWithResumeData().executeAsList().map(::transferIdFromDb) }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val MAX_PAGE_SIZE: Int = 1000

        internal fun toRecord(row: Transfer_row): TransferRecord {
            val where = "transfer ${row.id}"
            return TransferRecord(
                id = transferIdFromDb(row.id),
                peerDeviceId = deviceIdFromDb(row.peer_device_id),
                peerName = row.peer_custom_name ?: row.peer_nickname,
                peerPlatform = DevicePlatformColumn.decodeColumn(row.peer_platform, where),
                direction = TransferDirection.decodeColumn(row.direction, where),
                status = TransferStatus.decodeColumn(row.status, where),
                transport = row.transport?.let { LinkKindColumn.decodeColumn(it, where) },
                band = row.band?.let { WifiBand.decodeColumn(it, where) },
                startedAtMillis = row.started_at,
                finishedAtMillis = row.finished_at,
                updatedAtMillis = row.updated_at,
                bytesTotal = row.bytes_total,
                bytesDone = row.bytes_done,
                avgSpeedBytesPerSecond = row.avg_speed_bps,
                hints = row.hint_codes?.let { HintCodesColumn.decodeColumn(it, where) }.orEmpty(),
                fileCount = intFromDb(row.file_count, "$where file_count"),
                mimeHistogram = row.mime_histogram?.let { MimeHistogramColumn.decodeColumn(it, where) }.orEmpty(),
                failedFiles = intFromDb(row.failed_files, "$where failed_files"),
            )
        }
    }
}
