@file:OptIn(ExperimentalSerializationApi::class)

package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.cbor.DeterministicCbor
import com.constrivo.drop.core.crypto.cbor.MalformedCborException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/*
 * Wire format of the three plaintext handshake messages (architecture §6.2 as changed by N1 and N2).
 *
 * Each is a deterministic CBOR map with small integer keys, declared here in increasing key order. Optional fields
 * are omitted when absent. Signatures are the last key, so "the message without its signature" is the same map
 * with that key left out. Field validation runs in `init`, so it also runs while decoding.
 */

/** `Hello`, initiator → responder. Carries a commitment to the ephemeral key instead of the key itself (N1). */
@Serializable
internal class HelloMessage(
    @CborLabel(1) val version: Int,
    @CborLabel(2) val identityKey: ByteArray,
    @CborLabel(3) val commitment: ByteArray,
    @CborLabel(4) val caps: Int,
    @CborLabel(5) val nickname: String,
    @CborLabel(6) val platform: Int,
    @CborLabel(7) val aead: Int,
    @CborLabel(8) val trustedProof: ByteArray? = null,
) {
    init {
        require(version >= 0) { "version must be non-negative" }
        requireSize(identityKey, HandshakeLimits.KEY_SIZE, "identity_pk")
        requireSize(commitment, HandshakeLimits.HASH_SIZE, "commitment")
        validatePeerFields(caps, nickname, platform, aead)
        trustedProof?.let { requireSize(it, HandshakeLimits.PROOF_SIZE, "trusted proof") }
    }

    fun encode(): ByteArray = DeterministicCbor.encode(serializer(), this)

    companion object {
        fun decode(bytes: ByteArray): HelloMessage = decodeMessage(serializer(), bytes, "Hello")
    }
}

/** `HelloAck`, responder → initiator. [signature] is null only while building the bytes it signs. */
@Serializable
internal class HelloAckMessage(
    @CborLabel(1) val version: Int,
    @CborLabel(2) val identityKey: ByteArray,
    @CborLabel(3) val ephemeralKey: ByteArray,
    @CborLabel(4) val nonce: ByteArray,
    @CborLabel(5) val caps: Int,
    @CborLabel(6) val nickname: String,
    @CborLabel(7) val platform: Int,
    @CborLabel(8) val aead: Int,
    @CborLabel(9) val trustAck: ByteArray? = null,
    @CborLabel(10) val signature: ByteArray? = null,
) {
    init {
        require(version >= 0) { "version must be non-negative" }
        requireSize(identityKey, HandshakeLimits.KEY_SIZE, "identity_pk")
        requireSize(ephemeralKey, HandshakeLimits.KEY_SIZE, "eph_pk")
        requireSize(nonce, HandshakeLimits.NONCE_SIZE, "nonce")
        validatePeerFields(caps, nickname, platform, aead)
        trustAck?.let { requireSize(it, HandshakeLimits.PROOF_SIZE, "trust ack") }
        signature?.let { requireSize(it, HandshakeLimits.SIGNATURE_SIZE, "signature") }
    }

    fun withoutSignature(): HelloAckMessage =
        HelloAckMessage(version, identityKey, ephemeralKey, nonce, caps, nickname, platform, aead, trustAck, null)

    fun withSignature(signature: ByteArray): HelloAckMessage =
        HelloAckMessage(version, identityKey, ephemeralKey, nonce, caps, nickname, platform, aead, trustAck, signature)

    fun encode(): ByteArray = DeterministicCbor.encode(serializer(), this)

    companion object {
        fun decode(bytes: ByteArray): HelloAckMessage =
            decodeMessage(serializer(), bytes, "HelloAck").also {
                if (it.signature == null) throw HandshakeException(HandshakeFailure.MALFORMED_MESSAGE, "HelloAck has no signature")
            }
    }
}

/** `HelloReveal`, initiator → responder: opens the commitment and signs the whole transcript (N1, N2). */
@Serializable
internal class HelloRevealMessage(
    @CborLabel(1) val ephemeralKey: ByteArray,
    @CborLabel(2) val nonce: ByteArray,
    @CborLabel(3) val signature: ByteArray? = null,
) {
    init {
        requireSize(ephemeralKey, HandshakeLimits.KEY_SIZE, "eph_pk")
        requireSize(nonce, HandshakeLimits.NONCE_SIZE, "nonce")
        signature?.let { requireSize(it, HandshakeLimits.SIGNATURE_SIZE, "signature") }
    }

    fun withoutSignature(): HelloRevealMessage = HelloRevealMessage(ephemeralKey, nonce, null)

    fun withSignature(signature: ByteArray): HelloRevealMessage = HelloRevealMessage(ephemeralKey, nonce, signature)

    fun encode(): ByteArray = DeterministicCbor.encode(serializer(), this)

    companion object {
        fun decode(bytes: ByteArray): HelloRevealMessage =
            decodeMessage(serializer(), bytes, "HelloReveal").also {
                if (it.signature == null) {
                    throw HandshakeException(HandshakeFailure.MALFORMED_MESSAGE, "HelloReveal has no signature")
                }
            }
    }
}

private fun <T> decodeMessage(
    serializer: KSerializer<T>,
    bytes: ByteArray,
    name: String,
): T =
    try {
        DeterministicCbor.decode(serializer, bytes, HandshakeLimits.MAX_MESSAGE_SIZE, maxDepth = 1)
    } catch (e: MalformedCborException) {
        throw HandshakeException(HandshakeFailure.MALFORMED_MESSAGE, "malformed $name: ${e.message}", e)
    }

private fun requireSize(
    bytes: ByteArray,
    size: Int,
    what: String,
) {
    require(bytes.size == size) { "$what must be $size bytes, was ${bytes.size}" }
}

private fun validatePeerFields(
    caps: Int,
    nickname: String,
    platform: Int,
    aead: Int,
) {
    require(caps in 0..HandshakeLimits.MAX_CAPS) { "caps must be a 16-bit value" }
    require(nickname.encodeToByteArray().size <= HandshakeLimits.MAX_NICKNAME_BYTES) {
        "nickname longer than ${HandshakeLimits.MAX_NICKNAME_BYTES} UTF-8 bytes"
    }
    require(platform in 0..HandshakeLimits.MAX_PLATFORM) { "platform code must fit in 3 bits" }
    require(AeadAlgorithm.fromWireId(aead) != null) { "unknown AEAD id $aead" }
}
