package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun anIoErrorFromTheStackIsRethrownWhileOpen() =
        runBlocking {
            val failing =
                object : StreamSocket {
                    override val input: InputStream =
                        object : InputStream() {
                            override fun read(): Int = throw IOException("bt socket closed, read return: -1")
                        }
                    override val output: OutputStream =
                        object : OutputStream() {
                            override fun write(b: Int) = throw IOException("broken pipe")
                        }
                    override val remoteAddress: String? = null

                    override fun close() = Unit
                }
            val channel = SocketStreamChannel(failing, BluetoothTransport.RFCOMM, Dispatchers.IO)
            assertFailsWith<IOException> { channel.read(ByteArray(4)) }
            assertFailsWith<IOException> { channel.write(ByteArray(4)) }
            channel.close()
            assertEquals(-1, channel.read(ByteArray(4)))
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
            val (socket, _) = PipeSocket.pair()
            val block = CountDownLatch(1)
            val e =
                assertFailsWith<IOException> {
                    SocketStreamChannel.connect(socket, BluetoothTransport.L2CAP, timeoutMillis = 200, io = Dispatchers.IO) {
                        // A connect the stack never answers; closing the socket is what unblocks it on a device.
                        block.await(5, TimeUnit.SECONDS)
                    }
                }
            block.countDown()
            assertTrue(e.message!!.contains("timed out"))
            assertTrue(socket.closes >= 1)
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
