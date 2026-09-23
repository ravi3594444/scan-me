package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stats (F-G4): the figures of design §6, reconciled with History. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsTest {
    private val mb = 1_000_000L

    private suspend fun DropData.finished(
        n: Int,
        peer: String,
        start: Long,
        bytes: Long,
        status: TransferStatus = TransferStatus.DONE,
        speed: Long? = null,
        durationMillis: Long = 10_000,
    ) {
        transfers.create(NewTransfer(transferId(n), peer, TransferDirection.SEND, bytes, 1), start)
        transfers.finish(transferId(n), TransferOutcome(status, bytes, LinkKind.P2P, WifiBand.GHZ_5, speed), start + durationMillis)
    }

    @Test
    fun anEmptyHistoryHasZeroStats() =
        runTest {
            val stats = openTestData().stats.stats(T0)
            assertEquals(0, stats.transferCount)
            assertEquals(0, stats.totalBytes)
            assertNull(stats.averageBytesPerSecond)
            assertEquals(0, stats.transfersThisWeek)
            assertEquals(StatsRepository.WEEKS, stats.weeks.size)
            assertTrue(stats.weeks.all { it.transfers == 0 })
            assertEquals(0.0, stats.hoursSaved)
        }

    @Test
    fun figuresFollowTheDocumentedDefinitions() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            // 1 GB at 50 MB/s: 20 s active; Bluetooth would take 1e9 / 150,000 = 6,666.666 s.
            data.finished(1, peer, T0 - HOUR, 1_000 * mb, speed = 50 * mb)
            // 30 MB without a recorded speed: 60 s of wall time.
            data.finished(2, peer, T0 - 2 * HOUR, 30 * mb, durationMillis = 60_000)
            // Slower than Bluetooth (100 KB/s): saves nothing, never negative.
            data.finished(3, peer, T0 - 3 * HOUR, 1 * mb, speed = 100_000)
            // Not counted: failed and cancelled transfers, and one still running.
            data.finished(4, peer, T0 - HOUR, 500 * mb, status = TransferStatus.FAILED, speed = mb)
            data.finished(5, peer, T0 - HOUR, 500 * mb, status = TransferStatus.CANCELLED, speed = mb)
            data.transfers.create(NewTransfer(transferId(6), peer, TransferDirection.RECEIVE, 500 * mb, 1), T0)

            val stats = data.stats.stats(T0)
            assertEquals(3, stats.transferCount)
            assertEquals(1_031 * mb, stats.totalBytes)
            assertEquals(20_000 + 60_000 + 10_000L, stats.activeMillis)
            assertEquals(1_031 * mb * 1000 / 90_000, stats.averageBytesPerSecond)
            val saved = (1_000 * mb * 1000 / 150_000 - 20_000) + (30 * mb * 1000 / 150_000 - 60_000) + 0
            assertEquals(saved, stats.timeSavedMillis)
            assertEquals(saved / 3_600_000.0, stats.hoursSaved)
            assertEquals(3, stats.transfersThisWeek)
            assertEquals(150_000, TransferStats.BLUETOOTH_BYTES_PER_SECOND)
        }

    @Test
    fun weeksAreLocalCalendarWeeksOldestFirst() =
        runTest {
            // T0 is Wednesday 2026-09-23 12:00 UTC; in UTC+05:30 it is 17:30 the same day.
            val calendar = FixedOffsetCalendar(330 * 60_000L)
            val data = openTestData(calendar = calendar)
            val peer = data.peer(1)
            val monday = calendar.startOfDayMillis(calendar.epochDayOf(T0) - 2)
            data.finished(1, peer, monday, mb) // first instant of this week
            data.finished(2, peer, monday - 1, mb) // last instant of last week
            data.finished(3, peer, monday - 11 * 7 * DAY, mb) // first instant of the oldest week shown
            data.finished(4, peer, monday - 11 * 7 * DAY - 1, mb) // just before the chart: counted in totals only
            data.finished(5, peer, T0 + 30 * DAY, mb) // clock skew into the future: totals only

            val stats = data.stats.stats(T0)
            val weeks = stats.weeks
            assertEquals(StatsRepository.WEEKS, weeks.size)
            assertEquals(monday, weeks.last().startMillis)
            assertEquals(monday + 7 * DAY, weeks.last().endMillis)
            assertEquals(Weekday.MONDAY, Weekday.of(weeks.last().startEpochDay))
            for (i in 1 until weeks.size) assertEquals(weeks[i - 1].endMillis, weeks[i].startMillis)
            assertEquals(listOf(1) + List(9) { 0 } + listOf(1, 1), weeks.map { it.transfers })
            assertEquals(1, stats.transfersThisWeek)
            assertEquals(5, stats.transferCount)

            // Weeks starting on Sunday put the Monday-minus-one transfer into this week.
            val sunday = openTestData(calendar = calendar, firstDayOfWeek = Weekday.SUNDAY)
            val p = sunday.peer(1)
            sunday.finished(1, p, monday, mb)
            sunday.finished(2, p, monday - 1, mb)
            assertEquals(2, sunday.stats.stats(T0).transfersThisWeek)
            assertEquals(Weekday.SUNDAY, Weekday.of(sunday.stats.stats(T0).weeks.last().startEpochDay))
        }

    @Test
    fun weeksFollowDaylightSavingInTheUsersZone() =
        runTest {
            val zone = ZoneCalendar(ZoneId.of("Europe/Berlin"))
            val data = openTestData(calendar = zone)
            // 2026-10-25 is the Sunday clocks go back in Berlin; "now" is the Wednesday after.
            val now = java.time.ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, zone.zone).toInstant().toEpochMilli()
            val weeks = data.stats.stats(now).weeks
            val lastWeekStart = java.time.ZonedDateTime.of(2026, 10, 19, 0, 0, 0, 0, zone.zone).toInstant().toEpochMilli()
            val thisWeekStart = java.time.ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, zone.zone).toInstant().toEpochMilli()
            assertEquals(thisWeekStart, weeks.last().startMillis)
            assertEquals(lastWeekStart, weeks[weeks.size - 2].startMillis)
            assertEquals(7 * DAY + HOUR, thisWeekStart - lastWeekStart, "the week with the change is an hour longer")
        }

    @Test
    fun statsReconcileWithHistoryOnGeneratedData() =
        runTest {
            val calendar = FixedOffsetCalendar(-4 * HOUR)
            val data = openTestData(calendar = calendar)
            val peers = (1..5).map { data.peer(it) }
            val random = Random(2026)
            val now = T0
            for (n in 0 until 2_000) {
                val start = now - random.nextLong(120 * DAY)
                val bytes = random.nextLong(0, 4_000 * mb)
                val status =
                    listOf(
                        TransferStatus.DONE,
                        TransferStatus.DONE,
                        TransferStatus.DONE,
                        TransferStatus.FAILED,
                        TransferStatus.CANCELLED,
                    ).random(random)
                val speed = if (random.nextInt(10) == 0) null else random.nextLong(50_000, 120 * mb)
                val done = if (status == TransferStatus.DONE) bytes else random.nextLong(0, bytes + 1)
                data.transfers.create(NewTransfer(transferId(n), peers[n % 5], TransferDirection.entries.random(random), bytes, 1), start)
                if (random.nextInt(50) != 0) {
                    data.transfers.finish(
                        transferId(n),
                        TransferOutcome(status, done, avgSpeedBytesPerSecond = speed),
                        start + random.nextLong(1, 600_000),
                    )
                }
            }

            // Recompute every figure from what History shows, page by page.
            val history = ArrayList<TransferRecord>()
            var page = data.transfers.historyPage(null, 500)
            while (true) {
                history += page.transfers
                page = data.transfers.historyPage(page.next ?: break, 500)
            }
            assertEquals(2_000, history.size)
            val counted = history.filter { it.status == TransferStatus.DONE }

            fun active(r: TransferRecord): Long =
                r.avgSpeedBytesPerSecond?.takeIf { it > 0 }?.let { r.bytesDone * 1000 / it }
                    ?: (r.finishedAtMillis!! - r.startedAtMillis).coerceAtLeast(0)
            val totalBytes = counted.sumOf { it.bytesDone }
            val activeMillis = counted.sumOf(::active)
            val saved = counted.sumOf { maxOf(0, it.bytesDone * 1000 / TransferStats.BLUETOOTH_BYTES_PER_SECOND - active(it)) }

            val stats = data.stats.stats(now)
            assertEquals(counted.size.toLong(), stats.transferCount)
            assertEquals(totalBytes, stats.totalBytes)
            assertEquals(activeMillis, stats.activeMillis)
            assertEquals(saved, stats.timeSavedMillis)
            for (week in stats.weeks) {
                assertEquals(
                    counted.count {
                        it.startedAtMillis >= week.startMillis && it.startedAtMillis < week.endMillis
                    },
                    week.transfers,
                )
            }
            val today = calendar.epochDayOf(now)
            val weekStart = today - (Weekday.of(today).isoNumber - 1)
            assertEquals(
                counted.count {
                    calendar.epochDayOf(it.startedAtMillis) in weekStart until weekStart + 7
                },
                stats.transfersThisWeek,
            )
            assertTrue(stats.weeks.sumOf { it.transfers } > 0)
        }

    @Test
    fun observeRecomputesWhenATransferChanges() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            val peer = data.peer(1)
            val seen = collectInto(data.stats.observe())
            runCurrent()
            data.transfers.create(NewTransfer(transferId(1), peer, TransferDirection.SEND, mb, 1), T0)
            runCurrent()
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.DONE, mb, avgSpeedBytesPerSecond = mb), T0 + 1_000)
            runCurrent()
            assertEquals(listOf(0L, 1L), seen.map { it.transferCount })
            assertEquals(mb, seen.last().totalBytes)
        }
}
