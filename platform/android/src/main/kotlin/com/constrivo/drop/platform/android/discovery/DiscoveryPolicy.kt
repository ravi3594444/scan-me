package com.constrivo.drop.platform.android.discovery

import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility

/**
 * The hooks that decide what discovery does (F-A1, F-A2, F-A5; spec change N13). The radar is WP8's, the foreground
 * service WP7e's; both only flip these switches.
 *
 * @property radarVisible the radar is on screen: fast advertising and a low-latency scan.
 * @property foregroundService the radio session's foreground service runs (the app is visible to others while in the
 *   background, and parked transfers watch for their peer's beacon, S8): slow advertising and a low-power scan.
 * @property transferActive a transfer is streaming: N13 keeps advertising at the background interval so neither device
 *   vanishes from other radars, and pauses scanning, which competes with the Bluetooth head-start channel for air time.
 * @property visibility the effective visibility (the "Everyone for 10 min" window already applied); `HIDDEN` never
 *   advertises.
 * @property canAdvertise / [canScan]: the runtime permissions (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`).
 */
data class DiscoveryInputs(
    val radarVisible: Boolean = false,
    val foregroundService: Boolean = false,
    val transferActive: Boolean = false,
    val visibility: Visibility = Visibility.TRUSTED_ONLY,
    val canAdvertise: Boolean = false,
    val canScan: Boolean = false,
)

/**
 * What discovery should run: the advertising and scan modes (null = off), and whether the radar model
 * (`NearbyDevices`) runs at all. When it stops, its device list empties, so a radar opened later starts clean.
 */
data class DiscoveryPlan(
    val advertiseMode: RadioMode?,
    val scanMode: RadioMode?,
    val radarRunning: Boolean,
) {
    companion object {
        val IDLE: DiscoveryPlan = DiscoveryPlan(null, null, false)
    }
}

/**
 * The discovery policy as a pure function (architecture §5.1 timing, N13):
 *
 * | Situation | Advertising | Scanning |
 * | --- | --- | --- |
 * | Radar visible | 100 ms (foreground) | low latency |
 * | Only the foreground service | 1 s (background) | low power, filtered |
 * | Transfer streaming (N13) | 1 s | paused |
 * | Neither radar nor service | off | off |
 *
 * Advertising also needs a visibility other than `HIDDEN` and `BLUETOOTH_ADVERTISE`; scanning needs `BLUETOOTH_SCAN`.
 * The radar model runs whenever the radar or the service is up, even while scanning pauses, so LAN sightings and the
 * last Bluetooth ones stay current for the transfer UI.
 */
object DiscoveryPolicy {
    fun plan(
        inputs: DiscoveryInputs,
        pauseScanDuringTransfer: Boolean = true,
    ): DiscoveryPlan {
        val active = inputs.radarVisible || inputs.foregroundService
        if (!active) return DiscoveryPlan.IDLE
        val advertiseMode =
            when {
                inputs.visibility == Visibility.HIDDEN || !inputs.canAdvertise -> null
                inputs.radarVisible && !inputs.transferActive -> RadioMode.FOREGROUND
                else -> RadioMode.BACKGROUND
            }
        val scanMode =
            when {
                !inputs.canScan -> null
                inputs.transferActive && pauseScanDuringTransfer -> null
                inputs.radarVisible -> RadioMode.FOREGROUND
                else -> RadioMode.BACKGROUND
            }
        return DiscoveryPlan(advertiseMode, scanMode, radarRunning = true)
    }
}
