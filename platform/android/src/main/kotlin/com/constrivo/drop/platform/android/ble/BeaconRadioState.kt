package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.RadioMode

/** The advertiser's state (F-A1). Radio errors end up here, never as exceptions. */
sealed interface AdvertisingStatus {
    /** Nothing requested. */
    data object Off : AdvertisingStatus

    /** Requested, but Bluetooth is off or unavailable; it starts when Bluetooth comes on. */
    data object WaitingForBluetooth : AdvertisingStatus

    /**
     * On air. [extended] tells whether the extended set runs next to the legacy one; [validUntilMillis] is the epoch
     * boundary (unix ms) at which the owner rebuilds the advertisement (N4).
     */
    data class Advertising(
        val mode: RadioMode,
        val extended: Boolean,
        val validUntilMillis: Long,
    ) : AdvertisingStatus

    /**
     * The legacy set did not start. [code] is `AdvertisingSetCallback.ADVERTISE_FAILED_*`, or [CODE_PERMISSION],
     * [CODE_NOT_AVAILABLE] or [CODE_TIMEOUT]; a retry is scheduled unless [retrying] is false (a permanent failure such
     * as unsupported hardware or oversized data).
     */
    data class Failed(
        val code: Int,
        val retrying: Boolean,
    ) : AdvertisingStatus

    companion object {
        /** `BLUETOOTH_ADVERTISE` is missing. */
        const val CODE_PERMISSION: Int = -1

        /** No advertiser (Bluetooth off under us, or no LE hardware). */
        const val CODE_NOT_AVAILABLE: Int = -2

        /** The stack did not answer `startAdvertisingSet` in time. */
        const val CODE_TIMEOUT: Int = -3

        /** `AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE`. */
        const val ADVERTISE_FAILED_DATA_TOO_LARGE: Int = 1

        /** `AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED`. */
        const val ADVERTISE_FAILED_FEATURE_UNSUPPORTED: Int = 5

        /** Whether a failure [code] is worth retrying. */
        fun isRetryable(code: Int): Boolean = code != ADVERTISE_FAILED_DATA_TOO_LARGE && code != ADVERTISE_FAILED_FEATURE_UNSUPPORTED
    }
}

/** The scanner's state (F-A2). */
sealed interface ScanStatus {
    data object Off : ScanStatus

    data object WaitingForBluetooth : ScanStatus

    data class Scanning(
        val mode: RadioMode,
    ) : ScanStatus

    /**
     * A wanted (re)start waits for Android's scan throttle or a retry until [untilElapsedMillis]; [running] is the mode
     * still scanning meanwhile, if any.
     */
    data class Throttled(
        val wanted: RadioMode,
        val running: RadioMode?,
        val untilElapsedMillis: Long,
    ) : ScanStatus

    /** The last start failed with [code] (`ScanCallback.SCAN_FAILED_*`, or the scheduler's own codes). */
    data class Failed(
        val code: Int,
        val permanent: Boolean,
    ) : ScanStatus
}

/**
 * Diagnostics counters of the beacon radio (architecture §14 ring-buffer log).
 *
 * @property malformed scan results whose advertising data did not parse (never thrown).
 * @property unsupported beacons of an incompatible future layout.
 * @property dropped results dropped because a collector fell behind (the newest win).
 */
data class BeaconRadioCounters(
    val sightings: Long = 0,
    val malformed: Long = 0,
    val unsupported: Long = 0,
    val dropped: Long = 0,
    val scanStarts: Long = 0,
    val advertisingStarts: Long = 0,
)

/** Everything [AndroidBeaconRadio] reports; observe it for the radar's notices and the diagnostics log. */
data class BeaconRadioState(
    val power: BluetoothPower = BluetoothPower.OFF,
    val advertising: AdvertisingStatus = AdvertisingStatus.Off,
    val scanning: ScanStatus = ScanStatus.Off,
    val counters: BeaconRadioCounters = BeaconRadioCounters(),
)
