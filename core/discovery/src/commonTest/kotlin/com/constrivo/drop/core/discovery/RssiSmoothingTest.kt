package com.constrivo.drop.core.discovery

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RSSI smoothing and ring hysteresis, design §3.2 (F‑A2). */
class RssiSmoothingTest {
    private val smoothing = RssiSmoothing()
    private val rings = RingThresholds()

    @Test
    fun fA2_ewmaConvergesAtAlphaPoint2Per250msSample() {
        // Window 0 holds −80 only; from 250 ms on every reading is −50.
        var s = smoothing.start(-80, 0).add(-80, 100).add(-80, 200)
        var t = 250L
        while (t < 5_000) {
            s = s.add(-50, t)
            t += 100
        }
        s = s.advanceTo(5_000)
        // Windows 1..19 closed with mean −50 after window 0 (−80): value = −50 − 30 × 0.8^19.
        val expected = -50.0 - 30.0 * 0.8.pow(19)
        assertEquals(20, s.samples)
        assertTrue(abs(s.valueDbm - expected) < 1e-9, "${s.valueDbm} vs $expected")
    }

    @Test
    fun fA2_settlesWithinAboutTwoSeconds() {
        var s = smoothing.start(-80, 0)
        s = s.add(-50, 250)
        for (k in 1..8) s = s.add(-50, 250L * k + 1)
        s = s.advanceTo(250L * 9)
        // After 2 s of a 30 dB step the remaining error is 30 × 0.8^8 ≈ 5 dB, after 4 s under 1 dB.
        assertTrue(abs(s.valueDbm + 50) < 30 * 0.8.pow(8) + 1e-9)
        for (k in 9..16) s = s.add(-50, 250L * k + 1)
        assertTrue(abs(s.advanceTo(250L * 17).valueDbm + 50) < 1.0)
    }

    @Test
    fun firstWindowShowsTheRunningMean() {
        val s = smoothing.start(-60, 1_000).add(-70, 1_100)
        assertEquals(-65.0, s.valueDbm)
        assertEquals(0, s.samples)
        assertEquals(1_250L, s.openWindowEndMillis)
        val closed = s.advanceTo(1_249)
        assertEquals(0, closed.samples)
        val done = s.advanceTo(1_250)
        assertEquals(1, done.samples)
        assertEquals(-65.0, done.valueDbm)
        assertNull(done.openWindowEndMillis)
        assertTrue(done.advanceTo(9_999) === done, "nothing pending")
    }

    @Test
    fun valueOnlyMovesWhenAWindowCloses() {
        var s = smoothing.start(-60, 0).advanceTo(250)
        s = s.add(-40, 300).add(-40, 400)
        assertEquals(-60.0, s.valueDbm)
        s = s.add(-40, 500)
        assertEquals(-60.0 + 0.2 * 20, s.valueDbm)
    }

    @Test
    fun gapsAreSkippedNotDecayed() {
        var s = smoothing.start(-60, 0)
        s = s.add(-80, 60_000)
        assertEquals(-60.0, s.valueDbm)
        assertEquals(1, s.samples)
        s = s.advanceTo(60_250)
        assertEquals(-64.0, s.valueDbm)
    }

    @Test
    fun outOfOrderReadingsCountInTheOpenWindow() {
        var s = smoothing.start(-60, 1_000)
        s = s.add(-70, 400)
        assertEquals(-65.0, s.valueDbm)
        assertEquals(1_250L, s.openWindowEndMillis)
    }

    @Test
    fun ringTable() {
        assertEquals(Ring.INNER, rings.classify(-40.0))
        assertEquals(Ring.INNER, rings.classify(-55.0))
        assertEquals(Ring.MIDDLE, rings.classify(-55.5))
        assertEquals(Ring.MIDDLE, rings.classify(-56.0))
        assertEquals(Ring.MIDDLE, rings.classify(-70.0))
        assertEquals(Ring.OUTER, rings.classify(-70.1))
        assertEquals(Ring.OUTER, rings.classify(-100.0))
    }

    @Test
    fun fA2_hysteresisOfFiveDb() {
        // Falling back needs 5 dB beyond the boundary.
        assertEquals(Ring.INNER, rings.classify(-60.0, Ring.INNER))
        assertEquals(Ring.MIDDLE, rings.classify(-60.1, Ring.INNER))
        assertEquals(Ring.MIDDLE, rings.classify(-75.0, Ring.MIDDLE))
        assertEquals(Ring.OUTER, rings.classify(-75.1, Ring.MIDDLE))
        assertEquals(Ring.MIDDLE, rings.classify(-72.0, Ring.INNER), "a big drop still lands in the band's ring")
        assertEquals(Ring.OUTER, rings.classify(-80.0, Ring.INNER))
        // Moving closer uses the table thresholds.
        assertEquals(Ring.INNER, rings.classify(-55.0, Ring.MIDDLE))
        assertEquals(Ring.MIDDLE, rings.classify(-55.1, Ring.MIDDLE))
        assertEquals(Ring.MIDDLE, rings.classify(-70.0, Ring.OUTER))
        assertEquals(Ring.INNER, rings.classify(-50.0, Ring.OUTER))
    }

    @Test
    fun fA2_noRingFlappingWithinTwoSecondsForNoiseWithin4Db() {
        var devices = 0
        for (seed in 1..5) {
            val random = Random(seed)
            var mean = -80.0
            while (mean <= -45.0) {
                for (intervalMillis in listOf(100L, 1_000L)) {
                    val changes = ringChanges(random, mean, intervalMillis, durationMillis = 60_000)
                    for (i in 1 until changes.size) {
                        assertTrue(
                            changes[i] - changes[i - 1] >= 2_000,
                            "seed $seed mean $mean interval $intervalMillis: ring changed at ${changes[i - 1]} and ${changes[i]} ms",
                        )
                    }
                    if (intervalMillis == 100L) {
                        // Foreground radar: at most the one early correction of a noisy first placement.
                        assertTrue(changes.size <= 1, "seed $seed mean $mean: ${changes.size} ring changes in 60 s")
                    }
                    devices++
                }
                mean += 0.5
            }
        }
        assertEquals(5 * 71 * 2, devices)
    }

    /** Times of ring changes for a device at [mean] dBm with uniform ±4 dB noise, classified at every update. */
    private fun ringChanges(
        random: Random,
        mean: Double,
        intervalMillis: Long,
        durationMillis: Long,
    ): List<Long> {
        fun reading() = (mean + random.nextDouble(-4.0, 4.0)).roundToInt()
        val start = random.nextLong(0, 250)
        var s = smoothing.start(reading(), start)
        var ring = rings.classify(s.valueDbm)
        val changes = ArrayList<Long>()
        var t = start + intervalMillis
        while (t < start + durationMillis) {
            s = s.add(reading(), t)
            val next = rings.classify(s.valueDbm, ring)
            if (next != ring) changes += t
            ring = next
            t += intervalMillis
        }
        return changes
    }
}
