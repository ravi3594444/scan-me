package com.constrivo.drop.core.crypto.trust

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.constantTimeEquals

/**
 * Proofs of an existing pairing, carried in the plaintext handshake (architecture §5.3 "Trusted-only visibility";
 * spec change S3).
 *
 * Both devices store the per-pair `recognition_secret` (32 bytes, `HKDF(k_session, "drop-recog-v1")` from the
 * pairing session). A device in Trusted-only mode answers a `Hello` only if it carries
 *
 * `proof = HMAC-SHA256(recognition_secret, "drop-proof-v1" ‖ identity_pk_A ‖ commitment)`
 *
 * The commitment is fresh per handshake, so a proof is bound to one `Hello`. When the responder accepts a proof it
 * adds to its `HelloAck`
 *
 * `ack = HMAC-SHA256(recognition_secret, "drop-proof-ack-v1" ‖ identity_pk_B ‖ commitment)`
 *
 * so the initiator learns that the pairing is still held on the other side (for example, not forgotten).
 * Both values sit under the handshake signatures, so a proof or ack cannot be moved to another session.
 */
object TrustedProof {
    /** Proof and ack length (a full HMAC-SHA256 output). */
    const val SIZE: Int = 32

    /** Recognition secret length. */
    const val SECRET_SIZE: Int = 32

    private val PROOF_LABEL = "drop-proof-v1".encodeToByteArray()
    private val ACK_LABEL = "drop-proof-ack-v1".encodeToByteArray()

    /** The initiator's proof for a `Hello` with [commitment], sent by [initiatorIdentityKey]. */
    fun proof(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        initiatorIdentityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray = mac(crypto, recognitionSecret, PROOF_LABEL, initiatorIdentityKey, commitment)

    /** Checks [proof] in constant time. False for wrong sizes. */
    fun verifyProof(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        initiatorIdentityKey: ByteArray,
        commitment: ByteArray,
        proof: ByteArray,
    ): Boolean =
        validInputs(recognitionSecret, initiatorIdentityKey, commitment) &&
            constantTimeEquals(proof(crypto, recognitionSecret, initiatorIdentityKey, commitment), proof)

    /** The responder's acknowledgement that it accepted the proof for [commitment]. */
    fun ack(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        responderIdentityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray = mac(crypto, recognitionSecret, ACK_LABEL, responderIdentityKey, commitment)

    /** Checks [ack] in constant time. False for wrong sizes. */
    fun verifyAck(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        responderIdentityKey: ByteArray,
        commitment: ByteArray,
        ack: ByteArray,
    ): Boolean =
        validInputs(recognitionSecret, responderIdentityKey, commitment) &&
            constantTimeEquals(ack(crypto, recognitionSecret, responderIdentityKey, commitment), ack)

    private fun mac(
        crypto: CryptoProvider,
        secret: ByteArray,
        label: ByteArray,
        identityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray {
        require(validInputs(secret, identityKey, commitment)) {
            "recognition secret, identity key and commitment must be $SECRET_SIZE, 32 and 32 bytes"
        }
        return crypto.hmacSha256(secret, label + identityKey + commitment)
    }

    private fun validInputs(
        secret: ByteArray,
        identityKey: ByteArray,
        commitment: ByteArray,
    ): Boolean = secret.size == SECRET_SIZE && identityKey.size == 32 && commitment.size == 32
}
