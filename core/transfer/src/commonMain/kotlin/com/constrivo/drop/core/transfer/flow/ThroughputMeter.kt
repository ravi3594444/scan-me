package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

/**
 * The live speed of F-F1 and architecture §7.4. Bytes are [add]ed as they move; every [SAMPLE_MILLIS] the engine closes
 * a sample with [sample]. The display rate [bytesPerSecond] is an exponentially weighted moving average with [ALPHA] =
 * 0.3 of the rate over the last [displayWindowSamples] samples (3 s by default): a raw 250 ms sample holds 0, 1 or 2
 * whole units when bytes arrive in 4 MiB steps, and an EWMA of those samples swings by half the rate at 10–30 MB/s,
 * while the windowed rate stays within a few percent of bytes/time over any 5 s window. The engine feeds the display
 * meter at socket granularity (bytes written by the sender, bytes read by the receiver), so even slow links read true.
 * The ETA is the remaining bytes divided by the display rate.
 *
 * [lastSecondBytesPerSecond] (the rate over the last four samples) is the "1-second measured throughput" the stream
 * count rule of §7.4 compares with 40 MB/s. Rates are in bytes per second; the UI shows decimal MB/s (S6).
 *
 * Thread-safe.
 */
class ThroughputMeter(
    private val alpha: Double = ALPHA,
    private val displayWindowSamples: Int = DISPLAY_WINDOW_SAMPLES,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
        require(displayWindowSamples >= 1) { "the display window holds at least one sample" }
    }

    private val lock = TransferLock()
    private var pending = 0L
    private var total = 0L
    private var average: Double? = null
    private val ring = maxOf(displayWindowSamples, RATE_WINDOW_SAMPLES)
    private val bytes = LongArray(ring)
    private val millis = LongArray(ring)
    private var next = 0
    private var filled = 0

    /** Counts [bytes] moved just now. */
    fun add(bytes: Long) {
        require(bytes >= 0) { "bytes must be non-negative" }
        lock.withLock {
            pending += bytes
            total += bytes
        }
    }

    /** Bytes counted so far. */
    val totalBytes: Long get() = lock.withLock { total }

    /**
     * Closes the current sample, which lasted [intervalMillis] (normally [SAMPLE_MILLIS]; the engine passes the real
     * elapsed time so a late tick does not inflate the rate). Returns the bytes of the interval.
     */
    fun sample(intervalMillis: Long = SAMPLE_MILLIS): Long {
        require(intervalMillis > 0) { "interval must be positive" }
        return lock.withLock {
            val sampled = pending
            pending = 0
            bytes[next] = sampled
            millis[next] = intervalMillis
            next = (next + 1) % ring
            if (filled < ring) filled++
            val rate = rateOver(displayWindowSamples)
            average = average?.let { alpha * rate + (1 - alpha) * it } ?: rate
            sampled
        }
    }

    /** Bytes per second over the last [count] samples (fewer while the meter is young); the caller holds the lock. */
    private fun rateOver(count: Int): Double {
        val n = minOf(count, filled)
        if (n == 0) return 0.0
        var sumBytes = 0L
        var sumMillis = 0L
        for (i in 1..n) {
            val at = (next - i + ring) % ring
            sumBytes += bytes[at]
            sumMillis += millis[at]
        }
        return if (sumMillis == 0L) 0.0 else sumBytes * 1000.0 / sumMillis
    }

    /** The smoothed rate for display, or 0 before the first sample. */
    val bytesPerSecond: Double get() = lock.withLock { average ?: 0.0 }

    /** Bytes per second over the last four samples (about one second). */
    val lastSecondBytesPerSecond: Long get() = lock.withLock { rateOver(RATE_WINDOW_SAMPLES).toLong() }

    /** Milliseconds until [remainingBytes] are done at the smoothed rate, or null while the rate is unknown or zero. */
    fun etaMillis(remainingBytes: Long): Long? {
        val rate = bytesPerSecond
        if (remainingBytes <= 0) return 0
        if (rate < 1.0) return null
        return (remainingBytes * 1000.0 / rate).toLong()
    }

    /** Forgets the average and the window (a new link generation after a reconnect); keeps [totalBytes]. */
    fun resetRate() {
        lock.withLock {
            pending = 0
            average = null
            bytes.fill(0)
            millis.fill(0)
            next = 0
            filled = 0
        }
    }

    companion object {
        const val SAMPLE_MILLIS: Long = 250
        const val ALPHA: Double = 0.3

        /** The display rate's window: 12 samples of 250 ms. */
        const val DISPLAY_WINDOW_SAMPLES: Int = 12
        private const val RATE_WINDOW_SAMPLES = 4
    }
}
