package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.transfer.ThermalLevel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §7.4 and F-F1: the 250 ms samples, the EWMA with alpha 0.3 over a 3 s window, the one-second rate and the ETA. */
class ThroughputMeterTest {
    /**
     * Feeds [meter] with [granule]-byte steps at an even [rate] (bytes per second, first step after [phase] of a step
     * interval) for [seconds], closing a sample every 250 ms. After the first 5 s the displayed rate is compared with the
     * true rate and with bytes/time over the trailing 5 s window; returns the worst relative error of each.
     */
    private fun worstError(
        rate: Double,
        granule: Long,
        phase: Double,
        seconds: Int = 40,
    ): Pair<Double, Double> {
        val meter = ThroughputMeter()
        val every = granule * 1000.0 / rate
        var nextStep = every * (1 - phase)
        val samples = ArrayDeque<Long>()
        var worstTrue = 0.0
        var worstWindow = 0.0
        val count = seconds * 1000 / ThroughputMeter.SAMPLE_MILLIS.toInt()
        for (sample in 1..count) {
            val end = sample * ThroughputMeter.SAMPLE_MILLIS.toDouble()
            var bytes = 0L
            while (nextStep <= end) {
                meter.add(granule)
                bytes += granule
                nextStep += every
            }
            meter.sample()
            samples.addLast(bytes)
            if (samples.size > 20) samples.removeFirst()
            if (end >= 5_000) {
                val shown = meter.bytesPerSecond
                val window = samples.sum() / 5.0
                worstTrue = maxOf(worstTrue, abs(shown - rate) / rate)
                worstWindow = maxOf(worstWindow, abs(shown - window) / window)
            }
        }
        return worstTrue to worstWindow
    }

    private fun assertWithinTenPercent(
        rate: Double,
        granule: Long,
        againstWindow: Boolean = true,
    ) {
        for (p in 0 until 7) {
            val (vsTrue, vsWindow) = worstError(rate, granule, p / 7.0)
            val label = "${rate / 1e6} MB/s in ${granule / 1024} KiB steps (phase $p/7)"
            assertTrue(vsTrue <= 0.10, "$label: ${(vsTrue * 100).toInt()}% off the true rate")
            if (againstWindow) assertTrue(vsWindow <= 0.10, "$label: ${(vsWindow * 100).toInt()}% off bytes/time over 5 s")
        }
    }

    @Test
    fun `F-F1 socket-level counting reads within 10 percent from 1 to 100 MB per s`() {
        // The engine counts Wi-Fi bytes as the sockets move them, in slices of at most 256 KiB.
        for (mbps in listOf(1, 2, 5, 8, 12, 20, 30, 40, 60, 80, 100)) assertWithinTenPercent(mbps * 1e6, 256L * 1024)
        for (mbps in listOf(1, 3, 10)) assertWithinTenPercent(mbps * 1e6, 64L * 1024)
    }

    @Test
    fun `F-F1 whole 4 MiB units read within 10 percent from 10 MB per s`() {
        // A LAN primary moves whole units: the display still holds within 10% of the true rate from 10 MB/s up.
        val chunk = ProtocolConstants.CHUNK_SIZE.toLong()
        for (mbps in listOf(10, 12, 20, 30, 40, 60, 100)) assertWithinTenPercent(mbps * 1e6, chunk, againstWindow = mbps >= 12)
    }

    @Test
    fun `F-F1 Bluetooth blocks read within 10 percent at 20 to 60 KB per s`() {
        // 16 KiB blocks: a 5 s window itself holds only six to eighteen blocks, so compare with the true rate.
        for (kbps in listOf(20, 30, 40, 60)) assertWithinTenPercent(kbps * 1e3, ProtocolConstants.BLUETOOTH_BLOCK_SIZE.toLong(), false)
    }

    @Test
    fun `the display rate is an EWMA with alpha 0_3 of the windowed rate, seeded by the first sample`() {
        val raw = ThroughputMeter(displayWindowSamples = 1)
        assertEquals(0.0, raw.bytesPerSecond)
        raw.add(250_000)
        assertEquals(250_000, raw.sample())
        assertEquals(1_000_000.0, raw.bytesPerSecond, 1e-6)
        raw.add(500_000)
        raw.sample()
        assertEquals(0.3 * 2_000_000 + 0.7 * 1_000_000, raw.bytesPerSecond, 1e-6)
        assertEquals(0, raw.sample(), "an empty sample")
        assertEquals(0.7 * 1_300_000, raw.bytesPerSecond, 1e-6)
        assertEquals(750_000, raw.totalBytes)

        // The default display window spans 12 samples: an empty sample barely moves it.
        val windowed = ThroughputMeter()
        windowed.add(250_000)
        windowed.sample()
        windowed.add(500_000)
        windowed.sample()
        assertEquals(0.3 * 1_500_000 + 0.7 * 1_000_000, windowed.bytesPerSecond, 1e-6)
        windowed.sample()
        assertEquals(0.3 * 1_000_000 + 0.7 * 1_150_000, windowed.bytesPerSecond, 1e-6)
    }

    @Test
    fun `a late tick passes its real interval so the rate is not inflated`() {
        val meter = ThroughputMeter()
        meter.add(1_000_000)
        meter.sample(intervalMillis = 1_000)
        assertEquals(1_000_000.0, meter.bytesPerSecond, 1e-6)
        assertEquals(1_000_000, meter.lastSecondBytesPerSecond)
    }

    @Test
    fun `the ETA divides the remaining bytes by the smoothed rate`() {
        val meter = ThroughputMeter()
        assertNull(meter.etaMillis(1_000), "unknown before the first sample")
        meter.add(2_500_000)
        meter.sample()
        assertEquals(100L, meter.etaMillis(1_000_000))
        assertEquals(0L, meter.etaMillis(0))
        meter.resetRate()
        assertNull(meter.etaMillis(1_000))
        assertEquals(2_500_000, meter.totalBytes, "a reset keeps the total")
    }

    @Test
    fun `bad inputs are refused`() {
        assertFailsWith<IllegalArgumentException> { ThroughputMeter(alpha = 0.0) }
        assertFailsWith<IllegalArgumentException> { ThroughputMeter().add(-1) }
        assertFailsWith<IllegalArgumentException> { ThroughputMeter().sample(0) }
        assertFailsWith<IllegalArgumentException> { ThroughputMeter(displayWindowSamples = 0) }
    }
}

/** F-E8 and F-F6: 4 streams, 8 above 40 MB/s (sticky), 2 while hot, capped by the receiver's `stream_count`. */
class StreamCountPolicyTest {
    @Test
    fun `starts at four and rises to eight above 40 MB per s for good`() {
        val policy = StreamCountPolicy()
        assertEquals(4, policy.target(10_000_000, ThermalLevel.NONE))
        assertEquals(4, policy.target(40_000_000, ThermalLevel.NONE), "40 MB/s is not above the threshold")
        assertFalse(policy.isRaised)
        assertEquals(8, policy.target(40_000_001, ThermalLevel.NONE))
        assertTrue(policy.isRaised)
        assertEquals(8, policy.target(1_000, ThermalLevel.NONE), "the raise is sticky")
    }

    @Test
    fun `thermal SEVERE on either device means two streams`() {
        val policy = StreamCountPolicy()
        assertEquals(4, policy.target(0, ThermalLevel.MODERATE))
        assertEquals(2, policy.target(0, ThermalLevel.SEVERE))
        assertEquals(2, policy.target(0, ThermalLevel.CRITICAL))
        assertEquals(2, policy.target(90_000_000, ThermalLevel.NONE, peerHot = true))
        assertEquals(8, policy.target(0, ThermalLevel.NONE), "cool again: back to the raised count")
    }

    @Test
    fun `the receiver's stream count caps the target`() {
        assertEquals(3, StreamCountPolicy(maxStreams = 3).target(90_000_000, ThermalLevel.NONE))
        assertEquals(1, StreamCountPolicy(maxStreams = 1).target(0, ThermalLevel.SEVERE))
        assertFailsWith<IllegalArgumentException> { StreamCountPolicy(maxStreams = 0) }
        assertFailsWith<IllegalArgumentException> { StreamCountPolicy(maxStreams = 9) }
    }
}
