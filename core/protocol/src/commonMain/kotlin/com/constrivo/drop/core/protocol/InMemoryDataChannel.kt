package com.constrivo.drop.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile

/**
 * How many bytes each `read` of an [InMemoryDataChannel] may return, to exercise partial reads deterministically.
 * [maxBytes] gets the zero-based index of the read on that channel and returns a positive cap.
 */
fun interface ReadChunking {
    fun maxBytes(readIndex: Int): Int

    companion object {
        /** A read returns as much as is buffered, up to the requested length. */
        val UNLIMITED: ReadChunking = ReadChunking { Int.MAX_VALUE }

        /** Every read returns at most [size] bytes. */
        fun fixed(size: Int): ReadChunking {
            require(size >= 1) { "size must be positive" }
            return ReadChunking { size }
        }

        /** Reads cycle through [sizes]. */
        fun cycle(vararg sizes: Int): ReadChunking {
            require(sizes.isNotEmpty() && sizes.all { it >= 1 }) { "sizes must be positive" }
            val copy = sizes.copyOf()
            return ReadChunking { copy[it % copy.size] }
        }

        /** Pseudo-random caps in 1..[max], a pure function of [seed] and the read index (SplitMix64). */
        fun random(
            seed: Long,
            max: Int,
        ): ReadChunking {
            require(max >= 1) { "max must be positive" }
            return ReadChunking { index ->
                var z = seed + (index.toLong() + 1) * -0x61c8864680b583ebL
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                z = z xor (z ushr 31)
                ((z ushr 1) % max).toInt() + 1
            }
        }
    }
}

/**
 * An in-memory, bounded, ordered byte pipe implementing [DataChannel], for tests of the framing and of the transfer
 * engine (WP4) without sockets. Create connected ends with [pair].
 *
 * - Writes are copied into segments of at most `maxSegment` bytes and queued; a writer suspends when
 *   `capacitySegments` segments are waiting, like TCP backpressure.
 * - Each read returns at most the cap given by the reader's [ReadChunking], so partial reads can be forced.
 * - [close] ends this side: the peer reads the bytes already queued, then end of stream (-1); a later write by the
 *   peer throws [IllegalStateException]. Reads on a closed end return -1; writes throw [IllegalStateException].
 */
class InMemoryDataChannel private constructor(
    override val kind: LinkKind,
    private val inbound: Pipe,
    private val outbound: Pipe,
    private val chunking: ReadChunking,
    private val maxSegment: Int,
) : DataChannel {
    /** One direction: the queue plus a flag its reader sets when it closes. */
    private class Pipe(
        capacity: Int,
    ) {
        val queue = Channel<ByteArray>(capacity)

        @Volatile
        var readerClosed = false
    }

    private var current: ByteArray? = null
    private var position = 0
    private var reads = 0

    @Volatile
    private var closed = false

    /** Bytes this end has written / read so far. */
    var bytesWritten: Long = 0
        private set
    var bytesRead: Long = 0
        private set

    /** Number of [flush] calls, so tests can check that writers flush. */
    var flushCount: Int = 0
        private set

    val isClosed: Boolean get() = closed

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (closed) return -1
        if (length == 0) return 0
        var segment = current
        while (segment == null || position >= segment.size) {
            segment = inbound.queue.receiveCatching().getOrNull() ?: return -1
            current = segment
            position = 0
        }
        val cap = chunking.maxBytes(reads++).coerceAtLeast(1)
        val n = minOf(length, cap, segment.size - position)
        segment.copyInto(buffer, offset, position, position + n)
        position += n
        bytesRead += n
        return n
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        check(!closed) { "channel is closed" }
        var from = offset
        var remaining = length
        while (remaining > 0) {
            val n = minOf(remaining, maxSegment)
            if (outbound.readerClosed) throw IllegalStateException("peer closed the channel")
            try {
                outbound.queue.send(buffer.copyOfRange(from, from + n))
            } catch (e: CancellationException) {
                // The peer cancels our outbound queue when it closes; report that as a closed channel, not as the
                // cancellation of this coroutine.
                if (outbound.readerClosed) throw IllegalStateException("peer closed the channel", e)
                throw e
            }
            from += n
            remaining -= n
            bytesWritten += n
        }
    }

    override suspend fun flush() {
        flushCount++
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        outbound.queue.close()
        inbound.readerClosed = true
        inbound.queue.cancel()
    }

    companion object {
        /**
         * Two connected ends: bytes written to `first` are read from `second` and the other way round.
         *
         * @param capacitySegments queued segments per direction before a writer suspends.
         * @param maxSegment the largest segment a write is split into.
         */
        fun pair(
            kind: LinkKind = LinkKind.LAN,
            firstChunking: ReadChunking = ReadChunking.UNLIMITED,
            secondChunking: ReadChunking = ReadChunking.UNLIMITED,
            capacitySegments: Int = 64,
            maxSegment: Int = 64 * ProtocolConstants.KIB,
        ): Pair<InMemoryDataChannel, InMemoryDataChannel> {
            require(capacitySegments >= 1) { "capacity must be at least one segment" }
            require(maxSegment >= 1) { "segments must hold at least one byte" }
            val firstToSecond = Pipe(capacitySegments)
            val secondToFirst = Pipe(capacitySegments)
            val first = InMemoryDataChannel(kind, secondToFirst, firstToSecond, firstChunking, maxSegment)
            val second = InMemoryDataChannel(kind, firstToSecond, secondToFirst, secondChunking, maxSegment)
            return first to second
        }
    }
}
