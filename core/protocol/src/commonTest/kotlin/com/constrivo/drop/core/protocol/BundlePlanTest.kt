package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_THRESHOLD
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.KIB
import com.constrivo.drop.core.protocol.ProtocolConstants.MIB
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-E7 / S4: deterministic bundles and the bundle index layout. */
class BundlePlanTest {
    @Test
    fun smallFilesAreBundledInFileOrderAndLargeFilesAreChunked() {
        val sizes = listOf(100L, 5L * MIB, 0L, BUNDLE_THRESHOLD - 1L, BUNDLE_THRESHOLD.toLong(), 300L)
        val plan = BundlePlan.of(sizes)
        assertEquals(1, plan.bundleCount)
        assertEquals(listOf(0, 2, 3, 5), plan.bundles[0].entries.map { it.fileIndex })
        assertEquals(listOf(0L, 100L, 100L, 100L + BUNDLE_THRESHOLD - 1), plan.bundles[0].entries.map { it.offset.toLong() })
        assertFalse(plan.isBundled(1))
        assertFalse(plan.isBundled(4), "a file of exactly the threshold is chunked")
        assertTrue(plan.isBundled(2), "empty files are bundled")
        assertEquals(BundleEntry(5, 100 + BUNDLE_THRESHOLD - 1, 300), plan.entryOf(5))
        assertNull(plan.bundleOf(1))
        assertNull(plan.entryOf(4))
        assertFalse(plan.isBundled(99))
    }

    @Test
    fun aFileThatDoesNotFitStartsANewBundle() {
        // Files of 1 MiB - 1: three fit in 4 MiB with their index; the fourth would need 4 MiB + 48 bytes.
        val size = BUNDLE_THRESHOLD - 1L
        val plan = BundlePlan.of(List(5) { size })
        assertEquals(2, plan.bundleCount)
        assertEquals(listOf(0, 1, 2), plan.bundles[0].entries.map { it.fileIndex })
        assertEquals(listOf(3, 4), plan.bundles[1].entries.map { it.fileIndex })
        // Exactly full: index (4 + 12 n) + data = 4 MiB is allowed.
        val perFile = (CHUNK_SIZE - Bundle.indexSize(4)) / 4
        val exact = BundlePlan.of(List(4) { perFile.toLong() } + 1L)
        assertEquals(CHUNK_SIZE, exact.bundles[0].payloadLength)
        assertEquals(listOf(4), exact.bundles[1].entries.map { it.fileIndex })
    }

    @Test
    fun bundlesNeverExceedTheChunkSize() {
        val random = Random(11)
        repeat(50) {
            val sizes =
                List(random.nextInt(1, 3000)) {
                    if (random.nextInt(10) ==
                        0
                    ) {
                        random.nextLong(0, 10L * MIB)
                    } else {
                        random.nextLong(0, BUNDLE_THRESHOLD.toLong())
                    }
                }
            for (chunkSize in listOf(CHUNK_SIZE, MIB, 256 * KIB, 64 * KIB)) {
                val plan = BundlePlan.of(sizes, chunkSize)
                val limit = BundlePlan.smallFileLimit(chunkSize)
                val bundled = plan.bundles.flatMap { it.entries }.map { it.fileIndex }
                assertEquals(sizes.indices.filter { sizes[it] < limit }, bundled, "small files, in order, each exactly once")
                for (bundle in plan.bundles) {
                    assertTrue(bundle.payloadLength <= chunkSize)
                    assertEquals(bundle.entries.sumOf { sizes[it.fileIndex].toInt() }, bundle.dataLength)
                }
                // Greedy: the first file of each bundle would not have fitted into the previous one.
                for (i in 1 until plan.bundles.size) {
                    val previous = plan.bundles[i - 1]
                    val first = plan.bundles[i].entries.first()
                    assertTrue(Bundle.indexSize(previous.entries.size + 1) + previous.dataLength + first.length > chunkSize)
                }
                // Deterministic: the same input gives the same plan.
                assertEquals(plan.bundles, BundlePlan.of(sizes, chunkSize).bundles)
            }
        }
    }

    @Test
    fun smallerChunksLowerTheThreshold() {
        assertEquals(BUNDLE_THRESHOLD, BundlePlan.smallFileLimit(CHUNK_SIZE))
        assertEquals(16 * KIB, BundlePlan.smallFileLimit(64 * KIB))
        val plan = BundlePlan.of(listOf(20L * KIB, 10L * KIB), chunkSize = 64 * KIB)
        assertEquals(listOf(1), plan.bundles.single().entries.map { it.fileIndex })
    }

    @Test
    fun bundlingOffChunksEverything() {
        val plan = BundlePlan.of(listOf(1L, 0L, 5L * MIB), bundleSmall = false)
        assertEquals(0, plan.bundleCount)
        assertFalse(plan.isBundled(0))
    }

    @Test
    fun rejectsBadInput() {
        assertRejectsArgument { BundlePlan.of(listOf(-1L)) }
        assertRejectsArgument { BundlePlan.of(listOf(1L), chunkSize = 3 * MIB) }
        assertRejectsArgument { BundlePlan.of(listOf(1L), chunkSize = 8 * MIB) }
        assertRejectsArgument { BundlePlan.forFiles(listOf(FileEntry(1, "a", 1)), CHUNK_SIZE, true) }
        assertRejectsArgument { Bundle(0, emptyList()) }
        assertRejectsArgument { Bundle(0, listOf(BundleEntry(0, 1, 1))) }
        assertRejectsArgument { Bundle(0, listOf(BundleEntry(1, 0, 1), BundleEntry(0, 1, 1))) }
        assertRejectsArgument { Bundle(0, listOf(BundleEntry(0, 0, CHUNK_SIZE))) }
    }

    @Test
    fun bundleIndexRoundTrip() {
        val sizes = listOf(3L, 0L, 70_000L, 1L)
        val plan = BundlePlan.of(sizes)
        val bundle = plan.bundles.single()
        val files = sizes.map { size -> ByteArray(size.toInt()) { (size + it).toByte() } }
        val data = bundle.entries.fold(ByteArray(0)) { acc, e -> acc + files[e.fileIndex] }
        val payload = BundleIndex.encode(bundle, data)
        assertEquals(bundle.payloadLength, payload.size)
        assertContentEquals(BundleIndex.encodeIndex(bundle.entries), payload.copyOf(Bundle.indexSize(bundle.entries.size)))
        val decoded = BundleIndex.decode(payload, bundle)
        assertEquals(Bundle.indexSize(4), decoded.dataOffset)
        for (entry in decoded.entries) assertContentEquals(files[entry.fileIndex], decoded.slice(payload, entry))
        assertRejectsArgument { BundleIndex.encode(bundle, data.copyOf(data.size - 1)) }
    }

    @Test
    fun bundleIndexRejectsMalformedPayloads() {
        fun payload(
            vararg entries: Triple<Long, Long, Long>,
            count: Long = entries.size.toLong(),
            data: Int = entries.sumOf { it.third }.toInt(),
        ): ByteArray {
            val out = ByteArray(4 + 12 * entries.size + data)
            BigEndian.putU32(out, 0, count.toInt())
            entries.forEachIndexed { i, (f, o, l) ->
                BigEndian.putU32(out, 4 + 12 * i, f.toInt())
                BigEndian.putU32(out, 8 + 12 * i, o.toInt())
                BigEndian.putU32(out, 12 + 12 * i, l.toInt())
            }
            return out
        }
        BundleIndex.decode(payload(Triple(0, 0, 2), Triple(1, 2, 0)))
        assertProtocolError("short") { BundleIndex.decode(ByteArray(3)) }
        assertProtocolError("zero entries") { BundleIndex.decode(payload()) }
        assertProtocolError("count beyond payload") { BundleIndex.decode(payload(Triple(0, 0, 1), count = 2)) }
        assertProtocolError("huge count") { BundleIndex.decode(payload(Triple(0, 0, 1), count = 0xFFFF_FFFFL)) }
        assertProtocolError("gap") { BundleIndex.decode(payload(Triple(0, 0, 1), Triple(1, 2, 1), data = 3)) }
        assertProtocolError("overlap") { BundleIndex.decode(payload(Triple(0, 0, 2), Triple(1, 1, 1), data = 3)) }
        assertProtocolError("unordered") { BundleIndex.decode(payload(Triple(1, 0, 1), Triple(0, 1, 1))) }
        assertProtocolError("duplicate file") { BundleIndex.decode(payload(Triple(1, 0, 1), Triple(1, 1, 1))) }
        assertProtocolError("bundle marker as a file") { BundleIndex.decode(payload(Triple(0xFFFF_FFFFL, 0, 1))) }
        assertProtocolError("length past the data") { BundleIndex.decode(payload(Triple(0, 0, 0xFFFF_FFFFL), data = 1)) }
        assertProtocolError("data longer than the entries") { BundleIndex.decode(payload(Triple(0, 0, 1), data = 2)) }
        assertProtocolError("payload above a chunk") { BundleIndex.decode(ByteArray(CHUNK_SIZE + 1)) }
        val planned = Bundle(0, listOf(BundleEntry(0, 0, 2)))
        assertProtocolError("not the planned bundle") { BundleIndex.decode(payload(Triple(0, 0, 1), Triple(1, 1, 1)), planned) }
    }

    @Test
    fun randomPayloadsOnlyEverRaiseProtocolException() {
        val random = Random(17)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            if (bytes.size >= 4 && random.nextBoolean()) BigEndian.putU32(bytes, 0, random.nextInt(0, 6))
            try {
                BundleIndex.decode(bytes)
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun chunkPlanCountsChunks() {
        assertEquals(0, ChunkPlan(0, CHUNK_SIZE).chunkCount)
        assertEquals(0, ChunkPlan(0, CHUNK_SIZE).lastChunkSize)
        assertEquals(1, ChunkPlan(1, CHUNK_SIZE).chunkCount)
        assertEquals(1, ChunkPlan(CHUNK_SIZE.toLong(), CHUNK_SIZE).chunkCount)
        assertEquals(CHUNK_SIZE, ChunkPlan(CHUNK_SIZE.toLong(), CHUNK_SIZE).lastChunkSize)
        val plan = ChunkPlan(2L * CHUNK_SIZE + 5, CHUNK_SIZE)
        assertEquals(3, plan.chunkCount)
        assertEquals(5, plan.lastChunkSize)
        assertEquals(CHUNK_SIZE, plan.chunkLength(0))
        assertEquals(5, plan.chunkLength(2))
        assertEquals(2L * CHUNK_SIZE, plan.chunkOffset(2))
        assertEquals(CHUNK_SIZE / ProtocolConstants.BLUETOOTH_BLOCK_SIZE, plan.blockCount(0))
        assertEquals(1, plan.blockCount(2))
        assertEquals(64, plan.blockCount(0, 64 * KIB))
        val big = ChunkPlan(2L * 1024 * 1024 * 1024 * 1024, CHUNK_SIZE) // 2 TiB
        assertEquals(524_288, big.chunkCount)
        assertRejectsArgument { plan.chunkLength(3) }
        assertRejectsArgument { plan.chunkOffset(-1) }
        assertRejectsArgument { ChunkPlan(-1, CHUNK_SIZE) }
        assertRejectsArgument { ChunkPlan(Long.MAX_VALUE, MIN_CHUNK) }
    }

    private companion object {
        const val MIN_CHUNK = ProtocolConstants.MIN_CHUNK_SIZE
    }
}

/** S1 / S4 / §7.6: units, tracking keys and the conversion between "present" and `Resume`. */
class TransferLayoutTest {
    // Files: 0 small, 1 chunked (3 chunks), 2 small, 3 empty (bundled), 4 exactly the threshold (1 chunk), 5 small.
    private val sizes = listOf(10L, 2L * CHUNK_SIZE + 1, 20L, 0L, 5L * MIB - CHUNK_SIZE, 7L)
    private val layout = TransferLayout.of(sizes)

    @Test
    fun unitsAndTrackingKeys() {
        assertEquals(1, layout.bundleCount)
        assertEquals(listOf(BUNDLE_FILE_INDEX, 1, 4), layout.trackingKeys)
        assertEquals(1, layout.unitCount(BUNDLE_FILE_INDEX))
        assertEquals(3, layout.unitCount(1))
        assertEquals(1, layout.unitCount(4))
        assertEquals(0, layout.unitCount(0), "bundled file has no units of its own")
        assertEquals(5L, layout.totalUnits)
        assertEquals(sizes.sum(), layout.totalBytes)
        assertNull(layout.chunkPlan(0))
        assertEquals(3, layout.chunkPlan(1)!!.chunkCount)
        assertEquals(1, layout.unitLength(TransferUnit(1, 2)))
        assertEquals(layout.bundlePlan.bundles[0].payloadLength, layout.unitLength(TransferUnit(BUNDLE_FILE_INDEX, 0)))
        assertRejectsArgument { layout.unitCount(99) }
        assertRejectsArgument { layout.unitLength(TransferUnit(1, 3)) }
    }

    @Test
    fun globalOrderIsBundlesThenChunksInFileOrder() {
        val units = layout.units().toList()
        assertEquals(
            listOf(TransferUnit(BUNDLE_FILE_INDEX, 0), TransferUnit(1, 0), TransferUnit(1, 1), TransferUnit(1, 2), TransferUnit(4, 0)),
            units,
        )
        units.forEachIndexed { i, unit ->
            assertEquals(i.toLong(), layout.globalIndex(unit))
            assertEquals(unit, layout.unitAt(i.toLong()))
        }
        assertRejectsArgument { layout.unitAt(5) }
    }

    @Test
    fun globalOrderSkipsEmptyChunkedFiles() {
        val noBundles = TransferLayout.of(listOf(0L, 5L, 0L, 0L, 7L, 0L), bundleSmall = false)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), noBundles.trackingKeys)
        val units = noBundles.units().toList()
        assertEquals(listOf(TransferUnit(1, 0), TransferUnit(4, 0)), units)
        units.forEachIndexed { i, unit -> assertEquals(unit, noBundles.unitAt(i.toLong())) }
    }

    @Test
    fun checksChunkHeadersAgainstTheLayout() {
        val hash = ChunkHash(ByteArray(16))
        layout.checkHeader(ChunkHeader(TEST_ID, 1, 0, 0, CHUNK_SIZE, hash))
        layout.checkHeader(ChunkHeader(TEST_ID, 1, 0, CHUNK_SIZE - 16 * KIB, 16 * KIB, hash))
        layout.checkHeader(ChunkHeader(TEST_ID, 1, 2, 0, 1, hash))
        assertProtocolError("past the last chunk") { layout.checkHeader(ChunkHeader(TEST_ID, 1, 2, 0, 2, hash)) }
        assertProtocolError("no such chunk") { layout.checkHeader(ChunkHeader(TEST_ID, 1, 3, 0, 1, hash)) }
        assertProtocolError("bundled file") { layout.checkHeader(ChunkHeader(TEST_ID, 0, 0, 0, 1, hash)) }
        assertProtocolError("no such bundle") { layout.checkHeader(ChunkHeader(TEST_ID, BUNDLE_FILE_INDEX, 1, 0, 1, hash)) }
        assertProtocolError("unknown file") { layout.checkHeader(ChunkHeader(TEST_ID, 6, 0, 0, 1, hash)) }
    }

    @Test
    fun everythingMissingForAFreshTransfer() {
        val missing = layout.everything()
        assertEquals(listOf(MissingChunks(BUNDLE_FILE_INDEX, listOf(IndexRange(0, 1)))), missing.chunks)
        assertEquals(listOf(IndexRange(1, 4)), missing.files, "files 1..4 joined across the bundled files 2 and 3")
        assertEquals(layout.units().map { ResumeUnit(it, 0) }.toList(), layout.expand(missing))
    }

    @Test
    fun missingUnitsDescribeExactlyWhatIsAbsent() {
        val present = setOf(TransferUnit(1, 0), TransferUnit(BUNDLE_FILE_INDEX, 0))
        val missing = layout.missingUnits { it in present }
        assertEquals(listOf(MissingChunks(1, listOf(IndexRange(1, 2)))), missing.chunks)
        assertEquals(listOf(IndexRange(4, 1)), missing.files)
        assertEquals(layout.units().filter { it !in present }.toList(), layout.expand(missing).map { it.unit })
        assertTrue(layout.missingUnits { true }.isEmpty)
    }

    @Test
    fun partialBluetoothBlocksBecomeAFirstBlockOffset() {
        // Chunk 0 of file 1 has 3 blocks on disk: resume it from byte 48 KiB.
        val missing = layout.missingUnits(presentBytes = { if (it == TransferUnit(1, 0)) 48 * KIB else 0 }) { it.isBundle }
        val entry = missing.chunks.single()
        assertEquals(1, entry.fileIndex)
        assertEquals(48 * KIB, entry.firstBlockOffset)
        assertEquals(listOf(IndexRange(0, 3)), entry.ranges)
        val expanded = layout.expand(missing)
        assertEquals(ResumeUnit(TransferUnit(1, 0), 48 * KIB), expanded.first())
        assertTrue(expanded.drop(1).all { it.fromOffset == 0 })
    }

    @Test
    fun manyHolesAreWidenedToFitOneResume() {
        // 50,000 chunks with every other one missing: the exact list would not fit, so ranges merge.
        val big = TransferLayout.of(listOf(50_000L * 64 * KIB), chunkSize = 64 * KIB)
        val missing = big.missingUnits { it.chunkIndex % 2 == 0 }
        assertTrue(ControlCodec.encodedSize(Resume(TEST_ID, missing)) <= ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES)
        val requested = big.expand(missing).map { it.unit }.toSet()
        for (c in 0 until 50_000) if (c % 2 == 1) assertTrue(TransferUnit(0, c) in requested, "missing chunk $c must be requested")
        // Many partially received files: widened into whole-file ranges, still covering every missing unit.
        val sizesMany = List(6000) { 2L * CHUNK_SIZE }
        val many = TransferLayout.of(sizesMany)
        val sparse = many.missingUnits { it.chunkIndex == 0 }
        assertTrue(ControlCodec.encodedSize(Resume(TEST_ID, sparse)) <= ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES)
        val covered = many.expand(sparse).map { it.unit }.toSet()
        for (f in sizesMany.indices) assertTrue(TransferUnit(f, 1) in covered)
    }

    @Test
    fun expandRejectsUnitsOutsideTheLayout() {
        assertProtocolError("chunk range too long") {
            layout.expand(MissingUnits(chunks = listOf(MissingChunks(1, listOf(IndexRange(2, 2))))))
        }
        assertProtocolError("bundled file as a key") {
            layout.expand(MissingUnits(chunks = listOf(MissingChunks(0, listOf(IndexRange(0, 1))))))
        }
        assertProtocolError("unknown key") { layout.expand(MissingUnits(chunks = listOf(MissingChunks(9, listOf(IndexRange(0, 1)))))) }
        assertProtocolError("file range too long") { layout.expand(MissingUnits(files = listOf(IndexRange(5, 2)))) }
        assertProtocolError("block offset beyond the unit") {
            layout.expand(MissingUnits(chunks = listOf(MissingChunks(1, listOf(IndexRange(2, 1)), firstBlockOffset = 16 * KIB))))
        }
        val noBundles = TransferLayout.of(listOf(5L), bundleSmall = false)
        assertProtocolError("no bundles") {
            noBundles.expand(MissingUnits(chunks = listOf(MissingChunks(BUNDLE_FILE_INDEX, listOf(IndexRange(0, 1))))))
        }
    }

    @Test
    fun layoutFromAnOfferChecksTheBundleCount() {
        val files = sizes.mapIndexed { i, s -> FileEntry(i, "f$i", s) }
        val offer = Offer(TEST_ID, fileCount = files.size, totalBytes = sizes.sum(), bundleCount = 1)
        assertEquals(layout.totalUnits, TransferLayout.of(offer, files).totalUnits)
        assertProtocolError { TransferLayout.of(offer.copy(bundleCount = 2), files) }
        assertProtocolError { TransferLayout.of(offer, files.dropLast(1)) }
        assertProtocolError { TransferLayout.of(offer, files.reversed()) }
    }
}
