package com.constrivo.drop.web.mdns

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdnsHostAnswererTest {
    private val drop = DnsName.of("drop.local")
    private val address = InetAddress.getByName("192.168.49.1") as Inet4Address
    private val answerer = MdnsHostAnswerer(drop, address)

    private fun query(
        vararg questions: DnsQuestion,
        id: Int = 0,
        flags: Int = 0,
        knownAnswers: List<DnsRecord> = emptyList(),
    ) = DnsMessage(id, flags, questions.toList(), knownAnswers)

    private fun aRecord(
        ttl: Long,
        addressBytes: ByteArray = address.address,
    ) = DnsRecord(drop, DnsType.A, DnsClass.IN, true, ttl, DnsRData.A(addressBytes))

    @Test
    fun answersAnAQueryByMulticastWithCacheFlushAndNsec() {
        val reply = assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.A)), 5353))
        assertFalse(reply.unicast)
        val m = reply.message
        assertEquals(0, m.id)
        assertEquals(0x8400, m.flags)
        assertTrue(m.questions.isEmpty(), "multicast responses carry no questions")
        val a = m.answers.single()
        assertEquals(DnsType.A, a.type)
        assertTrue(a.cacheFlush)
        assertEquals(120L, a.ttl)
        assertEquals("192.168.49.1", a.data.toString())
        val nsec = m.additionals.single()
        assertEquals(DnsRData.Nsec(drop, setOf(DnsType.A)), nsec.data)
    }

    @Test
    fun theWholeReplyEncodesToTheGoldenBytes() {
        val reply = assertNotNull(answerer.answer(DnsCodec.decode(hex(DnsCodecTest.AVAHI_QUERY_A)), 5353))
        assertEquals(DnsCodecTest.GOLDEN_REPLY, DnsCodec.encode(reply.message).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun matchesTheNameIgnoringCaseAndAcceptsAnyTypeOrClass() {
        assertNotNull(answerer.answer(query(DnsQuestion(DnsName.of("DROP.Local"), DnsType.A)), 5353))
        assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.ANY)), 5353))
        assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.A, DnsClass.ANY)), 5353))
    }

    @Test
    fun aaaaGetsANegativeAnswerWithTheAddressAsAdditionalData() {
        val m = assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.AAAA)), 5353)).message
        assertEquals(DnsType.NSEC, m.answers.single().type)
        assertEquals(DnsType.A, m.additionals.single().type)
    }

    @Test
    fun aAndAaaaTogether() {
        val m = assertNotNull(answerer.answer(DnsCodec.decode(hex(DnsCodecTest.MACOS_QUERY_A_AAAA_QU)), 5353))
        assertTrue(m.unicast, "every answered question asked for a unicast reply")
        assertEquals(listOf(DnsType.A, DnsType.NSEC), m.message.answers.map { it.type })
        assertTrue(m.message.additionals.isEmpty())
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(answerer.answer(query(DnsQuestion(DnsName.of("other.local"), DnsType.A)), 5353))
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.TXT)), 5353))
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.PTR)), 5353))
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A, qclass = 3)), 5353), "class CH")
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), flags = 0x8400), 5353), "a response, not a query")
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), flags = 0x2000), 5353), "opcode UPDATE")
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), flags = 0x0003), 5353), "non-zero rcode")
        assertNull(answerer.answer(query(), 5353))
    }

    @Test
    fun knownAnswerSuppression() {
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), knownAnswers = listOf(aRecord(100))), 5353))
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), knownAnswers = listOf(aRecord(60))), 5353))
        // Less than half the TTL left, or another address: answer anyway.
        assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), knownAnswers = listOf(aRecord(59))), 5353))
        assertNotNull(
            answerer.answer(query(DnsQuestion(drop, DnsType.A), knownAnswers = listOf(aRecord(120, byteArrayOf(10, 0, 0, 1)))), 5353),
        )
        // The negative answer for AAAA is still sent.
        val m =
            assertNotNull(
                answerer.answer(
                    query(DnsQuestion(drop, DnsType.A), DnsQuestion(drop, DnsType.AAAA), knownAnswers = listOf(aRecord(120))),
                    5353,
                ),
            )
        assertEquals(listOf(DnsType.NSEC), m.message.answers.map { it.type })
    }

    @Test
    fun legacyUnicastQueriesGetTheirIdAndQuestionBack() {
        val reply = assertNotNull(answerer.answer(DnsCodec.decode(hex(DnsCodecTest.DIG_LEGACY_QUERY)), 53_211))
        assertTrue(reply.unicast)
        val m = reply.message
        assertEquals(0x9e4f, m.id)
        assertEquals(DnsQuestion(drop, DnsType.A), m.questions.single())
        val a = m.answers.single()
        assertFalse(a.cacheFlush, "no cache-flush bit toward a legacy resolver")
        assertEquals(10L, a.ttl)
        assertTrue(m.additionals.isEmpty())
        // Legacy AAAA queries are not answered.
        assertNull(answerer.answer(query(DnsQuestion(drop, DnsType.AAAA), id = 7), 40_000))
        // Known answers do not suppress a legacy reply.
        assertNotNull(answerer.answer(query(DnsQuestion(drop, DnsType.A), knownAnswers = listOf(aRecord(120))), 40_000))
    }

    @Test
    fun mixedQuQmQuestionsAreMulticast() {
        val reply =
            assertNotNull(
                answerer.answer(query(DnsQuestion(drop, DnsType.A, unicastResponse = true), DnsQuestion(drop, DnsType.AAAA)), 5353),
            )
        assertFalse(reply.unicast)
    }

    @Test
    fun announcementAndGoodbye() {
        val announce = answerer.announcement()
        assertEquals(120L, announce.answers.single().ttl)
        assertEquals(DnsType.NSEC, announce.additionals.single().type)
        assertEquals(0L, answerer.goodbye().answers.single().ttl)
    }

    @Test
    fun onLinkFilter() {
        assertTrue(MdnsResponder.samePrefix(byteArrayOf(192.toByte(), 168.toByte(), 49, 7), address.address, 24))
        assertFalse(MdnsResponder.samePrefix(byteArrayOf(192.toByte(), 168.toByte(), 50, 7), address.address, 24))
        assertTrue(MdnsResponder.samePrefix(byteArrayOf(192.toByte(), 168.toByte(), 49, 7), address.address, 23))
        assertTrue(MdnsResponder.samePrefix(byteArrayOf(192.toByte(), 168.toByte(), 48, 7), address.address, 23))
        assertFalse(MdnsResponder.samePrefix(byteArrayOf(192.toByte(), 168.toByte(), 50, 7), address.address, 23))
        assertTrue(MdnsResponder.samePrefix(byteArrayOf(1, 2, 3, 4), address.address, 0))
        assertFalse(MdnsResponder.samePrefix(byteArrayOf(1, 2, 3, 4), address.address, 32))
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
