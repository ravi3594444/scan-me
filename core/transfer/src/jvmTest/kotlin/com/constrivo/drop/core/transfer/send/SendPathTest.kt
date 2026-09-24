package com.constrivo.drop.core.transfer.send

import com.constrivo.drop.core.protocol.BundleIndex
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ResumeUnit
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ID = TransferId(ByteArray(TransferId.SIZE) { 7 })
private val MIB = ProtocolConstants.MIB

/** N12 and S4: the summary offer and the plan both sides derive. */
class OfferBuilderTest {
    @Test
    fun `the offer summarises the files and carries the bundle count and link options`() {
        val sources =
            listOf(
                MemorySource("a.jpg", ByteArray(100), "image/jpeg"),
                MemorySource("b.jpg", ByteArray(200), "image/jpeg"),
                MemorySource("movie.mp4", ByteArray(5 * MIB), "video/mp4"),
            ) + (0 until 6).map { MemorySource("n$it.txt", ByteArray(10), "text/plain") }
        val options = listOf(LinkOption(LinkKind.LAN, address = "192.168.1.20", port = 40_000))
        val plan = OfferBuilder.build(ID, sources, linkOptions = options)
        val offer = plan.offer
        assertEquals(9, offer.fileCount)
        assertEquals(300L + 5 * MIB + 60, offer.totalBytes)
        assertEquals(listOf("a.jpg", "b.jpg", "movie.mp4", "n0.txt", "n1.txt", "n2.txt"), offer.previewNames, "at most six names")
        assertEquals(2, offer.mimeHistogram["image/jpeg"])
        assertEquals(6, offer.mimeHistogram["text/plain"])
        assertEquals(1, offer.bundleCount, "the eight small files share one bundle")
        assertEquals(8, plan.bundledFileCount)
        assertEquals(options, offer.linkOptions)
        assertEquals(plan.layout.bundleCount, offer.bundleCount)
        assertEquals(1 + 2L, plan.layout.totalUnits, "one bundle and two 4 MiB chunks")
        assertEquals(sources.map { it.name }, plan.fileListPages.flatMap { it.files }.map { it.name })
    }

    @Test
    fun `names are made wire-safe`() {
        assertEquals("file", OfferBuilder.wireName(""))
        assertEquals("a�b", OfferBuilder.wireName("a\uD800b"), "an unpaired surrogate is replaced")
        assertEquals("😀.png", OfferBuilder.wireName("😀.png"), "a pair survives")
        val long = "é".repeat(600) // 1200 bytes of UTF-8
        val cut = OfferBuilder.wireName(long)
        assertEquals(512, cut.length, "cut at a code point within 1024 bytes")
        assertTrue(cut.encodeToByteArray().size <= ProtocolConstants.MAX_FILE_NAME_BYTES)
    }

    @Test
    fun `an empty or negative plan is refused`() {
        assertFailsWith<IllegalArgumentException> { OfferBuilder.build(ID, emptyList()) }
        val negative =
            object : SourceFile {
                override val name = "x"
                override val size = -1L
                override val mimeType: String? = null

                override suspend fun read(
                    position: Long,
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) = -1

                override suspend fun close() = Unit
            }
        assertFailsWith<IllegalArgumentException> { OfferBuilder.build(ID, listOf(negative)) }
    }
}

/** §7.4 work queue: two lanes, dedupe with the smaller start offset, epochs on `Resume`. */
class SendQueueTest {
    private fun unit(
        chunk: Int,
        from: Int = 0,
    ) = ResumeUnit(TransferUnit(0, chunk), from)

    @Test
    fun `priority units go first and a unit is queued once with the smaller offset`() {
        val queue = SendQueue()
        queue.addNormal(unit(0), 0)
        queue.addNormal(unit(1), 1)
        queue.addNormal(unit(1), 1)
        queue.addPriority(unit(1, from = 32_768), 1)
        queue.addPriority(unit(1, from = 16_384), 1)
        assertEquals(2, queue.size)
        val first = queue.poll()!!
        assertEquals(1L, first.global)
        assertTrue(first.priority)
        assertEquals(0, first.unit.fromOffset, "the normal copy started at 0, the smaller offset wins")
        assertEquals(0L, queue.poll()!!.global)
        assertNull(queue.poll())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `replace swaps in a Resume's missing set and bumps the epoch`() {
        val queue = SendQueue()
        queue.addNormal(unit(0), 0)
        queue.addPriority(unit(5), 5)
        val epoch = queue.replace(listOf(unit(2) to 2L, unit(3) to 3L, unit(2) to 2L))
        assertEquals(1, epoch)
        assertEquals(listOf(2L, 3L), listOfNotNull(queue.poll(), queue.poll()).map { it.global })
        assertNull(queue.poll())
        queue.addNormal(unit(4), 4)
        assertEquals(1, queue.poll()!!.epoch)
    }

    @Test
    fun `removed units are skipped and take waits until something is queued or the queue closes`() =
        runTest {
            val queue = SendQueue()
            queue.addNormal(unit(0), 0)
            assertTrue(queue.remove(0))
            assertFalse(queue.remove(0))
            assertFalse(0L in queue)
            val waiting = async { queue.take() }
            runCurrent()
            assertFalse(waiting.isCompleted)
            queue.addNormal(unit(7), 7)
            assertEquals(7L, waiting.await()?.global)
            val closed = async { queue.take() }
            runCurrent()
            queue.close()
            assertNull(closed.await())
            queue.addNormal(unit(8), 8)
            assertNull(queue.poll(), "a closed queue hands out nothing")
        }
}

/** §7.3 payloads: chunks, resumed chunks (S1) and assembled bundles (S4) with their per-file SHA-256. */
class UnitReaderTest {
    private val small1 = TestSupport.randomBytes(100, 1)
    private val small2 = TestSupport.randomBytes(200, 2)
    private val big = TestSupport.randomBytes(5 * MIB + 3, 3)
    private val sources = listOf(MemorySource("a", small1), MemorySource("b", small2), MemorySource("big", big))
    private val plan = OfferBuilder.build(ID, sources)
    private val reader = UnitReader(plan.layout, sources)

    @Test
    fun `a chunk is its slice of the file, and a resumed chunk starts at its offset`() =
        runTest {
            val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE + 8)
            val n = reader.read(ResumeUnit(TransferUnit(2, 1), 0), buffer, 8)
            assertEquals(MIB + 3, n)
            assertContentEquals(big.copyOfRange(4 * MIB, big.size), buffer.copyOfRange(8, 8 + n))
            val resumed = reader.read(ResumeUnit(TransferUnit(2, 0), 16_384), buffer, 0)
            assertEquals(4 * MIB - 16_384, resumed)
            assertContentEquals(big.copyOfRange(16_384, 4 * MIB), buffer.copyOfRange(0, resumed))
        }

    @Test
    fun `a bundle is its index followed by the files, each hashed whole`() =
        runTest {
            val bundleUnit = TransferUnit(ProtocolConstants.BUNDLE_FILE_INDEX, 0)
            val length = plan.layout.unitLength(bundleUnit)
            val buffer = ByteArray(length)
            val hashes = HashMap<Int, String>()
            val n = reader.read(ResumeUnit(bundleUnit, 0), buffer, 0) { file, sha -> hashes[file] = sha.toHex() }
            assertEquals(length, n)
            val index = BundleIndex.encodeIndex(plan.layout.bundlePlan.bundles[0].entries)
            assertContentEquals(index, buffer.copyOfRange(0, index.size))
            assertContentEquals(small1 + small2, buffer.copyOfRange(index.size, n))
            assertEquals(mapOf(0 to TestSupport.sha256(small1), 1 to TestSupport.sha256(small2)), hashes)
        }

    @Test
    fun `a source that shrank or stalls fails with SourceReadException`() =
        runTest {
            val shrunk = listOf(MemorySource("a", small1), MemorySource("b", small2), MemorySource("big", big.copyOf(MIB)))
            val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
            assertFailsWith<SourceReadException> { UnitReader(plan.layout, shrunk).read(ResumeUnit(TransferUnit(2, 0), 0), buffer, 0) }
            val stalling =
                object : SourceFile {
                    override val name = "big"
                    override val size = big.size.toLong()
                    override val mimeType: String? = null

                    override suspend fun read(
                        position: Long,
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = 0

                    override suspend fun close() = Unit
                }
            val stalled = listOf(sources[0], sources[1], stalling)
            assertFailsWith<SourceReadException> { UnitReader(plan.layout, stalled).read(ResumeUnit(TransferUnit(2, 0), 0), buffer, 0) }
        }
}
