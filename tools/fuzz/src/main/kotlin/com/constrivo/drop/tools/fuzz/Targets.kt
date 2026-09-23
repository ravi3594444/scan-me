package com.constrivo.drop.tools.fuzz

import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.BundleIndex
import com.constrivo.drop.core.protocol.BundlePlan
import com.constrivo.drop.core.protocol.Bytes
import com.constrivo.drop.core.protocol.ChunkFrame
import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ChunkHeader
import com.constrivo.drop.core.protocol.ControlCodec
import com.constrivo.drop.core.protocol.ControlMessage
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.FileList
import com.constrivo.drop.core.protocol.FileListAssembler
import com.constrivo.drop.core.protocol.FileListPager
import com.constrivo.drop.core.protocol.Frame
import com.constrivo.drop.core.protocol.FrameCodec
import com.constrivo.drop.core.protocol.FrameLimits
import com.constrivo.drop.core.protocol.FrameReader
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.IndexRange
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.MissingChunks
import com.constrivo.drop.core.protocol.MissingUnits
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.PlaintextFrameProtector
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.KIB
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.Resume
import com.constrivo.drop.core.protocol.Retransmit
import com.constrivo.drop.core.protocol.SessionRole
import com.constrivo.drop.core.protocol.StreamIdRegistry
import com.constrivo.drop.core.protocol.StreamOpen
import com.constrivo.drop.core.protocol.StreamOpenFrame
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.protocol.golden.GoldenVectors
import kotlinx.coroutines.runBlocking

/**
 * One decoder under fuzz. The property checked for every input: [run] returns normally or throws
 * [ProtocolException]; nothing else escapes, and the decoder allocates at most
 * [allocationLimit] bytes. Accepted inputs must also satisfy the target's round-trip property, reported as
 * [PropertyViolation].
 */
interface FuzzTarget {
    val name: String

    /** Valid encodings to start mutating from. */
    val seeds: List<ByteArray>

    /** The most bytes decoding [input] may allocate, from the limits the decoder declares. */
    fun allocationLimit(input: ByteArray): Long

    /**
     * Decodes [input]. Returns true when it was accepted, false when it was rejected; a rejection may also surface
     * as a [ProtocolException].
     */
    fun run(input: ByteArray): Boolean
}

/** An accepted input broke a round-trip property. */
class PropertyViolation(
    message: String,
) : RuntimeException(message)

private fun check(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw PropertyViolation(message())
}

private fun hex(hex: String): ByteArray = Bytes.fromHex(hex).toByteArray()

/** Fixed bookkeeping a decode may allocate regardless of input size (lists, exceptions, messages, coroutines). */
private const val BASE_ALLOCATION: Long = 512L * KIB

/** The fuzz targets for `core/protocol` (testing §5 "Fuzz" row). */
object ProtocolTargets {
    fun all(): List<FuzzTarget> =
        listOf(FrameDecoderTarget, ControlMessageTarget, ChunkHeaderTarget, BundleIndexTarget, StreamOpenTarget, LayoutTarget)

    fun byName(name: String): FuzzTarget = all().firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown target '$name'")
}

/**
 * The frame codec and the streaming [FrameReader] (7-byte reads). Property: both agree, and an accepted input
 * re-encodes to exactly itself. Odd-sized inputs are read with the handshake limits, so a declared 4 MiB payload must
 * be refused before allocation.
 */
object FrameDecoderTarget : FuzzTarget {
    override val name = "frame"

    override val seeds: List<ByteArray> by lazy {
        val frames = GoldenVectors.frames.map { hex(it.hex) }
        val controls = GoldenVectors.controlMessages.map { FrameCodec.encode(FrameType.CONTROL, hex(it.hex)) }
        val chunk = GoldenVectors.chunkHeaders.map { FrameCodec.encode(FrameType.CHUNK, hex(it.hex) + ByteArray(16)) }
        val handshake =
            listOf(FrameCodec.encode(FrameType.HELLO, ByteArray(80) { it.toByte() }), FrameCodec.encode(FrameType.HELLO_ACK, ByteArray(0)))
        frames + controls + chunk + handshake + listOf((frames + controls.take(3)).reduce { a, b -> a + b })
    }

    private fun limitsFor(input: ByteArray): FrameLimits = if (input.size % 2 == 0) FrameLimits.ALL else FrameLimits.HANDSHAKE

    override fun allocationLimit(input: ByteArray): Long =
        BASE_ALLOCATION + FrameReader.DEFAULT_BUFFER_SIZE + limitsFor(input).largestPayload + 8L * input.size

    override fun run(input: ByteArray): Boolean {
        val limits = limitsFor(input)
        val codec: List<Frame>? =
            try {
                FrameCodec.decodeAll(input, limits)
            } catch (e: ProtocolException) {
                null
            }
        val streamed = readAll(input, limits)
        check((codec == null) == (streamed == null)) { "FrameCodec and FrameReader disagree on accepting the input" }
        if (codec == null || streamed == null) return false
        check(codec == streamed) { "FrameCodec and FrameReader decoded different frames" }
        val again = codec.fold(ByteArray(0)) { acc, frame -> acc + FrameCodec.encode(frame) }
        check(again.contentEquals(input)) { "frames do not re-encode to the input" }
        return true
    }

    private fun readAll(
        input: ByteArray,
        limits: FrameLimits,
    ): List<Frame>? =
        runBlocking {
            val reader = FrameReader(ArrayChannel(input, maxRead = 7), bufferSize = 64)
            val frames = ArrayList<Frame>()
            try {
                while (true) frames += reader.readFrame(limits) ?: break
                frames
            } catch (e: ProtocolException) {
                null
            }
        }
}

/** [ControlCodec]. Property: an accepted message re-encodes canonically and decodes back to itself. */
object ControlMessageTarget : FuzzTarget {
    override val name = "control"

    override val seeds: List<ByteArray> by lazy { GoldenVectors.controlMessages.map { hex(it.hex) } }

    override fun allocationLimit(input: ByteArray): Long = BASE_ALLOCATION + 64L * input.size

    override fun run(input: ByteArray): Boolean {
        val message = ControlCodec.decode(input)
        // Re-encoding writes always-present defaults, so a message decoded from an input at the size limit may not
        // fit again; the property is checked with room to spare.
        if (input.size <= ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES - KIB) {
            val again = ControlCodec.encode(message)
            check(ControlCodec.decode(again) == message) { "${message.type} does not survive a round trip" }
            check(ControlCodec.encode(ControlCodec.decode(again)).contentEquals(again)) { "${message.type} encoding is not a fixpoint" }
        }
        return true
    }
}

/** [ChunkFrame] and [ChunkHeader]. Property: an accepted chunk re-encodes to exactly the input. */
object ChunkHeaderTarget : FuzzTarget {
    override val name = "chunk"

    override val seeds: List<ByteArray> by lazy {
        val headers = GoldenVectors.chunkHeaders.map { hex(it.hex) }
        val small =
            listOf(
                ChunkHeader(GoldenVectors.TRANSFER_ID, 3, 1, 0, 16, ChunkHash(ByteArray(16))) to ByteArray(16) { it.toByte() },
                ChunkHeader(
                    GoldenVectors.TRANSFER_ID,
                    ProtocolConstants.BUNDLE_FILE_INDEX,
                    0,
                    16 * KIB,
                    1,
                    ChunkHash(
                        ByteArray(16) {
                            -1
                        },
                    ),
                ) to
                    byteArrayOf(7),
            ).map { (h, p) -> ChunkFrame(h, p).encode() }
        headers + small
    }

    override fun allocationLimit(input: ByteArray): Long = BASE_ALLOCATION + 4L * input.size

    override fun run(input: ByteArray): Boolean {
        // The header alone, as the engine reads it before touching the payload.
        val headerOk =
            try {
                val header = ChunkHeader.decode(input)
                check(header.encode().contentEquals(input.copyOf(ChunkHeader.SIZE))) { "chunk header does not re-encode to the input" }
                true
            } catch (e: ProtocolException) {
                false
            }
        val frame = ChunkFrame.decode(input)
        check(headerOk) { "ChunkFrame accepted a header ChunkHeader rejected" }
        check(frame.encode().contentEquals(input)) { "chunk frame does not re-encode to the input" }
        return true
    }
}

/** [BundleIndex]. Property: the index re-encodes to the input's index bytes and every slice lies inside it. */
object BundleIndexTarget : FuzzTarget {
    override val name = "bundle"

    override val seeds: List<ByteArray> by lazy {
        val golden = GoldenVectors.bundles.map { hex(it.hex) }
        val plan = BundlePlan.of(listOf(5L, 0L, 900L, 3L, 1L))
        val planned = plan.bundles.map { bundle -> BundleIndex.encode(bundle, ByteArray(bundle.dataLength) { it.toByte() }) }
        golden + planned
    }

    override fun allocationLimit(input: ByteArray): Long = BASE_ALLOCATION + 16L * input.size

    override fun run(input: ByteArray): Boolean {
        val decoded = BundleIndex.decode(input)
        val index = BundleIndex.encodeIndex(decoded.entries)
        check(index.contentEquals(input.copyOf(decoded.dataOffset))) { "bundle index does not re-encode to the input" }
        var total = 0L
        for (entry in decoded.entries) {
            total += decoded.slice(input, entry).size
        }
        check(decoded.dataOffset + total == input.size.toLong()) { "bundle slices do not cover the data area" }
        return true
    }
}

/**
 * [StreamOpenFrame] with a pass-through protector, listening as the responder (so the initiator's even ids are
 * valid). Property: an accepted frame round-trips, and the same id cannot be opened twice.
 */
object StreamOpenTarget : FuzzTarget {
    override val name = "stream-open"

    override val seeds: List<ByteArray> by lazy {
        val golden = GoldenVectors.controlMessages.map { it.message }.filterIsInstance<StreamOpen>()
        (golden + golden.map { it.copy(streamId = 1_000_000) }).map { StreamOpenFrame.encode(PlaintextFrameProtector(), it) }
    }

    override fun allocationLimit(input: ByteArray): Long = BASE_ALLOCATION + 64L * input.size

    override fun run(input: ByteArray): Boolean {
        val protector = PlaintextFrameProtector()
        val open = StreamOpenFrame.open(protector, Frame(FrameType.STREAM_OPEN, input), StreamIdRegistry(SessionRole.RESPONDER))
        check(SessionRole.INITIATOR.opens(open.streamId)) { "accepted stream id ${open.streamId} from the wrong partition" }
        val again = StreamOpenFrame.encode(protector, open)
        val registry = StreamIdRegistry(SessionRole.RESPONDER)
        val reopened = StreamOpenFrame.open(protector, Frame(FrameType.STREAM_OPEN, again), registry)
        check(reopened == open) { "StreamOpen does not survive a round trip" }
        val replayed =
            try {
                StreamOpenFrame.open(protector, Frame(FrameType.STREAM_OPEN, again), registry)
                true
            } catch (e: ProtocolException) {
                false
            }
        check(!replayed) { "stream ${open.streamId} was opened twice" }
        return true
    }
}

/**
 * Peer data that describes a transfer: the receiver's [FileListAssembler] and [TransferLayout.of] over the sender's
 * `Offer` and `FileList` pages, and the sender's [TransferLayout.expand] over the receiver's `Resume`, `Retransmit`
 * or `Accept.resume`. An input is a sequence of `Control` frames holding plaintext envelopes: an `Offer`, its
 * `FileList` pages, then any number of those three.
 *
 * Property: every problem with the peer's data is a [ProtocolException]; an assembled list pages and assembles again
 * to itself; every expanded unit is in the layout, once, in global order, and asking for "everything but those"
 * with [TransferLayout.missingUnits] requests each of them again. Expansion allocates per unit, so it runs for
 * layouts of at most [MAX_EXPANDED_UNITS] units and at most [MAX_EXPANDED_MESSAGES] messages per input.
 */
object LayoutTarget : FuzzTarget {
    override val name = "layout"

    private const val MAX_EXPANDED_UNITS = 1024L
    private const val MAX_EXPANDED_MESSAGES = 4

    private val CONTROL_ONLY = FrameLimits.of(FrameType.CONTROL)

    override val seeds: List<ByteArray> by lazy {
        val id = GoldenVectors.TRANSFER_ID
        // The golden file list: file 0 is one 2.5 MB chunk, file 1 (42 bytes) is bundle 0.
        val goldenList = GoldenVectors.controlMessages.map { it.message }.filterIsInstance<FileList>().single()
        val goldenOffer = Offer(id, fileCount = 2, totalBytes = goldenList.files.sumOf { it.size }, bundleCount = 1)
        val golden =
            listOf(
                goldenOffer,
                goldenList,
                Resume(
                    id,
                    MissingUnits(
                        chunks =
                            listOf(
                                MissingChunks(0, listOf(IndexRange(0, 1)), firstBlockOffset = 16 * KIB),
                                MissingChunks(BUNDLE_FILE_INDEX, listOf(IndexRange(0, 1))),
                            ),
                    ),
                ),
                Retransmit(id, MissingUnits(files = listOf(IndexRange(0, 2)))),
                Accept(id, resume = MissingUnits.NONE, streamCount = 4),
            )
        // Thirty files of every kind over 64 KiB chunks, paged seven entries at a time.
        val chunk = ProtocolConstants.MIN_CHUNK_SIZE
        val sizes = List(30) { i -> listOf(0L, 10L, 3L * chunk + 5, chunk.toLong(), 7_000L)[i % 5] }
        val files = sizes.mapIndexed { i, size -> FileEntry(i, "dir/f$i.bin", size) }
        val plan = BundlePlan.of(sizes, chunk, bundleSmall = true)
        val offer = Offer(id, fileCount = files.size, totalBytes = sizes.sum(), chunkSize = chunk, bundleCount = plan.bundleCount)
        val layout = TransferLayout.of(offer, files)
        val paged =
            listOf(offer) + FileListPager.paginate(id, files, maxEntries = 7) +
                listOf(
                    Resume(id, layout.missingUnits { it.chunkIndex % 2 == 0 }),
                    Retransmit(id, layout.unitsOf(3)),
                    Retransmit(id, layout.unitsOf(1)),
                    Accept(id, resume = layout.everything(), streamCount = 8),
                )
        listOf(golden, paged, paged.take(1 + 5)).map { frames(it) }
    }

    private fun frames(messages: List<ControlMessage>): ByteArray =
        messages.fold(ByteArray(0)) { acc, m -> acc + FrameCodec.encode(FrameType.CONTROL, ControlCodec.encode(m)) }

    override fun allocationLimit(input: ByteArray): Long =
        BASE_ALLOCATION + 128L * input.size + MAX_EXPANDED_MESSAGES * MAX_EXPANDED_UNITS * KIB

    override fun run(input: ByteArray): Boolean {
        val messages = FrameCodec.decodeAll(input, CONTROL_ONLY).map { ControlCodec.decode(it.payload) }
        val offer = messages.firstOrNull() as? Offer ?: return false
        val assembler = FileListAssembler(offer)
        var next = 1
        var files: List<FileEntry>? = null
        while (files == null) {
            val page = messages.getOrNull(next++) as? FileList ?: return false
            files = assembler.add(page)
        }
        val layout = TransferLayout.of(offer, files)
        val again = FileListAssembler(offer)
        val repaged = FileListPager.paginate(offer.transferId, files).map { again.add(it) }
        check(repaged.last() == files) { "the file list does not page and assemble to itself" }

        val requests = messages.drop(next)
        for ((i, message) in requests.withIndex()) {
            val missing =
                when (message) {
                    is Resume -> message.missing
                    is Retransmit -> message.units
                    is Accept -> message.resume ?: continue
                    else -> return false
                }
            if (i >= MAX_EXPANDED_MESSAGES || layout.totalUnits > MAX_EXPANDED_UNITS) continue
            val units = layout.expand(missing)
            var previous = -1L
            for (unit in units) {
                check(layout.contains(unit.unit)) { "expanded ${unit.unit}, which is not in the layout" }
                val global = layout.globalIndex(unit.unit)
                check(global > previous) { "expanded units are not in strictly increasing global order" }
                check(unit.fromOffset in 0 until layout.unitLength(unit.unit)) { "offset ${unit.fromOffset} outside ${unit.unit}" }
                previous = global
            }
            val requested: Set<TransferUnit> = units.mapTo(HashSet()) { it.unit }
            val reRequested = layout.expand(layout.missingUnits { it !in requested }).mapTo(HashSet()) { it.unit }
            check(reRequested.containsAll(requested)) { "missingUnits dropped a unit that was not present" }
        }
        return true
    }
}

/** A read-only [DataChannel] over an array that returns at most [maxRead] bytes per read. */
internal class ArrayChannel(
    private val bytes: ByteArray,
    private val maxRead: Int,
) : DataChannel {
    private var position = 0
    override val kind: LinkKind = LinkKind.LAN

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length, maxRead, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + n)
        position += n
        return n
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Unit = throw UnsupportedOperationException("read-only channel")

    override suspend fun flush() {}

    override suspend fun close() {}
}
