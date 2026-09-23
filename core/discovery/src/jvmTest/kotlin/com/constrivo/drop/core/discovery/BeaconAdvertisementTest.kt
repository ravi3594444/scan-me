package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import com.constrivo.drop.core.discovery.Secrets.K0
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** This device's advertisement and mDNS record: visibility rules (F‑A5, N4), S10, S11. */
class BeaconAdvertisementTest {
    private val crypto = JcaCryptoProvider()
    private val hint = NetworkHint(0x5C452326)
    private val caps = Capabilities.of(Capabilities.Flag.WIFI_5GHZ, Capabilities.Flag.WIFI_DIRECT, Capabilities.Flag.STATION_ON_5GHZ)
    private val state =
        LocalBeaconState(
            visibility = Visibility.EVERYONE,
            platform = DevicePlatform.DESKTOP,
            capabilities = caps,
            networkHint = hint,
            nickname = "Studio PC",
            classicAddress = BluetoothAddress.parse("AA:BB:CC:DD:EE:FF"),
        )

    @Test
    fun fA1_everyoneAdvertisesNicknameHintAndAddress() {
        val ad = BeaconAdvertisement.create(crypto, K0, state, BeaconCarrier.SERVICE_DATA, EPOCH_START + 10)
        assertEquals("4c809f49f441", ad.body.ephemeralId.toHex())
        assertEquals(hint, ad.body.networkHint)
        assertTrue(Capabilities.Flag.CONNECTED_TO_WIFI in ad.body.capabilities, "bit 11 follows the non-zero hint")
        assertEquals(state.classicAddress, ad.body.classicAddress)
        assertEquals("Studio PC", ad.nickname)
        assertEquals(31, ad.advertisingData().size)
        val parsed = BeaconAdvertisements.parse(ad.advertisingData() + ad.scanResponseData()!!)!!
        assertEquals(ad.body, parsed.body)
        assertEquals("Studio PC", parsed.nickname)
        assertContentEquals(ad.body.encode(), ad.carrierPayload())
        assertTrue(ad.hasScanResponse)
        assertContentEquals(
            Fixtures.bytes("6472") + byteArrayOf(0x81.toByte()) + "Studio PC".encodeToByteArray(),
            ad.scanResponseManufacturerData(),
            "the nickname goes out as manufacturer data, never under the beacon's service UUID",
        )
        assertEquals(AdvertisingFormat.SERVICE_UUID_16, ad.serviceUuid16)
        assertEquals(AdvertisingFormat.COMPANY_ID, ad.companyId)
    }

    @Test
    fun n4_trustedOnlyHidesNicknameHintAndAddress() {
        val trustedOnly = state.copy(visibility = Visibility.TRUSTED_ONLY)
        for (carrier in BeaconCarrier.entries) {
            val ad = BeaconAdvertisement.create(crypto, K0, trustedOnly, carrier, EPOCH_START)
            assertNull(ad.nickname)
            assertNull(ad.scanResponseData())
            assertNull(ad.scanResponseManufacturerData())
            assertFalse(ad.hasScanResponse)
            assertTrue(ad.body.networkHint.isNone)
            assertFalse(Capabilities.Flag.CONNECTED_TO_WIFI in ad.body.capabilities)
            assertFalse(Capabilities.Flag.STATION_ON_5GHZ in ad.body.capabilities)
            assertNull(ad.body.classicAddress, "a permanent MAC would link every epoch")
            assertEquals(Visibility.TRUSTED_ONLY, ad.body.visibility)
            val bytes = ad.advertisingData()
            assertFalse(Bytes.hex(bytes).contains(hint.toHex()))
            assertFalse(Bytes.hex(bytes).contains("aabbccddeeff"))
        }
        val record = MdnsRecord.create(crypto, K0, trustedOnly, 49152, EPOCH_START)
        assertNull(record.nickname)
        assertFalse(MdnsRecord.KEY_NICKNAME in record.toTxt())
        assertEquals("2", record.toTxt()[MdnsRecord.KEY_VISIBILITY])
        // The advertisement object itself refuses to carry a nickname in Trusted-only mode.
        val body = BeaconAdvertisement.create(crypto, K0, trustedOnly, BeaconCarrier.SERVICE_DATA, EPOCH_START).body
        assertFailsWith<IllegalArgumentException> { BeaconAdvertisement(BeaconCarrier.SERVICE_DATA, body, "leak", 0) }
    }

    @Test
    fun fA5_hiddenNeverAdvertisesOrAnnounces() {
        val hidden = state.copy(visibility = Visibility.HIDDEN)
        assertFailsWith<IllegalArgumentException> {
            BeaconAdvertisement.create(crypto, K0, hidden, BeaconCarrier.SERVICE_DATA, EPOCH_START)
        }
        assertFailsWith<IllegalArgumentException> { MdnsRecord.create(crypto, K0, hidden, 49152, EPOCH_START) }
    }

    @Test
    fun n6_connectedBitAlwaysAgreesWithTheHint() {
        val offline = state.copy(networkHint = NetworkHint.NONE, capabilities = caps + Capabilities.Flag.CONNECTED_TO_WIFI)
        val ad = BeaconAdvertisement.create(crypto, K0, offline, BeaconCarrier.MANUFACTURER_DATA, EPOCH_START)
        assertFalse(Capabilities.Flag.CONNECTED_TO_WIFI in ad.body.capabilities)
        assertFalse(Capabilities.Flag.STATION_ON_5GHZ in ad.body.capabilities)
        val random = Random(3)
        repeat(500) {
            val s =
                state.copy(
                    visibility = listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY).random(random),
                    capabilities = Capabilities(random.nextInt(0x10000)),
                    networkHint = if (random.nextBoolean()) NetworkHint.NONE else NetworkHint(random.nextInt() or 1),
                )
            val body = BeaconAdvertisement.create(crypto, K0, s, BeaconCarrier.entries.random(random), EPOCH_START).body
            assertEquals(!body.networkHint.isNone, Capabilities.Flag.CONNECTED_TO_WIFI in body.capabilities)
            val record = MdnsRecord.create(crypto, K0, s, 49152, EPOCH_START)
            assertEquals(body.capabilities, record.capabilities, "both sources publish the same capabilities")
            assertEquals(body.ephemeralId, record.ephemeralId, "and the same rotating ID")
        }
    }

    @Test
    fun n4_advertisementIsValidUntilTheNextEpoch() {
        val ad = BeaconAdvertisement.create(crypto, K0, state, BeaconCarrier.SERVICE_DATA, EPOCH_START + 123_456)
        assertEquals(EPOCH_START + EphemeralIds.EPOCH_MILLIS, ad.validUntilMillis)
        val next = BeaconAdvertisement.create(crypto, K0, state, BeaconCarrier.SERVICE_DATA, ad.validUntilMillis)
        assertEquals(EphemeralIds.derive(crypto, K0, EPOCH + 1), next.body.ephemeralId)
        val record = MdnsRecord.create(crypto, K0, state, 49152, ad.validUntilMillis)
        assertEquals("drop-${next.body.ephemeralId.toHex()}", record.instanceName)
    }

    @Test
    fun s11_manufacturerCarrierForWindows() {
        val ad = BeaconAdvertisement.create(crypto, K0, state.copy(classicAddress = null), BeaconCarrier.MANUFACTURER_DATA, EPOCH_START)
        val data = ad.advertisingData()
        assertEquals(23, data.size)
        assertContentEquals(Fixtures.bytes("6472") + ad.body.encode(), ad.carrierPayload())
        val sighting = assertNotNull(BeaconSighting.fromAdvertisingData(data, -48, "C0:FF:EE:00:00:01", EPOCH_START))
        assertEquals(BeaconCarrier.MANUFACTURER_DATA, sighting.carrier)
        assertEquals(ad.body, BeaconBody.decode(sighting.body))
        // No scan response: the body already uses the company key, and WinRT cannot set one. The name goes over mDNS.
        assertEquals("Studio PC", ad.nickname)
        assertFalse(ad.hasScanResponse)
        assertNull(ad.scanResponseData())
        assertNull(ad.scanResponseManufacturerData())
        assertEquals("Studio PC", MdnsRecord.create(crypto, K0, state, 49152, EPOCH_START).nickname)
    }

    @Test
    fun anUnknownPlatformIsNeverAdvertised() {
        val unknown = state.copy(platform = DevicePlatform.UNKNOWN)
        assertFailsWith<IllegalArgumentException> {
            BeaconAdvertisement.create(crypto, K0, unknown, BeaconCarrier.SERVICE_DATA, EPOCH_START)
        }
        assertFailsWith<IllegalArgumentException> { MdnsRecord.create(crypto, K0, unknown, 49152, EPOCH_START) }
    }

    @Test
    fun longNicknamesAreShortenedForTheScanResponse() {
        val ad =
            BeaconAdvertisement.create(
                crypto,
                K0,
                state.copy(nickname = "Priya's Galaxy S24 Ultra 🚀🚀"),
                BeaconCarrier.SERVICE_DATA,
                EPOCH_START,
            )
        val scanResponse = ad.scanResponseData()!!
        assertTrue(scanResponse.size <= 31)
        val parsed = BeaconAdvertisements.parse(ad.advertisingData() + scanResponse)!!
        assertEquals("Priya's Galaxy S24 Ultra", parsed.nickname)
        assertTrue(parsed.nicknameTruncated)
    }
}
