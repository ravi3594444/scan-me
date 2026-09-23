package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlinx.coroutines.flow.Flow

/** Foreground: 100 ms advertising, low-latency scan. Background: 1 s advertising, low-power filtered scan (architecture §5.1). */
enum class RadioMode { FOREGROUND, BACKGROUND }

/**
 * Where the beacon body travels in the advertisement. Android and Linux use service data; Windows apps can only
 * publish manufacturer-specific data, so scanners accept both (implementation plan, spec change S11).
 */
enum class BeaconCarrier { SERVICE_DATA, MANUFACTURER_DATA }

/**
 * What this device wants to advertise, before visibility rules are applied (architecture §5.1, F‑A4, F‑A5).
 *
 * @property networkHint from [NetworkHint.derive]; it decides [Capabilities.Flag.CONNECTED_TO_WIFI] (§5.2 bit 11).
 * @property classicAddress only desktops that can read their own BR/EDR address set it (S10).
 */
data class LocalBeaconState(
    val visibility: Visibility,
    val platform: DevicePlatform,
    val capabilities: Capabilities,
    val networkHint: NetworkHint,
    val nickname: String,
    val classicAddress: BluetoothAddress? = null,
)

/**
 * What the platform advertises for one epoch: the carrier payload for the advertising data and, with the
 * service-data carrier outside Trusted-only mode, a nickname for the scan response (architecture §5.1, spec changes
 * S10, S11, N4).
 *
 * Platform APIs take structured fields, so each part is offered separately. Advertising data: [carrierPayload] as
 * service data under [serviceUuid16] ([BeaconCarrier.SERVICE_DATA], plus [serviceUuid16] in the service UUID list)
 * or as manufacturer data under [companyId] ([BeaconCarrier.MANUFACTURER_DATA]). Scan response:
 * [scanResponseManufacturerData] as manufacturer data under [companyId], never as service data, because scanners
 * that key service data by UUID would let it replace the beacon body. [advertisingData] and [scanResponseData] are
 * the complete on-air bytes, used for size accounting and by stacks that take raw bytes.
 *
 * N4: the advertisement is valid until [validUntilMillis], the next epoch boundary; the platform then builds a new
 * one and restarts its advertising set, so the OS-level address rotates together with the ephemeral ID.
 */
class BeaconAdvertisement(
    val carrier: BeaconCarrier,
    val body: BeaconBody,
    /**
     * This device's sanitised nickname; null in Trusted-only mode (N4). Only the service-data carrier sends it in a
     * scan response; with the manufacturer-data carrier it travels over mDNS only.
     */
    val nickname: String?,
    val validUntilMillis: Long,
) {
    init {
        if (body.visibility == Visibility.TRUSTED_ONLY) {
            require(nickname == null && body.networkHint.isNone && body.classicAddress == null) {
                "a Trusted-only advertisement carries no nickname, network hint or Classic address (N4)"
            }
        }
    }

    val serviceUuid16: Int get() = AdvertisingFormat.SERVICE_UUID_16
    val companyId: Int get() = AdvertisingFormat.COMPANY_ID

    /** Service data after the UUID, or manufacturer data after the company identifier. */
    fun carrierPayload(): ByteArray = BeaconAdvertisements.carrierPayload(body, carrier)

    /** Whether the platform sends a scan response: only the service-data carrier with a nickname does. */
    val hasScanResponse: Boolean get() = carrier == BeaconCarrier.SERVICE_DATA && nickname != null

    /**
     * The scan-response manufacturer data after [companyId] (marker ‖ nickname record), or null when no scan
     * response is sent ([hasScanResponse]).
     */
    fun scanResponseManufacturerData(): ByteArray? = if (hasScanResponse) BeaconAdvertisements.nicknamePayload(nickname!!) else null

    /** Complete legacy advertising data (≤ 31 bytes). */
    fun advertisingData(): ByteArray = BeaconAdvertisements.advertisingData(body, carrier)

    /** Complete scan response data (≤ 31 bytes), or null when no scan response is sent ([hasScanResponse]). */
    fun scanResponseData(): ByteArray? = if (hasScanResponse) BeaconAdvertisements.scanResponseData(nickname!!) else null

    override fun toString(): String = "BeaconAdvertisement($carrier, $body, nickname=$nickname, validUntil=$validUntilMillis)"

    companion object {
        /**
         * Builds this device's advertisement for the epoch containing [nowMillis].
         *
         * Visibility rules (F‑A5, N4):
         * - [Visibility.HIDDEN] never advertises and [DevicePlatform.UNKNOWN] is never sent: both are programming errors
         *   (`IllegalArgumentException`).
         * - [Visibility.TRUSTED_ONLY]: no nickname (no scan response), network hint zeroed with
         *   [Capabilities.Flag.CONNECTED_TO_WIFI] and [Capabilities.Flag.STATION_ON_5GHZ] cleared, and no Classic
         *   address (a permanent MAC would link every epoch). Only trusted peers can resolve the ephemeral ID.
         * - Otherwise [Capabilities.Flag.CONNECTED_TO_WIFI] follows the hint: set when it is non-zero, cleared (with
         *   [Capabilities.Flag.STATION_ON_5GHZ]) when it is zero.
         *
         * @param advertisingSecret this device's `k_adv` (32 bytes, S3).
         */
        fun create(
            crypto: CryptoProvider,
            advertisingSecret: ByteArray,
            state: LocalBeaconState,
            carrier: BeaconCarrier,
            nowMillis: Long,
        ): BeaconAdvertisement {
            require(state.visibility != Visibility.HIDDEN) { "a HIDDEN device never advertises (F-A5)" }
            require(state.platform.isKnown) { "a device never advertises an unknown platform" }
            val trustedOnly = state.visibility == Visibility.TRUSTED_ONLY
            val hint = if (trustedOnly) NetworkHint.NONE else state.networkHint
            val capabilities =
                if (hint.isNone) {
                    state.capabilities - Capabilities.Flag.CONNECTED_TO_WIFI - Capabilities.Flag.STATION_ON_5GHZ
                } else {
                    state.capabilities + Capabilities.Flag.CONNECTED_TO_WIFI
                }
            val epoch = EphemeralIds.epochAt(nowMillis)
            val body =
                BeaconBody(
                    ephemeralId = EphemeralIds.derive(crypto, advertisingSecret, epoch),
                    capabilities = capabilities,
                    networkHint = hint,
                    visibility = state.visibility,
                    platform = state.platform,
                    classicAddress = if (trustedOnly) null else state.classicAddress,
                )
            val nickname = if (trustedOnly) null else Nicknames.normalize(state.nickname)?.text
            return BeaconAdvertisement(carrier, body, nickname, EphemeralIds.epochStartMillis(epoch + 1))
        }
    }
}

/**
 * One received advertisement carrying a drop beacon.
 *
 * @property body the encoded [BeaconBody] (the drop record after the UUID, or after company identifier and marker).
 * @property localName the scan-response nickname, if one was received.
 * @property radioAddress the platform's opaque address for connecting back (GATT), if any.
 * @property atMillis unix time the platform heard it; informational, because [NearbyDevices] stamps sightings with
 *   its own clock so that platform clock bases (Android reports elapsed-realtime) never mix.
 */
class BeaconSighting(
    val body: ByteArray,
    val carrier: BeaconCarrier,
    val localName: String?,
    val rssiDbm: Int,
    val radioAddress: String?,
    val atMillis: Long,
    val localNameTruncated: Boolean = false,
) {
    companion object {
        /**
         * Builds a sighting from raw advertising bytes (Android `ScanRecord.getBytes()`, WinRT data sections).
         *
         * @return null when [record] holds no drop beacon.
         * @throws DiscoveryFormatException for a malformed record (see [BeaconAdvertisements.parse]).
         */
        fun fromAdvertisingData(
            record: ByteArray,
            rssiDbm: Int,
            radioAddress: String?,
            atMillis: Long,
        ): BeaconSighting? {
            val parsed = BeaconAdvertisements.parse(record) ?: return null
            return BeaconSighting(
                body = parsed.bodyBytes,
                carrier = parsed.carrier,
                localName = parsed.nickname,
                rssiDbm = rssiDbm,
                radioAddress = radioAddress,
                atMillis = atMillis,
                localNameTruncated = parsed.nicknameTruncated,
            )
        }
    }
}

/** Bluetooth LE advertising and scanning, implemented per platform (WP7a, WP10b–d). */
interface BeaconRadio {
    /** Starts or replaces the advertisement. Callers replace it at [BeaconAdvertisement.validUntilMillis] (N4). */
    suspend fun startAdvertising(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
    )

    suspend fun stopAdvertising()

    /** Emits every beacon heard while collected; cancelling the collector stops the scan. */
    fun scan(mode: RadioMode): Flow<BeaconSighting>
}
