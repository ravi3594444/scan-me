package com.constrivo.drop.core.discovery

import kotlinx.coroutines.flow.Flow

/** Foreground: 100 ms advertising, low-latency scan. Background: 1 s advertising, low-power filtered scan (architecture §5.1). */
enum class RadioMode { FOREGROUND, BACKGROUND }

/**
 * Where the beacon body travels in the advertisement. Android and Linux use service data; Windows apps can only
 * publish manufacturer-specific data, so scanners accept both (implementation plan, spec change S11).
 */
enum class BeaconCarrier { SERVICE_DATA, MANUFACTURER_DATA }

/** What the platform should advertise: the encoded beacon body plus the nickname for the scan response. */
class BeaconAdvertisement(
    val body: ByteArray,
    val localName: String,
)

/** One received advertisement. [radioAddress] is the platform's opaque address for connecting back, if any. */
class BeaconSighting(
    val body: ByteArray,
    val carrier: BeaconCarrier,
    val localName: String?,
    val rssiDbm: Int,
    val radioAddress: String?,
    val atMillis: Long,
)

/** Bluetooth LE advertising and scanning, implemented per platform (WP7a, WP10b–d). */
interface BeaconRadio {
    /** Starts or replaces the advertisement. */
    suspend fun startAdvertising(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
    )

    suspend fun stopAdvertising()

    /** Emits every beacon heard while collected; cancelling the collector stops the scan. */
    fun scan(mode: RadioMode): Flow<BeaconSighting>
}
