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
 * `proof = HMAC-SHA256(recognition_secret, "drop-proof-v1" ‖ u64be(epoch) ‖ identity_pk_A ‖ commitment)`
 *
 * where `epoch = floor(unix_time / 900)` is the initiator's current beacon epoch (§5.3). The responder accepts a
 * proof for its own epoch or an adjacent one ([EPOCH_TOLERANCE]), so a captured `Hello` stops working within
 * [REPLAY_WINDOW_SECONDS]. Within that window the responder remembers every `(identity_pk_A, commitment)` it accepted
 * (see `HandshakeGuard`) and refuses an exact repeat, so a `Hello` works once. When the responder accepts a proof it
 * adds to its `HelloAck`
 *
 * `ack = HMAC-SHA256(recognition_secret, "drop-proof-ack-v1" ‖ u64be(epoch) ‖ identity_pk_B ‖ commitment)`
 *
 * with the epoch the proof verified under, so the initiator learns that the pairing is still held on the other side
 * (for example, not forgotten). Both values sit under the handshake signatures, so a proof or ack cannot be moved to
 * another session.
 */
object TrustedProof {
    /** Proof and ack length (a full HMAC-SHA256 output). */
    const val SIZE: Int = 32

    /** Recognition secret length. */
    const val SECRET_SIZE: Int = 32

    /** Length of a proof epoch: the 15-minute beacon epoch of architecture §5.3. */
    const val EPOCH_SECONDS: Long = 900

    /** A proof made in epoch `e` is accepted while the responder's epoch is `e - 1`, `e` or `e + 1`. */
    const val EPOCH_TOLERANCE: Long = 1

    /**
     * How long a proof can stay acceptable after the responder first accepts it, 45 minutes. It is accepted at most
     * one epoch early, and stays valid until one epoch after its own ends.
     */
    const val REPLAY_WINDOW_SECONDS: Long = (2 * EPOCH_TOLERANCE + 1) * EPOCH_SECONDS

    private val PROOF_LABEL = "drop-proof-v1".encodeToByteArray()
    private val ACK_LABEL = "drop-proof-ack-v1".encodeToByteArray()

    /** The proof epoch `floor(unixSeconds / 900)`. */
    fun epochOf(unixSeconds: Long): Long = unixSeconds.floorDiv(EPOCH_SECONDS)

    /** The initiator's proof for a `Hello` with [commitment], sent by [initiatorIdentityKey] in [epoch]. */
    fun proof(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        epoch: Long,
        initiatorIdentityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray = mac(crypto, recognitionSecret, PROOF_LABEL, epoch, initiatorIdentityKey, commitment)

    /** Checks [proof] for exactly [epoch], in constant time. False for wrong sizes or a negative epoch. */
    fun verifyProof(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        epoch: Long,
        initiatorIdentityKey: ByteArray,
        commitment: ByteArray,
        proof: ByteArray,
    ): Boolean =
        validInputs(recognitionSecret, epoch, initiatorIdentityKey, commitment) &&
            constantTimeEquals(proof(crypto, recognitionSecret, epoch, initiatorIdentityKey, commitment), proof)

    /**
     * The epoch, among the responder's current one ± [EPOCH_TOLERANCE] at [nowUnixSeconds], under which [proof]
     * verifies, or null if none does.
     */
    fun acceptedEpoch(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        nowUnixSeconds: Long,
        initiatorIdentityKey: ByteArray,
        commitment: ByteArray,
        proof: ByteArray,
    ): Long? {
        val current = epochOf(nowUnixSeconds)
        return (current - EPOCH_TOLERANCE..current + EPOCH_TOLERANCE).firstOrNull { epoch ->
            verifyProof(crypto, recognitionSecret, epoch, initiatorIdentityKey, commitment, proof)
        }
    }

    /** The responder's acknowledgement that it accepted the proof for [commitment] made in [epoch]. */
    fun ack(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        epoch: Long,
        responderIdentityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray = mac(crypto, recognitionSecret, ACK_LABEL, epoch, responderIdentityKey, commitment)

    /** Checks [ack] in constant time. False for wrong sizes or a negative epoch. */
    fun verifyAck(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
        epoch: Long,
        responderIdentityKey: ByteArray,
        commitment: ByteArray,
        ack: ByteArray,
    ): Boolean =
        validInputs(recognitionSecret, epoch, responderIdentityKey, commitment) &&
            constantTimeEquals(ack(crypto, recognitionSecret, epoch, responderIdentityKey, commitment), ack)

    private fun mac(
        crypto: CryptoProvider,
        secret: ByteArray,
        label: ByteArray,
        epoch: Long,
        identityKey: ByteArray,
        commitment: ByteArray,
    ): ByteArray {
        require(validInputs(secret, epoch, identityKey, commitment)) {
            "recognition secret, identity key and commitment must be $SECRET_SIZE, 32 and 32 bytes, and the epoch non-negative"
        }
        return crypto.hmacSha256(secret, label + u64(epoch) + identityKey + commitment)
    }

    private fun validInputs(
        secret: ByteArray,
        epoch: Long,
        identityKey: ByteArray,
        commitment: ByteArray,
    ): Boolean = secret.size == SECRET_SIZE && epoch >= 0 && identityKey.size == 32 && commitment.size == 32

    private fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
}
