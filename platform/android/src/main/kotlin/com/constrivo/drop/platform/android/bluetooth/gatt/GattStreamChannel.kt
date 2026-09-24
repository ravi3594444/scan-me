package com.constrivo.drop.platform.android.bluetooth.gatt

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.platform.android.bluetooth.BluetoothDataChannel
import com.constrivo.drop.platform.android.bluetooth.BluetoothTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * The GATT link under a [GattStreamChannel]: one characteristic write (client) or notification (server) per segment.
 * On a device this is the Android GATT client or server; in tests an in-memory pair.
 */
interface GattSegmentTransport {
    /** Largest segment the link carries: the negotiated ATT MTU − 3, between 20 and 512. */
    val maxSegmentSize: Int

    /**
     * Sends one segment and returns once the stack accepted it (Android allows one outstanding GATT operation). Called
     * by one coroutine at a time. Throws [IOException] when the link is gone.
     */
    suspend fun send(segment: ByteArray)

    /** Tears the link down (GATT disconnect). Idempotent; never throws. */
    fun disconnect()
}

/**
 * Tunables of the GATT stream.
 *
 * @property initialCredits data segments the peer may send before it hears from us (the receive buffer is at most
 *   this × 509 bytes). 32 keeps a 20–100 KB/s link busy across a few connection events of latency.
 * @property creditReturnThreshold consumed segments are returned in one `CREDIT` once this many add up; at most half of
 *   [initialCredits], so the peer always keeps at least half its window and never stalls.
 * @property openTimeoutMillis the client waits this long for `OPEN_ACK`.
 * @property closeLingerMillis [GattStreamChannel.close] waits this long for its `CLOSE` to go out before disconnecting.
 * @property writeBatchSegments a large write is queued this many segments at a time, bounding what one write holds.
 */
data class GattStreamConfig(
    val initialCredits: Int = 32,
    val creditReturnThreshold: Int = 8,
    val openTimeoutMillis: Long = 5_000,
    val closeLingerMillis: Long = 1_000,
    val writeBatchSegments: Int = 16,
) {
    init {
        require(initialCredits in 2..1024) { "initial credits must be 2–1024" }
        require(creditReturnThreshold in 1..initialCredits / 2) { "the credit return threshold must be at most half the window" }
        require(openTimeoutMillis > 0 && closeLingerMillis >= 0) { "timeouts must be positive" }
        require(writeBatchSegments >= 1) { "a write batch holds at least one segment" }
    }
}

/**
 * A reliable, ordered byte stream over GATT (architecture §6.1 note, WP7b): the fallback Android↔Android channel where
 * LE L2CAP fails, and the way in for desktops without LE L2CAP. The client (the device that connected) writes segments
 * without response to [com.constrivo.drop.platform.android.bluetooth.DropBluetoothProfile.CLIENT_TO_SERVER_UUID]; the
 * server notifies them on [com.constrivo.drop.platform.android.bluetooth.DropBluetoothProfile.SERVER_TO_CLIENT_UUID].
 *
 * Protocol ([GattSegments]):
 * 1. The client sends `OPEN(version, credits, max segment)`; the server answers `OPEN_ACK` with the version both use
 *    (the lower of the two) and its own credits. Segment size = min(both maximums) − 3-byte header.
 * 2. **Credits** make every write safe: a side sends a `DATA` segment only while it holds a credit, and the receiver
 *    returns credits in `CREDIT` segments as its reader consumes data, so the receive buffer is bounded and the Android
 *    stack never drops a notification or write for lack of room. A peer that sends beyond its credits breaks the stream.
 * 3. **Sequence numbers** per direction detect loss (a gap breaks the stream) and make retries safe (an old number is a
 *    duplicate and is dropped). ATT delivers in order on one bearer, so a gap means a lost segment, never reordering.
 * 4. `CLOSE` ends the stream both ways after the data before it; `RESET(reason)` aborts it.
 *
 * `DataChannel` semantics: [read] returns buffered bytes, `-1` after the peer's `CLOSE` once everything before it was
 * read and after [close]; it throws [GattStreamException] after a reset or when the link is lost (bytes that arrived
 * before a lost link are still read first). [write] returns once every segment was handed to the stack, suspending on
 * credits, which is the backpressure, and it always ends: when the stream breaks or is torn down, a write whose segment
 * is still with the stack (a notification never confirmed, a busy-retry pause) fails with the stream's error instead of
 * waiting for a transport that may never answer. A cancelled write resets the stream (the peer cannot tell where it
 * stopped); a cancelled read loses nothing. One reader and one writer may run at once; concurrent reads, or writes, are
 * serialised. [close] is idempotent.
 *
 * Create with [client] (then call [open]) or [server] (then call [accept] with the first segment, or [refuse] it); route
 * every received segment to [onSegment] and a lost link to [onTransportClosed]. A server channel may be handed to its
 * owner before [accept]: its reads and writes wait until the stream is open.
 */
class GattStreamChannel private constructor(
    private val link: GattSegmentTransport,
    val role: Role,
    private val config: GattStreamConfig,
    context: CoroutineContext,
    override val remoteAddress: String?,
) : BluetoothDataChannel {
    enum class Role { CLIENT, SERVER }

    override val kind: LinkKind get() = LinkKind.BLUETOOTH
    override val transport: BluetoothTransport get() = BluetoothTransport.GATT

    private enum class Phase { NEW, OPENING, OPEN, CLOSED }

    private class Outgoing(
        val source: ByteArray,
        val offset: Int,
        val length: Int,
        val done: CompletableDeferred<Unit>?,
    )

    private sealed interface Next {
        class Send(
            val bytes: ByteArray,
            val done: CompletableDeferred<Unit>?,
            val final: Boolean,
        ) : Next

        data object Stop : Next
    }

    private val lock = Any()

    // --- guarded by lock ---
    private var phase = Phase.NEW
    private var failure: IOException? = null
    private var payloadSize = 0
    private var nextOutSeq = 0
    private var expectedInSeq = 0
    private var peerCredits = 0
    private var grantedToPeer = 0L
    private var receivedData = 0L
    private var creditsToReturn = 0
    private val inbound = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private val outbound = ArrayDeque<Outgoing>()
    private var remoteClosed = false
    private var localClosed = false
    private var closeQueued = false
    private var resetQueued: Int? = null
    private var handshakeQueued: ((Int) -> GattSegment)? = null
    private var duplicates = 0L

    /** What a writer waits for on the `DATA` segment the pump is handing to the link right now, if anything. */
    private var inFlight: CompletableDeferred<Unit>? = null

    /** Segments accepted in order (the `OPEN` included), for [isRepeatedOpen]. */
    private var receivedSegments = 0L

    /** Server: the client's `OPEN` as received, for [isRepeatedOpen]. */
    private var openBytes: ByteArray? = null
    // -----------------------

    private val readSignal = Channel<Unit>(Channel.CONFLATED)
    private val pumpSignal = Channel<Unit>(Channel.CONFLATED)
    private val opened = CompletableDeferred<Unit>()
    private val finalSent = CompletableDeferred<Unit>()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val closing = AtomicBoolean(false)
    private val tornDown = AtomicBoolean(false)
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))

    /** Segments dropped as duplicates (a retried write that had gone out after all), for diagnostics. */
    val duplicateSegments: Long get() = synchronized(lock) { duplicates }

    /** True once the stream is open (after `OPEN_ACK` on the client, after `OPEN` on the server). */
    val isOpen: Boolean get() = synchronized(lock) { phase == Phase.OPEN && failure == null }

    private val localMaxSegment: Int get() = link.maxSegmentSize.coerceIn(GattSegments.MIN_SEGMENT, GattSegments.MAX_SEGMENT)

    /**
     * Client: sends `OPEN` and waits for `OPEN_ACK` within [GattStreamConfig.openTimeoutMillis].
     *
     * @throws GattStreamException when the server refuses, times out or the link fails; the channel is then closed.
     */
    suspend fun open() {
        synchronized(lock) {
            check(role == Role.CLIENT && phase == Phase.NEW) { "open() is for a new client channel" }
            phase = Phase.OPENING
            grantedToPeer = config.initialCredits.toLong()
            val max = localMaxSegment
            handshakeQueued = { seq -> GattSegment.Open(seq, GattSegments.VERSION, config.initialCredits, max) }
        }
        scope.launch { pump() }
        val done =
            try {
                withTimeoutOrNull(config.openTimeoutMillis) { opened.await() }
            } catch (e: CancellationException) {
                fail(GattStreamException("opening the GATT stream was cancelled"), GattSegments.RESET_CANCELLED)
                throw e
            }
        if (done == null) {
            val e = GattStreamException("no OPEN_ACK within ${config.openTimeoutMillis} ms")
            fail(e, GattSegments.RESET_TIMEOUT)
            throw e
        }
    }

    /**
     * Server: processes the client's first segment (its `OPEN`) and answers `OPEN_ACK`. Does nothing when the owner
     * already closed the channel (it may be handed out before this call).
     */
    fun accept(openSegment: ByteArray) {
        synchronized(lock) {
            check(role == Role.SERVER) { "accept() is for a server channel" }
            if (phase == Phase.CLOSED || failure != null) return
            check(phase == Phase.NEW) { "accept() was already called" }
            phase = Phase.OPENING
            openBytes = openSegment.copyOf()
        }
        scope.launch { pump() }
        onSegment(openSegment)
    }

    /**
     * Server: refuses the client's `OPEN` ([openSegment]) with `RESET(reason)` instead of `OPEN_ACK`, so the client fails
     * at once instead of waiting for its open timeout, then ends the channel. A repeat of the same `OPEN` is dropped.
     */
    fun refuse(
        openSegment: ByteArray,
        reason: Int = GattSegments.RESET_REFUSED,
    ) {
        synchronized(lock) {
            check(role == Role.SERVER && phase == Phase.NEW) { "refuse() is for a new server channel" }
            phase = Phase.OPENING
            openBytes = openSegment.copyOf()
            // The OPEN counts as received, so the RESET carries sequence number 0 and a repeat of the OPEN is a duplicate.
            receivedSegments = 1
            expectedInSeq = 1
        }
        scope.launch { pump() }
        fail(GattStreamException("the incoming GATT stream was refused"), reason)
    }

    /**
     * Server: whether [value] repeats, byte for byte, the `OPEN` this channel started with while nothing else has arrived:
     * a write the client's stack reported as not sent although it was, then retried. Such a repeat belongs to this
     * channel (where its sequence number drops it), not to a new stream.
     */
    internal fun isRepeatedOpen(value: ByteArray): Boolean =
        synchronized(lock) { role == Role.SERVER && receivedSegments == 1L && openBytes?.contentEquals(value) == true }

    /** One segment from the peer, in arrival order. Called on the Bluetooth callback thread; never blocks. */
    fun onSegment(value: ByteArray) {
        val segment =
            try {
                GattSegments.decode(value)
            } catch (e: GattStreamException) {
                fail(e, GattSegments.RESET_PROTOCOL, discard = true)
                return
            }
        val effects = synchronized(lock) { receiveLocked(segment) } ?: return
        if (effects.opened) opened.complete(Unit)
        if (effects.abandoned.isNotEmpty()) {
            val closedByPeer = GattStreamException("the peer closed the stream")
            effects.abandoned.forEach { it.done?.completeExceptionally(closedByPeer) }
        }
        if (effects.wakeReader) readSignal.trySend(Unit)
        if (effects.wakePump) pumpSignal.trySend(Unit)
        // A peer that broke the protocol (we reset it) or reset us: nothing it sent can be trusted to continue the stream.
        effects.error?.let { fail(it, effects.resetReason, discard = effects.discard || effects.resetReason != null) }
    }

    /** The GATT link is gone (disconnect, Bluetooth off). */
    fun onTransportClosed(cause: Throwable?) {
        val expected = synchronized(lock) { remoteClosed || localClosed }
        if (expected) {
            teardown()
        } else {
            fail(GattStreamException("the GATT link was lost", cause), resetReason = null)
        }
    }

    private class Effects(
        val opened: Boolean = false,
        val wakeReader: Boolean = false,
        val wakePump: Boolean = false,
        val error: GattStreamException? = null,
        val resetReason: Int? = null,
        val discard: Boolean = false,
        /** Writes that can no longer be delivered (the peer closed), failed outside the lock. */
        val abandoned: List<Outgoing> = emptyList(),
    )

    private fun protocolError(message: String) = Effects(error = GattStreamException(message), resetReason = GattSegments.RESET_PROTOCOL)

    private fun receiveLocked(segment: GattSegment): Effects? {
        if (failure != null || phase == Phase.NEW || phase == Phase.CLOSED) return null
        val distance = GattSegments.distance(segment.seq, expectedInSeq)
        if (distance != 0) {
            if (distance >= DUPLICATE_WINDOW) {
                duplicates++
                return null
            }
            return protocolError("segment ${segment.seq} arrived while $expectedInSeq was expected: segments were lost")
        }
        expectedInSeq = (expectedInSeq + 1) and 0xFFFF
        receivedSegments++
        return when (segment) {
            is GattSegment.Open -> {
                if (role != Role.SERVER || phase != Phase.OPENING) return protocolError("unexpected OPEN")
                val version = minOf(GattSegments.VERSION, segment.version)
                val max = localMaxSegment
                peerCredits = segment.credits
                payloadSize = minOf(max, segment.maxSegment) - GattSegments.HEADER_SIZE
                grantedToPeer = config.initialCredits.toLong()
                handshakeQueued = { seq -> GattSegment.OpenAck(seq, version, config.initialCredits, max) }
                phase = Phase.OPEN
                Effects(opened = true, wakePump = true)
            }

            is GattSegment.OpenAck -> {
                if (role != Role.CLIENT || phase != Phase.OPENING) return protocolError("unexpected OPEN_ACK")
                if (segment.version != GattSegments.VERSION) {
                    return Effects(
                        error = GattStreamException("server chose stream version ${segment.version}"),
                        resetReason = GattSegments.RESET_UNSUPPORTED_VERSION,
                    )
                }
                peerCredits = segment.credits
                payloadSize = minOf(localMaxSegment, segment.maxSegment) - GattSegments.HEADER_SIZE
                phase = Phase.OPEN
                Effects(opened = true, wakePump = true)
            }

            is GattSegment.Data -> {
                if (phase != Phase.OPEN) return protocolError("DATA before the stream opened")
                if (remoteClosed) return protocolError("DATA after CLOSE")
                receivedData++
                if (receivedData > grantedToPeer) {
                    return Effects(error = GattStreamException("peer sent beyond its credits"), resetReason = GattSegments.RESET_OVERFLOW)
                }
                if (localClosed) return Effects()
                inbound.addLast(segment.payload)
                Effects(wakeReader = true)
            }

            is GattSegment.Credit -> {
                if (phase != Phase.OPEN) return protocolError("CREDIT before the stream opened")
                peerCredits += segment.increment
                if (peerCredits >
                    GattSegments.MAX_CREDITS
                ) {
                    return protocolError("peer granted more than ${GattSegments.MAX_CREDITS} credits")
                }
                Effects(wakePump = true)
            }

            is GattSegment.Close -> {
                if (phase != Phase.OPEN) return protocolError("CLOSE before the stream opened")
                remoteClosed = true
                val pending = outbound.toList()
                outbound.clear()
                Effects(wakeReader = true, wakePump = true, abandoned = pending)
            }

            is GattSegment.Reset -> {
                Effects(error = GattStreamException("the peer reset the stream (reason ${segment.reason})"), discard = true)
            }
        }
    }

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (length == 0) return 0
        return readMutex.withLock { readLocked(buffer, offset, length) }
    }

    private suspend fun readLocked(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        while (true) {
            var wakePump = false
            val result: Int? =
                synchronized(lock) {
                    when {
                        localClosed -> {
                            -1
                        }

                        inbound.isNotEmpty() -> {
                            var copied = 0
                            while (copied < length && inbound.isNotEmpty()) {
                                val head = inbound.first()
                                val n = minOf(length - copied, head.size - headOffset)
                                head.copyInto(buffer, offset + copied, headOffset, headOffset + n)
                                copied += n
                                headOffset += n
                                if (headOffset == head.size) {
                                    inbound.removeFirst()
                                    headOffset = 0
                                    creditsToReturn++
                                    if (creditsToReturn >= config.creditReturnThreshold) wakePump = true
                                }
                            }
                            copied
                        }

                        remoteClosed -> {
                            -1
                        }

                        failure != null -> {
                            throw failure!!
                        }

                        else -> {
                            null
                        }
                    }
                }
            if (wakePump) pumpSignal.trySend(Unit)
            if (result != null) return result
            readSignal.receive()
        }
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        synchronized(lock) { writableError()?.let { throw it } }
        if (length == 0) return
        writeMutex.withLock {
            // A client waits for OPEN_ACK; a server handed out before accept() waits for the client's OPEN.
            opened.await()
            var from = offset
            val end = offset + length
            while (from < end) {
                val last =
                    synchronized(lock) {
                        writableError()?.let { throw it }
                        if (phase != Phase.OPEN) throw GattStreamException("the stream is not open")
                        var queued = 0
                        var lastDone: CompletableDeferred<Unit>? = null
                        while (from < end && queued < config.writeBatchSegments) {
                            val n = minOf(payloadSize, end - from)
                            val closesBatch = from + n >= end || queued + 1 == config.writeBatchSegments
                            val done = if (closesBatch) CompletableDeferred<Unit>() else null
                            outbound.addLast(Outgoing(buffer, from, n, done))
                            from += n
                            queued++
                            if (done != null) lastDone = done
                        }
                        lastDone!!
                    }
                pumpSignal.trySend(Unit)
                try {
                    last.await()
                } catch (e: CancellationException) {
                    // Part of the write may be on its way: the peer could not tell where it stopped.
                    fail(GattStreamException("a write was cancelled"), GattSegments.RESET_CANCELLED)
                    throw e
                }
            }
        }
    }

    /** Segments go out as they are written; there is nothing to flush. */
    override suspend fun flush() = Unit

    override suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        val sendClose =
            synchronized(lock) {
                val usable = failure == null && phase == Phase.OPEN && !remoteClosed
                localClosed = true
                inbound.clear()
                headOffset = 0
                if (usable) closeQueued = true
                usable
            }
        readSignal.trySend(Unit)
        if (sendClose) {
            pumpSignal.trySend(Unit)
            withContext(NonCancellable) { withTimeoutOrNull(config.closeLingerMillis) { finalSent.await() } }
        }
        teardown()
    }

    /** Why a write cannot proceed, or null. Call under [lock]. */
    private fun writableError(): IOException? =
        when {
            localClosed -> GattStreamException("the stream is closed")
            remoteClosed -> GattStreamException("the peer closed the stream")
            else -> failure
        }

    private suspend fun pump() {
        while (true) {
            when (val next = synchronized(lock) { nextLocked() }) {
                null -> {
                    pumpSignal.receive()
                }

                Next.Stop -> {
                    return
                }

                is Next.Send -> {
                    try {
                        link.send(next.bytes)
                    } catch (e: CancellationException) {
                        // Torn down while the link still held the segment (a notification never confirmed, a busy-retry
                        // pause): its writer must not wait for a send that will never return. teardown() fails it too;
                        // this covers a scope cancelled from outside.
                        next.done?.completeExceptionally(closedError())
                        if (next.final) finalSent.complete(Unit)
                        throw e
                    } catch (e: Exception) {
                        val failure = e as? GattStreamException ?: GattStreamException("GATT write failed", e)
                        next.done?.completeExceptionally(failure)
                        finalSent.complete(Unit)
                        fail(failure, resetReason = null)
                        return
                    }
                    synchronized(lock) { if (inFlight === next.done) inFlight = null }
                    next.done?.complete(Unit)
                    if (next.final) {
                        finalSent.complete(Unit)
                        return
                    }
                }
            }
        }
    }

    private fun takeSeq(): Int = nextOutSeq.also { nextOutSeq = (nextOutSeq + 1) and 0xFFFF }

    /** The error a writer sees once the stream is gone. */
    private fun closedError(): IOException = synchronized(lock) { failure } ?: GattStreamException("the GATT stream is closed")

    /** The next segment to send, [Next.Stop] when the pump is done, or null to wait. Call under [lock]. */
    private fun nextLocked(): Next? {
        resetQueued?.let { reason ->
            resetQueued = null
            return Next.Send(GattSegments.encode(GattSegment.Reset(takeSeq(), reason)), null, final = true)
        }
        if (failure != null || phase == Phase.CLOSED) return Next.Stop
        handshakeQueued?.let { build ->
            handshakeQueued = null
            return Next.Send(GattSegments.encode(build(takeSeq())), null, final = false)
        }
        if (phase != Phase.OPEN) return null
        if (creditsToReturn >= config.creditReturnThreshold && !remoteClosed && !localClosed) {
            val increment = creditsToReturn
            creditsToReturn = 0
            grantedToPeer += increment
            return Next.Send(GattSegments.encode(GattSegment.Credit(takeSeq(), increment)), null, final = false)
        }
        if (outbound.isNotEmpty() && peerCredits > 0 && !remoteClosed) {
            val o = outbound.removeFirst()
            peerCredits--
            // Out of the queue but not yet sent: fail() and teardown() must still reach its writer.
            inFlight = o.done
            return Next.Send(GattSegments.data(takeSeq(), o.source, o.offset, o.length), o.done, final = false)
        }
        if (closeQueued && outbound.isEmpty()) {
            closeQueued = false
            return Next.Send(GattSegments.encode(GattSegment.Close(takeSeq())), null, final = true)
        }
        return null
    }

    /** Breaks the stream with [error]; sends `RESET(resetReason)` first when one is given. Idempotent. */
    private fun fail(
        error: IOException,
        resetReason: Int?,
        discard: Boolean = false,
    ) {
        val pending: List<Outgoing>
        val flying: CompletableDeferred<Unit>?
        val sendReset: Boolean
        synchronized(lock) {
            if (failure != null || phase == Phase.CLOSED) return
            failure = error
            if (discard) {
                inbound.clear()
                headOffset = 0
            }
            pending = outbound.toList()
            outbound.clear()
            flying = inFlight
            inFlight = null
            sendReset = resetReason != null && phase != Phase.NEW
            if (sendReset) resetQueued = resetReason
        }
        pending.forEach { it.done?.completeExceptionally(error) }
        flying?.completeExceptionally(error)
        opened.completeExceptionally(error)
        readSignal.trySend(Unit)
        if (sendReset) {
            pumpSignal.trySend(Unit)
            scope.launch {
                withTimeoutOrNull(config.closeLingerMillis) { finalSent.await() }
                teardown()
            }
        } else {
            teardown()
        }
    }

    /**
     * Ends everything: fails what is still pending, the segment in flight included (the link may never finish sending
     * it), disconnects the link, stops the pump. Idempotent.
     */
    private fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        val pending: List<Outgoing>
        val flying: CompletableDeferred<Unit>?
        val error: IOException
        synchronized(lock) {
            error = failure ?: GattStreamException("the GATT stream is closed").also { failure = it }
            phase = Phase.CLOSED
            pending = outbound.toList()
            outbound.clear()
            flying = inFlight
            inFlight = null
        }
        pending.forEach { it.done?.completeExceptionally(error) }
        flying?.completeExceptionally(error)
        opened.completeExceptionally(error)
        finalSent.complete(Unit)
        readSignal.trySend(Unit)
        pumpSignal.trySend(Unit)
        try {
            link.disconnect()
        } catch (e: RuntimeException) {
            // disconnect() must not throw; a misbehaving transport does not stop the teardown.
        }
        scope.cancel()
    }

    override fun toString(): String = "GattStreamChannel($role, ${remoteAddress ?: "unknown"})"

    companion object {
        /** Sequence distances from here up are duplicates of segments already received. */
        private const val DUPLICATE_WINDOW = 0x8000

        /** A client channel over [link]; call [open] before reading or writing. */
        fun client(
            link: GattSegmentTransport,
            context: CoroutineContext,
            config: GattStreamConfig = GattStreamConfig(),
            remoteAddress: String? = null,
        ): GattStreamChannel = GattStreamChannel(link, Role.CLIENT, config, context, remoteAddress)

        /** A server channel over [link]; register it for [onSegment] routing, then call [accept] with the `OPEN`. */
        fun server(
            link: GattSegmentTransport,
            context: CoroutineContext,
            config: GattStreamConfig = GattStreamConfig(),
            remoteAddress: String? = null,
        ): GattStreamChannel = GattStreamChannel(link, Role.SERVER, config, context, remoteAddress)
    }
}
