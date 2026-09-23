package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FRAME_PAYLOAD
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_PROTECTION_OVERHEAD

/**
 * Frame types (architecture §7.1). The code is the `u8 type` byte on the wire; codes are never reused.
 *
 * [isProtected] frames carry AEAD output from a [FrameProtector] authenticated with [FrameAad] (spec change N2);
 * the plaintext handshake frames carry bodies built by `core/crypto`. [maxPayload] is the decoder's hard limit
 * for the frame's `length` field.
 */
enum class FrameType(
    val code: Int,
    val isProtected: Boolean,
    val maxPayload: Int,
) {
    /** Handshake message 1 (§6.2, N1: carries the commitment to `eph_pk_A`). Plaintext. */
    HELLO(0x01, isProtected = false, maxPayload = MAX_HANDSHAKE_FRAME_PAYLOAD),

    /** Handshake message 2 (§6.2). Plaintext. */
    HELLO_ACK(0x02, isProtected = false, maxPayload = MAX_HANDSHAKE_FRAME_PAYLOAD),

    /** Handshake message 3, reveals `eph_pk_A` (spec change N1). Plaintext. */
    HELLO_REVEAL(0x03, isProtected = false, maxPayload = MAX_HANDSHAKE_FRAME_PAYLOAD),

    /** Key confirmation under the new session keys (spec change N2); body built by `core/crypto`. */
    FINISHED(0x04, isProtected = true, maxPayload = MAX_HANDSHAKE_FRAME_PAYLOAD),

    /** One CBOR control message ([ControlCodec] envelope), protected (§7.2). */
    CONTROL(0x10, isProtected = true, maxPayload = MAX_CONTROL_MESSAGE_BYTES + MAX_PROTECTION_OVERHEAD),

    /** One data unit: [ChunkHeader] then payload, protected (§7.3). */
    CHUNK(0x11, isProtected = true, maxPayload = MAX_FRAME_PAYLOAD),

    /**
     * First frame on every new data connection (spec change S7): `u32 stream_id` in clear, then a protected
     * [StreamOpen] control envelope. See [StreamOpenFrame].
     */
    STREAM_OPEN(0x12, isProtected = true, maxPayload = MAX_HANDSHAKE_FRAME_PAYLOAD),
    ;

    companion object {
        private val byCode: Map<Int, FrameType> = entries.associateBy { it.code }

        fun fromCode(code: Int): FrameType? = byCode[code]
    }
}

/**
 * One frame: [type] and its [payload] (ciphertext for protected types).
 *
 * The frame takes ownership of [payload] without copying, because chunk payloads are up to 4 MiB; callers must not
 * modify the array afterwards. Equality compares content.
 */
class Frame(
    val type: FrameType,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is Frame && other.type == type && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    override fun toString(): String = "Frame($type, ${payload.size} B)"
}

/** A decoded, validated frame header: [payloadLength] bytes of [type] payload follow it. */
data class FrameHeader(
    val type: FrameType,
    val payloadLength: Int,
)

/**
 * Which frame types a reader accepts at this point of a stream, and how large each may be.
 *
 * The limit is checked before any payload buffer is allocated, so a peer cannot make the reader allocate more than
 * the declared maximum. Before authentication, use [HANDSHAKE] (control channel) or [STREAM_START] (a new data
 * connection) so an unauthenticated peer can make the reader allocate at most 4 KiB per frame.
 */
class FrameLimits private constructor(
    private val maxByCode: IntArray,
) {
    /** The largest payload accepted for [type], or null when the type is not accepted here. */
    fun maxPayload(type: FrameType): Int? = maxByCode[type.code].takeIf { it >= 0 }

    fun accepts(type: FrameType): Boolean = maxByCode[type.code] >= 0

    /** The largest payload any accepted type may have; the reader's worst-case allocation per frame. */
    val largestPayload: Int get() = maxByCode.max()

    companion object {
        private const val TABLE_SIZE = 256

        /** Every type at its [FrameType.maxPayload]. */
        val ALL: FrameLimits = of(*FrameType.entries.toTypedArray())

        /** The handshake on the control channel: `Hello`, `HelloAck`, `HelloReveal`, `Finished`. */
        val HANDSHAKE: FrameLimits = of(FrameType.HELLO, FrameType.HELLO_ACK, FrameType.HELLO_REVEAL, FrameType.FINISHED)

        /** The first frame of a new data connection: `StreamOpen` only. */
        val STREAM_START: FrameLimits = of(FrameType.STREAM_OPEN)

        /** After the handshake or `StreamOpen`: control and chunk frames. */
        val SESSION: FrameLimits = of(FrameType.CONTROL, FrameType.CHUNK)

        /** Accept exactly [types], each up to its [FrameType.maxPayload] or the smaller [cap]. */
        fun of(
            vararg types: FrameType,
            cap: Int = Int.MAX_VALUE,
        ): FrameLimits {
            require(cap >= 0) { "cap must be non-negative" }
            val table = IntArray(TABLE_SIZE) { -1 }
            for (type in types) table[type.code] = minOf(type.maxPayload, cap)
            return FrameLimits(table)
        }
    }
}

/**
 * The frame codec of architecture §7.1 (with spec changes S7 and N2).
 *
 * Wire layout, big-endian: `u32 length ‖ u8 type ‖ payload`. **`length` counts the payload bytes only**: it excludes
 * the 4-byte length field and the type byte, so a frame occupies `5 + length` bytes on the wire, and for protected
 * types it is the ciphertext-plus-tag length. A zero-length payload is well-formed at this layer. Decoding rejects,
 * with [ProtocolException], an unknown type byte, a type not accepted by the [FrameLimits] in force, and a length
 * above that type's limit (never more than [ProtocolConstants.MAX_FRAME_PAYLOAD]).
 */
object FrameCodec {
    const val HEADER_SIZE: Int = ProtocolConstants.FRAME_HEADER_SIZE

    /** Writes the 5-byte header for a [payloadLength]-byte payload of [type] at [offset]. */
    fun writeHeader(
        destination: ByteArray,
        offset: Int,
        type: FrameType,
        payloadLength: Int,
    ) {
        checkEncodable(type, payloadLength)
        BigEndian.putU32(destination, offset, payloadLength)
        destination[offset + 4] = type.code.toByte()
    }

    fun encodeHeader(
        type: FrameType,
        payloadLength: Int,
    ): ByteArray = ByteArray(HEADER_SIZE).also { writeHeader(it, 0, type, payloadLength) }

    /** Encodes one complete frame. Throws [ProtocolException] if [length] exceeds [FrameType.maxPayload]. */
    fun encode(
        type: FrameType,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= payload.size - length) { "payload range out of bounds" }
        val out = ByteArray(HEADER_SIZE + length)
        writeHeader(out, 0, type, length)
        payload.copyInto(out, HEADER_SIZE, offset, offset + length)
        return out
    }

    fun encode(frame: Frame): ByteArray = encode(frame.type, frame.payload)

    /**
     * Decodes and validates the header at [offset]. Throws [TruncatedFrameException] if fewer than 5 bytes remain,
     * [ProtocolException] for an unknown or unaccepted type or an oversized length.
     */
    fun decodeHeader(
        bytes: ByteArray,
        offset: Int = 0,
        limits: FrameLimits = FrameLimits.ALL,
    ): FrameHeader {
        if (offset < 0 || offset > bytes.size) throw ProtocolException("header offset $offset outside ${bytes.size} bytes")
        if (bytes.size - offset < HEADER_SIZE) {
            throw TruncatedFrameException("frame header needs $HEADER_SIZE bytes, ${bytes.size - offset} left")
        }
        return parseHeader(BigEndian.getU32(bytes, offset), bytes[offset + 4].toInt() and 0xFF, limits)
    }

    /** Decodes [bytes] as exactly one frame; trailing bytes are an error. */
    fun decode(
        bytes: ByteArray,
        limits: FrameLimits = FrameLimits.ALL,
    ): Frame {
        val header = decodeHeader(bytes, 0, limits)
        val available = bytes.size - HEADER_SIZE
        if (available < header.payloadLength) {
            throw TruncatedFrameException("frame declares ${header.payloadLength} payload bytes, $available present")
        }
        if (available > header.payloadLength) {
            throw ProtocolException("${available - header.payloadLength} trailing bytes after the frame")
        }
        return Frame(header.type, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }

    /** Decodes a concatenation of complete frames; a partial frame at the end is a [TruncatedFrameException]. */
    fun decodeAll(
        bytes: ByteArray,
        limits: FrameLimits = FrameLimits.ALL,
    ): List<Frame> {
        val frames = ArrayList<Frame>()
        var offset = 0
        while (offset < bytes.size) {
            val header = decodeHeader(bytes, offset, limits)
            val start = offset + HEADER_SIZE
            if (bytes.size - start < header.payloadLength) {
                throw TruncatedFrameException("frame declares ${header.payloadLength} payload bytes, ${bytes.size - start} present")
            }
            frames += Frame(header.type, bytes.copyOfRange(start, start + header.payloadLength))
            offset = start + header.payloadLength
        }
        return frames
    }

    internal fun parseHeader(
        length: Long,
        typeCode: Int,
        limits: FrameLimits,
    ): FrameHeader {
        val type = FrameType.fromCode(typeCode) ?: throw ProtocolException("unknown frame type 0x${typeCode.toString(16)}")
        val max = limits.maxPayload(type) ?: throw ProtocolException("frame type $type not accepted here")
        if (length > max) throw ProtocolException("frame length $length exceeds the $max-byte limit for $type")
        return FrameHeader(type, length.toInt())
    }

    private fun checkEncodable(
        type: FrameType,
        payloadLength: Int,
    ) {
        if (payloadLength < 0 || payloadLength > type.maxPayload) {
            throw ProtocolException("payload of $payloadLength bytes does not fit a $type frame (max ${type.maxPayload})")
        }
    }
}

/**
 * Associated data for protected frames (spec change N2): `u32 sealed_length ‖ u8 type ‖ u32 stream_id`, 9 bytes.
 *
 * `sealed_length` is the number of protected bytes (ciphertext ‖ tag). For `Control`, `Chunk` and `Finished` frames
 * that is the frame's `length` field; for `StreamOpen` it is the length minus the 4-byte clear stream id, which is
 * itself authenticated as `stream_id`. Binding the length, type and stream id stops a relay from truncating a frame,
 * relabelling a control frame as a chunk, or moving a frame to another stream.
 */
object FrameAad {
    const val SIZE: Int = 9

    fun of(
        type: FrameType,
        streamId: Int,
        sealedLength: Int,
    ): ByteArray {
        require(type.isProtected) { "$type frames are not protected" }
        require(streamId >= 0) { "stream id must be non-negative" }
        require(sealedLength >= 0) { "sealed length must be non-negative" }
        val out = ByteArray(SIZE)
        BigEndian.putU32(out, 0, sealedLength)
        out[4] = type.code.toByte()
        BigEndian.putU32(out, 5, streamId)
        return out
    }
}
