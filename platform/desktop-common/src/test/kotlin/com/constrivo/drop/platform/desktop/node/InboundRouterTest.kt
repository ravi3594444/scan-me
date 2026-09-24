package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.handshake.HandshakeInitiator
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FrameCodec
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.TransferPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InboundRouterTest {
    private val crypto = JcaCryptoProvider()

    private fun identity(): IdentityKey {
        val pair = crypto.generateEd25519()
        return object : IdentityKey {
            override val publicKey: ByteArray = pair.publicKey

            override fun sign(message: ByteArray): ByteArray = crypto.ed25519Sign(pair.privateKey, message)
        }
    }

    private fun hello(identity: IdentityKey): ByteArray =
        HandshakeInitiator(crypto, identity, LocalPeerInfo(0x1000, "Alice", 1, AeadAlgorithm.AES_256_GCM)).start()

    @Test
    fun `the identity claimed by a real Hello is found`() {
        val me = identity()
        assertContentEquals(me.publicKey, HelloPeek.identityOf(hello(me)))
    }

    @Test
    fun `payloads that do not start like a Hello give no identity`() {
        val good = hello(identity())
        assertNull(HelloPeek.identityOf(ByteArray(0)))
        assertNull(HelloPeek.identityOf(byteArrayOf(0xA0.toByte())))
        assertNull(HelloPeek.identityOf(good.copyOf(10)), "cut inside the key")
        assertNull(HelloPeek.identityOf(good.copyOf().also { it[0] = 0x80.toByte() }), "an array, not a map")
        assertNull(HelloPeek.identityOf(good.copyOf().also { it[1] = 0x02 }), "keys out of order")
        assertNull(HelloPeek.identityOf(good.copyOf().also { it[2] = 0x60 }), "a text version")
        assertNull(HelloPeek.identityOf(good.copyOf().also { it[4] = 0x57 }), "a key of the wrong length")
        assertNull(HelloPeek.identityOf(byteArrayOf(0xA7.toByte(), 0x01, 0x1C, 0x02)), "a reserved length")
        repeat(200) { seed -> HelloPeek.identityOf(kotlin.random.Random(seed).nextBytes(seed % 60)) }
    }

    @Test
    fun `the first frame is read exactly and replayed in front of the rest of the stream`() =
        runBlocking<Unit> {
            withTimeout(5_000) {
                val (a, b) = InMemoryDataChannel.pair(LinkKind.LAN)
                val me = identity()
                val payload = hello(me)
                val frame = FrameCodec.encode(FrameType.HELLO, payload)
                a.write(frame + byteArrayOf(9, 8, 7))
                val peeked = HelloPeek.read(b)
                assertContentEquals(frame, peeked.frame)
                assertContentEquals(me.publicKey, peeked.claimedIdentity)
                val replay = ReplayChannel(b, peeked.frame)
                assertEquals(LinkKind.LAN, replay.kind)
                val all = ByteArray(frame.size + 3)
                var read = 0
                while (read < all.size) read += replay.read(all, read, minOf(7, all.size - read))
                assertContentEquals(frame + byteArrayOf(9, 8, 7), all)
                assertEquals(0, replay.read(all, 0, 0))
                replay.write(byteArrayOf(1))
                val back = ByteArray(1)
                a.read(back, 0, 1)
                assertEquals(1, back[0].toInt())
                replay.close()
            }
        }

    @Test
    fun `a first frame that is not a Hello, or a stream that ends, is refused`() =
        runBlocking<Unit> {
            withTimeout(5_000) {
                val (a, b) = InMemoryDataChannel.pair(LinkKind.LAN)
                a.write(FrameCodec.encode(FrameType.HELLO_ACK, ByteArray(4)))
                assertFailsWith<ProtocolException> { HelloPeek.read(b) }
                val (c, d) = InMemoryDataChannel.pair(LinkKind.LAN)
                c.write(byteArrayOf(0, 0))
                c.close()
                assertFailsWith<EOFException> { HelloPeek.read(d) }
                val (e, f) = InMemoryDataChannel.pair(LinkKind.LAN)
                e.write(byteArrayOf(0, 0, 0x7F, 0, 1))
                assertFailsWith<ProtocolException> { HelloPeek.read(f) }
            }
        }

    @Test
    fun `reconnects go to the oldest receiver waiting for that identity, others are not taken`() =
        runBlocking<Unit> {
            withTimeout(5_000) {
                val waiters = ReconnectWaiters()
                val alice = identity().publicKey
                val (c1, _) = InMemoryDataChannel.pair(LinkKind.LAN)
                assertFalse(waiters.offer(alice, c1), "nobody waits")
                assertFalse(waiters.offer(null, c1))
                val first = async { waiters.sourceFor(alice).next() }
                val second = async { waiters.sourceFor(alice).next() }
                while (waiters.size < 2) kotlinx.coroutines.yield()
                assertFalse(waiters.offer(identity().publicKey, c1), "another identity")
                assertTrue(waiters.offer(alice, c1))
                assertSame(c1, first.await())
                val (c2, _) = InMemoryDataChannel.pair(LinkKind.LAN)
                assertTrue(waiters.offer(alice, c2))
                assertSame(c2, second.await())
                assertEquals(0, waiters.size)
                val cancelled = async { waiters.sourceFor(alice).next() }
                while (waiters.size < 1) kotlinx.coroutines.yield()
                cancelled.cancel()
                cancelled.join()
                assertEquals(0, waiters.size, "a cancelled wait leaves no slot behind")
            }
        }

    @Test
    fun `engine phases map to the stages the UI names`() {
        assertEquals(NodeStage.AWAITING_ACCEPT, NodeStage.of(TransferPhase.OFFERED))
        assertEquals(NodeStage.TRANSFERRING, NodeStage.of(TransferPhase.STREAMING_WIFI))
        assertEquals(NodeStage.TRANSFERRING, NodeStage.of(TransferPhase.ACCEPTED))
        assertEquals(NodeStage.VERIFYING, NodeStage.of(TransferPhase.VERIFYING))
        assertEquals(NodeStage.RECONNECTING, NodeStage.of(TransferPhase.RECONNECTING))
        assertEquals(NodeStage.WAITING_FOR_PEER, NodeStage.of(TransferPhase.PARKED))
        assertEquals(NodeStage.DONE, NodeStage.of(TransferPhase.DONE))
        assertEquals(NodeStage.FAILED, NodeStage.of(TransferPhase.FAILED))
        assertEquals(NodeStage.DECLINED, NodeStage.of(TransferPhase.CANCELLED, declineReason = DeclineReason.USER))
        assertEquals(NodeStage.NO_ANSWER, NodeStage.of(TransferPhase.CANCELLED, declineReason = DeclineReason.TIMEOUT))
        assertEquals(NodeStage.NO_ANSWER, NodeStage.of(TransferPhase.CANCELLED, cancelReason = CancelReason.TIMEOUT, everAccepted = false))
        assertEquals(NodeStage.CANCELLED, NodeStage.of(TransferPhase.CANCELLED, cancelReason = CancelReason.TIMEOUT, everAccepted = true))
        assertEquals(NodeStage.CANCELLED, NodeStage.of(TransferPhase.CANCELLED, cancelReason = CancelReason.USER))
        assertTrue(NodeStage.entries.filter { it.isFinal }.containsAll(listOf(NodeStage.DONE, NodeStage.DECLINED, NodeStage.NO_ANSWER)))
    }
}
