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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `the gate admits a bounded number of unauthenticated connections, per host and in total`() {
        val gate = InboundGate(maxTotal = 3, maxPerHost = 2)
        val a1 = assertNotNull(gate.tryEnter("10.0.0.1"))
        val a2 = assertNotNull(gate.tryEnter("10.0.0.1"))
        assertNull(gate.tryEnter("10.0.0.1"), "a third connection from one host waits for a free place")
        val b1 = assertNotNull(gate.tryEnter("10.0.0.2"))
        assertNull(gate.tryEnter("10.0.0.3"), "the gate is full")
        assertEquals(3, gate.size)
        a1.close()
        a1.close() // a place is given back once
        assertEquals(2, gate.size)
        val a3 = assertNotNull(gate.tryEnter("10.0.0.1"))
        assertNull(gate.tryEnter(null), "unknown hosts share the limits too")
        for (t in listOf(a2, a3, b1)) t.close()
        assertEquals(0, gate.size)
        assertNotNull(gate.tryEnter(null)).close()
        assertFailsWith<IllegalArgumentException> { InboundGate(0, 1) }
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
