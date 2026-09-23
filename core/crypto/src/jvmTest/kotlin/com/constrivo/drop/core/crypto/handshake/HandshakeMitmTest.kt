package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.EPHEMERAL_A
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

/**
 * A man in the middle cannot make the two victims' SAS codes agree (F-B3, T-11; spec change N1): each side fixes its
 * ephemeral key before it sees the other's, so the attacker has one guess, not a million.
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
    fun n1_initiatorsCodeIsFixedBeforeTheResponderLearnsItsKey() {
        // A computes its SAS when it answers the HelloAck, and eph_pk_A leaves A only in that answer, so a responder
        // in the middle had to fix eph_pk_B (signed by the HelloAck) without knowing eph_pk_A.
        val a = initiator()
        val m = responder(identity = IDENTITY_M, randomness = TestFixtures.fixedRandomness("m"))
        val ack = m.receiveHello(a.start())
        val reveal = a.receiveHelloAck(ack)
        val sasBeforeRevealArrives = a.result.sas
        val mResult = m.receiveHelloReveal(reveal)
        assertEquals(sasBeforeRevealArrives, mResult.sas, "M only learns the code A already shows")
        assertContentEquals(EPHEMERAL_A.publicKey, HelloRevealMessage.decode(reveal).ephemeralKey)
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
