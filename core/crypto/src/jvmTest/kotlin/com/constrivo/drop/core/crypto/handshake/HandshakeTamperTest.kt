package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_B
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_C
import com.constrivo.drop.core.crypto.cbor.RawCbor
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.initiator
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.responder
import com.constrivo.drop.core.crypto.hexToBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every field of every handshake message is covered by a check (architecture §6.2; N1, N2): changing it makes the
 * handshake fail, and malformed input only ever raises [HandshakeException].
 */
class HandshakeTamperTest {
    private fun flip(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

    private val helloTamperings: Map<String, (HelloMessage) -> HelloMessage> =
        mapOf(
            "identity_pk" to { it.copy(identityKey = IDENTITY_C.publicKey) },
            "commitment" to { it.copy(commitment = flip(it.commitment)) },
            "caps (stripped Wi-Fi Direct bit)" to { it.copy(caps = it.caps xor 0x0008) },
            "nickname" to { it.copy(nickname = "Ananya's Pixel") },
            "platform" to { it.copy(platform = 2) },
            "aead (downgrade)" to { it.copy(aead = AeadAlgorithm.CHACHA20_POLY1305.wireId) },
            "trusted proof added" to { it.copy(trustedProof = ByteArray(32) { 1 }) },
        )

    @Test
    fun n2_tamperedHelloFieldIsDetectedByTheInitiator() {
        for ((field, tamper) in helloTamperings) {
            val a = initiator()
            val hello = a.start()
            val tampered = tamper(HelloMessage.decode(hello)).encode()
            val ack = responder().receiveHello(tampered)
            val e = assertFailsWith<HandshakeException>(field) { a.receiveHelloAck(ack) }
            assertEquals(HandshakeFailure.BAD_SIGNATURE, e.reason, field)
        }
    }

    @Test
    fun n2_tamperedHelloFieldIsDetectedByTheResponder() {
        for ((field, tamper) in helloTamperings) {
            // The attacker hands B a tampered Hello, and hands A an answer from an honest B' that saw the original.
            val hello = initiator().start()
            val victim = responder()
            victim.receiveHello(tamper(HelloMessage.decode(hello)).encode())
            val honestAck = responder().receiveHello(hello)
            val reveal = initiator().also { it.start() }.receiveHelloAck(honestAck)
            val e = assertFailsWith<HandshakeException>(field) { victim.receiveHelloReveal(reveal) }
            val expected = if (field == "commitment") HandshakeFailure.COMMITMENT_MISMATCH else HandshakeFailure.BAD_SIGNATURE
            assertEquals(expected, e.reason, field)
        }
    }

    @Test
    fun n2_strippedTrustedProofIsDetected() {
        val secret = HandshakeTest.PAIRING_SECRET
        val a = initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, secret))
        val hello = a.start()
        val stripped = HelloMessage.decode(hello).copy(trustedProof = null).encode()
        val ack = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, secret)).receiveHello(stripped)
        assertEquals(HandshakeFailure.BAD_SIGNATURE, assertFailsWith<HandshakeException> { a.receiveHelloAck(ack) }.reason)
    }

    @Test
    fun n2_tamperedHelloAckFieldIsRejected() {
        val otherSession = HandshakeHarness.run(initiator(randomness = TestFixtures.fixedRandomness("x")), responder())
        val tamperings: Map<String, Pair<(HelloAckMessage) -> HelloAckMessage, HandshakeFailure>> =
            mapOf(
                "version" to ({ m: HelloAckMessage -> m.copy(version = 0) } to HandshakeFailure.VERSION_MISMATCH),
                "identity_pk" to ({ m: HelloAckMessage -> m.copy(identityKey = IDENTITY_C.publicKey) } to HandshakeFailure.BAD_SIGNATURE),
                "eph_pk" to
                    (
                        { m: HelloAckMessage -> m.copy(ephemeralKey = TestFixtures.x25519From("mitm").publicKey) } to
                            HandshakeFailure.BAD_SIGNATURE
                    ),
                "nonce" to ({ m: HelloAckMessage -> m.copy(nonce = flip(m.nonce)) } to HandshakeFailure.BAD_SIGNATURE),
                "caps" to ({ m: HelloAckMessage -> m.copy(caps = 0) } to HandshakeFailure.BAD_SIGNATURE),
                "nickname" to ({ m: HelloAckMessage -> m.copy(nickname = "ThinkPad2") } to HandshakeFailure.BAD_SIGNATURE),
                "platform" to ({ m: HelloAckMessage -> m.copy(platform = 0) } to HandshakeFailure.BAD_SIGNATURE),
                "aead (downgrade)" to
                    ({ m: HelloAckMessage -> m.copy(aead = AeadAlgorithm.CHACHA20_POLY1305.wireId) } to HandshakeFailure.BAD_SIGNATURE),
                "trust ack added" to ({ m: HelloAckMessage -> m.copy(trustAck = ByteArray(32)) } to HandshakeFailure.BAD_SIGNATURE),
                "signature" to ({ m: HelloAckMessage -> m.copy(signature = flip(m.signature!!)) } to HandshakeFailure.BAD_SIGNATURE),
                "signature from another session" to
                    (
                        { m: HelloAckMessage -> m.copy(signature = HelloAckMessage.decode(otherSession.helloAck).signature) } to
                            HandshakeFailure.BAD_SIGNATURE
                    ),
            )
        for ((field, entry) in tamperings) {
            val (tamper, expected) = entry
            val a = initiator()
            val ack = responder().receiveHello(a.start())
            val tampered = tamper(HelloAckMessage.decode(ack)).encode()
            val e = assertFailsWith<HandshakeException>(field) { a.receiveHelloAck(tampered) }
            assertEquals(expected, e.reason, field)
        }
    }

    @Test
    fun n1_tamperedHelloRevealFieldIsRejected() {
        val tamperings: Map<String, Pair<(HelloRevealMessage) -> HelloRevealMessage, HandshakeFailure>> =
            mapOf(
                "eph_pk" to
                    (
                        { m: HelloRevealMessage -> m.copy(ephemeralKey = TestFixtures.x25519From("late").publicKey) } to
                            HandshakeFailure.COMMITMENT_MISMATCH
                    ),
                "nonce" to ({ m: HelloRevealMessage -> m.copy(nonce = flip(m.nonce)) } to HandshakeFailure.COMMITMENT_MISMATCH),
                "signature" to ({ m: HelloRevealMessage -> m.copy(signature = flip(m.signature!!)) } to HandshakeFailure.BAD_SIGNATURE),
            )
        for ((field, entry) in tamperings) {
            val (tamper, expected) = entry
            val a = initiator()
            val b = responder()
            val reveal = a.receiveHelloAck(b.receiveHello(a.start()))
            val tampered = tamper(HelloRevealMessage.decode(reveal)).encode()
            val e = assertFailsWith<HandshakeException>(field) { b.receiveHelloReveal(tampered) }
            assertEquals(expected, e.reason, field)
        }

        // A reveal that opens the commitment but is signed by another identity.
        val a = initiator()
        val b = responder()
        val hello = a.start()
        val ack = b.receiveHello(hello)
        val reveal = HelloRevealMessage.decode(a.receiveHelloAck(ack))
        val forged = resignReveal(hello, ack, reveal, signer = IDENTITY_C)
        assertEquals(HandshakeFailure.BAD_SIGNATURE, assertFailsWith<HandshakeException> { b.receiveHelloReveal(forged) }.reason)
    }

    @Test
    fun everySingleBitFlipInHelloBreaksTheHandshake() {
        val hello = HandshakeTest.GOLDEN_HELLO.hexToBytes()
        for (bit in 0 until hello.size * 8) {
            val a = initiator()
            assertTrue(a.start().contentEquals(hello))
            val b = responder()
            val completed =
                try {
                    val ack = b.receiveHello(flipBit(hello, bit))
                    b.receiveHelloReveal(a.receiveHelloAck(ack))
                    true
                } catch (e: HandshakeException) {
                    false
                }
            assertFalse(completed, "bit $bit of Hello")
        }
    }

    @Test
    fun everySingleBitFlipInHelloAckIsRejected() {
        val ack = HandshakeTest.GOLDEN_HELLO_ACK.hexToBytes()
        for (bit in 0 until ack.size * 8) {
            val a = initiator()
            a.start()
            assertFailsWith<HandshakeException>("bit $bit of HelloAck") { a.receiveHelloAck(flipBit(ack, bit)) }
        }
    }

    @Test
    fun everySingleBitFlipInHelloRevealIsRejected() {
        val reveal = HandshakeTest.GOLDEN_HELLO_REVEAL.hexToBytes()
        for (bit in 0 until reveal.size * 8) {
            val b = responder()
            b.receiveHello(HandshakeTest.GOLDEN_HELLO.hexToBytes())
            assertFailsWith<HandshakeException>("bit $bit of HelloReveal") { b.receiveHelloReveal(flipBit(reveal, bit)) }
        }
    }

    @Test
    fun malformedInputOnlyRaisesHandshakeException() {
        val random = Random(20260923)
        val samples = mutableListOf<ByteArray>()
        samples += ByteArray(0)
        samples += ByteArray(HandshakeLimits.MAX_MESSAGE_SIZE + 1)
        repeat(500) { samples += random.nextBytes(random.nextInt(0, 300)) }
        for (golden in listOf(HandshakeTest.GOLDEN_HELLO, HandshakeTest.GOLDEN_HELLO_ACK, HandshakeTest.GOLDEN_HELLO_REVEAL)) {
            val bytes = golden.hexToBytes()
            for (length in 0 until bytes.size) samples += bytes.copyOf(length)
            samples += bytes + byteArrayOf(0)
            repeat(300) { samples += mutate(bytes, random) }
        }
        for (sample in samples) {
            expectOnlyHandshakeException { responder().receiveHello(sample) }
            expectOnlyHandshakeException { initiator().also { it.start() }.receiveHelloAck(sample) }
            expectOnlyHandshakeException {
                responder().also { it.receiveHello(HandshakeTest.GOLDEN_HELLO.hexToBytes()) }.receiveHelloReveal(sample)
            }
        }
    }

    @Test
    fun nonCanonicalEncodingsAreRejected() {
        val hello = HelloMessage.decode(HandshakeTest.GOLDEN_HELLO.hexToBytes())
        val cases =
            mapOf(
                "unknown key" to
                    RawCbor.encode(
                        RawCbor.map(
                            1 to 1,
                            2 to hello.identityKey,
                            3 to hello.commitment,
                            4 to hello.caps,
                            5 to hello.nickname,
                            6 to hello.platform,
                            7 to hello.aead,
                            9 to 0,
                        ),
                    ),
                "explicit null proof" to
                    RawCbor.encode(
                        RawCbor.map(
                            1 to 1,
                            2 to hello.identityKey,
                            3 to hello.commitment,
                            4 to hello.caps,
                            5 to hello.nickname,
                            6 to hello.platform,
                            7 to hello.aead,
                            8 to null,
                        ),
                    ),
                "keys out of order" to
                    RawCbor.encode(
                        RawCbor.map(
                            2 to hello.identityKey,
                            1 to 1,
                            3 to hello.commitment,
                            4 to hello.caps,
                            5 to hello.nickname,
                            6 to hello.platform,
                            7 to hello.aead,
                        ),
                    ),
                "missing field" to
                    RawCbor.encode(
                        RawCbor.map(
                            1 to 1,
                            2 to hello.identityKey,
                            3 to hello.commitment,
                            4 to hello.caps,
                            5 to hello.nickname,
                            6 to hello.platform,
                        ),
                    ),
                "non-minimal integer" to
                    HandshakeTest.GOLDEN_HELLO.hexToBytes().let {
                        // "01 01" (version 1) → "01 18 01".
                        byteArrayOf(it[0], 0x01, 0x18, 0x01) + it.copyOfRange(3, it.size)
                    },
                "text as bytes" to
                    RawCbor.encode(
                        RawCbor.map(
                            1 to 1,
                            2 to hello.identityKey,
                            3 to hello.commitment,
                            4 to hello.caps,
                            5 to hello.nickname.encodeToByteArray(),
                            6 to hello.platform,
                            7 to hello.aead,
                        ),
                    ),
                "top-level array" to RawCbor.encode(listOf(1, 2, 3)),
            )
        for ((name, bytes) in cases) {
            val e = assertFailsWith<HandshakeException>(name) { responder().receiveHello(bytes) }
            assertEquals(HandshakeFailure.MALFORMED_MESSAGE, e.reason, name)
        }
    }

    private fun expectOnlyHandshakeException(block: () -> Unit) {
        try {
            block()
        } catch (e: HandshakeException) {
            // expected
        } catch (e: Exception) {
            fail("expected HandshakeException, got $e")
        }
    }

    private fun flipBit(
        bytes: ByteArray,
        bit: Int,
    ): ByteArray = bytes.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }

    private fun mutate(
        bytes: ByteArray,
        random: Random,
    ): ByteArray {
        val out = bytes.toMutableList()
        repeat(random.nextInt(1, 4)) {
            when (random.nextInt(3)) {
                0 -> if (out.isNotEmpty()) out[random.nextInt(out.size)] = random.nextInt(256).toByte()
                1 -> out.add(random.nextInt(out.size + 1), random.nextInt(256).toByte())
                else -> if (out.isNotEmpty()) out.removeAt(random.nextInt(out.size))
            }
        }
        return out.toByteArray()
    }
}
