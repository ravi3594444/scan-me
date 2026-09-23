package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The 24-hour clean-up of resume state (architecture §7.6: "partials and manifests are deleted 24 h after the
 * transfer's last activity"; §7.7 with S8: an interrupted transfer is cancelled when the parked window ends).
 *
 * One [runOnce] pass, with `cutoff = now − retention`:
 * 1. unfinished transfers with no activity since the cutoff (no engine holds them any more, typically after an app
 *    kill while parked) become `cancelled` ([TransferRepository.expireInactive]);
 * 2. for them and for every transfer whose manifests are idle since the cutoff, [deletePartials] removes the partial
 *    files (`FileStore.deletePartials` in `core/transfer`; must be idempotent);
 * 3. only after that succeeded are the transfer's manifests deleted, re-checking the cutoff in the same statement.
 *
 * Deleting files before rows means a crash in between leaves rows that the next pass finds again, never partial
 * files nothing points to. A transfer whose [deletePartials] fails keeps its manifests and is retried next pass.
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
        /** Unfinished transfers that were cancelled for inactivity. */
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
        val candidates = (expired + manifests.inactiveTransfers(cutoff)).distinct()
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
            manifests.deleteIfInactive(id, cutoff)
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
