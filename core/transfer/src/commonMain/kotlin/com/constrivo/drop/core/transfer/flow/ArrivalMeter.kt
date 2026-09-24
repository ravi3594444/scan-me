package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.LowLatencyChannel
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

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
 * A [DataChannel] that adds every byte read to [meter] under the channel's kind. Everything else, including
 * [LowLatencyChannel], passes through to [delegate].
 */
internal class CountingChannel(
    private val delegate: DataChannel,
    private val meter: ArrivalMeter,
) : DataChannel by delegate,
    LowLatencyChannel {
    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val n = delegate.read(buffer, offset, length)
        if (n > 0) meter.add(delegate.kind, n.toLong())
        return n
    }

    override fun setLowLatency(enabled: Boolean) {
        (delegate as? LowLatencyChannel)?.setLowLatency(enabled)
    }
}
