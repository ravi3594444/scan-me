package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.protocol.ChunkView
import com.constrivo.drop.core.protocol.ControlCodec
import com.constrivo.drop.core.protocol.ControlMessage
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.FrameHeader
import com.constrivo.drop.core.protocol.FrameReader
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.FrameWriter
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.StreamOpen
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.UnknownControlMessageException
import com.constrivo.drop.core.protocol.writeControl
import com.constrivo.drop.core.protocol.writeProtected
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One authenticated connection of a [SecureSession] (architecture §7.1, spec change S7): a [DataChannel] with its frame
 * reader, frame writer and [CipherFrameProtector].
 *
 * - The **primary** connection is the one the handshake ran on (RFCOMM, GATT or the LAN control connection). It
 *   carries stream 0 (control) and stream 1 (the Bluetooth head-start data, S1); [generation] is null.
 * - A **data** connection starts with an authenticated `StreamOpen` ([open]) and carries one stream id for both chunk
 *   and control frames (control only after a `ControlMoved` names it, N13).
 *
 * One coroutine reads ([readHeader] and then [readControl] or [readChunk]); any number may write (the [FrameWriter]
 * serialises frames and seals them in nonce order). After any exception from a read or write the connection is
 * unusable and must be closed.
 */
class SecureConnection internal constructor(
    val channel: DataChannel,
    /** Link kind of the channel: Bluetooth for RFCOMM/GATT, the Wi-Fi kind for TCP. */
    val kind: LinkKind,
    internal val protector: CipherFrameProtector,
    /** Stream id of control frames on this connection. */
    val controlStreamId: Int,
    /** Stream id of chunk frames on this connection. */
    val chunkStreamId: Int,
    /** Link generation from `LinkReady` / `StreamOpen`; null for the primary connection. */
    val generation: Int?,
    /** [StreamPurpose.CONTROL] for the primary and for the first data stream of a link. */
    val purpose: StreamPurpose,
    /** The authenticated `StreamOpen` of a data connection; null for the primary one. */
    val open: StreamOpen?,
    internal val reader: FrameReader,
    internal val writer: FrameWriter,
) {
    val isPrimary: Boolean get() = generation == null

    /** Payload bytes of chunk frames written so far (for the loopback accounting tests). */
    var chunkBytesWritten: Long = 0
        private set

    /** Writes [message] as a protected control frame (§7.2). */
    suspend fun sendControl(
        message: ControlMessage,
        flush: Boolean = true,
    ) = writer.writeControl(protector, controlStreamId, message, flush)

    /**
     * Writes one chunk frame whose plaintext (48-byte header, then payload) is `plaintext[offset until offset + length]`.
     * The plaintext may be reused as soon as this returns. Chunk frames of one connection are written one at a time, so
     * the sealed output array can go back to the session's pool right after its frame.
     */
    suspend fun sendChunk(
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        flush: Boolean = true,
    ) {
        chunkLock.withLock {
            try {
                writer.writeProtected(protector, FrameType.CHUNK, chunkStreamId, plaintext, offset, length, flush)
            } finally {
                protector.recycleSealed()
            }
            chunkBytesWritten += length
        }
    }

    private val chunkLock = Mutex()

    suspend fun flush() = writer.flush()

    /** Reads the next frame header, or null at a clean end of stream. Throws [ProtocolException] for bad input. */
    suspend fun readHeader(): FrameHeader? = reader.readHeader()

    /**
     * Reads and opens the control frame whose [header] [readHeader] returned. Returns null for a well-formed message of
     * a type this version does not know (the forward-compatibility rule of §7.2: ignore it). Throws
     * [ProtocolException] for anything malformed or unauthenticated, including a `StreamOpen` in a control frame.
     */
    suspend fun readControl(header: FrameHeader): ControlMessage? {
        if (header.type != FrameType.CONTROL) throw ProtocolException("expected a CONTROL frame, got ${header.type}")
        val sealed = ByteArray(header.payloadLength)
        reader.readPayload(sealed)
        val plain = protector.open(FrameType.CONTROL, controlStreamId, sealed, 0, sealed.size)
        return try {
            val message = ControlCodec.decode(plain)
            if (message is StreamOpen) throw ProtocolException("StreamOpen is not allowed in a CONTROL frame")
            message
        } catch (e: UnknownControlMessageException) {
            null
        }
    }

    /**
     * Reads the chunk frame whose [header] [readHeader] returned into [buffer] (at least [header]`.payloadLength` bytes),
     * decrypts it in place and decodes it. The returned view borrows [buffer]. Throws [ProtocolException] for anything
     * malformed or unauthenticated.
     */
    suspend fun readChunk(
        header: FrameHeader,
        buffer: ByteArray,
    ): ChunkView {
        if (header.type != FrameType.CHUNK) throw ProtocolException("expected a CHUNK frame, got ${header.type}")
        require(buffer.size >= header.payloadLength) { "buffer too small for a ${header.payloadLength}-byte chunk frame" }
        reader.readPayload(buffer)
        val plain = protector.openInto(FrameType.CHUNK, chunkStreamId, buffer, 0, header.payloadLength, buffer, 0)
        return ChunkView.decode(buffer, 0, plain)
    }

    /** Reads and discards the payload of the frame whose [header] [readHeader] returned. */
    suspend fun skipPayload(header: FrameHeader) {
        reader.readPayload(ByteArray(header.payloadLength))
    }

    /** Closes the channel. Idempotent. */
    suspend fun close() = channel.close()

    override fun toString(): String =
        if (isPrimary) "SecureConnection(primary, $kind)" else "SecureConnection(stream $chunkStreamId, $kind, gen $generation, $purpose)"

    companion object {
        /** Receive buffer size that holds any chunk frame (§7.1 limit). */
        const val MAX_CHUNK_FRAME: Int = ProtocolConstants.MAX_FRAME_PAYLOAD
    }
}
