package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The manifest model: bitmap layout, hashes and the partial prefix (architecture §7.6 with S1, N5). */
class ChunkManifestTest {
    private val id = TransferId(ByteArray(16) { 7 })

    private fun hash(n: Int) = ChunkHash(ByteArray(16) { (n + it).toByte() })

    @Test
    fun bitsAreLeastSignificantFirstWithinEachByte() {
        val manifest =
            ChunkManifest
                .empty(id, 0, 10, 0)
                .withReceived(0, hash(0), 1)
                .withReceived(3, hash(3), 2)
                .withReceived(9, hash(9), 3)
        assertContentEquals(byteArrayOf(0b0000_1001, 0b0000_0010), manifest.receivedBitmap())
        assertEquals(3, manifest.receivedCount)
        assertFalse(manifest.isComplete)
        assertTrue(manifest.isReceived(3))
        assertFalse(manifest.isReceived(4))
        assertEquals(hash(3), manifest.hashOf(3))
        assertNull(manifest.hashOf(4))
        val hashes = manifest.unitHashes()
        assertEquals(160, hashes.size)
        assertContentEquals(hash(9).toByteArray(), hashes.copyOfRange(144, 160))
        assertContentEquals(ByteArray(16), hashes.copyOfRange(16, 32), "unreceived slots stay zero")
        assertEquals(3, manifest.updatedAtMillis)
    }

    @Test
    fun receivingEveryUnitCompletesIt() {
        var manifest = ChunkManifest.empty(id, ProtocolConstants.BUNDLE_FILE_INDEX, 8, 0)
        for (u in 0 until 8) manifest = manifest.withReceived(u, hash(u), u.toLong())
        assertTrue(manifest.isComplete)
        assertContentEquals(byteArrayOf(-1), manifest.receivedBitmap())
        assertTrue(ChunkManifest.empty(id, 0, 0, 0).isComplete, "an empty file has nothing to receive")
    }

    @Test
    fun withMissingClearsTheBitAndTheHash() {
        val manifest = ChunkManifest.empty(id, 1, 4, 0).withReceived(2, hash(2), 1).withMissing(2, 5)
        assertFalse(manifest.isReceived(2))
        assertContentEquals(ByteArray(64), manifest.unitHashes())
        assertEquals(5, manifest.updatedAtMillis)
    }

    @Test
    fun partialPrefixIsReplacedWhenTheUnitCompletes() {
        val block = ProtocolConstants.BLUETOOTH_BLOCK_SIZE
        val partial = ChunkManifest.empty(id, 0, 4, 0).withPartial(0, 2 * block, 1)
        assertEquals(0, partial.partialUnit)
        assertEquals(2 * block, partial.presentPrefix(0))
        assertEquals(0, partial.presentPrefix(1))
        val done = partial.withReceived(0, hash(0), 2)
        assertNull(done.partialUnit)
        assertEquals(0, done.partialBytes)
        val other = partial.withReceived(1, hash(1), 3)
        assertEquals(0, other.partialUnit, "completing another unit keeps the prefix")
        assertNull(partial.withPartial(0, 0, 4).partialUnit)
    }

    @Test
    fun theConstructorRejectsInconsistentState() {
        assertFailsWith<IllegalArgumentException> { ChunkManifest(id, -2, 0, ByteArray(0), ByteArray(0), updatedAtMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ChunkManifest(id, 0, -1, ByteArray(0), ByteArray(0), updatedAtMillis = 0) }
        assertFailsWith<IllegalArgumentException> {
            ChunkManifest(id, 0, ChunkManifest.MAX_UNITS + 1, ByteArray(0), ByteArray(0), updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> { ChunkManifest(id, 0, 9, ByteArray(1), ByteArray(144), updatedAtMillis = 0) }
        assertFailsWith<IllegalArgumentException> { ChunkManifest(id, 0, 8, ByteArray(1), ByteArray(127), updatedAtMillis = 0) }
        assertFailsWith<IllegalArgumentException>("spare bits set") {
            ChunkManifest(id, 0, 3, byteArrayOf(0b0000_1000), ByteArray(48), updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkManifest(id, 0, 3, ByteArray(1), ByteArray(48), partialUnit = 1, partialBytes = 0, updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkManifest(id, 0, 3, ByteArray(1), ByteArray(48), partialUnit = 3, partialBytes = 5, updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkManifest(
                id,
                0,
                3,
                ByteArray(1),
                ByteArray(48),
                partialUnit = 0,
                partialBytes = ProtocolConstants.CHUNK_SIZE,
                updatedAtMillis = 0,
            )
        }
        assertFailsWith<IllegalArgumentException>("received and partial") {
            ChunkManifest(id, 0, 3, byteArrayOf(1), ByteArray(48), partialUnit = 0, partialBytes = 5, updatedAtMillis = 0)
        }
        val ok = ChunkManifest.empty(id, 0, 3, 0)
        assertFailsWith<IllegalArgumentException> { ok.isReceived(3) }
        assertFailsWith<IllegalArgumentException> { ok.withReceived(-1, hash(0), 0) }
        assertFailsWith<IllegalArgumentException> { ok.withMissing(3, 0) }
    }

    @Test
    fun inputArraysAreCopied() {
        val bitmap = ByteArray(1)
        val manifest = ChunkManifest(id, 0, 4, bitmap, ByteArray(64), updatedAtMillis = 0)
        bitmap[0] = 1
        assertFalse(manifest.isReceived(0))
        manifest.receivedBitmap()[0] = 1
        assertFalse(manifest.isReceived(0))
    }

    @Test
    fun equalityIsByContent() {
        val a = ChunkManifest.empty(id, 0, 4, 0).withReceived(1, hash(1), 9)
        val b = ChunkManifest.empty(id, 0, 4, 0).withReceived(1, hash(1), 9)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == ChunkManifest.empty(id, 0, 4, 0).withReceived(1, hash(2), 9))
        assertTrue("1/4 received" in a.toString())
    }
}
