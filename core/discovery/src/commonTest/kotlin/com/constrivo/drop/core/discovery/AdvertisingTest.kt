package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.discovery.Fixtures.bytes
import com.constrivo.drop.core.discovery.Fixtures.hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Legacy advertising payloads for both carriers (S11), the scan response and raw AD parsing. */
class AdvertisingTest {
    @Test
    fun s11_serviceDataCarrierGoldenBytes() {
        val payload = BeaconAdvertisements.advertisingData(Fixtures.BODY, BeaconCarrier.SERVICE_DATA)
        assertEquals(Fixtures.SERVICE_DATA_HEX, hex(payload))
        assertEquals(25, payload.size)
    }

    @Test
    fun s10_s11_serviceDataWithClassicAddressFillsExactly31Bytes() {
        val payload = BeaconAdvertisements.advertisingData(Fixtures.BODY_WITH_ADDRESS, BeaconCarrier.SERVICE_DATA)
        assertEquals(Fixtures.SERVICE_DATA_WITH_ADDRESS_HEX, hex(payload))
        assertEquals(AdvertisingFormat.LEGACY_PAYLOAD_MAX, payload.size)
    }

    @Test
    fun s11_manufacturerDataCarrierGoldenBytes() {
        val payload = BeaconAdvertisements.advertisingData(Fixtures.BODY, BeaconCarrier.MANUFACTURER_DATA)
        assertEquals(Fixtures.MANUFACTURER_DATA_HEX, hex(payload))
        assertEquals(23, payload.size)
        val withAddress = BeaconAdvertisements.advertisingData(Fixtures.BODY_WITH_ADDRESS, BeaconCarrier.MANUFACTURER_DATA)
        assertEquals(Fixtures.MANUFACTURER_DATA_WITH_ADDRESS_HEX, hex(withAddress))
        assertEquals(29, withAddress.size)
    }

    @Test
    fun scanResponseGoldenBytes() {
        assertEquals(Fixtures.SCAN_RESPONSE_HEX, hex(BeaconAdvertisements.scanResponseData(Fixtures.NICKNAME)!!))
        assertEquals(
            Fixtures.SCAN_RESPONSE_MANUFACTURER_HEX,
            hex(BeaconAdvertisements.scanResponseData(Fixtures.NICKNAME, BeaconCarrier.MANUFACTURER_DATA)!!),
        )
    }

    @Test
    fun scanResponseNicknameIsCutAtACodePointBoundary() {
        val payload = BeaconAdvertisements.scanResponseData(Fixtures.LONG_NICKNAME)!!
        assertEquals(Fixtures.SCAN_RESPONSE_SHORTENED_HEX, hex(payload))
        assertEquals(30, payload.size)
        val parsed = BeaconAdvertisements.parse(bytes(Fixtures.SERVICE_DATA_HEX) + payload)!!
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXY", parsed.nickname)
        assertTrue(parsed.nicknameTruncated)
    }

    @Test
    fun scanResponseHasNo128BitUuid() {
        val structures = BeaconAdvertisements.parseStructures(BeaconAdvertisements.scanResponseData(Fixtures.NICKNAME)!!)
        assertEquals(listOf(AdvertisingFormat.AD_TYPE_SERVICE_DATA_16BIT), structures.map { it.type })
    }

    @Test
    fun emptyNicknameGivesNoScanResponse() {
        assertNull(BeaconAdvertisements.scanResponseData(""))
        assertNull(BeaconAdvertisements.scanResponseData(" \u0000\u202E\n "))
    }

    @Test
    fun fA1_everyPayloadFits31BytesForRandomInputs() {
        val random = Random(31)
        repeat(3_000) {
            val body = Fixtures.randomBody(random)
            val nickname = Fixtures.randomNickname(random)
            for (carrier in BeaconCarrier.entries) {
                val ad = BeaconAdvertisements.advertisingData(body, carrier)
                assertTrue(ad.size <= AdvertisingFormat.LEGACY_PAYLOAD_MAX, "advertising data ${ad.size} bytes")
                val sr = BeaconAdvertisements.scanResponseData(nickname, carrier)
                if (sr != null) {
                    assertTrue(sr.size <= AdvertisingFormat.LEGACY_PAYLOAD_MAX, "scan response ${sr.size} bytes")
                    val parsed = BeaconAdvertisements.parse(ad + sr)!!
                    assertNotNull(parsed.nickname)
                    // The advertised name is a code-point prefix of the sanitised nickname.
                    val clean = Nicknames.normalize(nickname)!!.text
                    assertTrue(clean.startsWith(parsed.nickname), "'${parsed.nickname}' is not a prefix of '$clean'")
                    assertEquals(parsed.nickname != clean, parsed.nicknameTruncated)
                }
            }
        }
    }

    @Test
    fun roundTripBothCarriers() {
        val random = Random(99)
        repeat(1_000) {
            val body = Fixtures.randomBody(random)
            for (carrier in BeaconCarrier.entries) {
                val parsed = BeaconAdvertisements.parse(BeaconAdvertisements.advertisingData(body, carrier))!!
                assertEquals(body, parsed.body)
                assertEquals(carrier, parsed.carrier)
                assertNull(parsed.nickname)
            }
        }
    }

    @Test
    fun androidStyleZeroPaddedRecordParses() {
        // ScanRecord.getBytes() can hand over the advertising data padded to 31 bytes, then the scan response.
        val ad = bytes(Fixtures.SERVICE_DATA_HEX)
        val padded = ad + ByteArray(31 - ad.size) + bytes(Fixtures.SCAN_RESPONSE_HEX) + ByteArray(62 - 31 - 17)
        val sighting = BeaconSighting.fromAdvertisingData(padded, rssiDbm = -60, radioAddress = "5A:11:22:33:44:55", atMillis = 1)!!
        assertEquals(Fixtures.BODY, BeaconBody.decode(sighting.body))
        assertEquals(Fixtures.NICKNAME, sighting.localName)
        assertEquals(BeaconCarrier.SERVICE_DATA, sighting.carrier)
    }

    @Test
    fun unrelatedStructuresAreTolerated() {
        val unrelated =
            bytes("0319c103") + // appearance
                bytes("0509") + "Pixl".encodeToByteArray() + // complete local name
                bytes("0303aafe") + // another 16-bit UUID
                bytes("05169ffe0102") + // service data of another UUID
                bytes("07ff4c0010020b00") + // another company's manufacturer data
                bytes("07ffffff01020304") + // the shared test company id with a foreign marker
                bytes("021600") + // service data too short to hold a UUID
                bytes("01ff") // manufacturer data without a company id
        assertEquals(8, BeaconAdvertisements.parseStructures(unrelated).size)
        assertNull(BeaconAdvertisements.parse(unrelated))
        val parsed = BeaconAdvertisements.parse(unrelated + bytes(Fixtures.MANUFACTURER_DATA_HEX))!!
        assertEquals(Fixtures.BODY, parsed.body)
        assertEquals(BeaconCarrier.MANUFACTURER_DATA, parsed.carrier)
    }

    @Test
    fun malformedLengthBytesAreRejected() {
        assertFailsWith<DiscoveryFormatException> { BeaconAdvertisements.parseStructures(bytes("020106ff")) }
        assertFailsWith<DiscoveryFormatException> { BeaconAdvertisements.parseStructures(bytes("05")) }
        assertFailsWith<DiscoveryFormatException> { BeaconAdvertisements.parse(bytes(Fixtures.SERVICE_DATA_HEX).copyOfRange(0, 24)) }
        assertEquals(emptyList(), BeaconAdvertisements.parseStructures(ByteArray(62)).map { it.type })
    }

    @Test
    fun conflictingBodiesAreRejectedAndDuplicatesAccepted() {
        val ad = bytes(Fixtures.SERVICE_DATA_HEX)
        assertEquals(Fixtures.BODY, BeaconAdvertisements.parse(ad + ad)!!.body)
        val other = BeaconAdvertisements.advertisingData(Fixtures.BODY.copy(ephemeralId = EphemeralId(1)), BeaconCarrier.MANUFACTURER_DATA)
        assertFailsWith<DiscoveryFormatException> { BeaconAdvertisements.parse(ad + other) }
    }

    @Test
    fun unknownAuxiliaryRecordsAreIgnored() {
        val aux = bytes("081601df9001020304")
        val parsed = BeaconAdvertisements.parse(bytes(Fixtures.SERVICE_DATA_HEX) + aux)!!
        assertEquals(Fixtures.BODY, parsed.body)
        assertIs<DropRecord.Ignored>(DropRecord.decode(bytes("9001")))
    }

    @Test
    fun futureIncompatibleBodyRaisesUnsupportedVersion() {
        val raw = bytes(Fixtures.SERVICE_DATA_HEX)
        raw[11] = 0x20
        assertFailsWith<UnsupportedBeaconVersionException> { BeaconAdvertisements.parse(raw) }
    }

    @Test
    fun dictionaryScannerHelpers() {
        val body = Fixtures.BODY.encode()
        assertEquals(DropRecord.Beacon(Fixtures.BODY), DropRecord.fromServiceData(AdvertisingFormat.SERVICE_UUID_16, body))
        assertNull(DropRecord.fromServiceData(0xFEAA, body))
        val manufacturer = BeaconAdvertisements.carrierPayload(Fixtures.BODY, BeaconCarrier.MANUFACTURER_DATA)
        assertEquals(DropRecord.Beacon(Fixtures.BODY), DropRecord.fromManufacturerData(AdvertisingFormat.COMPANY_ID, manufacturer))
        assertNull(DropRecord.fromManufacturerData(0x004C, manufacturer))
        assertNull(DropRecord.fromManufacturerData(AdvertisingFormat.COMPANY_ID, bytes("0102") + body))
        assertNull(DropRecord.fromManufacturerData(AdvertisingFormat.COMPANY_ID, bytes("64")))
        val name = BeaconAdvertisements.nicknamePayload(Fixtures.NICKNAME, BeaconCarrier.SERVICE_DATA)!!
        assertEquals(DropRecord.Nickname(Fixtures.NICKNAME, false), DropRecord.fromServiceData(AdvertisingFormat.SERVICE_UUID_16, name))
    }

    @Test
    fun receivedNicknamesAreSanitised() {
        val record = byteArrayOf(0x81.toByte()) + "\u202Eevil\u0007 name ".encodeToByteArray()
        assertEquals(DropRecord.Nickname("evil name", false), DropRecord.decode(record))
        // Malformed UTF-8 becomes U+FFFD instead of failing the packet.
        val malformed = byteArrayOf(0x81.toByte(), 'a'.code.toByte(), 0xC3.toByte())
        assertEquals(DropRecord.Nickname("a\uFFFD", false), DropRecord.decode(malformed))
        assertIs<DropRecord.Ignored>(DropRecord.decode(byteArrayOf(0x81.toByte(), 0x00, 0x0A)))
    }

    @Test
    fun placeholderIdentifiers() {
        assertEquals(0xFFFF, AdvertisingFormat.PLACEHOLDER_COMPANY_ID)
        assertEquals("0000df01-0000-1000-8000-00805f9b34fb", AdvertisingFormat.SERVICE_UUID_128)
        assertContentEquals(bytes("6472"), "dr".encodeToByteArray())
    }

    @Test
    fun fuzzedRecordsThrowOnlyTheDocumentedException() {
        val random = Random(4242)
        val seeds =
            listOf(
                bytes(Fixtures.SERVICE_DATA_HEX) + bytes(Fixtures.SCAN_RESPONSE_HEX),
                bytes(Fixtures.MANUFACTURER_DATA_WITH_ADDRESS_HEX),
                bytes(Fixtures.SERVICE_DATA_WITH_ADDRESS_HEX) + bytes(Fixtures.SCAN_RESPONSE_SHORTENED_HEX),
            )
        var parsedSome = false
        repeat(30_000) { i ->
            val input =
                if (i % 3 == 0) {
                    random.nextBytes(random.nextInt(0, 64))
                } else {
                    val seed = seeds.random(random).copyOf()
                    repeat(random.nextInt(1, 4)) {
                        when (random.nextInt(3)) {
                            0 -> {
                                seed[random.nextInt(seed.size)] = random.nextInt(256).toByte()
                            }

                            1 -> {
                                seed[random.nextInt(seed.size)] =
                                    (seed[random.nextInt(seed.size)].toInt() xor (1 shl random.nextInt(8))).toByte()
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                    if (random.nextInt(4) == 0) seed.copyOfRange(0, random.nextInt(seed.size)) else seed
                }
            try {
                if (BeaconAdvertisements.parse(input) != null) parsedSome = true
                BeaconSighting.fromAdvertisingData(input, -50, null, 0)
            } catch (e: DiscoveryFormatException) {
                // documented failure
            }
        }
        assertTrue(parsedSome)
        assertFalse(seeds.isEmpty())
    }
}
