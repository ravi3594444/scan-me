package com.constrivo.drop.core.transfer.net

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.LowLatencyChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates the socket for a data or control connection before it connects (architecture §7.4): on Android the platform
 * layer binds it to the link's `Network` (`network.bindSocket(channel.socket())`) so traffic never drifts to mobile
 * data; on desktops the default opens a plain socket.
 */
fun interface TcpSocketFactory {
    fun create(): SocketChannel

    companion object {
        val DEFAULT: TcpSocketFactory = TcpSocketFactory { SocketChannel.open() }
    }
}

/**
 * A [DataChannel] over a TCP connection (architecture §7.4) on a blocking `java.nio` [SocketChannel], with every call
 * on [io] (`Dispatchers.IO`). Sockets get 4 MiB send and receive buffers; `TCP_NODELAY` is on only for connections
 * that carry control ([setLowLatency], called by the engine for the control stream).
 *
 * A read that is cancelled closes the channel (the socket cannot be read half-way). [read] returns -1 at the peer's
 * orderly close and after [close]; a reset connection throws [IOException]. [close] is idempotent.
 */
class TcpDataChannel(
    private val channel: SocketChannel,
    override val kind: LinkKind,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DataChannel,
    LowLatencyChannel {
    private val closed = AtomicBoolean(false)

    init {
        require(channel.isBlocking) { "the socket channel must be in blocking mode" }
    }

    /** The peer's address, for logs. */
    val remoteAddress: InetSocketAddress? get() = runCatching { channel.remoteAddress as? InetSocketAddress }.getOrNull()

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (length == 0) return 0
        if (closed.get()) return -1
        return try {
            // At most one slice per call: the JDK stages heap-buffer I/O through a per-thread direct buffer of the
            // request's size, so large requests would pin megabytes of native memory on every I/O thread (§15).
            runInterruptible(io) { channel.read(ByteBuffer.wrap(buffer, offset, minOf(length, IO_SLICE))) }
        } catch (e: AsynchronousCloseException) {
            if (closed.get()) -1 else throw e
        } catch (e: ClosedChannelException) {
            if (closed.get()) -1 else throw e
        }
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (closed.get()) throw ClosedChannelException()
        if (length == 0) return
        runInterruptible(io) {
            var from = offset
            val end = offset + length
            while (from < end) {
                val bytes = ByteBuffer.wrap(buffer, from, minOf(IO_SLICE, end - from))
                while (bytes.hasRemaining()) channel.write(bytes)
                from = bytes.position()
            }
        }
    }

    /** The socket sends as it writes; there is nothing to flush. */
    override suspend fun flush() = Unit

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            channel.close()
        } catch (_: IOException) {
            // already gone
        }
    }

    override fun setLowLatency(enabled: Boolean) {
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, enabled)
        } catch (_: IOException) {
            // closed meanwhile
        }
    }

    override fun toString(): String = "TcpDataChannel($kind, ${remoteAddress ?: "closed"})"

    companion object {
        /** Socket buffer size of §7.4. */
        const val BUFFER_BYTES: Int = 4 * 1024 * 1024

        /** Largest single socket read or write (bounds the JDK's per-thread staging buffers). */
        const val IO_SLICE: Int = 256 * 1024

        /** Applies the socket options of §7.4 to [channel]. */
        fun configure(
            channel: SocketChannel,
            lowLatency: Boolean,
        ) {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER_BYTES)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, BUFFER_BYTES)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, lowLatency)
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
        }

        /**
         * Connects to [host]:[port] within [timeoutMillis] with a socket from [factory] (bound to the link's network on
         * Android). [lowLatency] turns `TCP_NODELAY` on (a control connection).
         *
         * @throws IOException when the connection fails or times out.
         */
        suspend fun connect(
            host: String,
            port: Int,
            kind: LinkKind,
            timeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
            lowLatency: Boolean = false,
            factory: TcpSocketFactory = TcpSocketFactory.DEFAULT,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): TcpDataChannel {
            require(port in 1..65535) { "port $port out of range" }
            val channel = factory.create()
            try {
                channel.configureBlocking(true)
                configure(channel, lowLatency)
                runInterruptible(io) { channel.socket().connect(InetSocketAddress(host, port), timeoutMillis) }
            } catch (e: Throwable) {
                runCatching { channel.close() }
                throw e
            }
            return TcpDataChannel(channel, kind, io)
        }

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 3_000
    }
}

/**
 * A TCP listener for data connections (the link host, §7.4) and for the LAN control connection: accepted sockets get
 * the §7.4 options. [accept] is cancellable. Bind to the link interface's address (never the wildcard on a phone's
 * hotspot) or to loopback in tests.
 */
class TcpListener(
    bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val kind: LinkKind = LinkKind.LAN,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    backlog: Int = 16,
) : AutoCloseable {
    private val server: ServerSocketChannel =
        ServerSocketChannel.open().apply {
            // Set before bind so accepted sockets start with a large window.
            setOption(StandardSocketOptions.SO_RCVBUF, TcpDataChannel.BUFFER_BYTES)
            setOption(StandardSocketOptions.SO_REUSEADDR, true)
            bind(bindAddress, backlog)
        }

    /** The bound address and port (the port announced in `LinkReady`). */
    val address: InetSocketAddress get() = server.localAddress as InetSocketAddress
    val port: Int get() = address.port

    /**
     * Accepts the next connection. [lowLatency] turns `TCP_NODELAY` on at once (a control connection); the engine can
     * also do it later through [TcpDataChannel.setLowLatency].
     *
     * @throws IOException when the listener is closed.
     */
    suspend fun accept(lowLatency: Boolean = false): TcpDataChannel {
        val channel =
            try {
                runInterruptible(io) { server.accept() }
            } catch (e: AsynchronousCloseException) {
                throw SocketException("listener closed").apply { initCause(e) }
            }
        try {
            channel.configureBlocking(true)
            TcpDataChannel.configure(channel, lowLatency)
        } catch (e: IOException) {
            runCatching { channel.close() }
            throw e
        }
        return TcpDataChannel(channel, kind, io)
    }

    override fun close() {
        runCatching { server.close() }
    }
}
