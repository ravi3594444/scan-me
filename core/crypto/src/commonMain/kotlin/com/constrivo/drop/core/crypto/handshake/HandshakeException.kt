package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.CryptoException

/** Why a handshake was refused (architecture §6.2). The state machine that threw is unusable afterwards. */
enum class HandshakeFailure {
    /** Not a canonical encoding of the expected message, a field has the wrong size or range, or a field is missing. */
    MALFORMED_MESSAGE,

    /** The peer speaks another handshake version than [HandshakeLimits.VERSION]. */
    VERSION_MISMATCH,

    /** A transcript signature (`sig_A` or `sig_B`) does not verify under the peer's identity key. */
    BAD_SIGNATURE,

    /** The revealed `eph_pk_A ‖ nonce_A` does not match the commitment in `Hello` (N1). */
    COMMITMENT_MISMATCH,

    /** X25519 produced the all-zero secret: the peer sent a low-order ephemeral key. */
    WEAK_KEY,

    /** The peer's identity key differs from the one the caller expected (reconnect, N3; resolved or scanned peer). */
    PEER_IDENTITY_MISMATCH,

    /** The peer claims this device's own identity key (a reflected or looped-back handshake). */
    REFLECTED_IDENTITY,

    /** This device is in Trusted-only mode and the `Hello` carried no valid proof of a pairing (§5.3). */
    TRUST_PROOF_REQUIRED,

    /** The peer's `HelloAck` carried a trust ack although no proof was sent. */
    UNEXPECTED_TRUST_ACK,

    /** The peer's `Finished` MAC does not match the transcript (key confirmation, N2). */
    BAD_FINISHED,
}

/** A handshake was refused; [reason] says why. Messages are for logs, not for users. */
class HandshakeException(
    val reason: HandshakeFailure,
    message: String,
    cause: Throwable? = null,
) : CryptoException("$reason: $message", cause)
