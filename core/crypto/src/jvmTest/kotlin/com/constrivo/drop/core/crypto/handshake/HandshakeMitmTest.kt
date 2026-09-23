package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.FakeClock
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.EPHEMERAL_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_B
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_M
import com.constrivo.drop.core.crypto.TestFixtures.NONCE_A
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.initiator
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.responder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A man in the middle cannot make the two victims' SAS codes agree (F-B3, T-11; spec change N1): each side fixes its
 * ephemeral key before it sees the other's, so each attempt is one 10⁻⁶ guess instead of a search, and the
 * responder's [PairingAttemptLimiter] caps how many attempts end unverified.
 */
class HandshakeMitmTest {
    /** The two sessions a relaying attacker M runs: A ↔ M (M answers) and M ↔ B (M initiates). */
    private class Relay(
        val atA: HandshakeResult,
        val atB: HandshakeResult,
    )

    private fun relay(seed: String): Relay {
        val a = initiator(randomness = TestFixtures.fixedRandomness("victim-a:$seed"))
        val b = responder(randomness = TestFixtures.fixedRandomness("victim-b:$seed"))
        val mAnswersA = responder(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("m-to-a:$seed"))
        val mCallsB = initiator(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("m-to-b:$seed"))

        val revealA = a.receiveHelloAck(mAnswersA.receiveHello(a.start()))
        mAnswersA.receiveHelloReveal(revealA)
        val atB = b.receiveHelloReveal(mCallsB.receiveHelloAck(b.receiveHello(mCallsB.start())))
        return Relay(a.result, atB)
    }

    @Test
    fun fB3_t11_relayingAttackerLeavesTheVictimsWithDifferentCodes() {
        val relay = relay("1")
        assertNotEquals(relay.atA.sas, relay.atB.sas)
        // Typing A's code on B (or B's on A) does not confirm the pairing.
        assertFalse(relay.atB.sasMatches(relay.atA.sas))
        assertFalse(relay.atA.sasMatches(relay.atB.sas))
        // Each victim sees the attacker's identity, not the other victim's.
        assertContentEquals(IDENTITY_M.publicKey, relay.atA.peerIdentityKey)
        assertContentEquals(IDENTITY_M.publicKey, relay.atB.peerIdentityKey)
        // And the attacker holds no key the victims share.
        assertFalse(relay.atA.sendKey.contentEquals(relay.atB.receiveKey))
    }

    @Test
    fun fB3_codesDisagreeAcrossManyRelayedSessions() {
        // Without the commitment an attacker could grind ~10^6 keys per session; with it each session is one guess.
        for (seed in 0 until 100) {
            val relay = relay("many-$seed")
            assertNotEquals(relay.atA.sas, relay.atB.sas, "seed $seed")
        }
    }

    @Test
    fun n1_helloRevealsNeitherTheEphemeralKeyNorTheNonce() {
        val hello = initiator().start()
        assertFalse(containsSubsequence(hello, EPHEMERAL_A.publicKey), "eph_pk_A must not appear in Hello")
        assertFalse(containsSubsequence(hello, NONCE_A), "nonce_A must not appear in Hello")
    }

    @Test
    fun n1_attackerCannotSwapItsKeyAfterSeeingTheResponders() {
        // M initiates to B and commits to one key; after seeing eph_pk_B it would like to reveal another one
        // (for instance the result of a search for a matching SAS). A properly signed reveal of any other key fails.
        val m = initiator(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("committed"))
        val b = responder()
        val hello = m.start()
        val ack = b.receiveHello(hello)
        for (candidate in 0 until 16) {
            val victim = responder()
            val ackAgain = victim.receiveHello(hello)
            assertContentEquals(ack, ackAgain, "deterministic fixture")
            val swapped = TestFixtures.x25519From("ground-$candidate")
            val reveal =
                resignReveal(
                    hello,
                    ackAgain,
                    HelloRevealMessage(swapped.publicKey, TestFixtures.nonceFrom("ground-$candidate")),
                    IDENTITY_M,
                )
            val e = assertFailsWith<HandshakeException> { victim.receiveHelloReveal(reveal) }
            assertEquals(HandshakeFailure.COMMITMENT_MISMATCH, e.reason)
        }
        // The committed key itself still works.
        assertEquals(m.receiveHelloAck(ack).let { b.receiveHelloReveal(it).sas }, m.result.sas)
    }

    @Test
    fun n1_responderCannotSwapItsKeyAfterSeeingTheInitiators() {
        // M answers A and, once the HelloReveal tells it eph_pk_A, would like to replace eph_pk_B by another key (for
        // instance one found by searching for a SAS that matches B's). A's reveal and code are already bound to the
        // first HelloAck, so no second key gets anywhere.
        val a = initiator()
        val hello = a.start()
        val firstAck = responder(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("m-first")).receiveHello(hello)
        val reveal = a.receiveHelloAck(firstAck)
        val sasA = a.result.sas
        val committedKeyB = HelloAckMessage.decode(firstAck).ephemeralKey
        assertContentEquals(EPHEMERAL_A.publicKey, HelloRevealMessage.decode(reveal).ephemeralKey, "M now knows eph_pk_A")

        for (candidate in 0 until 16) {
            val swapped = TestFixtures.fixedRandomness("m-swap-$candidate")
            val secondResponder = responder(identity = IDENTITY_M, randomness = swapped)
            val secondAck = secondResponder.receiveHello(hello)
            val secondKeyB = HelloAckMessage.decode(secondAck).ephemeralKey
            assertFalse(secondKeyB.contentEquals(committedKeyB))
            // A takes exactly one HelloAck ...
            assertFailsWith<IllegalStateException> { a.receiveHelloAck(secondAck) }
            // ... and its reveal is signed over the first one, so M cannot finish a session on the swapped key.
            val e = assertFailsWith<HandshakeException> { secondResponder.receiveHelloReveal(reveal) }
            assertEquals(HandshakeFailure.BAD_SIGNATURE, e.reason)
            // A's code stays the one computed over the key M committed to first.
            assertEquals(sasA, a.result.sas)
            val sasOverFirstKey =
                KeySchedule.sas(
                    HandshakeHarness.crypto,
                    IDENTITY_A.publicKey,
                    IDENTITY_M.publicKey,
                    EPHEMERAL_A.publicKey,
                    committedKeyB,
                    NONCE_A,
                    HelloAckMessage.decode(firstAck).nonce,
                )
            assertEquals(sasOverFirstKey, sasA)
        }
    }

    @Test
    fun n1_initiatorRevealsItsKeyOnlyAfterVerifyingTheResponders() {
        // eph_pk_A leaves A only in the HelloReveal, which A builds only after sig_B over eph_pk_B has verified.
        val a = initiator()
        val hello = a.start()
        val ack = HelloAckMessage.decode(responder(identity = IDENTITY_M).receiveHello(hello))
        val unsignedSwap = ackMap(ack, ephemeralKey = TestFixtures.x25519From("unsigned").publicKey)
        assertFailsWith<HandshakeException> { a.receiveHelloAck(unsignedSwap) }
        assertFalse(a.isComplete)
        assertFailsWith<IllegalStateException> { a.result }
    }

    @Test
    fun n1_probingForAMatchingCodeIsCappedByTheAttemptLimiter() {
        // M completes A ↔ M, so A shows a code. M then keeps opening handshakes to B: each HelloAck gives it every input
        // of B's code before M reveals anything, and M drops the connection when the code does not match A's.
        val relay = relay("probe")
        val target = relay.atA.sas
        val clock = FakeClock()
        val guard = HandshakeGuard(clock)
        var guesses = 0
        var refusals = 0
        val hour = 3_600_000L
        while (clock.millis < hour) {
            val m = initiator(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("probe-${clock.millis}"))
            val b = responder(guard = guard, randomness = TestFixtures.fixedRandomness("b-${clock.millis}"))
            val hello = m.start()
            val ack =
                try {
                    b.receiveHello(hello)
                } catch (e: HandshakeException) {
                    assertEquals(HandshakeFailure.RATE_LIMITED, e.reason)
                    refusals++
                    clock.advance(1_000) // M retries every second.
                    continue
                }
            guesses++
            val parsed = HelloAckMessage.decode(ack)
            val codeAtB =
                KeySchedule.sas(
                    HandshakeHarness.crypto,
                    IDENTITY_M.publicKey,
                    IDENTITY_B.publicKey,
                    TestFixtures.x25519From("eph:probe-${clock.millis}").publicKey,
                    parsed.ephemeralKey,
                    TestFixtures.nonceFrom("nonce:probe-${clock.millis}"),
                    parsed.nonce,
                )
            assertNotEquals(target, codeAtB, "a one-in-a-million hit would make this test flaky; change the seeds")
            b.abort() // M drops the connection before its reveal.
            clock.advance(100)
        }
        // 5 free failures, then lockouts of 1, 2, 4, 8, 16 and 32 minutes: 11 guesses in the first hour, not 36,000.
        assertEquals(11, guesses)
        assertTrue(refusals > 3_000)
        // Trusted peers are never locked out.
        val secret = HandshakeTest.PAIRING_SECRET
        val trusted =
            HandshakeHarness.run(
                initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, secret), clock = clock),
                responder(guard = guard, trustedPeers = HandshakeHarness.lookup(IDENTITY_A.publicKey, secret)),
            )
        assertTrue(trusted.responder.peerProvedTrust)
    }

    @Test
    fun n3_initiatorExpectingTheRealPeerRejectsTheAttacker() {
        val a = initiator(expectedPeer = ExpectedPeer(IDENTITY_B.publicKey))
        val m = responder(identity = IDENTITY_M)
        val e = assertFailsWith<HandshakeException> { a.receiveHelloAck(m.receiveHello(a.start())) }
        assertEquals(HandshakeFailure.PEER_IDENTITY_MISMATCH, e.reason)
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
