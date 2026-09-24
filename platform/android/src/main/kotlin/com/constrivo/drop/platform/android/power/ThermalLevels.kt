package com.constrivo.drop.platform.android.power

import android.os.PowerManager
import com.constrivo.drop.core.transfer.ThermalLevel

/**
 * `PowerManager` thermal status to the engine's [ThermalLevel] (F-F6, architecture §9 "Stay awake and cool", §10.1
 * "Thermal"). The two scales are the same seven steps, so the mapping is one to one; the engine lowers the stream count
 * to 2 and sends the `thermal` hint from [ThermalLevel.SEVERE] up (§7.4, §7.8).
 *
 * | `THERMAL_STATUS_*` | [ThermalLevel] |
 * | --- | --- |
 * | `NONE` (0) | `NONE` |
 * | `LIGHT` (1) | `LIGHT` |
 * | `MODERATE` (2) | `MODERATE` |
 * | `SEVERE` (3) | `SEVERE` |
 * | `CRITICAL` (4) | `CRITICAL` |
 * | `EMERGENCY` (5) | `EMERGENCY` |
 * | `SHUTDOWN` (6) | `SHUTDOWN` |
 *
 * A status the platform may add later above `SHUTDOWN` counts as `SHUTDOWN` (hotter is never read as cool); a negative
 * value, which no release reports, counts as `NONE`.
 */
object ThermalLevels {
    fun fromStatus(status: Int): ThermalLevel =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
            else -> if (status > PowerManager.THERMAL_STATUS_SHUTDOWN) ThermalLevel.SHUTDOWN else ThermalLevel.NONE
        }

    /** Whether the engine throttles at [level] (streams 2 and the `thermal` hint, §7.4). */
    fun throttles(level: ThermalLevel): Boolean = level >= ThermalLevel.SEVERE
}
