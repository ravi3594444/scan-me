package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.crypto.handshake.HandshakeRole
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.FrameLimits
import com.constrivo.drop.core.protocol.FrameReader
import com.constrivo.drop.core.protocol.FrameWriter
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.SessionRole
import com.constrivo.drop.core.protocol.StreamDirection
import com.constrivo.drop.core.protocol.StreamIdAllocator
import com.constrivo.drop.core.protocol.StreamIdRegistry
import com.constrivo.drop.core.protocol.StreamOpen
import com.constrivo.drop.core.protocol.StreamOpenFrame
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.protocol.writeStreamOpen
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

/**
 * An authenticated session with one peer (architecture §6, §7.1; spec changes S7, N2, N3), produced by
 * [SessionHandshake] after the Finished MACs were exchanged and verified in both directions.
 *
 * The session owns the directional keys (inside [handshake]) and hands out the connections that use them:
 * the [primary] connection the handshake ran on (streams 0 and 1), and data connections that start with an
 * authenticated `StreamOpen` ([openStream] on the side that connects, [acceptStream] on the side that listens). Data
 * stream ids come from this side's [SessionRole] partition and are never reused within the session. A reconnect,
 * a new link generation after an interruption, or a `FrameLimitException` needs a new session: keys are never reused
 * across sessions (N3).
 *
 * Thread-safe.
 */
class SecureSession internal constructor(
    /** The verified handshake: peer identity, SAS, capabilities, keys. */
    val handshake: HandshakeResult,
    /** The connection the handshake ran on: stream 0 control, stream 1 Bluetooth data. */
    val primary: SecureConnection,
    private val ciphers: SessionCiphers,
) {
    /** Which data stream ids this side opens (S7): the handshake initiator even ids, the responder odd ids. */
    val role: SessionRole = if (handshake.role == HandshakeRole.INITIATOR) SessionRole.INITIATOR else SessionRole.RESPONDER

    private val lock = TransferLock()
    private val allocator = StreamIdAllocator(role)
    private val registry = StreamIdRegistry(role)

    /** The peer's verified 32-byte Ed25519 identity key. */
    val peerIdentityKey: ByteArray get() = handshake.peerIdentityKey

    /**
     * Opens a data connection over [channel] (the side that connects): allocates the next stream id of this side's
     * partition and writes the `StreamOpen` frame naming [transferId], [purpose] and link [generation] (S7). Chunks
     * flow sender to receiver on every data stream.
     */
    suspend fun openStream(
        channel: DataChannel,
        kind: LinkKind,
        transferId: TransferId,
        purpose: StreamPurpose,
        generation: Int,
    ): SecureConnection {
        val streamId = lock.withLock { allocator.next() }
        val protector = CipherFrameProtector(ciphers, intArrayOf(streamId))
        val writer = FrameWriter(channel)
        val open = StreamOpen(transferId, streamId, StreamDirection.SENDER_TO_RECEIVER, purpose, generation)
        writer.writeStreamOpen(protector, open)
        val reader = FrameReader(channel, limits = FrameLimits.SESSION)
        return SecureConnection(channel, kind, protector, streamId, streamId, generation, purpose, open, reader, writer)
    }

    /**
     * Accepts a data connection over [channel] (the listening side): reads its first frame, which must be a
     * `StreamOpen` for an id the peer may open and has not opened before, authenticates it, and registers the id.
     * The caller checks the transfer id, direction and generation of the returned [SecureConnection.open].
     *
     * @throws ProtocolException for a missing, malformed, replayed or unauthenticated `StreamOpen`.
     */
    suspend fun acceptStream(
        channel: DataChannel,
        kind: LinkKind,
    ): SecureConnection {
        val reader = FrameReader(channel, limits = FrameLimits.STREAM_START)
        val frame = reader.readFrame() ?: throw ProtocolException("data connection closed before its StreamOpen")
        val streamId = StreamOpenFrame.peekStreamId(frame)
        val protector = CipherFrameProtector(ciphers, intArrayOf(streamId))
        val open = lock.withLock { StreamOpenFrame.open(protector, frame, registry) }
        if (open.direction != StreamDirection.SENDER_TO_RECEIVER) {
            throw ProtocolException("data stream $streamId flows ${open.direction.wireName}; chunks only flow sender to receiver")
        }
        reader.limits = FrameLimits.SESSION
        return SecureConnection(
            channel,
            kind,
            protector,
            streamId,
            streamId,
            open.generation,
            open.purpose,
            open,
            reader,
            FrameWriter(channel),
        )
    }

    /** Sends this device's advertising secret to a trusted peer on the control stream (spec change S3). */
    suspend fun sendTrustShare(share: TrustShare) = primary.sendControl(share)

    /** Closes the primary connection; data connections are closed by their owner. */
    suspend fun close() = primary.close()

    override fun toString(): String = "SecureSession(${handshake.role}, peer=${handshake.peerNickname.take(16)}, ${primary.kind})"

    internal companion object {
        val PRIMARY_STREAMS: IntArray = intArrayOf(ProtocolConstants.STREAM_ID_CONTROL, ProtocolConstants.STREAM_ID_BLUETOOTH)
    }
}
