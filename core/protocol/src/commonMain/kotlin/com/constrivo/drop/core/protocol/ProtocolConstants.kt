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
}
