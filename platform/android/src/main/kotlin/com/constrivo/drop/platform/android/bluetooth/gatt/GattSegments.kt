package com.constrivo.drop.platform.android.bluetooth.gatt

import java.io.IOException

/**
 * The GATT stream broke: a malformed or out-of-order segment, a peer that exceeded its credits, a reset from the peer,
 * or the GATT link going away. An [IOException], as every `DataChannel` failure is.
 */
class GattStreamException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * One segment of the GATT stream (architecture §6.1 note, WP7b): the value of one write-without-response (client to
 * server) or one notification (server to client). Layout: `u8 type ‖ u16 seq (big-endian) ‖ body`.
 *
 * Each direction numbers its segments from 0 (wrapping at 65536); the receiver accepts exactly the next number, drops an
 * older one as a duplicate (a write the Android stack reported as failed but sent anyway, then retried) and treats a gap
 * as a lost segment, which breaks the stream.
 */
sealed class GattSegment(
    val seq: Int,
) {
    /** Client → server, first segment: `u8 version ‖ u16 credits ‖ u16 max segment`. */
    class Open(
        seq: Int,
        val version: Int,
        val credits: Int,
        val maxSegment: Int,
    ) : GattSegment(seq)

    /** Server → client, first segment: the version both use, the server's credits and its maximum segment. */
    class OpenAck(
        seq: Int,
        val version: Int,
        val credits: Int,
        val maxSegment: Int,
    ) : GattSegment(seq)

    /** Stream bytes (at least one). Each consumes one credit. */
    class Data(
        seq: Int,
        val payload: ByteArray,
    ) : GattSegment(seq)

    /** `u16 increment`: the sender may send that many more [Data] segments. */
    class Credit(
        seq: Int,
        val increment: Int,
    ) : GattSegment(seq)

    /** Orderly close of the whole stream (both directions): the peer reads what came before, then end of stream. */
    class Close(
        seq: Int,
    ) : GattSegment(seq)

    /** Abort: `u8 reason` ([GattSegments.RESET_PROTOCOL] and friends). The peer fails at once. */
    class Reset(
        seq: Int,
        val reason: Int,
    ) : GattSegment(seq)
}

/** Encoding and decoding of [GattSegment]s. Decoding throws only [GattStreamException]. */
object GattSegments {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 3

    const val TYPE_OPEN: Int = 0x01
    const val TYPE_OPEN_ACK: Int = 0x02
    const val TYPE_DATA: Int = 0x03
    const val TYPE_CREDIT: Int = 0x04
    const val TYPE_CLOSE: Int = 0x05
    const val TYPE_RESET: Int = 0x06

    /** The smallest segment a link carries: the default ATT MTU of 23 minus the 3-byte ATT header. */
    const val MIN_SEGMENT: Int = 20

    /** The largest attribute value. */
    const val MAX_SEGMENT: Int = 512

    /** Credits in one grant; also the most a receiver may have outstanding. */
    const val MAX_CREDITS: Int = 0xFFFF

    const val RESET_UNSPECIFIED: Int = 0
    const val RESET_PROTOCOL: Int = 1
    const val RESET_UNSUPPORTED_VERSION: Int = 2
    const val RESET_CANCELLED: Int = 3
    const val RESET_TIMEOUT: Int = 4
    const val RESET_OVERFLOW: Int = 5

    /** The server cannot take another stream (all sessions in use, or its owner's queue is full); sent instead of `OPEN_ACK`. */
    const val RESET_REFUSED: Int = 6

    private const val OPEN_BODY = 5

    fun encode(segment: GattSegment): ByteArray =
        when (segment) {
            is GattSegment.Open -> handshake(TYPE_OPEN, segment.seq, segment.version, segment.credits, segment.maxSegment)
            is GattSegment.OpenAck -> handshake(TYPE_OPEN_ACK, segment.seq, segment.version, segment.credits, segment.maxSegment)
            is GattSegment.Data -> data(segment.seq, segment.payload, 0, segment.payload.size)
            is GattSegment.Credit -> header(TYPE_CREDIT, segment.seq, 2).also { writeU16(it, HEADER_SIZE, segment.increment) }
            is GattSegment.Close -> header(TYPE_CLOSE, segment.seq, 0)
            is GattSegment.Reset -> header(TYPE_RESET, segment.seq, 1).also { it[HEADER_SIZE] = segment.reason.toByte() }
        }

    /** A [GattSegment.Data] segment for `source[offset, offset + length)`, copied once. */
    fun data(
        seq: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(length >= 1) { "a data segment carries at least one byte" }
        return header(TYPE_DATA, seq, length).also { source.copyInto(it, HEADER_SIZE, offset, offset + length) }
    }

    /**
     * Decodes one segment.
     *
     * @throws GattStreamException for a value shorter than the header, an unknown type, a body of the wrong size, a
     *   version of 0, credits of 0, or a maximum segment outside 20–512. Extra bytes after an `OPEN`/`OPEN_ACK` body are
     *   ignored (later versions may append fields); every other type must be exact.
     */
    fun decode(value: ByteArray): GattSegment {
        if (value.size < HEADER_SIZE) throw GattStreamException("segment of ${value.size} bytes is shorter than its header")
        val type = value[0].toInt() and 0xFF
        val seq = readU16(value, 1)
        val body = value.size - HEADER_SIZE
        return when (type) {
            TYPE_OPEN, TYPE_OPEN_ACK -> {
                if (body < OPEN_BODY) throw GattStreamException("handshake segment body is $body bytes, need $OPEN_BODY")
                val version = value[HEADER_SIZE].toInt() and 0xFF
                val credits = readU16(value, HEADER_SIZE + 1)
                val maxSegment = readU16(value, HEADER_SIZE + 3)
                if (version == 0) throw GattStreamException("stream version 0")
                if (credits == 0) throw GattStreamException("handshake grants no credits")
                if (maxSegment !in
                    MIN_SEGMENT..MAX_SEGMENT
                ) {
                    throw GattStreamException("maximum segment $maxSegment outside $MIN_SEGMENT–$MAX_SEGMENT")
                }
                if (type == TYPE_OPEN) {
                    GattSegment.Open(seq, version, credits, maxSegment)
                } else {
                    GattSegment.OpenAck(seq, version, credits, maxSegment)
                }
            }

            TYPE_DATA -> {
                if (body < 1) throw GattStreamException("empty data segment")
                GattSegment.Data(seq, value.copyOfRange(HEADER_SIZE, value.size))
            }

            TYPE_CREDIT -> {
                if (body != 2) throw GattStreamException("credit segment body is $body bytes, need 2")
                val increment = readU16(value, HEADER_SIZE)
                if (increment == 0) throw GattStreamException("credit segment grants nothing")
                GattSegment.Credit(seq, increment)
            }

            TYPE_CLOSE -> {
                if (body != 0) throw GattStreamException("close segment carries $body bytes")
                GattSegment.Close(seq)
            }

            TYPE_RESET -> {
                if (body != 1) throw GattStreamException("reset segment body is $body bytes, need 1")
                GattSegment.Reset(seq, value[HEADER_SIZE].toInt() and 0xFF)
            }

            else -> {
                throw GattStreamException("unknown segment type 0x${type.toString(16)}")
            }
        }
    }

    /** `(seq − expected) mod 2¹⁶`: 0 is the expected segment, 1–32767 a gap, 32768–65535 an old duplicate. */
    fun distance(
        seq: Int,
        expected: Int,
    ): Int = (seq - expected) and 0xFFFF

    private fun handshake(
        type: Int,
        seq: Int,
        version: Int,
        credits: Int,
        maxSegment: Int,
    ): ByteArray {
        require(version in 1..0xFF && credits in 1..MAX_CREDITS && maxSegment in MIN_SEGMENT..MAX_SEGMENT) {
            "handshake fields out of range"
        }
        return header(type, seq, OPEN_BODY).also {
            it[HEADER_SIZE] = version.toByte()
            writeU16(it, HEADER_SIZE + 1, credits)
            writeU16(it, HEADER_SIZE + 3, maxSegment)
        }
    }

    private fun header(
        type: Int,
        seq: Int,
        bodySize: Int,
    ): ByteArray {
        require(seq in 0..0xFFFF) { "sequence number $seq is not 16 bits" }
        return ByteArray(HEADER_SIZE + bodySize).also {
            it[0] = type.toByte()
            writeU16(it, 1, seq)
        }
    }

    private fun writeU16(
        out: ByteArray,
        offset: Int,
        value: Int,
    ) {
        require(value in 0..0xFFFF) { "$value is not 16 bits" }
        out[offset] = (value ushr 8).toByte()
        out[offset + 1] = value.toByte()
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
