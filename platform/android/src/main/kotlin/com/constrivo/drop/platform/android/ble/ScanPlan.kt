package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.RadioMode

/**
 * The scan settings for one [RadioMode] (architecture §5.1: low latency while the radar is visible, low power with
 * filters in the background), as plain values that [AndroidBeaconRadio] turns into `ScanSettings`.
 *
 * @property scanMode `ScanSettings.SCAN_MODE_*`.
 * @property legacyOnly `setLegacy(true)` reports legacy advertisements only; with extended scanning support it is false,
 *   so the extended set's full nickname is heard too, and [allPhys] scans every supported PHY.
 * @property matchMode `ScanSettings.MATCH_MODE_*`: aggressive in the foreground (report on the first packet, F-A1's
 *   ≤ 500 ms), sticky in the background (fewer, surer reports).
 */
data class ScanParameters(
    val scanMode: Int,
    val legacyOnly: Boolean,
    val allPhys: Boolean,
    val matchMode: Int,
)

/** Maps radio modes to scan settings and names the scan filters. Pure. */
object ScanPlan {
    /** `ScanSettings.SCAN_MODE_LOW_POWER`. */
    const val SCAN_MODE_LOW_POWER: Int = 0

    /** `ScanSettings.SCAN_MODE_LOW_LATENCY`. */
    const val SCAN_MODE_LOW_LATENCY: Int = 2

    /** `ScanSettings.MATCH_MODE_AGGRESSIVE`. */
    const val MATCH_MODE_AGGRESSIVE: Int = 1

    /** `ScanSettings.MATCH_MODE_STICKY`. */
    const val MATCH_MODE_STICKY: Int = 2

    /** The manufacturer-data filter: our company identifier followed by the `"dr"` marker (S11). */
    val MANUFACTURER_FILTER_DATA: ByteArray =
        byteArrayOf((AdvertisingFormat.MANUFACTURER_MARKER ushr 8).toByte(), AdvertisingFormat.MANUFACTURER_MARKER.toByte())

    /** Mask for [MANUFACTURER_FILTER_DATA]: both marker bytes must match. */
    val MANUFACTURER_FILTER_MASK: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

    fun parameters(
        mode: RadioMode,
        extendedScanning: Boolean,
    ): ScanParameters =
        ScanParameters(
            scanMode = if (mode == RadioMode.FOREGROUND) SCAN_MODE_LOW_LATENCY else SCAN_MODE_LOW_POWER,
            legacyOnly = !extendedScanning,
            allPhys = extendedScanning,
            matchMode = if (mode == RadioMode.FOREGROUND) MATCH_MODE_AGGRESSIVE else MATCH_MODE_STICKY,
        )

    /** The mode a set of collectors needs: foreground if any wants it, background if any scans at all, else none. */
    fun combined(modes: Collection<RadioMode>): RadioMode? =
        when {
            RadioMode.FOREGROUND in modes -> RadioMode.FOREGROUND
            modes.isNotEmpty() -> RadioMode.BACKGROUND
            else -> null
        }
}

/**
 * A small LRU map from scanned LE address to the platform's device object, so the handshake connects to the exact
 * `BluetoothDevice` the scan reported (it carries the random address type, which an address string alone does not).
 * Thread-safe.
 */
class DeviceCache<T : Any>(
    private val capacity: Int,
) {
    init {
        require(capacity >= 1) { "capacity must be positive" }
    }

    private val map =
        object : LinkedHashMap<String, T>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > capacity
        }

    fun put(
        address: String,
        device: T,
    ) {
        synchronized(map) { map[address.uppercase()] = device }
    }

    /** The device last seen at [address] (case-insensitive), or null. */
    fun get(address: String): T? = synchronized(map) { map[address.uppercase()] }

    val size: Int get() = synchronized(map) { map.size }

    fun clear() {
        synchronized(map) { map.clear() }
    }
}
