package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.RawKeyPair
import com.constrivo.drop.core.crypto.constantTimeEquals
import com.constrivo.drop.core.crypto.trust.TrustedProof

/**
 * The responder (`B`) of the handshake, as a pure state machine; see [HandshakeInitiator] for the message flow.
 *
 * `B` chooses its ephemeral key while it knows only `A`'s commitment, and accepts `A`'s key only if it opens that
 * commitment (N1). `B` signs `Hello ‖ HelloAck` and verifies `A`'s signature over all three messages (N2).
 *
 * Use: pass the `Hello` to [receiveHello] → send the returned `HelloAck`; pass the `HelloReveal` to
 * [receiveHelloReveal] → the verified [HandshakeResult]. If the connection closes or times out in between, call
 * [abort]. Each instance runs one handshake. Any [HandshakeException] leaves it failed. Calls out of order throw
 * [IllegalStateException]. Not thread-safe.
 *
 * Untrusted handshakes (no valid trusted proof) go through [HandshakeGuard.attempts]: an initiator learns every SAS
 * input from the `HelloAck`, before it reveals anything, so each untrusted `HelloAck` is one SAS guess for a man in
 * the middle, and the device-wide limiter caps how many of them end unverified ([PairingAttemptLimiter]).
 *
 * @param guard the device's one [HandshakeGuard], shared by all responders: attempt limiter, proof replay cache
 *   and clock.
 * @param trustedPeers the recognition secrets stored at pairing, used to check a `Hello`'s trusted proof.
 * @param requireTrustedProof true in Trusted-only visibility (architecture §5.3): a `Hello` without a valid proof
 *   (missing, wrong, stale or replayed) is refused with [HandshakeFailure.TRUST_PROOF_REQUIRED] before anything
 *   about this device is sent.
 * @param expectedPeerIdentity on a reconnect (N3), the identity key the initiator must have.
 */
class HandshakeResponder(
    private val crypto: CryptoProvider,
    private val identity: IdentityKey,
    private val local: LocalPeerInfo,
    private val guard: HandshakeGuard,
    private val trustedPeers: TrustedPeerLookup = TrustedPeerLookup.NONE,
    private val requireTrustedProof: Boolean = false,
    expectedPeerIdentity: ByteArray? = null,
    private val randomness: HandshakeRandomness = HandshakeRandomness.secure(crypto),
) {
    private enum class State { AWAITING_HELLO, AWAITING_REVEAL, COMPLETE, FAILED }

    private val expectedPeerIdentity: ByteArray? = expectedPeerIdentity?.copyOf()
    private var state = State.AWAITING_HELLO
    private var ephemeral: RawKeyPair? = null
    private var hello: HelloMessage? = null
    private var helloBytes: ByteArray? = null
    private var ack: HelloAckMessage? = null
    private var ackBytes: ByteArray? = null
    private var peerProvedTrust = false
    private var permit: PairingAttemptLimiter.Permit? = null
    private var completed: HandshakeResult? = null

    init {
        require(expectedPeerIdentity == null || expectedPeerIdentity.size == HandshakeLimits.KEY_SIZE) {
            "expected identity key must be 32 bytes"
        }
    }

    /** True once [receiveHelloReveal] has succeeded. */
    val isComplete: Boolean get() = state == State.COMPLETE

    /** The verified result. @throws IllegalStateException before [receiveHelloReveal] has succeeded. */
    val result: HandshakeResult get() = checkNotNull(completed) { "handshake is not complete" }

    /**
     * Checks the initiator's `Hello` and returns the signed `HelloAck` to send.
     *
     * @throws HandshakeException if the message is malformed, of another version, from an unexpected or reflected
     *   identity, lacks a required trusted proof, or is refused by the attempt limiter
     *   ([HandshakeFailure.RATE_LIMITED]).
     * @throws CryptoException if a local primitive fails.
     */
    fun receiveHello(bytes: ByteArray): ByteArray {
        check(state == State.AWAITING_HELLO) { "not waiting for Hello (state $state)" }
        return guarded { processHello(bytes.copyOf()) }
    }

    /**
     * Ends this handshake unfinished: call when the connection closes, errors or times out before
     * [receiveHelloReveal] has succeeded. An untrusted handshake that already sent its `HelloAck` counts as a failed
     * attempt ([PairingAttemptLimiter]). Idempotent; no effect once the handshake is complete.
     */
    fun abort() {
        if (state == State.COMPLETE) return
        state = State.FAILED
        wipeEphemeral()
        settlePermit(succeeded = false)
    }

    /**
     * Verifies the initiator's `HelloReveal` and returns the result.
     *
     * @throws HandshakeException if the message is malformed, does not open the commitment, is badly signed, or
     *   yields a weak X25519 secret.
     * @throws CryptoException if a local primitive fails.
     */
    fun receiveHelloReveal(bytes: ByteArray): HandshakeResult {
        check(state == State.AWAITING_REVEAL) { "not waiting for HelloReveal (state $state)" }
        return guarded { processReveal(bytes.copyOf()) }
    }

    private fun processHello(helloBytes: ByteArray): ByteArray {
        val hello = HelloMessage.decode(helloBytes)
        if (hello.version != HandshakeLimits.VERSION) {
            throw HandshakeException(HandshakeFailure.VERSION_MISMATCH, "peer speaks version ${hello.version}")
        }
        val identityKey = identity.publicKey
        check(identityKey.size == HandshakeLimits.KEY_SIZE) { "identity key must be 32 bytes" }
        if (constantTimeEquals(hello.identityKey, identityKey)) {
            throw HandshakeException(HandshakeFailure.REFLECTED_IDENTITY, "peer uses this device's identity key")
        }
        if (expectedPeerIdentity != null && !constantTimeEquals(hello.identityKey, expectedPeerIdentity)) {
            throw HandshakeException(HandshakeFailure.PEER_IDENTITY_MISMATCH, "initiator is not the expected device")
        }
        val secret = hello.trustedProof?.let { trustedPeers.recognitionSecretFor(hello.identityKey.copyOf()) }
        val epoch =
            secret?.let {
                TrustedProof.acceptedEpoch(
                    crypto,
                    it,
                    guard.clock.unixSeconds(),
                    hello.identityKey,
                    hello.commitment,
                    checkNotNull(hello.trustedProof),
                )
            }
        val proofValid =
            epoch != null &&
                when (guard.rememberProof(hello.identityKey, hello.commitment)) {
                    HandshakeGuard.ProofUse.FIRST -> true

                    HandshakeGuard.ProofUse.REPLAYED -> false

                    HandshakeGuard.ProofUse.CACHE_FULL -> throw HandshakeException(
                        HandshakeFailure.RATE_LIMITED,
                        "too many trusted handshakes to remember; try again later",
                    )
                }
        if (requireTrustedProof && !proofValid) {
            throw HandshakeException(HandshakeFailure.TRUST_PROOF_REQUIRED, "Trusted-only: Hello has no valid trusted proof")
        }
        // An untrusted HelloAck hands the initiator every SAS input, so each one is a guess: limit them.
        val permit =
            if (proofValid) {
                null
            } else {
                guard.attempts.tryAcquire()
                    ?: throw HandshakeException(HandshakeFailure.RATE_LIMITED, "untrusted handshakes are rate-limited; try again later")
            }
        try {
            val bytes = buildAck(hello, helloBytes, identityKey, if (proofValid) checkNotNull(secret) else null, epoch ?: 0L)
            this.permit = permit
            return bytes
        } catch (e: Exception) {
            permit?.cancelled()
            throw e
        }
    }

    /** Signs and stores the `HelloAck`; [secret] is the pairing's recognition secret if the proof was accepted. */
    private fun buildAck(
        hello: HelloMessage,
        helloBytes: ByteArray,
        identityKey: ByteArray,
        secret: ByteArray?,
        epoch: Long,
    ): ByteArray {
        val pair = randomness.ephemeralKeyPair()
        val nonceB = randomness.nonce()
        check(pair.publicKey.size == HandshakeLimits.KEY_SIZE && pair.privateKey.size == HandshakeLimits.KEY_SIZE) {
            "ephemeral key pair must be 32-byte X25519 keys"
        }
        check(nonceB.size == HandshakeLimits.NONCE_SIZE) { "nonce must be ${HandshakeLimits.NONCE_SIZE} bytes" }
        val unsigned =
            HelloAckMessage(
                version = HandshakeLimits.VERSION,
                identityKey = identityKey,
                ephemeralKey = pair.publicKey.copyOf(),
                nonce = nonceB.copyOf(),
                caps = local.caps,
                nickname = local.nickname,
                platform = local.platform,
                aead = local.aeadPreference.wireId,
                trustAck = secret?.let { TrustedProof.ack(crypto, it, epoch, identityKey, hello.commitment) },
            )
        val signed = KeySchedule.transcriptHash(crypto, helloBytes, unsigned.encode())
        val message = unsigned.withSignature(identity.sign(KeySchedule.ackSignatureInput(signed)))
        val bytes = message.encode()
        ephemeral = pair
        this.hello = hello
        this.helloBytes = helloBytes
        ack = message
        ackBytes = bytes
        peerProvedTrust = secret != null
        state = State.AWAITING_REVEAL
        return bytes.copyOf()
    }

    private fun processReveal(revealBytes: ByteArray): HandshakeResult {
        val hello = checkNotNull(hello)
        val helloBytes = checkNotNull(helloBytes)
        val ack = checkNotNull(ack)
        val ackBytes = checkNotNull(ackBytes)
        val pair = checkNotNull(ephemeral)
        val reveal = HelloRevealMessage.decode(revealBytes)
        val opened = KeySchedule.commitment(crypto, reveal.ephemeralKey, reveal.nonce)
        if (!constantTimeEquals(opened, hello.commitment)) {
            throw HandshakeException(HandshakeFailure.COMMITMENT_MISMATCH, "HelloReveal does not open the Hello commitment")
        }
        val revealSigned = KeySchedule.transcriptHash(crypto, helloBytes, ackBytes, reveal.withoutSignature().encode())
        if (!crypto.ed25519Verify(hello.identityKey, KeySchedule.revealSignatureInput(revealSigned), checkNotNull(reveal.signature))) {
            throw HandshakeException(HandshakeFailure.BAD_SIGNATURE, "HelloReveal signature does not verify")
        }
        val sharedSecret =
            try {
                crypto.x25519(pair.privateKey, reveal.ephemeralKey)
            } catch (e: CryptoException) {
                throw HandshakeException(HandshakeFailure.WEAK_KEY, "X25519 with the initiator's ephemeral key failed", e)
            }
        try {
            val transcript = KeySchedule.transcriptHash(crypto, helloBytes, ackBytes, revealBytes)
            val secrets = KeySchedule.derive(crypto, sharedSecret, reveal.nonce, ack.nonce, transcript)
            val result =
                HandshakeResult(
                    crypto = crypto,
                    role = HandshakeRole.RESPONDER,
                    localIdentityKey = ack.identityKey,
                    peerIdentityKey = hello.identityKey,
                    peerVersion = hello.version,
                    peerCaps = hello.caps,
                    peerNickname = hello.nickname,
                    peerPlatform = hello.platform,
                    sas =
                        KeySchedule.sas(
                            crypto,
                            identityKeyA = hello.identityKey,
                            identityKeyB = ack.identityKey,
                            ephemeralKeyA = reveal.ephemeralKey,
                            ephemeralKeyB = ack.ephemeralKey,
                            nonceA = reveal.nonce,
                            nonceB = ack.nonce,
                        ),
                    aead = AeadAlgorithm.negotiate(local.aeadPreference, checkNotNull(AeadAlgorithm.fromWireId(hello.aead))),
                    sendKeyBytes = secrets.keyBToA,
                    receiveKeyBytes = secrets.keyAToB,
                    localFinishedKey = secrets.finishedKeyB,
                    peerFinishedKey = secrets.finishedKeyA,
                    recognitionSecret = secrets.recognitionSecret,
                    transcriptHash = transcript,
                    peerProvedTrust = peerProvedTrust,
                )
            completed = result
            state = State.COMPLETE
            wipeEphemeral()
            settlePermit(succeeded = true)
            return result
        } finally {
            sharedSecret.fill(0)
        }
    }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            // Peer errors arrive as HandshakeException; anything else is a local failure. Either way this
            // handshake is over, and one that sent an untrusted HelloAck counts as a failed attempt.
            state = State.FAILED
            wipeEphemeral()
            settlePermit(succeeded = false)
            throw e
        }

    private fun settlePermit(succeeded: Boolean) {
        val held = permit ?: return
        permit = null
        if (succeeded) held.succeeded() else held.failed()
    }

    private fun wipeEphemeral() {
        ephemeral?.privateKey?.fill(0)
        ephemeral = null
    }
}
