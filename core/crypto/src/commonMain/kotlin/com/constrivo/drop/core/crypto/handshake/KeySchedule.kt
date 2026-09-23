package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.CryptoProvider

/**
 * Hashes, labels and key derivation of the handshake (architecture §6.2 as changed by N1 and N2).
 *
 * All labels are ASCII and are always followed by fixed-size fields or by length-prefixed messages, so no two
 * inputs can collide.
 */
internal object KeySchedule {
    private val COMMIT = label("drop-commit-v1")
    private val TRANSCRIPT = label("drop-transcript-v1")
    private val SIG_ACK = label("drop-sig-ack-v1")
    private val SIG_REVEAL = label("drop-sig-reveal-v1")
    private val SESSION = label("drop-session-v1")
    private val KEY_A_TO_B = label("drop-key-a2b-v1")
    private val KEY_B_TO_A = label("drop-key-b2a-v1")
    private val FINISHED_A = label("drop-finished-a-v1")
    private val FINISHED_B = label("drop-finished-b-v1")
    private val RECOGNITION = label("drop-recog-v1")
    private val SAS = label("drop-sas-v1")
    private val EMPTY = ByteArray(0)

    /** `commitment = SHA-256("drop-commit-v1" ‖ eph_pk_A ‖ nonce_A)` (N1). */
    fun commitment(
        crypto: CryptoProvider,
        ephemeralKeyA: ByteArray,
        nonceA: ByteArray,
    ): ByteArray = crypto.sha256(COMMIT + ephemeralKeyA + nonceA)

    /**
     * `SHA-256("drop-transcript-v1" ‖ Σ u32be(len(m)) ‖ m)` over the exact encoded messages, in order. Used with
     * `Hello ‖ HelloAck-without-sig` (signed by B), `Hello ‖ HelloAck ‖ HelloReveal-without-sig` (signed by A)
     * and `Hello ‖ HelloAck ‖ HelloReveal` (the final transcript hash).
     */
    fun transcriptHash(
        crypto: CryptoProvider,
        vararg messages: ByteArray,
    ): ByteArray {
        var input = TRANSCRIPT
        for (message in messages) input += u32(message.size) + message
        return crypto.sha256(input)
    }

    /** What B signs: `"drop-sig-ack-v1" ‖ transcriptHash(Hello, HelloAck-without-sig)`. */
    fun ackSignatureInput(transcriptHash: ByteArray): ByteArray = SIG_ACK + transcriptHash

    /** What A signs: `"drop-sig-reveal-v1" ‖ transcriptHash(Hello, HelloAck, HelloReveal-without-sig)`. */
    fun revealSignatureInput(transcriptHash: ByteArray): ByteArray = SIG_REVEAL + transcriptHash

    /**
     * `k_session = HKDF-SHA256(ikm = ss, salt = nonce_A ‖ nonce_B, info = "drop-session-v1" ‖ transcript_hash)`,
     * then every other key as `HKDF-SHA256(ikm = k_session, salt = "", info = label)`.
     */
    fun derive(
        crypto: CryptoProvider,
        sharedSecret: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
        transcriptHash: ByteArray,
    ): SessionSecrets {
        val session = crypto.hkdfSha256(sharedSecret, nonceA + nonceB, SESSION + transcriptHash, KEY_SIZE)
        try {
            return SessionSecrets(
                keyAToB = expand(crypto, session, KEY_A_TO_B),
                keyBToA = expand(crypto, session, KEY_B_TO_A),
                finishedKeyA = expand(crypto, session, FINISHED_A),
                finishedKeyB = expand(crypto, session, FINISHED_B),
                recognitionSecret = expand(crypto, session, RECOGNITION),
            )
        } finally {
            session.fill(0)
        }
    }

    /**
     * SAS (F-B3): `u32be(SHA-256("drop-sas-v1" ‖ identity_pk_A ‖ identity_pk_B ‖ eph_pk_A ‖ eph_pk_B ‖ nonce_A ‖
     * nonce_B)[0..4]) mod 10^6`, as six zero-padded decimal digits.
     */
    fun sas(
        crypto: CryptoProvider,
        identityKeyA: ByteArray,
        identityKeyB: ByteArray,
        ephemeralKeyA: ByteArray,
        ephemeralKeyB: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
    ): String {
        val digest = crypto.sha256(SAS + identityKeyA + identityKeyB + ephemeralKeyA + ephemeralKeyB + nonceA + nonceB)
        return formatSas(digest)
    }

    /** The six-digit SAS for [digest]: its first four bytes as a big-endian u32, mod 10^6, zero-padded. */
    fun formatSas(digest: ByteArray): String {
        require(digest.size >= 4) { "digest too short" }
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
    }

    /** `HMAC-SHA256(finished_key, transcript_hash)` (N2). */
    fun finishedMac(
        crypto: CryptoProvider,
        finishedKey: ByteArray,
        transcriptHash: ByteArray,
    ): ByteArray = crypto.hmacSha256(finishedKey, transcriptHash)

    private fun expand(
        crypto: CryptoProvider,
        session: ByteArray,
        info: ByteArray,
    ): ByteArray = crypto.hkdfSha256(session, EMPTY, info, KEY_SIZE)

    private fun u32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun label(text: String): ByteArray = text.encodeToByteArray()

    const val SAS_DIGITS: Int = 6
    private const val SAS_MODULUS = 1_000_000L
    private const val KEY_SIZE = 32
}

/** The keys derived from one handshake. Directions are fixed by role: A is the initiator. */
internal class SessionSecrets(
    val keyAToB: ByteArray,
    val keyBToA: ByteArray,
    val finishedKeyA: ByteArray,
    val finishedKeyB: ByteArray,
    val recognitionSecret: ByteArray,
)
