package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.RawKeyPair
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.EPHEMERAL_A
import com.constrivo.drop.core.crypto.TestFixtures.EPHEMERAL_B
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_B
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_C
import com.constrivo.drop.core.crypto.TestFixtures.NONCE_A
import com.constrivo.drop.core.crypto.TestFixtures.NONCE_B
import com.constrivo.drop.core.crypto.cbor.RawCbor
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.INFO_A
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.INFO_B
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.initiator
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.responder
import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.crypto.trust.TrustedProof
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** F-B2 handshake and F-B3 SAS (architecture §6.2; spec changes N1, N2, N3). */
class HandshakeTest {
    private val crypto = HandshakeHarness.crypto

    @Test
    fun fB2_bothRolesAgreeOnKeysSasAndAead() {
        val ex = HandshakeHarness.run()
        val a = ex.initiator
        val b = ex.responder

        assertEquals(HandshakeRole.INITIATOR, a.role)
        assertEquals(HandshakeRole.RESPONDER, b.role)
        assertContentEquals(a.sendKey, b.receiveKey)
        assertContentEquals(a.receiveKey, b.sendKey)
        assertFalse(a.sendKey.contentEquals(a.receiveKey), "directional keys must differ")
        assertEquals(a.sas, b.sas)
        assertTrue(a.sas.matches(Regex("[0-9]{6}")))
        assertEquals(AeadAlgorithm.AES_256_GCM, a.aead)
        assertEquals(a.aead, b.aead)
        assertContentEquals(a.transcriptHash, b.transcriptHash)
        assertContentEquals(a.recognitionSecret, b.recognitionSecret)

        assertContentEquals(IDENTITY_B.publicKey, a.peerIdentityKey)
        assertContentEquals(IDENTITY_A.publicKey, b.peerIdentityKey)
        assertContentEquals(IDENTITY_A.publicKey, a.localIdentityKey)
        assertContentEquals(crypto.deviceId(IDENTITY_B.publicKey), a.peerDeviceId)
        assertContentEquals(crypto.deviceId(IDENTITY_A.publicKey), b.peerDeviceId)

        assertEquals(INFO_B.caps, a.peerCaps)
        assertEquals(INFO_B.nickname, a.peerNickname)
        assertEquals(INFO_B.platform, a.peerPlatform)
        assertEquals(INFO_A.caps, b.peerCaps)
        assertEquals(INFO_A.nickname, b.peerNickname)
        assertEquals(INFO_A.platform, b.peerPlatform)
        assertEquals(HandshakeLimits.VERSION, a.peerVersion)
        assertEquals(HandshakeLimits.VERSION, b.peerVersion)
        assertFalse(a.peerProvedTrust)
        assertFalse(b.peerProvedTrust)
    }

    @Test
    fun fB2_secureRandomnessHandshakeAgrees() {
        val provider = JcaCryptoProvider()
        val a = HandshakeInitiator(provider, IDENTITY_A, INFO_A)
        val b = HandshakeResponder(provider, IDENTITY_B, INFO_B)
        val ex = HandshakeHarness.run(a, b)
        assertContentEquals(ex.initiator.sendKey, ex.responder.receiveKey)
        assertEquals(ex.initiator.sas, ex.responder.sas)
    }

    @Test
    fun aeadIsAesUnlessEitherSideLacksAesHardware() {
        val aes = AeadAlgorithm.AES_256_GCM
        val chacha = AeadAlgorithm.CHACHA20_POLY1305
        val cases =
            listOf(
                Triple(aes, aes, aes),
                Triple(aes, chacha, chacha),
                Triple(chacha, aes, chacha),
                Triple(chacha, chacha, chacha),
            )
        for ((prefA, prefB, expected) in cases) {
            val ex =
                HandshakeHarness.run(
                    initiator(info = LocalPeerInfo(INFO_A.caps, INFO_A.nickname, INFO_A.platform, prefA)),
                    responder(info = LocalPeerInfo(INFO_B.caps, INFO_B.nickname, INFO_B.platform, prefB)),
                )
            assertEquals(expected, ex.initiator.aead, "A=$prefA B=$prefB")
            assertEquals(expected, ex.responder.aead, "A=$prefA B=$prefB")
        }
    }

    @Test
    fun goldenBytes_messagesAndKeySchedule() {
        val ex = HandshakeHarness.run()
        assertEquals(GOLDEN_HELLO, ex.hello.toHex())
        assertEquals(GOLDEN_HELLO_ACK, ex.helloAck.toHex())
        assertEquals(GOLDEN_HELLO_REVEAL, ex.helloReveal.toHex())
        assertEquals(GOLDEN_TRANSCRIPT_HASH, ex.initiator.transcriptHash.toHex())
        assertEquals(GOLDEN_SAS, ex.initiator.sas)
        assertEquals(GOLDEN_KEY_A_TO_B, ex.initiator.sendKey.toHex())
        assertEquals(GOLDEN_KEY_B_TO_A, ex.initiator.receiveKey.toHex())
        assertEquals(GOLDEN_RECOGNITION_SECRET, ex.initiator.recognitionSecret.toHex())
        assertEquals(GOLDEN_FINISHED_A, ex.initiator.localFinished().toHex())
        assertEquals(GOLDEN_FINISHED_B, ex.responder.localFinished().toHex())
    }

    @Test
    fun goldenBytes_trustedProofVariant() {
        val ex = trustedExchange()
        assertEquals(GOLDEN_HELLO_WITH_PROOF, ex.hello.toHex())
        assertEquals(GOLDEN_HELLO_ACK_WITH_TRUST_ACK, ex.helloAck.toHex())
    }

    @Test
    fun goldenSignaturesVerifyOverTheDocumentedInput() {
        // Recompute what each side signs straight from the golden bytes, independently of the state machines.
        val hello = GOLDEN_HELLO.hexToBytes()
        val ack = GOLDEN_HELLO_ACK.hexToBytes()
        val reveal = GOLDEN_HELLO_REVEAL.hexToBytes()
        // HelloAck without key 10: map header 0xa9 → 0xa8, drop the trailing 0x0a 0x58 0x40 ‖ 64-byte signature.
        val ackUnsigned = byteArrayOf(0xa8.toByte()) + ack.copyOfRange(1, ack.size - 67)
        val revealUnsigned = byteArrayOf(0xa2.toByte()) + reveal.copyOfRange(1, reveal.size - 67)
        val sigB = ack.copyOfRange(ack.size - 64, ack.size)
        val sigA = reveal.copyOfRange(reveal.size - 64, reveal.size)
        assertTrue(
            crypto.ed25519Verify(IDENTITY_B.publicKey, "drop-sig-ack-v1".encodeToByteArray() + transcript(hello, ackUnsigned), sigB),
        )
        assertTrue(
            crypto.ed25519Verify(
                IDENTITY_A.publicKey,
                "drop-sig-reveal-v1".encodeToByteArray() + transcript(hello, ack, revealUnsigned),
                sigA,
            ),
        )
        assertEquals(GOLDEN_TRANSCRIPT_HASH, transcript(hello, ack, reveal).toHex())
        assertEquals(
            sha256("drop-commit-v1".encodeToByteArray() + EPHEMERAL_A.publicKey + NONCE_A).toHex(),
            hello.copyOfRange(41, 73).toHex(),
            "Hello key 3 is the commitment",
        )
    }

    @Test
    fun fB3_sasGoldenVectorFromFixedKeys() {
        val digest =
            sha256(
                "drop-sas-v1".encodeToByteArray() + IDENTITY_A.publicKey + IDENTITY_B.publicKey +
                    EPHEMERAL_A.publicKey + EPHEMERAL_B.publicKey + NONCE_A + NONCE_B,
            )
        val value =
            ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
                ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        assertEquals(GOLDEN_SAS, (value % 1_000_000).toString().padStart(6, '0'))
        assertEquals(
            GOLDEN_SAS,
            KeySchedule.sas(
                crypto,
                IDENTITY_A.publicKey,
                IDENTITY_B.publicKey,
                EPHEMERAL_A.publicKey,
                EPHEMERAL_B.publicKey,
                NONCE_A,
                NONCE_B,
            ),
        )
    }

    @Test
    fun fB3_sasIsZeroPaddedSixDigits() {
        assertEquals("000042", KeySchedule.formatSas(byteArrayOf(0, 0, 0, 42)))
        assertEquals("000000", KeySchedule.formatSas(byteArrayOf(0, 0x0F, 0x42, 0x40))) // 1,000,000
        assertEquals("967295", KeySchedule.formatSas(byteArrayOf(-1, -1, -1, -1))) // 4,294,967,295
        assertEquals("000001", KeySchedule.formatSas(byteArrayOf(0, 0x0F, 0x42, 0x41, 0x7F)))
    }

    @Test
    fun fB3_sasMatchesOnlyTheSessionCode() {
        val result = HandshakeHarness.run().initiator
        assertTrue(result.sasMatches(result.sas))
        assertTrue(result.sasMatches(" ${result.sas.take(3)} ${result.sas.drop(3)} "))
        val wrong = ((result.sas.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertFalse(result.sasMatches(wrong))
        assertFalse(result.sasMatches(""))
        assertFalse(result.sasMatches(result.sas + "0"))
    }

    @Test
    fun n2_finishedConfirmsKeysInBothDirections() {
        val ex = HandshakeHarness.run()
        assertFalse(ex.responder.isPeerConfirmed)
        ex.responder.verifyPeerFinished(ex.initiator.localFinished())
        assertTrue(ex.responder.isPeerConfirmed)
        ex.initiator.verifyPeerFinished(ex.responder.localFinished())
        assertTrue(ex.initiator.isPeerConfirmed)
        assertNotEquals(ex.initiator.localFinished().toHex(), ex.responder.localFinished().toHex())
    }

    @Test
    fun n2_wrongFinishedIsRejected() {
        val ex = HandshakeHarness.run()
        val reflected = assertFailsWith<HandshakeException> { ex.initiator.verifyPeerFinished(ex.initiator.localFinished()) }
        assertEquals(HandshakeFailure.BAD_FINISHED, reflected.reason)
        val tampered = ex.initiator.localFinished().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertEquals(
            HandshakeFailure.BAD_FINISHED,
            assertFailsWith<HandshakeException> {
                ex.responder.verifyPeerFinished(tampered)
            }.reason,
        )
        val short = ex.initiator.localFinished().copyOf(31)
        assertEquals(HandshakeFailure.BAD_FINISHED, assertFailsWith<HandshakeException> { ex.responder.verifyPeerFinished(short) }.reason)
        assertFalse(ex.responder.isPeerConfirmed)

        // A Finished from another session (different transcript) is rejected too.
        val other =
            HandshakeHarness.run(
                initiator(randomness = TestFixtures.fixedRandomness("other-a")),
                responder(randomness = TestFixtures.fixedRandomness("other-b")),
            )
        assertFailsWith<HandshakeException> { ex.responder.verifyPeerFinished(other.initiator.localFinished()) }
    }

    @Test
    fun n2_finishedTravelsAsFirstEncryptedControlFrame() {
        val ex = HandshakeHarness.run()
        val aad = byteArrayOf(0, 0, 0, 48, 1, 0, 0, 0, 0)
        val frame = ex.initiator.frameSender(0).seal(ex.initiator.localFinished(), aad)
        val opened = ex.responder.frameReceiver(0).open(frame, aad)
        ex.responder.verifyPeerFinished(opened)
        val back = ex.responder.frameSender(0).seal(ex.responder.localFinished(), aad)
        ex.initiator.verifyPeerFinished(ex.initiator.frameReceiver(0).open(back, aad))
        assertTrue(ex.initiator.isPeerConfirmed && ex.responder.isPeerConfirmed)
    }

    @Test
    fun s7_eachStreamGetsOneCipherPerDirection() {
        val result = HandshakeHarness.run().initiator
        result.frameSender(2)
        result.frameReceiver(2)
        assertFailsWith<IllegalStateException> { result.frameSender(2) }
        assertFailsWith<IllegalStateException> { result.frameReceiver(2) }
        result.frameSender(3)
        assertFailsWith<IllegalArgumentException> { result.frameSender(-1) }
    }

    @Test
    fun versionMismatchIsRejectedInBothDirections() {
        val hello = HelloMessage.decode(initiator().start())
        val v2Hello = hello.copy(version = 2).encode()
        assertEquals(HandshakeFailure.VERSION_MISMATCH, assertFailsWith<HandshakeException> { responder().receiveHello(v2Hello) }.reason)

        val a = initiator()
        val helloBytes = a.start()
        val ack = HelloAckMessage.decode(responder().receiveHello(helloBytes))
        val v2Ack = resignAck(helloBytes, ack.copy(version = 2))
        assertEquals(HandshakeFailure.VERSION_MISMATCH, assertFailsWith<HandshakeException> { a.receiveHelloAck(v2Ack) }.reason)
    }

    @Test
    fun wrongSizesAreRejectedAsMalformed() {
        val goodHello = HelloMessage.decode(initiator().start())
        val badHellos =
            listOf(
                helloMap(goodHello, identityKey = ByteArray(31)),
                helloMap(goodHello, identityKey = ByteArray(33)),
                helloMap(goodHello, commitment = ByteArray(16)),
                helloMap(goodHello, caps = 0x10000),
                helloMap(goodHello, platform = 8),
                helloMap(goodHello, aead = 3),
                helloMap(goodHello, nickname = "x".repeat(65)),
                helloMap(goodHello, proof = ByteArray(31)),
            )
        for (bytes in badHellos) {
            val e = assertFailsWith<HandshakeException>(bytes.toHex()) { responder().receiveHello(bytes) }
            assertEquals(HandshakeFailure.MALFORMED_MESSAGE, e.reason)
        }

        val ack = HelloAckMessage.decode(GOLDEN_HELLO_ACK.hexToBytes())
        val badAcks =
            listOf(
                ackMap(ack, ephemeralKey = ByteArray(31)),
                ackMap(ack, nonce = ByteArray(15)),
                ackMap(ack, nonce = ByteArray(32)),
                ackMap(ack, signature = ByteArray(63)),
                ackMap(ack, signature = null),
                ackMap(ack, trustAck = ByteArray(16)),
            )
        for (bytes in badAcks) {
            val a = initiator()
            a.start()
            val e = assertFailsWith<HandshakeException>(bytes.toHex()) { a.receiveHelloAck(bytes) }
            assertEquals(HandshakeFailure.MALFORMED_MESSAGE, e.reason)
        }

        val badReveals =
            listOf(
                RawCbor.encode(
                    RawCbor.map(1 to ByteArray(32), 2 to ByteArray(15), 3 to ByteArray(64)),
                ),
                RawCbor.encode(
                    RawCbor.map(1 to ByteArray(31), 2 to ByteArray(16), 3 to ByteArray(64)),
                ),
                RawCbor.encode(
                    RawCbor.map(1 to ByteArray(32), 2 to ByteArray(16)),
                ),
            )
        for (bytes in badReveals) {
            val b = responder()
            b.receiveHello(initiator().start())
            val e = assertFailsWith<HandshakeException>(bytes.toHex()) { b.receiveHelloReveal(bytes) }
            assertEquals(HandshakeFailure.MALFORMED_MESSAGE, e.reason)
        }
    }

    @Test
    fun weakX25519KeysAreRejected() {
        // Low-order points: u = 0 and u = 1 give the all-zero shared secret.
        for (lowOrder in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 })) {
            val weak = TestFixtures.fixedRandomness(RawKeyPair(lowOrder, EPHEMERAL_A.privateKey), NONCE_A)
            val a = initiator(randomness = weak)
            val b = responder()
            val ack = b.receiveHello(a.start())
            val reveal = a.receiveHelloAck(ack) // A's own secret is fine; B's X25519 with the low-order point is not.
            assertEquals(HandshakeFailure.WEAK_KEY, assertFailsWith<HandshakeException> { b.receiveHelloReveal(reveal) }.reason)

            val weakB = TestFixtures.fixedRandomness(RawKeyPair(lowOrder, EPHEMERAL_B.privateKey), NONCE_B)
            val a2 = initiator()
            val ack2 = responder(randomness = weakB).receiveHello(a2.start())
            assertEquals(HandshakeFailure.WEAK_KEY, assertFailsWith<HandshakeException> { a2.receiveHelloAck(ack2) }.reason)
        }
    }

    @Test
    fun n3_unexpectedPeerIdentityIsRejected() {
        val a = initiator(expectedPeer = ExpectedPeer(IDENTITY_C.publicKey))
        val ack = responder().receiveHello(a.start())
        assertEquals(HandshakeFailure.PEER_IDENTITY_MISMATCH, assertFailsWith<HandshakeException> { a.receiveHelloAck(ack) }.reason)

        val b = responder(expectedPeerIdentity = IDENTITY_C.publicKey)
        assertEquals(
            HandshakeFailure.PEER_IDENTITY_MISMATCH,
            assertFailsWith<HandshakeException> { b.receiveHello(initiator().start()) }.reason,
        )

        // The right expectations succeed.
        val ex =
            HandshakeHarness.run(
                initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey)),
                responder(expectedPeerIdentity = IDENTITY_A.publicKey),
            )
        assertEquals(ex.initiator.sas, ex.responder.sas)
    }

    @Test
    fun reflectedIdentityIsRejected() {
        val b = responder(identity = IDENTITY_A)
        assertEquals(
            HandshakeFailure.REFLECTED_IDENTITY,
            assertFailsWith<HandshakeException> {
                b.receiveHello(initiator().start())
            }.reason,
        )

        val a = initiator()
        val mirror = responder(identity = IDENTITY_B)
        val hello = a.start()
        val ack = HelloAckMessage.decode(mirror.receiveHello(hello))
        val reflected = resignAck(hello, ack.copy(identityKey = IDENTITY_A.publicKey), signer = IDENTITY_A)
        assertEquals(HandshakeFailure.REFLECTED_IDENTITY, assertFailsWith<HandshakeException> { a.receiveHelloAck(reflected) }.reason)
    }

    @Test
    fun stateMachinesRefuseCallsOutOfOrder() {
        val a = initiator()
        assertFailsWith<IllegalStateException> { a.receiveHelloAck(GOLDEN_HELLO_ACK.hexToBytes()) }
        assertFailsWith<IllegalStateException> { a.result }
        a.start()
        assertFailsWith<IllegalStateException> { a.start() }

        val b = responder()
        assertFailsWith<IllegalStateException> { b.receiveHelloReveal(GOLDEN_HELLO_REVEAL.hexToBytes()) }
        assertFailsWith<IllegalStateException> { b.result }

        // After a failure every further call is refused.
        val failed = initiator()
        failed.start()
        assertFailsWith<HandshakeException> { failed.receiveHelloAck(byteArrayOf(0x00)) }
        assertFailsWith<IllegalStateException> { failed.receiveHelloAck(GOLDEN_HELLO_ACK.hexToBytes()) }
        assertFalse(failed.isComplete)

        val failedB = responder()
        assertFailsWith<HandshakeException> { failedB.receiveHello(byteArrayOf(0x00)) }
        assertFailsWith<IllegalStateException> { failedB.receiveHello(GOLDEN_HELLO.hexToBytes()) }

        // After success, too.
        val ex = HandshakeHarness.run(initiator = initiator(), responder = responder())
        assertEquals(ex.initiator.sas, ex.responder.sas)
    }

    @Test
    fun trustedProofIsAcceptedWithTheStoredSecret() {
        val ex = trustedExchange(requireTrustedProof = true)
        assertTrue(ex.initiator.peerProvedTrust)
        assertTrue(ex.responder.peerProvedTrust)
        assertEquals(ex.initiator.sas, ex.responder.sas)
    }

    @Test
    fun trustedProofWithAnotherSecretIsNotTrust() {
        val wrong = ByteArray(32) { 0x55 }
        val ex =
            HandshakeHarness.run(
                initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET)),
                responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, wrong)),
            )
        assertFalse(ex.responder.peerProvedTrust)
        assertFalse(ex.initiator.peerProvedTrust, "no trust ack comes back")

        val strict = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, wrong), requireTrustedProof = true)
        val hello = initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET)).start()
        assertEquals(HandshakeFailure.TRUST_PROOF_REQUIRED, assertFailsWith<HandshakeException> { strict.receiveHello(hello) }.reason)
    }

    @Test
    fun trustedOnlyResponderRefusesStrangersBeforeRevealingItself() {
        val strict = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, PAIRING_SECRET), requireTrustedProof = true)
        // No proof at all.
        assertEquals(
            HandshakeFailure.TRUST_PROOF_REQUIRED,
            assertFailsWith<HandshakeException> { strict.receiveHello(initiator().start()) }.reason,
        )
        // A stranger with a proof under a secret it made up.
        val stranger = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, PAIRING_SECRET), requireTrustedProof = true)
        val strangerHello = initiator(identity = IDENTITY_C, expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET)).start()
        assertEquals(
            HandshakeFailure.TRUST_PROOF_REQUIRED,
            assertFailsWith<HandshakeException> {
                stranger.receiveHello(strangerHello)
            }.reason,
        )
    }

    @Test
    fun trustedProofIsBoundToItsCommitment() {
        val proofHello = HelloMessage.decode(initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET)).start())
        val otherHello = HelloMessage.decode(initiator(randomness = TestFixtures.fixedRandomness("fresh")).start())
        val moved = otherHello.copy(trustedProof = proofHello.trustedProof).encode()
        val strict = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, PAIRING_SECRET), requireTrustedProof = true)
        assertEquals(HandshakeFailure.TRUST_PROOF_REQUIRED, assertFailsWith<HandshakeException> { strict.receiveHello(moved) }.reason)

        assertTrue(TrustedProofCheck.verify(proofHello))
        assertFalse(TrustedProofCheck.verify(otherHello.copy(trustedProof = proofHello.trustedProof)))
    }

    @Test
    fun unsolicitedTrustAckIsRejected() {
        val a = initiator()
        val hello = a.start()
        val ack = HelloAckMessage.decode(responder().receiveHello(hello))
        val withAck = resignAck(hello, ack.copy(trustAck = ByteArray(32) { 7 }))
        assertEquals(HandshakeFailure.UNEXPECTED_TRUST_ACK, assertFailsWith<HandshakeException> { a.receiveHelloAck(withAck) }.reason)
    }

    @Test
    fun invalidTrustAckMeansNotTrusted() {
        val a = initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET))
        val b = responder(trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, PAIRING_SECRET))
        val hello = a.start()
        val ack = HelloAckMessage.decode(b.receiveHello(hello))
        val forgedAck = resignAck(hello, ack.copy(trustAck = ByteArray(32) { 9 }))
        a.receiveHelloAck(forgedAck)
        assertFalse(a.result.peerProvedTrust)
    }

    @Test
    fun pairingSecretFromFirstSessionProvesTrustInTheNext() {
        val first = HandshakeHarness.run()
        val storedByA = first.initiator.recognitionSecret
        val storedByB = first.responder.recognitionSecret
        assertContentEquals(storedByA, storedByB)

        val second =
            HandshakeHarness.run(
                initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, storedByA), randomness = TestFixtures.fixedRandomness("s2-a")),
                responder(
                    trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, storedByB),
                    requireTrustedProof = true,
                    randomness = TestFixtures.fixedRandomness("s2-b"),
                ),
            )
        assertTrue(second.responder.peerProvedTrust)
        assertTrue(second.initiator.peerProvedTrust)
        assertFalse(second.initiator.sendKey.contentEquals(first.initiator.sendKey), "every session has fresh keys (N3)")
    }

    @Test
    fun nicknameAndPeerInfoValidation() {
        assertEquals("abc", HandshakeLimits.fitNickname("abc"))
        val emoji = "😀" // 4 UTF-8 bytes, 2 UTF-16 chars
        val long = "a".repeat(62) + emoji
        val fitted = HandshakeLimits.fitNickname(long)
        assertEquals("a".repeat(62), fitted, "a code point is never split")
        assertTrue(HandshakeLimits.fitNickname("é".repeat(40)).encodeToByteArray().size <= 64)
        assertFailsWith<IllegalArgumentException> { LocalPeerInfo(0x10000, "x", 0, AeadAlgorithm.AES_256_GCM) }
        assertFailsWith<IllegalArgumentException> { LocalPeerInfo(0, "x".repeat(65), 0, AeadAlgorithm.AES_256_GCM) }
        assertFailsWith<IllegalArgumentException> { LocalPeerInfo(0, "x", 8, AeadAlgorithm.AES_256_GCM) }
        assertEquals(64, LocalPeerInfo.forDevice(crypto, 0, "y".repeat(100), 0).nickname.length)
        assertFailsWith<IllegalArgumentException> { ExpectedPeer(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { ExpectedPeer(ByteArray(32), ByteArray(16)) }
    }

    private fun trustedExchange(requireTrustedProof: Boolean = false): HandshakeHarness.Exchange =
        HandshakeHarness.run(
            initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, PAIRING_SECRET)),
            responder(
                trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, PAIRING_SECRET),
                requireTrustedProof = requireTrustedProof,
            ),
        )

    private object TrustedProofCheck {
        fun verify(hello: HelloMessage): Boolean =
            TrustedProof.verifyProof(
                HandshakeHarness.crypto,
                PAIRING_SECRET,
                hello.identityKey,
                hello.commitment,
                checkNotNull(hello.trustedProof),
            )
    }

    companion object {
        val PAIRING_SECRET = ByteArray(32) { (0xA0 + it).toByte() }

        const val GOLDEN_HELLO =
            "a70101025820d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a035820ba67c4f1d15b27d8b598935d9b089def" +
                "94e6be1d54a401323b44c6ef3d218f3a041908190570416e616e7961e280997320506978656c06000701"
        const val GOLDEN_HELLO_ACK =
            "a901010258203d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c035820de9edb7d7b7dc1b4d35b61c2ece43537" +
                "3f8343c85b78674dadfc7e146f882b4f0450101112131415161718191a1b1c1d1e1f0519108906685468696e6b506164070108010a5840" +
                "46056649f99cd7a3552507b9f2822fd443eb627ee2dd6cb82d857749696a97eba82f0b34902f6cc853119225b4a02ea5096c37312fb9d2" +
                "7c71b7061481d5b00f"
        const val GOLDEN_HELLO_REVEAL =
            "a30158208520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a0250000102030405060708090a0b0c0d0e0f0358" +
                "40e675f4387d6e383797925e6a75f168f6db705bb1bc17aa2a1f3496a645487584ac5fead0711d26850b548ceed592a0d60927547fb464" +
                "87ba7511d2a7b0b0b703"
        const val GOLDEN_TRANSCRIPT_HASH = "e6c070bf8535d82c8365aa68b73617207b5588dbd78ec0b745053e334a721d0e"
        const val GOLDEN_SAS = "500509"
        const val GOLDEN_KEY_A_TO_B = "5855f7378fac6949d62a711c2594a53f4587cee73fbb9c2ceb2cda4a8462e15a"
        const val GOLDEN_KEY_B_TO_A = "51e830bb33d10cdb76c8d49fde2d0de10cc511394fe4d1f8c4ddec01291c6c2c"
        const val GOLDEN_RECOGNITION_SECRET = "4a0bd1c30fa16adaedadbbe7ef5e7e08230b7fa276dc3a6f1f838589bb3ef521"
        const val GOLDEN_FINISHED_A = "0cb8787526721202ec64db60083de66e4c2102493271e23da8c7522160fed51d"
        const val GOLDEN_FINISHED_B = "0ff13f9bbe6ba705cb53379ca356cdfece3f8840b9b566f7f2bc2de276ae45e2"
        const val GOLDEN_HELLO_WITH_PROOF =
            "a80101025820d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a035820ba67c4f1d15b27d8b598935d9b089def" +
                "94e6be1d54a401323b44c6ef3d218f3a041908190570416e616e7961e280997320506978656c060007010858203665bb9861d4ed496341" +
                "8ef9f3d9fc1a3551e8edba85ff5d16e28b205ef2230e"
        const val GOLDEN_HELLO_ACK_WITH_TRUST_ACK =
            "aa01010258203d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c035820de9edb7d7b7dc1b4d35b61c2ece43537" +
                "3f8343c85b78674dadfc7e146f882b4f0450101112131415161718191a1b1c1d1e1f0519108906685468696e6b50616407010801095820" +
                "a6d3167512dbc20661a98c9d3533fb092c167f8fff0bcb7d3298c9e3882437420a5840a7f74e3e55345ce3b64e895b8c20747d542e9502" +
                "f4ad4df09bc9286a38953892c21cac8c4da2aa5e02093dddcceed96529c7026b390e88b19f20ea17ac8e1209"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun transcript(vararg messages: ByteArray): ByteArray {
            var input = "drop-transcript-v1".encodeToByteArray()
            for (m in messages) {
                input +=
                    byteArrayOf((m.size ushr 24).toByte(), (m.size ushr 16).toByte(), (m.size ushr 8).toByte(), m.size.toByte()) + m
            }
            return sha256(input)
        }
    }
}
