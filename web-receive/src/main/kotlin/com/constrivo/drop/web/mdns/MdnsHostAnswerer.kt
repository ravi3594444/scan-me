package com.constrivo.drop.web.mdns

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import java.net.Inet4Address

/**
 * The decision half of the mDNS responder (spec change N15): which queries for [hostName] get which answer. Free of
 * sockets, so it is tested on its own with a fake [clock]; [MdnsResponder] feeds it packets.
 *
 * Rules (RFC 6762, reduced to one host name with one IPv4 address):
 * - only queries are answered (QR = 0) with opcode 0 and response code 0 (§18.3, §18.11); everything else is dropped;
 * - a question for [hostName] (ASCII case-insensitive) of type A or ANY and class IN or ANY gets the A record for
 *   [address], with the cache-flush bit, since the name is unique on the link (§10.2), and an NSEC record in the
 *   additional section saying that no other type exists (§6.1), so resolvers stop waiting for AAAA;
 * - a question for [hostName] of type AAAA gets that NSEC as the answer and the A record as additional data (§6.1,
 *   §6.2); other types and other names are ignored;
 * - known-answer suppression (§7.1): no A answer when the query already lists our A record with at least half its TTL;
 * - a query from a port other than [mdnsPort] is a legacy unicast query (§6.7): the reply echoes the id and the
 *   questions, goes back to the sender, carries a TTL of at most 10 s and no cache-flush bits and no NSEC;
 * - a query whose answered questions all have the QU bit gets a unicast reply to the sender (§5.4); otherwise the reply
 *   is multicast with id 0 and no questions (§18.1, §6);
 * - a record is multicast at most once per [MIN_MULTICAST_INTERVAL_MILLIS] (§6, the announcement included): a
 *   multicast reply leaves out records sent less than a second ago and is dropped when no answer is left, so a
 *   device that queries in a loop cannot make the phone flood the group. Unicast replies are not limited.
 */
class MdnsHostAnswerer(
    val hostName: DnsName,
    address: Inet4Address,
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val mdnsPort: Int = MdnsResponder.MDNS_PORT,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    private val addressBytes: ByteArray = address.address

    /** Record type to the [clock] time it was last multicast. */
    private val lastMulticast = HashMap<Int, Long>()

    init {
        require(ttlSeconds in 1..0x7FFFFFFFL) { "ttl must be positive" }
        require(hostName.labels.isNotEmpty()) { "host name must not be the root" }
    }

    /** A reply and where it goes: to the multicast group, or back to the querier. */
    data class Reply(
        val message: DnsMessage,
        val unicast: Boolean,
    )

    /**
     * The reply to [query] received from [sourcePort], or null when it asks nothing we answer (or every answer was
     * multicast less than a second ago). A multicast reply counts as sent when this returns it.
     */
    @Synchronized
    fun answer(
        query: DnsMessage,
        sourcePort: Int,
    ): Reply? {
        if (query.isResponse || query.opcode != 0 || query.responseCode != 0) return null
        val legacy = sourcePort != mdnsPort
        var wantA = false
        var wantNsec = false
        val answered = ArrayList<DnsQuestion>()
        for (q in query.questions) {
            if (!q.name.matches(hostName) || (q.qclass != DnsClass.IN && q.qclass != DnsClass.ANY)) continue
            when (q.type) {
                DnsType.A, DnsType.ANY -> wantA = true
                DnsType.AAAA -> wantNsec = true
                else -> continue
            }
            answered += q
        }
        if (answered.isEmpty()) return null
        if (!legacy && wantA && knownAnswerSuppresses(query)) wantA = false
        if (!wantA && (!wantNsec || legacy)) return null

        val ttl = if (legacy) minOf(ttlSeconds, LEGACY_TTL_SECONDS) else ttlSeconds
        val flush = !legacy
        val a = aRecord(ttl, flush)
        val answers = ArrayList<DnsRecord>()
        val additionals = ArrayList<DnsRecord>()
        if (wantA) answers += a
        if (!legacy) {
            val nsec = nsecRecord(ttl)
            if (wantNsec) answers += nsec else additionals += nsec
            if (!wantA) additionals += a
        }
        val flags = DnsMessage.FLAG_RESPONSE or DnsMessage.FLAG_AUTHORITATIVE
        if (legacy) {
            return Reply(
                DnsMessage(
                    query.id,
                    flags,
                    answered.map {
                        it.copy(unicastResponse = false)
                    },
                    answers,
                    additionals = additionals,
                ),
                true,
            )
        }
        if (answered.all { it.unicastResponse }) return Reply(DnsMessage(0, flags, answers = answers, additionals = additionals), true)
        val now = clock.elapsedMillis()
        val freshAnswers = answers.filterNot { multicastRecently(it.type, now) }
        if (freshAnswers.isEmpty()) return null
        val freshAdditionals = additionals.filterNot { multicastRecently(it.type, now) }
        markMulticast(freshAnswers + freshAdditionals, now)
        return Reply(DnsMessage(0, flags, answers = freshAnswers, additionals = freshAdditionals), false)
    }

    /** The unsolicited announcement sent when the responder starts (RFC 6762 §8.3); it counts as a multicast. */
    @Synchronized
    fun announcement(): DnsMessage {
        val message =
            DnsMessage(
                0,
                DnsMessage.FLAG_RESPONSE or DnsMessage.FLAG_AUTHORITATIVE,
                answers = listOf(aRecord(ttlSeconds, true)),
                additionals = listOf(nsecRecord(ttlSeconds)),
            )
        markMulticast(message.answers + message.additionals, clock.elapsedMillis())
        return message
    }

    /** The goodbye sent when the responder stops: the A record with TTL 0 (RFC 6762 §10.1). */
    fun goodbye(): DnsMessage = DnsMessage(0, DnsMessage.FLAG_RESPONSE or DnsMessage.FLAG_AUTHORITATIVE, answers = listOf(aRecord(0, true)))

    private fun multicastRecently(
        type: Int,
        now: Long,
    ): Boolean {
        val last = lastMulticast[type] ?: return false
        return now - last < MIN_MULTICAST_INTERVAL_MILLIS
    }

    private fun markMulticast(
        records: List<DnsRecord>,
        now: Long,
    ) {
        records.forEach { lastMulticast[it.type] = now }
    }

    private fun knownAnswerSuppresses(query: DnsMessage): Boolean =
        query.answers.any { rec ->
            rec.type == DnsType.A &&
                rec.name.matches(hostName) &&
                (rec.data as? DnsRData.A)?.address?.contentEquals(addressBytes) == true &&
                rec.ttl * 2 >= ttlSeconds
        }

    private fun aRecord(
        ttl: Long,
        flush: Boolean,
    ) = DnsRecord(hostName, DnsType.A, DnsClass.IN, flush, ttl, DnsRData.A(addressBytes))

    private fun nsecRecord(ttl: Long) = DnsRecord(hostName, DnsType.NSEC, DnsClass.IN, true, ttl, DnsRData.Nsec(hostName, setOf(DnsType.A)))

    companion object {
        /** TTL of host address records recommended by RFC 6762 §10. */
        const val DEFAULT_TTL_SECONDS = 120L

        /** Longest TTL in a legacy unicast reply (RFC 6762 §6.7). */
        const val LEGACY_TTL_SECONDS = 10L

        /** A record is multicast on the interface at most this often (RFC 6762 §6). */
        const val MIN_MULTICAST_INTERVAL_MILLIS = 1000L
    }
}
