package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.RawKeyPair
import com.constrivo.drop.core.crypto.constantTimeEquals
import com.constrivo.drop.core.crypto.trust.TrustedProof

/**
 * The initiator (`A`) of the handshake, as a pure state machine: no I/O, messages in and out as byte arrays
 * (architecture §6.2 as changed by N1–N3).
 *
 * ```
 * A → B  Hello        version, identity_pk_A, commitment = SHA-256("drop-commit-v1" ‖ eph_pk_A ‖ nonce_A),
 *                     caps, nickname, platform, aead, [trusted proof]
 * B → A  HelloAck     version, identity_pk_B, eph_pk_B, nonce_B, caps, nickname, platform, aead, [trust ack], sig_B
 * A → B  HelloReveal  eph_pk_A, nonce_A, sig_A
 * ```
 *
 * `A` fixes its ephemeral key before it sees `B`'s, and `B` fixes its key before it sees `A`'s, so a man in the middle
 * cannot search for keys that make the two SAS codes equal (N1). Each signature covers the transcript so far (N2).
 *
 * Use: [start] → send the `Hello`; pass the `HelloAck` to [receiveHelloAck] → send the returned `HelloReveal`;
 * then [result] is available. Each instance runs one handshake. Any [HandshakeException] leaves it failed.
 * Calls out of order throw [IllegalStateException]. Not thread-safe.
 *
 * The initiator's SAS is fixed once [receiveHelloAck] returns, before the responder learns `eph_pk_A`; a man in the
 * middle acting as responder learns it from the `HelloReveal`. So the UI shows [HandshakeResult.sas] as soon as the
 * result exists, and never retries an untrusted handshake automatically: each retry would be another silent guess.
 *
 * @param expectedPeer the device the caller means to reach (resolved beacon, scanned QR code, or reconnect, N3);
 *   the handshake fails if the responder's identity differs. With a recognition secret the `Hello` carries a
 *   trusted proof for the current epoch.
 * @param clock wall-clock time for the trusted proof's epoch (architecture §5.3).
 */
class HandshakeInitiator(
    private val crypto: CryptoProvider,
    private val identity: IdentityKey,
    private val local: LocalPeerInfo,
    private val expectedPeer: ExpectedPeer? = null,
    private val randomness: HandshakeRandomness = HandshakeRandomness.secure(crypto),
    private val clock: HandshakeClock = HandshakeClock.SYSTEM,
) {
    private enum class State { NEW, AWAITING_HELLO_ACK, COMPLETE, FAILED }

    private var state = State.NEW
    private var ephemeral: RawKeyPair? = null
    private var nonceA: ByteArray? = null
    private var helloBytes: ByteArray? = null
    private var hello: HelloMessage? = null
    private var proofEpoch = 0L
    private var completed: HandshakeResult? = null

    /** True once [receiveHelloAck] has succeeded. */
    val isComplete: Boolean get() = state == State.COMPLETE

    /** The verified result. @throws IllegalStateException before [receiveHelloAck] has succeeded. */
    val result: HandshakeResult get() = checkNotNull(completed) { "handshake is not complete" }

    /** Builds the `Hello`. Call once. */
    fun start(): ByteArray {
        check(state == State.NEW) { "start() was already called" }
        val identityKey = identity.publicKey
        check(identityKey.size == HandshakeLimits.KEY_SIZE) { "identity key must be 32 bytes" }
        val pair = randomness.ephemeralKeyPair()
        val nonce = randomness.nonce()
        check(pair.publicKey.size == HandshakeLimits.KEY_SIZE && pair.privateKey.size == HandshakeLimits.KEY_SIZE) {
            "ephemeral key pair must be 32-byte X25519 keys"
        }
        check(nonce.size == HandshakeLimits.NONCE_SIZE) { "nonce must be ${HandshakeLimits.NONCE_SIZE} bytes" }
        val commitment = KeySchedule.commitment(crypto, pair.publicKey, nonce)
        val secret = expectedPeer?.recognitionSecretBytes
        val epoch = if (secret != null) TrustedProof.epochOf(clock.unixSeconds()) else 0L
        val proof = secret?.let { TrustedProof.proof(crypto, it, epoch, identityKey, commitment) }
        val message =
            HelloMessage(
                version = HandshakeLimits.VERSION,
                identityKey = identityKey,
                commitment = commitment,
                caps = local.caps,
                nickname = local.nickname,
                platform = local.platform,
                aead = local.aeadPreference.wireId,
                trustedProof = proof,
            )
        val bytes = message.encode()
        ephemeral = pair
        nonceA = nonce.copyOf()
        hello = message
        helloBytes = bytes
        proofEpoch = epoch
        state = State.AWAITING_HELLO_ACK
        return bytes.copyOf()
    }

    /**
     * Verifies the responder's `HelloAck` and returns the `HelloReveal` to send. Afterwards [result] is available;
     * the result's keys are usable at once (the Finished exchange confirms them, N2).
     *
     * @throws HandshakeException if the message is malformed, of another version, from an unexpected or reflected
     *   identity, badly signed, carries an unsolicited trust ack, or yields a weak X25519 secret.
     * @throws CryptoException if a local primitive fails (for example the identity key cannot sign).
     */
    fun receiveHelloAck(bytes: ByteArray): ByteArray {
        check(state == State.AWAITING_HELLO_ACK) { "not waiting for HelloAck (state $state)" }
        try {
            return processHelloAck(bytes.copyOf())
        } catch (e: Exception) {
            // Peer errors arrive as HandshakeException; anything else is a local failure. Either way this
            // handshake is over.
            fail()
            throw e
        }
    }

    private fun processHelloAck(ackBytes: ByteArray): ByteArray {
        val helloBytes = checkNotNull(helloBytes)
        val hello = checkNotNull(hello)
        val pair = checkNotNull(ephemeral)
        val nonceA = checkNotNull(nonceA)
        val ack = HelloAckMessage.decode(ackBytes)
        if (ack.version != HandshakeLimits.VERSION) {
            throw HandshakeException(HandshakeFailure.VERSION_MISMATCH, "peer speaks version ${ack.version}")
        }
        if (constantTimeEquals(ack.identityKey, hello.identityKey)) {
            throw HandshakeException(HandshakeFailure.REFLECTED_IDENTITY, "peer uses this device's identity key")
        }
        if (expectedPeer != null && !constantTimeEquals(ack.identityKey, expectedPeer.identityKeyBytes)) {
            throw HandshakeException(HandshakeFailure.PEER_IDENTITY_MISMATCH, "responder is not the expected device")
        }
        val ackSigned = KeySchedule.transcriptHash(crypto, helloBytes, ack.withoutSignature().encode())
        if (!crypto.ed25519Verify(ack.identityKey, KeySchedule.ackSignatureInput(ackSigned), checkNotNull(ack.signature))) {
            throw HandshakeException(HandshakeFailure.BAD_SIGNATURE, "HelloAck signature does not verify")
        }
        val secret = expectedPeer?.recognitionSecretBytes
        val trustAck = ack.trustAck
        val peerProvedTrust =
            when {
                trustAck == null -> {
                    false
                }

                secret == null -> {
                    throw HandshakeException(HandshakeFailure.UNEXPECTED_TRUST_ACK, "trust ack without a trusted proof")
                }

                else -> {
                    TrustedProof.verifyAck(crypto, secret, proofEpoch, ack.identityKey, hello.commitment, trustAck)
                }
            }

        val sharedSecret =
            try {
                crypto.x25519(pair.privateKey, ack.ephemeralKey)
            } catch (e: CryptoException) {
                throw HandshakeException(HandshakeFailure.WEAK_KEY, "X25519 with the responder's ephemeral key failed", e)
            }
        try {
            val revealUnsigned = HelloRevealMessage(pair.publicKey, nonceA)
            val revealSigned = KeySchedule.transcriptHash(crypto, helloBytes, ackBytes, revealUnsigned.encode())
            val signature = identity.sign(KeySchedule.revealSignatureInput(revealSigned))
            val revealBytes = revealUnsigned.withSignature(signature).encode()
            val transcript = KeySchedule.transcriptHash(crypto, helloBytes, ackBytes, revealBytes)
            val secrets = KeySchedule.derive(crypto, sharedSecret, nonceA, ack.nonce, transcript)
            completed =
                HandshakeResult(
                    crypto = crypto,
                    role = HandshakeRole.INITIATOR,
                    localIdentityKey = hello.identityKey,
                    peerIdentityKey = ack.identityKey,
                    peerVersion = ack.version,
                    peerCaps = ack.caps,
                    peerNickname = ack.nickname,
                    peerPlatform = ack.platform,
                    sas =
                        KeySchedule.sas(
                            crypto,
                            identityKeyA = hello.identityKey,
                            identityKeyB = ack.identityKey,
                            ephemeralKeyA = pair.publicKey,
                            ephemeralKeyB = ack.ephemeralKey,
                            nonceA = nonceA,
                            nonceB = ack.nonce,
                        ),
                    aead = AeadAlgorithm.negotiate(local.aeadPreference, checkNotNull(AeadAlgorithm.fromWireId(ack.aead))),
                    sendKeyBytes = secrets.keyAToB,
                    receiveKeyBytes = secrets.keyBToA,
                    localFinishedKey = secrets.finishedKeyA,
                    peerFinishedKey = secrets.finishedKeyB,
                    recognitionSecret = secrets.recognitionSecret,
                    transcriptHash = transcript,
                    peerProvedTrust = peerProvedTrust,
                )
            state = State.COMPLETE
            wipeEphemeral()
            return revealBytes
        } finally {
            sharedSecret.fill(0)
        }
    }

    private fun fail() {
        state = State.FAILED
        wipeEphemeral()
    }

    private fun wipeEphemeral() {
        ephemeral?.privateKey?.fill(0)
        ephemeral = null
    }
}
