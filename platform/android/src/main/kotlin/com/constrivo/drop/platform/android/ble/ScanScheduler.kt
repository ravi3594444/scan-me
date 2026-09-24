package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.RadioMode

/**
 * Android's scan limits, as the scheduler honours them.
 *
 * @property maxStarts / [windowMillis]: since Android 7 an app that calls `startScan` a sixth time within 30 s gets
 *   no scan at all (AOSP `AppScanStats.isScanningTooFrequently`, logged as "scanning too frequently", with no callback),
 *   so at most five starts fit in any 30 s window. [marginMillis] is added to the window against clock and
 *   binder-latency differences between the app and the Bluetooth process.
 * @property maxScanMillis Android turns a scan that has run for 30 minutes into an opportunistic one, which only receives
 *   results other apps' scans produce; the scheduler restarts it before that.
 * @property stopGraceMillis a scan nobody wants any more is stopped only after this grace, so a collector that switches
 *   mode (cancel, then collect again) costs one restart instead of a stop and a start.
 * @property firstRetryMillis / [maxRetryMillis]: back-off after `onScanFailed`, doubling per consecutive failure.
 */
data class ScanThrottleConfig(
    val maxStarts: Int = 5,
    val windowMillis: Long = 30_000,
    val marginMillis: Long = 1_000,
    val maxScanMillis: Long = 25 * 60_000L,
    val stopGraceMillis: Long = 500,
    val firstRetryMillis: Long = 1_000,
    val maxRetryMillis: Long = 60_000,
) {
    init {
        require(maxStarts >= 1) { "at least one start per window" }
        require(windowMillis > 0 && marginMillis >= 0 && maxScanMillis > 0 && stopGraceMillis >= 0) { "durations must be positive" }
        require(firstRetryMillis > 0 && maxRetryMillis >= firstRetryMillis) { "retry back-off must be positive" }
    }
}

/** What the scan driver should do next. */
sealed interface ScanAction {
    /** Nothing to do until an input changes. */
    data object Idle : ScanAction

    /** Nothing to do before [atMillis] (on the scheduler's monotonic clock) unless an input changes. */
    data class Wait(
        val atMillis: Long,
    ) : ScanAction

    /** Start a scan in [mode]; stop the running one first when [stopFirst]. Report it with [ScanScheduler.started]. */
    data class Start(
        val mode: RadioMode,
        val stopFirst: Boolean,
    ) : ScanAction

    /** Stop the running scan; report it with [ScanScheduler.stopped]. */
    data object Stop : ScanAction
}

/** The scheduler's view, for [BeaconRadioState]. */
data class ScanSchedule(
    val desired: RadioMode?,
    val running: RadioMode?,
    /** Set while a wanted start waits for the throttle window or a retry. */
    val blockedUntilMillis: Long?,
    val lastFailure: Int?,
    val unsupported: Boolean,
)

/**
 * Decides when to start, restart and stop the Bluetooth LE scan (F-A2) within Android's limits ([ScanThrottleConfig]):
 * at most five starts per 30 s, a restart before the 30-minute opportunistic downgrade, a short grace before stopping,
 * and back-off after failures. Mode changes while throttled are conflated: the scan keeps running in its old mode until
 * a start is allowed, then restarts in the latest wanted mode.
 *
 * Pure and single-threaded (the driver calls it under its lock); time is milliseconds on a monotonic clock
 * (`SystemClock.elapsedRealtime()` on a device), passed into every call.
 */
class ScanScheduler(
    private val config: ScanThrottleConfig = ScanThrottleConfig(),
) {
    private val starts = ArrayDeque<Long>()
    private var desired: RadioMode? = null
    private var desiredChangedAt = 0L
    private var running: RadioMode? = null
    private var runningSince = 0L
    private var available = false
    private var retryAt: Long? = null
    private var failures = 0
    private var lastFailure: Int? = null
    private var unsupported = false

    /** The stack said a scan with our callback is already running (`SCAN_FAILED_ALREADY_STARTED`): stop before starting. */
    private var staleScan = false

    /** The mode the collectors want, or null when nobody scans. */
    fun desire(
        mode: RadioMode?,
        nowMillis: Long,
    ) {
        if (mode == desired) return
        desired = mode
        desiredChangedAt = nowMillis
    }

    /** Bluetooth turned on ([available]) or off. Off drops the running scan (the stack stops it) and any back-off. */
    fun radioAvailable(
        available: Boolean,
        nowMillis: Long,
    ) {
        if (available == this.available) return
        this.available = available
        if (!available) running = null
        retryAt = null
        failures = 0
        unsupported = false
        desiredChangedAt = nowMillis
    }

    /** `startScan` was called in [mode] at [nowMillis] (every call counts against the throttle, even a failing one). */
    fun started(
        mode: RadioMode,
        nowMillis: Long,
    ) {
        starts.addLast(nowMillis)
        while (starts.size > config.maxStarts) starts.removeFirst()
        running = mode
        runningSince = nowMillis
        retryAt = null
        staleScan = false
    }

    /** `stopScan` was called. */
    fun stopped() {
        running = null
        staleScan = false
    }

    /**
     * The scan failed: `onScanFailed(errorCode)` arrived, or `startScan` threw ([errorCode] is then one of the
     * [ERROR_PERMISSION] or [ERROR_NOT_AVAILABLE] codes of this class).
     */
    fun failed(
        errorCode: Int,
        nowMillis: Long,
    ) {
        running = null
        lastFailure = errorCode
        failures++
        retryAt =
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> {
                    staleScan = true
                    nowMillis + config.firstRetryMillis
                }

                SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    unsupported = true
                    null
                }

                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
                    nowMillis + config.windowMillis + config.marginMillis
                }

                else -> {
                    val shift = (failures - 1).coerceIn(0, 20)
                    nowMillis + minOf(config.maxRetryMillis, config.firstRetryMillis shl shift)
                }
            }
    }

    /** A scan result arrived: the scan works, so the failure back-off starts over. */
    fun resultReceived() {
        failures = 0
        lastFailure = null
    }

    /** The next action at [nowMillis]. */
    fun next(nowMillis: Long): ScanAction {
        if (!available) return if (running != null) ScanAction.Stop else ScanAction.Idle
        val want = desired
        val current = running
        if (want == null || unsupported) {
            if (current == null) return ScanAction.Idle
            if (unsupported) return ScanAction.Stop
            val stopAt = desiredChangedAt + config.stopGraceMillis
            return if (nowMillis >= stopAt) ScanAction.Stop else ScanAction.Wait(stopAt)
        }
        val restartAt = runningSince + config.maxScanMillis
        if (current == want && nowMillis < restartAt) return ScanAction.Wait(restartAt)
        val allowedAt = maxOf(retryAt ?: nowMillis, throttleAllowedAt())
        if (allowedAt > nowMillis) return ScanAction.Wait(allowedAt)
        return ScanAction.Start(want, stopFirst = current != null || staleScan)
    }

    /** The scheduler's state at [nowMillis], for diagnostics. */
    fun schedule(nowMillis: Long): ScanSchedule {
        val blocked =
            when (val action = next(nowMillis)) {
                is ScanAction.Wait -> action.atMillis.takeIf { desired != null && (running == null || running != desired) }
                else -> null
            }
        return ScanSchedule(desired, running, blocked, lastFailure, unsupported)
    }

    /** When the next start fits the throttle window: the oldest of the last [ScanThrottleConfig.maxStarts] starts must have aged out. */
    private fun throttleAllowedAt(): Long {
        if (starts.size < config.maxStarts) return Long.MIN_VALUE
        return starts.first() + config.windowMillis + config.marginMillis
    }

    companion object {
        /** `ScanCallback.SCAN_FAILED_ALREADY_STARTED`. */
        const val SCAN_FAILED_ALREADY_STARTED: Int = 1

        /** `ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED`: this device cannot scan with these settings, for good. */
        const val SCAN_FAILED_FEATURE_UNSUPPORTED: Int = 4

        /** `ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY`. */
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY: Int = 6

        /** Not an Android code: `startScan` threw a SecurityException (`BLUETOOTH_SCAN` missing). */
        const val ERROR_PERMISSION: Int = -1

        /** Not an Android code: no scanner (Bluetooth turned off under us) or `startScan` threw IllegalStateException. */
        const val ERROR_NOT_AVAILABLE: Int = -2
    }
}
