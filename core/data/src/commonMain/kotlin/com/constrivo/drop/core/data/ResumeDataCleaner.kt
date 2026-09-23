package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The 24-hour clean-up of resume state (architecture §7.6: "partials and manifests are deleted 24 h after the
 * transfer's last activity"; §7.7 with S8: an interrupted transfer is cancelled when the parked window ends), and the
 * "Clear partial files" action of Settings (F-G5, [clearPartials]).
 *
 * One [runOnce] pass, with `cutoff = now − retention`:
 * 1. unfinished transfers with no activity since the cutoff (no engine holds them any more, typically after an app
 *    kill while parked) become `cancelled` ([TransferRepository.expireInactive]);
 * 2. for them and for every finished transfer whose manifests are idle since the cutoff, [deletePartials] removes the
 *    partial files (`FileStore.deletePartials` in `core/transfer`; must be idempotent);
 * 3. only after that succeeded are the transfer's manifests deleted.
 *
 * Only finished transfers are cleaned, and a finished transfer takes no new manifest
 * ([ChunkManifestRepository.put]), so no manifest can outlive the bytes it describes, even when an engine's late
 * write-behind flush races the pass. Deleting files before rows means a crash in between leaves rows that the next
 * pass finds again. A transfer whose [deletePartials] fails keeps its manifests and is retried next pass. Deleting a
 * transfer from History deletes its partial files the same way ([TransferRepository.delete]).
 *
 * @param deletePartials deletes the partial files of one transfer; a no-op when there are none.
 * @param retentionMillis inactivity after which resume state goes; [RETENTION_MILLIS] (24 h) by default.
 */
class ResumeDataCleaner(
    private val transfers: TransferRepository,
    private val manifests: ChunkManifestRepository,
    private val clock: WallClock,
    private val retentionMillis: Long = RETENTION_MILLIS,
    private val deletePartials: suspend (TransferId) -> Unit,
) {
    init {
        require(retentionMillis >= 0) { "retention must be non-negative" }
    }

    /** What one pass did. */
    data class Report(
        /** Unfinished transfers that were cancelled because their resume state went (for inactivity, or released). */
        val expired: List<TransferId>,
        /** Transfers whose partial files and manifests were deleted. */
        val purged: List<TransferId>,
        /** Transfers whose partial files could not be deleted, with the error; their manifests were kept. */
        val failed: Map<TransferId, Throwable>,
    )

    /** One clean-up pass at [nowMillis]. */
    suspend fun runOnce(nowMillis: Long = clock.nowMillis()): Report {
        val cutoff = nowMillis - retentionMillis
        val expired = transfers.expireInactive(cutoff, nowMillis)
        // An idle manifest of a transfer that is still unfinished means it became active again after step 1 (or is
        // about to be expired by the next pass): its partial files may be in use, so it is left alone.
        val idle = manifests.inactiveTransfers(cutoff).filter { it !in expired && transfers.isFinished(it) == true }
        return purge(expired, (expired + idle).distinct())
    }

    /**
     * "Clear partial files" (F-G5, design §6 Storage): deletes the partial files and manifests of every finished
     * transfer that may have some (every received one, and any with a manifest left), plus those of the unfinished
     * transfers in [released], whatever their age. [released] are transfers the caller has stopped and no engine holds
     * (for example the parked ones the user agreed to give up): they are cancelled first, since they cannot resume
     * without their partial files. Every other unfinished transfer is left alone, so this never deletes a file an
     * engine is writing. Unlike [runOnce] it cancels nothing for inactivity.
     */
    suspend fun clearPartials(
        released: Collection<TransferId> = emptyList(),
        nowMillis: Long = clock.nowMillis(),
    ): Report {
        val cancelled = transfers.cancelUnfinished(released, nowMillis)
        return purge(cancelled, (cancelled + transfers.finishedWithResumeData()).distinct())
    }

    /** Deletes the partial files, then the manifests, of the finished [candidates]. */
    private suspend fun purge(
        expired: List<TransferId>,
        candidates: List<TransferId>,
    ): Report {
        val purged = ArrayList<TransferId>()
        val failed = LinkedHashMap<TransferId, Throwable>()
        for (id in candidates) {
            try {
                deletePartials(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[id] = e
                continue
            }
            // Finished, so no manifest can be written any more: whatever is left points at deleted bytes.
            manifests.deleteOfFinished(id)
            purged += id
        }
        return Report(expired, purged, failed)
    }

    /**
     * Runs [runOnce] now and then every [intervalMillis] until cancelled (launch it in the app's or the transfer
     * service's scope). A pass that throws does not stop the loop: the error goes to [onError] (for the ring-buffer
     * log, architecture §14) and the next pass runs on schedule.
     */
    suspend fun runPeriodically(
        intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
        onReport: (Report) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ): Nothing {
        require(intervalMillis > 0) { "interval must be positive" }
        while (true) {
            try {
                onReport(runOnce())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            delay(intervalMillis)
        }
    }

    companion object {
        /** §7.6: resume state is kept 24 hours after the transfer's last activity. */
        const val RETENTION_MILLIS: Long = 24L * 60 * 60 * 1000

        /** How often [runPeriodically] runs by default: hourly, so state goes at most an hour late. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 60L * 60 * 1000
    }
}
