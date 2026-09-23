package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.discovery.Fixtures.bytes
import com.constrivo.drop.core.discovery.Fixtures.hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Beacon body codec, architecture §5.1 with S10 (Classic address) and the versioning rule. */
class BeaconBodyTest {
    @Test
    fun fA1_bodyGoldenBytes() {
        assertEquals(Fixtures.BODY_HEX, hex(Fixtures.BODY.encode()))
        assertEquals(BeaconBody.SIZE, Fixtures.BODY.encode().size)
    }

    @Test
    fun s10_bodyWithClassicAddressGoldenBytes() {
        assertEquals(Fixtures.BODY_WITH_ADDRESS_HEX, hex(Fixtures.BODY_WITH_ADDRESS.encode()))
        assertEquals(BeaconBody.SIZE_WITH_CLASSIC_ADDRESS, Fixtures.BODY_WITH_ADDRESS.encodedSize)
    }

    @Test
    fun fA1_goldenBytesDecode() {
        assertEquals(Fixtures.BODY, BeaconBody.decode(bytes(Fixtures.BODY_HEX)))
        assertEquals(Fixtures.BODY_WITH_ADDRESS, BeaconBody.decode(bytes(Fixtures.BODY_WITH_ADDRESS_HEX)))
    }

    @Test
    fun fieldBitPositions() {
        val body = BeaconBody.decode(bytes(Fixtures.BODY_HEX))
        assertEquals(0x2899, body.capabilities.bits)
        // Bit 0 (WIFI_5GHZ) is the least significant bit of byte 8.
        val onlyBit0 =
            BeaconBody(
                Fixtures.EPH,
                Capabilities.of(Capabilities.Flag.WIFI_5GHZ),
                NetworkHint.NONE,
                Visibility.EVERYONE,
                DevicePlatform.PHONE,
            )
        assertEquals("0001", hex(onlyBit0.encode().copyOfRange(7, 9)))
        // Visibility in bits 7–6, platform in bits 5–3.
        for (v in listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY)) {
            for (p in Fixtures.KNOWN_PLATFORMS) {
                val packed = onlyBit0.copy(visibility = v, platform = p).encode()[13].toInt() and 0xFF
                assertEquals(v.code, packed ushr 6)
                assertEquals(p.code, (packed ushr 3) and 7)
                assertEquals(0, packed and 7, "reserved bits are sent as zero")
            }
        }
    }

    @Test
    fun roundTripRandomBodies() {
        val random = Random(20260923)
        repeat(2_000) {
            val body = Fixtures.randomBody(random)
            assertEquals(body, BeaconBody.decode(body.encode()))
        }
    }

    @Test
    fun reservedBitsAreIgnoredOnDecode() {
        val raw = bytes(Fixtures.BODY_HEX)
        raw[13] = (raw[13].toInt() or 0x07).toByte()
        assertEquals(Fixtures.BODY, BeaconBody.decode(raw))
    }

    @Test
    fun s10_zeroAddressFromPhonesReadsAsAbsent() {
        val raw = bytes(Fixtures.BODY_HEX) + ByteArray(6)
        assertNull(BeaconBody.decode(raw).classicAddress)
        val placeholder = bytes(Fixtures.BODY_HEX) + bytes("020000000000")
        assertNull(BeaconBody.decode(placeholder).classicAddress)
    }

    @Test
    fun v1LengthsAreStrict() {
        val raw = bytes(Fixtures.BODY_WITH_ADDRESS_HEX)
        for (n in 0..raw.size + 4) {
            if (n == BeaconBody.SIZE || n == BeaconBody.SIZE_WITH_CLASSIC_ADDRESS) continue
            val candidate = if (n <= raw.size) raw.copyOfRange(0, n) else raw + ByteArray(n - raw.size)
            assertFailsWith<DiscoveryFormatException>("length $n") { BeaconBody.decode(candidate) }
        }
    }

    @Test
    fun laterMinorVersionsKeepTheClassicAddressAndIgnoreFieldsAfterOffset20() {
        for (version in 2..BeaconBody.LAST_COMPATIBLE_VERSION) {
            // S10: a newer desktop's Classic address stays readable to v1 scanners.
            val raw = bytes(Fixtures.BODY_WITH_ADDRESS_HEX) + bytes("0102030405")
            raw[0] = version.toByte()
            assertEquals(Fixtures.BODY_WITH_ADDRESS, BeaconBody.decode(raw))
            assertEquals(Fixtures.BODY_WITH_ADDRESS, BeaconBody.decode(raw.copyOfRange(0, BeaconBody.SIZE_WITH_CLASSIC_ADDRESS)))
            // New fields without an address: six zero bytes hold the address slot.
            val noAddress = bytes(Fixtures.BODY_HEX) + ByteArray(6) + bytes("0a0b")
            noAddress[0] = version.toByte()
            assertEquals(Fixtures.BODY, BeaconBody.decode(noAddress))
            val minimal = bytes(Fixtures.BODY_HEX).also { it[0] = version.toByte() }
            assertEquals(Fixtures.BODY, BeaconBody.decode(minimal))
            assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(minimal.copyOfRange(0, 13)) }
            // Lengths 15–19 would cut the address slot: malformed in every version.
            for (n in BeaconBody.SIZE + 1 until BeaconBody.SIZE_WITH_CLASSIC_ADDRESS) {
                assertFailsWith<DiscoveryFormatException>("v$version, $n bytes") { BeaconBody.decode(raw.copyOfRange(0, n)) }
            }
        }
    }

    @Test
    fun incompatibleLayoutsAreRejectedSafely() {
        for (version in 0x10..0x7F) {
            val raw = bytes(Fixtures.BODY_HEX).also { it[0] = version.toByte() }
            val e = assertFailsWith<UnsupportedBeaconVersionException> { BeaconBody.decode(raw) }
            assertEquals(version, e.version)
        }
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(bytes(Fixtures.BODY_HEX).also { it[0] = 0 }) }
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(bytes(Fixtures.BODY_HEX).also { it[0] = 0x81.toByte() }) }
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(ByteArray(0)) }
    }

    @Test
    fun fA5_hiddenNeverAdvertises() {
        assertFailsWith<IllegalArgumentException> {
            BeaconBody(Fixtures.EPH, Capabilities.NONE, NetworkHint.NONE, Visibility.HIDDEN, DevicePlatform.PHONE)
        }
        val raw = bytes(Fixtures.BODY_HEX)
        raw[13] = (raw[13].toInt() or 0xC0).toByte()
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(raw) }
    }

    @Test
    fun n6_connectedBitMustAgreeWithTheHint() {
        val connected = Capabilities.of(Capabilities.Flag.CONNECTED_TO_WIFI)
        assertFailsWith<IllegalArgumentException> {
            BeaconBody(Fixtures.EPH, connected, NetworkHint.NONE, Visibility.EVERYONE, DevicePlatform.PHONE)
        }
        assertFailsWith<IllegalArgumentException> {
            BeaconBody(Fixtures.EPH, Capabilities.NONE, Fixtures.HINT, Visibility.EVERYONE, DevicePlatform.PHONE)
        }
        assertFailsWith<IllegalArgumentException> {
            BeaconBody(
                Fixtures.EPH,
                Capabilities.of(Capabilities.Flag.STATION_ON_5GHZ),
                NetworkHint.NONE,
                Visibility.EVERYONE,
                DevicePlatform.PHONE,
            )
        }
        // The same rules on the wire: clear bit 11 (0x0800) in the golden body, keeping the hint.
        val raw = bytes(Fixtures.BODY_HEX)
        raw[7] = (raw[7].toInt() and 0xF7).toByte()
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(raw) }
        // Zero the hint, keeping bit 11.
        val zeroHint = bytes(Fixtures.BODY_HEX).also { for (i in 9 until 13) it[i] = 0 }
        assertFailsWith<DiscoveryFormatException> { BeaconBody.decode(zeroHint) }
    }

    @Test
    fun unknownPlatformCodesDecodeAsUnknownInEveryCompatibleVersion() {
        for (version in listOf(1, 2, BeaconBody.LAST_COMPATIBLE_VERSION)) {
            for (code in 4..7) {
                val raw = bytes(Fixtures.BODY_HEX)
                raw[0] = version.toByte()
                raw[13] = ((raw[13].toInt() and 0xC7) or (code shl 3)).toByte()
                val decoded = BeaconBody.decode(raw)
                assertEquals(DevicePlatform.UNKNOWN, decoded.platform, "v$version code $code")
                assertEquals(Fixtures.BODY.copy(platform = DevicePlatform.UNKNOWN), decoded)
                // Only decoding produces it: an unknown platform is never sent.
                assertFailsWith<IllegalArgumentException> { decoded.encode() }
            }
        }
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromCode(5))
        assertEquals(DevicePlatform.DESKTOP, DevicePlatform.fromCode(2))
        assertFailsWith<IllegalArgumentException> { DevicePlatform.fromCode(8) }
    }

    @Test
    fun unusableClassicAddressIsRefusedWhenEncoding() {
        for (bad in listOf(0L, BluetoothAddress.MAX_VALUE, 0x020000000000L)) {
            assertFailsWith<IllegalArgumentException> { Fixtures.BODY.copy(classicAddress = BluetoothAddress(bad)) }
        }
    }

    @Test
    fun decodingRandomBytesThrowsOnlyTheDocumentedException() {
        val random = Random(7)
        repeat(20_000) {
            val raw = random.nextBytes(random.nextInt(0, 40))
            try {
                BeaconBody.decode(raw)
            } catch (e: DiscoveryFormatException) {
                // expected for most inputs
            }
        }
    }
}
