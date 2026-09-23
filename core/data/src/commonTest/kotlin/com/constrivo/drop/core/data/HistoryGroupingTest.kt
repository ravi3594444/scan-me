package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals

/** History grouped by local day (design §6), across pages. */
class HistoryGroupingTest {
    private val t0 = 1_790_164_800_000L // 2026-09-23T12:00Z
    private val hour = 3_600_000L

    private fun record(
        n: Int,
        startedAt: Long,
    ) = TransferRecord(
        id = TransferId(ByteArray(16) { n.toByte() }),
        peerDeviceId = "a".repeat(32),
        peerName = "Dev",
        peerPlatform = DevicePlatform.PHONE,
        direction = TransferDirection.SEND,
        status = TransferStatus.DONE,
        transport = null,
        band = null,
        startedAtMillis = startedAt,
        finishedAtMillis = startedAt + 1,
        updatedAtMillis = startedAt + 1,
        bytesTotal = 1,
        bytesDone = 1,
        avgSpeedBytesPerSecond = null,
        hints = emptyList(),
        fileCount = 1,
        mimeHistogram = emptyMap(),
        failedFiles = 0,
    )

    private val newestFirst =
        listOf(
            record(1, t0 + 11 * hour), // 23:00 UTC, 04:30 next day IST
            record(2, t0),
            record(3, t0 - 11 * hour), // 01:00 UTC
            record(4, t0 - 13 * hour), // previous day 23:00 UTC
            record(5, t0 - 50 * hour),
        )

    @Test
    fun groupsFollowTheLocalCalendar() {
        val utc = HistoryGrouping.byDay(newestFirst, LocalCalendar.UTC)
        assertEquals(listOf("2026-09-23", "2026-09-22", "2026-09-21"), utc.map { it.date.toString() })
        assertEquals(listOf(listOf(1, 2, 3), listOf(4), listOf(5)), utc.map { day -> day.transfers.map { it.id.toByteArray()[0].toInt() } })
        assertEquals(utc.map { it.date.epochDay }, utc.map { it.epochDay })

        val ist = HistoryGrouping.byDay(newestFirst, FixedOffsetCalendar(330 * 60_000L))
        assertEquals(listOf("2026-09-24", "2026-09-23", "2026-09-21"), ist.map { it.date.toString() })
        assertEquals(listOf(listOf(1), listOf(2, 3, 4), listOf(5)), ist.map { day -> day.transfers.map { it.id.toByteArray()[0].toInt() } })
        assertEquals(emptyList(), HistoryGrouping.byDay(emptyList(), LocalCalendar.UTC))
    }

    @Test
    fun appendingPagesNeverShowsADayTwice() {
        val whole = HistoryGrouping.byDay(newestFirst, LocalCalendar.UTC)
        for (split in 0..newestFirst.size) {
            val first = HistoryGrouping.byDay(newestFirst.take(split), LocalCalendar.UTC)
            val both = HistoryGrouping.append(first, newestFirst.drop(split), LocalCalendar.UTC)
            assertEquals(whole, both, "split at $split")
        }
        val one = HistoryGrouping.byDay(newestFirst.take(1), LocalCalendar.UTC)
        assertEquals(one, HistoryGrouping.append(one, emptyList(), LocalCalendar.UTC))
    }
}
