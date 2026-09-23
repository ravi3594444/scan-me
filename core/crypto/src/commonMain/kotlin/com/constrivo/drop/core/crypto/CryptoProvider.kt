package com.constrivo.drop.core.crypto

/**
 * The cryptographic primitives the shared core needs (architecture §6, §7.1, §13).
 *
 * All keys and outputs are raw bytes: X25519 and Ed25519 keys are 32 bytes, Ed25519 signatures 64 bytes,
 * SHA-256 and HMAC-SHA256 outputs 32 bytes. Implementations must be thread-safe.
 */
interface CryptoProvider {
    /** Cryptographically secure random bytes. */
    fun randomBytes(size: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** HKDF-SHA256 extract-and-expand (RFC 5869). [length] is at most 255 × 32 bytes. */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray

    fun generateX25519(): RawKeyPair

    /** X25519 shared secret (RFC 7748). Throws [CryptoException] if the result is all zeros. */
    fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray

    fun generateEd25519(): RawKeyPair

    fun ed25519Sign(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray

    fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean

    /** An AEAD instance bound to [key] (32 bytes for both algorithms). */
    fun aead(
        algorithm: AeadAlgorithm,
        key: ByteArray,
    ): Aead

    /** True when AES-GCM is hardware-accelerated; otherwise frames use ChaCha20-Poly1305 (architecture §7.1). */
    val hasAesHardware: Boolean
}

/** A raw key pair. [privateKey] never leaves the device (architecture §13). */
class RawKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/**
 * The two frame AEADs (architecture §7.1). [wireId] is the value carried as the AEAD preference in the handshake
 * (`Hello` / `HelloAck`, architecture §6.2); it is part of the signed transcript, so it cannot be downgraded.
 */
enum class AeadAlgorithm(
    val keySize: Int,
    val nonceSize: Int,
    val tagSize: Int,
    val wireId: Int,
) {
    AES_256_GCM(32, 12, 16, 1),
    CHACHA20_POLY1305(32, 12, 16, 2),
    ;

    companion object {
        /** The algorithm with [wireId], or null for an unknown id. */
        fun fromWireId(wireId: Int): AeadAlgorithm? = entries.firstOrNull { it.wireId == wireId }

        /**
         * The algorithm both sides use (architecture §7.1): AES-256-GCM unless either side lacks AES hardware
         * (prefers ChaCha20-Poly1305). The rule is symmetric, so both peers derive the same choice.
         */
        fun negotiate(
            local: AeadAlgorithm,
            peer: AeadAlgorithm,
        ): AeadAlgorithm = if (local == AES_256_GCM && peer == AES_256_GCM) AES_256_GCM else CHACHA20_POLY1305

        /** The preference a device advertises: AES-256-GCM when [hasAesHardware], else ChaCha20-Poly1305. */
        fun preferred(hasAesHardware: Boolean): AeadAlgorithm = if (hasAesHardware) AES_256_GCM else CHACHA20_POLY1305
    }
}

/** Authenticated encryption with associated data. Nonces must never repeat for one key. */
interface Aead {
    val algorithm: AeadAlgorithm

    /** Returns ciphertext ‖ 16-byte tag. */
    fun seal(
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = EMPTY,
    ): ByteArray

    /** Returns the plaintext, or throws [CryptoException] if authentication fails. */
    fun open(
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray = EMPTY,
    ): ByteArray

    companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * A cryptographic check failed or input could not be processed. Subclasses name the layer that failed:
 * [com.constrivo.drop.core.crypto.handshake.HandshakeException], [com.constrivo.drop.core.crypto.qr.QrPayloadException]
 * and [com.constrivo.drop.core.crypto.frame.FrameLimitException]. Every decoder in this module reports malformed input
 * with one of these types, never with an index or arithmetic exception.
 */
open class CryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
