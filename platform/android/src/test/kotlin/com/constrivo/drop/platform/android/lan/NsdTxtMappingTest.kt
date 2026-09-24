package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.DiscoveryFormatException
import com.constrivo.drop.core.discovery.EphemeralId
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.core.discovery.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mDNS TXT record (§5.4, F-A3) as `NsdServiceInfo` attributes, both ways. */
class NsdTxtMappingTest {
    private val record =
        MdnsRecord(EphemeralId.parseHex("0123456789ab"), Capabilities(0x002b), DevicePlatform.PHONE, "Asha", 40404, Visibility.EVERYONE)

    @Test
    fun aRecordSurvivesTheRoundTripThroughAttributes() {
        val attributes = NsdTxtMapping.toAttributes(record.toTxt())
        assertEquals(record.toTxt(), attributes)
        val bytes = attributes.mapValues { it.value.encodeToByteArray() }
        assertEquals(record, MdnsRecord.fromTxt(NsdTxtMapping.fromAttributes(bytes)))
    }

    @Test
    fun theRulesOfNsdServiceInfoAreCheckedFirst() {
        val bad =
            listOf(
                mapOf("" to "x"),
                mapOf("k=y" to "x"),
                mapOf("kéy" to "x"),
                mapOf("tab\t" to "x"),
                mapOf("v" to "1", "V" to "2"),
                mapOf("nick" to "x".repeat(251)),
            )
        for (txt in bad) {
            assertEquals(NsdError.BAD_PARAMETERS, assertFailsWith<LanDiscoveryException>("$txt") { NsdTxtMapping.toAttributes(txt) }.error)
        }
        // key + value just below 255 bytes passes, and so does a record of exactly 1300 bytes.
        NsdTxtMapping.toAttributes(mapOf("nick" to "x".repeat(250)))
        val entries = (0 until 5).associate { "k$it" to "x".repeat(252) } + ("z" to "x".repeat(17))
        assertEquals(1300, entries.entries.sumOf { it.key.length + it.value.length + 2 })
        NsdTxtMapping.toAttributes(entries)
        assertFailsWith<LanDiscoveryException> { NsdTxtMapping.toAttributes(entries + ("y" to "")) }
    }

    @Test
    fun browsedAttributesAreDecodedStrictly() {
        val txt =
            NsdTxtMapping.fromAttributes(
                linkedMapOf(
                    "v" to "1".encodeToByteArray(),
                    "flag" to null,
                    "bad" to byteArrayOf(0xC3.toByte()),
                    "nick" to "Näher".encodeToByteArray(),
                    "" to "x".encodeToByteArray(),
                ),
            )
        assertEquals(mapOf("v" to "1", "nick" to "Näher"), txt)
    }

    @Test
    fun aRecordWithAnUndecodableKeyFailsInTheRecordsOwnValidation() {
        val attributes = record.toTxt().mapValues { it.value.encodeToByteArray() } + ("eph" to byteArrayOf(0xFF.toByte()))
        assertFailsWith<DiscoveryFormatException> { MdnsRecord.fromTxt(NsdTxtMapping.fromAttributes(attributes)) }
    }

    @Test
    fun serviceTypesAreComparedAsNsdReportsThem() {
        for (type in listOf(
            "_drop._tcp",
            "_drop._tcp.",
            "_drop._tcp.local.",
            "_DROP._TCP.",
            " _drop._tcp. ",
        )) {
            assertTrue(NsdTxtMapping.isOwnServiceType(type), type)
        }
        for (type in listOf(
            null,
            "",
            "_other._tcp.",
            "_drop._udp.",
            "_drop._tcp.example.",
        )) {
            assertFalse(NsdTxtMapping.isOwnServiceType(type), "$type")
        }
    }
}
