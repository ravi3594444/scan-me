package com.constrivo.drop.platform.android.bluetooth

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded, blocking byte pipe that behaves like one direction of a Bluetooth socket: a full buffer blocks the writer
 * (backpressure), an empty one blocks the reader, closing the write end gives the reader end of stream after the
 * buffered bytes, and closing the read end makes a blocked read or write fail with an [IOException] (as
 * `BluetoothSocket.close()` does). Unlike `PipedInputStream` it does not care which threads read and write.
 */
internal class BlockingPipe(
    capacity: Int = 4096,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var writerClosed = false
    private var readerClosed = false

    /** Bytes written into the pipe so far. */
    var written: Long = 0
        private set

    val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (len == 0) return 0
                lock.withLock {
                    while (count == 0) {
                        if (readerClosed) throw IOException("socket closed")
                        if (writerClosed) return -1
                        changed.await()
                    }
                    if (readerClosed) throw IOException("socket closed")
                    val n = minOf(len, count)
                    for (i in 0 until n) b[off + i] = buffer[(head + i) % buffer.size]
                    head = (head + n) % buffer.size
                    count -= n
                    changed.signalAll()
                    return n
                }
            }

            override fun close() = closeReader()
        }

    val output: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                var from = off
                var remaining = len
                lock.withLock {
                    while (remaining > 0) {
                        while (count == buffer.size) {
                            if (readerClosed || writerClosed) throw IOException("broken pipe")
                            changed.await()
                        }
                        if (readerClosed || writerClosed) throw IOException("broken pipe")
                        val n = minOf(remaining, buffer.size - count)
                        for (i in 0 until n) buffer[(head + count + i) % buffer.size] = b[from + i]
                        count += n
                        from += n
                        remaining -= n
                        written += n
                        changed.signalAll()
                    }
                }
            }

            override fun close() = closeWriter()
        }

    fun closeReader() {
        lock.withLock {
            readerClosed = true
            changed.signalAll()
        }
    }

    fun closeWriter() {
        lock.withLock {
            writerClosed = true
            changed.signalAll()
        }
    }
}

/** One end of an in-memory socket pair: closing it ends both of its directions, like `BluetoothSocket.close()`. */
internal class PipeSocket(
    private val inbound: BlockingPipe,
    private val outbound: BlockingPipe,
    override val remoteAddress: String? = "11:22:33:44:55:66",
) : StreamSocket {
    @Volatile var closes = 0
        private set

    override val input: InputStream get() = inbound.input
    override val output: OutputStream get() = outbound.output

    override fun close() {
        closes++
        inbound.closeReader()
        outbound.closeWriter()
    }

    companion object {
        fun pair(capacity: Int = 4096): Pair<PipeSocket, PipeSocket> {
            val ab = BlockingPipe(capacity)
            val ba = BlockingPipe(capacity)
            return PipeSocket(ba, ab) to PipeSocket(ab, ba)
        }
    }
}
