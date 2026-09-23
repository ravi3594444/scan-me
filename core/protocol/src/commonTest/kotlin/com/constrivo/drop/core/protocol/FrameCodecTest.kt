package com.constrivo.drop.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-E6 frame format (architecture §7.1). */
class FrameCodecTest {
    @Test
    fun lengthCountsPayloadBytesOnly() {
        val frame = FrameCodec.encode(FrameType.CHUNK, ByteArray(300) { it.toByte() })
        assertEquals(FrameCodec.HEADER_SIZE + 300, frame.size)
        assertEquals("0000012c11", frame.copyOf(5).hex())
        assertEquals(FrameHeader(FrameType.CHUNK, 300), FrameCodec.decodeHeader(frame))
    }

    @Test
    fun typeCodesAreStable() {
        assertEquals(
            mapOf(
                FrameType.HELLO to 0x01,
                FrameType.HELLO_ACK to 0x02,
                FrameType.HELLO_REVEAL to 0x03,
                FrameType.FINISHED to 0x04,
                FrameType.CONTROL to 0x10,
                FrameType.CHUNK to 0x11,
                FrameType.STREAM_OPEN to 0x12,
            ),
            FrameType.entries.associateWith { it.code },
        )
        FrameType.entries.forEach { assertEquals(it, FrameType.fromCode(it.code)) }
        assertNull(FrameType.fromCode(0x00))
        assertFalse(FrameType.HELLO.isProtected)
        assertTrue(FrameType.CHUNK.isProtected)
    }

    @Test
    fun roundTripsEveryType() {
        for (type in FrameType.entries) {
            val payload = Random(type.code).nextBytes(minOf(type.maxPayload, 1000))
            assertEquals(Frame(type, payload), FrameCodec.decode(FrameCodec.encode(type, payload)))
        }
    }

    @Test
    fun encodesASlice() {
        val source = "aabbccddee".unhex()
        assertEquals("0000000310bbccdd", FrameCodec.encode(FrameType.CONTROL, source, 1, 3).hex())
    }

    @Test
    fun rejectsUnknownTypes() {
        for (code in listOf(0x00, 0x05, 0x0F, 0x13, 0x7F, 0xFF)) {
            val bytes = byteArrayOf(0, 0, 0, 0, code.toByte())
            assertProtocolError("type $code") { FrameCodec.decode(bytes) }
        }
    }

    @Test
    fun rejectsLengthsAboveTheTypeLimit() {
        val max = FrameType.CHUNK.maxPayload
        assertEquals(ProtocolConstants.MAX_FRAME_PAYLOAD, max)
        val header = ByteArray(5)
        BigEndian.putU32(header, 0, max + 1)
        header[4] = FrameType.CHUNK.code.toByte()
        assertProtocolError { FrameCodec.decodeHeader(header) }
        BigEndian.putU32(header, 0, -1) // 0xFFFFFFFF
        assertProtocolError { FrameCodec.decodeHeader(header) }
        BigEndian.putU32(header, 0, ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD + 1)
        header[4] = FrameType.HELLO.code.toByte()
        assertProtocolError { FrameCodec.decodeHeader(header) }
        assertProtocolError { FrameCodec.encode(FrameType.HELLO, ByteArray(ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD + 1)) }
        // Exactly at the limit is fine.
        BigEndian.putU32(header, 0, ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD)
        assertEquals(ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD, FrameCodec.decodeHeader(header).payloadLength)
    }

    @Test
    fun limitsRestrictTypesAndSizes() {
        val chunkHeader = FrameCodec.encodeHeader(FrameType.CHUNK, 10)
        assertProtocolError { FrameCodec.decodeHeader(chunkHeader, limits = FrameLimits.HANDSHAKE) }
        assertProtocolError { FrameCodec.decodeHeader(chunkHeader, limits = FrameLimits.STREAM_START) }
        assertEquals(FrameType.CHUNK, FrameCodec.decodeHeader(chunkHeader, limits = FrameLimits.SESSION).type)
        val capped = FrameLimits.of(FrameType.CONTROL, cap = 8)
        assertEquals(8, capped.maxPayload(FrameType.CONTROL))
        assertNull(capped.maxPayload(FrameType.CHUNK))
        assertProtocolError { FrameCodec.decodeHeader(FrameCodec.encodeHeader(FrameType.CONTROL, 9), limits = capped) }
        assertEquals(ProtocolConstants.MAX_HANDSHAKE_FRAME_PAYLOAD, FrameLimits.HANDSHAKE.largestPayload)
        assertEquals(ProtocolConstants.MAX_FRAME_PAYLOAD, FrameLimits.ALL.largestPayload)
        assertTrue(FrameLimits.HANDSHAKE.accepts(FrameType.FINISHED))
        assertFalse(FrameLimits.HANDSHAKE.accepts(FrameType.CONTROL))
    }

    @Test
    fun truncationAndTrailingBytes() {
        val frame = FrameCodec.encode(FrameType.CONTROL, ByteArray(10))
        assertIs<TruncatedFrameException>(assertProtocolError { FrameCodec.decode(frame.copyOf(3)) })
        assertIs<TruncatedFrameException>(assertProtocolError { FrameCodec.decode(frame.copyOf(12)) })
        assertProtocolError { FrameCodec.decode(frame + byteArrayOf(0)) }
        assertProtocolError { FrameCodec.decodeHeader(frame, offset = -1) }
        assertProtocolError { FrameCodec.decodeHeader(frame, offset = frame.size + 1) }
    }

    @Test
    fun decodesConcatenatedFrames() {
        val a = FrameCodec.encode(FrameType.HELLO, "01".unhex())
        val b = FrameCodec.encode(FrameType.CONTROL, ByteArray(0))
        val c = FrameCodec.encode(FrameType.CHUNK, "0203".unhex())
        val frames = FrameCodec.decodeAll(a + b + c)
        assertEquals(
            listOf(Frame(FrameType.HELLO, "01".unhex()), Frame(FrameType.CONTROL, ByteArray(0)), Frame(FrameType.CHUNK, "0203".unhex())),
            frames,
        )
        assertEquals(emptyList(), FrameCodec.decodeAll(ByteArray(0)))
        assertIs<TruncatedFrameException>(assertProtocolError { FrameCodec.decodeAll(a + b + c.copyOf(6)) })
    }

    @Test
    fun associatedDataLayout() {
        // N2: u32 sealed_length ‖ u8 type ‖ u32 stream_id.
        assertEquals("0000012c1100000002", FrameAad.of(FrameType.CHUNK, 2, 300).hex())
        assertEquals(FrameAad.SIZE, FrameAad.of(FrameType.CONTROL, 0, 0).size)
        assertContentEquals("00000000107fffffff".unhex(), FrameAad.of(FrameType.CONTROL, Int.MAX_VALUE, 0))
        assertEquals("0000001d1200000005", FrameAad.of(FrameType.STREAM_OPEN, 5, 29).hex())
        assertRejectsArgument { FrameAad.of(FrameType.HELLO, 0, 1) }
        assertRejectsArgument { FrameAad.of(FrameType.CONTROL, -1, 1) }
    }

    @Test
    fun framesCompareByContent() {
        assertEquals(Frame(FrameType.CHUNK, "0102".unhex()), Frame(FrameType.CHUNK, "0102".unhex()))
        assertEquals(Frame(FrameType.CHUNK, "0102".unhex()).hashCode(), Frame(FrameType.CHUNK, "0102".unhex()).hashCode())
        assertFalse(Frame(FrameType.CHUNK, "0102".unhex()) == Frame(FrameType.CONTROL, "0102".unhex()))
    }

    @Test
    fun randomBytesOnlyEverRaiseProtocolException() {
        val random = Random(99)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 24))
            try {
                FrameCodec.decodeAll(bytes)
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }
}
