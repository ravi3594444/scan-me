package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.cbor.RawCbor

// Copies with changed fields, re-signing, and raw (possibly invalid) encodings of the handshake messages.

internal fun HelloMessage.copy(
    version: Int = this.version,
    identityKey: ByteArray = this.identityKey,
    commitment: ByteArray = this.commitment,
    caps: Int = this.caps,
    nickname: String = this.nickname,
    platform: Int = this.platform,
    aead: Int = this.aead,
    trustedProof: ByteArray? = this.trustedProof,
): HelloMessage = HelloMessage(version, identityKey, commitment, caps, nickname, platform, aead, trustedProof)

internal fun HelloAckMessage.copy(
    version: Int = this.version,
    identityKey: ByteArray = this.identityKey,
    ephemeralKey: ByteArray = this.ephemeralKey,
    nonce: ByteArray = this.nonce,
    caps: Int = this.caps,
    nickname: String = this.nickname,
    platform: Int = this.platform,
    aead: Int = this.aead,
    trustAck: ByteArray? = this.trustAck,
    signature: ByteArray? = this.signature,
): HelloAckMessage = HelloAckMessage(version, identityKey, ephemeralKey, nonce, caps, nickname, platform, aead, trustAck, signature)

internal fun HelloRevealMessage.copy(
    ephemeralKey: ByteArray = this.ephemeralKey,
    nonce: ByteArray = this.nonce,
    signature: ByteArray? = this.signature,
): HelloRevealMessage = HelloRevealMessage(ephemeralKey, nonce, signature)

/** [ack] signed by [signer] over `Hello ‖ ack-without-sig`, as an honest responder with that key would. */
internal fun resignAck(
    helloBytes: ByteArray,
    ack: HelloAckMessage,
    signer: IdentityKey = TestFixtures.IDENTITY_B,
): ByteArray {
    val unsigned = ack.withoutSignature()
    val hash = KeySchedule.transcriptHash(HandshakeHarness.crypto, helloBytes, unsigned.encode())
    return unsigned.withSignature(signer.sign(KeySchedule.ackSignatureInput(hash))).encode()
}

/** [reveal] signed by [signer] over `Hello ‖ HelloAck ‖ reveal-without-sig`. */
internal fun resignReveal(
    helloBytes: ByteArray,
    ackBytes: ByteArray,
    reveal: HelloRevealMessage,
    signer: IdentityKey,
): ByteArray {
    val unsigned = reveal.withoutSignature()
    val hash = KeySchedule.transcriptHash(HandshakeHarness.crypto, helloBytes, ackBytes, unsigned.encode())
    return unsigned.withSignature(signer.sign(KeySchedule.revealSignatureInput(hash))).encode()
}

/** A `Hello` encoded with the independent test encoder; values need not be valid. Null optionals are omitted. */
internal fun helloMap(
    base: HelloMessage,
    identityKey: ByteArray = base.identityKey,
    commitment: ByteArray = base.commitment,
    caps: Int = base.caps,
    nickname: String = base.nickname,
    platform: Int = base.platform,
    aead: Int = base.aead,
    proof: ByteArray? = base.trustedProof,
): ByteArray {
    val entries =
        mutableListOf<Pair<Int, Any?>>(
            1 to base.version,
            2 to identityKey,
            3 to commitment,
            4 to caps,
            5 to nickname,
            6 to platform,
            7 to aead,
        )
    if (proof != null) entries += 8 to proof
    return RawCbor.encode(RawCbor.map(*entries.toTypedArray()))
}

/** A `HelloAck` encoded with the independent test encoder; values need not be valid. Null optionals are omitted. */
internal fun ackMap(
    base: HelloAckMessage,
    ephemeralKey: ByteArray = base.ephemeralKey,
    nonce: ByteArray = base.nonce,
    trustAck: ByteArray? = base.trustAck,
    signature: ByteArray? = base.signature,
): ByteArray {
    val entries =
        mutableListOf<Pair<Int, Any?>>(
            1 to base.version,
            2 to base.identityKey,
            3 to ephemeralKey,
            4 to nonce,
            5 to base.caps,
            6 to base.nickname,
            7 to base.platform,
            8 to base.aead,
        )
    if (trustAck != null) entries += 9 to trustAck
    if (signature != null) entries += 10 to signature
    return RawCbor.encode(RawCbor.map(*entries.toTypedArray()))
}
