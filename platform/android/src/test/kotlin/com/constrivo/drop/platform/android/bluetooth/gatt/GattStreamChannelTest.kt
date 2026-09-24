package com.constrivo.drop.platform.android.bluetooth.gatt

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.platform.android.bluetooth.BluetoothTransport
import com.constrivo.drop.platform.android.bluetooth.EngineOverBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The GATT stream (architecture §6.1 note, WP7b) over an in-memory ATT link: segmentation, credits, ordering, duplicate
 * and loss detection, close and reset, and `DataChannel` semantics. Deterministic: everything runs on the test
 * scheduler.
 */
class GattStreamChannelTest {
    private val small =
        GattStreamConfig(
            initialCredits = 8,
            creditReturnThreshold = 2,
            openTimeoutMillis = 1_000,
            closeLingerMillis = 200,
            writeBatchSegments = 4,
        )

    private fun TestScope.pair(
        maxSegment: Int = 509,
        clientConfig: GattStreamConfig = GattStreamConfig(),
        serverConfig: GattStreamConfig = GattStreamConfig(),
        serverMaxSegment: Int = maxSegment,
    ): GattLinkPair {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Delivery runs as foreground work, so advanceUntilIdle() waits for segments in flight; the loops only ever
        // suspend on their queues, so they need no cancellation.
        return GattLinkPair(CoroutineScope(dispatcher), dispatcher, maxSegment, clientConfig, serverConfig, serverMaxSegment).start()
    }

    private suspend fun GattLinkPair.open(): Pair<GattStreamChannel, GattStreamChannel> {
        client.open()
        return client to server!!
    }

    private suspend fun readFully(
        channel: GattStreamChannel,
        size: Int,
        chunk: Int = 1000,
    ): ByteArray {
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = channel.read(out, at, minOf(chunk, size - at))
            check(n > 0) { "end of stream after $at of $size bytes" }
            at += n
        }
        return out
    }

    @Test
    fun opensWithTheSmallerSegmentSize() =
        runTest {
            val link = pair(maxSegment = 509, serverMaxSegment = 100)
            val (client, server) = link.open()
            assertTrue(client.isOpen && server.isOpen)
            assertEquals(listOf(GattSegments.TYPE_OPEN), link.clientLink.sentTypes().take(1))
            assertEquals(GattSegments.TYPE_OPEN_ACK, link.serverLink.sentTypes().first())
            client.write(ByteArray(500))
            advanceUntilIdle()
            // 500 bytes at 100 − 3 per segment.
            assertEquals(6, link.clientLink.sentTypes().count { it == GattSegments.TYPE_DATA })
            assertTrue(link.clientLink.sent.filter { it[0].toInt() == GattSegments.TYPE_DATA }.all { it.size <= 100 })
            assertContentEquals(ByteArray(500), readFully(server, 500))
            assertEquals(LinkKind.BLUETOOTH, client.kind)
            assertEquals(BluetoothTransport.GATT, server.transport)
            assertEquals("client", client.remoteAddress)
        }

    @Test
    fun carriesLargeStreamsBothWaysAtOnceInOrder() =
        runTest {
            val link = pair(maxSegment = 20)
            val (client, server) = link.open()
            val up = Random(1).nextBytes(60_000)
            val down = Random(2).nextBytes(45_000)
            val writeUp = launch { for (i in up.indices step 999) client.write(up, i, minOf(999, up.size - i)) }
            val writeDown = launch { server.write(down) }
            val readUp = async { readFully(server, up.size, chunk = 77) }
            val readDown = async { readFully(client, down.size, chunk = 4096) }
            assertContentEquals(up, readUp.await())
            assertContentEquals(down, readDown.await())
            writeUp.join()
            writeDown.join()
            // Credits flowed back in both directions.
            assertTrue(link.clientLink.sentTypes().count { it == GattSegments.TYPE_CREDIT } > 0)
            assertTrue(link.serverLink.sentTypes().count { it == GattSegments.TYPE_CREDIT } > 0)
        }

    @Test
    fun aWriterWaitsForCreditsSoTheReceiverBufferStaysBounded() =
        runTest {
            val link = pair(clientConfig = small, serverConfig = small)
            val (client, server) = link.open()
            val data = Random(3).nextBytes(20 * 506)
            val writing = launch { client.write(data) }
            advanceUntilIdle()
            // The server granted 8 credits and nobody reads: exactly 8 data segments went out and the write waits.
            assertEquals(8, link.clientLink.sentTypes().count { it == GattSegments.TYPE_DATA })
            assertFalse(writing.isCompleted)
            assertContentEquals(data, readFully(server, data.size))
            writing.join()
            assertEquals(20, link.clientLink.sentTypes().count { it == GattSegments.TYPE_DATA })
        }

    @Test
    fun theFrameLevelHandshakeAndATransferRunOverIt() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val link = GattLinkPair(scope, Dispatchers.Default, maxSegment = 244).start()
                withTimeout(30_000) { link.client.open() }
                EngineOverBluetooth.transfer(
                    link.client to link.server!!,
                    listOf(
                        "voice.m4a" to EngineOverBluetooth.randomBytes(150_000, 5),
                        "tiny.txt" to EngineOverBluetooth.randomBytes(10, 6),
                    ),
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun closeDeliversEverythingThenEndOfStream() =
        runTest {
            val link = pair()
            val (client, server) = link.open()
            client.write("hello".encodeToByteArray())
            client.close()
            client.close()
            assertEquals("hello", readFully(server, 5).decodeToString())
            assertEquals(-1, server.read(ByteArray(10)))
            assertEquals(-1, server.read(ByteArray(10)))
            assertEquals(-1, client.read(ByteArray(10)))
            assertFailsWith<IOException> { client.write(byteArrayOf(1)) }
            // Writes toward a peer that closed fail; our own close then just disconnects.
            assertFailsWith<GattStreamException> { server.write(byteArrayOf(1)) }
            server.close()
            advanceUntilIdle()
            assertTrue(link.clientLink.disconnects.get() >= 1)
            assertEquals(GattSegments.TYPE_CLOSE, link.clientLink.sentTypes().last())
        }

    @Test
    fun closeFailsAWriteThatWaitsForCredits() =
        runTest {
            val link = pair(clientConfig = small, serverConfig = small)
            val (client, _) = link.open()
            val writing = async { runCatching { client.write(ByteArray(50_000)) } }
            advanceUntilIdle()
            assertFalse(writing.isCompleted)
            client.close()
            assertIs<IOException>(writing.await().exceptionOrNull())
        }

    @Test
    fun aDuplicatedSegmentIsDropped() =
        runTest {
            val link = pair(maxSegment = 20)
            link.clientLink.fault = { index, _ -> if (index == 3 || index == 7) Fault.DUPLICATE else Fault.NONE }
            val (client, server) = link.open()
            val data = Random(4).nextBytes(400)
            client.write(data)
            assertContentEquals(data, readFully(server, data.size))
            advanceUntilIdle()
            assertEquals(2L, server.duplicateSegments)
            assertTrue(server.isOpen)
        }

    @Test
    fun aLostSegmentBreaksTheStreamAndResetsThePeer() =
        runTest {
            val link = pair(maxSegment = 20)
            link.clientLink.fault =
                { index, segment -> if (index == 4 && segment[0].toInt() == GattSegments.TYPE_DATA) Fault.DROP else Fault.NONE }
            val (client, server) = link.open()
            // The write may itself fail: the server resets and disconnects while segments are still going out.
            runCatching { client.write(ByteArray(300)) }
            advanceUntilIdle()
            val e = assertFailsWith<GattStreamException> { readFully(server, 300) }
            assertTrue(e.message!!.contains("lost"), e.message)
            // The server told the client with RESET(protocol); the client fails too.
            assertEquals(GattSegments.TYPE_RESET, link.serverLink.sentTypes().last())
            assertFailsWith<GattStreamException> { client.read(ByteArray(1)) }
            assertFailsWith<IOException> { client.write(byteArrayOf(1)) }
        }

    @Test
    fun aLostLinkDeliversWhatArrivedThenFails() =
        runTest {
            val link = pair()
            val (client, server) = link.open()
            client.write(byteArrayOf(1, 2, 3))
            advanceUntilIdle()
            link.linkDown()
            advanceUntilIdle()
            val buffer = ByteArray(10)
            assertEquals(3, server.read(buffer))
            assertFailsWith<GattStreamException> { server.read(buffer) }
            assertFailsWith<IOException> { client.write(byteArrayOf(4)) }
        }

    @Test
    fun aFailingGattWriteFailsTheWriter() =
        runTest {
            val link = pair()
            val (client, _) = link.open()
            link.clientLink.fault = { _, _ -> Fault.FAIL }
            assertFailsWith<IOException> { client.write(byteArrayOf(1)) }
            assertFailsWith<IOException> { client.read(ByteArray(1)) }
        }

    @Test
    fun openTimesOutWhenNoServerAnswers() =
        runTest {
            val link = pair()
            link.clientLink.fault = { _, _ -> Fault.DROP }
            val e = assertFailsWith<GattStreamException> { link.client.open() }
            assertTrue(e.message!!.contains("OPEN_ACK"))
            assertFailsWith<IOException> { link.client.write(byteArrayOf(1)) }
        }

    @Test
    fun cancellingAWriteResetsTheStream() =
        runTest {
            val link = pair(clientConfig = small, serverConfig = small)
            val (client, server) = link.open()
            val writing = launch { client.write(ByteArray(50_000)) }
            advanceUntilIdle()
            writing.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(GattSegments.TYPE_RESET, link.clientLink.sentTypes().last())
            assertFailsWith<GattStreamException> { server.read(ByteArray(1)) }
        }

    @Test
    fun cancellingAReadLosesNothing() =
        runTest {
            val link = pair()
            val (client, server) = link.open()
            val reading = launch { server.read(ByteArray(4)) }
            runCurrent()
            reading.cancelAndJoin()
            client.write(byteArrayOf(9, 8, 7))
            assertContentEquals(byteArrayOf(9, 8, 7), readFully(server, 3))
        }

    private class Rogue : GattSegmentTransport {
        override val maxSegmentSize: Int = 509
        val sent = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun send(segment: ByteArray) {
            sent.send(segment)
        }

        override fun disconnect() = Unit
    }

    @Test
    fun aPeerThatIgnoresCreditsIsCutOff() =
        runTest {
            val rogue = Rogue()
            val server = GattStreamChannel.server(rogue, StandardTestDispatcher(testScheduler), small)
            server.accept(GattSegments.encode(GattSegment.Open(0, 1, 8, 509)))
            advanceUntilIdle()
            assertIs<GattSegment.OpenAck>(GattSegments.decode(rogue.sent.receive()))
            // Eight credits granted; the ninth data segment is one too many.
            for (seq in 1..9) server.onSegment(GattSegments.encode(GattSegment.Data(seq, byteArrayOf(seq.toByte()))))
            advanceUntilIdle()
            val reset = assertIs<GattSegment.Reset>(GattSegments.decode(rogue.sent.receive()))
            assertEquals(GattSegments.RESET_OVERFLOW, reset.reason)
            assertFailsWith<GattStreamException> { server.read(ByteArray(64)) }
        }

    @Test
    fun protocolViolationsResetTheStream() =
        runTest {
            val cases =
                listOf(
                    // A second OPEN once the stream is open.
                    GattSegments.encode(GattSegment.Open(1, 1, 8, 509)),
                    // Garbage.
                    byteArrayOf(0x7F, 0, 1),
                    // Data after the peer's own CLOSE.
                    null,
                )
            for (case in cases) {
                val rogue = Rogue()
                val server = GattStreamChannel.server(rogue, StandardTestDispatcher(testScheduler), small)
                server.accept(GattSegments.encode(GattSegment.Open(0, 1, 8, 509)))
                advanceUntilIdle()
                rogue.sent.receive()
                if (case == null) {
                    server.onSegment(GattSegments.encode(GattSegment.Close(1)))
                    server.onSegment(GattSegments.encode(GattSegment.Data(2, byteArrayOf(1))))
                } else {
                    server.onSegment(case)
                }
                advanceUntilIdle()
                val reset = assertIs<GattSegment.Reset>(GattSegments.decode(rogue.sent.receive()))
                assertEquals(GattSegments.RESET_PROTOCOL, reset.reason)
            }
        }

    @Test
    fun theServerAnswersANewerClientWithTheCommonVersion() =
        runTest {
            val rogue = Rogue()
            val server = GattStreamChannel.server(rogue, StandardTestDispatcher(testScheduler), small)
            server.accept(GattSegments.encode(GattSegment.Open(0, 7, 8, 509)))
            advanceUntilIdle()
            val ack = assertIs<GattSegment.OpenAck>(GattSegments.decode(rogue.sent.receive()))
            assertEquals(1, ack.version)
            assertEquals(8, ack.credits)
        }

    @Test
    fun aClientRefusesAServerVersionItDoesNotSpeak() =
        runTest {
            val rogue = Rogue()
            val client = GattStreamChannel.client(rogue, StandardTestDispatcher(testScheduler), small)
            val opening = async { runCatching { client.open() } }
            runCurrent()
            assertIs<GattSegment.Open>(GattSegments.decode(rogue.sent.receive()))
            client.onSegment(GattSegments.encode(GattSegment.OpenAck(0, 2, 8, 509)))
            assertIs<GattStreamException>(opening.await().exceptionOrNull())
            advanceUntilIdle()
            val reset = assertIs<GattSegment.Reset>(GattSegments.decode(rogue.sent.receive()))
            assertEquals(GattSegments.RESET_UNSUPPORTED_VERSION, reset.reason)
        }

    @Test
    fun aPeerResetFailsReadsAtOnce() =
        runTest {
            val rogue = Rogue()
            val server = GattStreamChannel.server(rogue, StandardTestDispatcher(testScheduler), small)
            server.accept(GattSegments.encode(GattSegment.Open(0, 1, 8, 509)))
            server.onSegment(GattSegments.encode(GattSegment.Data(1, byteArrayOf(1, 2))))
            server.onSegment(GattSegments.encode(GattSegment.Reset(2, GattSegments.RESET_CANCELLED)))
            advanceUntilIdle()
            // Buffered data is discarded after a reset, unlike after a lost link.
            val e = assertFailsWith<GattStreamException> { server.read(ByteArray(8)) }
            assertTrue(e.message!!.contains("reset"))
        }

    @Test
    fun rangesAreChecked() =
        runTest {
            val (client, _) = pair().open()
            assertFailsWith<IllegalArgumentException> { client.read(ByteArray(4), 2, 3) }
            assertFailsWith<IllegalArgumentException> { client.write(ByteArray(4), 5, 0) }
            assertEquals(0, client.read(ByteArray(4), 4, 0))
            client.write(ByteArray(4), 4, 0)
            client.flush()
        }
}
