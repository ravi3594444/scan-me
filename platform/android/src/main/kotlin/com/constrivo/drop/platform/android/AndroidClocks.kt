package com.constrivo.drop.platform.android

import android.os.SystemClock
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock

/**
 * The clocks discovery runs on, on Android (core/discovery `NearbyDevices`): `SystemClock.elapsedRealtime()` for every
 * duration, because it keeps counting in deep sleep and is the base of `ScanResult.getTimestampNanos()`, and the wall
 * clock only for the rotating-ID epochs.
 */
object AndroidClocks {
    val elapsedRealtime: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

    val wall: WallClock = SystemWallClock
}
