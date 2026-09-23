package com.constrivo.drop.core.crypto.frame

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.SynchronizedLock

/**
 * Seals or opens the payloads of one stream in one direction (architecture §7.1; spec changes S7, N2, N3).
 *
 * - Key: the directional session key (`k_A→B` or `k_B→A`) from the handshake.
 * - Nonce (12 bytes): `u32 stream_id ‖ u64 counter`, big-endian. Stream ids are reserved by S7:
 *   0 control, 1 Bluetooth, 2.. Wi-Fi data streams. Directional keys keep the two directions apart.
 * - Counter: the sender starts at 0 and adds 1 per sealed frame. Frames carry no counter; the receiver expects
 *   exactly the next value, which holds on an in-order stream (TCP, RFCOMM, GATT with write responses). A replayed,
 *   reordered, dropped or corrupted frame therefore fails authentication and raises [CryptoException].
 * - Associated data: supplied by the caller. The protocol layer passes `length ‖ type ‖ stream_id` of the frame
 *   header (N2), so a header cannot be changed without failing authentication.
 *
 * Limits (per cipher, so per key and stream): at most [MAX_FRAMES] frames (2^32) and [MAX_PLAINTEXT_BYTES] payload
 * bytes (2^38 = 256 GiB). Past either, [seal] throws [FrameLimitException]; the protocol must then run a fresh
 * handshake (N3) for new keys, never restart the counter. The byte limit keeps AES-GCM well inside its usage bounds
 * even with 8 streams sharing one directional key (2^41 bytes, about 2^37 AES blocks, per key).
 *
 * Fail closed: after any failure an opener refuses every further frame; the stream must be torn down.
 * Exactly one sealer and one opener may exist per (key, stream id) — use
 * [com.constrivo.drop.core.crypto.handshake.HandshakeResult.frameSender] and
 * [com.constrivo.drop.core.crypto.handshake.HandshakeResult.frameReceiver], which enforce that.
 *
 * [seal] is safe to call from several threads (each call reserves a distinct counter), but frames must reach the
 * wire in counter order, so a stream should have one writer. [open] must be called in wire order by one reader.
 */
class FrameCipher internal constructor(
    private val aead: Aead,
    /** The stream id placed in every nonce, 0 ≤ id ≤ 2^31 − 1. */
    val streamId: Int,
    /** Whether this instance seals (sender) or opens (receiver). */
    val mode: Mode,
    private val maxFrames: Long = MAX_FRAMES,
    private val maxPlaintextBytes: Long = MAX_PLAINTEXT_BYTES,
) {
    /** Sender or receiver half of a stream. */
    enum class Mode { SEAL, OPEN }

    private val lock = SynchronizedLock()
    private var counter = 0L
    private var bytes = 0L
    private var failed = false

    init {
        require(streamId >= 0) { "stream id must be non-negative, was $streamId" }
        require(maxFrames in 1..MAX_FRAMES) { "maxFrames out of range" }
        require(maxPlaintextBytes in 0..MAX_PLAINTEXT_BYTES) { "maxPlaintextBytes out of range" }
    }

    /** The AEAD algorithm in use. */
    val algorithm: AeadAlgorithm get() = aead.algorithm

    /** The counter the next [seal] or [open] uses. */
    val nextCounter: Long get() = lock.withLock { counter }

    /** Frames left before [FrameLimitException]. */
    val framesRemaining: Long get() = lock.withLock { maxFrames - counter }

    /** Payload bytes left before [FrameLimitException]. */
    val bytesRemaining: Long get() = lock.withLock { maxPlaintextBytes - bytes }

    /**
     * Encrypts [plaintext] as the next frame of this stream and returns `ciphertext ‖ 16-byte tag`.
     *
     * @throws FrameLimitException if the frame or byte limit would be exceeded.
     * @throws IllegalStateException if this is an opener.
     */
    fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        check(mode == Mode.SEAL) { "this FrameCipher opens frames; it cannot seal" }
        val frameCounter =
            lock.withLock {
                if (counter >= maxFrames) throw FrameLimitException("stream $streamId has sealed its $maxFrames frames")
                if (plaintext.size > maxPlaintextBytes - bytes) {
                    throw FrameLimitException("stream $streamId would exceed $maxPlaintextBytes payload bytes")
                }
                bytes += plaintext.size
                counter++
            }
        return aead.seal(nonce(streamId, frameCounter), plaintext, aad)
    }

    /**
     * Decrypts the next frame of this stream (the one with counter [nextCounter]).
     *
     * @throws CryptoException if authentication fails (wrong key, stream, AAD, or a replayed, reordered or
     *   corrupted frame), the frame is shorter than a tag, a limit is exceeded, or an earlier frame failed.
     * @throws IllegalStateException if this is a sealer.
     */
    fun open(
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = lock.withLock { openLocked(null, ciphertext, aad) }

    /**
     * Like [open], for transports that carry the counter explicitly: [counter] must equal [nextCounter].
     *
     * @throws CryptoException if [counter] is not the expected value (replay or reorder), or as for [open].
     */
    fun open(
        counter: Long,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = lock.withLock { openLocked(counter, ciphertext, aad) }

    private fun openLocked(
        claimedCounter: Long?,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        check(mode == Mode.OPEN) { "this FrameCipher seals frames; it cannot open" }
        if (failed) throw CryptoException("stream $streamId already failed; tear it down")
        try {
            if (claimedCounter != null && claimedCounter != counter) {
                throw CryptoException("stream $streamId expected frame $counter, got $claimedCounter (replay or reorder)")
            }
            if (counter >= maxFrames) throw FrameLimitException("stream $streamId has opened its $maxFrames frames")
            val payloadSize = ciphertext.size - aead.algorithm.tagSize
            if (payloadSize < 0) throw CryptoException("frame on stream $streamId is shorter than an AEAD tag")
            if (payloadSize > maxPlaintextBytes - bytes) {
                throw FrameLimitException("stream $streamId would exceed $maxPlaintextBytes payload bytes")
            }
            val plaintext =
                try {
                    aead.open(nonce(streamId, counter), ciphertext, aad)
                } catch (e: CryptoException) {
                    throw CryptoException(
                        "frame $counter on stream $streamId failed authentication (replayed, reordered or corrupted)",
                        e,
                    )
                }
            counter++
            bytes += plaintext.size
            return plaintext
        } catch (e: CryptoException) {
            failed = true
            throw e
        }
    }

    companion object {
        /** Frames per cipher: the counter runs from 0 to 2^32 − 1. */
        const val MAX_FRAMES: Long = 1L shl 32

        /** Payload bytes per cipher (256 GiB). */
        const val MAX_PLAINTEXT_BYTES: Long = 1L shl 38

        /** Nonce length for both AEADs. */
        const val NONCE_SIZE: Int = 12

        /** A sealer for [streamId] under [key] (the local → peer directional key). */
        fun sealer(
            crypto: CryptoProvider,
            algorithm: AeadAlgorithm,
            key: ByteArray,
            streamId: Int,
        ): FrameCipher = FrameCipher(crypto.aead(algorithm, key), streamId, Mode.SEAL)

        /** An opener for [streamId] under [key] (the peer → local directional key). */
        fun opener(
            crypto: CryptoProvider,
            algorithm: AeadAlgorithm,
            key: ByteArray,
            streamId: Int,
        ): FrameCipher = FrameCipher(crypto.aead(algorithm, key), streamId, Mode.OPEN)

        /** The 12-byte nonce `u32 stream_id ‖ u64 counter`, big-endian. */
        fun nonce(
            streamId: Int,
            counter: Long,
        ): ByteArray {
            require(streamId >= 0) { "stream id must be non-negative" }
            require(counter >= 0) { "counter must be non-negative" }
            val out = ByteArray(NONCE_SIZE)
            for (i in 0 until 4) out[i] = (streamId ushr (24 - 8 * i)).toByte()
            for (i in 0 until 8) out[4 + i] = (counter ushr (56 - 8 * i)).toByte()
            return out
        }
    }
}

/** A [FrameCipher] reached its frame or byte limit; a new handshake is needed for fresh keys (spec change N3). */
class FrameLimitException(
    message: String,
) : CryptoException(message)
