package com.constrivo.drop.core.protocol

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A stand-in for the real frame cipher: a keyed keystream XOR plus an 8-byte FNV-1a tag over the associated data,
 * the counter and the ciphertext. Not secure; it behaves like an AEAD for the protocol plumbing (per-stream
 * counters, tag failure on any change to length, type, stream or content) and records every nonce it seals with.
 */
internal class ToyProtector(
    private val sendKey: Long,
    private val receiveKey: Long,
    private val sealedNonces: MutableList<Triple<Long, Int, Long>> = ArrayList(),
) : FrameProtector {
    override val overhead: Int = 8
    private val sendCounters = HashMap<Int, Long>()
    private val receiveCounters = HashMap<Int, Long>()

    override fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val counter = sendCounters[streamId] ?: 0L
        sendCounters[streamId] = counter + 1
        sealedNonces += Triple(sendKey, streamId, counter)
        val ciphertext = xor(sendKey, streamId, counter, plaintext)
        val tag = tag(sendKey, FrameAad.of(type, streamId, plaintext.size + overhead), counter, ciphertext)
        return ciphertext + tag
    }

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
    ): ByteArray {
        if (sealed.size < overhead) throw ProtocolException("sealed frame shorter than its tag")
        val counter = receiveCounters[streamId] ?: 0L
        val ciphertext = sealed.copyOf(sealed.size - overhead)
        val expected = tag(receiveKey, FrameAad.of(type, streamId, sealed.size), counter, ciphertext)
        if (!expected.contentEquals(
                sealed.copyOfRange(sealed.size - overhead, sealed.size),
            )
        ) {
            throw ProtocolException("authentication failed")
        }
        receiveCounters[streamId] = counter + 1
        return xor(receiveKey, streamId, counter, ciphertext)
    }

    private fun xor(
        key: Long,
        streamId: Int,
        counter: Long,
        data: ByteArray,
    ): ByteArray {
        var state = key xor (streamId.toLong() shl 40) xor counter
        return ByteArray(data.size) { i ->
            state = state * 6364136223846793005L + 1442695040888963407L
            (data[i].toInt() xor (state ushr 56).toInt()).toByte()
        }
    }

    private fun tag(
        key: Long,
        aad: ByteArray,
        counter: Long,
        ciphertext: ByteArray,
    ): ByteArray {
        var h = -0x340d631b7bdddcdbL xor key

        fun mix(b: Int) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
        }
        aad.forEach { mix(it.toInt()) }
        for (i in 0 until 8) mix((counter ushr (8 * i)).toInt())
        ciphertext.forEach { mix(it.toInt()) }
        return ByteArray(8) { (h ushr (8 * it)).toByte() }
    }
}

class FrameProtectionTest {
    private fun pairOfProtectors(nonces: MutableList<Triple<Long, Int, Long>> = ArrayList()) =
        ToyProtector(sendKey = 0xA11CE, receiveKey = 0xB0B, sealedNonces = nonces) to
            ToyProtector(sendKey = 0xB0B, receiveKey = 0xA11CE, sealedNonces = nonces)

    @Test
    fun controlAndChunkFramesRoundTrip() =
        runTest {
            val (alice, bob) = pairOfProtectors()
            val (a, b) = InMemoryDataChannel.pair(secondChunking = ReadChunking.fixed(11))
            val header = ChunkHeader(TEST_ID, 0, 0, payloadLength = 5, hash = ChunkHash(ByteArray(16)))
            coroutineScope {
                launch {
                    val writer = FrameWriter(a)
                    writer.writeControl(alice, ProtocolConstants.STREAM_ID_CONTROL, Heartbeat(1))
                    writer.writeChunk(alice, ProtocolConstants.STREAM_ID_CONTROL, header, "hello".encodeToByteArray())
                    writer.writeControl(alice, ProtocolConstants.STREAM_ID_CONTROL, Cancel(TEST_ID, CancelReason.USER))
                    a.close()
                }
                val reader = FrameReader(b, limits = FrameLimits.SESSION)
                assertEquals(Heartbeat(1), bob.openControl(reader.readFrame()!!, 0))
                val chunk = bob.openChunk(reader.readFrame()!!, 0)
                assertEquals(header, chunk.header)
                assertEquals("hello", chunk.payload.decodeToString())
                assertEquals(Cancel(TEST_ID, CancelReason.USER), bob.openControl(reader.readFrame()!!, 0))
            }
        }

    @Test
    fun associatedDataBindsTypeStreamAndLength() {
        val sealedPayload = ToyProtector(1, 2).seal(FrameType.CONTROL, 0, ControlCodec.encode(Heartbeat(7)))

        fun receiver() = ToyProtector(2, 1)
        assertEquals(Heartbeat(7), receiver().openControl(Frame(FrameType.CONTROL, sealedPayload), 0))
        assertProtocolError("relabelled as a chunk") { receiver().openChunk(Frame(FrameType.CHUNK, sealedPayload), 0) }
        assertProtocolError("other stream") { receiver().openControl(Frame(FrameType.CONTROL, sealedPayload), 2) }
        assertProtocolError("truncated") {
            receiver().openControl(Frame(FrameType.CONTROL, sealedPayload.copyOf(sealedPayload.size - 1)), 0)
        }
        assertProtocolError("extended") { receiver().openControl(Frame(FrameType.CONTROL, sealedPayload + byteArrayOf(0)), 0) }
        val flipped = sealedPayload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertProtocolError("bit flip") { receiver().openControl(Frame(FrameType.CONTROL, flipped), 0) }
        // Replaying the same frame fails because the receive counter moved on.
        val r = receiver()
        r.openControl(Frame(FrameType.CONTROL, sealedPayload), 0)
        assertProtocolError("replay") { r.openControl(Frame(FrameType.CONTROL, sealedPayload), 0) }
    }

    @Test
    fun plaintextProtectorIsTransparent() {
        val p = PlaintextFrameProtector()
        val bytes = "010203".unhex()
        assertContentEquals(bytes, p.seal(FrameType.CHUNK, 3, bytes))
        assertContentEquals(bytes, p.open(FrameType.CHUNK, 3, bytes))
        assertEquals(0, p.overhead)
        assertRejectsArgument { p.seal(FrameType.HELLO, 0, bytes) }
        assertRejectsArgument { p.open(FrameType.CONTROL, -1, bytes) }
    }

    @Test
    fun streamOpenFrameRoundTrip() =
        runTest {
            val (alice, bob) = pairOfProtectors()
            val open = StreamOpen(TEST_ID, 5, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.CONTROL, generation = 1)
            val (a, b) = InMemoryDataChannel.pair()
            FrameWriter(a).writeStreamOpen(alice, open)
            val frame = FrameReader(b, limits = FrameLimits.STREAM_START).readFrame()!!
            assertEquals(FrameType.STREAM_OPEN, frame.type)
            assertEquals(5, StreamOpenFrame.peekStreamId(frame))
            assertEquals("00000005", frame.payload.copyOf(4).hex())
            assertEquals(open, StreamOpenFrame.open(bob, frame))
        }

    @Test
    fun streamOpenFrameRejectsTampering() {
        val open = StreamOpen(TEST_ID, 2, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, generation = 0)
        val payload = StreamOpenFrame.encode(ToyProtector(1, 2), open)
        val moved = payload.copyOf().also { BigEndian.putU32(it, 0, 3) }
        assertProtocolError("clear stream id changed") { StreamOpenFrame.open(ToyProtector(2, 1), Frame(FrameType.STREAM_OPEN, moved)) }
        // Without a MAC the sealed id still has to match the clear one.
        val plain = StreamOpenFrame.encode(PlaintextFrameProtector(), open).also { BigEndian.putU32(it, 0, 3) }
        assertProtocolError("sealed id differs") { StreamOpenFrame.open(PlaintextFrameProtector(), Frame(FrameType.STREAM_OPEN, plain)) }
        assertProtocolError("short") { StreamOpenFrame.peekStreamId(Frame(FrameType.STREAM_OPEN, ByteArray(3))) }
        assertProtocolError("id above 2^31") { StreamOpenFrame.peekStreamId(Frame(FrameType.STREAM_OPEN, "80000000".unhex())) }
        assertProtocolError("wrong type") { StreamOpenFrame.peekStreamId(Frame(FrameType.CONTROL, ByteArray(8))) }
        // A STREAM_OPEN frame must carry a StreamOpen, and a CONTROL frame must not.
        val heartbeat = ByteArray(4).also { BigEndian.putU32(it, 0, 2) } + ControlCodec.encode(Heartbeat(1))
        assertProtocolError("heartbeat in STREAM_OPEN") {
            StreamOpenFrame.open(PlaintextFrameProtector(), Frame(FrameType.STREAM_OPEN, heartbeat))
        }
        val smuggled = Frame(FrameType.CONTROL, ControlCodec.encode(open))
        assertProtocolError("StreamOpen in CONTROL") { PlaintextFrameProtector().openControl(smuggled, 0) }
        assertProtocolError("StreamOpen via openFrame") { PlaintextFrameProtector().openFrame(Frame(FrameType.STREAM_OPEN, payload), 2) }
    }

    @Test
    fun helpersRejectMisuse() =
        runTest {
            val (a, _) = InMemoryDataChannel.pair()
            val writer = FrameWriter(a)
            val open = StreamOpen(TEST_ID, 2, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, generation = 0)
            assertFailsWith<IllegalArgumentException> { writer.writeControl(PlaintextFrameProtector(), 0, open) }
            assertFailsWith<IllegalArgumentException> { writer.writeProtected(PlaintextFrameProtector(), FrameType.HELLO, 0, ByteArray(1)) }
            assertFailsWith<IllegalArgumentException> {
                writer.writeProtected(PlaintextFrameProtector(), FrameType.STREAM_OPEN, 2, ByteArray(1))
            }
            val liar =
                object : FrameProtector {
                    override val overhead = 16

                    override fun seal(
                        type: FrameType,
                        streamId: Int,
                        plaintext: ByteArray,
                    ) = plaintext

                    override fun open(
                        type: FrameType,
                        streamId: Int,
                        sealed: ByteArray,
                    ) = sealed
                }
            assertFailsWith<IllegalStateException> { writer.writeProtected(liar, FrameType.CONTROL, 0, ByteArray(1)) }
            assertProtocolError { PlaintextFrameProtector().openFrame(Frame(FrameType.HELLO, ByteArray(1)), 0) }
            assertProtocolError { PlaintextFrameProtector().openControl(Frame(FrameType.CHUNK, ByteArray(1)), 0) }
            assertProtocolError { PlaintextFrameProtector().openChunk(Frame(FrameType.CONTROL, ByteArray(1)), 0) }
        }

    @Test
    fun streamIdAllocatorNeverRepeats() {
        val allocator = StreamIdAllocator()
        val ids = List(1000) { allocator.next() }
        assertEquals(ProtocolConstants.STREAM_ID_FIRST_WIFI, ids.first())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        val nearEnd = StreamIdAllocator(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, nearEnd.next())
        assertFailsWith<IllegalStateException> { nearEnd.next() }
        assertRejectsArgument { StreamIdAllocator(ProtocolConstants.STREAM_ID_BLUETOOTH) }
    }

    /**
     * S7: over a session with a control stream, a Bluetooth stream, four Wi-Fi streams and a reconnect onto four
     * new Wi-Fi streams, no (key, stream id, counter) nonce is ever used twice, in either direction.
     */
    @Test
    fun noNonceRepeatsAcrossStreamsAndReconnects() =
        runTest {
            val nonces = ArrayList<Triple<Long, Int, Long>>()
            val (sender, receiver) = pairOfProtectors(nonces)
            val allocator = StreamIdAllocator()

            suspend fun stream(
                streamId: Int,
                frames: Int,
                open: Boolean,
            ) {
                val (a, b) = InMemoryDataChannel.pair(secondChunking = ReadChunking.random(streamId.toLong(), 64))
                val writer = FrameWriter(a)
                val reader = FrameReader(b, limits = FrameLimits.ALL)
                if (open) {
                    writer.writeStreamOpen(
                        sender,
                        StreamOpen(TEST_ID, streamId, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, generation = 0),
                    )
                    assertEquals(streamId, StreamOpenFrame.open(receiver, reader.readFrame()!!).streamId)
                }
                repeat(frames) { i ->
                    val header = ChunkHeader(TEST_ID, 0, i, payloadLength = 16, hash = ChunkHash(ByteArray(16)))
                    writer.writeChunk(sender, streamId, header, ByteArray(16) { it.toByte() })
                    assertEquals(i, receiver.openChunk(reader.readFrame()!!, streamId).header.chunkIndex)
                }
            }

            // Control stream: both directions.
            repeat(50) {
                receiver.seal(FrameType.CONTROL, ProtocolConstants.STREAM_ID_CONTROL, ControlCodec.encode(Heartbeat(it.toLong())))
            }
            repeat(50) { sender.seal(FrameType.CONTROL, ProtocolConstants.STREAM_ID_CONTROL, ControlCodec.encode(Heartbeat(it.toLong()))) }
            stream(ProtocolConstants.STREAM_ID_BLUETOOTH, 20, open = false)
            repeat(4) { stream(allocator.next(), 30, open = true) }
            // Reconnect: new link generation, new stream ids from the same allocator.
            repeat(4) { stream(allocator.next(), 30, open = true) }

            assertEquals(nonces.size, nonces.toSet().size, "a (key, nonce) pair repeated")
            assertTrue(nonces.map { it.second }.toSet().containsAll(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
        }

    @Test
    fun unknownControlMessagesAreDistinguishable() {
        val envelope = RawCbor.envelope(99, RawCbor.map(RawCbor.uint(1) to RawCbor.uint(1)))
        val e = assertProtocolError { PlaintextFrameProtector().openControl(Frame(FrameType.CONTROL, envelope), 0) }
        assertIs<UnknownControlMessageException>(e)
    }
}
