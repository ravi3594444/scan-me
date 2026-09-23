package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-E6 / S1: the binary chunk header of §7.3. */
class ChunkHeaderTest {
    private val hash = ChunkHash(ByteArray(16) { it.toByte() })

    private fun raw(
        file: Long = 0,
        chunk: Long = 0,
        block: Long = 0,
        length: Long = 1,
    ): ByteArray {
        val out = ByteArray(ChunkHeader.SIZE)
        TEST_ID.toByteArray().copyInto(out)
        BigEndian.putU32(out, 16, file.toInt())
        BigEndian.putU32(out, 20, chunk.toInt())
        BigEndian.putU32(out, 24, block.toInt())
        BigEndian.putU32(out, 28, length.toInt())
        hash.toByteArray().copyInto(out, 32)
        return out
    }

    @Test
    fun roundTripsBoundaryValues() {
        val headers =
            listOf(
                ChunkHeader(TEST_ID, 0, 0, 0, 1, hash),
                ChunkHeader(TEST_ID, ProtocolConstants.MAX_FILES_PER_TRANSFER - 1, Int.MAX_VALUE, 0, CHUNK_SIZE, hash),
                ChunkHeader(TEST_ID, BUNDLE_FILE_INDEX, 7, CHUNK_SIZE - 1, 1, hash),
                ChunkHeader(TEST_ID, 5, 0, 16384, CHUNK_SIZE - 16384, hash),
            )
        for (header in headers) assertEquals(header, ChunkHeader.decode(header.encode()))
        assertTrue(headers[2].isBundle)
        assertEquals(TransferUnit(BUNDLE_FILE_INDEX, 7), headers[2].unit)
    }

    @Test
    fun decodesAtAnOffset() {
        val header = ChunkHeader(TEST_ID, 1, 2, 0, 3, hash)
        val buffer = ByteArray(ChunkHeader.SIZE + 10)
        header.encodeInto(buffer, 7)
        assertEquals(header, ChunkHeader.decode(buffer, 7))
        assertProtocolError { ChunkHeader.decode(buffer, 11) }
        assertRejectsArgument { header.encodeInto(buffer, 11) }
    }

    @Test
    fun rejectsInvalidFields() {
        assertEquals(ChunkHeader(TEST_ID, 0, 0, 0, 1, hash), ChunkHeader.decode(raw()))
        assertProtocolError("short") { ChunkHeader.decode(ByteArray(47)) }
        assertProtocolError("file index at the limit") { ChunkHeader.decode(raw(file = ProtocolConstants.MAX_FILES_PER_TRANSFER.toLong())) }
        assertProtocolError("file index 0xFFFFFFFE") { ChunkHeader.decode(raw(file = 0xFFFF_FFFEL)) }
        assertProtocolError("chunk index 2^31") { ChunkHeader.decode(raw(chunk = 0x8000_0000L)) }
        assertProtocolError("block offset at chunk size") { ChunkHeader.decode(raw(block = CHUNK_SIZE.toLong())) }
        assertProtocolError("zero payload") { ChunkHeader.decode(raw(length = 0)) }
        assertProtocolError("payload above chunk size") { ChunkHeader.decode(raw(length = CHUNK_SIZE + 1L)) }
        assertProtocolError("payload 0xFFFFFFFF") { ChunkHeader.decode(raw(length = 0xFFFF_FFFFL)) }
        assertProtocolError("block past the chunk") { ChunkHeader.decode(raw(block = 16384, length = CHUNK_SIZE.toLong())) }
        assertRejectsArgument { ChunkHeader(TEST_ID, 0, 0, 0, 0, hash) }
        assertRejectsArgument { ChunkHeader(TEST_ID, -2, 0, 0, 1, hash) }
        assertRejectsArgument { ChunkHeader(TEST_ID, 0, -1, 0, 1, hash) }
    }

    @Test
    fun chunkFrameRequiresTheExactPayload() {
        val header = ChunkHeader(TEST_ID, 1, 0, 0, 4, hash)
        val frame = ChunkFrame(header, "01020304".unhex())
        val encoded = frame.encode()
        assertEquals(ChunkHeader.SIZE + 4, encoded.size)
        val decoded = ChunkFrame.decode(encoded)
        assertEquals(frame, decoded)
        assertContentEquals("01020304".unhex(), decoded.payload)
        assertProtocolError("short payload") { ChunkFrame.decode(encoded.copyOf(encoded.size - 1)) }
        assertProtocolError("long payload") { ChunkFrame.decode(encoded + byteArrayOf(5)) }
        assertRejectsArgument { ChunkFrame(header, "0102".unhex()) }
    }

    @Test
    fun viewsDecodeARangeWithoutCopyingThePayload() {
        val header = ChunkHeader(TEST_ID, 3, 1, payloadLength = 4, hash = hash)
        val encoded = ChunkFrame(header, "01020304".unhex()).encode()
        val buffer = ByteArray(5) + encoded + ByteArray(3)
        val view = ChunkView.decode(buffer, 5, encoded.size)
        assertEquals(header, view.header)
        assertTrue(view.buffer === buffer, "the view borrows the buffer")
        assertEquals(5 + ChunkHeader.SIZE, view.payloadOffset)
        assertEquals(4, view.payloadLength)
        assertContentEquals("01020304".unhex(), view.copyPayload())
        assertEquals(ChunkFrame.decode(encoded), view.toFrame())
        assertProtocolError("range longer than the payload") { ChunkView.decode(buffer, 5, encoded.size + 1) }
        assertProtocolError("shorter than a header") { ChunkView.decode(buffer, 5, ChunkHeader.SIZE - 1) }
        assertProtocolError("range outside the buffer") { ChunkView.decode(buffer, 5, buffer.size) }
        assertRejectsArgument { ChunkView(header, ByteArray(3), 0) }
    }

    @Test
    fun randomHeadersOnlyEverRaiseProtocolException() {
        val random = Random(5)
        repeat(50_000) {
            try {
                ChunkFrame.decode(random.nextBytes(random.nextInt(40, 80)))
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }
}
