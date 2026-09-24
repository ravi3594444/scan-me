package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeFailure
import com.constrivo.drop.core.protocol.AdvertisingSecret
import com.constrivo.drop.core.protocol.ChunkHeader
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.Heartbeat
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.SessionRole
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.hash.xxh3ChunkHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val TID = TransferId(ByteArray(TransferId.SIZE) { 9 })

/** Records every byte written, so a test can replay a frame on another connection. */
private class RecordingChannel(
    private val delegate: DataChannel,
) : DataChannel by delegate {
    val written = ByteArrayOutputStream()

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        synchronized(written) { written.write(buffer, offset, length) }
        delegate.write(buffer, offset, length)
    }
}

/** WP2 handshake on the wire, the Finished exchange, `StreamOpen` streams (S7) and blocked chunk sealing (§7.1). */
class SessionTest {
    private val sender = TestSupport.sessionConfig("Sender")
    private val receiver = TestSupport.sessionConfig("Receiver")

    @Test
    fun `the handshake authenticates both identities and control frames flow both ways`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val (a, b) = TestSupport.handshake(sender, receiver, TestSupport.memoryPair(LinkKind.BLUETOOTH))
                assertEquals(SessionRole.INITIATOR, a.role)
                assertEquals(SessionRole.RESPONDER, b.role)
                assertContentEquals(receiver.identity.publicKey, a.peerIdentityKey)
                assertContentEquals(sender.identity.publicKey, b.peerIdentityKey)
                assertTrue(a.primary.isPrimary)
                assertEquals(LinkKind.BLUETOOTH, a.primary.kind)

                a.primary.sendControl(Heartbeat(t = 1234))
                val header = b.primary.readHeader()!!
                assertEquals(FrameType.CONTROL, header.type)
                assertEquals(Heartbeat(t = 1234), b.primary.readControl(header))
                b.primary.sendControl(Heartbeat(t = 5, echo = 1234))
                assertEquals(Heartbeat(t = 5, echo = 1234), a.primary.readControl(a.primary.readHeader()!!))
                a.close()
                b.close()
            }
        }

    @Test
    fun `an expected identity that does not answer fails the handshake`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val (x, y) = TestSupport.memoryPair()
                val impostor = TestSupport.identity().publicKey
                val responder = scope.async { runCatching { SessionHandshake.respond(y, receiver) } }
                val failure = assertFailsWith<HandshakeException> { SessionHandshake.initiate(x, sender, sender.expectedPeer(impostor)) }
                assertEquals(HandshakeFailure.PEER_IDENTITY_MISMATCH, failure.reason)
                assertTrue(responder.await().isFailure, "the responder does not get a session either")
                scope.cancel()
            }
        }

    @Test
    fun `a silent or closed channel ends in a SessionException`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val quick =
                    TestSupport.sessionConfig("Quick").let {
                        SessionConfig(it.crypto, it.identity, it.local, it.guard, timeoutMillis = 300)
                    }
                val (x, _) = TestSupport.memoryPair()
                assertFailsWith<SessionException> { SessionHandshake.respond(x, quick) }
                val (p, q) = TestSupport.memoryPair()
                q.close()
                assertFailsWith<SessionException> { SessionHandshake.initiate(p, quick) }
            }
        }

    @Test
    fun `a TrustShare named by the config is the first control message after the handshake`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val share = TrustShare(AdvertisingSecret(ByteArray(AdvertisingSecret.SIZE) { 4 }), generation = 2)
                val sharing = SessionConfig(receiver.crypto, receiver.identity, receiver.local, receiver.guard, trustShare = { share })
                val (a, b) = TestSupport.handshake(sender, sharing, TestSupport.memoryPair())
                assertEquals(share, a.primary.readControl(a.primary.readHeader()!!))
                a.close()
                b.close()
            }
        }

    @Test
    fun `StreamOpen streams authenticate, carry blocked chunk frames and cannot be replayed`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val (a, b) = TestSupport.handshake(sender, receiver, TestSupport.memoryPair())
                val (x, y) = TestSupport.memoryPair()
                val recording = RecordingChannel(x)
                val opening = async { a.openStream(recording, LinkKind.LAN, TID, StreamPurpose.DATA, generation = 3) }
                val accepted = b.acceptStream(y, LinkKind.LAN)
                val opened = opening.await()
                val open = accepted.open!!
                assertEquals(TID, open.transferId)
                assertEquals(3, open.generation)
                assertEquals(StreamPurpose.DATA, open.purpose)
                assertEquals(opened.chunkStreamId, accepted.chunkStreamId)
                assertEquals(0, opened.chunkStreamId % 2, "the initiator opens even stream ids")
                assertTrue(opened.chunkStreamId !in SecureSession.PRIMARY_STREAMS)
                val streamOpenBytes = synchronized(recording.written) { recording.written.toByteArray() }

                // A 200 KiB chunk frame: four 64 KiB AEAD blocks, each with its own tag.
                val payload = TestSupport.randomBytes(200 * 1024, 5)
                val hash = xxh3ChunkHasher().hash(payload, 0, payload.size)
                val header = ChunkHeader(TID, fileIndex = 0, chunkIndex = 1, payloadLength = payload.size, hash = hash)
                val plaintext = header.encode() + payload
                val writing = async { opened.sendChunk(plaintext, 0, plaintext.size) }
                val frame = accepted.readHeader()!!
                assertEquals(FrameType.CHUNK, frame.type)
                assertEquals(plaintext.size + 16 * 4, frame.payloadLength)
                val view = accepted.readChunk(frame, ByteArray(SecureConnection.MAX_CHUNK_FRAME))
                writing.await()
                assertEquals(header, view.header)
                assertContentEquals(payload, view.copyPayload())

                // The same StreamOpen replayed on a new connection is refused (S7).
                val (r1, r2) = TestSupport.memoryPair()
                r1.write(streamOpenBytes)
                assertFailsWith<ProtocolException> { b.acceptStream(r2, LinkKind.LAN) }
                a.close()
                b.close()
            }
        }

    @Test
    fun `a flipped bit in any chunk block fails authentication`() =
        runBlocking<Unit> {
            withTimeout(20_000) {
                val (a, b) = TestSupport.handshake(sender, receiver, TestSupport.memoryPair())
                val (x, y) = TestSupport.memoryPair()
                val tamper = TamperingChannel(x, flipAt = 5 + 3 * (64 * 1024 + 16) + 100)
                val opening = async { a.openStream(tamper, LinkKind.LAN, TID, StreamPurpose.DATA, generation = 0) }
                val accepted = b.acceptStream(y, LinkKind.LAN)
                val opened = opening.await()
                tamper.armed = true
                val payload = TestSupport.randomBytes(300 * 1024, 6)
                val header = ChunkHeader(TID, 0, 0, payloadLength = payload.size, hash = xxh3ChunkHasher().hash(payload, 0, payload.size))
                val plaintext = header.encode() + payload
                val writing = async { opened.sendChunk(plaintext, 0, plaintext.size) }
                val frame = accepted.readHeader()!!
                assertFailsWith<ProtocolException> { accepted.readChunk(frame, ByteArray(SecureConnection.MAX_CHUNK_FRAME)) }
                writing.await()
                a.close()
                b.close()
            }
        }

    /** Flips one bit at byte [flipAt] of what is written once [armed]. */
    private class TamperingChannel(
        private val delegate: DataChannel,
        private val flipAt: Int,
    ) : DataChannel by delegate {
        @Volatile
        var armed = false
        private var seen = 0

        override suspend fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (!armed) return delegate.write(buffer, offset, length)
            val copy = buffer.copyOfRange(offset, offset + length)
            val at = flipAt - seen
            if (at in 0 until length) copy[at] = (copy[at].toInt() xor 0x01).toByte()
            seen += length
            delegate.write(copy, 0, length)
        }
    }
}

/** Endpoint selection behind the identity check (WP1–WP3 carry-forward). */
class EndpointDialerTest {
    private val sender = TestSupport.sessionConfig("Sender")
    private val receiver = TestSupport.sessionConfig("Receiver")

    @Test
    fun `failing, silent and impostor endpoints are skipped until the real peer answers`() =
        runBlocking<Unit> {
            withTimeout(30_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val impostorConfig = TestSupport.sessionConfig("Impostor")
                val (i1, i2) = TestSupport.memoryPair()
                scope.async { runCatching { SessionHandshake.respond(i2, impostorConfig) } }
                val (r1, r2) = TestSupport.memoryPair()
                val real = scope.async { SessionHandshake.respond(r2, receiver) }
                val candidates =
                    listOf(
                        DialCandidate("refused") { throw IOException("connection refused") },
                        DialCandidate("silent") { awaitCancellation() },
                        DialCandidate("impostor") { i1 },
                        DialCandidate("real") { r1 },
                    )
                val session =
                    EndpointDialer.dial(candidates, perCandidateMillis = 200) { channel ->
                        SessionHandshake.initiate(channel, sender, sender.expectedPeer(receiver.identity.publicKey))
                    }
                assertContentEquals(receiver.identity.publicKey, session.peerIdentityKey)
                real.await().close()
                session.close()

                val failure =
                    assertFailsWith<DialException> {
                        EndpointDialer.dial(candidates.take(2), perCandidateMillis = 100) { SessionHandshake.initiate(it, sender) }
                    }
                assertEquals(listOf("refused", "silent"), failure.failures.map { it.first })
                assertIs<IOException>(failure.failures[0].second)
                assertFailsWith<DialException> { EndpointDialer.dial(emptyList()) { error("never") } }
                scope.cancel()
            }
        }

    @Test
    fun `LAN endpoints from the authenticated offer come first, without duplicates`() {
        val offer =
            Offer(
                transferId = TID,
                fileCount = 1,
                totalBytes = 1,
                bundleCount = 1,
                linkOptions =
                    listOf(
                        LinkOption(LinkKind.P2P),
                        LinkOption(LinkKind.LAN, address = "192.168.1.20", port = 40_000),
                        LinkOption(LinkKind.LAN, address = "192.168.1.21"),
                    ),
            )
        val discovered = listOf(Endpoint("192.168.1.30", 40_000), Endpoint("192.168.1.20", 40_000))
        assertEquals(
            listOf(Endpoint("192.168.1.20", 40_000), Endpoint("192.168.1.30", 40_000)),
            EndpointDialer.lanEndpoints(offer, discovered),
        )
        assertEquals(discovered, EndpointDialer.lanEndpoints(null, discovered))
        assertEquals("[fe80::1]:9", Endpoint("fe80::1", 9).toString())
    }
}
