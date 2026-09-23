package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.FakeClock
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_B
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_C
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.initiator
import com.constrivo.drop.core.crypto.handshake.HandshakeHarness.responder
import com.constrivo.drop.core.crypto.trust.TrustedProof
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-wide [HandshakeGuard] as the responder uses it: the limit on untrusted handshakes that end unverified
 * (SAS probing, architecture §6 note) and the replay protection of trusted proofs (§5.3, F-J1).
 */
class HandshakeGuardTest {
    private val clock = FakeClock()
    private val guard = HandshakeGuard(clock)
    private val secret = HandshakeTest.PAIRING_SECRET
    private val lookup = HandshakeHarness.lookup(IDENTITY_A.publicKey, secret)
    private var seed = 0

    /** A fresh `Hello` from a stranger (identity C). */
    private fun strangerHello(): ByteArray =
        initiator(identity = IDENTITY_C, randomness = TestFixtures.fixedRandomness("s${seed++}")).start()

    private fun sharedResponder(
        requireTrustedProof: Boolean = false,
        trustedPeers: TrustedPeerLookup = lookup,
    ): HandshakeResponder =
        responder(
            guard = guard,
            trustedPeers = trustedPeers,
            requireTrustedProof = requireTrustedProof,
            randomness = TestFixtures.fixedRandomness("b${seed++}"),
        )

    private fun proofInitiator(initiatorClock: HandshakeClock = clock): HandshakeInitiator =
        initiator(
            expectedPeer = ExpectedPeer(IDENTITY_B.publicKey, secret),
            randomness = TestFixtures.fixedRandomness("a${seed++}"),
            clock = initiatorClock,
        )

    private fun assertRefused(
        reason: HandshakeFailure,
        block: () -> Unit,
    ) {
        assertEquals(reason, assertFailsWith<HandshakeException> { block() }.reason)
    }

    @Test
    fun repeatedAbortedHellosFromStrangersAreRefusedAfterTheFreeFailures() {
        val free = guard.attempts.policy.freeFailures
        repeat(free + 1) {
            val b = sharedResponder()
            b.receiveHello(strangerHello())
            b.abort()
        }
        assertEquals(free + 1, guard.attempts.failures)
        assertRefused(HandshakeFailure.RATE_LIMITED) { sharedResponder().receiveHello(strangerHello()) }
        assertEquals(60_000, guard.attempts.lockoutRemainingMillis())

        // After the lockout one more attempt is allowed, and failing it doubles the lockout.
        clock.advance(60_000)
        sharedResponder().apply { receiveHello(strangerHello()) }.abort()
        assertRefused(HandshakeFailure.RATE_LIMITED) { sharedResponder().receiveHello(strangerHello()) }
        assertEquals(120_000, guard.attempts.lockoutRemainingMillis())
    }

    @Test
    fun aRefusedHelloSendsNothingAndIsNotItselfAFailure() {
        val first = sharedResponder()
        first.receiveHello(strangerHello())
        // One untrusted handshake at a time: a second one waits, and the refusal costs nothing.
        val second = sharedResponder()
        assertRefused(HandshakeFailure.RATE_LIMITED) { second.receiveHello(strangerHello()) }
        assertFailsWith<IllegalStateException> { second.receiveHelloReveal(ByteArray(0)) }
        assertEquals(0, guard.attempts.failures)
        first.abort()
        assertEquals(1, guard.attempts.failures)
        sharedResponder().receiveHello(strangerHello())
    }

    @Test
    fun anAbandonedResponderCountsAsFailedAfterTheTimeout() {
        // The caller never calls abort(): the slot is freed when the in-flight time-out passes, as a failure.
        sharedResponder().receiveHello(strangerHello())
        clock.advance(guard.attempts.policy.inFlightTimeoutMillis - 1)
        assertRefused(HandshakeFailure.RATE_LIMITED) { sharedResponder().receiveHello(strangerHello()) }
        clock.advance(1)
        sharedResponder().receiveHello(strangerHello())
        assertEquals(1, guard.attempts.failures)
    }

    @Test
    fun aBadRevealCountsAsAFailureAndAVerifiedOneDoesNot() {
        val a = initiator(randomness = TestFixtures.fixedRandomness("honest"))
        val b = sharedResponder(trustedPeers = TrustedPeerLookup.NONE)
        val reveal = a.receiveHelloAck(b.receiveHello(a.start()))
        b.receiveHelloReveal(reveal)
        assertEquals(0, guard.attempts.failures)
        b.abort() // no effect after completion
        assertEquals(0, guard.attempts.failures)

        val a2 = initiator(randomness = TestFixtures.fixedRandomness("honest-2"))
        val b2 = sharedResponder(trustedPeers = TrustedPeerLookup.NONE)
        val reveal2 = a2.receiveHelloAck(b2.receiveHello(a2.start()))
        val tampered = reveal2.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertRefused(HandshakeFailure.BAD_SIGNATURE) { b2.receiveHelloReveal(tampered) }
        assertEquals(1, guard.attempts.failures)
        b2.abort() // already failed: counted once
        assertEquals(1, guard.attempts.failures)
    }

    @Test
    fun aHelloRefusedForOtherReasonsTakesNoSlot() {
        val strict = sharedResponder(requireTrustedProof = true)
        assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) { strict.receiveHello(strangerHello()) }
        strict.abort()
        assertRefused(HandshakeFailure.MALFORMED_MESSAGE) { sharedResponder().receiveHello(byteArrayOf(0)) }
        assertEquals(0, guard.attempts.failures)
        sharedResponder().receiveHello(strangerHello())
    }

    @Test
    fun trustedPeersAreNotLimited() {
        repeat(guard.attempts.policy.freeFailures + 1) { sharedResponder().apply { receiveHello(strangerHello()) }.abort() }
        assertRefused(HandshakeFailure.RATE_LIMITED) { sharedResponder().receiveHello(strangerHello()) }
        // Several trusted handshakes, even in parallel and even abandoned, while strangers are locked out.
        val pending = sharedResponder()
        pending.receiveHello(proofInitiator().start())
        val ex = HandshakeHarness.run(proofInitiator(), sharedResponder(requireTrustedProof = true))
        assertTrue(ex.responder.peerProvedTrust)
        assertTrue(ex.initiator.peerProvedTrust)
        pending.abort()
        assertEquals(guard.attempts.policy.freeFailures + 1, guard.attempts.failures)
    }

    @Test
    fun replayedProofHelloIsRefusedByATrustedOnlyResponder() {
        val hello = proofInitiator().start()
        sharedResponder(requireTrustedProof = true).receiveHello(hello)
        // An eavesdropper sends the captured bytes again, to a new responder on the same device, in the same epoch.
        assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) { sharedResponder(requireTrustedProof = true).receiveHello(hello) }
        clock.advance(60_000)
        assertEquals(TestFixtures.FIXED_EPOCH, TrustedProof.epochOf(clock.unix))
        assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) { sharedResponder(requireTrustedProof = true).receiveHello(hello) }
        // Control: the epoch alone would still accept it; the device's replay cache is what refuses it.
        responder(guard = HandshakeGuard(clock), trustedPeers = lookup, requireTrustedProof = true).receiveHello(hello)
        // After the replay window the cache has forgotten it, and the epoch has gone stale.
        clock.advance(TrustedProof.REPLAY_WINDOW_SECONDS * 1000)
        assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) { sharedResponder(requireTrustedProof = true).receiveHello(hello) }
        assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) {
            responder(guard = HandshakeGuard(clock), trustedPeers = lookup, requireTrustedProof = true).receiveHello(hello)
        }
    }

    @Test
    fun replayedProofHelloIsTreatedAsAStrangerElsewhere() {
        val hello = proofInitiator().start()
        sharedResponder().receiveHello(hello)
        // Outside Trusted-only mode the device answers strangers anyway, but without a trust ack, and under the limiter.
        val again = sharedResponder()
        val ack = HelloAckMessage.decode(again.receiveHello(hello))
        assertNull(ack.trustAck)
        again.abort()
        assertEquals(1, guard.attempts.failures)
    }

    @Test
    fun proofFromAnAdjacentEpochIsAcceptedAndFromFurtherAwayRefused() {
        // The responder is in epoch FIXED_EPOCH; initiators whose clocks put them one epoch either side still pass.
        val start = TestFixtures.FIXED_EPOCH * TrustedProof.EPOCH_SECONDS
        for (initiatorTime in listOf(start - 1, start + 2 * TrustedProof.EPOCH_SECONDS - 1)) {
            val ex = HandshakeHarness.run(proofInitiator(FakeClock(unix = initiatorTime)), sharedResponder(requireTrustedProof = true))
            assertTrue(ex.responder.peerProvedTrust)
            assertTrue(ex.initiator.peerProvedTrust, "the trust ack uses the epoch the proof was made in")
        }
        for (initiatorTime in listOf(start - TrustedProof.EPOCH_SECONDS - 1, start + 2 * TrustedProof.EPOCH_SECONDS)) {
            assertRefused(HandshakeFailure.TRUST_PROOF_REQUIRED) {
                sharedResponder(requireTrustedProof = true).receiveHello(proofInitiator(FakeClock(unix = initiatorTime)).start())
            }
        }
    }

    @Test
    fun fullReplayCacheFailsClosed() {
        val small = HandshakeGuard(clock, maxRememberedProofs = 2)
        repeat(2) { responder(guard = small, trustedPeers = lookup, requireTrustedProof = true).receiveHello(proofInitiator().start()) }
        val e =
            assertFailsWith<HandshakeException> {
                responder(guard = small, trustedPeers = lookup, requireTrustedProof = true).receiveHello(proofInitiator().start())
            }
        assertEquals(HandshakeFailure.RATE_LIMITED, e.reason)
        // Entries expire with the replay window.
        clock.advance(TrustedProof.REPLAY_WINDOW_SECONDS * 1000)
        responder(guard = small, trustedPeers = lookup, requireTrustedProof = true).receiveHello(proofInitiator().start())
        assertFailsWith<IllegalArgumentException> { HandshakeGuard(clock, maxRememberedProofs = 0) }
    }

    @Test
    fun abortBeforeHelloOrAfterFailureIsHarmless() {
        val b = sharedResponder()
        b.abort()
        b.abort()
        assertFailsWith<IllegalStateException> { b.receiveHello(strangerHello()) }
        assertFalse(b.isComplete)
        assertEquals(0, guard.attempts.failures)
    }
}
