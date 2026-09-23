package com.constrivo.drop.core.protocol

/**
 * Numbers shared by the protocol, transfer engine and ladder (architecture §7, implementation plan §3).
 * Sizes are binary (MiB); only user-facing speed readouts use decimal MB (spec change S6).
 */
object ProtocolConstants {
    const val PROTOCOL_VERSION: Int = 1

    const val KIB: Int = 1024
    const val MIB: Int = 1024 * 1024

    /** Chunk size for files ≥ [BUNDLE_THRESHOLD]; also the maximum bundle size (§7.3). */
    const val CHUNK_SIZE: Int = 4 * MIB

    /** Files smaller than this are bundled (§7.3). */
    const val BUNDLE_THRESHOLD: Int = 1 * MIB

    /** Block size on the Bluetooth head-start stream so progress moves within 1 s (spec changes S1, S10). */
    const val BLUETOOTH_BLOCK_SIZE: Int = 16 * KIB

    /** AEAD sub-block size for chunk payloads, keeping memory flat (§9 "Big pipes"). */
    const val AEAD_BLOCK_SIZE: Int = 64 * KIB

    /** Largest frame payload accepted by the decoder: one chunk plus headers and tags. */
    const val MAX_FRAME_PAYLOAD: Int = CHUNK_SIZE + 64 * KIB

    /** Stream ids for AEAD nonces (spec change S7): 0 control, 1 Bluetooth, 2.. Wi-Fi data streams. */
    const val STREAM_ID_CONTROL: Int = 0
    const val STREAM_ID_BLUETOOTH: Int = 1
    const val STREAM_ID_FIRST_WIFI: Int = 2

    const val DEFAULT_STREAMS: Int = 4
    const val MAX_STREAMS: Int = 8
    const val THERMAL_STREAMS: Int = 2

    /** Raise to [MAX_STREAMS] above this measured throughput, bytes per second (§7.4: 40 MB/s). */
    const val RAISE_STREAMS_ABOVE_BPS: Long = 40_000_000

    /** Chunks in flight per stream (§7.4). */
    const val STREAM_WINDOW: Int = 2

    /** Receiver write queue bound (§7.4). */
    const val WRITE_QUEUE_BYTES: Int = 16 * MIB

    /** Ack batching (§7.2): every 50 ms or 8 chunks. */
    const val ACK_BATCH_MILLIS: Long = 50
    const val ACK_BATCH_CHUNKS: Int = 8

    /** Timeouts (§7.8), milliseconds. */
    const val OFFER_TIMEOUT_MS: Long = 30_000
    const val LAN_CONNECT_TIMEOUT_MS: Long = 1_000
    const val LAN_MEASURE_MS: Long = 1_000
    const val P2P_FORMATION_TIMEOUT_MS: Long = 6_000
    const val HOTSPOT_TIMEOUT_MS: Long = 6_000
    const val HEARTBEAT_INTERVAL_MS: Long = 2_000
    const val HEARTBEAT_LOST_MS: Long = 6_000
    const val RECONNECT_WINDOW_MS: Long = 2 * 60_000
    const val PARKED_WINDOW_MS: Long = 24 * 60 * 60_000L
    const val LINK_IDLE_TEARDOWN_MS: Long = 60_000
    const val WIFI_RESTORE_BUDGET_MS: Long = 5_000

    /** Three hash mismatches on one chunk fail the file (§7.8). */
    const val MAX_CHUNK_MISMATCHES: Int = 3

    /** LAN falls through when slower than this, bytes per second (§4: 10 MB/s). */
    const val LAN_MIN_BPS: Long = 10_000_000

    // ---- Wire-format limits added in WP3 (architecture §7.1–7.3). Decoders reject anything larger. ----

    /** `u32 length ‖ u8 type` in front of every frame payload (§7.1). */
    const val FRAME_HEADER_SIZE: Int = 5

    /** Largest payload of a plaintext handshake frame (`Hello`, `HelloAck`, `HelloReveal`, `Finished`) and of `StreamOpen`. */
    const val MAX_HANDSHAKE_FRAME_PAYLOAD: Int = 4 * KIB

    /** Largest encoded control message (the CBOR envelope before protection). Keeps the control stream responsive on GATT. */
    const val MAX_CONTROL_MESSAGE_BYTES: Int = 64 * KIB

    /** Room a [FrameProtector] may add to a control or handshake payload (AEAD tag plus slack). */
    const val MAX_PROTECTION_OVERHEAD: Int = 1 * KIB

    /** Smallest `chunk_size` an `Offer` may announce; `chunk_size` is a power of two in [MIN_CHUNK_SIZE, CHUNK_SIZE]. */
    const val MIN_CHUNK_SIZE: Int = 64 * KIB

    /**
     * Binary `Chunk` header (§7.3, S1): transfer_id 16 ‖ file_index 4 ‖ chunk_index 4 ‖ block_offset 4 ‖ payload_len 4 ‖
     * hash 16.
     */
    const val CHUNK_HEADER_SIZE: Int = 48

    /** `file_index` of a bundle unit: 0xFFFFFFFF on the wire, -1 as a Kotlin Int (§7.3). */
    const val BUNDLE_FILE_INDEX: Int = -1

    /** Bundle payload: `u32 count` then 12 bytes per entry (§7.3). */
    const val BUNDLE_INDEX_HEADER_SIZE: Int = 4
    const val BUNDLE_INDEX_ENTRY_SIZE: Int = 12

    /** Upper bound on files in one transfer; file indices are 0 until this (exclusive). */
    const val MAX_FILES_PER_TRANSFER: Int = 100_000

    /** A file name is a relative path of at most this many UTF-8 bytes; sanitising and confinement are WP12. */
    const val MAX_FILE_NAME_BYTES: Int = 1024
    const val MAX_MIME_BYTES: Int = 255

    /** `Offer` summary (spec change N12): six names, six previews of at most 4 KiB each, a bounded MIME histogram. */
    const val MAX_PREVIEW_NAMES: Int = 6
    const val MAX_PREVIEWS: Int = 6
    const val MAX_PREVIEW_BYTES: Int = 4 * KIB
    const val MAX_MIME_HISTOGRAM_ENTRIES: Int = 16
    const val MAX_LINK_OPTIONS: Int = 8

    /** `FileList` paging (spec change N12). The pager aims for [FILE_LIST_PAGE_BUDGET_BYTES] per page. */
    const val MAX_FILE_LIST_PAGE_ENTRIES: Int = 1024
    const val FILE_LIST_PAGE_BUDGET_BYTES: Int = 16 * KIB

    /** Per-message list bounds for `Ack` and `Resume` / `Accept.resume`. */
    const val MAX_ACK_REFS: Int = 1024
    const val MAX_RESUME_ENTRIES: Int = 4096
    const val MAX_RANGES_PER_ENTRY: Int = 4096

    /** `Hint.params` bounds. */
    const val MAX_HINT_PARAMS: Int = 16
    const val MAX_HINT_PARAM_BYTES: Int = 256

    /** Short identifiers on the wire: link kinds, hint codes, reason and status codes. */
    const val MAX_WIRE_NAME_BYTES: Int = 32

    /** Size of the advertising secret `k_adv` shared with trusted peers in `TrustShare` (spec change S3). */
    const val ADVERTISING_SECRET_SIZE: Int = 32

    /**
     * Nesting limit for CBOR control messages. Version 1 needs 7 levels (envelope, body, `Resume` state, list, entry,
     * ranges, range); the rest is room for fields a newer peer adds.
     */
    const val MAX_CBOR_DEPTH: Int = 16
}
