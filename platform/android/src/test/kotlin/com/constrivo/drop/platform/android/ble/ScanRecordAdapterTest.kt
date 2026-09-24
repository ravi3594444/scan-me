package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.BeaconBody
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.ble.BleTestSupport.NOW
import com.constrivo.drop.platform.android.ble.BleTestSupport.advertisement
import com.constrivo.drop.platform.android.ble.BleTestSupport.legacyRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-A1, F-A2: raw `ScanRecord` bytes to `BeaconSighting` through core/discovery's parser. */
class ScanRecordAdapterTest {
    private val elapsedNowNanos = 5_000_000_000_000L

    private fun parse(
        record: ByteArray?,
        timestampNanos: Long = elapsedNowNanos,
    ) = ScanRecordAdapter.parse(record, -61, "4A:1B:2C:3D:4E:5F", timestampNanos, elapsedNowNanos, NOW)

    @Test
    fun aPhoneBeaconWithItsScanResponseBecomesASighting() {
        val ad = advertisement()
        val outcome = parse(legacyRecord(ad.advertisingData(), ad.scanResponseData()!!))
        val sighting = assertIs<ScanOutcome.Sighting>(outcome).sighting
        assertContentEquals(ad.body.encode(), sighting.body)
        assertEquals(BeaconCarrier.SERVICE_DATA, sighting.carrier)
        assertEquals("Ana's Pixel", sighting.localName)
        assertFalse(sighting.localNameTruncated)
        assertEquals(-61, sighting.rssiDbm)
        assertEquals("4A:1B:2C:3D:4E:5F", sighting.radioAddress)
        assertEquals(NOW, sighting.atMillis)
        assertEquals(ad.body, BeaconBody.decode(sighting.body))
    }

    @Test
    fun aWindowsBeaconOnTheManufacturerCarrierIsAccepted() {
        val ad = advertisement(carrier = BeaconCarrier.MANUFACTURER_DATA)
        val sighting = assertIs<ScanOutcome.Sighting>(parse(legacyRecord(ad.advertisingData()))).sighting
        assertEquals(BeaconCarrier.MANUFACTURER_DATA, sighting.carrier)
        assertNull(sighting.localName)
    }

    @Test
    fun aTrustedOnlyBeaconHasNoName() {
        val ad = advertisement(visibility = Visibility.TRUSTED_ONLY)
        assertNull(ad.scanResponseData())
        val sighting = assertIs<ScanOutcome.Sighting>(parse(legacyRecord(ad.advertisingData()))).sighting
        assertNull(sighting.localName)
    }

    @Test
    fun theExtendedSetCarriesTheFullNickname() {
        val name = "Ana's very long phone name that does not fit a legacy scan response"
        val ad = advertisement(nickname = name)
        val extended = AdvertisingPlan.extended(ad, RadioMode.FOREGROUND, AdvertiserCapabilities(true, true, 251))
        val sighting = assertIs<ScanOutcome.Sighting>(parse(BleTestSupport.onAir(extended))).sighting
        assertEquals(ad.nickname, sighting.localName)
        assertFalse(sighting.localNameTruncated)
        // The legacy scan response has room for 24 bytes only.
        val legacy = assertIs<ScanOutcome.Sighting>(parse(legacyRecord(ad.advertisingData(), ad.scanResponseData()!!))).sighting
        assertTrue(legacy.localNameTruncated)
        assertTrue(ad.nickname!!.startsWith(legacy.localName!!))
    }

    @Test
    fun resultTimesAreConvertedFromElapsedRealtimeToUnixTime() {
        val ad = advertisement()
        val record = legacyRecord(ad.advertisingData())
        val older = assertIs<ScanOutcome.Sighting>(parse(record, timestampNanos = elapsedNowNanos - 1_500_000_000L)).sighting
        assertEquals(NOW - 1_500, older.atMillis)
        // A stamp "from the future" (stack and app clocks disagree by a little) counts as now.
        val future = assertIs<ScanOutcome.Sighting>(parse(record, timestampNanos = elapsedNowNanos + 3_000_000L)).sighting
        assertEquals(NOW, future.atMillis)
    }

    @Test
    fun recordsWithoutADropBeaconAreNotOurs() {
        assertEquals(ScanOutcome.NotDrop, parse(null))
        assertEquals(ScanOutcome.NotDrop, parse(ByteArray(0)))
        assertEquals(ScanOutcome.NotDrop, parse(ByteArray(62)))
        // Another product under the shared test company identifier, without our "dr" marker.
        val foreign = byteArrayOf(2, 1, 6, 7, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3, 4)
        assertEquals(ScanOutcome.NotDrop, parse(foreign))
        // Only a nickname record, no body.
        val nicknameOnly = advertisement().scanResponseData()!!
        assertEquals(ScanOutcome.NotDrop, parse(nicknameOnly))
    }

    @Test
    fun malformedAndFutureBeaconsAreCountedNotThrown() {
        // A length byte that runs past the end.
        assertIs<ScanOutcome.Malformed>(parse(byteArrayOf(2, 1, 6, 30, 0x16, 1)))
        // A drop body with a broken layout (visibility Hidden is never sent).
        val body = advertisement().body.encode().also { it[13] = (0xC0).toByte() }
        val record =
            byteArrayOf(3, 3) + le16(AdvertisingFormat.SERVICE_UUID_16) + byteArrayOf((body.size + 3).toByte(), 0x16) +
                le16(AdvertisingFormat.SERVICE_UUID_16) +
                body
        assertIs<ScanOutcome.Malformed>(parse(record))
        // A body of an incompatible future layout (version 0x10).
        val future = advertisement().body.encode().also { it[0] = 0x10 }
        val futureRecord = byteArrayOf((future.size + 3).toByte(), 0x16) + le16(AdvertisingFormat.SERVICE_UUID_16) + future
        assertEquals(ScanOutcome.Unsupported, parse(futureRecord))
    }

    @Test
    fun randomBytesNeverEscapeAsExceptions() {
        val random = Random(7)
        repeat(5_000) {
            val bytes = random.nextBytes(random.nextInt(0, 80))
            parse(bytes)
        }
        // Mutations of a real record too.
        val good = legacyRecord(advertisement().advertisingData(), advertisement().scanResponseData()!!)
        repeat(5_000) {
            val mutated = good.copyOf()
            repeat(random.nextInt(1, 4)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            parse(mutated)
        }
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
}
