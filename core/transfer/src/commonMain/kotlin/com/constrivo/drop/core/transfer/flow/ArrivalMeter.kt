package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.LowLatencyChannel
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock
import kotlin.concurrent.Volatile

/**
 * The receiver's count of bytes that arrived per link kind, drained into the 250 ms throughput samples the ladder's
 * LAN check reads (architecture §4: 10 MB within the 1 s measurement window). Wi-Fi bytes are counted as the socket
 * delivers them ([CountingChannel]), not per finished chunk: a 4 MiB chunk is 40% of the threshold, and a LAN of four
 * 3 MB/s streams would otherwise show nothing before its window closed. Thread-safe.
 */
internal class ArrivalMeter {
    private val lock = TransferLock()
    private val counts = HashMap<LinkKind, Long>()

    fun add(
        kind: LinkKind,
        bytes: Long,
    ) {
        if (bytes <= 0) return
        lock.withLock { counts[kind] = (counts[kind] ?: 0L) + bytes }
    }

    /** The bytes per kind since the previous call; empty when nothing arrived. */
    fun drain(): Map<LinkKind, Long> =
        lock.withLock {
            if (counts.isEmpty()) {
                emptyMap()
            } else {
                HashMap(counts).also { counts.clear() }
            }
        }
}

/**
 * A [DataChannel] that reports the bytes moving through it as the socket moves them: every read to [onRead], and
 * writes in slices of at most [writeSlice] bytes to [onWrite], so a 4 MiB frame counts as it goes out rather than all
 * at once. The receiver counts reads (the ladder's LAN check and the live speed), the sender writes (the live speed,
 * F-F1). With a [clock], [lastReadAt] is when bytes last arrived: a 4 MiB frame on a slow stream takes seconds, and
 * the link watchdog must not take it for silence. Everything else, including [LowLatencyChannel], passes through to
 * [delegate].
 */
internal class CountingChannel(
    private val delegate: DataChannel,
    private val onRead: ((kind: LinkKind, bytes: Long) -> Unit)? = null,
    private val onWrite: ((kind: LinkKind, bytes: Long) -> Unit)? = null,
    private val writeSlice: Int = WRITE_SLICE,
    private val clock: TransferClock? = null,
) : DataChannel by delegate,
    LowLatencyChannel {
    init {
        require(writeSlice > 0) { "write slice must be positive" }
    }

    /** [TransferClock.elapsedMillis] when the last bytes were read (with a [clock]), else 0. */
    @Volatile
    var lastReadAt: Long = clock?.elapsedMillis() ?: 0
        private set

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val n = delegate.read(buffer, offset, length)
        if (n > 0) {
            onRead?.invoke(delegate.kind, n.toLong())
            clock?.let { lastReadAt = it.elapsedMillis() }
        }
        return n
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val count = onWrite
        if (count == null) {
            delegate.write(buffer, offset, length)
            return
        }
        var from = offset
        val end = offset + length
        while (from < end) {
            val n = minOf(writeSlice, end - from)
            delegate.write(buffer, from, n)
            count(delegate.kind, n.toLong())
            from += n
        }
    }

    override fun setLowLatency(enabled: Boolean) {
        (delegate as? LowLatencyChannel)?.setLowLatency(enabled)
    }

    companion object {
        /** The socket slice of §7.4 (`TcpDataChannel.IO_SLICE`). */
        const val WRITE_SLICE: Int = 256 * 1024
    }
}
