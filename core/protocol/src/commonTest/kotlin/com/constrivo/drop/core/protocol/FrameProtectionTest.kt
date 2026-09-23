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
 * A stand-in for the real frame cipher, shaped like the AEAD of WP4: the plaintext is cut into [blockSize]-byte
 * blocks and each block is a keyed keystream XOR followed by an 8-byte FNV-1a tag over the associated data, the
 * counter, the block number and the block's ciphertext, so the overhead grows with the plaintext size. Not secure; it
 * behaves like an AEAD for the protocol plumbing (per-stream counters, tag failure on any change to length, type,
 * stream or content) and records every nonce it seals with.
 */
internal class ToyProtector(
    private val sendKey: Long,
    private val receiveKey: Long,
    private val sealedNonces: MutableList<Triple<Long, Int, Long>> = ArrayList(),
    private val blockSize: Int = ProtocolConstants.AEAD_BLOCK_SIZE,
) : FrameProtector {
    private val sendCounters = HashMap<Int, Long>()
    private val receiveCounters = HashMap<Int, Long>()

    override fun sealedSize(
        type: FrameType,
        plaintextSize: Int,
    ): Int = plaintextSize + TAG_SIZE * blocks(plaintextSize)

    override fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= plaintext.size - length) { "range out of bounds" }
        val counter = sendCounters[streamId] ?: 0L
        sendCounters[streamId] = counter + 1
        sealedNonces += Triple(sendKey, streamId, counter)
        val aad = FrameAad.of(type, streamId, sealedSize(type, length))
        val ciphertext = xor(sendKey, streamId, counter, plaintext, offset, length)
        val out = ByteArray(sealedSize(type, length))
        var at = 0
        for (b in 0 until blocks(length)) {
            val start = b * blockSize
            val end = minOf(length, start + blockSize)
            ciphertext.copyInto(out, at, start, end)
            at += end - start
            tag(sendKey, aad, counter, b, ciphertext, start, end).copyInto(out, at)
            at += TAG_SIZE
        }
        return out
    }

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= sealed.size - length) { "range out of bounds" }
        val plaintextSize = plaintextSizeOf(length) ?: throw ProtocolException("sealed frame of $length bytes has no valid size")
        val counter = receiveCounters[streamId] ?: 0L
        val aad = FrameAad.of(type, streamId, length)
        val ciphertext = ByteArray(plaintextSize)
        var at = offset
        for (b in 0 until blocks(plaintextSize)) {
            val start = b * blockSize
            val end = minOf(plaintextSize, start + blockSize)
            sealed.copyInto(ciphertext, start, at, at + end - start)
            at += end - start
            val expected = tag(receiveKey, aad, counter, b, ciphertext, start, end)
            if (!expected.contentEquals(sealed.copyOfRange(at, at + TAG_SIZE))) throw ProtocolException("authentication failed")
            at += TAG_SIZE
        }
        receiveCounters[streamId] = counter + 1
        return xor(receiveKey, streamId, counter, ciphertext, 0, plaintextSize)
    }

    private fun blocks(plaintextSize: Int): Int = maxOf(1, (plaintextSize + blockSize - 1) / blockSize)

    private fun plaintextSizeOf(sealedSize: Int): Int? {
        if (sealedSize == TAG_SIZE) return 0
        val full = sealedSize / (blockSize + TAG_SIZE)
        val rest = sealedSize % (blockSize + TAG_SIZE)
        return when {
            rest == 0 && full > 0 -> full * blockSize
            rest > TAG_SIZE -> full * blockSize + rest - TAG_SIZE
            else -> null
        }
    }

    private fun xor(
        key: Long,
        streamId: Int,
        counter: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        var state = key xor (streamId.toLong() shl 40) xor counter
        return ByteArray(length) { i ->
            state = state * 6364136223846793005L + 1442695040888963407L
            (data[offset + i].toInt() xor (state ushr 56).toInt()).toByte()
        }
    }

    private fun tag(
        key: Long,
        aad: ByteArray,
        counter: Long,
        block: Int,
        ciphertext: ByteArray,
        start: Int,
        end: Int,
    ): ByteArray {
        var h = -0x340d631b7bdddcdbL xor key

        fun mix(b: Int) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
        }
        aad.forEach { mix(it.toInt()) }
        for (i in 0 until 8) mix((counter ushr (8 * i)).toInt())
        for (i in 0 until 4) mix(block ushr (8 * i))
        for (i in start until end) mix(ciphertext[i].toInt())
        return ByteArray(TAG_SIZE) { (h ushr (8 * it)).toByte() }
    }

    companion object {
        const val TAG_SIZE: Int = 8
    }
}

/** A [PlaintextFrameProtector] that records which stream ids it was asked to open. */
internal class RecordingProtector : FrameProtector by PlaintextFrameProtector() {
    val opened = ArrayList<Int>()

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        opened += streamId
        return sealed.copyOfRange(offset, offset + length)
    }
}

class FrameProtectionTest {
    private fun pairOfProtectors(
        nonces: MutableList<Triple<Long, Int, Long>> = ArrayList(),
        blockSize: Int = ProtocolConstants.AEAD_BLOCK_SIZE,
    ) = ToyProtector(sendKey = 0xA11CE, receiveKey = 0xB0B, sealedNonces = nonces, blockSize = blockSize) to
        ToyProtector(sendKey = 0xB0B, receiveKey = 0xA11CE, sealedNonces = nonces, blockSize = blockSize)

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

    /**
     * WP4 memory design: a size-dependent overhead (one tag per AEAD block of a full 4 MiB chunk) fits the frame
     * limits, and a chunk can be sealed from a range of a pooled buffer, read into another pooled buffer and opened
     * in place into a copy-free view.
     */
    @Test
    fun blockwiseOverheadAndPooledBuffersCarryAFullChunk() =
        runTest {
            val (alice, bob) = pairOfProtectors()
            val plaintextSize = ChunkHeader.SIZE + ProtocolConstants.CHUNK_SIZE
            val sealedSize = alice.sealedSize(FrameType.CHUNK, plaintextSize)
            assertEquals(plaintextSize + 65 * ToyProtector.TAG_SIZE, sealedSize, "65 AEAD blocks, 65 tags")
            assertTrue(sealedSize <= FrameType.CHUNK.maxPayload)

            val header = ChunkHeader(TEST_ID, 2, 7, payloadLength = ProtocolConstants.CHUNK_SIZE, hash = ChunkHash(ByteArray(16) { 3 }))
            val sendBuffer = ByteArray(10 + plaintextSize)
            header.encodeInto(sendBuffer, 10)
            for (i in 0 until ProtocolConstants.CHUNK_SIZE) sendBuffer[10 + ChunkHeader.SIZE + i] = (i * 31).toByte()
            val (a, b) = InMemoryDataChannel.pair()
            coroutineScope {
                launch { FrameWriter(a).writeProtected(alice, FrameType.CHUNK, 4, sendBuffer, offset = 10, length = plaintextSize) }
                val reader = FrameReader(b, limits = FrameLimits.SESSION)
                val frameHeader = reader.readHeader()!!
                assertEquals(FrameHeader(FrameType.CHUNK, sealedSize), frameHeader)
                val pool = ByteArray(sealedSize + 100)
                reader.readPayload(pool, offset = 100)
                val n = bob.openInto(FrameType.CHUNK, 4, pool, 100, sealedSize, pool, 0)
                val view = ChunkView.decode(pool, 0, n)
                assertEquals(header, view.header)
                assertEquals(ChunkHeader.SIZE, view.payloadOffset)
                assertContentEquals(sendBuffer.copyOfRange(10 + ChunkHeader.SIZE, sendBuffer.size), view.copyPayload())
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
        assertContentEquals("0203".unhex(), p.seal(FrameType.CHUNK, 3, bytes, 1, 2))
        assertContentEquals("02".unhex(), p.open(FrameType.CHUNK, 3, bytes, 1, 1))
        assertEquals(3, p.sealedSize(FrameType.CONTROL, 3))
        val inPlace = bytes.copyOf()
        assertEquals(2, p.openInto(FrameType.CHUNK, 3, inPlace, 1, 2, inPlace, 0))
        assertContentEquals("020303".unhex(), inPlace)
        assertRejectsArgument { p.seal(FrameType.HELLO, 0, bytes) }
        assertRejectsArgument { p.open(FrameType.CONTROL, -1, bytes) }
        assertRejectsArgument { p.seal(FrameType.CONTROL, 0, bytes, 2, 2) }
        assertRejectsArgument { p.sealedSize(FrameType.HELLO, 1) }
        assertRejectsArgument { p.openInto(FrameType.CHUNK, 3, bytes, 0, 3, ByteArray(2), 0) }
    }

    @Test
    fun streamOpenFrameRoundTrip() =
        runTest {
            val (alice, bob) = pairOfProtectors()
            // Alice is the responder, so she opens odd ids; Bob, the initiator, listens.
            val open = StreamOpen(TEST_ID, 5, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.CONTROL, generation = 1)
            val (a, b) = InMemoryDataChannel.pair()
            FrameWriter(a).writeStreamOpen(alice, open)
            val frame = FrameReader(b, limits = FrameLimits.STREAM_START).readFrame()!!
            assertEquals(FrameType.STREAM_OPEN, frame.type)
            assertEquals(5, StreamOpenFrame.peekStreamId(frame))
            assertEquals("00000005", frame.payload.copyOf(4).hex())
            val registry = StreamIdRegistry(SessionRole.INITIATOR)
            assertEquals(open, StreamOpenFrame.open(bob, frame, registry))
            assertTrue(5 in registry)
        }

    @Test
    fun streamOpenFrameRejectsTampering() {
        val open = StreamOpen(TEST_ID, 2, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, generation = 0)

        fun registry() = StreamIdRegistry(SessionRole.RESPONDER)
        val payload = StreamOpenFrame.encode(ToyProtector(1, 2), open)
        val moved = payload.copyOf().also { BigEndian.putU32(it, 0, 4) }
        assertProtocolError("clear stream id changed") {
            StreamOpenFrame.open(ToyProtector(2, 1), Frame(FrameType.STREAM_OPEN, moved), registry())
        }
        // Without a MAC the sealed id still has to match the clear one.
        val plain = StreamOpenFrame.encode(PlaintextFrameProtector(), open).also { BigEndian.putU32(it, 0, 4) }
        assertProtocolError("sealed id differs") {
            StreamOpenFrame.open(PlaintextFrameProtector(), Frame(FrameType.STREAM_OPEN, plain), registry())
        }
        assertProtocolError("short") { StreamOpenFrame.peekStreamId(Frame(FrameType.STREAM_OPEN, ByteArray(3))) }
        assertProtocolError("id above 2^31") { StreamOpenFrame.peekStreamId(Frame(FrameType.STREAM_OPEN, "80000000".unhex())) }
        assertProtocolError("wrong type") { StreamOpenFrame.peekStreamId(Frame(FrameType.CONTROL, ByteArray(8))) }
        // A STREAM_OPEN frame must carry a StreamOpen, and a CONTROL frame must not.
        val heartbeat = ByteArray(4).also { BigEndian.putU32(it, 0, 2) } + ControlCodec.encode(Heartbeat(1))
        assertProtocolError("heartbeat in STREAM_OPEN") {
            StreamOpenFrame.open(PlaintextFrameProtector(), Frame(FrameType.STREAM_OPEN, heartbeat), registry())
        }
        val smuggled = Frame(FrameType.CONTROL, ControlCodec.encode(open))
        assertProtocolError("StreamOpen in CONTROL") { PlaintextFrameProtector().openControl(smuggled, 0) }
        assertProtocolError("StreamOpen via openFrame") { PlaintextFrameProtector().openFrame(Frame(FrameType.STREAM_OPEN, payload), 2) }
    }

    /**
     * The clear id of an unauthenticated `StreamOpen` must not steer the protector into the control (0) or Bluetooth
     * (1) nonce space, into the listener's own partition, or into a stream already opened.
     */
    @Test
    fun streamOpenChecksTheClearIdBeforeOpening() {
        val registry = StreamIdRegistry(SessionRole.RESPONDER)
        val recording = RecordingProtector()

        fun frameFor(id: Int): Frame {
            val body = ControlCodec.encode(StreamOpen(TEST_ID, maxOf(id, 2), StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, 0))
            return Frame(FrameType.STREAM_OPEN, ByteArray(4).also { BigEndian.putU32(it, 0, id) } + body)
        }
        assertProtocolError("control stream id") { StreamOpenFrame.open(recording, frameFor(0), registry) }
        assertProtocolError("Bluetooth stream id") { StreamOpenFrame.open(recording, frameFor(1), registry) }
        assertProtocolError("the listener's own partition") { StreamOpenFrame.open(recording, frameFor(3), registry) }
        assertEquals(emptyList<Int>(), recording.opened, "rejected before the protector saw them")

        assertEquals(2, StreamOpenFrame.open(recording, frameFor(2), registry).streamId)
        assertProtocolError("replayed StreamOpen") { StreamOpenFrame.open(recording, frameFor(2), registry) }
        assertEquals(listOf(2), recording.opened, "the replay never reached the protector")
        assertEquals(1, registry.size)
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
            assertFailsWith<IllegalArgumentException> {
                writer.writeProtected(PlaintextFrameProtector(), FrameType.CONTROL, 0, ByteArray(4), offset = 3, length = 2)
            }
            // An oversized plaintext is refused before sealing, so no nonce counter is spent on it.
            val nonces = ArrayList<Triple<Long, Int, Long>>()
            val counting = ToyProtector(1, 2, nonces)
            assertProtocolError("oversized control frame") {
                writer.writeProtected(counting, FrameType.CONTROL, 0, ByteArray(FrameType.CONTROL.maxPayload))
            }
            assertEquals(emptyList(), nonces)
            writer.writeProtected(counting, FrameType.CONTROL, 0, ByteArray(3))
            assertEquals(listOf(Triple(1L, 0, 0L)), nonces, "the first frame written takes counter 0")
            val liar =
                object : FrameProtector by PlaintextFrameProtector() {
                    override fun sealedSize(
                        type: FrameType,
                        plaintextSize: Int,
                    ) = plaintextSize + 16
                }
            assertFailsWith<IllegalStateException> { writer.writeProtected(liar, FrameType.CONTROL, 0, ByteArray(1)) }
            assertFailsWith<IllegalStateException>("a writer whose protector misbehaved is broken") {
                writer.writeProtected(PlaintextFrameProtector(), FrameType.CONTROL, 0, ByteArray(1))
            }
            assertProtocolError { PlaintextFrameProtector().openFrame(Frame(FrameType.HELLO, ByteArray(1)), 0) }
            assertProtocolError { PlaintextFrameProtector().openControl(Frame(FrameType.CHUNK, ByteArray(1)), 0) }
            assertProtocolError { PlaintextFrameProtector().openChunk(Frame(FrameType.CONTROL, ByteArray(1)), 0) }
            assertProtocolError { PlaintextFrameProtector().openChunkView(Frame(FrameType.CHUNK, ByteArray(47)), 0) }
        }

    @Test
    fun streamIdsArePartitionedBySessionRole() {
        val initiator = StreamIdAllocator(SessionRole.INITIATOR)
        val responder = StreamIdAllocator(SessionRole.RESPONDER)
        val mine = List(1000) { initiator.next() }
        val theirs = List(1000) { responder.next() }
        assertEquals(listOf(2, 4, 6), mine.take(3))
        assertEquals(listOf(3, 5, 7), theirs.take(3))
        assertEquals(mine.sorted(), mine)
        assertTrue(mine.intersect(theirs.toSet()).isEmpty(), "the two partitions never meet")
        assertTrue(mine.all { SessionRole.INITIATOR.opens(it) && !SessionRole.RESPONDER.opens(it) })
        assertTrue(theirs.all { SessionRole.RESPONDER.opens(it) })
        assertTrue(listOf(0, 1).none { SessionRole.INITIATOR.opens(it) || SessionRole.RESPONDER.opens(it) })
        assertEquals(SessionRole.RESPONDER, SessionRole.INITIATOR.peer)

        val nearEnd = StreamIdAllocator(SessionRole.RESPONDER, first = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, nearEnd.next())
        assertFailsWith<IllegalStateException> { nearEnd.next() }
        val evenEnd = StreamIdAllocator(SessionRole.INITIATOR, first = Int.MAX_VALUE - 1)
        assertEquals(Int.MAX_VALUE - 1, evenEnd.next())
        assertFailsWith<IllegalStateException> { evenEnd.next() }
        assertRejectsArgument { StreamIdAllocator(SessionRole.INITIATOR, first = 3) }
        assertRejectsArgument { StreamIdAllocator(SessionRole.RESPONDER, first = ProtocolConstants.STREAM_ID_BLUETOOTH) }

        val registry = StreamIdRegistry(SessionRole.INITIATOR)
        registry.register(3)
        assertProtocolError("twice") { registry.register(3) }
        assertProtocolError("own partition") { registry.checkAvailable(4) }
        assertProtocolError("Bluetooth") { registry.checkAvailable(1) }
        registry.checkAvailable(5)
    }

    /**
     * S7: over a session with a control stream, a Bluetooth stream, and data connections opened by **both** peers
     * across three link generations (the side that connects changes with the link), with frames flowing both ways on
     * every connection, no (key, stream id, counter) nonce is ever used twice, and every frame authenticates.
     */
    @Test
    fun noNonceRepeatsAcrossPeersStreamsAndLinkGenerations() =
        runTest {
            val nonces = ArrayList<Triple<Long, Int, Long>>()
            val (initiator, responder) = pairOfProtectors(nonces)
            val roles = mapOf(initiator to SessionRole.INITIATOR, responder to SessionRole.RESPONDER)
            val allocators = roles.mapValues { (_, role) -> StreamIdAllocator(role) }
            val registries = roles.mapValues { (_, role) -> StreamIdRegistry(role) }
            val opened = ArrayList<Int>()

            suspend fun connection(
                opener: ToyProtector,
                listener: ToyProtector,
                generation: Int,
            ) {
                val streamId = allocators.getValue(opener).next()
                opened += streamId
                val (a, b) = InMemoryDataChannel.pair(secondChunking = ReadChunking.random(streamId.toLong(), 64))
                val openerWriter = FrameWriter(a)
                val listenerReader = FrameReader(b, limits = FrameLimits.STREAM_START)
                openerWriter.writeStreamOpen(
                    opener,
                    StreamOpen(TEST_ID, streamId, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.CONTROL, generation),
                )
                val open = StreamOpenFrame.open(listener, listenerReader.readFrame()!!, registries.getValue(listener))
                assertEquals(streamId, open.streamId)
                listenerReader.limits = FrameLimits.SESSION
                val listenerWriter = FrameWriter(b)
                val openerReader = FrameReader(a, limits = FrameLimits.SESSION)
                repeat(10) { i ->
                    val header = ChunkHeader(TEST_ID, 0, i, payloadLength = 16, hash = ChunkHash(ByteArray(16)))
                    openerWriter.writeChunk(opener, streamId, header, ByteArray(16) { it.toByte() })
                    assertEquals(i, listener.openChunk(listenerReader.readFrame()!!, streamId).header.chunkIndex)
                    // Control moved onto this connection (N13): the listener answers on the same stream id.
                    listenerWriter.writeControl(listener, streamId, Heartbeat(i.toLong()))
                    assertEquals(Heartbeat(i.toLong()), opener.openControl(openerReader.readFrame()!!, streamId))
                }
            }

            // The handshake connection: control (0) and the Bluetooth head start (1), both directions.
            repeat(20) {
                initiator.seal(FrameType.CONTROL, ProtocolConstants.STREAM_ID_CONTROL, ControlCodec.encode(Heartbeat(it.toLong())))
                responder.seal(FrameType.CONTROL, ProtocolConstants.STREAM_ID_CONTROL, ControlCodec.encode(Heartbeat(it.toLong())))
                initiator.seal(FrameType.CHUNK, ProtocolConstants.STREAM_ID_BLUETOOTH, ByteArray(64))
                responder.seal(FrameType.CHUNK, ProtocolConstants.STREAM_ID_BLUETOOTH, ByteArray(64))
            }
            // Generation 0 (LAN): the initiator connects to the responder's listener.
            repeat(4) { connection(initiator, responder, 0) }
            // Generation 1 (P2P, the initiator is group owner): the responder connects.
            repeat(4) { connection(responder, initiator, 1) }
            // Generation 2: the initiator connects again.
            repeat(2) { connection(initiator, responder, 2) }

            assertEquals(opened.size, opened.toSet().size, "a stream id was opened twice")
            assertEquals(nonces.size, nonces.toSet().size, "a (key, nonce) pair repeated")
            assertEquals(listOf(2, 4, 6, 8, 3, 5, 7, 9, 10, 12), opened)
        }

    @Test
    fun unknownControlMessagesAreDistinguishable() {
        val envelope = RawCbor.envelope(99, RawCbor.map(RawCbor.uint(1) to RawCbor.uint(1)))
        val e = assertProtocolError { PlaintextFrameProtector().openControl(Frame(FrameType.CONTROL, envelope), 0) }
        assertIs<UnknownControlMessageException>(e)
    }
}
