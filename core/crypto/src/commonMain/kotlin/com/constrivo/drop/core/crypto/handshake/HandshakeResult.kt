package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.SynchronizedLock
import com.constrivo.drop.core.crypto.constantTimeEquals
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.frame.FrameCipher

/**
 * The outcome of a verified handshake (architecture §6.2, spec changes N1–N3), seen from one side.
 *
 * Every `peer*` value comes from a message whose signature verified, so it can be trusted as the claim of the device
 * holding [peerIdentityKey]. Whether that device is *trusted* is a separate question: it is trusted if the local trust
 * store holds [peerIdentityKey] (first pairing: the users compared [sas]; or a verified QR code, F-B5).
 *
 * Key confirmation (N2): each side's first encrypted control payload (stream 0, counter 0) is [localFinished]; on
 * receiving the peer's, call [verifyPeerFinished] before acting on anything else from the peer.
 *
 * Frame keys: [frameSender] and [frameReceiver] are the only way to use the directional keys, which never leave this
 * object. A session hands out one sender per stream id, so a (key, nonce) pair can never repeat, and accepts one
 * opened stream per stream id, so a stream cannot be replayed onto a new connection. A new link generation after a
 * reconnect needs a new handshake (N3).
 */
class HandshakeResult internal constructor(
    private val crypto: CryptoProvider,
    /** The role this device played. */
    val role: HandshakeRole,
    localIdentityKey: ByteArray,
    peerIdentityKey: ByteArray,
    /** The peer's handshake version (always [HandshakeLimits.VERSION] in this build). */
    val peerVersion: Int,
    /** The peer's verified capability flags (architecture §5.2). */
    val peerCaps: Int,
    /** The peer's verified nickname. Display it; never use it to decide trust (T-13). */
    val peerNickname: String,
    /** The peer's verified platform code (architecture §5.1). */
    val peerPlatform: Int,
    /**
     * The six-digit short authentication string, the same on both devices unless someone is in the middle (F-B3).
     *
     * When the peer is not yet trusted, show it as soon as this result exists, on both sides, before and regardless
     * of the Finished exchange: a code that only appears after Finished would let a man in the middle drop the
     * session unseen whenever its guess is wrong. Never retry an untrusted handshake automatically; each retry is a
     * fresh guess (architecture §6 note, [PairingAttemptLimiter]).
     */
    val sas: String,
    /** The negotiated frame AEAD (architecture §7.1). */
    val aead: AeadAlgorithm,
    private val sendKeyBytes: ByteArray,
    private val receiveKeyBytes: ByteArray,
    private val localFinishedKey: ByteArray,
    private val peerFinishedKey: ByteArray,
    recognitionSecret: ByteArray,
    transcriptHash: ByteArray,
    /**
     * Whether the peer proved it holds this pairing's recognition secret: for the responder, the `Hello` carried a
     * valid trusted proof; for the initiator, the `HelloAck` carried a valid trust ack in answer to its proof.
     */
    val peerProvedTrust: Boolean,
) {
    private val localIdentityKeyBytes = localIdentityKey.copyOf()
    private val peerIdentityKeyBytes = peerIdentityKey.copyOf()
    private val peerDeviceIdBytes = crypto.deviceId(peerIdentityKey)
    private val recognitionSecretBytes = recognitionSecret.copyOf()
    private val transcriptHashBytes = transcriptHash.copyOf()
    private val lock = SynchronizedLock()
    private val sendStreams = HashSet<Int>()
    private val receiveStreams = HashSet<Int>()
    private var peerFinishedVerified = false

    /** This device's identity key. */
    val localIdentityKey: ByteArray get() = localIdentityKeyBytes.copyOf()

    /** The peer's verified 32-byte Ed25519 identity key. */
    val peerIdentityKey: ByteArray get() = peerIdentityKeyBytes.copyOf()

    /** `device_id` of the peer: SHA-256(identity_pk)[0..16] (architecture §5.3). */
    val peerDeviceId: ByteArray get() = peerDeviceIdBytes.copyOf()

    /**
     * The key this device seals frames with (`k_A→B` for the initiator, `k_B→A` for the responder). Internal (tests
     * and golden vectors): a cipher built from the raw key outside [frameSender] would restart the nonce counter.
     */
    internal val sendKey: ByteArray get() = sendKeyBytes.copyOf()

    /** The key this device opens the peer's frames with. Internal, like [sendKey]. */
    internal val receiveKey: ByteArray get() = receiveKeyBytes.copyOf()

    /**
     * `HKDF(k_session, "drop-recog-v1")`. Store it as the pairing's recognition secret only when this session creates
     * the pairing (SAS confirmed or QR verified); later sessions keep the stored value.
     */
    val recognitionSecret: ByteArray get() = recognitionSecretBytes.copyOf()

    /** SHA-256 transcript hash over the three exact handshake messages. */
    val transcriptHash: ByteArray get() = transcriptHashBytes.copyOf()

    /** True once [verifyPeerFinished] has accepted the peer's Finished MAC. */
    val isPeerConfirmed: Boolean get() = lock.withLock { peerFinishedVerified }

    /** This device's Finished MAC: `HMAC(finished key for its direction, transcript_hash)`. */
    fun localFinished(): ByteArray = KeySchedule.finishedMac(crypto, localFinishedKey, transcriptHashBytes)

    /**
     * Checks the peer's Finished MAC in constant time.
     *
     * @throws HandshakeException with [HandshakeFailure.BAD_FINISHED] if it does not match.
     */
    fun verifyPeerFinished(mac: ByteArray) {
        val expected = KeySchedule.finishedMac(crypto, peerFinishedKey, transcriptHashBytes)
        if (!constantTimeEquals(expected, mac)) {
            throw HandshakeException(HandshakeFailure.BAD_FINISHED, "peer Finished MAC does not match the transcript")
        }
        lock.withLock { peerFinishedVerified = true }
    }

    /**
     * True if [entered] is this session's SAS (surrounding whitespace and inner spaces ignored), compared in constant
     * time. A user who types the other device's code gets false when a man in the middle is present (T-11).
     */
    fun sasMatches(entered: String): Boolean {
        val normalized = entered.filterNot { it.isWhitespace() }
        return constantTimeEquals(normalized.encodeToByteArray(), sas.encodeToByteArray())
    }

    /**
     * The sealer for [streamId] under [sendKey].
     *
     * @throws IllegalStateException if a sealer for [streamId] was already created in this session.
     * @throws IllegalArgumentException if [streamId] is negative.
     */
    fun frameSender(streamId: Int): FrameCipher {
        require(streamId >= 0) { "stream id must be non-negative, was $streamId" }
        lock.withLock {
            check(sendStreams.add(streamId)) {
                "stream $streamId already has a sender in this session; a new link generation needs a new handshake (N3)"
            }
        }
        return FrameCipher.sealer(crypto, aead, sendKeyBytes, streamId)
    }

    /**
     * An opener for [streamId] under [receiveKey].
     *
     * The stream id is taken only when the opener's first frame authenticates, so a forged or corrupted first frame
     * on a new connection (the opener then fails closed) does not use up the id: the protocol layer drops that
     * connection and calls this again for the next one. Opening creates no nonce, so several openers may wait for a
     * first frame at once; the first to authenticate one wins, and any other opener of that stream id then refuses
     * every frame with [com.constrivo.drop.core.crypto.CryptoException]. Once a stream has been opened, a new
     * connection for the same id (for example a replay of the old one) always fails.
     *
     * @throws IllegalArgumentException if [streamId] is negative.
     */
    fun frameReceiver(streamId: Int): FrameCipher {
        require(streamId >= 0) { "stream id must be non-negative, was $streamId" }
        return FrameCipher.opener(crypto, aead, receiveKeyBytes, streamId) {
            lock.withLock { receiveStreams.add(streamId) }
        }
    }

    /** True once an opener of [streamId] has authenticated its first frame in this session. */
    fun isReceiveStreamOpen(streamId: Int): Boolean = lock.withLock { streamId in receiveStreams }

    override fun toString(): String = "HandshakeResult(role=$role, peer=${peerNickname.take(16)}, aead=$aead)"
}
