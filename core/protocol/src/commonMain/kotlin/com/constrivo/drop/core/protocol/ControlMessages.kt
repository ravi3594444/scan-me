package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_ACK_REFS
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILES_PER_TRANSFER
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILE_LIST_PAGE_ENTRIES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILE_NAME_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_HINT_PARAMS
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_HINT_PARAM_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_LINK_OPTIONS
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_MIME_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_MIME_HISTOGRAM_ENTRIES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_PREVIEWS
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_PREVIEW_NAMES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_RESUME_ENTRIES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_STREAMS
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_WIRE_NAME_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MIN_CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.PROTOCOL_VERSION
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/**
 * Control message types and their envelope codes (architecture §7.2). Codes are never reused or renumbered.
 * See [ControlCodec] for the envelope and the forward-compatibility rules.
 */
enum class ControlMessageType(
    val code: Int,
) {
    OFFER(1),
    FILE_LIST(2),
    ACCEPT(3),
    DECLINE(4),
    ACK(5),
    RESUME(6),
    HINT(7),
    LINK_READY(8),
    HEARTBEAT(9),
    FILE_DONE(10),
    COMPLETE(11),
    CANCEL(12),
    CONTROL_MOVED(13),
    TRUST_SHARE(14),
    STREAM_OPEN(15),
    RETRANSMIT(16),
    ;

    companion object {
        private val byCode: Map<Int, ControlMessageType> = entries.associateBy { it.code }

        fun fromCode(code: Int): ControlMessageType? = byCode[code]
    }
}

/**
 * A control message (architecture §7.2). Every subtype is a `@Serializable` data class whose properties carry
 * integer `@CborLabel`s, declared in ascending label order so the encoding is deterministic. Encode and decode with
 * [ControlCodec]. Constructors validate their arguments (throwing [IllegalArgumentException]); decoding reports the
 * same violations as [ProtocolException].
 */
sealed interface ControlMessage {
    val type: ControlMessageType
}

/**
 * S→R: the transfer summary the incoming card shows (spec change N12), small enough to arrive within 300 ms over
 * RFCOMM. The full file list follows after `Accept` as [FileList] pages; hashes follow as [FileDone] (S2).
 *
 * @property version protocol version of this transfer ([ProtocolConstants.PROTOCOL_VERSION]); a receiver that does
 *   not support it declines with [DeclineReason.INCOMPATIBLE].
 * @property mimeHistogram MIME type (or a wildcard bucket, see [MimeHistogram]) to file count; counts sum to at most
 *   [fileCount].
 * @property previewNames names of the first files, for the card.
 * @property chunkSize a power of two in [ProtocolConstants.MIN_CHUNK_SIZE]..[ProtocolConstants.CHUNK_SIZE].
 * @property bundleSmall whether small files are bundled (S4); [bundleCount] is the number of bundles the
 *   deterministic [BundlePlan] yields, which the receiver re-derives and checks.
 * @property linkOptions links the sender offers; when the sender will be group owner, with credentials (S5).
 */
@Serializable
data class Offer(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) @EncodeDefault val version: Int = PROTOCOL_VERSION,
    @CborLabel(3) val fileCount: Int,
    @CborLabel(4) val totalBytes: Long,
    @CborLabel(5) @Serializable(with = MimeHistogramSerializer::class) val mimeHistogram: Map<String, Int> = emptyMap(),
    @CborLabel(6) val previewNames: List<String> = emptyList(),
    @CborLabel(7) val previews: List<Preview> = emptyList(),
    @CborLabel(8) @EncodeDefault val chunkSize: Int = CHUNK_SIZE,
    @CborLabel(9) @EncodeDefault val bundleSmall: Boolean = true,
    @CborLabel(10) val bundleCount: Int,
    @CborLabel(11) val linkOptions: List<LinkOption> = emptyList(),
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.OFFER

    init {
        require(version >= 1) { "version must be at least 1" }
        require(fileCount in 1..MAX_FILES_PER_TRANSFER) { "file_count $fileCount out of range" }
        require(totalBytes >= 0) { "total_bytes must be non-negative" }
        require(mimeHistogram.size <= MAX_MIME_HISTOGRAM_ENTRIES) { "at most $MAX_MIME_HISTOGRAM_ENTRIES histogram entries" }
        var histogramTotal = 0L
        for ((mime, count) in mimeHistogram) {
            requireText(mime, "histogram mime type", max = MAX_MIME_BYTES)
            require(count >= 1) { "histogram counts must be positive" }
            histogramTotal += count
        }
        require(histogramTotal <= fileCount) { "histogram counts exceed file_count" }
        require(previewNames.size <= minOf(MAX_PREVIEW_NAMES, fileCount)) { "too many preview names" }
        previewNames.forEach { requireText(it, "preview name", max = MAX_FILE_NAME_BYTES) }
        require(previews.size <= MAX_PREVIEWS) { "at most $MAX_PREVIEWS previews" }
        require(previews.all { it.fileIndex < fileCount }) { "preview file index beyond file_count" }
        require(previews.map { it.fileIndex }.toSet().size == previews.size) { "one preview per file" }
        require(chunkSize in MIN_CHUNK_SIZE..CHUNK_SIZE && chunkSize and (chunkSize - 1) == 0) {
            "chunk_size $chunkSize must be a power of two in $MIN_CHUNK_SIZE..$CHUNK_SIZE"
        }
        require(bundleCount in 0..fileCount) { "bundle_count $bundleCount out of range" }
        require(bundleSmall || bundleCount == 0) { "bundle_count must be 0 when bundle_small is false" }
        require(linkOptions.size <= MAX_LINK_OPTIONS) { "at most $MAX_LINK_OPTIONS link options" }
    }
}

/**
 * S→R after `Accept`: one page of the file list (N12). Pages are numbered from 0 and sent in order; file indices
 * run 0 until `Offer.file_count` across the pages; [last] marks the final page. Build pages with [FileListPager]
 * and check them with [FileListAssembler].
 */
@Serializable
data class FileList(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val page: Int,
    @CborLabel(3) val last: Boolean,
    @CborLabel(4) val files: List<FileEntry>,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.FILE_LIST

    init {
        require(page >= 0) { "page must be non-negative" }
        require(files.size in 1..MAX_FILE_LIST_PAGE_ENTRIES) { "1..$MAX_FILE_LIST_PAGE_ENTRIES files per page, got ${files.size}" }
        for (i in 1 until files.size) require(files[i].index > files[i - 1].index) { "file indices must increase within a page" }
    }
}

/**
 * R→S: the receiver accepts (§7.2 with S5). [link] is its link intent, with credentials when the receiver hosts;
 * [resume] lists what it still needs when it already holds part of this transfer (null: a fresh transfer, everything
 * is needed); [streamCount] is the most data streams it will take (1..[ProtocolConstants.MAX_STREAMS]).
 */
@Serializable
data class Accept(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val link: LinkIntent? = null,
    @CborLabel(3) val resume: MissingUnits? = null,
    @CborLabel(4) val streamCount: Int,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.ACCEPT

    init {
        require(streamCount in 1..MAX_STREAMS) { "stream_count $streamCount out of range" }
    }
}

/** R→S: the receiver declines the offer. */
@Serializable
data class Decline(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val reason: DeclineReason,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.DECLINE
}

/**
 * R→S: verified units, batched every [ProtocolConstants.ACK_BATCH_MILLIS] ms or
 * [ProtocolConstants.ACK_BATCH_CHUNKS] units (§7.2); Bluetooth blocks are acked with [ChunkRef.blockOffset] (S1).
 */
@Serializable
data class Ack(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val chunks: List<ChunkRef>,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.ACK

    init {
        require(chunks.size in 1..MAX_ACK_REFS) { "1..$MAX_ACK_REFS refs per ack, got ${chunks.size}" }
    }
}

/**
 * R→S on every reconnect (§7.6, S1, S4): the **complete** set of units the receiver still needs. Units not listed are
 * present, so the sender replaces whatever it still had queued with exactly [missing] ([TransferLayout.expand]).
 * Re-requests while connected use [Retransmit], which adds to the queue instead.
 */
@Serializable
data class Resume(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val missing: MissingUnits,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.RESUME
}

/**
 * R→S while connected (§7.2, §7.8): send [units] again, after a per-frame hash mismatch (that unit, from the bad
 * Bluetooth block's offset through [MissingChunks.firstBlockOffset], S1) or a whole-file SHA-256 mismatch (the
 * suspect units, or [TransferLayout.unitsOf]). **Additive**: the sender queues these units in addition to everything
 * it still has to send and drops nothing; unlike [Resume], units not listed say nothing about what the receiver has.
 * Never empty.
 */
@Serializable
data class Retransmit(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val units: MissingUnits,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.RETRANSMIT

    init {
        require(!units.isEmpty) { "a retransmit names at least one unit" }
    }
}

/**
 * Either direction: a speed hint (§7.2, design §8.2). [code] is a [HintCode] wire name kept as a string so hints
 * from a newer peer survive decoding ([hintCode] is null for them and the UI skips them). [transferId] is set when
 * the hint concerns one transfer (for example `bundling`).
 */
@Serializable
data class Hint(
    @CborLabel(1) val code: String,
    @CborLabel(2) @Serializable(with = HintParamsSerializer::class) val params: Map<String, String> = emptyMap(),
    @CborLabel(3) val transferId: TransferId? = null,
) : ControlMessage {
    constructor(
        code: HintCode,
        params: Map<String, String> = emptyMap(),
        transferId: TransferId? = null,
    ) : this(code.wireName, params, transferId)

    override val type: ControlMessageType get() = ControlMessageType.HINT

    val hintCode: HintCode? get() = HintCode.fromWire(code)

    init {
        requireText(code, "hint code", max = MAX_WIRE_NAME_BYTES)
        require(params.size <= MAX_HINT_PARAMS) { "at most $MAX_HINT_PARAMS hint params" }
        for ((key, value) in params) {
            requireText(key, "hint param name", max = MAX_WIRE_NAME_BYTES)
            requireText(value, "hint param value", max = MAX_HINT_PARAM_BYTES, min = 0)
        }
    }
}

/**
 * Either direction: a Wi-Fi (or LAN) link is up (§7.2, S5 sharpened). [freqMhz] is the measured channel frequency,
 * always present (0 when unknown, for example Ethernet); [credentials] are set when the announcing side hosts a
 * group or hotspot whose credentials were not known earlier (system-generated hotspot, N15); [address] and [port]
 * are where to open data streams; [generation] numbers the links of this session from 0, and `StreamOpen` names it.
 */
@Serializable
data class LinkReady(
    @CborLabel(1) val kind: String,
    @CborLabel(2) val address: String? = null,
    @CborLabel(3) val port: Int? = null,
    @CborLabel(4) val freqMhz: Int,
    @CborLabel(5) val credentials: WifiCredentials? = null,
    @CborLabel(6) val generation: Int,
) : ControlMessage {
    constructor(
        kind: LinkKind,
        address: String? = null,
        port: Int? = null,
        freqMhz: Int,
        credentials: WifiCredentials? = null,
        generation: Int,
    ) : this(kind.wireName, address, port, freqMhz, credentials, generation)

    override val type: ControlMessageType get() = ControlMessageType.LINK_READY

    val linkKind: LinkKind? get() = LinkKind.fromWire(kind)

    init {
        requireText(kind, "link kind", max = MAX_WIRE_NAME_BYTES)
        address?.let { requireText(it, "address", max = MAX_ADDRESS_BYTES) }
        port?.let { require(it in 1..65535) { "port $it out of range" } }
        require(freqMhz in 0..MAX_FREQ_MHZ) { "freq_mhz $freqMhz out of range" }
        require(generation >= 0) { "generation must be non-negative" }
    }
}

/**
 * Either direction, every [ProtocolConstants.HEARTBEAT_INTERVAL_MS] on the control stream (§7.2). [t] is the
 * sender's monotonic clock in milliseconds; [echo] repeats the last `t` received from the peer, for round-trip time.
 */
@Serializable
data class Heartbeat(
    @CborLabel(1) val t: Long,
    @CborLabel(2) val echo: Long? = null,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.HEARTBEAT

    init {
        require(t >= 0) { "t must be non-negative" }
        echo?.let { require(it >= 0) { "echo must be non-negative" } }
    }
}

/**
 * S→R once a file's last unit is sent: its whole-file SHA-256, computed while streaming (spec change S2). The
 * receiver verifies the file when this arrives and its data is complete.
 */
@Serializable
data class FileDone(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val fileIndex: Int,
    @CborLabel(3) val sha256: Sha256Digest,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.FILE_DONE

    init {
        requireFileIndex(fileIndex)
    }
}

/**
 * S→R when every unit is acked, then R→S with the verified outcome (§7.2). [bytes] counts payload bytes of the
 * transfer, [durationMs] runs from `Accept`; [failedFiles] lists files that failed after
 * [ProtocolConstants.MAX_CHUNK_MISMATCHES] mismatches (empty unless [status] is not OK).
 */
@Serializable
data class Complete(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val status: CompleteStatus,
    @CborLabel(3) val bytes: Long,
    @CborLabel(4) val durationMs: Long,
    @CborLabel(5) val failedFiles: List<Int> = emptyList(),
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.COMPLETE

    init {
        require(bytes >= 0) { "bytes must be non-negative" }
        require(durationMs >= 0) { "duration_ms must be non-negative" }
        require(failedFiles.size <= MAX_RESUME_ENTRIES) { "at most $MAX_RESUME_ENTRIES failed files" }
        failedFiles.forEach { requireFileIndex(it, "failed file index") }
        requireStrictlyIncreasing(failedFiles, "failed_files")
        require(status != CompleteStatus.OK || failedFiles.isEmpty()) { "an OK transfer has no failed files" }
    }
}

/** Either direction: the transfer is cancelled (§7.2, §7.8). */
@Serializable
data class Cancel(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val reason: CancelReason,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.CANCEL
}

/**
 * Either direction (spec change N13): from now on this side sends control frames on data stream [streamId] of link
 * [generation] (the first Wi-Fi stream, opened with purpose [StreamPurpose.CONTROL]). After this message only
 * `Heartbeat`s continue on the old control stream, as a secondary liveness signal over Bluetooth.
 */
@Serializable
data class ControlMoved(
    @CborLabel(1) val streamId: Int,
    @CborLabel(2) val generation: Int,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.CONTROL_MOVED

    init {
        requireStreamId(streamId)
        require(generation >= 0) { "generation must be non-negative" }
    }
}

/**
 * Either direction, inside the encrypted session after pairing (spec change S3): the sender's advertising secret
 * `k_adv`, so every trusted peer resolves the same rotating beacon id. [generation] increases each time `k_adv`
 * rotates ("Forget" or "Reset identity"); a peer keeps only the newest. [toString] hides the secret.
 */
@Serializable
data class TrustShare(
    @CborLabel(1) val secret: AdvertisingSecret,
    @CborLabel(2) val generation: Int,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.TRUST_SHARE

    init {
        require(generation >= 0) { "generation must be non-negative" }
    }
}

/**
 * The first frame on every new data connection (spec change S7), carried in a [FrameType.STREAM_OPEN] frame (see
 * [StreamOpenFrame]), never in a `Control` frame. Names the [transferId], the [streamId] whose nonce space the
 * stream uses (2 and up, from the opener's [SessionRole] partition; the control stream 0 and the Bluetooth stream 1
 * run on the handshake connection and are never opened this way), the [direction] chunks flow, the [purpose], and
 * the link [generation] from `LinkReady`.
 */
@Serializable
data class StreamOpen(
    @CborLabel(1) val transferId: TransferId,
    @CborLabel(2) val streamId: Int,
    @CborLabel(3) val direction: StreamDirection,
    @CborLabel(4) val purpose: StreamPurpose,
    @CborLabel(5) val generation: Int,
) : ControlMessage {
    override val type: ControlMessageType get() = ControlMessageType.STREAM_OPEN

    init {
        require(streamId >= ProtocolConstants.STREAM_ID_FIRST_WIFI) {
            "a data connection's stream id is at least ${ProtocolConstants.STREAM_ID_FIRST_WIFI}"
        }
        require(generation >= 0) { "generation must be non-negative" }
    }
}

private const val MAX_FREQ_MHZ = 100_000
