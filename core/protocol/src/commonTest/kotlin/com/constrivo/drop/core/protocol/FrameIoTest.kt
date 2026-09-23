package com.constrivo.drop.core.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FrameReader / FrameWriter over DataChannel: exact reads, partial reads, EOF, limits, concurrency. */
class FrameIoTest {
    /** A DataChannel over a fixed byte array, recording how much each read asked for. */
    private class ArraySource(
        private val bytes: ByteArray,
        private val maxRead: Int = Int.MAX_VALUE,
    ) : DataChannel {
        var position = 0
        val requests = ArrayList<Int>()
        override val kind = LinkKind.BLUETOOTH

        override suspend fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            requests += length
            if (position >= bytes.size) return -1
            val n = minOf(length, maxRead, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }

        override suspend fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) = error("read-only")

        override suspend fun flush() {}

        override suspend fun close() {}
    }

    private fun frames(random: Random): List<Frame> =
        List(40) { i ->
            val type = FrameType.entries[i % FrameType.entries.size]
            val size =
                when (i % 5) {
                    0 -> 0
                    1 -> 1
                    2 -> random.nextInt(2, 200)
                    3 -> minOf(type.maxPayload, random.nextInt(60_000, 70_000))
                    else -> minOf(type.maxPayload, random.nextInt(200, 4_000))
                }
            Frame(type, random.nextBytes(size))
        }

    @Test
    fun roundTripsOverEveryReadChunking() =
        runTest {
            val chunkings =
                listOf(
                    ReadChunking.UNLIMITED,
                    ReadChunking.fixed(1),
                    ReadChunking.fixed(3),
                    ReadChunking.cycle(1, 5, 4096, 2),
                    ReadChunking.random(seed = 42, max = 9000),
                )
            for (chunking in chunkings) {
                val sent = frames(Random(1))
                val (a, b) = InMemoryDataChannel.pair(secondChunking = chunking, maxSegment = 777)
                val writer = FrameWriter(a)
                val reader = FrameReader(b, bufferSize = 1024)
                coroutineScope {
                    launch {
                        for ((i, frame) in sent.withIndex()) writer.writeFrame(frame, flush = i % 3 == 0)
                        writer.flush()
                        a.close()
                    }
                    val received = ArrayList<Frame>()
                    while (true) received += reader.readFrame() ?: break
                    assertEquals(sent, received)
                }
                assertEquals(writer.bytesWritten, reader.bytesRead)
            }
        }

    @Test
    fun readsAFullSizeChunkFrame() =
        runTest {
            val payload = Random(3).nextBytes(ProtocolConstants.MAX_FRAME_PAYLOAD)
            val (a, b) = InMemoryDataChannel.pair(secondChunking = ReadChunking.random(7, 100_000))
            coroutineScope {
                launch {
                    FrameWriter(a).writeFrame(FrameType.CHUNK, payload)
                    a.close()
                }
                val reader = FrameReader(b)
                val frame = reader.readFrame()!!
                assertEquals(FrameType.CHUNK, frame.type)
                assertContentEquals(payload, frame.payload)
                assertNull(reader.readFrame())
            }
        }

    @Test
    fun cleanEndOfStreamReturnsNull() =
        runTest {
            val reader = FrameReader(ArraySource(ByteArray(0)))
            assertNull(reader.readFrame())
            assertNull(reader.readFrame())
            val one = FrameReader(ArraySource(FrameCodec.encode(FrameType.HELLO, "01".unhex())))
            assertEquals(Frame(FrameType.HELLO, "01".unhex()), one.readFrame())
            assertNull(one.readFrame())
        }

    @Test
    fun endOfStreamInsideAFrameIsTruncation() =
        runTest {
            val frame = FrameCodec.encode(FrameType.CONTROL, ByteArray(100) { 1 })
            for (cut in listOf(1, 4, 5, 6, 104)) {
                val reader = FrameReader(ArraySource(frame.copyOf(cut), maxRead = 7))
                assertIs<TruncatedFrameException>(assertFailsWith<ProtocolException> { reader.readFrame() }, "cut at $cut")
            }
        }

    @Test
    fun headerIsCheckedBeforeThePayloadIsRead() =
        runTest {
            // A 4 MiB chunk header during the handshake: rejected after reading 5 bytes, nothing allocated for the payload.
            val source = ArraySource(FrameCodec.encodeHeader(FrameType.CHUNK, ProtocolConstants.CHUNK_SIZE) + ByteArray(10), maxRead = 5)
            val reader = FrameReader(source, bufferSize = 16, limits = FrameLimits.HANDSHAKE)
            assertProtocolError { reader.readFrame() }
            assertEquals(5, source.position)
            // Unknown type and oversized length are rejected the same way.
            assertProtocolError { FrameReader(ArraySource("0000000099".unhex())).readFrame() }
            assertProtocolError { FrameReader(ArraySource("ffffffff11".unhex())).readFrame() }
        }

    @Test
    fun limitsCanChangeBetweenFrames() =
        runTest {
            val bytes = FrameCodec.encode(FrameType.HELLO, "01".unhex()) + FrameCodec.encode(FrameType.CONTROL, "02".unhex())
            val reader = FrameReader(ArraySource(bytes), limits = FrameLimits.HANDSHAKE)
            assertEquals(FrameType.HELLO, reader.readFrame()!!.type)
            assertProtocolError { FrameReader(ArraySource(bytes.copyOfRange(6, bytes.size)), limits = FrameLimits.HANDSHAKE).readFrame() }
            reader.limits = FrameLimits.SESSION
            assertEquals(Frame(FrameType.CONTROL, "02".unhex()), reader.readFrame())
            assertNull(reader.readFrame(FrameLimits.SESSION))
        }

    @Test
    fun largePayloadsAreReadStraightIntoTheirArray() =
        runTest {
            val payload = ByteArray(10_000) { it.toByte() }
            val source = ArraySource(FrameCodec.encode(FrameType.CHUNK, payload))
            val frame = FrameReader(source, bufferSize = 64).readFrame()!!
            assertContentEquals(payload, frame.payload)
            assertTrue(source.requests.any { it > 64 }, "the reader asked the channel for more than its buffer")
        }

    @Test
    fun concurrentWritersNeverInterleaveFrames() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(maxSegment = 100, secondChunking = ReadChunking.fixed(37))
            val writer = FrameWriter(a, bufferSize = 256)
            val payloads =
                List(8) { w ->
                    List(25) { i ->
                        ByteArray(2 + (w * 31 + i * 17) % 900) { k ->
                            when (k) {
                                0 -> w.toByte()
                                1 -> i.toByte()
                                else -> k.toByte()
                            }
                        }
                    }
                }
            coroutineScope {
                val writers =
                    payloads.map { list ->
                        async { for ((i, p) in list.withIndex()) writer.writeFrame(FrameType.CONTROL, p, flush = i % 2 == 0) }
                    }
                launch {
                    writers.awaitAll()
                    writer.flush()
                    a.close()
                }
                val reader = FrameReader(b)
                val received = ArrayList<ByteArray>()
                while (true) received += (reader.readFrame() ?: break).payload
                assertEquals(payloads.sumOf { it.size }, received.size)
                val expected = payloads.flatten().map { it.hex() }.sorted()
                assertEquals(expected, received.map { it.hex() }.sorted())
                // Each writer's frames arrive in its own order.
                for (list in payloads) {
                    val mine = received.filter { r -> list.any { it.contentEquals(r) } }
                    assertEquals(list.map { it.hex() }, mine.map { it.hex() })
                }
            }
        }

    @Test
    fun unflushedFramesAreCoalescedUntilFlush() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair()
            val writer = FrameWriter(a)
            writer.writeFrame(FrameType.CONTROL, "01".unhex(), flush = false)
            writer.writeFrame(FrameType.CONTROL, "02".unhex(), flush = false)
            assertEquals(0, a.bytesWritten)
            assertEquals(0, a.flushCount)
            writer.flush()
            assertEquals(12, a.bytesWritten)
            assertEquals(1, a.flushCount)
            val reader = FrameReader(b)
            assertEquals("01", reader.readFrame()!!.payload.hex())
            assertEquals("02", reader.readFrame()!!.payload.hex())
        }

    @Test
    fun writerRejectsOversizedPayloadsAndBreaksAfterAFailedWrite() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair()
            val writer = FrameWriter(a)
            assertProtocolError { writer.writeFrame(FrameType.HELLO, ByteArray(ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD + 1)) }
            b.close()
            assertFailsWith<IllegalStateException> { writer.writeFrame(FrameType.CONTROL, ByteArray(10)) }
            // Once broken, every later call fails fast.
            assertFailsWith<IllegalStateException> { writer.flush() }
            assertFailsWith<IllegalStateException> { writer.writeFrame(FrameType.CONTROL, ByteArray(1)) }
        }

    @Test
    fun misbehavingChannelsAreDetected() =
        runTest {
            val zeroes =
                object : DataChannel {
                    override val kind = LinkKind.LAN

                    override suspend fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = 0

                    override suspend fun write(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {}

                    override suspend fun flush() {}

                    override suspend fun close() {}
                }
            assertFailsWith<IllegalStateException> { FrameReader(zeroes).readFrame() }
            assertFailsWith<IllegalArgumentException> { FrameReader(zeroes, bufferSize = 4) }
        }
}
