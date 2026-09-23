package com.constrivo.drop.core.protocol

/**
 * Protects the payload of [FrameType.isProtected] frames (architecture §7.1, spec changes S7 and N2).
 *
 * `core/protocol` does not know the cipher; `core/transfer` (WP4) adapts the frame cipher of `core/crypto` to this
 * interface. An implementation must:
 * - report in [sealedSize] exactly how many bytes [seal] returns for a plaintext of a given size. The size may depend
 *   on the plaintext size (for example one tag per [ProtocolConstants.AEAD_BLOCK_SIZE] block of a chunk), is at least
 *   the plaintext size, and must keep every frame the protocol builds within its [FrameType.maxPayload]: at most
 *   [ProtocolConstants.MAX_PROTECTION_OVERHEAD] extra bytes for a control message or a `StreamOpen` envelope, and at
 *   most `MAX_FRAME_PAYLOAD - CHUNK_HEADER_SIZE - CHUNK_SIZE` (about 64 KiB) for a full chunk;
 * - authenticate `FrameAad.of(type, streamId, sealedLength)` as associated data, where `sealedLength` is the size of
 *   the sealed bytes, so length, type and stream are bound to every frame;
 * - derive the nonce from `streamId` and a per-stream counter (`u32 stream_id ‖ u64 counter`) and keep one counter
 *   per stream and direction. The protocol seals and opens the frames of a stream in wire order: the [FrameWriter]
 *   helpers ([writeProtected], [writeControl], [writeChunk], [writeStreamOpen]) seal while holding the writer's lock,
 *   so frames reach the wire in the order their counters were taken even when several coroutines write;
 * - throw [ProtocolException] from [open] when authentication fails or the input is malformed (for example shorter
 *   than a tag), leaving its counter unchanged.
 *
 * The range overloads and [openInto] let the engine seal a plaintext built in a pooled buffer and open a payload read
 * into one ([FrameReader.readPayload]) without extra copies of a 4 MiB chunk.
 *
 * Stream ids: 0 control, 1 Bluetooth, 2 and up Wi-Fi data ([ProtocolConstants.STREAM_ID_CONTROL] and following);
 * [SessionRole] says which side opens which data stream ids.
 */
interface FrameProtector {
    /** Size of [seal]'s output for a [plaintextSize]-byte plaintext of [type]: the frame's `length` and the AAD's `sealed_length`. */
    fun sealedSize(
        type: FrameType,
        plaintextSize: Int,
    ): Int

    /** Seals `plaintext[offset until offset + length]` for [streamId]; returns exactly `sealedSize(type, length)` bytes. */
    fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray

    /** Opens `sealed[offset until offset + length]` received on [streamId] and returns the plaintext. */
    fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray

    fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
    ): ByteArray = seal(type, streamId, plaintext, 0, plaintext.size)

    fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
    ): ByteArray = open(type, streamId, sealed, 0, sealed.size)

    /**
     * Opens `sealed[offset until offset + length]` into [destination] at [destinationOffset] and returns the plaintext
     * length. [destination] may be [sealed] itself with `destinationOffset <= offset` (decryption in place). The
     * default opens into a new array and copies it; a cipher that can decrypt in place should override it.
     */
    fun openInto(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int {
        val plaintext = open(type, streamId, sealed, offset, length)
        require(destinationOffset >= 0 && destinationOffset <= destination.size - plaintext.size) { "destination too small" }
        plaintext.copyInto(destination, destinationOffset)
        return plaintext.size
    }
}

/**
 * A [FrameProtector] that copies bytes unchanged, **for tests only**: it provides no confidentiality or integrity.
 * It still checks that the type is a protected type and the stream id is non-negative, like a real protector.
 */
class PlaintextFrameProtector : FrameProtector {
    override fun sealedSize(
        type: FrameType,
        plaintextSize: Int,
    ): Int {
        require(type.isProtected) { "$type frames are not protected" }
        require(plaintextSize >= 0) { "plaintext size must be non-negative" }
        return plaintextSize
    }

    override fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        checkArguments(type, streamId, plaintext.size, offset, length)
        return plaintext.copyOfRange(offset, offset + length)
    }

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        checkArguments(type, streamId, sealed.size, offset, length)
        return sealed.copyOfRange(offset, offset + length)
    }

    private fun checkArguments(
        type: FrameType,
        streamId: Int,
        size: Int,
        offset: Int,
        length: Int,
    ) {
        require(type.isProtected) { "$type frames are not protected" }
        require(streamId >= 0) { "stream id must be non-negative" }
        require(offset >= 0 && length >= 0 && offset <= size - length) { "range out of bounds" }
    }
}

/**
 * Seals `plaintext[offset until offset + length]` with [protector] and writes it as one [type] frame on [streamId].
 *
 * The seal runs inside the writer's lock, so the frames of a stream reach the wire in the order their nonce counters
 * were taken, whichever coroutines write them. The frame size is checked before sealing, so an oversized plaintext
 * spends no counter.
 *
 * @throws ProtocolException if the sealed payload would exceed [FrameType.maxPayload].
 * @throws IllegalStateException if the protector returns a different size than [FrameProtector.sealedSize] (the
 *   writer is then broken) or an earlier write failed.
 */
suspend fun FrameWriter.writeProtected(
    protector: FrameProtector,
    type: FrameType,
    streamId: Int,
    plaintext: ByteArray,
    offset: Int = 0,
    length: Int = plaintext.size - offset,
    flush: Boolean = true,
) {
    require(type.isProtected) { "$type frames are not protected" }
    require(type != FrameType.STREAM_OPEN) { "use writeStreamOpen for StreamOpen frames" }
    require(offset >= 0 && length >= 0 && offset <= plaintext.size - length) { "plaintext range out of bounds" }
    writeProduced(type, protector.sealedSize(type, length), flush) { protector.seal(type, streamId, plaintext, offset, length) }
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
    writeProtected(protector, FrameType.CONTROL, streamId, ControlCodec.encode(message), flush = flush)
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

/**
 * Writes one protected `Chunk` frame: [header] followed by [payload] (whose size must equal the header's length).
 * To avoid the copy into a fresh plaintext array, build the plaintext in a buffer ([ChunkHeader.encodeInto], then
 * the payload) and pass that range to [writeProtected].
 */
suspend fun FrameWriter.writeChunk(
    protector: FrameProtector,
    streamId: Int,
    header: ChunkHeader,
    payload: ByteArray,
    flush: Boolean = true,
) {
    writeProtected(protector, FrameType.CHUNK, streamId, ChunkFrame(header, payload).encode(), flush = flush)
}

/** Opens and decodes a `Chunk` frame; throws [ProtocolException] for any other type or a malformed chunk. */
fun FrameProtector.openChunk(
    frame: Frame,
    streamId: Int,
): ChunkFrame = openChunkView(frame, streamId).toFrame()

/**
 * Opens a `Chunk` frame and decodes it without copying the payload out of the opened plaintext. Throws
 * [ProtocolException] for any other type or a malformed chunk.
 */
fun FrameProtector.openChunkView(
    frame: Frame,
    streamId: Int,
): ChunkView {
    if (frame.type != FrameType.CHUNK) throw ProtocolException("expected a CHUNK frame, got ${frame.type}")
    return ChunkView.decode(openFrame(frame, streamId))
}

/**
 * The `StreamOpen` frame that starts every new data connection (spec change S7).
 *
 * Payload layout: `u32 stream_id` in clear ‖ `seal(StreamOpen envelope)` sealed on that stream id. The listener reads
 * the clear stream id to pick the nonce space, checks it with the session's [StreamIdRegistry] **before** opening
 * anything, opens the rest, and checks that the sealed [StreamOpen.streamId] matches the clear one; the clear id is
 * also bound through [FrameAad]. The session (and so the key) is chosen by the listening socket: `LinkReady`
 * announces one listener per link.
 */
object StreamOpenFrame {
    private const val PREFIX_SIZE = 4

    /** Builds the frame payload for [open]. */
    fun encode(
        protector: FrameProtector,
        open: StreamOpen,
    ): ByteArray = seal(protector, open, ControlCodec.encode(open))

    /** Payload size of the frame for an [envelopeSize]-byte `StreamOpen` envelope. */
    internal fun payloadSize(
        protector: FrameProtector,
        envelopeSize: Int,
    ): Int = PREFIX_SIZE + protector.sealedSize(FrameType.STREAM_OPEN, envelopeSize)

    internal fun seal(
        protector: FrameProtector,
        open: StreamOpen,
        envelope: ByteArray,
    ): ByteArray {
        val expected = protector.sealedSize(FrameType.STREAM_OPEN, envelope.size)
        val sealed = protector.seal(FrameType.STREAM_OPEN, open.streamId, envelope)
        check(sealed.size == expected) { "protector returned ${sealed.size} bytes, sealedSize says $expected" }
        val payload = ByteArray(PREFIX_SIZE + sealed.size)
        BigEndian.putU32(payload, 0, open.streamId)
        sealed.copyInto(payload, PREFIX_SIZE)
        return payload
    }

    /**
     * The clear stream id of a `StreamOpen` frame, before it is opened. Ids below
     * [ProtocolConstants.STREAM_ID_FIRST_WIFI] are rejected here: the control stream (0) and the Bluetooth stream (1)
     * run on the connection the handshake used and never start a connection of their own.
     */
    fun peekStreamId(frame: Frame): Int {
        if (frame.type != FrameType.STREAM_OPEN) throw ProtocolException("expected a STREAM_OPEN frame, got ${frame.type}")
        if (frame.payload.size < PREFIX_SIZE) throw ProtocolException("StreamOpen frame shorter than its stream id")
        val id = BigEndian.getU32(frame.payload, 0)
        if (id > Int.MAX_VALUE) throw ProtocolException("stream id $id out of range")
        if (id < ProtocolConstants.STREAM_ID_FIRST_WIFI) throw ProtocolException("stream id $id cannot start a data connection")
        return id.toInt()
    }

    /**
     * Opens and validates a `StreamOpen` frame for the session whose opened streams [registry] tracks. The clear id
     * must be one the peer may open and has not opened yet ([StreamIdRegistry.checkAvailable]) before the protector
     * sees it; after the frame authenticated, the id is registered, so a replayed or colliding `StreamOpen` cannot
     * start a second connection in the same nonce space. Throws [ProtocolException] on any mismatch or malformed
     * content.
     */
    fun open(
        protector: FrameProtector,
        frame: Frame,
        registry: StreamIdRegistry,
    ): StreamOpen {
        val streamId = peekStreamId(frame)
        registry.checkAvailable(streamId)
        val plaintext = protector.open(FrameType.STREAM_OPEN, streamId, frame.payload, PREFIX_SIZE, frame.payload.size - PREFIX_SIZE)
        val message = ControlCodec.decode(plaintext)
        if (message !is StreamOpen) throw ProtocolException("STREAM_OPEN frame carries ${message.type}")
        if (message.streamId != streamId) {
            throw ProtocolException("StreamOpen names stream ${message.streamId} but was sealed for stream $streamId")
        }
        registry.register(streamId)
        return message
    }
}

/** Writes the `StreamOpen` frame for [open], sealing it inside the writer's lock like [writeProtected]. */
suspend fun FrameWriter.writeStreamOpen(
    protector: FrameProtector,
    open: StreamOpen,
    flush: Boolean = true,
) {
    val envelope = ControlCodec.encode(open)
    writeProduced(FrameType.STREAM_OPEN, StreamOpenFrame.payloadSize(protector, envelope.size), flush) {
        StreamOpenFrame.seal(protector, open, envelope)
    }
}

/**
 * This device's end of the session handshake (§6.2): the [INITIATOR] sends `Hello`, the [RESPONDER] answers with
 * `HelloAck`. The role partitions the data stream ids (spec change S7), as in QUIC and HTTP/2: the side that opens a
 * data connection names it with an id from its own partition, the initiator even ids (2, 4, 6, ...) and the responder
 * odd ids (3, 5, 7, ...). Either side may open connections (the side that did not announce a link in `LinkReady`
 * connects to it, and that can change between link generations), and the two partitions never meet, so no two
 * connections of a session share a stream id, and so a nonce space, under either directional key.
 */
enum class SessionRole {
    INITIATOR,
    RESPONDER,
    ;

    val peer: SessionRole get() = if (this == INITIATOR) RESPONDER else INITIATOR

    /** The first data stream id this side opens. */
    val firstStreamId: Int
        get() = if (this == INITIATOR) ProtocolConstants.STREAM_ID_FIRST_WIFI else ProtocolConstants.STREAM_ID_FIRST_WIFI + 1

    /** Whether [streamId] is in this side's partition. */
    fun opens(streamId: Int): Boolean = streamId >= ProtocolConstants.STREAM_ID_FIRST_WIFI && streamId % 2 == firstStreamId % 2
}

/**
 * Hands out the ids of the data connections this side opens in one session (spec change S7): its [SessionRole]
 * partition in increasing order, never an id twice, also across link generations, so a (key, nonce) pair cannot
 * repeat even if a connection is reopened under the same keys. Not thread-safe; the engine allocates from one
 * coroutine.
 */
class StreamIdAllocator(
    val role: SessionRole,
    first: Int = role.firstStreamId,
) {
    private var next: Long = first.toLong()

    init {
        require(role.opens(first)) { "stream id $first is not in the $role partition" }
    }

    /** The next unused stream id. Throws [IllegalStateException] once the u31 space is exhausted. */
    fun next(): Int {
        check(next <= Int.MAX_VALUE) { "stream ids exhausted" }
        val id = next.toInt()
        next += 2
        return id
    }
}

/**
 * The listening side's record of the data connections the peer opened in one session (spec change S7). A
 * `StreamOpen` is accepted only for an id in the peer's [SessionRole] partition that it has not opened before, so two
 * connections never share a nonce space and a replayed `StreamOpen` cannot start a second connection.
 * [StreamOpenFrame.open] consults it. Not thread-safe; use it from the session's accept loop.
 */
class StreamIdRegistry(
    localRole: SessionRole,
) {
    private val peerRole = localRole.peer
    private val opened = HashSet<Int>()

    /** Ids the peer has opened so far. */
    val size: Int get() = opened.size

    operator fun contains(streamId: Int): Boolean = streamId in opened

    /** Throws [ProtocolException] unless the peer may open [streamId] now: in its partition and not opened before. */
    fun checkAvailable(streamId: Int) {
        if (!peerRole.opens(streamId)) throw ProtocolException("stream id $streamId is not one the $peerRole opens")
        if (streamId in opened) throw ProtocolException("stream id $streamId was already opened in this session")
    }

    /** Records [streamId] as opened, after its `StreamOpen` authenticated; throws like [checkAvailable]. */
    fun register(streamId: Int) {
        checkAvailable(streamId)
        opened += streamId
    }
}
