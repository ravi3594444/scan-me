package com.constrivo.drop.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The Stats tab figures (F-G4, design §6), computed from the `transfer` table alone so they reconcile with History
 * by construction.
 *
 * Definitions, over the **counted** transfers: History rows with status `done` (including partial ones, whose
 * [TransferRecord.bytesDone] covers only the files that arrived). Failed and cancelled transfers are not counted.
 * - [totalBytes]: the sum of their `bytes_done`.
 * - [activeMillis]: the sum over them of the time data was moving: `bytes_done × 1000 / avg_speed_bps` (integer
 *   division) when a speed was recorded, else `finished_at − started_at`.
 * - [averageBytesPerSecond]: `totalBytes × 1000 / activeMillis`, the byte-weighted average speed; null before any
 *   data moved.
 * - [weeks]: counted transfers per local calendar week, by start time, for the last [StatsRepository.WEEKS] weeks,
 *   oldest first; the last entry is the current week and [transfersThisWeek] its count.
 * - [timeSavedMillis]: the sum over counted transfers of `max(0, bytes_done × 1000 / B − active time)`, where B is
 *   [BLUETOOTH_BYTES_PER_SECOND]: how much longer the same bytes would have taken over Bluetooth.
 */
data class TransferStats(
    val transferCount: Long,
    val totalBytes: Long,
    val activeMillis: Long,
    val transfersThisWeek: Int,
    val weeks: List<WeekCount>,
    val timeSavedMillis: Long,
) {
    val averageBytesPerSecond: Long?
        get() =
            when {
                activeMillis <= 0 -> null
                totalBytes <= Long.MAX_VALUE / 1000 -> totalBytes * 1000 / activeMillis
                else -> (totalBytes.toDouble() * 1000 / activeMillis).toLong()
            }

    /** [timeSavedMillis] in hours, for the "Hours saved vs Bluetooth" tile. */
    val hoursSaved: Double get() = timeSavedMillis / 3_600_000.0

    companion object {
        /**
         * The Bluetooth baseline for "hours saved": 0.15 MB/s (150,000 bytes per second, decimal MB as in the UI
         * speed readouts, S6), the middle of the 0.1–0.2 MB/s BLE range of architecture §1.
         */
        const val BLUETOOTH_BYTES_PER_SECOND: Long = 150_000
    }
}

/** Counted transfers in one local calendar week starting on [startEpochDay] (`[startMillis], [endMillis])`). */
data class WeekCount(
    val startEpochDay: Long,
    val startMillis: Long,
    val endMillis: Long,
    val transfers: Int,
)

/**
 * Computes [TransferStats] (F-G4) in the user's [calendar], with weeks starting on [firstDayOfWeek] (ISO Monday by
 * default; pass the locale's first day). Main-safe.
 */
class StatsRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
    private val clock: WallClock,
    private val calendar: LocalCalendar,
    private val firstDayOfWeek: Weekday,
) {
    private val queries get() = database.transferQueries

    /** The figures at [nowMillis] ("this week" is the local week containing it). */
    suspend fun stats(nowMillis: Long = clock.nowMillis()): TransferStats = withContext(context) { compute(nowMillis) }

    /**
     * [stats], recomputed when a done transfer is added or removed and when a new local week starts (so "this week"
     * resets without any write). Progress writes of running transfers, and failed or cancelled ones, only re-run a
     * cheap check over the status index (the count and start times of the done rows).
     * The week boundary is re-checked at least every [RECHECK_MILLIS] of awake time, since coroutine delays stop while
     * the CPU sleeps.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<TransferStats> =
        queries
            .statsFingerprint()
            .asFlow()
            .mapToOne(context)
            .distinctUntilChanged()
            .transformLatest {
                while (true) {
                    val now = clock.nowMillis()
                    val stats = withContext(context) { compute(now) }
                    emit(stats)
                    delay((stats.weeks.last().endMillis - now).coerceIn(1, RECHECK_MILLIS))
                }
            }.distinctUntilChanged()

    private fun compute(nowMillis: Long): TransferStats {
        val weeks = weekBoundaries(nowMillis)
        // One read transaction, so the totals and the weekly counts see the same rows.
        return database.transactionWithResult {
            val totals = queries.statsTotals(TransferStats.BLUETOOTH_BYTES_PER_SECOND).executeAsOne()
            val starts = queries.statsStartsBetween(weeks.first().startMillis, weeks.last().endMillis).executeAsList()
            val counted = weeks.map { week -> week.copy(transfers = countIn(starts, week.startMillis, week.endMillis)) }
            TransferStats(
                transferCount = totals.transfers,
                totalBytes = totals.bytes ?: 0,
                activeMillis = totals.active_ms ?: 0,
                transfersThisWeek = counted.last().transfers,
                weeks = counted,
                timeSavedMillis = totals.saved_ms ?: 0,
            )
        }
    }

    /** The last [WEEKS] local weeks, oldest first, with zero counts. */
    private fun weekBoundaries(nowMillis: Long): List<WeekCount> {
        val today = calendar.epochDayOf(nowMillis)
        val back = (Weekday.of(today).isoNumber - firstDayOfWeek.isoNumber).mod(7)
        val thisWeek = today - back
        return (WEEKS - 1 downTo 0).map { ago ->
            val start = thisWeek - 7L * ago
            WeekCount(start, calendar.startOfDayMillis(start), calendar.startOfDayMillis(start + 7), 0)
        }
    }

    /** How many of the ascending [starts] fall in `[from, until)`. */
    private fun countIn(
        starts: List<Long>,
        from: Long,
        until: Long,
    ): Int = lowerBound(starts, until) - lowerBound(starts, from)

    private fun lowerBound(
        sorted: List<Long>,
        value: Long,
    ): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Weeks in the Stats chart (design §6). */
        const val WEEKS: Int = 12

        /** [observe] re-reads the wall clock for a new week at least this often (1 h). */
        const val RECHECK_MILLIS: Long = 60L * 60 * 1000
    }
}
