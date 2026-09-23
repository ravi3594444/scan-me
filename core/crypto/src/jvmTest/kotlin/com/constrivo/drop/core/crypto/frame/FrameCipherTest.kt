package com.constrivo.drop.core.crypto.frame

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness
import com.constrivo.drop.core.crypto.handshake.HandshakeInitiator
import com.constrivo.drop.core.crypto.handshake.HandshakeResponder
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.toHex
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-E9 frame encryption (architecture §7.1; spec changes S7, N2, N3). */
class FrameCipherTest {
    private val crypto = TestFixtures.crypto
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val aad = byteArrayOf(0, 0, 0, 21, 0x10, 0, 0, 0, 2)

    private fun pair(
        algorithm: AeadAlgorithm,
        streamId: Int = 2,
    ): Pair<FrameCipher, FrameCipher> =
        FrameCipher.sealer(crypto, algorithm, key, streamId) to FrameCipher.opener(crypto, algorithm, key, streamId)

    @Test
    fun nonceIsStreamIdThenCounterBigEndian() {
        assertEquals("0102030405060708090a0b0c", FrameCipher.nonce(0x01020304, 0x05060708090a0b0cL).toHex())
        assertEquals("000000000000000000000000", FrameCipher.nonce(0, 0).toHex())
        assertEquals("7fffffff00000000ffffffff", FrameCipher.nonce(Int.MAX_VALUE, 0xFFFFFFFFL).toHex())
        assertFailsWith<IllegalArgumentException> { FrameCipher.nonce(-1, 0) }
        assertFailsWith<IllegalArgumentException> { FrameCipher.nonce(0, -1) }
    }

    @Test
    fun fE9_roundTripsInOrderWithBothAlgorithms() {
        for (algorithm in AeadAlgorithm.entries) {
            val (sealer, opener) = pair(algorithm)
            for (i in 0 until 100) {
                val payload = ByteArray(i * 13) { (it + i).toByte() }
                val frame = sealer.seal(payload, aad)
                assertEquals(payload.size + 16, frame.size)
                assertContentEquals(payload, opener.open(frame, aad), "$algorithm frame $i")
            }
            assertEquals(100, sealer.nextCounter)
            assertEquals(100, opener.nextCounter)
            assertEquals(algorithm, sealer.algorithm)
        }
    }

    @Test
    fun fE9_sealIsEncryptionUnderTheDocumentedNonce() {
        for (algorithm in AeadAlgorithm.entries) {
            val sealer = FrameCipher.sealer(crypto, algorithm, key, 5)
            sealer.seal(ByteArray(3), aad)
            val second = sealer.seal("payload".encodeToByteArray(), aad)
            val direct = crypto.aead(algorithm, key).open(FrameCipher.nonce(5, 1), second, aad)
            assertEquals("payload", direct.decodeToString())
        }
    }

    @Test
    fun s7_noNonceRepeatsOverOneMillionFrames() {
        val recording = RecordingAead(crypto.aead(AeadAlgorithm.AES_256_GCM, key))
        val sealers = (0 until 4).map { FrameCipher(recording, streamId = 2 + it, mode = FrameCipher.Mode.SEAL) }
        val payload = ByteArray(0)
        repeat(250_000) { sealers.forEach { it.seal(payload, aad) } }
        assertEquals(1_000_000, recording.nonces.size)
        assertEquals(1_000_000, recording.nonces.toHashSet().size, "a nonce repeated")
        sealers.forEach { assertEquals(250_000, it.nextCounter) }
    }

    @Test
    fun s7_noKeyNoncePairRepeatsAcrossASession() {
        // Loopback over a real handshake: both directions, the control, Bluetooth and four Wi-Fi streams.
        val recorder = RecordingProvider(crypto)
        val initiator =
            HandshakeInitiator(
                recorder,
                TestFixtures.IDENTITY_A,
                HandshakeHarness.INFO_A,
                randomness = TestFixtures.fixedRandomness(TestFixtures.EPHEMERAL_A, TestFixtures.NONCE_A),
            )
        val responder =
            HandshakeResponder(
                recorder,
                TestFixtures.IDENTITY_B,
                HandshakeHarness.INFO_B,
                HandshakeGuard(),
                randomness = TestFixtures.fixedRandomness(TestFixtures.EPHEMERAL_B, TestFixtures.NONCE_B),
            )
        val ex = HandshakeHarness.run(initiator, responder)
        val streams = listOf(0, 1, 2, 3, 4, 5)
        for (result in listOf(ex.initiator, ex.responder)) {
            val peer = if (result === ex.initiator) ex.responder else ex.initiator
            for (stream in streams) {
                val sealer = result.frameSender(stream)
                val opener = peer.frameReceiver(stream)
                repeat(2_000) { i ->
                    val frame = sealer.seal(byteArrayOf(i.toByte()), aad)
                    assertEquals(i.toByte(), opener.open(frame, aad)[0])
                }
            }
        }
        assertEquals(2 * streams.size * 2_000, recorder.sealed.size)
        assertEquals(recorder.sealed.size, recorder.sealed.toHashSet().size, "a (key, nonce) pair repeated")
    }

    @Test
    fun s7_concurrentSealersNeverShareACounter() {
        val recording = RecordingAead(crypto.aead(AeadAlgorithm.CHACHA20_POLY1305, key))
        val sealer = FrameCipher(recording, streamId = 3, mode = FrameCipher.Mode.SEAL)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { pool.execute { repeat(5_000) { sealer.seal(ByteArray(1), aad) } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(40_000, recording.nonces.size)
        assertEquals(40_000, recording.nonces.toHashSet().size)
        assertEquals(40_000, sealer.nextCounter)
    }

    @Test
    fun replayedFrameIsRejectedAndTheStreamFailsClosed() {
        for (algorithm in AeadAlgorithm.entries) {
            val (sealer, opener) = pair(algorithm)
            val f0 = sealer.seal("zero".encodeToByteArray(), aad)
            val f1 = sealer.seal("one".encodeToByteArray(), aad)
            opener.open(f0, aad)
            assertFailsWith<CryptoException> { opener.open(f0, aad) }
            // Fail closed: even the genuine next frame is refused now.
            assertFailsWith<CryptoException> { opener.open(f1, aad) }
        }
    }

    @Test
    fun reorderedOrDroppedFramesAreRejected() {
        for (algorithm in AeadAlgorithm.entries) {
            val (sealer, opener) = pair(algorithm)
            val frames = (0 until 3).map { sealer.seal(byteArrayOf(it.toByte()), aad) }
            assertFailsWith<CryptoException> { opener.open(frames[1], aad) }

            val (sealer2, opener2) = pair(algorithm)
            val frames2 = (0 until 3).map { sealer2.seal(byteArrayOf(it.toByte()), aad) }
            opener2.open(frames2[0], aad)
            assertFailsWith<CryptoException> { opener2.open(frames2[2], aad) }
        }
    }

    @Test
    fun explicitCounterMustBeTheExpectedOne() {
        val (sealer, opener) = pair(AeadAlgorithm.AES_256_GCM)
        val f0 = sealer.seal(byteArrayOf(1), aad)
        val f1 = sealer.seal(byteArrayOf(2), aad)
        assertContentEquals(byteArrayOf(1), opener.open(0, f0, aad))
        assertFailsWith<CryptoException> { opener.open(0, f1, aad) }

        val (_, opener2) = pair(AeadAlgorithm.AES_256_GCM)
        assertFailsWith<CryptoException> { opener2.open(1, f1, aad) }
    }

    @Test
    fun n2_associatedDataMismatchIsRejected() {
        for (algorithm in AeadAlgorithm.entries) {
            val (sealer, opener) = pair(algorithm)
            val frame = sealer.seal(ByteArray(10), aad)
            val otherHeader = aad.copyOf().also { it[4] = 0x11 }
            assertFailsWith<CryptoException> { opener.open(frame, otherHeader) }
            val (sealer2, opener2) = pair(algorithm)
            assertFailsWith<CryptoException> { opener2.open(sealer2.seal(ByteArray(10), aad), ByteArray(0)) }
        }
    }

    @Test
    fun wrongStreamWrongKeyOrCorruptionIsRejected() {
        for (algorithm in AeadAlgorithm.entries) {
            val frame = FrameCipher.sealer(crypto, algorithm, key, 2).seal(ByteArray(32), aad)
            assertFailsWith<CryptoException> { FrameCipher.opener(crypto, algorithm, key, 3).open(frame, aad) }
            val otherKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException> { FrameCipher.opener(crypto, algorithm, otherKey, 2).open(frame, aad) }
            val corrupted = frame.copyOf().also { it[7] = (it[7].toInt() xor 0x40).toByte() }
            assertFailsWith<CryptoException> { FrameCipher.opener(crypto, algorithm, key, 2).open(corrupted, aad) }
            val otherAlgorithm = AeadAlgorithm.entries.first { it != algorithm }
            assertFailsWith<CryptoException> { FrameCipher.opener(crypto, otherAlgorithm, key, 2).open(frame, aad) }
        }
    }

    @Test
    fun directionalKeysDoNotOpenEachOther() {
        val ex = HandshakeHarness.run()
        val frame = ex.initiator.frameSender(2).seal(ByteArray(8), aad)
        // The initiator's own receiver uses k_B→A and must not open its own frames (reflection).
        assertFailsWith<CryptoException> { ex.initiator.frameReceiver(2).open(frame, aad) }
        assertContentEquals(ByteArray(8), ex.responder.frameReceiver(2).open(frame, aad))
    }

    @Test
    fun forgedFirstFrameDoesNotUseUpTheStreamId() {
        val ex = HandshakeHarness.run()
        val sender = ex.initiator.frameSender(2)
        // Someone opens a connection for stream 2 first and sends garbage: that opener fails closed ...
        val forged = ex.responder.frameReceiver(2)
        assertFailsWith<CryptoException> { forged.open(ByteArray(40) { 7 }, aad) }
        assertFalse(ex.responder.isReceiveStreamOpen(2))
        // ... and the real peer's connection for stream 2 still opens.
        val real = ex.responder.frameReceiver(2)
        val frames = (0 until 3).map { sender.seal(byteArrayOf(it.toByte()), aad) }
        assertContentEquals(byteArrayOf(0), real.open(frames[0], aad))
        assertTrue(ex.responder.isReceiveStreamOpen(2))
        // Once the stream is open, a new connection for it (here a replay of the genuine frames) is refused.
        val replay = ex.responder.frameReceiver(2)
        assertFailsWith<CryptoException> { replay.open(frames[0], aad) }
        assertFailsWith<CryptoException> { replay.open(frames[1], aad) }
        assertContentEquals(byteArrayOf(1), real.open(frames[1], aad))
        // Other stream ids are independent.
        val other = ex.responder.frameReceiver(3)
        assertContentEquals(byteArrayOf(9), other.open(ex.initiator.frameSender(3).seal(byteArrayOf(9), aad), aad))
    }

    @Test
    fun concurrentOpenersOfOneStreamFirstToAuthenticateWins() {
        val ex = HandshakeHarness.run()
        val frame0 = ex.initiator.frameSender(4).seal(ByteArray(3), aad)
        val first = ex.responder.frameReceiver(4)
        val second = ex.responder.frameReceiver(4)
        second.open(frame0, aad)
        assertFailsWith<CryptoException> { first.open(frame0, aad) }
    }

    @Test
    fun offsetFormsMatchTheAllocatingFormsAndShareTheCounter() {
        for (algorithm in AeadAlgorithm.entries) {
            val (sealer, opener) = pair(algorithm)
            val (twinSealer, _) = pair(algorithm)
            val payload = ByteArray(1000) { (it * 3).toByte() }
            val buffer = ByteArray(2048)
            payload.copyInto(buffer, 100)

            // Frame 0: in place, at an offset.
            val written = sealer.seal(buffer, 100, payload.size, aad, buffer, 100)
            assertEquals(payload.size + 16, written)
            assertContentEquals(twinSealer.seal(payload, aad), buffer.copyOfRange(100, 100 + written))
            // Frame 1: allocating form on the same cipher.
            val frame1 = sealer.seal(payload, aad)
            assertContentEquals(twinSealer.seal(payload, aad), frame1)
            assertEquals(2, sealer.nextCounter)

            // Open frame 0 in place, frame 1 into another array.
            val opened = opener.open(buffer, 100, written, aad, buffer, 100)
            assertEquals(payload.size, opened)
            assertContentEquals(payload, buffer.copyOfRange(100, 100 + opened))
            val out = ByteArray(payload.size + 5)
            assertEquals(payload.size, opener.open(frame1, 0, frame1.size, aad, out, 5))
            assertContentEquals(payload, out.copyOfRange(5, out.size))
            assertEquals(2, opener.nextCounter)
            assertEquals(FrameCipher.MAX_PLAINTEXT_BYTES - 2 * payload.size, opener.bytesRemaining)
        }
    }

    @Test
    fun offsetFormsRejectBadRangesWithoutUsingACounter() {
        val (sealer, opener) = pair(AeadAlgorithm.AES_256_GCM)
        val input = ByteArray(10)
        assertFailsWith<IllegalArgumentException> { sealer.seal(input, 5, 6, aad, ByteArray(64), 0) }
        assertFailsWith<IllegalArgumentException> { sealer.seal(input, -1, 5, aad, ByteArray(64), 0) }
        assertFailsWith<IllegalArgumentException> { sealer.seal(input, 0, 10, aad, ByteArray(25), 0) }
        assertFailsWith<IllegalArgumentException> { sealer.seal(input, 0, 10, aad, ByteArray(26), 1) }
        assertEquals(0, sealer.nextCounter)
        val frame = sealer.seal(input, aad)
        assertFailsWith<IllegalArgumentException> { opener.open(frame, 0, frame.size + 1, aad, ByteArray(64), 0) }
        assertFailsWith<IllegalArgumentException> { opener.open(frame, 0, frame.size, aad, ByteArray(9), 0) }
        // A programming error does not fail the stream; the frame still opens.
        assertEquals(10, opener.open(frame, 0, frame.size, aad, ByteArray(10), 0))
        // A frame shorter than a tag is a peer error: it fails the stream like the allocating form.
        val (_, opener2) = pair(AeadAlgorithm.AES_256_GCM)
        assertFailsWith<CryptoException> { opener2.open(frame, 0, 15, aad, ByteArray(0), 0) }
        assertFailsWith<CryptoException> { opener2.open(sealer.seal(input, aad), aad) }
    }

    @Test
    fun offsetFormsKeepTheLimitsAndFailClosed() {
        val aead = crypto.aead(AeadAlgorithm.CHACHA20_POLY1305, key)
        val sealer = FrameCipher(aead, 2, FrameCipher.Mode.SEAL, maxPlaintextBytes = 10)
        val opener = FrameCipher(aead, 2, FrameCipher.Mode.OPEN, maxPlaintextBytes = 10)
        val buffer = ByteArray(64)
        val n = sealer.seal(buffer, 0, 8, aad, buffer, 0)
        assertFailsWith<FrameLimitException> { sealer.seal(buffer, 0, 3, aad, buffer, 32) }
        assertEquals(8, opener.open(buffer, 0, n, aad, ByteArray(8), 0))
        val corrupt = sealer.seal(ByteArray(2), aad).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException> { opener.open(corrupt, 0, corrupt.size, aad, ByteArray(2), 0) }
        assertFailsWith<CryptoException> { opener.open(ByteArray(18), aad) }
    }

    @Test
    fun truncatedFramesRaiseCryptoException() {
        for (algorithm in AeadAlgorithm.entries) {
            val frame = FrameCipher.sealer(crypto, algorithm, key, 2).seal(ByteArray(4), aad)
            for (length in 0 until frame.size) {
                val opener = FrameCipher.opener(crypto, algorithm, key, 2)
                assertFailsWith<CryptoException>("length $length") { opener.open(frame.copyOf(length), aad) }
            }
        }
    }

    @Test
    fun frameLimitStopsSealingAndOpening() {
        val aead = crypto.aead(AeadAlgorithm.AES_256_GCM, key)
        val sealer = FrameCipher(aead, 2, FrameCipher.Mode.SEAL, maxFrames = 3)
        val opener = FrameCipher(aead, 2, FrameCipher.Mode.OPEN, maxFrames = 3)
        repeat(3) { opener.open(sealer.seal(ByteArray(1), aad), aad) }
        assertEquals(0, sealer.framesRemaining)
        assertFailsWith<FrameLimitException> { sealer.seal(ByteArray(1), aad) }
        assertFailsWith<FrameLimitException> { opener.open(ByteArray(17), aad) }
        assertEquals(3, sealer.nextCounter, "a refused seal does not consume a counter")
    }

    @Test
    fun byteLimitStopsSealing() {
        val aead = crypto.aead(AeadAlgorithm.CHACHA20_POLY1305, key)
        val sealer = FrameCipher(aead, 2, FrameCipher.Mode.SEAL, maxPlaintextBytes = 10)
        val opener = FrameCipher(aead, 2, FrameCipher.Mode.OPEN, maxPlaintextBytes = 10)
        opener.open(sealer.seal(ByteArray(8), aad), aad)
        assertEquals(2, sealer.bytesRemaining)
        assertFailsWith<FrameLimitException> { sealer.seal(ByteArray(3), aad) }
        opener.open(sealer.seal(ByteArray(2), aad), aad)
        assertFailsWith<FrameLimitException> { sealer.seal(ByteArray(1), aad) }
        assertEquals(0, opener.bytesRemaining)
    }

    @Test
    fun documentedLimits() {
        assertEquals(1L shl 32, FrameCipher.MAX_FRAMES)
        assertEquals(256L * 1024 * 1024 * 1024, FrameCipher.MAX_PLAINTEXT_BYTES)
        val sealer = FrameCipher.sealer(crypto, AeadAlgorithm.AES_256_GCM, key, 0)
        assertEquals(FrameCipher.MAX_FRAMES, sealer.framesRemaining)
        assertEquals(FrameCipher.MAX_PLAINTEXT_BYTES, sealer.bytesRemaining)
    }

    @Test
    fun wrongModeIsAProgrammingError() {
        val (sealer, opener) = pair(AeadAlgorithm.AES_256_GCM)
        assertFailsWith<IllegalStateException> { sealer.open(ByteArray(16), aad) }
        assertFailsWith<IllegalStateException> { opener.seal(ByteArray(1), aad) }
        assertFailsWith<IllegalArgumentException> { FrameCipher.sealer(crypto, AeadAlgorithm.AES_256_GCM, key, -1) }
        assertFailsWith<CryptoException> { FrameCipher.sealer(crypto, AeadAlgorithm.AES_256_GCM, ByteArray(16), 0) }
    }

    @Test
    fun chachaIsUsedWhenOnePeerLacksAesHardware() {
        val ex =
            HandshakeHarness.run(
                HandshakeHarness.initiator(),
                HandshakeHarness.responder(
                    info = LocalPeerInfo(HandshakeHarness.INFO_B.caps, "Old phone", 0, AeadAlgorithm.CHACHA20_POLY1305),
                ),
            )
        val sealer = ex.initiator.frameSender(0)
        assertEquals(AeadAlgorithm.CHACHA20_POLY1305, sealer.algorithm)
        val frame = sealer.seal(ByteArray(5), aad)
        val direct = crypto.aead(AeadAlgorithm.CHACHA20_POLY1305, ex.responder.receiveKey).open(FrameCipher.nonce(0, 0), frame, aad)
        assertContentEquals(ByteArray(5), direct)
        assertFalse(ex.initiator.sendKey.contentEquals(ex.initiator.receiveKey))
    }

    /** Records every nonce passed to [delegate]'s seal, in a synchronized list. */
    private class RecordingAead(
        private val delegate: Aead,
    ) : Aead by delegate {
        val nonces: MutableList<ByteBuffer> = Collections.synchronizedList(ArrayList())

        override fun seal(
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            nonces += ByteBuffer.wrap(nonce.copyOf())
            return delegate.seal(nonce, plaintext, aad)
        }
    }

    /** A provider whose AEADs record `key ‖ nonce` of every seal. */
    private class RecordingProvider(
        private val delegate: CryptoProvider,
    ) : CryptoProvider by delegate {
        /** Every recorded seal as `hex(key) ‖ hex(nonce)`, in order, duplicates kept. */
        val sealed: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun aead(
            algorithm: AeadAlgorithm,
            key: ByteArray,
        ): Aead {
            val inner = delegate.aead(algorithm, key)
            val keyHex = key.toHex()
            return object : Aead by inner {
                override fun seal(
                    nonce: ByteArray,
                    plaintext: ByteArray,
                    aad: ByteArray,
                ): ByteArray {
                    sealed += keyHex + nonce.toHex()
                    return inner.seal(nonce, plaintext, aad)
                }
            }
        }
    }
}
