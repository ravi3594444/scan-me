package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconAdvertisements
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.RadioMode

/** What the adapter can do for advertising, read from `BluetoothAdapter`. */
data class AdvertiserCapabilities(
    /** `isLeExtendedAdvertisingSupported()`. */
    val extendedAdvertising: Boolean,
    /** `isLe2MPhySupported()`: the extended set's secondary channel uses 2M when possible. */
    val le2mPhy: Boolean,
    /** `getLeMaximumAdvertisingDataLength()`: 31 without extended advertising. */
    val maxAdvertisingDataLength: Int,
)

/** Which set a [AdvertisingSetSpec] describes. */
enum class AdvertisingSetKind {
    /** Legacy ADV_IND: connectable, scannable, 31 bytes plus a 31-byte scan response. Always sent. */
    LEGACY,

    /** Extended, connectable and not scannable: body and full nickname in one PDU (architecture §5.1). */
    EXTENDED,
}

/**
 * One advertising set, independent of Android classes: [AndroidBeaconRadio] turns it into `AdvertisingSetParameters`
 * and `AdvertiseData`. Byte arrays are the platform-API fields (service data after the UUID, manufacturer data after the
 * company identifier), as [BeaconAdvertisement] provides them.
 */
class AdvertisingSetSpec(
    val kind: AdvertisingSetKind,
    /** In 0.625 ms units, as `AdvertisingSetParameters.setInterval` takes it. */
    val interval: Int,
    /** `AdvertisingSetParameters.TX_POWER_*` in dBm. */
    val txPowerDbm: Int,
    val connectable: Boolean,
    val scannable: Boolean,
    /** `BluetoothDevice.PHY_LE_*`; legacy sets use 1M on both. */
    val primaryPhy: Int,
    val secondaryPhy: Int,
    /** Put [AdvertisingFormat.SERVICE_UUID_16] in the complete 16-bit UUID list. */
    val includeServiceUuid: Boolean,
    /** Service data under the service UUID, or null. */
    val serviceData: ByteArray?,
    /** Manufacturer data under [AdvertisingFormat.COMPANY_ID] in the advertising data, or null. */
    val manufacturerData: ByteArray?,
    /** Manufacturer data under [AdvertisingFormat.COMPANY_ID] in the scan response (legacy only), or null for none. */
    val scanResponseManufacturerData: ByteArray?,
) {
    /**
     * Bytes on air in the advertising data: the Flags AD that Android adds to connectable advertising (3), the UUID list
     * (4), the service data (4 + payload) and the manufacturer data (4 + payload).
     */
    val advertisingDataLength: Int
        get() =
            (if (connectable) FLAGS_BYTES else 0) +
                (if (includeServiceUuid) UUID_LIST_BYTES else 0) +
                (serviceData?.let { AD_HEADER_BYTES + UUID16_BYTES + it.size } ?: 0) +
                (manufacturerData?.let { AD_HEADER_BYTES + COMPANY_BYTES + it.size } ?: 0)

    /** Bytes on air in the scan response. */
    val scanResponseLength: Int
        get() = scanResponseManufacturerData?.let { AD_HEADER_BYTES + COMPANY_BYTES + it.size } ?: 0

    private companion object {
        const val FLAGS_BYTES = 3
        const val UUID_LIST_BYTES = 4
        const val AD_HEADER_BYTES = 2
        const val UUID16_BYTES = 2
        const val COMPANY_BYTES = 2
    }
}

/**
 * Advertising configuration of [AndroidBeaconRadio].
 *
 * @property scanResponse send the scan-response nickname of the service-data carrier (core/discovery puts it in
 *   manufacturer data, never under the service UUID, so per-UUID dictionaries of BlueZ and CoreBluetooth cannot merge it
 *   with the body). Off sends no scan response; peers then learn the name from the extended set, mDNS or the handshake.
 *   The switch exists for the lab (implementation plan, carried forward from WP1–WP3).
 * @property extendedAdvertising also run the extended set where the adapter supports it.
 */
data class AdvertisingConfig(
    val scanResponse: Boolean = true,
    val extendedAdvertising: Boolean = true,
)

/** Turns a [BeaconAdvertisement] into advertising sets (architecture §5.1 timing, F-A1). Pure. */
object AdvertisingPlan {
    /** `AdvertisingSetParameters.INTERVAL_LOW`: 160 × 0.625 ms = 100 ms (foreground). */
    const val INTERVAL_FOREGROUND: Int = 160

    /** `AdvertisingSetParameters.INTERVAL_HIGH`: 1600 × 0.625 ms = 1 s (background). */
    const val INTERVAL_BACKGROUND: Int = 1600

    /**
     * `AdvertisingSetParameters.TX_POWER_MEDIUM` (−7 dBm, Android's default) in both modes: peers place this device on
     * their radar by RSSI, so a power step at a mode change would move its bubble by a ring (F-A2, design §3.2).
     */
    const val TX_POWER: Int = -7

    /** `BluetoothDevice.PHY_LE_1M`. */
    const val PHY_LE_1M: Int = 1

    /** `BluetoothDevice.PHY_LE_2M`. */
    const val PHY_LE_2M: Int = 2

    /** The legacy payload limit, the same for advertising data and scan response. */
    const val LEGACY_LIMIT: Int = AdvertisingFormat.LEGACY_PAYLOAD_MAX

    fun interval(mode: RadioMode): Int = if (mode == RadioMode.FOREGROUND) INTERVAL_FOREGROUND else INTERVAL_BACKGROUND

    /**
     * The sets for [advertisement] in [mode]: always the legacy set; the extended set as well when [config] allows it,
     * the adapter supports it and the payload fits [AdvertiserCapabilities.maxAdvertisingDataLength].
     */
    fun sets(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
        adapter: AdvertiserCapabilities,
        config: AdvertisingConfig = AdvertisingConfig(),
    ): List<AdvertisingSetSpec> {
        val legacy = legacy(advertisement, mode, config)
        check(legacy.advertisingDataLength <= LEGACY_LIMIT && legacy.scanResponseLength <= LEGACY_LIMIT) {
            "legacy advertisement does not fit: ${legacy.advertisingDataLength} + ${legacy.scanResponseLength} bytes"
        }
        if (!config.extendedAdvertising || !adapter.extendedAdvertising) return listOf(legacy)
        val extended = extended(advertisement, mode, adapter)
        return if (extended.advertisingDataLength <= adapter.maxAdvertisingDataLength) listOf(legacy, extended) else listOf(legacy)
    }

    /** The legacy ADV_IND set: the carrier of [advertisement] and, with the service-data carrier, the scan response. */
    fun legacy(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
        config: AdvertisingConfig = AdvertisingConfig(),
    ): AdvertisingSetSpec {
        val serviceData = advertisement.carrier == BeaconCarrier.SERVICE_DATA
        return AdvertisingSetSpec(
            kind = AdvertisingSetKind.LEGACY,
            interval = interval(mode),
            txPowerDbm = TX_POWER,
            connectable = true,
            scannable = true,
            primaryPhy = PHY_LE_1M,
            secondaryPhy = PHY_LE_1M,
            includeServiceUuid = serviceData,
            serviceData = if (serviceData) advertisement.carrierPayload() else null,
            manufacturerData = if (serviceData) null else advertisement.carrierPayload(),
            scanResponseManufacturerData = if (config.scanResponse) advertisement.scanResponseManufacturerData() else null,
        )
    }

    /**
     * The extended set: service data with the body (the scanner filter matches its UUID list) and, outside
     * Trusted-only mode, the whole nickname as a manufacturer-data record (up to [Nicknames.MAX_BYTES], where the legacy
     * scan response holds 24).
     */
    fun extended(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
        adapter: AdvertiserCapabilities,
    ): AdvertisingSetSpec =
        AdvertisingSetSpec(
            kind = AdvertisingSetKind.EXTENDED,
            interval = interval(mode),
            txPowerDbm = TX_POWER,
            connectable = true,
            scannable = false,
            primaryPhy = PHY_LE_1M,
            secondaryPhy = if (adapter.le2mPhy) PHY_LE_2M else PHY_LE_1M,
            includeServiceUuid = true,
            serviceData = BeaconAdvertisements.carrierPayload(advertisement.body, BeaconCarrier.SERVICE_DATA),
            manufacturerData = advertisement.nickname?.let(::fullNicknameRecord),
            scanResponseManufacturerData = null,
        )

    /**
     * Marker ‖ `0x81` (complete) or `0x82` (shortened) ‖ UTF-8 nickname cut at [Nicknames.MAX_BYTES], for manufacturer
     * data under [AdvertisingFormat.COMPANY_ID]; null when nothing printable is left.
     */
    fun fullNicknameRecord(nickname: String): ByteArray? {
        val clean = Nicknames.normalize(nickname, Nicknames.MAX_BYTES) ?: return null
        val type = if (clean.truncated) AdvertisingFormat.RECORD_NICKNAME_SHORTENED else AdvertisingFormat.RECORD_NICKNAME_COMPLETE
        val marker = AdvertisingFormat.MANUFACTURER_MARKER
        return byteArrayOf((marker ushr 8).toByte(), marker.toByte(), type.toByte()) + clean.text.encodeToByteArray()
    }
}
