package com.constrivo.drop.platform.android.capability

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.IpAddress
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.NetworkLinkInfo
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.StationBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-A4: the Android-to-capabilities mapping of architecture §5.2 (with N6 and N9), as a pure function. */
class CapabilityMappingTest {
    private val crypto = JcaCryptoProvider()

    private val link =
        NetworkLinkInfo(
            gateways = listOf(IpAddress.parse("192.168.1.1"), IpAddress.parse("fe80::1")),
            dhcpServer = IpAddress.parse("192.168.1.1"),
            ipv6Addresses = listOf(IpAddress.parse("2001:db8:1:2::abcd")),
        )

    /** A current flagship: every feature, connected on 5 GHz. */
    private val flagship =
        CapabilityInputs(
            hasWifi = true,
            wifiEnabled = true,
            wifi5GhzSupported = true,
            wifi6GhzSupported = true,
            wifiStandard11ax = true,
            wifiStandard11be = true,
            wifiDirectFeature = true,
            p2pSupported = true,
            wifiAwareFeature = true,
            bluetoothClassicFeature = true,
            bluetoothLeFeature = true,
            bluetoothAdapterPresent = true,
            bluetoothEnabled = true,
            leExtendedAdvertising = true,
            leCodedPhy = true,
            le2mPhy = true,
            saveLocationRemovable = false,
            verifiedP2p5GhzHost = true,
            station = StationFacts(5180, link),
            staApConcurrency = true,
            dualBandSimultaneous = true,
        )

    @Test
    fun aFlagshipSetsEveryHardwareBit() {
        val caps = CapabilityMapping.capabilities(flagship)
        val expected =
            setOf(
                Flag.WIFI_5GHZ,
                Flag.WIFI_6GHZ,
                Flag.WIFI_6_OR_NEWER,
                Flag.WIFI_DIRECT,
                Flag.CAN_HOST_P2P_5GHZ,
                Flag.CAN_HOST_LOCAL_HOTSPOT,
                Flag.WIFI_AWARE,
                Flag.BLUETOOTH_RFCOMM,
                Flag.BLE_EXTENDED_ADVERTISING,
                Flag.BLE_CODED_PHY,
                Flag.CONNECTED_TO_WIFI,
                Flag.STATION_ON_5GHZ,
            )
        assertEquals(expected, caps.flags())
        assertFalse(Flag.DESKTOP_WITHOUT_BLUETOOTH in caps)
    }

    @Test
    fun aBudgetPhoneOn24GhzGetsOnlyWhatItHas() {
        val budget =
            CapabilityInputs(
                hasWifi = true,
                wifiDirectFeature = true,
                p2pSupported = true,
                bluetoothClassicFeature = true,
                bluetoothLeFeature = true,
                bluetoothAdapterPresent = true,
                saveLocationRemovable = true,
                station = StationFacts(2437, link),
            )
        val caps = CapabilityMapping.capabilities(budget)
        assertEquals(
            setOf(
                Flag.WIFI_DIRECT,
                Flag.CAN_HOST_LOCAL_HOTSPOT,
                Flag.BLUETOOTH_RFCOMM,
                Flag.SAVE_LOCATION_REMOVABLE,
                Flag.CONNECTED_TO_WIFI,
            ),
            caps.flags(),
        )
        assertEquals(StationBand.BAND_2_4_GHZ, CapabilityMapping.facts(budget, crypto).stationBand)
    }

    @Test
    fun wifiBitsNeedWifiHardware() {
        val noWifi = flagship.copy(hasWifi = false)
        val caps = CapabilityMapping.capabilities(noWifi)
        for (flag in listOf(
            Flag.WIFI_5GHZ,
            Flag.WIFI_6GHZ,
            Flag.WIFI_6_OR_NEWER,
            Flag.WIFI_DIRECT,
            Flag.CAN_HOST_P2P_5GHZ,
            Flag.CAN_HOST_LOCAL_HOTSPOT,
            Flag.WIFI_AWARE,
            Flag.CONNECTED_TO_WIFI,
            Flag.STATION_ON_5GHZ,
        )) {
            assertFalse(flag in caps, flag.name)
        }
        assertTrue(Flag.BLE_EXTENDED_ADVERTISING in caps)
        val facts = CapabilityMapping.facts(noWifi, crypto)
        assertEquals(NetworkHint.NONE, facts.networkHint)
        assertNull(facts.stationFrequencyMhz)
        assertFalse(facts.wifiEnabled)
    }

    @Test
    fun wifiDirectNeedsTheFeatureAndTheManager() {
        assertFalse(Flag.WIFI_DIRECT in CapabilityMapping.capabilities(flagship.copy(p2pSupported = false)))
        assertFalse(Flag.WIFI_DIRECT in CapabilityMapping.capabilities(flagship.copy(wifiDirectFeature = false)))
        // Bit 4 also needs 5 GHz and a verified 5 GHz group (WP7c).
        assertFalse(Flag.CAN_HOST_P2P_5GHZ in CapabilityMapping.capabilities(flagship.copy(p2pSupported = false)))
        assertFalse(Flag.CAN_HOST_P2P_5GHZ in CapabilityMapping.capabilities(flagship.copy(wifi5GhzSupported = false)))
        assertFalse(Flag.CAN_HOST_P2P_5GHZ in CapabilityMapping.capabilities(flagship.copy(verifiedP2p5GhzHost = false)))
    }

    @Test
    fun wifi6OrNewerCountsWifi7() {
        assertTrue(Flag.WIFI_6_OR_NEWER in CapabilityMapping.capabilities(flagship.copy(wifiStandard11ax = false, wifiStandard11be = true)))
        assertTrue(Flag.WIFI_6_OR_NEWER in CapabilityMapping.capabilities(flagship.copy(wifiStandard11ax = true, wifiStandard11be = false)))
        assertFalse(
            Flag.WIFI_6_OR_NEWER in CapabilityMapping.capabilities(flagship.copy(wifiStandard11ax = false, wifiStandard11be = false)),
        )
    }

    @Test
    fun bluetoothBitsNeedTheFeatureAndAnAdapter() {
        val noAdapter = CapabilityMapping.capabilities(flagship.copy(bluetoothAdapterPresent = false))
        assertFalse(Flag.BLUETOOTH_RFCOMM in noAdapter || Flag.BLE_EXTENDED_ADVERTISING in noAdapter || Flag.BLE_CODED_PHY in noAdapter)
        val noLe = CapabilityMapping.capabilities(flagship.copy(bluetoothLeFeature = false))
        assertTrue(Flag.BLUETOOTH_RFCOMM in noLe)
        assertFalse(Flag.BLE_EXTENDED_ADVERTISING in noLe || Flag.BLE_CODED_PHY in noLe)
        val noClassic = CapabilityMapping.capabilities(flagship.copy(bluetoothClassicFeature = false))
        assertFalse(Flag.BLUETOOTH_RFCOMM in noClassic)
        assertFalse(CapabilityMapping.facts(flagship.copy(bluetoothAdapterPresent = false), crypto).bluetoothAvailable)
    }

    @Test
    fun theStationBandComesFromTheFrequency() {
        assertEquals(StationBand.NONE, CapabilityMapping.stationBand(null))
        assertEquals(StationBand.NONE, CapabilityMapping.stationBand(-1))
        assertEquals(StationBand.NONE, CapabilityMapping.stationBand(0))
        assertEquals(StationBand.NONE, CapabilityMapping.stationBand(3000))
        assertEquals(StationBand.BAND_2_4_GHZ, CapabilityMapping.stationBand(2412))
        assertEquals(StationBand.BAND_2_4_GHZ, CapabilityMapping.stationBand(2484))
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, CapabilityMapping.stationBand(4900))
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, CapabilityMapping.stationBand(5825))
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, CapabilityMapping.stationBand(5955))
        for ((frequency, onFive) in listOf(2412 to false, 5180 to true, 6115 to true, -1 to false)) {
            val caps = CapabilityMapping.capabilities(flagship.copy(station = StationFacts(frequency, link)))
            assertTrue(Flag.CONNECTED_TO_WIFI in caps)
            assertEquals(onFive, Flag.STATION_ON_5GHZ in caps, "$frequency MHz")
        }
    }

    @Test
    fun notConnectedClearsTheStationBitsAndTheHint() {
        val offline = flagship.copy(station = null)
        val caps = CapabilityMapping.capabilities(offline)
        assertFalse(Flag.CONNECTED_TO_WIFI in caps || Flag.STATION_ON_5GHZ in caps)
        val facts = CapabilityMapping.facts(offline, crypto)
        assertEquals(NetworkHint.NONE, facts.networkHint)
        assertEquals(StationBand.NONE, facts.stationBand)
    }

    @Test
    fun theHintIsTheN6DerivationOfTheStationLink() {
        val facts = CapabilityMapping.facts(flagship, crypto)
        assertEquals(NetworkHint.derive(crypto, link), facts.networkHint)
        assertFalse(facts.networkHint.isNone)
        assertEquals(5180, facts.stationFrequencyMhz)
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, facts.stationBand)
        assertTrue(facts.wifiEnabled && facts.bluetoothEnabled && facts.staApConcurrency && facts.dualBandSimultaneous && facts.le2mPhy)
        // A station whose link properties have not arrived yet is connected but has nothing to hash.
        val early = CapabilityMapping.facts(flagship.copy(station = StationFacts(5180, NetworkLinkInfo())), crypto)
        assertEquals(NetworkHint.NONE, early.networkHint)
    }

    @Test
    fun theBeaconClearsStationBitsWhenThereIsNoHintAndInTrustedOnlyMode() {
        val facts = CapabilityMapping.facts(flagship, crypto)
        val secret = ByteArray(32) { 3 }

        fun advertised(
            visibility: Visibility,
            hint: NetworkHint,
        ): Capabilities =
            BeaconAdvertisement
                .create(
                    crypto,
                    secret,
                    LocalBeaconState(visibility, DevicePlatform.PHONE, facts.capabilities, hint, "Ana"),
                    BeaconCarrier.SERVICE_DATA,
                    1_000_000,
                )
                .body.capabilities
        assertTrue(Flag.STATION_ON_5GHZ in advertised(Visibility.EVERYONE, facts.networkHint))
        assertFalse(Flag.CONNECTED_TO_WIFI in advertised(Visibility.EVERYONE, NetworkHint.NONE))
        assertFalse(Flag.STATION_ON_5GHZ in advertised(Visibility.TRUSTED_ONLY, facts.networkHint))
    }

    @Test
    fun unknownFactsClaimNothing() {
        assertEquals(Capabilities.NONE, LocalRadioFacts.UNKNOWN.capabilities)
        assertEquals(Capabilities.NONE, CapabilityMapping.capabilities(CapabilityInputs()))
    }
}
