package com.constrivo.drop.core.discovery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** mDNS / DNS-SD record, architecture §5.4 with N4 (F‑A3). */
class MdnsRecordTest {
    private val record =
        MdnsRecord(
            ephemeralId = Fixtures.EPH,
            capabilities = Fixtures.CAPS,
            platform = DevicePlatform.LAPTOP,
            nickname = Fixtures.NICKNAME,
            controlPort = 49152,
            visibility = Visibility.EVERYONE_TEN_MINUTES,
        )

    private val golden =
        linkedMapOf(
            "v" to "1",
            "eph" to "0123456789ab",
            "cap" to "2899",
            "plat" to "laptop",
            "nick" to "Anna's Pixel",
            "port" to "49152",
            "vis" to "1",
        )

    @Test
    fun fA3_txtGolden() {
        val txt = record.toTxt()
        assertEquals(golden, txt)
        assertEquals(golden.keys.toList(), txt.keys.toList(), "documented key order")
        assertEquals("drop-0123456789ab", record.instanceName)
    }

    @Test
    fun n4_noPermanentIdentifierIsPublished() {
        val txt = record.toTxt()
        assertFalse("id" in txt)
        assertFalse(record.instanceName.contains("Anna"))
        val trustedOnly = record.copy(nickname = null, visibility = Visibility.TRUSTED_ONLY)
        assertFalse(MdnsRecord.KEY_NICKNAME in trustedOnly.toTxt())
        assertFailsWith<IllegalArgumentException> { record.copy(visibility = Visibility.TRUSTED_ONLY) }
    }

    @Test
    fun fA3_roundTrip() {
        assertEquals(record, MdnsRecord.fromTxt(golden))
        val random = Random(54)
        repeat(1_000) {
            val visibility = listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY).random(random)
            val r =
                MdnsRecord(
                    ephemeralId = EphemeralId(random.nextLong(0, EphemeralId.MAX_VALUE + 1)),
                    capabilities = Capabilities(random.nextInt(0x10000)),
                    platform = DevicePlatform.entries.random(random),
                    nickname =
                        if (visibility ==
                            Visibility.TRUSTED_ONLY
                        ) {
                            null
                        } else {
                            Nicknames.normalize(Fixtures.randomNickname(random))?.text
                        },
                    controlPort = random.nextInt(1, 65536),
                    visibility = visibility,
                )
            assertEquals(r, MdnsRecord.fromTxt(r.toTxt()))
            val service = r.toLanService("192.168.1.20")
            assertEquals(r.controlPort, service.port)
            assertEquals(r, MdnsRecord.fromTxt(service.txt))
        }
    }

    @Test
    fun keysAreCaseInsensitiveAndUnknownKeysIgnored() {
        val txt = golden.mapKeys { it.key.uppercase() } + mapOf("future" to "x", "id" to "00112233445566778899aabbccddeeff")
        assertEquals(record, MdnsRecord.fromTxt(txt))
        assertFailsWith<DiscoveryFormatException> { MdnsRecord.fromTxt(golden + mapOf("EPH" to "0123456789ab")) }
    }

    @Test
    fun hexAndDecimalFormatsAreValidated() {
        val bad =
            listOf(
                "v" to "0",
                "v" to "01",
                "v" to "x",
                "v" to "",
                "v" to "256",
                "eph" to "0123456789a",
                "eph" to "0123456789abc",
                "eph" to "0123456789ag",
                "eph" to "",
                "cap" to "289",
                "cap" to "28999",
                "cap" to "zz99",
                "cap" to "-289",
                "plat" to "tablet",
                "plat" to "",
                "port" to "0",
                "port" to "65536",
                "port" to "080",
                "port" to "+80",
                "port" to "8o",
                "port" to "",
                "vis" to "3",
                "vis" to "9",
                "vis" to "",
                "vis" to "01",
            )
        for ((key, value) in bad) {
            assertFailsWith<DiscoveryFormatException>("$key=$value") { MdnsRecord.fromTxt(golden + (key to value)) }
        }
        for (missing in listOf("v", "eph", "cap", "plat", "port")) {
            assertFailsWith<DiscoveryFormatException>("missing $missing") { MdnsRecord.fromTxt(golden - missing) }
        }
        assertEquals(Visibility.EVERYONE, MdnsRecord.fromTxt(golden - "vis").visibility)
        assertNull(MdnsRecord.fromTxt(golden - "nick").nickname)
        assertEquals(record, MdnsRecord.fromTxt(golden + ("v" to "2")), "later versions keep the v1 keys")
    }

    @Test
    fun sizeLimitsOfDnsSdTxtRecords() {
        assertFailsWith<DiscoveryFormatException> { MdnsRecord.fromTxt(golden + ("x" to "y".repeat(254))) }
        MdnsRecord.fromTxt(golden + ("x" to "y".repeat(253)))
        val many = (0 until 60).associate { "k$it" to "v".repeat(20) }
        assertFailsWith<DiscoveryFormatException> { MdnsRecord.fromTxt(golden + many) }
        for (badKey in listOf("", "a=b", "café", "tab\t")) {
            assertFailsWith<DiscoveryFormatException>(badKey) { MdnsRecord.fromTxt(golden + (badKey to "1")) }
        }
    }

    @Test
    fun receivedNicknamesAreSanitisedAndBounded() {
        assertEquals("evil", MdnsRecord.fromTxt(golden + ("nick" to "\u202Eevil\n")).nickname)
        val long = MdnsRecord.fromTxt(golden + ("nick" to "漢".repeat(40))).nickname!!
        assertEquals(21, long.length)
        assertTrue(Nicknames.utf8Length(long) <= Nicknames.MAX_BYTES)
        assertNull(MdnsRecord.fromTxt(golden + ("nick" to " \u0000 ")).nickname)
        // A Trusted-only record never yields a nickname, whatever it carries.
        assertNull(MdnsRecord.fromTxt(golden + ("vis" to "2")).nickname)
    }

    @Test
    fun fA5_hiddenIsNeverAnnounced() {
        assertFailsWith<IllegalArgumentException> { record.copy(visibility = Visibility.HIDDEN) }
        assertFailsWith<IllegalArgumentException> { record.copy(controlPort = 0) }
        assertFailsWith<IllegalArgumentException> { record.copy(nickname = "") }
        assertFailsWith<IllegalArgumentException> { record.copy(nickname = "a\nb") }
        assertFailsWith<IllegalArgumentException> { record.copy(nickname = "x".repeat(65)) }
    }

    @Test
    fun fuzzedTxtRecordsThrowOnlyTheDocumentedException() {
        val random = Random(5)
        val alphabet = "0123456789abcdefABCDEF xyz-=.é\u0000"
        repeat(10_000) {
            val txt = golden.toMutableMap()
            repeat(random.nextInt(1, 4)) {
                val key = (golden.keys + listOf("V", "Eph", "zz", "")).random(random)
                val value = buildString { repeat(random.nextInt(0, 16)) { append(alphabet.random(random)) } }
                if (random.nextInt(5) == 0) txt.remove(key) else txt[key] = value
            }
            try {
                MdnsRecord.fromTxt(txt)
            } catch (e: DiscoveryFormatException) {
                // documented failure
            }
        }
    }
}
