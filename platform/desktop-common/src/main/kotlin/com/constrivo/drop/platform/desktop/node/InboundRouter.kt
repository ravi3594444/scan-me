package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.FrameCodec
import com.constrivo.drop.core.protocol.FrameLimits
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.transfer.LowLatencyChannel
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The first frame of an inbound control connection, read before the handshake runs under a short deadline: its bytes
 * (replayed to the handshake) and the identity key the `Hello` claims. The claim is not authenticated here, and nothing
 * is decided on it: every inbound connection starts a new session, whose handshake verifies the identity.
 */
internal class InboundHello(
    val frame: ByteArray,
    val claimedIdentity: ByteArray?,
)

/**
 * Reads the `Hello` of an inbound connection and finds its claimed identity (architecture §6.2 with N1:
 * `Hello` = deterministic CBOR map, key 1 the version, key 2 the 32-byte `identity_pk`).
 */
internal object HelloPeek {
    /**
     * Reads exactly one frame from [channel]: the header, then its payload (never more, so nothing of the next frame
     * is consumed).
     *
     * @throws ProtocolException when the first frame is not a well-formed `Hello` frame header.
     * @throws EOFException when the connection ends first.
     */
    suspend fun read(channel: DataChannel): InboundHello {
        val header = ByteArray(FrameCodec.HEADER_SIZE)
        readFully(channel, header)
        val decoded = FrameCodec.decodeHeader(header, 0, FrameLimits.HANDSHAKE)
        if (decoded.type != FrameType.HELLO) throw ProtocolException("an inbound connection must start with Hello, not ${decoded.type}")
        val payload = ByteArray(decoded.payloadLength)
        readFully(channel, payload)
        return InboundHello(header + payload, identityOf(payload))
    }

    /**
     * The `identity_pk` of a `Hello` payload, or null when the bytes do not start like one: a CBOR map (major type 5)
     * of 1–23 entries whose first key is 1 with an unsigned version, then key 2 with a 32-byte byte string.
     */
    fun identityOf(payload: ByteArray): ByteArray? {
        var at = 0

        fun next(): Int? = if (at < payload.size) payload[at++].toInt() and 0xFF else null

        val map = next() ?: return null
        if (map ushr 5 != MAJOR_MAP || map and 0x1F !in 1..23) return null
        if (next() != 0x01) return null
        val version = next() ?: return null
        if (version ushr 5 != MAJOR_UINT) return null
        when (version and 0x1F) {
            in 0..23 -> Unit
            24 -> at += 1
            25 -> at += 2
            26 -> at += 4
            27 -> at += 8
            else -> return null
        }
        if (next() != 0x02) return null
        if (next() != BYTES_ONE_BYTE_LENGTH || next() != IDENTITY_SIZE) return null
        if (at + IDENTITY_SIZE > payload.size) return null
        return payload.copyOfRange(at, at + IDENTITY_SIZE)
    }

    private suspend fun readFully(
        channel: DataChannel,
        into: ByteArray,
    ) {
        var filled = 0
        while (filled < into.size) {
            val n = channel.read(into, filled, into.size - filled)
            if (n < 0) throw EOFException("the connection ended after $filled of ${into.size} bytes")
            filled += n
        }
    }

    private const val MAJOR_UINT = 0
    private const val MAJOR_MAP = 5
    private const val BYTES_ONE_BYTE_LENGTH = 0x58
    private const val IDENTITY_SIZE = 32
}

/**
 * A [DataChannel] that first returns [prefix] (bytes already read from [delegate], such as a peeked `Hello`) and then
 * reads [delegate]; writes, flushes and closes go straight to [delegate], and so does [setLowLatency].
 */
internal class ReplayChannel(
    private val delegate: DataChannel,
    prefix: ByteArray,
) : DataChannel,
    LowLatencyChannel {
    private val pending = prefix.copyOf()
    private var position = 0
    private val lock = Any()

    override val kind: LinkKind get() = delegate.kind

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (length == 0) return 0
        val copied =
            synchronized(lock) {
                val left = pending.size - position
                if (left <= 0) {
                    0
                } else {
                    val n = minOf(left, length)
                    pending.copyInto(buffer, offset, position, position + n)
                    position += n
                    n
                }
            }
        return if (copied > 0) copied else delegate.read(buffer, offset, length)
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) = delegate.write(buffer, offset, length)

    override suspend fun flush() = delegate.flush()

    override suspend fun close() = delegate.close()

    override fun setLowLatency(enabled: Boolean) {
        (delegate as? LowLatencyChannel)?.setLowLatency(enabled)
    }

    override fun toString(): String = "ReplayChannel($delegate)"
}

/**
 * Admission of inbound control connections before they authenticate (§13: anyone on the LAN can connect). At most
 * [maxTotal] connections may be between their accept and the end of their handshake at once, and at most [maxPerHost]
 * from one address, so a host that opens silent connections neither exhausts the node's threads nor locks every other
 * device out; a connection beyond either limit is closed at once. Thread-safe.
 */
internal class InboundGate(
    private val maxTotal: Int,
    private val maxPerHost: Int,
) {
    private val lock = Any()
    private var total = 0
    private val perHost = HashMap<String, Int>()

    init {
        require(maxTotal >= 1 && maxPerHost >= 1) { "the gate must admit at least one connection" }
    }

    /** A place for a connection from [host] (its address, null when unknown), or null when the gate is full. */
    fun tryEnter(host: String?): Ticket? {
        val key = host ?: UNKNOWN_HOST
        synchronized(lock) {
            val fromHost = perHost[key] ?: 0
            if (total >= maxTotal || fromHost >= maxPerHost) return null
            total++
            perHost[key] = fromHost + 1
        }
        return Ticket(key)
    }

    /** Connections admitted and not yet released (tests). */
    val size: Int get() = synchronized(lock) { total }

    /** One admitted connection; [close] gives its place back, once. */
    inner class Ticket internal constructor(
        private val key: String,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) {
                total--
                val left = (perHost[key] ?: 1) - 1
                if (left <= 0) perHost.remove(key) else perHost[key] = left
            }
        }
    }

    private companion object {
        const val UNKNOWN_HOST = "?"
    }
}
