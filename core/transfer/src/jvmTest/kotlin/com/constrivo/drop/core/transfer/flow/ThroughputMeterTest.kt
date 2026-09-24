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

/** §7.4 and F-F1: the 250 ms samples, the EWMA with alpha 0.3, the one-second window and the ETA. */
class ThroughputMeterTest {
    @Test
    fun `4 MiB acks at 60 MB per s read within 10 percent over a 5 s window`() {
        val meter = ThroughputMeter()
        val rate = 60_000_000.0
        val ack = ProtocolConstants.CHUNK_SIZE.toLong()
        val ackEvery = ack * 1000.0 / rate
        var nextAck = ackEvery
        for (sample in 1..20) {
            val end = sample * ThroughputMeter.SAMPLE_MILLIS
            while (nextAck <= end) {
                meter.add(ack)
                nextAck += ackEvery
            }
            meter.sample()
            // After the first second the display rate stays within 10% of the truth, 4 MiB granularity included.
            if (end >= 1_000) {
                val error = abs(meter.bytesPerSecond - rate) / rate
                assertTrue(error <= 0.10, "at $end ms the meter shows ${meter.bytesPerSecond}, ${(error * 100).toInt()}% off")
            }
        }
        val average = meter.totalBytes * 1000.0 / 5_000
        assertTrue(abs(average - rate) / rate <= 0.10, "5 s average $average")
        assertTrue(abs(meter.lastSecondBytesPerSecond - rate) / rate <= 0.15, "last second ${meter.lastSecondBytesPerSecond}")
    }

    @Test
    fun `the average is an EWMA with alpha 0_3 and the first sample seeds it`() {
        val meter = ThroughputMeter()
        assertEquals(0.0, meter.bytesPerSecond)
        meter.add(250_000)
        assertEquals(250_000, meter.sample())
        assertEquals(1_000_000.0, meter.bytesPerSecond, 1e-6)
        meter.add(500_000)
        meter.sample()
        assertEquals(0.3 * 2_000_000 + 0.7 * 1_000_000, meter.bytesPerSecond, 1e-6)
        assertEquals(0, meter.sample(), "an empty sample")
        assertEquals(0.7 * 1_300_000, meter.bytesPerSecond, 1e-6)
        assertEquals(750_000, meter.totalBytes)
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
