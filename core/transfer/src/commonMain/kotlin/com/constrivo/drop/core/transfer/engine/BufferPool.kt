package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore

/**
 * A bounded pool of equally sized buffers (architecture §7.4, §15 memory budget): at most [maxBuffers] are out at a
 * time, and [acquire] suspends until one comes back. On the receiver the pool *is* the bounded 16 MiB write queue: a
 * stream reader takes a buffer before it reads a chunk frame and the writer returns it after the bytes are on disk, so a
 * slow disk stops the readers, which stops reading the sockets, which slows the sender (TCP backpressure).
 * Buffers are allocated lazily and reused. Thread-safe.
 */
internal class BufferPool(
    val bufferSize: Int,
    val maxBuffers: Int,
) {
    init {
        require(bufferSize > 0 && maxBuffers > 0) { "pool sizes must be positive" }
    }

    private val permits = Semaphore(maxBuffers)
    private val lock = TransferLock()
    private val free = ArrayDeque<ByteArray>()
    private var allocated = 0

    /** Buffers allocated so far (never more than [maxBuffers]). */
    val allocatedCount: Int get() = lock.withLock { allocated }

    suspend fun acquire(): PooledBuffer {
        permits.acquire()
        val bytes =
            lock.withLock {
                free.removeLastOrNull() ?: ByteArray(bufferSize).also { allocated++ }
            }
        return PooledBuffer(bytes, this)
    }

    /** A buffer without waiting, or null when all are out. */
    fun tryAcquire(): PooledBuffer? {
        if (!permits.tryAcquire()) return null
        val bytes = lock.withLock { free.removeLastOrNull() ?: ByteArray(bufferSize).also { allocated++ } }
        return PooledBuffer(bytes, this)
    }

    /** Drops the buffers that are back in the pool (a parked transfer, S8); later [acquire]s allocate again. */
    fun trim() {
        lock.withLock {
            allocated -= free.size
            free.clear()
        }
    }

    internal fun giveBack(bytes: ByteArray) {
        lock.withLock { free.addLast(bytes) }
        permits.release()
    }
}

/**
 * A buffer from a [BufferPool], returned by [release] once every holder let go. [retain] adds a holder (a bundle's
 * payload is written to several files by one writer each). Releasing more often than retained is ignored.
 */
internal class PooledBuffer(
    val bytes: ByteArray,
    private val pool: BufferPool,
) {
    private val lock = TransferLock()
    private var holders = 1

    fun retain(count: Int = 1) {
        require(count >= 0) { "count must be non-negative" }
        lock.withLock {
            check(holders > 0) { "buffer already released" }
            holders += count
        }
    }

    fun release() {
        val last =
            lock.withLock {
                if (holders <= 0) return
                holders--
                holders == 0
            }
        if (last) pool.giveBack(bytes)
    }
}

/** The dispatcher for blocking file and socket calls: `Dispatchers.IO` on the JVM. */
internal expect fun defaultIoDispatcher(): CoroutineDispatcher
