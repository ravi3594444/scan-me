package com.constrivo.drop.web.mdns

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wire vectors: queries as Avahi (nss-mdns), macOS mDNSResponder, Windows and `dig` send them, and a DNS-SD response
 * with name compression. Every vector was also parsed by an independent implementation (dnspython 2.8).
 */
class DnsCodecTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private val drop = DnsName.of("drop.local")

    @Test
    fun avahiQuery() {
        val m = DnsCodec.decode(hex(AVAHI_QUERY_A))
        assertEquals(0, m.id)
        assertFalse(m.isResponse)
        assertEquals(listOf(DnsQuestion(drop, DnsType.A, DnsClass.IN, false)), m.questions)
        assertTrue(m.answers.isEmpty() && m.additionals.isEmpty())
        assertEquals(AVAHI_QUERY_A, DnsCodec.encode(m).toHex())
    }

    @Test
    fun macosQueryWithQuBitsCompressionAndOwnerOption() {
        val m = DnsCodec.decode(hex(MACOS_QUERY_A_AAAA_QU))
        assertEquals(
            listOf(DnsQuestion(drop, DnsType.A, DnsClass.IN, true), DnsQuestion(drop, DnsType.AAAA, DnsClass.IN, true)),
            m.questions,
        )
        val opt = m.additionals.single()
        assertEquals(DnsType.OPT, opt.type)
        assertEquals(DnsName.ROOT, opt.name)
        assertEquals(1440, opt.rclass, "OPT keeps the UDP payload size in the class field")
        assertFalse(opt.cacheFlush)
        assertEquals(0x1194L, opt.ttl)
        assertEquals(18, (opt.data as DnsRData.Raw).bytes.size)
        assertEquals(MACOS_QUERY_A_AAAA_QU, DnsCodec.encode(m).toHex(), "re-encoding compresses the same way")
    }

    @Test
    fun digLegacyUnicastQuery() {
        val m = DnsCodec.decode(hex(DIG_LEGACY_QUERY))
        assertEquals(0x9e4f, m.id)
        assertEquals(0x0120, m.flags)
        assertEquals(0, m.opcode)
        assertEquals(DnsQuestion(drop, DnsType.A), m.questions.single())
        assertEquals(1232, m.additionals.single().rclass)
        assertEquals(DIG_LEGACY_QUERY, DnsCodec.encode(m).toHex())
    }

    @Test
    fun windowsAaaaQuery() {
        val m = DnsCodec.decode(hex(WINDOWS_QUERY_AAAA))
        assertEquals(DnsQuestion(drop, DnsType.AAAA), m.questions.single())
    }

    @Test
    fun dnsSdResponseWithCompressedNames() {
        val m = DnsCodec.decode(hex(DNSSD_RESPONSE))
        assertTrue(m.isResponse)
        assertEquals(0x8400, m.flags)
        val ptr = m.answers.single()
        assertEquals("_ipp._tcp.local", ptr.name.toString())
        assertEquals(DnsType.PTR, ptr.type)
        assertEquals(4500L, ptr.ttl)
        assertEquals(
            listOf(
                "Office Printer._ipp._tcp.local" to DnsType.SRV,
                "Office Printer._ipp._tcp.local" to DnsType.TXT,
                "printer.local" to DnsType.A,
            ),
            m.additionals.map { it.name.toString() to it.type },
        )
        val a = m.additionals.last()
        assertEquals("192.168.1.20", a.data.toString())
        assertFalse(a.cacheFlush)
    }

    @Test
    fun ourReplyMatchesTheGoldenBytes() {
        val reply =
            DnsMessage(
                0,
                0x8400,
                answers =
                    listOf(
                        DnsRecord(drop, DnsType.A, DnsClass.IN, true, 120, DnsRData.A(byteArrayOf(192.toByte(), 168.toByte(), 49, 1))),
                    ),
                additionals = listOf(DnsRecord(drop, DnsType.NSEC, DnsClass.IN, true, 120, DnsRData.Nsec(drop, setOf(DnsType.A)))),
            )
        // dnspython reads this as "drop.local. 120 A 192.168.49.1" and "drop.local. 120 NSEC drop.local. A".
        assertEquals(GOLDEN_REPLY, DnsCodec.encode(reply).toHex())
        assertEquals(reply, DnsCodec.decode(hex(GOLDEN_REPLY)))
    }

    @Test
    fun nsecBitmapsAcrossWindowsRoundTrip() {
        val types = setOf(DnsType.A, DnsType.AAAA, DnsType.NSEC, DnsType.ANY, 256, 1234, 65535)
        val record = DnsRecord(drop, DnsType.NSEC, DnsClass.IN, false, 1, DnsRData.Nsec(drop, types))
        val bytes = DnsCodec.encode(DnsMessage(0, 0x8400, answers = listOf(record)))
        val back = DnsCodec.decode(bytes).answers.single().data
        assertIs<DnsRData.Nsec>(back)
        assertEquals(types, back.types)
    }

    @Test
    fun namesCompareCaseInsensitivelyButKeepTheirSpelling() {
        val m = DnsCodec.decode(hex(AVAHI_QUERY_A.replace("64726f70", "44524f50")))
        val name = m.questions.single().name
        assertEquals("DROP.local", name.toString())
        assertTrue(name.matches(drop))
        assertFalse(name.matches(DnsName.of("drop.local.local")))
        assertFalse(DnsName.of("dróp.local").matches(drop))
    }

    @Test
    fun nameValidation() {
        assertEquals(DnsName.ROOT, DnsName.of("."))
        assertEquals(listOf("drop", "local"), DnsName.of("drop.local.").labels)
        assertFailsWith<IllegalArgumentException> { DnsName.of("a..b") }
        assertFailsWith<IllegalArgumentException> { DnsName.of("x".repeat(64) + ".local") }
        assertFailsWith<IllegalArgumentException> { DnsName.of(List(5) { "y".repeat(60) }.joinToString(".")) }
    }

    @Test
    fun malformedPacketsRaiseDnsFormatException() {
        val cases =
            mapOf(
                "empty" to "",
                "short header" to "0000000000010000000000",
                "question count too high" to "000000000005000000000000",
                "truncated name" to "0000000000010000000000000464726f",
                "label runs past the end" to "0000000000010000000000000964726f70",
                "missing type and class" to "0000000000010000000000000464726f70056c6f63616c00",
                "reserved label type 0x40" to "0000000000010000000000004064726f70056c6f63616c0000010001",
                "reserved label type 0x80" to "0000000000010000000000008064726f70056c6f63616c0000010001",
                "pointer to itself" to "000000000001000000000000c00c00010001",
                "forward pointer" to "000000000001000000000000c00e0000010001",
                "pointer loop" to "0000000000010000000000000164c00cc00e00010001",
                "truncated pointer" to "000000000001000000000000c0",
                "A record with 3 bytes" to "0000840000000001000000000464726f70056c6f63616c0000010001000000780003c0a831",
                "rdata past the end" to "0000840000000001000000000464726f70056c6f63616c0000010001000000780010c0a83101",
                "NSEC bitmap too long" to "0000840000000001000000000464726f70056c6f63616c00002f80010000007800" +
                    "0f" + "0464726f70056c6f63616c00" + "0021" + "40",
                "NSEC windows out of order" to "0000840000000001000000000464726f70056c6f63616c00002f80010000007800" +
                    "12" + "0464726f70056c6f63616c00" + "010140" + "000140",
                "NSEC empty window" to "0000840000000001000000000464726f70056c6f63616c00002f80010000007800" +
                    "0e" + "0464726f70056c6f63616c00" + "0000",
            )
        for ((what, bytes) in cases) {
            assertFailsWith<DnsFormatException>(what) { DnsCodec.decode(hex(bytes)) }
        }
    }

    @Test
    fun nameLongerThan255BytesIsRefused() {
        // 5 labels of 63 bytes: 320 bytes on the wire.
        val labels = (1..5).joinToString("") { "3f" + "61".repeat(63) }
        assertFailsWith<DnsFormatException> { DnsCodec.decode(hex("000000000001000000000000" + labels + "0000010001")) }
    }

    @Test
    fun malformedUtf8LabelsDecodeWithoutThrowing() {
        val m = DnsCodec.decode(hex("00000000000100000000000002ff fe00 0001 0001".replace(" ", "")))
        assertEquals(1, m.questions.single().name.labels.size)
    }

    @Test
    fun trailingBytesAreIgnored() {
        assertEquals(1, DnsCodec.decode(hex(AVAHI_QUERY_A + "deadbeef")).questions.size)
    }

    @Test
    fun decodesASliceOfALargerBuffer() {
        val packet = hex(AVAHI_QUERY_A)
        val buffer = ByteArray(7) + packet + ByteArray(5)
        assertEquals(DnsCodec.decode(packet), DnsCodec.decode(buffer, 7, packet.size))
        assertFailsWith<DnsFormatException> { DnsCodec.decode(buffer, 10, buffer.size) }
        assertFailsWith<DnsFormatException> { DnsCodec.decode(buffer, -1, 3) }
    }

    @Test
    fun encoderRejectsNamesThatDoNotFit() {
        // A decoded label of 63 invalid bytes becomes 63 replacement characters (189 bytes) and cannot be re-encoded.
        val m = DnsCodec.decode(hex("0000000000010000000000003f" + "ff".repeat(63) + "0000010001"))
        assertFailsWith<IllegalArgumentException> { DnsCodec.encode(m) }
    }

    @Test
    fun randomAndMutatedPacketsNeverThrowAnythingElse() {
        val random = Random(20260923)
        val seeds =
            listOf(AVAHI_QUERY_A, MACOS_QUERY_A_AAAA_QU, DIG_LEGACY_QUERY, WINDOWS_QUERY_AAAA, DNSSD_RESPONSE, GOLDEN_REPLY).map {
                hex(it)
            }
        var decoded = 0
        repeat(50_000) { i ->
            val bytes =
                if (i % 5 == 0) {
                    random.nextBytes(random.nextInt(0, 128))
                } else {
                    val base = seeds[random.nextInt(seeds.size)].copyOf()
                    repeat(random.nextInt(1, 6)) { base[random.nextInt(base.size)] = random.nextInt(256).toByte() }
                    if (random.nextInt(4) == 0) base.copyOf(random.nextInt(base.size + 1)) else base
                }
            try {
                val m = DnsCodec.decode(bytes)
                decoded++
                // Whatever decodes must also answer without throwing.
                MdnsHostAnswerer(drop, java.net.Inet4Address.getByName("192.168.49.1") as java.net.Inet4Address).answer(m, 5353)
            } catch (_: DnsFormatException) {
                // Expected for most inputs.
            }
        }
        assertTrue(decoded > 1000, "mutations should still decode sometimes ($decoded)")
    }

    companion object {
        const val AVAHI_QUERY_A = "0000000000010000000000000464726f70056c6f63616c0000010001"
        const val MACOS_QUERY_A_AAAA_QU =
            "0000000000020000000000010464726f70056c6f63616c0000018001c00c001c8001" +
                "00002905a00000119400120004000e0005a4c3f0112233a4c3f0112233"
        const val DIG_LEGACY_QUERY =
            "9e4f012000010000000000010464726f70056c6f63616c000001000100002904d000000000000c000a00081d2c3b4a59687786"
        const val WINDOWS_QUERY_AAAA = "0000000000010000000000000464726f70056c6f63616c00001c0001"
        const val DNSSD_RESPONSE =
            "000084000000000100000003045f697070045f746370056c6f63616c00000c00010000119400110e4f6666696365205072696e746572c00c" +
                "c02700210001000000780010000000000277077072696e746572c016c0270010000100001194001709747874766572733d310c72703d69" +
                "70702f7072696e74c04a00010001000000780004c0a80114"
        const val GOLDEN_REPLY =
            "000084000000000100000001" + "0464726f70056c6f63616c00" + "0001" + "8001" + "00000078" + "0004" + "c0a83101" +
                "c00c" + "002f" + "8001" + "00000078" + "000f" + "0464726f70056c6f63616c00" + "000140"
    }
}
