package com.constrivo.drop.platform.android.service

/**
 * An estimate of Android 15's `dataSync` foreground-service limit (spec change S9, architecture §8 notes): an app's
 * `dataSync` services may run about [limitMillis] (6 h) in any [windowMillis] (24 h); then the system calls
 * `Service.onTimeout(int, int)`, the service must stop within seconds, and no `dataSync` service starts again until the
 * user brings the app to the foreground, which also resets the count. Transfers never come near it, so nothing
 * changes in behaviour: this class keeps the count the service can see and writes it to the ring-buffer log
 * ([ServiceLog], architecture §14) so support can tell when the limit was involved.
 *
 * The count lives in memory: it restarts with the process, while the system's own does not, so the logged figures
 * are a lower bound. The system is the authority on exhaustion ([timedOut], [refused]); [exhausted] only reflects what
 * it reported. Times are elapsed realtime in milliseconds. Not thread-safe: the service calls it on the main thread.
 *
 * @param log a line for the ring-buffer log.
 */
class DataSyncQuota(
    private val limitMillis: Long = LIMIT_MILLIS,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val log: (String) -> Unit = {},
) {
    init {
        require(limitMillis > 0 && windowMillis >= limitMillis) { "the limit must be positive and fit in its window" }
    }

    /** Closed runs of the `dataSync` type as [start, end) pairs, oldest first, all ending inside the window. */
    private val runs = ArrayDeque<LongArray>()
    private var runningSince: Long? = null
    private var warned = 0

    /**
     * Whether the system reported the limit spent ([timedOut], or a start [refused]) and the user has not brought the
     * app to the foreground since: the service then runs a transfer under `connectedDevice` alone.
     */
    var exhausted: Boolean = false
        private set

    /** Whether the `dataSync` type is on now. */
    val running: Boolean get() = runningSince != null

    /** The `dataSync` time counted in the window ending at [now], the current run included. */
    fun usedMillis(now: Long): Long {
        val from = now - windowMillis
        var total = 0L
        for (run in runs) total += overlap(run[0], run[1], from, now)
        runningSince?.let { total += overlap(it, now, from, now) }
        return total
    }

    /** What is left of the limit at [now] by this count (never below 0). */
    fun remainingMillis(now: Long): Long = (limitMillis - usedMillis(now)).coerceAtLeast(0)

    /** The service added `dataSync` to its foreground types at [now]. */
    fun started(now: Long) {
        if (runningSince != null) return
        prune(now)
        runningSince = now
        log("dataSync on; ${describe(now)}")
    }

    /** The service dropped `dataSync` (or left the foreground) at [now]. */
    fun stopped(now: Long) {
        val since = runningSince ?: return
        runningSince = null
        if (now > since) runs.addLast(longArrayOf(since, now))
        prune(now)
        log("dataSync off after ${seconds(now - since)} s; ${describe(now)}")
    }

    /**
     * Logs once when the count passes half and nine tenths of the limit; the service calls it with each progress
     * notification while `dataSync` is on.
     */
    fun check(now: Long) {
        if (runningSince == null) return
        val used = usedMillis(now)
        val level =
            when {
                used >= limitMillis * WARN_HIGH_PERCENT / 100 -> 2
                used >= limitMillis * WARN_LOW_PERCENT / 100 -> 1
                else -> 0
            }
        if (level > warned) {
            warned = level
            log("dataSync near the Android 15 limit; ${describe(now)}")
        }
    }

    /**
     * The user brought the app to the foreground at [now]: Android resets its count then, and a start it refused is
     * allowed again.
     */
    fun userReturned(now: Long) {
        runs.clear()
        warned = 0
        if (runningSince != null) runningSince = now
        if (exhausted) log("dataSync limit reset: the app is in the foreground")
        exhausted = false
    }

    /** `Service.onTimeout` for `dataSync` at [now] (Android 15): the limit is spent and the service leaves the foreground. */
    fun timedOut(now: Long) {
        runningSince?.let { since ->
            if (now > since) runs.addLast(longArrayOf(since, now))
        }
        runningSince = null
        exhausted = true
        log("dataSync time limit reached (Android 15, S9); the service leaves the foreground. ${describe(now)}")
    }

    /** Starting with `dataSync` failed at [now] with [reason] (`ForegroundServiceStartNotAllowedException`). */
    fun refused(
        now: Long,
        reason: String?,
    ) {
        exhausted = true
        log("dataSync start refused: ${reason ?: "not allowed"}; ${describe(now)}")
    }

    private fun describe(now: Long): String =
        "used ${minutes(usedMillis(now))} of ${minutes(limitMillis)} min in the last ${hours(windowMillis)} h (this process's count)"

    private fun prune(now: Long) {
        val from = now - windowMillis
        while (runs.isNotEmpty() && runs.first()[1] <= from) runs.removeFirst()
    }

    private fun overlap(
        start: Long,
        end: Long,
        from: Long,
        to: Long,
    ): Long = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)

    private fun seconds(millis: Long): Long = millis / 1_000

    private fun minutes(millis: Long): Long = millis / 60_000

    private fun hours(millis: Long): Long = millis / 3_600_000

    companion object {
        /** About 6 hours per day (Android 15 behaviour change for `dataSync`). */
        const val LIMIT_MILLIS: Long = 6 * 3_600_000L

        /** The rolling day the limit applies to. */
        const val WINDOW_MILLIS: Long = 24 * 3_600_000L

        private const val WARN_LOW_PERCENT = 50
        private const val WARN_HIGH_PERCENT = 90
    }
}
