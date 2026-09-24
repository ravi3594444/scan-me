package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether this device's station is connected: a Wi-Fi network with internet capability, the "previous Wi-Fi" that
 * F-E11 gives back (a local-only network joined for a transfer has no internet capability, so it never counts).
 * [AndroidStationMonitor] is the platform implementation.
 */
interface StationMonitor {
    val connected: StateFlow<Boolean>
}

/**
 * How long a teardown waits for the previous Wi-Fi (F-E11, T-14).
 *
 * @property budgetMillis the 5 s restore budget; a longer restore is reported [RestoreReport.overdue].
 * @property maxWaitMillis how long a teardown waits at most; after that it returns and the system goes on reconnecting
 *   by itself (the report then says not restored).
 */
data class RestorePolicy(
    val budgetMillis: Long = ProtocolConstants.WIFI_RESTORE_BUDGET_MS,
    val maxWaitMillis: Long = 2 * ProtocolConstants.WIFI_RESTORE_BUDGET_MS,
) {
    init {
        require(budgetMillis > 0 && maxWaitMillis >= budgetMillis) { "restore budget must be positive and the wait at least the budget" }
    }
}

/**
 * The outcome of giving the previous Wi-Fi back after a link (F-E11).
 *
 * @property displaced the link had taken the station down: it was connected before the link came up and was not when
 *   the teardown released the link (a joiner without station-and-local-only concurrency, a hotspot host without STA/AP
 *   concurrency).
 * @property restored the station is connected again (always true when nothing was displaced).
 * @property durationMillis from the start of the teardown until the station was back, or until the wait gave up.
 */
data class RestoreReport(
    val kind: LinkKind,
    val mode: LinkMode,
    val displaced: Boolean,
    val restored: Boolean,
    val durationMillis: Long,
    val budgetMillis: Long,
) {
    /** Took longer than the 5 s budget, or never came back within the wait. */
    val overdue: Boolean get() = !restored || durationMillis > budgetMillis
}

/** The F-E11 restore accounting shared by the providers. */
object WifiRestore {
    /**
     * After a link of [kind] / [mode] was released, waits for the station when the link displaced it, and reports.
     * [stationBefore] is whether the station was connected before the link came up; [startedAtMillis] is when the
     * teardown started, on [clock]. Waits at most [RestorePolicy.maxWaitMillis] from [startedAtMillis]; never throws a
     * timeout. Without a [monitor] nothing is known and nothing is waited for.
     */
    suspend fun awaitRestore(
        kind: LinkKind,
        mode: LinkMode,
        stationBefore: Boolean,
        monitor: StationMonitor?,
        clock: MonotonicClock,
        policy: RestorePolicy,
        startedAtMillis: Long,
    ): RestoreReport {
        fun report(
            displaced: Boolean,
            restored: Boolean,
        ) = RestoreReport(kind, mode, displaced, restored, (clock.elapsedMillis() - startedAtMillis).coerceAtLeast(0), policy.budgetMillis)

        if (monitor == null || !stationBefore || monitor.connected.value) return report(displaced = false, restored = true)
        val left = policy.maxWaitMillis - (clock.elapsedMillis() - startedAtMillis)
        val back = left > 0 && withTimeoutOrNull(left) { monitor.connected.first { it } } != null
        return report(displaced = true, restored = back)
    }
}
