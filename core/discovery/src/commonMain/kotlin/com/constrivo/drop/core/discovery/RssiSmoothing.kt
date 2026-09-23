package com.constrivo.drop.core.discovery

import kotlin.math.pow

/** The three radar rings of design §3.2, nearest first. */
enum class Ring { INNER, MIDDLE, OUTER }

/**
 * Parameters of the RSSI smoother (design §3.2): an exponential moving average over fixed sample windows.
 *
 * Readings are grouped into windows of [sampleIntervalMillis] aligned to multiples of it (on the monotonic clock the
 * caller uses); a window's mean is one sample, folded in when the window closes. Windows without readings are
 * bridged, not skipped: a sample that follows `k` windows since the previous one (`k − 1` of them empty) is folded
 * with weight `α_k = 1 − (1 − α)^k`, `k` capped at [maxBridgedWindows]. That is exactly the average the smoother would
 * reach had the new value been heard in every one of those windows (sample and hold), so the estimate settles in wall
 * time, not in advertisements: with α = 0.2 and 250 ms windows a 30 dB step leaves `30 × 0.8⁸ ≈ 5 dB` after 2 s,
 * whether the peer advertises every 100 ms (foreground) or every second (background, architecture §5.1). A sparse
 * advertiser can only move as often as it is heard, so its bubble settles within about 2 s plus one advertising
 * interval. The cap (2 s by default) keeps one reading after a long silence from counting for more than 2 s of
 * readings.
 */
data class RssiSmoothing(
    val alpha: Double = DEFAULT_ALPHA,
    val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS,
    val maxBridgedWindows: Int = DEFAULT_MAX_BRIDGED_WINDOWS,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
        require(sampleIntervalMillis > 0) { "sample interval must be positive" }
        require(maxBridgedWindows >= 1) { "at least one window must be bridged" }
    }

    /** `weights[k − 1] = 1 − (1 − α)^k` for `k = 1…maxBridgedWindows`. */
    private val weights = DoubleArray(maxBridgedWindows) { 1.0 - (1.0 - alpha).pow(it + 1) }

    /** The state after the first reading. */
    fun start(
        rssiDbm: Int,
        atMillis: Long,
    ): SmoothedRssi {
        val window = windowOf(atMillis)
        return SmoothedRssi(this, null, window, window, rssiDbm.toDouble(), 1, 0)
    }

    internal fun windowOf(atMillis: Long): Long = atMillis.floorDiv(sampleIntervalMillis)

    /** The fold weight of a sample `windows` windows after the previous one. */
    internal fun weight(windows: Long): Double = weights[(windows.coerceIn(1, maxBridgedWindows.toLong()) - 1).toInt()]

    companion object {
        const val DEFAULT_ALPHA: Double = 0.2
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS: Long = 250
        const val DEFAULT_MAX_BRIDGED_WINDOWS: Int = 8
    }
}

/**
 * Immutable smoother state; every operation returns a new state.
 *
 * [valueDbm] changes only when a window closes, except during the very first window, where it is the running mean
 * of the readings so far (so a device shows up on its first beacon). Readings stamped earlier than the open window
 * (out of order) are counted in the open window.
 */
class SmoothedRssi internal constructor(
    val params: RssiSmoothing,
    private val average: Double?,
    /** The window of the last sample folded into [average] (meaningless while it is null). */
    private val sampleWindow: Long,
    private val window: Long,
    private val windowSum: Double,
    private val windowCount: Int,
    /** Number of closed windows folded into the average so far. */
    val samples: Int,
) {
    /** The smoothed RSSI in dBm. */
    val valueDbm: Double get() = average ?: (windowSum / windowCount)

    /** When the open window closes, or null when it holds no readings (nothing pending). */
    val openWindowEndMillis: Long? get() = if (windowCount > 0) (window + 1) * params.sampleIntervalMillis else null

    /** Adds a reading heard at [atMillis]. */
    fun add(
        rssiDbm: Int,
        atMillis: Long,
    ): SmoothedRssi {
        val w = params.windowOf(atMillis)
        val base = if (w > window) closeInto(w) else this
        return SmoothedRssi(
            params,
            base.average,
            base.sampleWindow,
            base.window,
            base.windowSum + rssiDbm,
            base.windowCount + 1,
            base.samples,
        )
    }

    /** Closes the open window if [nowMillis] is past its end. */
    fun advanceTo(nowMillis: Long): SmoothedRssi {
        val w = params.windowOf(nowMillis)
        return if (w > window && windowCount > 0) closeInto(w) else this
    }

    private fun closeInto(newWindow: Long): SmoothedRssi {
        if (windowCount == 0) return SmoothedRssi(params, average, sampleWindow, newWindow, 0.0, 0, samples)
        val mean = windowSum / windowCount
        val next = average?.let { it + params.weight(window - sampleWindow) * (mean - it) } ?: mean
        return SmoothedRssi(params, next, window, newWindow, 0.0, 0, samples + 1)
    }

    override fun toString(): String = "SmoothedRssi(${valueDbm}dBm, samples=$samples)"
}

/**
 * Ring classification with hysteresis (design §3.2, F‑A2).
 *
 * Without history the table applies: `≥ −55` inner, `−56…−70` middle, `< −70` outer ([innerMinDbm],
 * [middleMinDbm]). With a previous ring, moving to a nearer ring uses the same thresholds, but falling back to a
 * farther ring needs the value to drop [hysteresisDb] below the boundary: inner is left below −60, middle below −75.
 * So the bands `[−60, −55)` and `[−75, −70)` keep whatever ring the bubble already has, and a bubble can only flip
 * back after its smoothed value has crossed a whole 5 dB band. Smoothed noise of ±4 dBm raw moves the average far
 * less than that (≈ 0.6 dB standard deviation for a 100 ms advertiser, ≈ 1.5 dB for a 1 s one, whose sparse
 * samples are folded with more weight), so bubbles do not flap (F‑A2: no ring jump within 2 s).
 */
data class RingThresholds(
    val innerMinDbm: Double = -55.0,
    val middleMinDbm: Double = -70.0,
    val hysteresisDb: Double = 5.0,
) {
    init {
        require(innerMinDbm > middleMinDbm) { "the inner threshold must be above the middle threshold" }
        require(hysteresisDb >= 0.0) { "hysteresis must not be negative" }
    }

    /** The ring for [rssiDbm], given the bubble's [previous] ring (null for a new bubble). */
    fun classify(
        rssiDbm: Double,
        previous: Ring? = null,
    ): Ring {
        val table = ringFor(rssiDbm, 0.0)
        if (previous == null || table.ordinal <= previous.ordinal) return table
        return ringFor(rssiDbm, hysteresisDb)
    }

    private fun ringFor(
        rssiDbm: Double,
        slack: Double,
    ): Ring =
        when {
            rssiDbm >= innerMinDbm - slack -> Ring.INNER
            rssiDbm >= middleMinDbm - slack -> Ring.MIDDLE
            else -> Ring.OUTER
        }
}
