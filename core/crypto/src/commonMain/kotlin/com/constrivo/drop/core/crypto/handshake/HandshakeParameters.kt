package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.RawKeyPair
import com.constrivo.drop.core.crypto.trust.TrustedProof

/** Sizes, ranges and the version of the handshake wire format (architecture §6.2). */
object HandshakeLimits {
    /** The only handshake version this build speaks. */
    const val VERSION: Int = 1

    /** Ed25519 and X25519 public keys. */
    const val KEY_SIZE: Int = 32

    /** `nonce_A`, `nonce_B`. */
    const val NONCE_SIZE: Int = 16

    /** SHA-256 outputs: commitment and transcript hash. */
    const val HASH_SIZE: Int = 32

    /** Ed25519 signatures. */
    const val SIGNATURE_SIZE: Int = 64

    /** Trusted proof and trust ack (HMAC-SHA256). */
    const val PROOF_SIZE: Int = TrustedProof.SIZE

    /** Finished MAC (HMAC-SHA256). */
    const val FINISHED_SIZE: Int = 32

    /** Nickname limit in UTF-8 bytes; use [fitNickname] to truncate at a code-point boundary. */
    const val MAX_NICKNAME_BYTES: Int = 64

    /** Capability flags are 16 bits (architecture §5.2). */
    const val MAX_CAPS: Int = 0xFFFF

    /** Platform codes are 3 bits (architecture §5.1 offset 24). */
    const val MAX_PLATFORM: Int = 7

    /** No handshake message may be longer than this; longer input is rejected before parsing. */
    const val MAX_MESSAGE_SIZE: Int = 1024

    /** [nickname] truncated to at most [MAX_NICKNAME_BYTES] UTF-8 bytes without splitting a code point. */
    fun fitNickname(nickname: String): String {
        if (nickname.encodeToByteArray().size <= MAX_NICKNAME_BYTES) return nickname
        var end = 0
        var bytes = 0
        while (end < nickname.length) {
            val high = nickname[end]
            val step = if (high.isHighSurrogate() && end + 1 < nickname.length && nickname[end + 1].isLowSurrogate()) 2 else 1
            val size = nickname.substring(end, end + step).encodeToByteArray().size
            if (bytes + size > MAX_NICKNAME_BYTES) break
            bytes += size
            end += step
        }
        return nickname.substring(0, end)
    }
}

/**
 * What this device announces in its `Hello` or `HelloAck`. The peer trusts these values only after the handshake
 * signatures verify, and reads them from [HandshakeResult].
 *
 * @property caps capability flags (architecture §5.2), 0..0xFFFF.
 * @property nickname at most [HandshakeLimits.MAX_NICKNAME_BYTES] UTF-8 bytes ([HandshakeLimits.fitNickname]).
 * @property platform platform code (architecture §5.1: 0 phone, 1 laptop, 2 desktop, 3 browser proxy), 0..7.
 * @property aeadPreference [AeadAlgorithm.AES_256_GCM] when the device has AES hardware, otherwise
 *   [AeadAlgorithm.CHACHA20_POLY1305]; see [AeadAlgorithm.preferred].
 */
class LocalPeerInfo(
    val caps: Int,
    val nickname: String,
    val platform: Int,
    val aeadPreference: AeadAlgorithm,
) {
    init {
        require(caps in 0..HandshakeLimits.MAX_CAPS) { "caps must be a 16-bit value" }
        require(nickname.encodeToByteArray().size <= HandshakeLimits.MAX_NICKNAME_BYTES) {
            "nickname longer than ${HandshakeLimits.MAX_NICKNAME_BYTES} UTF-8 bytes; use HandshakeLimits.fitNickname"
        }
        require(platform in 0..HandshakeLimits.MAX_PLATFORM) { "platform code must fit in 3 bits" }
    }

    companion object {
        /** A [LocalPeerInfo] whose AEAD preference follows [CryptoProvider.hasAesHardware]. */
        fun forDevice(
            crypto: CryptoProvider,
            caps: Int,
            nickname: String,
            platform: Int,
        ): LocalPeerInfo =
            LocalPeerInfo(caps, HandshakeLimits.fitNickname(nickname), platform, AeadAlgorithm.preferred(crypto.hasAesHardware))
    }
}

/**
 * The peer an initiator expects to reach: the device behind a resolved beacon or a scanned QR code, or the same
 * device again on a reconnect (spec change N3). The handshake fails with
 * [HandshakeFailure.PEER_IDENTITY_MISMATCH] if the responder's identity key differs.
 *
 * @property identityKey the peer's 32-byte Ed25519 public key.
 * @property recognitionSecret the pairing's recognition secret, if the peer is trusted: the `Hello` then carries a
 *   [TrustedProof] so a peer in Trusted-only mode answers.
 */
class ExpectedPeer(
    identityKey: ByteArray,
    recognitionSecret: ByteArray? = null,
) {
    init {
        require(identityKey.size == HandshakeLimits.KEY_SIZE) { "identity key must be 32 bytes" }
        require(recognitionSecret == null || recognitionSecret.size == TrustedProof.SECRET_SIZE) {
            "recognition secret must be ${TrustedProof.SECRET_SIZE} bytes"
        }
    }

    internal val identityKeyBytes: ByteArray = identityKey.copyOf()
    internal val recognitionSecretBytes: ByteArray? = recognitionSecret?.copyOf()

    /** A copy of the expected identity key. */
    val identityKey: ByteArray get() = identityKeyBytes.copyOf()

    /** Whether the `Hello` will carry a trusted proof. */
    val hasRecognitionSecret: Boolean get() = recognitionSecretBytes != null
}

/** Looks up the recognition secret stored at pairing for a peer's identity key (the responder's trust store). */
fun interface TrustedPeerLookup {
    /** The 32-byte recognition secret for [identityKey], or null if that device is not trusted. */
    fun recognitionSecretFor(identityKey: ByteArray): ByteArray?

    companion object {
        /** No device is trusted. */
        val NONE: TrustedPeerLookup = TrustedPeerLookup { null }
    }
}

/**
 * Fresh per-handshake randomness: one X25519 key pair and one 16-byte nonce per side.
 * Production code uses [secure]; tests inject fixed values to get golden bytes. Never reuse values across handshakes:
 * the state machine takes ownership of the returned key pair and zeroes its private key when the handshake ends.
 */
interface HandshakeRandomness {
    /** A new X25519 key pair (32-byte public and private keys). */
    fun ephemeralKeyPair(): RawKeyPair

    /** A new [HandshakeLimits.NONCE_SIZE]-byte nonce. */
    fun nonce(): ByteArray

    companion object {
        /** Randomness from [crypto]'s secure generator. */
        fun secure(crypto: CryptoProvider): HandshakeRandomness =
            object : HandshakeRandomness {
                override fun ephemeralKeyPair(): RawKeyPair = crypto.generateX25519()

                override fun nonce(): ByteArray = crypto.randomBytes(HandshakeLimits.NONCE_SIZE)
            }
    }
}

/** Which side of the handshake this device played. The initiator is `A`, the responder `B` (architecture §6.2). */
enum class HandshakeRole { INITIATOR, RESPONDER }
