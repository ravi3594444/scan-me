package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.BeaconAdvertisements
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.ble.BleTestSupport.advertisement
import com.constrivo.drop.platform.android.ble.BleTestSupport.onAir
import com.constrivo.drop.platform.android.ble.BleTestSupport.scanResponseOnAir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-A1 and architecture §5.1 on Android: which advertising sets, what they carry, at which interval (S11, N4). */
class AdvertisingPlanTest {
    private val legacyOnly = AdvertiserCapabilities(extendedAdvertising = false, le2mPhy = false, maxAdvertisingDataLength = 31)
    private val extendedCapable = AdvertiserCapabilities(extendedAdvertising = true, le2mPhy = true, maxAdvertisingDataLength = 251)

    @Test
    fun theLegacySetIsExactlyTheCoreLayout() {
        val ad = advertisement()
        val legacy = AdvertisingPlan.legacy(ad, RadioMode.FOREGROUND)
        assertEquals(AdvertisingSetKind.LEGACY, legacy.kind)
        assertTrue(legacy.connectable && legacy.scannable)
        // What Android puts on air for this set is byte for byte what core/discovery specifies (§5.1, 25 bytes).
        assertContentEquals(ad.advertisingData(), onAir(legacy))
        assertEquals(25, legacy.advertisingDataLength)
        assertContentEquals(ad.scanResponseData(), scanResponseOnAir(legacy))
        assertTrue(legacy.scanResponseLength <= 31)
    }

    @Test
    fun aSetEndsAtItsEpochBoundaryAndAnExpiredOneNeverStarts() {
        val boundary = 1_800_000_000_000L
        // Ten seconds before the boundary: 1000 units of 10 ms, rounded up so the ID never ends early by a unit.
        assertEquals(1_000, AdvertisingPlan.durationUnits(boundary, boundary - 10_000))
        assertEquals(1, AdvertisingPlan.durationUnits(boundary, boundary - 1))
        assertEquals(2, AdvertisingPlan.durationUnits(boundary, boundary - 11))
        // Longer than the controller allows (655.35 s): capped, and restarted within the epoch.
        assertEquals(AdvertisingPlan.MAX_DURATION_UNITS, AdvertisingPlan.durationUnits(boundary, boundary - 15 * 60_000L))
        // At or past the boundary the advertisement has expired.
        assertNull(AdvertisingPlan.durationUnits(boundary, boundary))
        assertNull(AdvertisingPlan.durationUnits(boundary, boundary + 60_000))
    }

    @Test
    fun intervalsFollowTheRadioMode() {
        val ad = advertisement()
        assertEquals(160, AdvertisingPlan.legacy(ad, RadioMode.FOREGROUND).interval) // 100 ms
        assertEquals(1600, AdvertisingPlan.legacy(ad, RadioMode.BACKGROUND).interval) // 1 s
        // One transmit power in both modes, so peers' rings do not jump at a mode change.
        assertEquals(
            AdvertisingPlan.legacy(ad, RadioMode.FOREGROUND).txPowerDbm,
            AdvertisingPlan.legacy(ad, RadioMode.BACKGROUND).txPowerDbm,
        )
    }

    @Test
    fun trustedOnlySendsNoScanResponse() {
        val legacy = AdvertisingPlan.legacy(advertisement(visibility = Visibility.TRUSTED_ONLY), RadioMode.FOREGROUND)
        assertNull(legacy.scanResponseManufacturerData)
        assertEquals(0, legacy.scanResponseLength)
    }

    @Test
    fun theScanResponseCanBeSwitchedOffForTheLab() {
        val legacy = AdvertisingPlan.legacy(advertisement(), RadioMode.FOREGROUND, AdvertisingConfig(scanResponse = false))
        assertNull(legacy.scanResponseManufacturerData)
        assertNotNull(legacy.serviceData)
    }

    @Test
    fun theNicknameNeverSharesAKeyWithTheBody() {
        // Dictionary-style scanners (BlueZ, CoreBluetooth) merge advertising data and scan response per AD key.
        val legacy = AdvertisingPlan.legacy(advertisement(), RadioMode.FOREGROUND)
        assertNotNull(legacy.serviceData)
        assertNull(legacy.manufacturerData)
        assertNotNull(legacy.scanResponseManufacturerData)
    }

    @Test
    fun theManufacturerCarrierHasNoUuidListAndNoScanResponse() {
        val ad = advertisement(carrier = BeaconCarrier.MANUFACTURER_DATA)
        val legacy = AdvertisingPlan.legacy(ad, RadioMode.BACKGROUND)
        assertEquals(false, legacy.includeServiceUuid)
        assertNull(legacy.serviceData)
        assertContentEquals(ad.carrierPayload(), legacy.manufacturerData)
        assertNull(legacy.scanResponseManufacturerData)
        assertContentEquals(ad.advertisingData(), onAir(legacy))
    }

    @Test
    fun theExtendedSetRunsOnlyWhereTheAdapterSupportsIt() {
        val ad = advertisement()
        assertEquals(listOf(AdvertisingSetKind.LEGACY), AdvertisingPlan.sets(ad, RadioMode.FOREGROUND, legacyOnly).map { it.kind })
        assertEquals(
            listOf(AdvertisingSetKind.LEGACY, AdvertisingSetKind.EXTENDED),
            AdvertisingPlan.sets(ad, RadioMode.FOREGROUND, extendedCapable).map { it.kind },
        )
        assertEquals(
            listOf(AdvertisingSetKind.LEGACY),
            AdvertisingPlan.sets(ad, RadioMode.FOREGROUND, extendedCapable, AdvertisingConfig(extendedAdvertising = false)).map { it.kind },
        )
        // A controller that reports extended support but a tiny data length gets the legacy set only.
        assertEquals(
            listOf(AdvertisingSetKind.LEGACY),
            AdvertisingPlan.sets(
                advertisement(nickname = "x".repeat(60)),
                RadioMode.FOREGROUND,
                extendedCapable.copy(maxAdvertisingDataLength = 40),
            ).map {
                it.kind
            },
        )
    }

    @Test
    fun theExtendedSetIsConnectableNotScannableAndUses2MWherePossible() {
        val extended = AdvertisingPlan.extended(advertisement(), RadioMode.FOREGROUND, extendedCapable)
        assertTrue(extended.connectable)
        assertEquals(false, extended.scannable)
        assertEquals(AdvertisingPlan.PHY_LE_1M, extended.primaryPhy)
        assertEquals(AdvertisingPlan.PHY_LE_2M, extended.secondaryPhy)
        assertEquals(
            AdvertisingPlan.PHY_LE_1M,
            AdvertisingPlan.extended(advertisement(), RadioMode.FOREGROUND, extendedCapable.copy(le2mPhy = false)).secondaryPhy,
        )
        assertNull(extended.scanResponseManufacturerData)
    }

    @Test
    fun theExtendedSetCarriesBodyAndFullNicknameAndParsesBack() {
        val name = "Ana's Pixel 9 Pro Fold in the kitchen"
        val ad = advertisement(nickname = name)
        val extended = AdvertisingPlan.extended(ad, RadioMode.FOREGROUND, extendedCapable)
        val bytes = onAir(extended)
        assertEquals(extended.advertisingDataLength, bytes.size)
        assertTrue(bytes.size > 31)
        val parsed = BeaconAdvertisements.parse(bytes)!!
        assertEquals(ad.body, parsed.body)
        assertEquals(name, parsed.nickname)
        assertEquals(BeaconCarrier.SERVICE_DATA, parsed.carrier)
        val sighting = BeaconSighting.fromAdvertisingData(bytes, -50, "AA:BB:CC:DD:EE:01", 0)!!
        assertEquals(name, sighting.localName)
    }

    @Test
    fun aTrustedOnlyExtendedSetHasNoNickname() {
        val extended = AdvertisingPlan.extended(advertisement(visibility = Visibility.TRUSTED_ONLY), RadioMode.FOREGROUND, extendedCapable)
        assertNull(extended.manufacturerData)
        assertNull(BeaconAdvertisements.parse(onAir(extended))!!.nickname)
    }

    @Test
    fun fullNicknameRecordsAreCutAt64BytesAndMarked() {
        val short = AdvertisingPlan.fullNicknameRecord("Ana")!!
        assertContentEquals(byteArrayOf(0x64, 0x72, AdvertisingFormat.RECORD_NICKNAME_COMPLETE.toByte()) + "Ana".encodeToByteArray(), short)
        val long = AdvertisingPlan.fullNicknameRecord("é".repeat(40))!!
        assertEquals(AdvertisingFormat.RECORD_NICKNAME_SHORTENED, long[2].toInt() and 0xFF)
        // 32 two-byte characters fit in 64 bytes; the cut never splits one.
        assertEquals("é".repeat(32), long.copyOfRange(3, long.size).decodeToString())
        assertNull(AdvertisingPlan.fullNicknameRecord("​​"))
    }

    @Test
    fun everyLegacySetFits31Bytes() {
        for (visibility in listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY)) {
            for (carrier in BeaconCarrier.entries) {
                for (name in listOf("A", "x".repeat(64), "漢".repeat(30))) {
                    val sets = AdvertisingPlan.sets(advertisement(visibility, name, carrier), RadioMode.FOREGROUND, extendedCapable)
                    val legacy = sets.first()
                    assertTrue(legacy.advertisingDataLength <= 31 && legacy.scanResponseLength <= 31, "$visibility $carrier $name")
                }
            }
        }
    }
}
