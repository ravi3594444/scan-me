package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.DiscoveryFormatException
import com.constrivo.drop.core.discovery.UnsupportedBeaconVersionException

/** What one scan result turned into (F-A1, F-A2). */
sealed interface ScanOutcome {
    /** A drop beacon, ready for `NearbyDevices`. */
    class Sighting(
        val sighting: BeaconSighting,
    ) : ScanOutcome

    /** Advertising data without a drop beacon (another product under the shared test company identifier, say). */
    data object NotDrop : ScanOutcome

    /** A beacon body of an incompatible future layout; the peer runs a newer app (§5.1 versioning). */
    data object Unsupported : ScanOutcome

    /** Malformed advertising data: counted and dropped, never thrown. */
    class Malformed(
        val error: DiscoveryFormatException,
    ) : ScanOutcome
}

/**
 * Turns the raw bytes of an Android scan result (`ScanRecord.getBytes()`: advertising data and scan response
 * concatenated, zero-padded for legacy advertising, the whole extended payload otherwise) into a [BeaconSighting] with
 * core/discovery's parser. Pure, so it is tested on the JVM with advertisements built by `BeaconAdvertisements`.
 *
 * Timestamps: Android stamps a result with `getTimestampNanos()` on the elapsed-realtime clock. [BeaconSighting.atMillis]
 * is unix time (informational: `NearbyDevices` stamps sightings with its own clocks, whose monotonic one must be
 * `SystemClock.elapsedRealtime()` on Android), so the result time is converted with one reading of both clocks:
 * `unix = unixNow − (elapsedNow − elapsedAtResult)`. A result stamped in the future (clock skew between the Bluetooth
 * stack and the app) counts as now.
 */
object ScanRecordAdapter {
    fun parse(
        record: ByteArray?,
        rssiDbm: Int,
        address: String?,
        timestampNanos: Long,
        elapsedNowNanos: Long,
        unixNowMillis: Long,
    ): ScanOutcome {
        if (record == null || record.isEmpty()) return ScanOutcome.NotDrop
        val atMillis = unixNowMillis - ((elapsedNowNanos - timestampNanos).coerceAtLeast(0) / NANOS_PER_MILLI)
        return try {
            val sighting =
                BeaconSighting.fromAdvertisingData(record, rssiDbm, address, atMillis.coerceAtLeast(0))
                    ?: return ScanOutcome.NotDrop
            ScanOutcome.Sighting(sighting)
        } catch (e: UnsupportedBeaconVersionException) {
            ScanOutcome.Unsupported
        } catch (e: DiscoveryFormatException) {
            ScanOutcome.Malformed(e)
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
