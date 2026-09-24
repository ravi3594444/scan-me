package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.frame.FrameCipher
import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.protocol.FrameAad
import com.constrivo.drop.core.protocol.FrameProtector
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

/**
 * The frame ciphers of one session (spec changes S7, N3): one sealer per stream id for the whole session, created on
 * first use through [HandshakeResult.frameSender] (which refuses a second one, so a (key, nonce) pair cannot repeat),
 * and a fresh opener for each connection that carries a stream ([HandshakeResult.frameReceiver]). Thread-safe.
 */
internal class SessionCiphers(
    private val handshake: HandshakeResult,
) {
    private val lock = TransferLock()
    private val sealers = HashMap<Int, FrameCipher>()
    private val spare = ArrayDeque<ByteArray>()

    fun sealer(streamId: Int): FrameCipher = lock.withLock { sealers.getOrPut(streamId) { handshake.frameSender(streamId) } }

    fun opener(streamId: Int): FrameCipher = handshake.frameReceiver(streamId)

    /**
     * An output array of exactly [size] bytes for a large chunk seal: a spare one when available. The session's
     * connections share the spares, so the arrays alive at once are bounded by the chunk writes in progress, not by the
     * number of streams (architecture §15 memory budget).
     */
    fun sealedBuffer(size: Int): ByteArray =
        lock.withLock {
            val index = spare.indexOfFirst { it.size == size }
            if (index >= 0) spare.removeAt(index) else null
        } ?: ByteArray(size)

    /** Returns [buffer] once its frame is written; at most [MAX_SPARE] are kept. */
    fun recycle(buffer: ByteArray) {
        lock.withLock { if (spare.size < MAX_SPARE) spare.addLast(buffer) }
    }

    private companion object {
        const val MAX_SPARE = 4
    }
}

/**
 * Adapts the `core/crypto` [FrameCipher] to the `core/protocol` [FrameProtector] (architecture §7.1; spec changes
 * S7, N2) for the streams of one connection: stream 0 (control) and 1 (Bluetooth data) on the connection the
 * handshake ran on, or the one data stream id of a `StreamOpen` connection.
 *
 * - **Associated data** is `FrameAad.of(type, streamId, sealedLength)` (N2), so a frame cannot be truncated, relabelled
 *   or moved to another stream.
 * - **Chunk frames are sealed in [ProtocolConstants.AEAD_BLOCK_SIZE] blocks** (§9 "Big pipes"): each 64 KiB block of the
 *   plaintext is its own AEAD message with the next nonce counter of the stream and a 16-byte tag, all under the frame's
 *   associated data. The counters bind the block order, the `sealed_length` in the associated data binds their number.
 *   A full 4 MiB chunk grows by 65 tags (1040 bytes). Other protected frames are one AEAD message (one tag).
 * - [open] and [openInto] throw [ProtocolException] for a stream this connection does not carry, a sealed length that
 *   no plaintext produces, or a frame that fails authentication; the opener then fails closed and the connection must
 *   be torn down. [seal] propagates [com.constrivo.drop.core.crypto.frame.FrameLimitException] when a stream reaches
 *   its frame or byte limit: the engine then runs a new handshake (N3).
 *
 * The array [seal] returns for a large chunk comes from a small pool shared by the session's connections; the connection
 * gives it back with [recycleSealed] once `FrameWriter` wrote the frame (the writer writes a sealed payload before it
 * releases its lock, and every frame of a connection goes through its one writer). Do not keep the array. Sealing runs
 * under that lock, opening in the connection's one reader, so the class needs no locking of its own.
 */
class CipherFrameProtector internal constructor(
    private val ciphers: SessionCiphers,
    streamIds: IntArray,
) : FrameProtector {
    private val streams: IntArray = streamIds.copyOf()
    private val openers = HashMap<Int, FrameCipher>()
    private var lastSealed: ByteArray? = null

    /** Test hook: runs before every seal (for example to throw a `FrameLimitException`). */
    internal var beforeSeal: ((type: FrameType, streamId: Int) -> Unit)? = null

    /** The stream ids this connection carries. */
    val streamIds: IntArray get() = streams.copyOf()

    override fun sealedSize(
        type: FrameType,
        plaintextSize: Int,
    ): Int {
        require(type.isProtected) { "$type frames are not protected" }
        require(plaintextSize >= 0) { "plaintext size must be non-negative" }
        return plaintextSize + TAG_SIZE * blocks(type, plaintextSize)
    }

    override fun seal(
        type: FrameType,
        streamId: Int,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(type.isProtected) { "$type frames are not protected" }
        require(streamId in streams) { "stream $streamId is not carried by this connection" }
        require(offset >= 0 && length >= 0 && offset <= plaintext.size - length) { "plaintext range out of bounds" }
        beforeSeal?.invoke(type, streamId)
        val size = sealedSize(type, length)
        val out = output(type, size)
        val aad = FrameAad.of(type, streamId, size)
        val cipher = ciphers.sealer(streamId)
        if (type != FrameType.CHUNK) {
            cipher.seal(plaintext, offset, length, aad, out, 0)
            return out
        }
        var from = offset
        var to = 0
        var remaining = length
        do {
            val n = minOf(BLOCK_SIZE, remaining)
            to += cipher.seal(plaintext, from, n, aad, out, to)
            from += n
            remaining -= n
        } while (remaining > 0)
        return out
    }

    override fun open(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        val size = plaintextSizeOrThrow(type, length)
        val out = ByteArray(size)
        openInto(type, streamId, sealed, offset, length, out, 0)
        return out
    }

    override fun openInto(
        type: FrameType,
        streamId: Int,
        sealed: ByteArray,
        offset: Int,
        length: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int {
        if (!type.isProtected) throw ProtocolException("$type frames are not protected")
        if (streamId !in streams) throw ProtocolException("stream $streamId is not carried by this connection")
        if (offset < 0 || length < 0 || offset > sealed.size - length) throw ProtocolException("sealed range out of bounds")
        val size = plaintextSizeOrThrow(type, length)
        require(destinationOffset >= 0 && destinationOffset <= destination.size - size) { "destination has no room for $size bytes" }
        val aad = FrameAad.of(type, streamId, length)
        val cipher = openers.getOrPut(streamId) { ciphers.opener(streamId) }
        try {
            if (type != FrameType.CHUNK) {
                cipher.open(sealed, offset, length, aad, destination, destinationOffset)
                return size
            }
            var from = offset
            var to = destinationOffset
            var remaining = length
            while (remaining > 0) {
                val n = minOf(BLOCK_SIZE + TAG_SIZE, remaining)
                to += cipher.open(sealed, from, n, aad, destination, to)
                from += n
                remaining -= n
            }
            return size
        } catch (e: CryptoException) {
            throw ProtocolException("$type frame on stream $streamId failed authentication", e)
        }
    }

    private fun plaintextSizeOrThrow(
        type: FrameType,
        sealedLength: Int,
    ): Int {
        if (!type.isProtected) throw ProtocolException("$type frames are not protected")
        return plaintextSize(type, sealedLength) ?: throw ProtocolException("no $type plaintext seals to $sealedLength bytes")
    }

    private fun output(
        type: FrameType,
        size: Int,
    ): ByteArray {
        if (type != FrameType.CHUNK || size < SCRATCH_MIN) return ByteArray(size)
        return ciphers.sealedBuffer(size).also { lastSealed = it }
    }

    /** Gives the array of the last large chunk seal back to the session's pool, once its frame is written. */
    internal fun recycleSealed() {
        val buffer = lastSealed ?: return
        lastSealed = null
        ciphers.recycle(buffer)
    }

    internal companion object {
        const val TAG_SIZE: Int = 16
        const val BLOCK_SIZE: Int = ProtocolConstants.AEAD_BLOCK_SIZE

        /** Chunk seals at least this large reuse the scratch array (full-size chunks). */
        private const val SCRATCH_MIN: Int = ProtocolConstants.MIB

        fun blocks(
            type: FrameType,
            plaintextSize: Int,
        ): Int = if (type != FrameType.CHUNK || plaintextSize == 0) 1 else (plaintextSize + BLOCK_SIZE - 1) / BLOCK_SIZE

        /** The plaintext size that seals to [sealedLength] bytes, or null when none does. */
        fun plaintextSize(
            type: FrameType,
            sealedLength: Int,
        ): Int? {
            if (sealedLength < TAG_SIZE) return null
            if (type != FrameType.CHUNK) return sealedLength - TAG_SIZE
            val blocks = (sealedLength.toLong() + BLOCK_SIZE + TAG_SIZE - 1) / (BLOCK_SIZE + TAG_SIZE)
            val plain = sealedLength - TAG_SIZE * blocks
            if (plain < 0) return null
            val size = plain.toInt()
            return size.takeIf { it + TAG_SIZE * blocks(type, it) == sealedLength }
        }
    }
}
