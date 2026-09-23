package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_HEADER_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILES_PER_TRANSFER

/**
 * The binary header of a `Chunk` frame's plaintext (architecture §7.3 with spec change S1), 48 bytes, big-endian:
 *
 * | Offset | Size | Field |
 * | --- | --- | --- |
 * | 0 | 16 | `transfer_id` |
 * | 16 | 4 | `file_index` (0xFFFFFFFF = bundle, [ProtocolConstants.BUNDLE_FILE_INDEX]) |
 * | 20 | 4 | `chunk_index` (the bundle number for bundles) |
 * | 24 | 4 | `block_offset`: byte offset of this frame's payload inside the unit; non-zero only for Bluetooth blocks |
 * | 28 | 4 | `payload_len`, 1..[ProtocolConstants.CHUNK_SIZE] |
 * | 32 | 16 | `hash`: XXH3-128 of this frame's plaintext payload (decision 6), computed by `core/transfer` |
 *
 * The payload follows. A Wi-Fi frame carries a whole unit (`block_offset` 0); a Bluetooth frame may carry one block,
 * in which case the payload is that block and [hash] covers the block. Invariant:
 * `block_offset + payload_len <= CHUNK_SIZE`; the stricter check against the unit's real length is
 * [TransferLayout.checkHeader]. Chunk and file indices are u32 on the wire and must fit a non-negative Int here.
 */
data class ChunkHeader(
    val transferId: TransferId,
    val fileIndex: Int,
    val chunkIndex: Int,
    val blockOffset: Int = 0,
    val payloadLength: Int,
    val hash: ChunkHash,
) {
    init {
        violation(fileIndex, chunkIndex.toLong(), blockOffset.toLong(), payloadLength.toLong())?.let { throw IllegalArgumentException(it) }
    }

    val isBundle: Boolean get() = fileIndex == BUNDLE_FILE_INDEX

    val unit: TransferUnit get() = TransferUnit(fileIndex, chunkIndex)

    fun encode(): ByteArray = ByteArray(SIZE).also { encodeInto(it, 0) }

    fun encodeInto(
        destination: ByteArray,
        offset: Int,
    ) {
        require(offset >= 0 && offset <= destination.size - SIZE) { "no room for a chunk header at $offset" }
        transferId.copyInto(destination, offset)
        BigEndian.putU32(destination, offset + 16, fileIndex)
        BigEndian.putU32(destination, offset + 20, chunkIndex)
        BigEndian.putU32(destination, offset + 24, blockOffset)
        BigEndian.putU32(destination, offset + 28, payloadLength)
        hash.copyInto(destination, offset + 32)
    }

    companion object {
        const val SIZE: Int = CHUNK_HEADER_SIZE

        /** Decodes the 48-byte header at [offset]; throws [ProtocolException] for short input or invalid fields. */
        fun decode(
            bytes: ByteArray,
            offset: Int = 0,
        ): ChunkHeader {
            checkRange(bytes.size, offset, SIZE, "chunk header")
            val rawFile = BigEndian.getU32(bytes, offset + 16)
            val fileIndex =
                when {
                    rawFile == 0xFFFF_FFFFL -> BUNDLE_FILE_INDEX
                    rawFile < MAX_FILES_PER_TRANSFER -> rawFile.toInt()
                    else -> throw ProtocolException("chunk file_index $rawFile out of range")
                }
            val chunkIndex = BigEndian.getU32(bytes, offset + 20)
            val blockOffset = BigEndian.getU32(bytes, offset + 24)
            val payloadLength = BigEndian.getU32(bytes, offset + 28)
            violation(fileIndex, chunkIndex, blockOffset, payloadLength)?.let { throw ProtocolException(it) }
            return ChunkHeader(
                transferId = TransferId(bytes.copyOfRange(offset, offset + 16)),
                fileIndex = fileIndex,
                chunkIndex = chunkIndex.toInt(),
                blockOffset = blockOffset.toInt(),
                payloadLength = payloadLength.toInt(),
                hash = ChunkHash(bytes.copyOfRange(offset + 32, offset + 48)),
            )
        }

        private fun violation(
            fileIndex: Int,
            chunkIndex: Long,
            blockOffset: Long,
            payloadLength: Long,
        ): String? =
            when {
                fileIndex != BUNDLE_FILE_INDEX && fileIndex !in 0 until MAX_FILES_PER_TRANSFER -> "chunk file_index $fileIndex out of range"
                chunkIndex !in 0..Int.MAX_VALUE.toLong() -> "chunk_index $chunkIndex out of range"
                blockOffset !in 0 until CHUNK_SIZE.toLong() -> "block_offset $blockOffset out of range"
                payloadLength !in 1..CHUNK_SIZE.toLong() -> "payload_len $payloadLength out of range"
                blockOffset + payloadLength > CHUNK_SIZE -> "block_offset + payload_len exceeds $CHUNK_SIZE"
                else -> null
            }
    }
}

/**
 * The plaintext of a `Chunk` frame: [header] then exactly `header.payloadLength` bytes of [payload].
 * The frame takes ownership of [payload] without copying.
 */
class ChunkFrame(
    val header: ChunkHeader,
    val payload: ByteArray,
) {
    init {
        require(payload.size == header.payloadLength) { "payload is ${payload.size} bytes, header says ${header.payloadLength}" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(ChunkHeader.SIZE + payload.size)
        header.encodeInto(out, 0)
        payload.copyInto(out, ChunkHeader.SIZE)
        return out
    }

    override fun equals(other: Any?): Boolean = other is ChunkFrame && other.header == header && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    override fun toString(): String = "ChunkFrame($header)"

    companion object {
        /** Decodes a chunk plaintext; the payload length must match the rest of [bytes] exactly. Copies the payload. */
        fun decode(bytes: ByteArray): ChunkFrame = ChunkView.decode(bytes).toFrame()
    }
}

/**
 * A chunk plaintext decoded without copying its payload: [header], and the payload at
 * `buffer[payloadOffset until payloadOffset + header.payloadLength]`. The view borrows [buffer]: it stays valid only
 * while the caller leaves that range unchanged (for example until a pooled receive buffer is reused).
 */
class ChunkView(
    val header: ChunkHeader,
    val buffer: ByteArray,
    val payloadOffset: Int,
) {
    init {
        require(payloadOffset >= 0 && payloadOffset <= buffer.size - header.payloadLength) { "payload range outside the buffer" }
    }

    val payloadLength: Int get() = header.payloadLength

    fun copyPayload(): ByteArray = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

    fun toFrame(): ChunkFrame = ChunkFrame(header, copyPayload())

    override fun toString(): String = "ChunkView($header)"

    companion object {
        /**
         * Decodes the chunk plaintext `bytes[offset until offset + length]`: a valid header whose `payload_len` equals
         * the rest of the range exactly. Throws [ProtocolException] otherwise.
         */
        fun decode(
            bytes: ByteArray,
            offset: Int = 0,
            length: Int = bytes.size - offset,
        ): ChunkView {
            checkRange(bytes.size, offset, length, "chunk frame")
            if (length < ChunkHeader.SIZE) throw ProtocolException("chunk frame of $length bytes is shorter than its header")
            val header = ChunkHeader.decode(bytes, offset)
            val available = length - ChunkHeader.SIZE
            if (available != header.payloadLength) {
                throw ProtocolException("chunk payload_len ${header.payloadLength} but $available payload bytes present")
            }
            return ChunkView(header, bytes, offset + ChunkHeader.SIZE)
        }
    }
}
