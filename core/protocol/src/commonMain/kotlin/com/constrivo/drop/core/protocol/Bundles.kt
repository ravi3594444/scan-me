package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_INDEX_ENTRY_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_INDEX_HEADER_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_THRESHOLD
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILES_PER_TRANSFER
import com.constrivo.drop.core.protocol.ProtocolConstants.MIN_CHUNK_SIZE

/** One small file inside a bundle: `length` bytes at `offset` of the bundle's data area (§7.3). */
data class BundleEntry(
    val fileIndex: Int,
    val offset: Int,
    val length: Int,
) {
    init {
        requireFileIndex(fileIndex)
        require(offset >= 0 && length >= 0) { "bundle entry offset and length must be non-negative" }
    }
}

/**
 * Bundle number [index] of a transfer: its [entries] in file-index order, packed back to back.
 * The payload is the index (`u32 count` then 12 bytes per entry) followed by the data.
 */
data class Bundle(
    val index: Int,
    val entries: List<BundleEntry>,
) {
    init {
        require(index >= 0) { "bundle index must be non-negative" }
        require(entries.isNotEmpty()) { "a bundle holds at least one file" }
        var expected = 0L
        for (i in entries.indices) {
            val entry = entries[i]
            require(entry.offset.toLong() == expected) { "bundle entries must be contiguous from offset 0" }
            if (i > 0) require(entry.fileIndex > entries[i - 1].fileIndex) { "bundle entries must be in file-index order" }
            expected += entry.length
        }
        require(indexSize(entries.size) + expected <= CHUNK_SIZE) { "bundle exceeds $CHUNK_SIZE bytes" }
    }

    /** Bytes of file data. */
    val dataLength: Int get() = entries.sumOf { it.length }

    /** Bytes of the whole bundle payload: index plus data. */
    val payloadLength: Int get() = indexSize(entries.size) + dataLength

    val unit: TransferUnit get() = TransferUnit(BUNDLE_FILE_INDEX, index)

    companion object {
        fun indexSize(count: Int): Int = BUNDLE_INDEX_HEADER_SIZE + BUNDLE_INDEX_ENTRY_SIZE * count
    }
}

/**
 * The deterministic bundle plan of spec change S4: both devices derive it from the ordered file sizes, so a
 * `Resume` that names bundle *n* means the same files on both sides.
 *
 * Rule: walk the files in index order; a file smaller than [smallFileLimit] is small. Each small file joins the
 * current bundle if the bundle's index plus data would still fit in `chunkSize`, otherwise it starts a new bundle.
 * Files at or above the limit are chunked ([ChunkPlan]) and never bundled. With bundling off every file is chunked.
 */
class BundlePlan private constructor(
    val chunkSize: Int,
    val bundles: List<Bundle>,
    private val bundleOfFile: IntArray,
    private val slotOfFile: IntArray,
) {
    val bundleCount: Int get() = bundles.size

    fun isBundled(fileIndex: Int): Boolean = fileIndex in bundleOfFile.indices && bundleOfFile[fileIndex] >= 0

    /** The bundle holding [fileIndex], or null when that file is chunked. */
    fun bundleOf(fileIndex: Int): Bundle? = if (isBundled(fileIndex)) bundles[bundleOfFile[fileIndex]] else null

    /** The entry for [fileIndex] inside its bundle, or null when that file is chunked. */
    fun entryOf(fileIndex: Int): BundleEntry? = bundleOf(fileIndex)?.entries?.get(slotOfFile[fileIndex])

    companion object {
        /** Files below this many bytes are bundled: [ProtocolConstants.BUNDLE_THRESHOLD], or a quarter chunk if smaller. */
        fun smallFileLimit(chunkSize: Int): Int = minOf(BUNDLE_THRESHOLD, chunkSize / 4)

        /** The plan for files `0 until fileSizes.size` with these sizes. */
        fun of(
            fileSizes: List<Long>,
            chunkSize: Int = CHUNK_SIZE,
            bundleSmall: Boolean = true,
        ): BundlePlan {
            requireChunkSize(chunkSize)
            require(fileSizes.size <= MAX_FILES_PER_TRANSFER) { "too many files" }
            val bundleOfFile = IntArray(fileSizes.size) { -1 }
            val slotOfFile = IntArray(fileSizes.size) { -1 }
            val bundles = ArrayList<Bundle>()
            if (bundleSmall) {
                val limit = smallFileLimit(chunkSize)
                var current = ArrayList<BundleEntry>()
                var data = 0
                for ((index, size) in fileSizes.withIndex()) {
                    require(size >= 0) { "file sizes must be non-negative" }
                    if (size >= limit) continue
                    val length = size.toInt()
                    if (current.isNotEmpty() && Bundle.indexSize(current.size + 1) + data + length > chunkSize) {
                        bundles += Bundle(bundles.size, current)
                        current = ArrayList()
                        data = 0
                    }
                    bundleOfFile[index] = bundles.size
                    slotOfFile[index] = current.size
                    current += BundleEntry(index, data, length)
                    data += length
                }
                if (current.isNotEmpty()) bundles += Bundle(bundles.size, current)
            } else {
                fileSizes.forEach { require(it >= 0) { "file sizes must be non-negative" } }
            }
            return BundlePlan(chunkSize, bundles, bundleOfFile, slotOfFile)
        }

        /** The plan for a complete, ordered file list (indices 0 until size). */
        fun forFiles(
            files: List<FileEntry>,
            chunkSize: Int,
            bundleSmall: Boolean,
        ): BundlePlan {
            require(files.withIndex().all { (i, f) -> f.index == i }) { "file indices must run 0 until ${files.size} in order" }
            return of(files.map { it.size }, chunkSize, bundleSmall)
        }
    }
}

/**
 * The bundle payload codec (§7.3): `u32 count`, then `count` entries `{u32 file_index, u32 offset_in_bundle,
 * u32 len}`, then the concatenated file bytes. `offset_in_bundle` is relative to the start of the data area (the
 * byte after the index). Entries are in ascending file-index order and contiguous from offset 0, and the lengths add
 * up to the data area exactly.
 */
object BundleIndex {
    /** Builds the payload for [bundle] from its concatenated [data]. */
    fun encode(
        bundle: Bundle,
        data: ByteArray,
    ): ByteArray {
        require(data.size == bundle.dataLength) { "bundle data is ${data.size} bytes, plan says ${bundle.dataLength}" }
        val header = encodeIndex(bundle.entries)
        val out = ByteArray(header.size + data.size)
        header.copyInto(out)
        data.copyInto(out, header.size)
        return out
    }

    /** Only the index part of the payload, for writers that stream the data after it. */
    fun encodeIndex(entries: List<BundleEntry>): ByteArray {
        val out = ByteArray(Bundle.indexSize(entries.size))
        BigEndian.putU32(out, 0, entries.size)
        for ((i, entry) in entries.withIndex()) {
            val at = BUNDLE_INDEX_HEADER_SIZE + i * BUNDLE_INDEX_ENTRY_SIZE
            BigEndian.putU32(out, at, entry.fileIndex)
            BigEndian.putU32(out, at + 4, entry.offset)
            BigEndian.putU32(out, at + 8, entry.length)
        }
        return out
    }

    /**
     * Parses and validates a bundle payload. Returns the entries and the offset of the data area. Throws
     * [ProtocolException] for any malformed or inconsistent index. Allocation is bounded by the payload size.
     */
    fun decode(payload: ByteArray): DecodedBundle {
        if (payload.size > CHUNK_SIZE) throw ProtocolException("bundle payload of ${payload.size} bytes exceeds $CHUNK_SIZE")
        if (payload.size < BUNDLE_INDEX_HEADER_SIZE) throw ProtocolException("bundle payload shorter than its count")
        val count = BigEndian.getU32(payload, 0)
        if (count < 1) throw ProtocolException("bundle index is empty")
        if (count > (payload.size - BUNDLE_INDEX_HEADER_SIZE) / BUNDLE_INDEX_ENTRY_SIZE) {
            throw ProtocolException("bundle index declares $count entries, payload has room for fewer")
        }
        val n = count.toInt()
        val dataStart = Bundle.indexSize(n)
        val dataLength = payload.size - dataStart
        val entries = ArrayList<BundleEntry>(n)
        var expectedOffset = 0L
        var previousFile = -1L
        for (i in 0 until n) {
            val at = BUNDLE_INDEX_HEADER_SIZE + i * BUNDLE_INDEX_ENTRY_SIZE
            val file = BigEndian.getU32(payload, at)
            val offset = BigEndian.getU32(payload, at + 4)
            val length = BigEndian.getU32(payload, at + 8)
            if (file >= MAX_FILES_PER_TRANSFER) throw ProtocolException("bundle entry file_index $file out of range")
            if (file <= previousFile) throw ProtocolException("bundle entries not in ascending file-index order")
            if (offset != expectedOffset) throw ProtocolException("bundle entry $i at offset $offset, expected $expectedOffset")
            if (length > dataLength - expectedOffset) throw ProtocolException("bundle entry $i runs past the data area")
            entries += BundleEntry(file.toInt(), offset.toInt(), length.toInt())
            expectedOffset += length
            previousFile = file
        }
        if (expectedOffset != dataLength.toLong()) {
            throw ProtocolException("bundle entries cover $expectedOffset bytes, data area has $dataLength")
        }
        return DecodedBundle(entries, dataStart)
    }

    /** [decode], then checks the entries equal those of the planned [expected] bundle. */
    fun decode(
        payload: ByteArray,
        expected: Bundle,
    ): DecodedBundle {
        val decoded = decode(payload)
        if (decoded.entries != expected.entries) throw ProtocolException("bundle ${expected.index} does not match the bundle plan")
        return decoded
    }
}

/** A validated bundle payload: its [entries] and where the data area starts. */
class DecodedBundle(
    val entries: List<BundleEntry>,
    val dataOffset: Int,
) {
    /** The bytes of [entry] inside [payload]. */
    fun slice(
        payload: ByteArray,
        entry: BundleEntry,
    ): ByteArray = payload.copyOfRange(dataOffset + entry.offset, dataOffset + entry.offset + entry.length)
}

/**
 * How a chunked file splits into units (§7.3): [chunkCount] chunks of `chunkSize` bytes, the last one
 * [lastChunkSize] bytes. An empty file has no chunks; its `FileDone` alone completes it.
 */
data class ChunkPlan(
    val fileSize: Long,
    val chunkSize: Int,
) {
    init {
        require(fileSize >= 0) { "file size must be non-negative" }
        requireChunkSize(chunkSize)
        require(countChunks(fileSize, chunkSize) <= Int.MAX_VALUE) { "file too large for u32 chunk indices" }
    }

    val chunkCount: Int get() = countChunks(fileSize, chunkSize).toInt()

    val lastChunkSize: Int get() = if (chunkCount == 0) 0 else (fileSize - (chunkCount - 1).toLong() * chunkSize).toInt()

    fun chunkLength(chunkIndex: Int): Int {
        require(chunkIndex in 0 until chunkCount) { "chunk $chunkIndex out of range 0 until $chunkCount" }
        return if (chunkIndex == chunkCount - 1) lastChunkSize else chunkSize
    }

    /** Byte offset of [chunkIndex] in the file. */
    fun chunkOffset(chunkIndex: Int): Long {
        require(chunkIndex in 0 until chunkCount) { "chunk $chunkIndex out of range 0 until $chunkCount" }
        return chunkIndex.toLong() * chunkSize
    }

    /** Number of Bluetooth blocks of [blockSize] bytes in [chunkIndex] (spec change S1). */
    fun blockCount(
        chunkIndex: Int,
        blockSize: Int = ProtocolConstants.BLUETOOTH_BLOCK_SIZE,
    ): Int {
        require(blockSize >= 1) { "block size must be positive" }
        return (chunkLength(chunkIndex) + blockSize - 1) / blockSize
    }
}

/**
 * A unit the resume manifest tracks (§7.6 with S4): chunk [chunkIndex] of file [fileIndex], or bundle [chunkIndex]
 * when [fileIndex] is [ProtocolConstants.BUNDLE_FILE_INDEX].
 */
data class TransferUnit(
    val fileIndex: Int,
    val chunkIndex: Int,
) {
    init {
        requireFileIndex(fileIndex, allowBundle = true)
        require(chunkIndex >= 0) { "chunk index must be non-negative" }
    }

    val isBundle: Boolean get() = fileIndex == BUNDLE_FILE_INDEX

    override fun toString(): String = if (isBundle) "bundle#$chunkIndex" else "file$fileIndex/chunk$chunkIndex"
}

/**
 * Everything the transfer moves, as units (spec changes S1, S4): the bundles of the [bundlePlan] and the chunks of
 * each chunked file. Both sides build it from the `Offer` and the complete `FileList`.
 *
 * - **Tracking keys** ([trackingKeys]) are what `chunk_manifest.file_index` holds: [ProtocolConstants.BUNDLE_FILE_INDEX]
 *   for the bundles (bit *n* = bundle *n*), and each chunked file's index (bit *c* = chunk *c*). [unitCount] of a
 *   key is its bitmap length.
 * - **Global order** numbers all units 0 until [totalUnits]: bundles first (they are what the UI shows first, §7.5),
 *   then the chunks of each chunked file in file order. The scheduler may use it as the default queue order.
 * - [missingUnits] (receiver) and [expand] (sender) convert between "which units are present" and the
 *   [MissingUnits] carried by `Resume` and `Accept.resume`; [unitsOf] names the units of one file, for a
 *   [Retransmit] after a whole-file SHA-256 mismatch.
 */
class TransferLayout private constructor(
    val chunkSize: Int,
    val bundlePlan: BundlePlan,
    private val fileSizes: LongArray,
    /** Chunked files in index order. */
    private val chunkedFiles: IntArray,
    /** Chunk count of each chunked file, parallel to [chunkedFiles]. */
    private val chunkCounts: IntArray,
    /** Global index of the first chunk of each chunked file, parallel to [chunkedFiles]. */
    private val firstUnit: LongArray,
    /** Slot in [chunkedFiles] of each file, or -1 for bundled files. */
    private val slotOfFile: IntArray,
) {
    val fileCount: Int get() = fileSizes.size

    val bundleCount: Int get() = bundlePlan.bundleCount

    /** All units: bundles plus chunks of chunked files. */
    val totalUnits: Long = bundleCount.toLong() + chunkCounts.sumOf { it.toLong() }

    /** Sum of the file sizes (bundle index bytes excluded). */
    val totalBytes: Long = fileSizes.sum()

    /** Tracking keys, bundles first, then chunked files (including empty ones, which have no units) in index order. */
    val trackingKeys: List<Int> =
        buildList {
            if (bundleCount > 0) add(BUNDLE_FILE_INDEX)
            chunkedFiles.forEach { add(it) }
        }

    fun fileSize(fileIndex: Int): Long {
        require(fileIndex in fileSizes.indices) { "file $fileIndex out of range" }
        return fileSizes[fileIndex]
    }

    /** The chunk plan of [fileIndex], or null when the file is bundled. */
    fun chunkPlan(fileIndex: Int): ChunkPlan? {
        require(fileIndex in fileSizes.indices) { "file $fileIndex out of range" }
        return if (slotOfFile[fileIndex] < 0) null else ChunkPlan(fileSizes[fileIndex], chunkSize)
    }

    /** Bitmap length for tracking [key]: the bundle count, or the file's chunk count; 0 for a bundled file. */
    fun unitCount(key: Int): Int =
        when {
            key == BUNDLE_FILE_INDEX -> bundleCount
            key in fileSizes.indices -> slotOfFile[key].let { if (it < 0) 0 else chunkCounts[it] }
            else -> throw IllegalArgumentException("tracking key $key out of range")
        }

    /** Whether [unit] exists in this layout. */
    fun contains(unit: TransferUnit): Boolean =
        if (unit.isBundle) {
            unit.chunkIndex < bundleCount
        } else {
            unit.fileIndex in fileSizes.indices && unit.chunkIndex < unitCount(unit.fileIndex)
        }

    /** Plaintext payload bytes of [unit]: the bundle payload (index plus data) or the chunk length. */
    fun unitLength(unit: TransferUnit): Int {
        require(contains(unit)) { "$unit is not in this layout" }
        return if (unit.isBundle) {
            bundlePlan.bundles[unit.chunkIndex].payloadLength
        } else {
            ChunkPlan(fileSizes[unit.fileIndex], chunkSize).chunkLength(unit.chunkIndex)
        }
    }

    /** Position of [unit] in the global order. */
    fun globalIndex(unit: TransferUnit): Long {
        require(contains(unit)) { "$unit is not in this layout" }
        return if (unit.isBundle) unit.chunkIndex.toLong() else firstUnit[slotOfFile[unit.fileIndex]] + unit.chunkIndex
    }

    /** The unit at [globalIndex] in the global order. */
    fun unitAt(globalIndex: Long): TransferUnit {
        require(globalIndex in 0 until totalUnits) { "unit $globalIndex out of range 0 until $totalUnits" }
        if (globalIndex < bundleCount) return TransferUnit(BUNDLE_FILE_INDEX, globalIndex.toInt())
        // The last chunked file whose first unit is at or before globalIndex. An empty file shares its first unit with
        // the next file (or lies beyond the last unit), so the search always lands on a file that has chunks.
        var lo = 0
        var hi = chunkedFiles.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstUnit[mid] <= globalIndex) lo = mid else hi = mid - 1
        }
        return TransferUnit(chunkedFiles[lo], (globalIndex - firstUnit[lo]).toInt())
    }

    /** All units in global order. */
    fun units(): Sequence<TransferUnit> =
        sequence {
            for (b in 0 until bundleCount) yield(TransferUnit(BUNDLE_FILE_INDEX, b))
            for (slot in chunkedFiles.indices) {
                for (c in 0 until chunkCounts[slot]) yield(TransferUnit(chunkedFiles[slot], c))
            }
        }

    /**
     * Checks a received chunk [header] against this layout: the unit exists and `block_offset + payload_len` stays
     * inside it. A frame that carries a whole unit has `block_offset` 0 and `payload_len` equal to [unitLength].
     * Throws [ProtocolException] otherwise.
     */
    fun checkHeader(header: ChunkHeader) {
        val unit = header.unit
        if (!contains(unit)) throw ProtocolException("chunk frame for $unit, which is not in the transfer")
        val length = unitLength(unit)
        if (header.blockOffset.toLong() + header.payloadLength > length) {
            throw ProtocolException("chunk frame bytes ${header.blockOffset}+${header.payloadLength} exceed $unit's $length bytes")
        }
    }

    /**
     * The units that carry file [fileIndex], as a [MissingUnits] to request it again whole: its bundle for a bundled
     * file (resending the other files of that bundle too), a file range for a chunked file, and [MissingUnits.NONE]
     * for an empty chunked file, which has no units (its `FileDone` alone completes it).
     */
    fun unitsOf(fileIndex: Int): MissingUnits {
        require(fileIndex in fileSizes.indices) { "file $fileIndex out of range" }
        val bundle = bundlePlan.bundleOf(fileIndex)
        return when {
            bundle != null -> MissingUnits(chunks = listOf(MissingChunks(BUNDLE_FILE_INDEX, listOf(IndexRange(bundle.index, 1)))))
            unitCount(fileIndex) > 0 -> MissingUnits(files = listOf(IndexRange(fileIndex, 1)))
            else -> MissingUnits.NONE
        }
    }

    /** "Everything is missing": the [MissingUnits] of a fresh transfer. */
    fun everything(): MissingUnits = missingUnits { false }

    /**
     * The [MissingUnits] covering every unit for which [isPresent] is false (receiver side of §7.6).
     *
     * [presentBytes] reports how many leading bytes of a missing unit are already on disk (Bluetooth blocks, S1);
     * it is consulted for the first missing unit of each tracking key. Chunked files with every chunk missing become
     * file ranges. If the exact description exceeds the per-message limits, it is widened step by step (ranges
     * merged across present units, partial files requested whole, file ranges merged): the sender then re-sends some
     * units the receiver already has, which is safe, and never skips a missing one.
     */
    fun missingUnits(
        presentBytes: (TransferUnit) -> Int = { 0 },
        isPresent: (TransferUnit) -> Boolean,
    ): MissingUnits {
        val exact = ArrayList<MissingChunks>()
        val wholeFiles = ArrayList<Int>()
        for (key in trackingKeys.sortedBy { it.asU32() }) {
            val count = unitCount(key)
            val missing = ArrayList<Int>()
            for (c in 0 until count) if (!isPresent(TransferUnit(key, c))) missing += c
            if (missing.isEmpty()) continue
            val first = TransferUnit(key, missing[0])
            val offset = presentBytes(first).coerceIn(0, unitLength(first) - 1)
            if (key != BUNDLE_FILE_INDEX && missing.size == count && offset == 0) {
                wholeFiles += key
            } else {
                var ranges = IndexRange.coalesce(missing)
                while (ranges.size > ProtocolConstants.MAX_RANGES_PER_ENTRY) ranges = mergePairs(ranges)
                exact += MissingChunks(key, ranges, offset)
            }
        }
        var chunks: List<MissingChunks> = exact
        var files = joinAcrossUnitless(IndexRange.coalesce(wholeFiles))
        while (true) {
            if (withinLimits(chunks, files)) {
                val candidate = MissingUnits(chunks, files)
                if (fitsResume(candidate)) return candidate
            }
            when {
                chunks.any { it.ranges.size > 1 } -> {
                    chunks = chunks.map { MissingChunks(it.fileIndex, mergePairs(it.ranges), it.firstBlockOffset) }
                }

                chunks.any { it.fileIndex != BUNDLE_FILE_INDEX } -> {
                    val partial = chunks.filter { it.fileIndex != BUNDLE_FILE_INDEX }.map { it.fileIndex }
                    val all = (wholeFiles + partial).sorted()
                    wholeFiles.clear()
                    wholeFiles += all
                    chunks = chunks.filter { it.fileIndex == BUNDLE_FILE_INDEX }
                    files = joinAcrossUnitless(IndexRange.coalesce(all))
                }

                files.size > 1 -> {
                    files = mergePairs(files)
                }

                else -> {
                    error("a single bundle range and a single file range always fit a Resume")
                }
            }
        }
    }

    /**
     * Expands [missing] into its units, in global order, each with the byte offset to start from (sender side of
     * §7.6). For a `Resume` (or `Accept.resume`) the result is the whole queue: the sender replaces what it still had
     * queued with exactly these units. For a [Retransmit] the sender adds them to its queue and drops nothing. Throws
     * [ProtocolException] if [missing] names units that are not in this layout.
     */
    fun expand(missing: MissingUnits): List<ResumeUnit> {
        val out = ArrayList<ResumeUnit>()
        for (entry in missing.chunks) {
            val key = entry.fileIndex
            val count = if (key == BUNDLE_FILE_INDEX || key in fileSizes.indices) unitCount(key) else 0
            if (count == 0) throw ProtocolException("resume names tracking key $key, which has no units")
            for ((r, range) in entry.ranges.withIndex()) {
                if (range.endExclusive > count) throw ProtocolException("resume range $range beyond the $count units of key $key")
                for (c in range.start until range.endExclusive) {
                    val unit = TransferUnit(key, c)
                    val offset = if (r == 0 && c == range.start) entry.firstBlockOffset else 0
                    if (offset >= unitLength(unit)) throw ProtocolException("first_block_offset $offset beyond $unit")
                    out += ResumeUnit(unit, offset)
                }
            }
        }
        for (range in missing.files) {
            if (range.endExclusive > fileSizes.size) throw ProtocolException("resume file range $range beyond ${fileSizes.size} files")
            for (f in range.start until range.endExclusive) {
                for (c in 0 until unitCount(f)) out += ResumeUnit(TransferUnit(f, c), 0)
            }
        }
        out.sortBy { globalIndex(it.unit) }
        return out
    }

    /** Joins file ranges separated only by bundled or empty files, which a file range may cover. */
    private fun joinAcrossUnitless(ranges: List<IndexRange>): List<IndexRange> {
        if (ranges.size < 2) return ranges
        val out = ArrayList<IndexRange>()
        var current = ranges[0]
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            val gapHasNoUnits = (current.endExclusive until next.start).all { unitCount(it) == 0 }
            if (gapHasNoUnits) {
                current = IndexRange(current.start, next.endExclusive - current.start)
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun mergePairs(ranges: List<IndexRange>): List<IndexRange> =
        ranges.chunked(2).map { pair -> IndexRange(pair.first().start, pair.last().endExclusive - pair.first().start) }

    private fun withinLimits(
        chunks: List<MissingChunks>,
        files: List<IndexRange>,
    ): Boolean =
        chunks.size <= ProtocolConstants.MAX_RESUME_ENTRIES &&
            files.size <= ProtocolConstants.MAX_RESUME_ENTRIES &&
            chunks.all { it.ranges.size <= ProtocolConstants.MAX_RANGES_PER_ENTRY }

    private fun fitsResume(missing: MissingUnits): Boolean =
        try {
            ControlCodec.encodedSize(Resume(PLACEHOLDER_ID, missing)) <= ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES
        } catch (e: ProtocolException) {
            false
        }

    companion object {
        private val PLACEHOLDER_ID = TransferId(ByteArray(TransferId.SIZE))

        /** The layout for files `0 until fileSizes.size`. */
        fun of(
            fileSizes: List<Long>,
            chunkSize: Int = CHUNK_SIZE,
            bundleSmall: Boolean = true,
        ): TransferLayout {
            val plan = BundlePlan.of(fileSizes, chunkSize, bundleSmall)
            val chunked = fileSizes.indices.filter { !plan.isBundled(it) }.toIntArray()
            val counts = IntArray(chunked.size) { ChunkPlan(fileSizes[chunked[it]], chunkSize).chunkCount }
            val first = LongArray(chunked.size)
            val slots = IntArray(fileSizes.size) { -1 }
            var next = plan.bundleCount.toLong()
            for ((slot, file) in chunked.withIndex()) {
                first[slot] = next
                slots[file] = slot
                next += counts[slot]
            }
            return TransferLayout(chunkSize, plan, fileSizes.toLongArray(), chunked, counts, first, slots)
        }

        /**
         * The layout for [offer] and its complete, peer-supplied file list. Checks what [FileListAssembler] checks, so
         * every problem with the peer's data is a [ProtocolException]: the file count and indices, sizes adding up to
         * `total_bytes` (without overflow), at most `Int.MAX_VALUE` chunks per file, and the bundle count.
         */
        fun of(
            offer: Offer,
            files: List<FileEntry>,
        ): TransferLayout {
            if (files.size != offer.fileCount) throw ProtocolException("file list has ${files.size} files, offer says ${offer.fileCount}")
            var total = 0L
            for ((i, file) in files.withIndex()) {
                if (file.index != i) throw ProtocolException("file indices must run 0 until ${files.size}")
                if (file.size > offer.totalBytes - total) throw ProtocolException("file sizes exceed total_bytes ${offer.totalBytes}")
                checkChunkCount(file.index, file.size, offer.chunkSize)
                total += file.size
            }
            if (total != offer.totalBytes) throw ProtocolException("file sizes add up to $total, offer says ${offer.totalBytes}")
            val layout = of(files.map { it.size }, offer.chunkSize, offer.bundleSmall)
            if (layout.bundleCount != offer.bundleCount) {
                throw ProtocolException("bundle plan has ${layout.bundleCount} bundles, offer says ${offer.bundleCount}")
            }
            return layout
        }
    }
}

/** A unit to (re)send and the byte offset inside it to start from (non-zero only after partial Bluetooth blocks). */
data class ResumeUnit(
    val unit: TransferUnit,
    val fromOffset: Int,
)

/**
 * Throws [ProtocolException] when a peer-supplied file [fileIndex] of [size] bytes needs more chunks of [chunkSize]
 * than a u31 chunk index can name (a [ChunkPlan] would reject it with [IllegalArgumentException]).
 */
internal fun checkChunkCount(
    fileIndex: Int,
    size: Long,
    chunkSize: Int,
) {
    if (countChunks(size, chunkSize) > Int.MAX_VALUE) {
        throw ProtocolException("file $fileIndex of $size bytes needs more than ${Int.MAX_VALUE} chunks of $chunkSize bytes")
    }
}

/** Chunks needed for [fileSize] bytes, without overflowing near Long.MAX_VALUE. */
private fun countChunks(
    fileSize: Long,
    chunkSize: Int,
): Long = fileSize / chunkSize + if (fileSize % chunkSize != 0L) 1 else 0

private fun requireChunkSize(chunkSize: Int) {
    require(chunkSize in MIN_CHUNK_SIZE..CHUNK_SIZE && chunkSize and (chunkSize - 1) == 0) {
        "chunk size $chunkSize must be a power of two in $MIN_CHUNK_SIZE..$CHUNK_SIZE"
    }
}
