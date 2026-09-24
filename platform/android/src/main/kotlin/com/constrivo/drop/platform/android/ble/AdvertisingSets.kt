package com.constrivo.drop.platform.android.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One advertising set as the platform knows it: on a device, a `BluetoothLeAdvertiser` set with its
 * `AdvertisingSetCallback` ([AndroidBeaconRadio]); in tests, a fake.
 */
internal interface PlatformAdvertisingSet {
    /**
     * Hands the set to the platform. The platform answers once through [onStarted] with an
     * `AdvertisingSetCallback.ADVERTISE_*` status, and calls [onEnded] if the set later leaves the air by itself (its
     * duration ran out). Returns null when the request went through, or a failure code when the call itself failed.
     */
    fun start(
        onStarted: (status: Int) -> Unit,
        onEnded: () -> Unit,
    ): Int?

    /**
     * Takes the set off the air, or cancels its start if the platform has not answered yet (Android frees a set that
     * is stopped while it registers as soon as it has started). Idempotent; never throws.
     */
    fun stop()
}

/**
 * The advertising sets [AndroidBeaconRadio] has on air, tracked so that none is ever left behind (N4, F-A5): a set is
 * tracked from the moment it is handed to the platform, before the platform answers, until it is stopped. So a start that
 * fails, times out, or whose caller is cancelled while it waits (the discovery controller cancels a start whenever the
 * plan or the beacon state changes) stops its set, and [stopAll] also reaches sets that are still starting. Without that,
 * a set that the stack starts a moment after its caller gave up stays on air, with its ephemeral ID and nickname, after
 * Hidden, after the epoch ends and after [AndroidBeaconRadio.close], and holds a controller slot.
 *
 * Thread-safe; [start] suspends only while it waits for the platform's answer.
 */
internal class AdvertisingSets(
    private val startTimeoutMillis: Long,
) {
    private val lock = Any()
    private val live = LinkedHashSet<PlatformAdvertisingSet>()

    /** Sets on air or starting. */
    val count: Int get() = synchronized(lock) { live.size }

    /** Whether [set] is on air or starting (false once stopped, or given up). */
    fun isLive(set: PlatformAdvertisingSet): Boolean = synchronized(lock) { set in live }

    /**
     * Starts [set] and waits up to the start timeout for the platform's answer. Returns [ADVERTISE_SUCCESS] with the set
     * on air and tracked, or a failure code with the set stopped: the platform's failure status, the call's own failure
     * code, [AdvertisingStatus.CODE_TIMEOUT], or [AdvertisingStatus.CODE_NOT_AVAILABLE] when [stopAll] ran meanwhile. A
     * caller cancelled while waiting also leaves the set stopped (the cancellation is rethrown).
     */
    suspend fun start(
        set: PlatformAdvertisingSet,
        onEnded: () -> Unit = {},
    ): Int {
        val started = CompletableDeferred<Int>()
        // Tracked before the platform hears of it, so stopAll() and a cancellation reach it at every point.
        synchronized(lock) { live += set }
        var keep = false
        try {
            val refused = set.start({ started.complete(it) }, onEnded)
            if (refused != null) return refused
            val status = withTimeoutOrNull(startTimeoutMillis) { started.await() } ?: AdvertisingStatus.CODE_TIMEOUT
            if (status != ADVERTISE_SUCCESS) return status
            keep = isLive(set)
            return if (keep) ADVERTISE_SUCCESS else AdvertisingStatus.CODE_NOT_AVAILABLE
        } finally {
            if (!keep) {
                synchronized(lock) { live -= set }
                // A failed start needs no stop, but a late one would stay on air; a stop is harmless either way.
                set.stop()
            }
        }
    }

    /** Stops every set on air or starting. */
    fun stopAll() {
        val sets =
            synchronized(lock) {
                live.toList().also { live.clear() }
            }
        sets.forEach { it.stop() }
    }

    companion object {
        /** `AdvertisingSetCallback.ADVERTISE_SUCCESS`. */
        const val ADVERTISE_SUCCESS: Int = 0
    }
}
