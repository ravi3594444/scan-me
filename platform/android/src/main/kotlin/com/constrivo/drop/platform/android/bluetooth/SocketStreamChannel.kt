package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A connected stream socket: an LE L2CAP or RFCOMM `BluetoothSocket` on a device, piped streams in tests. [close] must
 * unblock a thread blocked in a read or write of its streams (a `BluetoothSocket` does).
 */
interface StreamSocket {
    val input: InputStream
    val output: OutputStream
    val remoteAddress: String?

    fun close()
}

/**
 * Runs blocking calls of one socket on [executor] so a coroutine can wait for them and be cancelled: cancellation
 * closes the socket ([onCancel]), which makes the blocked call fail, and the coroutine resumes with the cancellation.
 * A plain `runInterruptible` would not do: an interrupt does not unblock a Bluetooth socket read.
 */
internal class CancellableBlocking(
    private val executor: Executor,
    private val onCancel: () -> Unit,
) {
    suspend fun <T> call(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { onCancel() }
            try {
                executor.execute {
                    val result = runCatching(block)
                    // After a cancellation the continuation ignores the result (the socket is closed anyway).
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            } catch (e: RejectedExecutionException) {
                continuation.resumeWithException(IOException("no thread for the Bluetooth socket", e))
            }
        }
}

/**
 * [BluetoothDataChannel] over a connected [StreamSocket]: the LE L2CAP channel (Android↔Android, primary) and the RFCOMM
 * socket toward desktops (architecture §6.1 note). Honours `DataChannel` exactly:
 *
 * - [read] returns what the socket delivered (at least one byte), `-1` at the stream's end and after [close]; an
 *   `IOException` from the stack (the peer disappeared, the link dropped) is rethrown unless this side closed first.
 * - [write] blocks, on an I/O thread, until the stack took every byte: the L2CAP credit flow and the RFCOMM window are
 *   the backpressure. A cancelled read or write closes the channel, since the socket cannot be used half-way (as
 *   `TcpDataChannel` does).
 * - One reader and one writer may run concurrently (the engine's reader and writer coroutines); concurrent reads, or
 *   concurrent writes, are serialised.
 * - [close] is idempotent and unblocks both directions.
 */
class SocketStreamChannel(
    private val socket: StreamSocket,
    override val transport: BluetoothTransport,
    io: CoroutineDispatcher = Dispatchers.IO,
) : BluetoothDataChannel {
    override val kind: LinkKind get() = LinkKind.BLUETOOTH
    override val remoteAddress: String? get() = socket.remoteAddress

    private val closed = AtomicBoolean(false)
    private val blocking = CancellableBlocking(io.asExecutor()) { closeSocket() }
    private val readLock = Mutex()
    private val writeLock = Mutex()

    val isClosed: Boolean get() = closed.get()

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (length == 0) return 0
        if (closed.get()) return -1
        return readLock.withLock {
            try {
                var n: Int
                do {
                    if (closed.get()) return@withLock -1
                    n = blocking.call { socket.input.read(buffer, offset, length) }
                } while (n == 0)
                if (n < 0) -1 else n
            } catch (e: IOException) {
                if (closed.get()) -1 else throw e
            }
        }
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (closed.get()) throw IOException("channel is closed")
        if (length == 0) return
        writeLock.withLock {
            if (closed.get()) throw IOException("channel is closed")
            try {
                blocking.call { socket.output.write(buffer, offset, length) }
            } catch (e: IOException) {
                if (closed.get()) throw IOException("channel is closed", e)
                throw e
            }
        }
    }

    override suspend fun flush() {
        if (closed.get()) return
        writeLock.withLock {
            if (closed.get()) return@withLock
            try {
                blocking.call { socket.output.flush() }
            } catch (e: IOException) {
                if (!closed.get()) throw e
            }
        }
    }

    override suspend fun close() {
        closeSocket()
    }

    /** [close] for callers that cannot suspend (a Bluetooth callback refusing the channel). */
    fun closeNow() {
        closeSocket()
    }

    private fun closeSocket() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (e: IOException) {
            // Already gone.
        } catch (e: RuntimeException) {
            // A stack that throws from close is as good as closed.
        }
    }

    override fun toString(): String =
        "SocketStreamChannel($transport, ${remoteAddress ?: "unknown"}, ${if (closed.get()) "closed" else "open"})"

    companion object {
        /**
         * Connects [socket] with [connect] (the blocking `BluetoothSocket.connect()`) within [timeoutMillis]; on time-out
         * or cancellation the socket is closed, which aborts the connect.
         *
         * @throws IOException when the connection fails or times out.
         */
        suspend fun connect(
            socket: StreamSocket,
            transport: BluetoothTransport,
            timeoutMillis: Long,
            io: CoroutineDispatcher = Dispatchers.IO,
            connect: () -> Unit,
        ): SocketStreamChannel {
            val blocking = CancellableBlocking(io.asExecutor()) { runCatching { socket.close() } }
            val done =
                try {
                    withTimeoutOrNull(timeoutMillis) { blocking.call(connect) }
                } catch (e: Throwable) {
                    // A failed connect, a SecurityException, or the caller's cancellation: the socket is not used.
                    runCatching { socket.close() }
                    throw e
                }
            if (done == null) {
                runCatching { socket.close() }
                throw IOException("$transport connect timed out after $timeoutMillis ms")
            }
            return SocketStreamChannel(socket, transport, io)
        }
    }
}
