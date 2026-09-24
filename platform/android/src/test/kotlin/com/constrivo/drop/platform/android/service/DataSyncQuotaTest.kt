package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.discovery.WallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android 15's `dataSync` limit as the service counts and logs it (S9), and the ring-buffer log it writes to. */
class DataSyncQuotaTest {
    private val hour = 3_600_000L
    private val lines = ArrayList<String>()
    private val quota = DataSyncQuota(log = { lines += it })

    @Test
    fun `runs are counted in a rolling day`() {
        quota.started(0)
        assertTrue(quota.running)
        assertEquals(hour, quota.usedMillis(hour))
        quota.stopped(2 * hour)
        quota.started(10 * hour)
        quota.stopped(11 * hour)
        assertEquals(3 * hour, quota.usedMillis(12 * hour))
        assertEquals(3 * hour, quota.remainingMillis(12 * hour))
        // A day after the first run began, half of it has left the window.
        assertEquals(2 * hour, quota.usedMillis(25 * hour))
        assertEquals(hour, quota.usedMillis(34 * hour))
        assertEquals(0, quota.usedMillis(36 * hour))
        assertEquals(DataSyncQuota.LIMIT_MILLIS, quota.remainingMillis(36 * hour))
    }

    @Test
    fun `every start and stop is logged with the count`() {
        quota.started(0)
        quota.stopped(30 * 60_000)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("dataSync on"), lines[0])
        assertTrue("used 0 of 360 min" in lines[0], lines[0])
        assertTrue(lines[1].startsWith("dataSync off after 1800 s"), lines[1])
        assertTrue("used 30 of 360 min" in lines[1], lines[1])
        // Stopping twice, or starting while running, changes nothing.
        quota.stopped(40 * 60_000)
        quota.started(50 * 60_000)
        quota.started(51 * 60_000)
        assertEquals(3, lines.size)
    }

    @Test
    fun `warnings come once at half and at nine tenths of the limit`() {
        quota.started(0)
        quota.check(2 * hour)
        assertEquals(1, lines.size)
        quota.check(3 * hour)
        quota.check(4 * hour)
        assertEquals(2, lines.size)
        assertTrue("near the Android 15 limit" in lines[1])
        quota.check(5 * hour + 25 * 60_000)
        quota.check(5 * hour + 30 * 60_000)
        assertEquals(3, lines.size)
    }

    @Test
    fun `a timeout or a refused start marks the limit spent until the user returns`() {
        quota.started(0)
        quota.timedOut(6 * hour)
        assertTrue(quota.exhausted)
        assertFalse(quota.running)
        assertEquals(6 * hour, quota.usedMillis(6 * hour))
        assertTrue("time limit reached" in lines.last())

        quota.userReturned(7 * hour)
        assertFalse(quota.exhausted)
        assertEquals(0, quota.usedMillis(7 * hour), "the foreground resets Android's count")
        assertTrue("reset" in lines.last())

        quota.refused(8 * hour, "Time limit already exhausted for foreground service type dataSync")
        assertTrue(quota.exhausted)
        assertTrue("refused: Time limit already exhausted" in lines.last())
    }

    @Test
    fun `the limit must fit its window`() {
        assertFailsWith<IllegalArgumentException> { DataSyncQuota(limitMillis = 0) }
        assertFailsWith<IllegalArgumentException> { DataSyncQuota(limitMillis = 2 * hour, windowMillis = hour) }
    }

    @Test
    fun `the ring buffer keeps the last events, one line each, and says how many it dropped`() {
        var now = 1_758_700_000_000
        val mirrored = ArrayList<ServiceLog.Entry>()
        val log = ServiceLog(WallClock { now++ }, capacity = 3) { mirrored += it }
        log.log("node", "first")
        log.tagged("S9")("second\nwith a line break")
        log.log("service", "third")
        log.log("service", "fourth")
        assertEquals(listOf("second with a line break", "third", "fourth"), log.snapshot().map { it.message })
        assertEquals(1, log.dropped)
        assertEquals(4, mirrored.size)
        val export = log.export("drop 1.0 (42)")
        val exportLines = export.lines().filter { it.isNotEmpty() }
        assertEquals("drop 1.0 (42)", exportLines[0])
        assertEquals("events: 3 (older dropped: 1)", exportLines[1])
        assertEquals("2025-09-24T07:46:40.001Z [S9] second with a line break", exportLines[2])
        assertEquals(5, exportLines.size)
        log.log("x", "y".repeat(2 * ServiceLog.MAX_MESSAGE_CHARS))
        assertEquals(ServiceLog.MAX_MESSAGE_CHARS, log.snapshot().last().message.length)
        log.clear()
        assertEquals(0, log.size)
        assertEquals(0, log.dropped)
        assertFailsWith<IllegalArgumentException> { ServiceLog(WallClock { 0 }, capacity = 0) }
    }
}
