package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The L2CAP and RFCOMM adapter ([SocketStreamChannel]) over in-memory blocking sockets: `DataChannel` semantics. */
class SocketStreamChannelTest {
    private fun channels(capacity: Int = 4096): Triple<SocketStreamChannel, SocketStreamChannel, Pair<PipeSocket, PipeSocket>> {
        val sockets = PipeSocket.pair(capacity)
        return Triple(
            SocketStreamChannel(sockets.first, BluetoothTransport.L2CAP, Dispatchers.IO),
            SocketStreamChannel(sockets.second, BluetoothTransport.L2CAP, Dispatchers.IO),
            sockets,
        )
    }

    private suspend fun readFully(
        channel: SocketStreamChannel,
        size: Int,
        chunk: Int = 8192,
    ): ByteArray {
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = channel.read(out, at, minOf(chunk, size - at))
            check(n > 0) { "end of stream after $at bytes" }
            at += n
        }
        return out
    }

    @Test
    fun movesBytesBothWaysAtOnceWithBackpressure() =
        runBlocking {
            val (a, b) = channels(capacity = 1000)
            val ab = Random(1).nextBytes(300_000)
            val ba = Random(2).nextBytes(200_000)
            withTimeout(20_000) {
                val writeA = launch { for (i in ab.indices step 7_000) a.write(ab, i, minOf(7_000, ab.size - i)) }
                val writeB = launch { b.write(ba) }
                val readB = async { readFully(b, ab.size, chunk = 333) }
                val readA = async { readFully(a, ba.size) }
                assertContentEquals(ab, readB.await())
                assertContentEquals(ba, readA.await())
                writeA.join()
                writeB.join()
            }
            assertEquals(LinkKind.BLUETOOTH, a.kind)
            assertEquals(BluetoothTransport.L2CAP, a.transport)
            assertEquals("11:22:33:44:55:66", a.remoteAddress)
            a.close()
            b.close()
        }

    @Test
    fun readReturnsMinusOneAtThePeersCloseAndAfterOurOwn() =
        runBlocking {
            val (a, b) = channels()
            withTimeout(5_000) {
                a.write(byteArrayOf(1, 2, 3))
                a.close()
                val buffer = ByteArray(10)
                assertEquals(3, b.read(buffer))
                assertEquals(-1, b.read(buffer))
                b.close()
                assertEquals(-1, b.read(buffer))
                assertEquals(0, b.read(buffer, 0, 0))
            }
        }

    @Test
    fun writesAfterCloseFailAndCloseIsIdempotent() =
        runBlocking {
            val (a, b, sockets) = channels()
            a.close()
            a.close()
            assertTrue(a.isClosed)
            assertEquals(1, sockets.first.closes)
            assertFailsWith<IOException> { a.write(byteArrayOf(1)) }
            // Writing to a peer that has gone fails too.
            assertFailsWith<IOException> { withTimeout(5_000) { b.write(ByteArray(10_000)) } }
            a.flush()
            b.close()
        }

    @Test
    fun closeUnblocksABlockedReadWhichThenReturnsEndOfStream() =
        runBlocking {
            val (a, b) = channels()
            withTimeout(5_000) {
                val reading = async { a.read(ByteArray(8)) }
                delay(100)
                a.close()
                assertEquals(-1, reading.await())
            }
            b.close()
        }

    @Test
    fun cancellingABlockedReadClosesTheChannel() =
        runBlocking {
            val (a, b, sockets) = channels()
            withTimeout(5_000) {
                val reading = launch { a.read(ByteArray(8)) }
                delay(100)
                reading.cancelAndJoin()
            }
            assertTrue(a.isClosed)
            assertEquals(1, sockets.first.closes)
            // The peer sees the end of the stream.
            assertEquals(-1, withTimeout(5_000) { b.read(ByteArray(8)) })
        }

    @Test
    fun cancellingAWriteBlockedOnBackpressureClosesTheChannel() =
        runBlocking {
            val (a, b) = channels(capacity = 100)
            withTimeout(5_000) {
                val writing = launch { a.write(ByteArray(10_000)) }
                delay(100)
                writing.cancelAndJoin()
            }
            assertTrue(a.isClosed)
            b.close()
        }

    /** A socket whose every read fails with [message] and every write with "broken pipe". */
    private fun failing(message: String) =
        object : StreamSocket {
            override val input: InputStream =
                object : InputStream() {
                    override fun read(): Int = throw IOException(message)
                }
            override val output: OutputStream =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("broken pipe")
                }
            override val remoteAddress: String? = null

            override fun close() = Unit
        }

    @Test
    fun anIoErrorFromTheStackIsRethrownWhileOpen() =
        runBlocking {
            val channel = SocketStreamChannel(failing("Software caused connection abort"), BluetoothTransport.RFCOMM, Dispatchers.IO)
            assertFailsWith<IOException> { channel.read(ByteArray(4)) }
            assertFailsWith<IOException> { channel.write(ByteArray(4)) }
            channel.close()
            assertEquals(-1, channel.read(ByteArray(4)))
            // Only RFCOMM reports its end of stream as an exception: on L2CAP the same text is an error.
            val l2cap = SocketStreamChannel(failing("bt socket closed, read return: -1"), BluetoothTransport.L2CAP, Dispatchers.IO)
            assertFailsWith<IOException> { l2cap.read(ByteArray(4)) }
        }

    @Test
    fun rfcommReportsThePeersCloseAsEndOfStreamToo() =
        runBlocking {
            // An RFCOMM BluetoothSocket throws "bt socket closed, read return: -1" where L2CAP returns -1.
            val (x, y) = PipeSocket.pair(rfcomm = true)
            val a = SocketStreamChannel(x, BluetoothTransport.RFCOMM, Dispatchers.IO)
            val b = SocketStreamChannel(y, BluetoothTransport.RFCOMM, Dispatchers.IO)
            withTimeout(5_000) {
                a.write(byteArrayOf(1, 2, 3))
                a.close()
                val buffer = ByteArray(10)
                assertEquals(3, b.read(buffer))
                assertEquals(-1, b.read(buffer))
                assertEquals(-1, b.read(buffer))
            }
            assertFalse(b.isClosed, "the end of the stream is not a local close")
            b.close()
            assertTrue(SocketStreamChannel.isRfcommEndOfStream(IOException("bt socket closed, read return: -1")))
            assertFalse(SocketStreamChannel.isRfcommEndOfStream(IOException("Software caused connection abort")))
            assertFalse(SocketStreamChannel.isRfcommEndOfStream(IOException()))
        }

    @Test
    fun aCancelledReadReturnsOnlyOnceTheBlockedCallHasEnded() =
        runBlocking {
            // Closing the socket unblocks the read only after a while, and the read still writes into the buffer: that
            // must happen before the cancelled read returns, since the engine hands its buffers back to a pool.
            val closed = CountDownLatch(1)
            val socket =
                object : StreamSocket {
                    override val input: InputStream =
                        object : InputStream() {
                            override fun read(): Int = throw UnsupportedOperationException()

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                closed.await(5, TimeUnit.SECONDS)
                                Thread.sleep(200)
                                b.fill(7, off, off + len)
                                throw IOException("socket closed")
                            }
                        }
                    override val output: OutputStream =
                        object : OutputStream() {
                            override fun write(b: Int) = Unit
                        }
                    override val remoteAddress: String? = null

                    override fun close() {
                        closed.countDown()
                    }
                }
            val channel = SocketStreamChannel(socket, BluetoothTransport.L2CAP, Dispatchers.IO)
            val buffer = ByteArray(16)
            withTimeout(5_000) {
                val reading = launch { channel.read(buffer) }
                delay(100)
                reading.cancelAndJoin()
            }
            val atReturn = buffer.copyOf()
            delay(500)
            assertContentEquals(atReturn, buffer, "nothing is written into the buffer after the cancelled read returned")
            assertTrue(atReturn.all { it == 7.toByte() }, "the blocked read had ended before the cancelled read returned")
            assertTrue(channel.isClosed)
        }

    @Test
    fun aResultThatArrivesAfterTheCancellationIsDiscardedNotLost() =
        runBlocking {
            // accept() returns a connection just as the accept loop is cancelled: it must be closed, not leak.
            val cancelled = CountDownLatch(1)
            val discarded = CompletableDeferred<String>()
            val blocking = CancellableBlocking(Dispatchers.IO.asExecutor()) { cancelled.countDown() }
            withTimeout(5_000) {
                val accepting =
                    launch {
                        blocking.call<String>(discard = { discarded.complete(it) }) {
                            cancelled.await(5, TimeUnit.SECONDS)
                            "accepted socket"
                        }
                    }
                delay(100)
                accepting.cancelAndJoin()
                assertTrue(discarded.isCompleted, "discarded before the cancelled call returned")
                assertEquals("accepted socket", discarded.await())
            }
        }

    @Test
    fun rangesAreChecked() =
        runBlocking {
            val (a, b) = channels()
            assertFailsWith<IllegalArgumentException> { a.read(ByteArray(4), 3, 2) }
            assertFailsWith<IllegalArgumentException> { a.write(ByteArray(4), -1, 1) }
            a.write(ByteArray(4), 4, 0)
            a.close()
            b.close()
        }

    @Test
    fun connectTimesOutAndClosesTheSocket() =
        runBlocking {
            val (pipe, _) = PipeSocket.pair()
            val block = CountDownLatch(1)
            // A connect the stack never answers; closing the socket is what aborts it, on a device as here.
            val socket =
                object : StreamSocket by pipe {
                    override fun close() {
                        block.countDown()
                        pipe.close()
                    }
                }
            val started = System.nanoTime()
            val e =
                assertFailsWith<IOException> {
                    SocketStreamChannel.connect(socket, BluetoothTransport.L2CAP, timeoutMillis = 200, io = Dispatchers.IO) {
                        block.await(5, TimeUnit.SECONDS)
                    }
                }
            assertTrue(e.message!!.contains("timed out"))
            assertTrue(pipe.closes >= 1)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "the close aborted the connect")
        }

    @Test
    fun aFailedConnectClosesTheSocketAndAGoodOneReturnsTheChannel() =
        runBlocking {
            val (socket, peer) = PipeSocket.pair()
            assertFailsWith<IOException> {
                SocketStreamChannel.connect(socket, BluetoothTransport.RFCOMM, 1_000, Dispatchers.IO) {
                    throw IOException("SDP lookup failed")
                }
            }
            assertEquals(1, socket.closes)
            val (good, other) = PipeSocket.pair()
            val connected = CompletableDeferred<Unit>()
            val channel = SocketStreamChannel.connect(good, BluetoothTransport.RFCOMM, 1_000, Dispatchers.IO) { connected.complete(Unit) }
            assertTrue(connected.isCompleted)
            assertEquals(BluetoothTransport.RFCOMM, channel.transport)
            channel.write(byteArrayOf(42))
            val received = ByteArray(1)
            val peerChannel = SocketStreamChannel(other, BluetoothTransport.RFCOMM, Dispatchers.IO)
            assertEquals(1, withTimeout(5_000) { peerChannel.read(received) })
            assertEquals(42, received[0].toInt())
            channel.close()
            peerChannel.close()
            peer.close()
        }

    @Test
    fun theSessionHandshakeAndATransferRunOverIt() =
        runBlocking {
            val (a, b) = channels(capacity = 64 * 1024)
            EngineOverBluetooth.transfer(
                a to b,
                listOf(
                    "photo.jpg" to EngineOverBluetooth.randomBytes(700_000, 3),
                    "note.txt" to EngineOverBluetooth.randomBytes(1_234, 4),
                    "empty" to ByteArray(0),
                ),
            )
        }
}
