package com.constrivo.drop.core.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads frames (architecture §7.1) from a [DataChannel] with buffering and exact-read semantics.
 *
 * - [readFrame] returns one complete frame, however the channel splits the bytes (one byte per read is fine).
 * - A clean end of stream exactly at a frame boundary returns `null`; an end of stream inside a header or payload
 *   throws [TruncatedFrameException].
 * - The header is validated against the [FrameLimits] in force **before** the payload buffer is allocated, so the
 *   reader never allocates more than `limits.largestPayload` plus its own buffer per frame.
 *
 * Small frames are served from an internal buffer of [bufferSize] bytes; payloads larger than the buffer are read
 * straight into their destination array. Not safe for concurrent use: one coroutine reads a given stream.
 */
class FrameReader(
    private val channel: DataChannel,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    /** Limits used when [readFrame] is called without an explicit argument; switch it after the handshake. */
    var limits: FrameLimits = FrameLimits.ALL,
) {
    private val buffer: ByteArray
    private var start = 0
    private var end = 0
    private var endOfStream = false

    /** Bytes consumed from the channel so far, frame headers included. */
    var bytesRead: Long = 0
        private set

    init {
        require(bufferSize >= MIN_BUFFER_SIZE) { "buffer must hold at least $MIN_BUFFER_SIZE bytes" }
        buffer = ByteArray(bufferSize)
    }

    /**
     * Reads the next frame, or returns `null` at a clean end of stream.
     *
     * @throws TruncatedFrameException if the stream ends inside a frame.
     * @throws ProtocolException for an unknown type, a type [limits] does not accept, or an oversized length.
     */
    suspend fun readFrame(limits: FrameLimits = this.limits): Frame? {
        if (!fill(FrameCodec.HEADER_SIZE)) {
            if (end == start) return null
            throw TruncatedFrameException("stream ended inside a frame header (${end - start} of ${FrameCodec.HEADER_SIZE} bytes)")
        }
        val header = FrameCodec.parseHeader(BigEndian.getU32(buffer, start), buffer[start + 4].toInt() and 0xFF, limits)
        start += FrameCodec.HEADER_SIZE
        val payload = ByteArray(header.payloadLength)
        readFully(payload, header)
        return Frame(header.type, payload)
    }

    /** Ensures at least [count] bytes are buffered; returns false if the stream ended first. */
    private suspend fun fill(count: Int): Boolean {
        if (end - start >= count) return true
        if (start > 0) {
            buffer.copyInto(buffer, 0, start, end)
            end -= start
            start = 0
        }
        while (end < count) {
            val n = readSome(buffer, end, buffer.size - end)
            if (n < 0) return false
            end += n
        }
        return true
    }

    private suspend fun readFully(
        destination: ByteArray,
        header: FrameHeader,
    ) {
        var filled = minOf(end - start, destination.size)
        buffer.copyInto(destination, 0, start, start + filled)
        start += filled
        while (filled < destination.size) {
            val remaining = destination.size - filled
            val n =
                if (remaining >= buffer.size) {
                    readSome(destination, filled, remaining)
                } else {
                    // Refill the buffer, then copy what this frame needs; the rest stays buffered for the next frame.
                    start = 0
                    end = 0
                    val got = readSome(buffer, 0, buffer.size)
                    if (got > 0) {
                        end = got
                        val take = minOf(got, remaining)
                        buffer.copyInto(destination, filled, 0, take)
                        start = take
                        take
                    } else {
                        got
                    }
                }
            if (n < 0) {
                throw TruncatedFrameException(
                    "stream ended inside a ${header.type} payload ($filled of ${header.payloadLength} bytes)",
                )
            }
            filled += n
        }
    }

    private suspend fun readSome(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (endOfStream) return -1
        var zeroReads = 0
        while (true) {
            val n = channel.read(destination, offset, length)
            when {
                n < 0 -> {
                    endOfStream = true
                    return -1
                }

                n > 0 -> {
                    check(n <= length) { "DataChannel.read returned $n bytes for a $length-byte request" }
                    bytesRead += n
                    return n
                }

                ++zeroReads >= MAX_ZERO_READS -> {
                    error("DataChannel.read returned 0 bytes $MAX_ZERO_READS times in a row")
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 64 * ProtocolConstants.KIB
        const val MIN_BUFFER_SIZE: Int = 16
        private const val MAX_ZERO_READS = 1000
    }
}

/**
 * Writes frames (architecture §7.1) to a [DataChannel].
 *
 * Safe for concurrent use: a [Mutex] keeps frames from interleaving, so control messages, acks and heartbeats may be
 * written from different coroutines. Frames written with `flush = false` are coalesced in an internal buffer of
 * [bufferSize] bytes and go out on the next flushing write or [flush]; frames larger than the buffer are written
 * directly. If a write fails or is cancelled mid-frame, the stream is left in an unknown state: the writer refuses
 * further frames and the caller must close the channel.
 */
class FrameWriter(
    private val channel: DataChannel,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    private val mutex = Mutex()
    private val buffer: ByteArray
    private var used = 0
    private var broken = false

    /** Bytes handed to the channel so far, frame headers included. */
    var bytesWritten: Long = 0
        private set

    init {
        require(bufferSize >= FrameCodec.HEADER_SIZE) { "buffer must hold at least a frame header" }
        buffer = ByteArray(bufferSize)
    }

    /**
     * Writes one frame of [type] whose payload is `payload[offset until offset + length]`.
     *
     * @throws ProtocolException if the payload exceeds [FrameType.maxPayload].
     * @throws IllegalStateException if an earlier write failed.
     */
    suspend fun writeFrame(
        type: FrameType,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
        flush: Boolean = true,
    ) {
        require(offset >= 0 && length >= 0 && offset <= payload.size - length) { "payload range out of bounds" }
        if (length > type.maxPayload) {
            throw ProtocolException("payload of $length bytes does not fit a $type frame (max ${type.maxPayload})")
        }
        mutex.withLock {
            check(!broken) { "an earlier write failed; the stream must be closed" }
            guarded {
                val total = FrameCodec.HEADER_SIZE + length
                if (used + total > buffer.size) drain()
                FrameCodec.writeHeader(buffer, used, type, length)
                used += FrameCodec.HEADER_SIZE
                if (total <= buffer.size) {
                    payload.copyInto(buffer, used, offset, offset + length)
                    used += length
                } else {
                    drain()
                    channel.write(payload, offset, length)
                    bytesWritten += length
                }
                if (flush) {
                    drain()
                    channel.flush()
                }
            }
        }
    }

    suspend fun writeFrame(
        frame: Frame,
        flush: Boolean = true,
    ) = writeFrame(frame.type, frame.payload, flush = flush)

    /** Writes any coalesced frames and flushes the channel. */
    suspend fun flush() {
        mutex.withLock {
            check(!broken) { "an earlier write failed; the stream must be closed" }
            guarded {
                drain()
                channel.flush()
            }
        }
    }

    private suspend fun drain() {
        if (used == 0) return
        channel.write(buffer, 0, used)
        bytesWritten += used
        used = 0
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            broken = true
            throw t
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 64 * ProtocolConstants.KIB
    }
}
