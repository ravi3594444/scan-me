package com.constrivo.drop.platform.android.capability

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.NetworkLinkInfo
import com.constrivo.drop.core.ladder.StationBand
import com.constrivo.drop.core.ladder.WifiBand

/**
 * The Wi-Fi network this device's station is connected to, as far as capability detection needs it: the channel
 * frequency (from `WifiInfo.getFrequency()`, which needs no location permission) and the link properties the network
 * hint is derived from (spec change N6).
 */
data class StationFacts(
    val frequencyMhz: Int,
    val link: NetworkLinkInfo,
)

/**
 * Everything Android reports that feeds the capability bits of architecture §5.2 (F-A4), read by
 * [AndroidCapabilityDetector] from `WifiManager`, `PackageManager`, `BluetoothAdapter`, `StorageManager` and the
 * connectivity callbacks. Plain values, so [CapabilityMapping] is a pure function with JVM tests.
 *
 * @property wifi5GhzSupported `WifiManager.is5GHzBandSupported()`.
 * @property wifi6GhzSupported `WifiManager.is6GHzBandSupported()`.
 * @property wifiStandard11ax `isWifiStandardSupported(WIFI_STANDARD_11AX)`.
 * @property wifiStandard11be `isWifiStandardSupported(WIFI_STANDARD_11BE)` (API 33+, false below).
 * @property wifiDirectFeature `FEATURE_WIFI_DIRECT`; [p2pSupported] is `WifiManager.isP2pSupported()`.
 * @property wifiAwareFeature `FEATURE_WIFI_AWARE`.
 * @property bluetoothClassicFeature `FEATURE_BLUETOOTH` (BR/EDR, needed for RFCOMM).
 * @property bluetoothLeFeature `FEATURE_BLUETOOTH_LE`.
 * @property leExtendedAdvertising `BluetoothAdapter.isLeExtendedAdvertisingSupported()`, last read while the adapter
 *   was on (some builds answer false while it is off).
 * @property leCodedPhy `BluetoothAdapter.isLeCodedPhySupported()`, same caveat.
 * @property saveLocationRemovable the volume received files are written to is removable (a microSD card).
 * @property verifiedP2p5GhzHost this device has hosted a Wi-Fi Direct group on 5 GHz at least once (bit 4, persisted
 *   by the Wi-Fi Direct provider of WP7c).
 * @property station the connected Wi-Fi network, or null when not connected.
 * @property staApConcurrency `isStaApConcurrencySupported()`: the local-only hotspot can run while connected.
 * @property dualBandSimultaneous `isDualBandSimultaneousSupported()` (API 34+, false below; N9: a group need not share
 *   the station channel).
 */
data class CapabilityInputs(
    val hasWifi: Boolean = false,
    val wifiEnabled: Boolean = false,
    val wifi5GhzSupported: Boolean = false,
    val wifi6GhzSupported: Boolean = false,
    val wifiStandard11ax: Boolean = false,
    val wifiStandard11be: Boolean = false,
    val wifiDirectFeature: Boolean = false,
    val p2pSupported: Boolean = false,
    val wifiAwareFeature: Boolean = false,
    val bluetoothClassicFeature: Boolean = false,
    val bluetoothLeFeature: Boolean = false,
    val bluetoothAdapterPresent: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val leExtendedAdvertising: Boolean = false,
    val leCodedPhy: Boolean = false,
    val le2mPhy: Boolean = false,
    val saveLocationRemovable: Boolean = false,
    val verifiedP2p5GhzHost: Boolean = false,
    val station: StationFacts? = null,
    val staApConcurrency: Boolean = false,
    val dualBandSimultaneous: Boolean = false,
)

/**
 * What capability detection publishes (F-A4): the 16 bits for the beacon and the mDNS record, the network hint (N6),
 * and the radio facts other WP7 packages need but that are not on the wire.
 *
 * @property stationBand from [stationFrequencyMhz] (N9); [StationBand.NONE] when not connected.
 */
data class LocalRadioFacts(
    val capabilities: Capabilities,
    val networkHint: NetworkHint,
    val stationFrequencyMhz: Int?,
    val stationBand: StationBand,
    val wifiEnabled: Boolean,
    val bluetoothAvailable: Boolean,
    val bluetoothEnabled: Boolean,
    val staApConcurrency: Boolean,
    val dualBandSimultaneous: Boolean,
    val le2mPhy: Boolean,
) {
    companion object {
        /** Before the first detection: nothing claimed. */
        val UNKNOWN: LocalRadioFacts =
            LocalRadioFacts(Capabilities.NONE, NetworkHint.NONE, null, StationBand.NONE, false, false, false, false, false, false)
    }
}

/** The Android-to-capabilities mapping of F-A4 (architecture §5.2), a pure function. */
object CapabilityMapping {
    /**
     * The §5.2 bits for [inputs]:
     *
     * | Bit | Set when |
     * | --- | --- |
     * | 0 `WIFI_5GHZ` | Wi-Fi present and `is5GHzBandSupported()` |
     * | 1 `WIFI_6GHZ` | Wi-Fi present and `is6GHzBandSupported()` |
     * | 2 `WIFI_6_OR_NEWER` | 802.11ax or 802.11be supported |
     * | 3 `WIFI_DIRECT` | `FEATURE_WIFI_DIRECT` and `isP2pSupported()` |
     * | 4 `CAN_HOST_P2P_5GHZ` | Wi-Fi Direct and 5 GHz, and a 5 GHz group was verified once (WP7c) |
     * | 5 `CAN_HOST_LOCAL_HOTSPOT` | Wi-Fi present: every Android 8+ device can start a local-only hotspot (whether it may now is private, WP7d) |
     * | 6 `WIFI_AWARE` | `FEATURE_WIFI_AWARE` |
     * | 7 `BLUETOOTH_RFCOMM` | `FEATURE_BLUETOOTH` and an adapter |
     * | 8 `BLE_EXTENDED_ADVERTISING` | BLE and `isLeExtendedAdvertisingSupported()` |
     * | 9 `BLE_CODED_PHY` | BLE and `isLeCodedPhySupported()` |
     * | 10 `SAVE_LOCATION_REMOVABLE` | the receive volume is removable |
     * | 11 `CONNECTED_TO_WIFI` | a Wi-Fi station network is connected (the beacon clears it again when no hint can be derived) |
     * | 12 `DESKTOP_WITHOUT_BLUETOOTH` | never on a phone |
     * | 13 `STATION_ON_5GHZ` | connected, and the channel is at 4900 MHz or above (N9) |
     *
     * Wi-Fi bits describe the hardware and stay set while Wi-Fi is switched off, so the peer's ladder can still plan a
     * rung the user may enable; the ladder learns the radio state separately.
     */
    fun capabilities(inputs: CapabilityInputs): Capabilities {
        var caps = Capabilities.NONE

        fun set(
            flag: Flag,
            condition: Boolean,
        ) {
            if (condition) caps += flag
        }
        val wifi = inputs.hasWifi
        val direct = wifi && inputs.wifiDirectFeature && inputs.p2pSupported
        set(Flag.WIFI_5GHZ, wifi && inputs.wifi5GhzSupported)
        set(Flag.WIFI_6GHZ, wifi && inputs.wifi6GhzSupported)
        set(Flag.WIFI_6_OR_NEWER, wifi && (inputs.wifiStandard11ax || inputs.wifiStandard11be))
        set(Flag.WIFI_DIRECT, direct)
        set(Flag.CAN_HOST_P2P_5GHZ, direct && inputs.wifi5GhzSupported && inputs.verifiedP2p5GhzHost)
        set(Flag.CAN_HOST_LOCAL_HOTSPOT, wifi)
        set(Flag.WIFI_AWARE, wifi && inputs.wifiAwareFeature)
        set(Flag.BLUETOOTH_RFCOMM, inputs.bluetoothClassicFeature && inputs.bluetoothAdapterPresent)
        val le = inputs.bluetoothLeFeature && inputs.bluetoothAdapterPresent
        set(Flag.BLE_EXTENDED_ADVERTISING, le && inputs.leExtendedAdvertising)
        set(Flag.BLE_CODED_PHY, le && inputs.leCodedPhy)
        set(Flag.SAVE_LOCATION_REMOVABLE, inputs.saveLocationRemovable)
        val station = inputs.station.takeIf { wifi }
        set(Flag.CONNECTED_TO_WIFI, station != null)
        set(Flag.STATION_ON_5GHZ, station != null && stationBand(station.frequencyMhz) == StationBand.BAND_5_GHZ_OR_ABOVE)
        return caps
    }

    /**
     * The station band of N9 from a channel frequency: 2.4 GHz channels, anything from 4900 MHz up (the 5 GHz check of
     * architecture §4), or [StationBand.NONE] for an unknown (`-1`, `0`) or bogus value.
     */
    fun stationBand(frequencyMhz: Int?): StationBand =
        when {
            frequencyMhz == null || frequencyMhz <= 0 -> StationBand.NONE
            WifiBand.fromFrequency(frequencyMhz) == WifiBand.BAND_2_4_GHZ -> StationBand.BAND_2_4_GHZ
            WifiBand.isFiveGhzOrAbove(frequencyMhz) -> StationBand.BAND_5_GHZ_OR_ABOVE
            else -> StationBand.NONE
        }

    /** Everything [AndroidCapabilityDetector] publishes for [inputs]; [crypto] hashes the network hint (N6). */
    fun facts(
        inputs: CapabilityInputs,
        crypto: CryptoProvider,
    ): LocalRadioFacts {
        val station = inputs.station.takeIf { inputs.hasWifi }
        return LocalRadioFacts(
            capabilities = capabilities(inputs),
            networkHint = NetworkHint.derive(crypto, station?.link),
            stationFrequencyMhz = station?.frequencyMhz,
            stationBand = stationBand(station?.frequencyMhz),
            wifiEnabled = inputs.hasWifi && inputs.wifiEnabled,
            bluetoothAvailable = inputs.bluetoothAdapterPresent && inputs.bluetoothLeFeature,
            bluetoothEnabled = inputs.bluetoothAdapterPresent && inputs.bluetoothEnabled,
            staApConcurrency = inputs.hasWifi && inputs.staApConcurrency,
            dualBandSimultaneous = inputs.hasWifi && inputs.dualBandSimultaneous,
            le2mPhy = inputs.bluetoothAdapterPresent && inputs.le2mPhy,
        )
    }
}
