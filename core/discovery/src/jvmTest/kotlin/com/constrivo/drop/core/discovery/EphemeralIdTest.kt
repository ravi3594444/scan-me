package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import com.constrivo.drop.core.discovery.Secrets.K0
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Rotating IDs, architecture §5.3 with S3 (F‑A1 "ID changes every 15 min", F‑J1). */
class EphemeralIdTest {
    private val crypto = JcaCryptoProvider()

    @Test
    fun fJ1_goldenVectors() {
        // HMAC-SHA256(k_adv = 00..1f, "drop-eph-v1" ‖ u64be(epoch))[0..6], computed with Python hmac.
        assertEquals("46ae8e8ad96a", EphemeralIds.derive(crypto, K0, 0).toHex())
        assertEquals("55d372f05382", EphemeralIds.derive(crypto, K0, 1).toHex())
        assertEquals("4c809f49f441", EphemeralIds.derive(crypto, K0, EPOCH).toHex())
        assertEquals("1dce6d357895", EphemeralIds.derive(crypto, K0, EPOCH + 1).toHex())
        assertContentEquals("drop-eph-v1".encodeToByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0x1E, 0x59, 0x18), EphemeralIds.message(EPOCH))
    }

    @Test
    fun fA1_idRotatesExactlyAtEpochBoundaries() {
        val before = EphemeralIds.at(crypto, K0, EPOCH_START - 1)
        val first = EphemeralIds.at(crypto, K0, EPOCH_START)
        val last = EphemeralIds.at(crypto, K0, EPOCH_START + EphemeralIds.EPOCH_MILLIS - 1)
        val next = EphemeralIds.at(crypto, K0, EPOCH_START + EphemeralIds.EPOCH_MILLIS)
        assertEquals(EphemeralIds.derive(crypto, K0, EPOCH - 1), before)
        assertEquals("4c809f49f441", first.toHex())
        assertEquals(first, last)
        assertEquals("1dce6d357895", next.toHex())
        assertNotEquals(before, first)
    }

    @Test
    fun epochArithmetic() {
        assertEquals(0, EphemeralIds.epochAt(0))
        assertEquals(0, EphemeralIds.epochAt(899_999))
        assertEquals(1, EphemeralIds.epochAt(900_000))
        assertEquals(EPOCH, EphemeralIds.epochAt(EPOCH_START))
        assertEquals(EPOCH_START, EphemeralIds.epochStartMillis(EPOCH))
        assertEquals(900_000, EphemeralIds.millisUntilNextEpoch(EPOCH_START))
        assertEquals(899_999, EphemeralIds.millisUntilNextEpoch(EPOCH_START + 1))
        assertEquals(1, EphemeralIds.millisUntilNextEpoch(EPOCH_START - 1))
        assertFailsWith<IllegalArgumentException> { EphemeralIds.epochAt(-1) }
        assertFailsWith<IllegalArgumentException> { EphemeralIds.derive(crypto, ByteArray(16), EPOCH) }
        assertFailsWith<IllegalArgumentException> { EphemeralIds.derive(crypto, K0, -1) }
    }

    @Test
    fun idEncodings() {
        val id = EphemeralIds.derive(crypto, K0, EPOCH)
        assertEquals(id, EphemeralId.parseHex(id.toHex()))
        assertEquals(id, EphemeralId.parseHex(id.toHex().uppercase()))
        assertEquals(id, EphemeralId.fromBytes(id.toByteArray()))
        assertContentEquals(crypto.hmacSha256(K0, EphemeralIds.message(EPOCH)).copyOfRange(0, 6), id.toByteArray())
        for (bad in listOf("", "4c809f49f44", "4c809f49f4411", "4c809f49f44g", "+c809f49f441")) {
            assertFailsWith<DiscoveryFormatException>(bad) { EphemeralId.parseHex(bad) }
        }
        assertFailsWith<IllegalArgumentException> { EphemeralId(EphemeralId.MAX_VALUE + 1) }
    }

    @Test
    fun fJ1_idsOfDifferentEpochsShareNoStructureBeyondChance() {
        // Sanity check, not a proof: the HMAC output bits of consecutive epochs look independent.
        val ids = (0 until 4_000L).map { EphemeralIds.derive(crypto, K0, EPOCH + it).value }
        assertEquals(ids.size, ids.toSet().size, "no repeats")
        val ones = IntArray(48)
        var distanceSum = 0L
        for (i in ids.indices) {
            for (bit in 0 until 48) if ((ids[i] ushr bit) and 1L == 1L) ones[bit]++
        }
        // 20 minutes apart = one or two epochs later.
        for (i in 0 until ids.size - 2) {
            distanceSum += (ids[i] xor ids[i + 1]).countOneBits() + (ids[i] xor ids[i + 2]).countOneBits()
        }
        for (bit in 0 until 48) assertTrue(ones[bit] in 1_800..2_200, "bit $bit set in ${ones[bit]} of 4000 ids")
        val meanDistance = distanceSum.toDouble() / (2 * (ids.size - 2))
        assertTrue(meanDistance in 23.5..24.5, "mean Hamming distance $meanDistance, expected 24")
        // Different devices in the same epoch are unrelated as well.
        val other = (0 until 4_000L).map { EphemeralIds.derive(crypto, Secrets.secret(1), EPOCH + it).value }
        assertTrue(ids.toSet().intersect(other.toSet()).isEmpty())
    }
}
