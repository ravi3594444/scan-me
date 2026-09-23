package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILES_PER_TRANSFER
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILE_NAME_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_MIME_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_PREVIEW_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_RANGES_PER_ENTRY
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_RESUME_ENTRIES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_WIRE_NAME_BYTES
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Records and codes shared by the control messages (architecture §7.2). Enumerations travel as their wire names
// (short lower-case strings, like LinkKind and HintCode); reason and purpose codes decode an unknown name as OTHER /
// DATA so that a newer peer can add codes (forward-compatibility rule in ControlCodec).

/** Why the receiver declined an `Offer`. An unknown wire name decodes as [OTHER]. */
@Serializable(with = DeclineReasonSerializer::class)
enum class DeclineReason(
    val wireName: String,
) {
    /** The user tapped Decline. */
    USER("user"),

    /** Another transfer is running and the receiver does not queue this one. */
    BUSY("busy"),

    /** The 30 s incoming card timed out (§7.8). */
    TIMEOUT("timeout"),

    /** Not enough free space for `total_bytes`. */
    STORAGE("storage"),

    /** Unsupported `version` or `chunk_size`. */
    INCOMPATIBLE("incompatible"),

    /** Visibility or a block list refused the sender. */
    BLOCKED("blocked"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(name: String): DeclineReason? = entries.firstOrNull { it.wireName == name }
    }
}

/** Why a transfer was cancelled (§7.8). An unknown wire name decodes as [OTHER]. */
@Serializable(with = CancelReasonSerializer::class)
enum class CancelReason(
    val wireName: String,
) {
    USER("user"),

    /** Disk full on the receiver: partials are cleared (§7.8). */
    STORAGE("storage"),

    /** Offer unanswered for 30 s, or the 24 h parked window ran out (§7.8, S8). */
    TIMEOUT("timeout"),

    /** Every file failed verification (three mismatches, §7.8). */
    VERIFICATION("verification"),

    /** The peer violated the protocol (a [ProtocolException] on an authenticated stream). */
    PROTOCOL("protocol"),

    /** The sender can no longer read a source file. */
    SOURCE("source"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(name: String): CancelReason? = entries.firstOrNull { it.wireName == name }
    }
}

/** Outcome carried by `Complete`. An unknown wire name decodes as [FAILED] (the conservative reading). */
@Serializable(with = CompleteStatusSerializer::class)
enum class CompleteStatus(
    val wireName: String,
) {
    /** Every file arrived and verified. */
    OK("ok"),

    /** Some files failed after three mismatches (`Complete.failed_files`); the rest verified. */
    PARTIAL("partial"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(name: String): CompleteStatus? = entries.firstOrNull { it.wireName == name }
    }
}

/** Direction in which `Chunk` frames flow on a data stream (spec change S7). Unknown names are rejected. */
@Serializable(with = StreamDirectionSerializer::class)
enum class StreamDirection(
    val wireName: String,
) {
    SENDER_TO_RECEIVER("s2r"),
    RECEIVER_TO_SENDER("r2s"),
    ;

    companion object {
        fun fromWire(name: String): StreamDirection? = entries.firstOrNull { it.wireName == name }
    }
}

/** What a data stream is for (spec changes S7, N13). An unknown wire name decodes as [DATA]. */
@Serializable(with = StreamPurposeSerializer::class)
enum class StreamPurpose(
    val wireName: String,
) {
    /** Chunk frames only. */
    DATA("data"),

    /** Chunk frames, and control frames once a `ControlMoved` names this stream (N13). */
    CONTROL("control"),
    ;

    companion object {
        fun fromWire(name: String): StreamPurpose? = entries.firstOrNull { it.wireName == name }
    }
}

/** Serializes a code as its wire name; unknown names decode as [fallback], or fail when it is null. */
internal open class WireNameSerializer<T : Any>(
    serialName: String,
    private val wireName: (T) -> String,
    private val lookup: (String) -> T?,
    private val fallback: T?,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = encoder.encodeString(wireName(value))

    override fun deserialize(decoder: Decoder): T {
        val name = decoder.decodeString()
        return lookup(name) ?: fallback ?: throw SerializationException("unknown ${descriptor.serialName} '$name'")
    }
}

internal object DeclineReasonSerializer :
    WireNameSerializer<DeclineReason>("drop.DeclineReason", { it.wireName }, DeclineReason::fromWire, DeclineReason.OTHER)

internal object CancelReasonSerializer :
    WireNameSerializer<CancelReason>("drop.CancelReason", { it.wireName }, CancelReason::fromWire, CancelReason.OTHER)

internal object CompleteStatusSerializer :
    WireNameSerializer<CompleteStatus>("drop.CompleteStatus", { it.wireName }, CompleteStatus::fromWire, CompleteStatus.FAILED)

internal object StreamDirectionSerializer :
    WireNameSerializer<StreamDirection>("drop.StreamDirection", { it.wireName }, StreamDirection::fromWire, null)

internal object StreamPurposeSerializer :
    WireNameSerializer<StreamPurpose>("drop.StreamPurpose", { it.wireName }, StreamPurpose::fromWire, StreamPurpose.DATA)

/**
 * A `file_index` that may be the bundle marker: CBOR unsigned 0..0xFFFFFFFF, where 0xFFFFFFFF is
 * [ProtocolConstants.BUNDLE_FILE_INDEX] (-1 in Kotlin), matching the binary chunk header (§7.3).
 */
internal object WireFileIndexSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("drop.FileIndex", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeLong(value.asU32())

    override fun deserialize(decoder: Decoder): Int {
        val value = decoder.decodeLong()
        return when (value) {
            0xFFFF_FFFFL -> ProtocolConstants.BUNDLE_FILE_INDEX
            in 0L..Int.MAX_VALUE.toLong() -> value.toInt()
            else -> throw SerializationException("file_index $value out of range")
        }
    }
}

/**
 * SSID and passphrase of a Wi-Fi Direct group or hotspot, generated by whichever device hosts it (spec change S5).
 * [toString] hides the passphrase.
 */
@Serializable
data class WifiCredentials(
    @CborLabel(1) val ssid: String,
    @CborLabel(2) val passphrase: String,
) {
    init {
        requireText(ssid, "ssid", max = 32)
        requireText(passphrase, "passphrase", max = 128, min = 8)
    }

    override fun toString(): String = "WifiCredentials(ssid=$ssid, passphrase=<redacted>)"
}

/**
 * A link the sender can offer (`Offer.link_options`). When the sender will be the group owner it puts the
 * [credentials] here (S5); a LAN option carries the sender's [address] and [port].
 * [kind] is a [LinkKind] wire name kept as a string so options of kinds this version does not know survive decoding
 * and are skipped ([linkKind] is null for them).
 */
@Serializable
data class LinkOption(
    @CborLabel(1) val kind: String,
    @CborLabel(2) val credentials: WifiCredentials? = null,
    @CborLabel(3) val address: String? = null,
    @CborLabel(4) val port: Int? = null,
) {
    constructor(
        kind: LinkKind,
        credentials: WifiCredentials? = null,
        address: String? = null,
        port: Int? = null,
    ) : this(kind.wireName, credentials, address, port)

    init {
        requireText(kind, "link kind", max = MAX_WIRE_NAME_BYTES)
        address?.let { requireText(it, "address", max = MAX_ADDRESS_BYTES) }
        port?.let { require(it in 1..65535) { "port $it out of range" } }
    }

    val linkKind: LinkKind? get() = LinkKind.fromWire(kind)
}

/**
 * The receiver's link choice in `Accept` (S5 sharpened): the [kind] it will bring up and, when the receiver hosts
 * the group or hotspot, the [credentials]. Addresses are unknown before the link exists and follow in `LinkReady`.
 */
@Serializable
data class LinkIntent(
    @CborLabel(1) val kind: String,
    @CborLabel(2) val credentials: WifiCredentials? = null,
) {
    constructor(kind: LinkKind, credentials: WifiCredentials? = null) : this(kind.wireName, credentials)

    init {
        requireText(kind, "link kind", max = MAX_WIRE_NAME_BYTES)
    }

    val linkKind: LinkKind? get() = LinkKind.fromWire(kind)
}

/**
 * One file in a `FileList` page (N12). [name] is a relative path with `/` separators; sanitising and confinement
 * are the receiver's job (WP12). Hashes are not here: they follow in `FileDone` (S2).
 */
@Serializable
data class FileEntry(
    @CborLabel(1) val index: Int,
    @CborLabel(2) val name: String,
    @CborLabel(3) val size: Long,
    @CborLabel(4) val mime: String? = null,
    /** Last-modified time, Unix epoch milliseconds, when the sender knows it. */
    @CborLabel(5) val modifiedMillis: Long? = null,
) {
    init {
        requireFileIndex(index, "file index")
        requireText(name, "file name", max = MAX_FILE_NAME_BYTES)
        require(size >= 0) { "file size must be non-negative" }
        mime?.let { requireText(it, "mime type", max = MAX_MIME_BYTES) }
    }
}

/** A thumbnail for the incoming card (N12, design §5.1): at most [ProtocolConstants.MAX_PREVIEW_BYTES] of [mime] data. */
@Serializable
data class Preview(
    @CborLabel(1) val fileIndex: Int,
    @CborLabel(2) val mime: String,
    @CborLabel(3) val data: Bytes,
) {
    init {
        requireFileIndex(fileIndex, "preview file index")
        requireText(mime, "preview mime type", max = MAX_MIME_BYTES)
        require(data.size in 1..MAX_PREVIEW_BYTES) { "preview must be 1..$MAX_PREVIEW_BYTES bytes, got ${data.size}" }
    }
}

/**
 * A verified unit in an `Ack` (§7.2). Without [blockOffset] it acknowledges the whole unit: chunk [chunkIndex] of
 * file [fileIndex], or bundle [chunkIndex] when [fileIndex] is [ProtocolConstants.BUNDLE_FILE_INDEX]. With
 * [blockOffset] (0 included) it acknowledges only the Bluetooth block frame that started at that byte offset inside
 * the unit (spec change S1).
 */
@Serializable
data class ChunkRef(
    @CborLabel(1) @Serializable(with = WireFileIndexSerializer::class) val fileIndex: Int,
    @CborLabel(2) val chunkIndex: Int,
    @CborLabel(3) val blockOffset: Int? = null,
) {
    init {
        requireFileIndex(fileIndex, allowBundle = true)
        require(chunkIndex >= 0) { "chunk_index must be non-negative" }
        blockOffset?.let { require(it in 0 until CHUNK_SIZE) { "block_offset $it out of range" } }
    }

    val unit: TransferUnit get() = TransferUnit(fileIndex, chunkIndex)
}

/**
 * The half-open range `start until start + count` of chunk, bundle or file indices. Encoded as the two-element
 * CBOR array `[start, count]`.
 */
@Serializable
@CborArray
data class IndexRange(
    val start: Int,
    val count: Int,
) {
    init {
        require(start >= 0) { "range start must be non-negative" }
        require(count >= 1) { "range count must be positive" }
        require(start.toLong() + count <= Int.MAX_VALUE) { "range end overflows" }
    }

    val endExclusive: Int get() = start + count

    operator fun contains(index: Int): Boolean = index in start until endExclusive

    companion object {
        /** Coalesces ascending, distinct [indices] into the fewest ranges. */
        fun coalesce(indices: Iterable<Int>): List<IndexRange> {
            val out = ArrayList<IndexRange>()
            var runStart = -1
            var runEnd = -1
            for (i in indices) {
                if (runStart >= 0) require(i >= runEnd) { "indices must be ascending and distinct" }
                if (runStart >= 0 && i == runEnd) {
                    runEnd++
                } else {
                    if (runStart >= 0) out += IndexRange(runStart, runEnd - runStart)
                    runStart = i
                    runEnd = i + 1
                }
            }
            if (runStart >= 0) out += IndexRange(runStart, runEnd - runStart)
            return out
        }
    }
}

/**
 * Missing units of one tracking key (spec changes S1, S4): chunk [ranges] of file [fileIndex], or bundle ranges when
 * [fileIndex] is [ProtocolConstants.BUNDLE_FILE_INDEX]. [firstBlockOffset] says the receiver already holds bytes
 * `0 until firstBlockOffset` of the first missing unit (Bluetooth blocks), so the sender resumes it from there.
 * Ranges are ascending and separated by at least one present unit.
 */
@Serializable
data class MissingChunks(
    @CborLabel(1) @Serializable(with = WireFileIndexSerializer::class) val fileIndex: Int,
    @CborLabel(2) val ranges: List<IndexRange>,
    @CborLabel(3) val firstBlockOffset: Int = 0,
) {
    init {
        requireFileIndex(fileIndex, allowBundle = true)
        require(ranges.size in 1..MAX_RANGES_PER_ENTRY) { "1..$MAX_RANGES_PER_ENTRY ranges per entry, got ${ranges.size}" }
        for (i in 1 until ranges.size) {
            require(ranges[i].start > ranges[i - 1].endExclusive) { "ranges must be ascending and coalesced" }
        }
        require(firstBlockOffset in 0 until CHUNK_SIZE) { "first_block_offset $firstBlockOffset out of range" }
    }
}

/**
 * What a receiver still needs (`Resume.missing`, `Accept.resume`; §7.6 with S1, S4):
 * - [chunks]: per tracking key, the missing unit ranges; entries in ascending unsigned `file_index` order, so the
 *   bundle entry (0xFFFFFFFF) comes last;
 * - [files]: ranges of file indices whose chunked files are missing entirely; bundled and empty files inside a range
 *   are ignored because bundles are tracked by the bundle entry. A file index in [chunks] must not also fall inside
 *   [files].
 *
 * Units not listed are present. An empty value means nothing is missing. Both lists are always written, even when
 * empty: the CBOR decoder reads an empty map in a nullable field as null, and `Accept.resume = {}` ("nothing is
 * missing") must not turn into `null` ("fresh transfer").
 */
@Serializable
data class MissingUnits(
    @CborLabel(1) @EncodeDefault val chunks: List<MissingChunks> = emptyList(),
    @CborLabel(2) @EncodeDefault val files: List<IndexRange> = emptyList(),
) {
    init {
        require(chunks.size <= MAX_RESUME_ENTRIES) { "at most $MAX_RESUME_ENTRIES chunk entries" }
        require(files.size <= MAX_RESUME_ENTRIES) { "at most $MAX_RESUME_ENTRIES file ranges" }
        for (i in 1 until chunks.size) {
            require(chunks[i].fileIndex.asU32() > chunks[i - 1].fileIndex.asU32()) { "chunk entries must be in ascending file_index order" }
        }
        for (i in files.indices) {
            require(files[i].endExclusive <= MAX_FILES_PER_TRANSFER) { "file range beyond the file limit" }
            if (i > 0) require(files[i].start > files[i - 1].endExclusive) { "file ranges must be ascending and coalesced" }
        }
        var r = 0
        for (entry in chunks) {
            if (entry.fileIndex == ProtocolConstants.BUNDLE_FILE_INDEX) continue
            while (r < files.size && files[r].endExclusive <= entry.fileIndex) r++
            require(r >= files.size || entry.fileIndex !in files[r]) { "file ${entry.fileIndex} is listed both whole and by chunks" }
        }
    }

    val isEmpty: Boolean get() = chunks.isEmpty() && files.isEmpty()

    companion object {
        val NONE: MissingUnits = MissingUnits()
    }
}

/** Longest address in `LinkOption` / `LinkReady`: an IP literal or a DNS name. */
internal const val MAX_ADDRESS_BYTES: Int = 253
