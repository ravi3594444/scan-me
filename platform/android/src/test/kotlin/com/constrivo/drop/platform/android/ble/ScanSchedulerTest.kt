package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.RadioMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-A2 within Android's scan limits: five starts per 30 s, the 30-minute downgrade, grace, back-off. */
class ScanSchedulerTest {
    private val config = ScanThrottleConfig()
    private val window = config.windowMillis + config.marginMillis

    /** A scheduler with the radio on, driven like the Android driver drives it. */
    private class Driver(
        config: ScanThrottleConfig = ScanThrottleConfig(),
    ) {
        val scheduler = ScanScheduler(config).also { it.radioAvailable(true, 0) }
        val starts = ArrayList<Pair<Long, RadioMode>>()
        var stops = 0

        /** Performs every action due at [now] and returns the first non-immediate one. */
        fun step(now: Long): ScanAction {
            repeat(10) {
                when (val action = scheduler.next(now)) {
                    is ScanAction.Start -> {
                        if (action.stopFirst) stops++
                        starts += now to action.mode
                        scheduler.started(action.mode, now)
                    }

                    ScanAction.Stop -> {
                        stops++
                        scheduler.stopped()
                    }

                    else -> {
                        return action
                    }
                }
            }
            error("scheduler did not settle")
        }
    }

    @Test
    fun startsTheWantedModeAndIdlesWithoutCollectors() {
        val d = Driver()
        assertEquals(ScanAction.Idle, d.step(0))
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        val wait = assertIs<ScanAction.Wait>(d.step(0))
        assertEquals(listOf(0L to RadioMode.FOREGROUND), d.starts)
        // Waiting until the 25-minute restart.
        assertEquals(config.maxScanMillis, wait.atMillis)
    }

    @Test
    fun atMostFiveStartsFitIn30Seconds() {
        val d = Driver()
        var mode = RadioMode.FOREGROUND
        // Flip the mode every second: each flip wants a restart. The last flip (9 s, background) differs from the mode
        // that is still running (the fifth start, foreground).
        for (t in 0L..9_000L step 1_000L) {
            d.scheduler.desire(mode, t)
            d.step(t)
            mode = if (mode == RadioMode.FOREGROUND) RadioMode.BACKGROUND else RadioMode.FOREGROUND
        }
        assertEquals(5, d.starts.size)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), d.starts.map { it.first })
        // The sixth start waits until the first one has left the 30 s window (plus the safety margin).
        assertEquals(window, d.scheduler.schedule(10_000).blockedUntilMillis)
        val next = d.step(window)
        assertIs<ScanAction.Wait>(next)
        assertEquals(6, d.starts.size)
        // The conflated restart uses the latest wanted mode.
        assertEquals(RadioMode.BACKGROUND, d.scheduler.schedule(window).desired)
        assertEquals(RadioMode.BACKGROUND, d.starts.last().second)
    }

    @Test
    fun anySlidingWindowHoldsAtMostFiveStarts() {
        val d = Driver()
        var mode = RadioMode.FOREGROUND
        for (t in 0L..300_000L step 700L) {
            mode = if (mode == RadioMode.FOREGROUND) RadioMode.BACKGROUND else RadioMode.FOREGROUND
            d.scheduler.desire(mode, t)
            d.step(t)
        }
        val times = d.starts.map { it.first }
        for (i in times.indices) {
            val inWindow = times.count { it >= times[i] && it < times[i] + config.windowMillis }
            assertTrue(inWindow <= config.maxStarts, "window at ${times[i]} holds $inWindow starts")
        }
        assertTrue(times.size >= 40, "the scheduler keeps making progress (${times.size} starts)")
    }

    @Test
    fun keepsTheOldModeRunningWhileAStartIsThrottled() {
        val d = Driver()
        for (t in 0L until 5L) {
            d.scheduler.desire(if (t % 2 == 0L) RadioMode.FOREGROUND else RadioMode.BACKGROUND, t)
            d.step(t)
        }
        d.scheduler.desire(RadioMode.BACKGROUND, 10)
        val stopsBefore = d.stops
        assertIs<ScanAction.Wait>(d.step(10))
        assertEquals(stopsBefore, d.stops, "no stop while the restart is throttled")
        assertEquals(RadioMode.FOREGROUND, d.scheduler.schedule(10).running)
        assertEquals(RadioMode.BACKGROUND, d.scheduler.schedule(10).desired)
    }

    @Test
    fun restartsBeforeAndroidDowngradesALongScan() {
        val d = Driver()
        d.scheduler.desire(RadioMode.BACKGROUND, 0)
        d.step(0)
        assertIs<ScanAction.Wait>(d.step(config.maxScanMillis - 1))
        assertEquals(1, d.starts.size)
        d.step(config.maxScanMillis)
        assertEquals(2, d.starts.size)
        assertEquals(1, d.stops, "the restart stops the old scan first")
        assertTrue(config.maxScanMillis < 30 * 60_000L)
    }

    @Test
    fun stopsAfterAGraceSoAModeSwitchCostsOneRestart() {
        val d = Driver()
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        d.step(0)
        // The collector is cancelled and a new one subscribes 100 ms later with the same mode: nothing happens.
        d.scheduler.desire(null, 1_000)
        assertEquals(ScanAction.Wait(1_000 + config.stopGraceMillis), d.step(1_000))
        d.scheduler.desire(RadioMode.FOREGROUND, 1_100)
        d.step(1_100)
        assertEquals(1, d.starts.size)
        assertEquals(0, d.stops)
        // Really gone: stopped once the grace has passed.
        d.scheduler.desire(null, 2_000)
        d.step(2_000 + config.stopGraceMillis)
        assertEquals(1, d.stops)
        assertEquals(ScanAction.Idle, d.step(3_000))
    }

    @Test
    fun failuresBackOffAndAResultResetsTheBackOff() {
        val d = Driver()
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        d.step(0)
        d.scheduler.failed(3, 100) // SCAN_FAILED_INTERNAL_ERROR
        assertEquals(ScanAction.Wait(100 + config.firstRetryMillis), d.scheduler.next(100))
        assertEquals(3, d.scheduler.schedule(100).lastFailure)
        d.step(100 + config.firstRetryMillis)
        d.scheduler.failed(3, 1_200)
        assertEquals(ScanAction.Wait(1_200 + 2 * config.firstRetryMillis), d.scheduler.next(1_200))
        d.scheduler.resultReceived()
        assertNull(d.scheduler.schedule(1_200).lastFailure)
        // The back-off is capped.
        repeat(20) { d.scheduler.failed(3, 50_000) }
        val wait = assertIs<ScanAction.Wait>(d.scheduler.next(50_000))
        assertTrue(wait.atMillis - 50_000 <= config.maxRetryMillis)
    }

    @Test
    fun scanningTooFrequentlyWaitsAWholeWindow() {
        val d = Driver()
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        d.step(0)
        d.scheduler.failed(ScanScheduler.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, 500)
        assertEquals(ScanAction.Wait(500 + window), d.scheduler.next(500))
    }

    @Test
    fun alreadyStartedMakesTheNextStartStopFirst() {
        val d = Driver()
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        d.step(0)
        d.scheduler.failed(ScanScheduler.SCAN_FAILED_ALREADY_STARTED, 10)
        val start = d.scheduler.next(10 + config.firstRetryMillis)
        assertEquals(ScanAction.Start(RadioMode.FOREGROUND, stopFirst = true), start)
    }

    @Test
    fun anUnsupportedFeatureStopsTryingUntilTheRadioComesBack() {
        val d = Driver()
        d.scheduler.desire(RadioMode.FOREGROUND, 0)
        d.step(0)
        d.scheduler.failed(ScanScheduler.SCAN_FAILED_FEATURE_UNSUPPORTED, 10)
        assertEquals(ScanAction.Idle, d.scheduler.next(1_000_000))
        assertTrue(d.scheduler.schedule(1_000_000).unsupported)
        d.scheduler.radioAvailable(false, 1_000_001)
        d.scheduler.radioAvailable(true, 1_000_002)
        assertIs<ScanAction.Start>(d.scheduler.next(1_000_002))
    }

    @Test
    fun bluetoothOffDropsTheScanAndOnRestartsIt() {
        val d = Driver()
        d.scheduler.desire(RadioMode.BACKGROUND, 0)
        d.step(0)
        d.scheduler.radioAvailable(false, 5_000)
        assertEquals(ScanAction.Idle, d.scheduler.next(5_000))
        assertNull(d.scheduler.schedule(5_000).running)
        d.scheduler.radioAvailable(true, 9_000)
        assertEquals(ScanAction.Start(RadioMode.BACKGROUND, stopFirst = false), d.scheduler.next(9_000))
    }

    @Test
    fun rejectsNonsenseConfigurations() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { ScanThrottleConfig(maxStarts = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ScanThrottleConfig(windowMillis = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ScanThrottleConfig(firstRetryMillis = 10, maxRetryMillis = 5) }
    }
}
