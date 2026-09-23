package com.constrivo.drop.core.protocol

/**
 * Protects the payload of [FrameType.isProtected] frames (architecture §7.1, spec changes S7 and N2).
 *
 * `core/protocol` does not know the cipher; `core/transfer` (WP4) adapts the frame cipher of `core/crypto` to this
 * interface. An implementation must:
 * - authenticate `FrameAad.of(type, streamId, sealedLength)` as associated data, where `sealedLength` is the size of
 *   the sealed output (`plaintext.size + overhead`), so length, type and stream are bound to every frame;
 * - derive the nonce from `streamId` and a per-stream counter (`u32 stream_id ‖ u64 counter`) and keep one counter
 *   per stream and direction; [seal] and [open] are called in wire order for each stream;
 * - return exactly `plaintext.size + overhead` bytes from [seal];
 * - throw [ProtocolException] from [open] when authentication fails or the input is shorter than [overhead].
 *
 * Stream ids: 0 control, 1 Bluetooth, 2 and up Wi-Fi data ([ProtocolConstants.STREAM_ID_CONTROL] and following).
 */
interface FrameProtector {
    /** Bytes [seal] adds to a plaintext (the AEAD tag); at most [ProtocolConstants.MAX_PROTECTION_OVERHEAD]. */
    val overhead: Int

    fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
    ): ByteArray

    fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
    ): ByteArray
}

/**
 * A [FrameProtector] that copies bytes unchanged, **for tests only**: it provides no confidentiality or integrity.
 * It still checks that [type] is a protected type and [streamId] is non-negative, like a real protector.
 */
class PlaintextFrameProtector : FrameProtector {
    override val overhead: Int = 0

    override fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
    ): ByteArray {
        checkArguments(type, streamId)
        return plaintext.copyOf()
    }

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
    ): ByteArray {
        checkArguments(type, streamId)
        return sealed.copyOf()
    }

    private fun checkArguments(
        type: FrameType,
        streamId: Int,
    ) {
        require(type.isProtected) { "$type frames are not protected" }
        require(streamId >= 0) { "stream id must be non-negative" }
    }
}

/** Seals [plaintext] with [protector] and writes it as one [type] frame on [streamId]. */
suspend fun FrameWriter.writeProtected(
    protector: FrameProtector,
    type: FrameType,
    streamId: Int,
    plaintext: ByteArray,
    flush: Boolean = true,
) {
    require(type.isProtected) { "$type frames are not protected" }
    require(type != FrameType.STREAM_OPEN) { "use writeStreamOpen for StreamOpen frames" }
    val sealed = protector.seal(type, streamId, plaintext)
    check(sealed.size == plaintext.size + protector.overhead) { "protector returned ${sealed.size} bytes, expected plaintext + overhead" }
    writeFrame(type, sealed, flush = flush)
}

/**
 * Opens a protected [frame] received on [streamId] and returns its plaintext.
 * Throws [ProtocolException] for an unprotected type or when [FrameProtector.open] rejects it.
 */
fun FrameProtector.openFrame(
    frame: Frame,
    streamId: Int,
): ByteArray {
    if (!frame.type.isProtected) throw ProtocolException("${frame.type} frames are not protected")
    if (frame.type == FrameType.STREAM_OPEN) throw ProtocolException("StreamOpen frames are opened with StreamOpenFrame.open")
    return open(frame.type, streamId, frame.payload)
}

/** Encodes [message] with [ControlCodec] and writes it as a protected `Control` frame on [streamId]. */
suspend fun FrameWriter.writeControl(
    protector: FrameProtector,
    streamId: Int,
    message: ControlMessage,
    flush: Boolean = true,
) {
    require(message !is StreamOpen) { "StreamOpen travels in a StreamOpen frame; use writeStreamOpen" }
    writeProtected(protector, FrameType.CONTROL, streamId, ControlCodec.encode(message), flush)
}

/**
 * Opens and decodes a `Control` frame. Throws [ProtocolException] if the frame is not a `Control` frame, fails to
 * open, is malformed, or carries a `StreamOpen` (which only a `StreamOpen` frame may carry);
 * [UnknownControlMessageException] for a message type this version does not know (the caller ignores those).
 */
fun FrameProtector.openControl(
    frame: Frame,
    streamId: Int,
): ControlMessage {
    if (frame.type != FrameType.CONTROL) throw ProtocolException("expected a CONTROL frame, got ${frame.type}")
    val message = ControlCodec.decode(openFrame(frame, streamId))
    if (message is StreamOpen) throw ProtocolException("StreamOpen is not allowed in a CONTROL frame")
    return message
}

/** Writes one protected `Chunk` frame: [header] followed by [payload] (whose size must equal the header's length). */
suspend fun FrameWriter.writeChunk(
    protector: FrameProtector,
    streamId: Int,
    header: ChunkHeader,
    payload: ByteArray,
    flush: Boolean = true,
) {
    writeProtected(protector, FrameType.CHUNK, streamId, ChunkFrame(header, payload).encode(), flush)
}

/** Opens and decodes a `Chunk` frame; throws [ProtocolException] for any other type or a malformed chunk. */
fun FrameProtector.openChunk(
    frame: Frame,
    streamId: Int,
): ChunkFrame {
    if (frame.type != FrameType.CHUNK) throw ProtocolException("expected a CHUNK frame, got ${frame.type}")
    return ChunkFrame.decode(openFrame(frame, streamId))
}

/**
 * The `StreamOpen` frame that starts every new data connection (spec change S7).
 *
 * Payload layout: `u32 stream_id` in clear ‖ `seal(StreamOpen envelope)` sealed on that stream id. The listener reads
 * the clear stream id to pick the nonce space, opens the rest, and checks that the sealed [StreamOpen.streamId]
 * matches the clear one; the clear id is also bound through [FrameAad]. The session (and so the key) is chosen by the
 * listening socket: `LinkReady` announces one listener per link.
 */
object StreamOpenFrame {
    private const val PREFIX_SIZE = 4

    /** Builds the frame payload for [open]. */
    fun encode(
        protector: FrameProtector,
        open: StreamOpen,
    ): ByteArray {
        val envelope = ControlCodec.encode(open)
        val sealed = protector.seal(FrameType.STREAM_OPEN, open.streamId, envelope)
        check(sealed.size == envelope.size + protector.overhead) {
            "protector returned ${sealed.size} bytes, expected plaintext + overhead"
        }
        val payload = ByteArray(PREFIX_SIZE + sealed.size)
        BigEndian.putU32(payload, 0, open.streamId)
        sealed.copyInto(payload, PREFIX_SIZE)
        return payload
    }

    /** The clear stream id of a `StreamOpen` frame, before it is opened. */
    fun peekStreamId(frame: Frame): Int {
        if (frame.type != FrameType.STREAM_OPEN) throw ProtocolException("expected a STREAM_OPEN frame, got ${frame.type}")
        if (frame.payload.size < PREFIX_SIZE) throw ProtocolException("StreamOpen frame shorter than its stream id")
        val id = BigEndian.getU32(frame.payload, 0)
        if (id > Int.MAX_VALUE) throw ProtocolException("stream id $id out of range")
        return id.toInt()
    }

    /** Opens and validates a `StreamOpen` frame. Throws [ProtocolException] on any mismatch or malformed content. */
    fun open(
        protector: FrameProtector,
        frame: Frame,
    ): StreamOpen {
        val streamId = peekStreamId(frame)
        val sealed = frame.payload.copyOfRange(PREFIX_SIZE, frame.payload.size)
        val message = ControlCodec.decode(protector.open(FrameType.STREAM_OPEN, streamId, sealed))
        if (message !is StreamOpen) throw ProtocolException("STREAM_OPEN frame carries ${message.type}")
        if (message.streamId != streamId) {
            throw ProtocolException("StreamOpen names stream ${message.streamId} but was sealed for stream $streamId")
        }
        return message
    }
}

/** Writes the `StreamOpen` frame for [open]. */
suspend fun FrameWriter.writeStreamOpen(
    protector: FrameProtector,
    open: StreamOpen,
    flush: Boolean = true,
) {
    writeFrame(FrameType.STREAM_OPEN, StreamOpenFrame.encode(protector, open), flush = flush)
}

/**
 * Hands out data stream ids for one session (spec change S7): Wi-Fi streams get 2, 3, 4, ... and an id is never
 * handed out twice, also across link generations, so a (key, nonce) pair cannot repeat even if a stream is reopened
 * under the same keys. Not thread-safe; the engine allocates from one coroutine.
 */
class StreamIdAllocator(
    first: Int = ProtocolConstants.STREAM_ID_FIRST_WIFI,
) {
    private var next: Long = first.toLong()

    init {
        require(first > ProtocolConstants.STREAM_ID_BLUETOOTH) { "Wi-Fi stream ids start above the Bluetooth id" }
    }

    /** The next unused stream id. Throws [IllegalStateException] once the u31 space is exhausted. */
    fun next(): Int {
        check(next <= Int.MAX_VALUE) { "stream ids exhausted" }
        return (next++).toInt()
    }
}
