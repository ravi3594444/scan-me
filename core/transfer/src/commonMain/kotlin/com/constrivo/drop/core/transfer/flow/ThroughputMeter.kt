package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

/**
 * The live speed of F-F1 and architecture §7.4: bytes acknowledged (sender) or verified (receiver) are [add]ed as they
 * happen; every [SAMPLE_MILLIS] the engine closes a sample with [sample], which turns the bytes of that interval into a
 * rate and folds it into an exponentially weighted moving average with [ALPHA] = 0.3 for display. The ETA is the
 * remaining bytes divided by that average.
 *
 * [lastSecondBytesPerSecond] (the sum of the last four samples) is the "1-second measured throughput" the stream count
 * rule of §7.4 compares with 40 MB/s. Rates are in bytes per second; the UI shows decimal MB/s (S6).
 *
 * Thread-safe.
 */
class ThroughputMeter(
    private val alpha: Double = ALPHA,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
    }

    private val lock = TransferLock()
    private var pending = 0L
    private var total = 0L
    private var average: Double? = null
    private val window = LongArray(WINDOW_SAMPLES)
    private val windowMillis = LongArray(WINDOW_SAMPLES)
    private var windowIndex = 0

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
            val bytes = pending
            pending = 0
            val rate = bytes * 1000.0 / intervalMillis
            average = average?.let { alpha * rate + (1 - alpha) * it } ?: rate
            window[windowIndex] = bytes
            windowMillis[windowIndex] = intervalMillis
            windowIndex = (windowIndex + 1) % WINDOW_SAMPLES
            bytes
        }
    }

    /** The smoothed rate for display, or 0 before the first sample. */
    val bytesPerSecond: Double get() = lock.withLock { average ?: 0.0 }

    /** Bytes per second over the last four samples (about one second). */
    val lastSecondBytesPerSecond: Long
        get() =
            lock.withLock {
                val millis = windowMillis.sum()
                if (millis == 0L) 0L else window.sum() * 1000 / millis
            }

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
            window.fill(0)
            windowMillis.fill(0)
            windowIndex = 0
        }
    }

    companion object {
        const val SAMPLE_MILLIS: Long = 250
        const val ALPHA: Double = 0.3
        private const val WINDOW_SAMPLES = 4
    }
}
