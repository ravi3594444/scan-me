package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.Ack
import com.constrivo.drop.core.protocol.ChunkRef
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.TransferClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Batches the receiver's acknowledgements (architecture §7.2): an `Ack` goes out when [maxRefs] (8) references are
 * pending or [maxDelayMillis] (50 ms) after the first one, whichever comes first. A Bluetooth block (spec change S1) is
 * [add]ed as urgent and flushes the batch at once, since the sender keeps only one block in flight on Bluetooth and
 * would otherwise lose 50 ms per 16 KiB block.
 *
 * [send] runs in one coroutine of [scope], so acks leave in order; a failed send drops that batch (the control route is
 * gone, and the `Resume` that follows lists what is still missing). The deadline is on [clock]'s monotonic time line
 * ([TransferClock.elapsedMillis]), which must run with [scope]'s dispatcher time. Close with [close]; references added
 * afterwards are dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AckBatcher(
    private val transferId: TransferId,
    scope: CoroutineScope,
    private val clock: TransferClock,
    private val maxRefs: Int = ProtocolConstants.ACK_BATCH_CHUNKS,
    private val maxDelayMillis: Long = ProtocolConstants.ACK_BATCH_MILLIS,
    private val send: suspend (Ack) -> Unit,
) {
    init {
        require(maxRefs in 1..ProtocolConstants.MAX_ACK_REFS) { "maxRefs out of range" }
        require(maxDelayMillis > 0) { "maxDelayMillis must be positive" }
    }

    private class Pending(
        val ref: ChunkRef,
        val urgent: Boolean,
    )

    private val inbox = Channel<Pending>(Channel.UNLIMITED)
    private val job: Job = scope.launch { run() }

    /** Batches handed to [send] so far. */
    var batchesSent: Int = 0
        private set

    /** Queues [ref]; [urgent] (a Bluetooth block) sends the pending batch now. */
    fun add(
        ref: ChunkRef,
        urgent: Boolean = false,
    ) {
        inbox.trySend(Pending(ref, urgent))
    }

    /** Stops batching; references already added are still sent. */
    fun close() {
        inbox.close()
    }

    /** [close], then waits until the last batch went out. */
    suspend fun closeAndJoin() {
        close()
        job.join()
    }

    private suspend fun run() {
        while (true) {
            val first = inbox.receiveCatching().getOrNull() ?: return
            val batch = ArrayList<ChunkRef>(maxRefs)
            batch += first.ref
            var urgent = first.urgent
            var open = true
            val deadline = clock.elapsedMillis() + maxDelayMillis
            while (open && !urgent && batch.size < maxRefs) {
                val ready = inbox.tryReceive()
                val next: Pending? =
                    when {
                        ready.isSuccess -> {
                            ready.getOrThrow()
                        }

                        ready.isClosed -> {
                            open = false
                            null
                        }

                        else -> {
                            val remaining = deadline - clock.elapsedMillis()
                            if (remaining <= 0) {
                                null
                            } else {
                                select {
                                    inbox.onReceiveCatching { result -> result.getOrNull().also { if (it == null) open = false } }
                                    onTimeout(remaining) { null }
                                }
                            }
                        }
                    }
                if (next == null) break
                batch += next.ref
                urgent = next.urgent
            }
            try {
                send(Ack(transferId, batch))
                batchesSent++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The control route is gone; the Resume that follows covers these units.
            }
            if (!open) return
        }
    }
}
