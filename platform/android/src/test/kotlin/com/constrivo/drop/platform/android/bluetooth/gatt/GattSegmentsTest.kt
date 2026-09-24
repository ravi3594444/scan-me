package com.constrivo.drop.platform.android.bluetooth.gatt

import com.constrivo.drop.platform.android.bluetooth.BluetoothProfileException
import com.constrivo.drop.platform.android.bluetooth.ChannelInfo
import com.constrivo.drop.platform.android.bluetooth.DropBluetoothProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The GATT stream segment format and the channel-info characteristic (architecture §6.1 note). */
class GattSegmentsTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun goldenBytes() {
        assertEquals("010000" + "01" + "0020" + "01fd", hex(GattSegments.encode(GattSegment.Open(0, 1, 32, 509))))
        assertEquals("020000" + "01" + "0008" + "0014", hex(GattSegments.encode(GattSegment.OpenAck(0, 1, 8, 20))))
        assertEquals("03fffe" + "616263", hex(GattSegments.encode(GattSegment.Data(0xFFFE, "abc".encodeToByteArray()))))
        assertEquals("040102" + "0010", hex(GattSegments.encode(GattSegment.Credit(0x0102, 16))))
        assertEquals("050007", hex(GattSegments.encode(GattSegment.Close(7))))
        assertEquals("060009" + "03", hex(GattSegments.encode(GattSegment.Reset(9, GattSegments.RESET_CANCELLED))))
        assertEquals("030005" + "0203", hex(GattSegments.data(5, byteArrayOf(1, 2, 3, 4), 1, 2)))
    }

    @Test
    fun everySegmentRoundTrips() {
        val segments =
            listOf(
                GattSegment.Open(0, 1, 32, 509),
                GattSegment.OpenAck(0, 1, 65535, 512),
                GattSegment.Data(65535, ByteArray(509) { it.toByte() }),
                GattSegment.Credit(12, 65535),
                GattSegment.Close(13),
                GattSegment.Reset(14, 255),
            )
        for (segment in segments) {
            val decoded = GattSegments.decode(GattSegments.encode(segment))
            assertEquals(segment::class, decoded::class)
            assertEquals(segment.seq, decoded.seq)
            assertContentEquals(GattSegments.encode(segment), GattSegments.encode(decoded))
        }
        val open = assertIs<GattSegment.Open>(GattSegments.decode(GattSegments.encode(GattSegment.Open(0, 3, 7, 100))))
        assertEquals(3, open.version)
        assertEquals(7, open.credits)
        assertEquals(100, open.maxSegment)
    }

    @Test
    fun laterVersionsMayAppendHandshakeFields() {
        val longer = GattSegments.encode(GattSegment.Open(0, 2, 32, 509)) + byteArrayOf(9, 9, 9)
        val open = assertIs<GattSegment.Open>(GattSegments.decode(longer))
        assertEquals(2, open.version)
    }

    @Test
    fun malformedSegmentsRaiseOnlyGattStreamException() {
        val bad =
            listOf(
                ByteArray(0),
                byteArrayOf(3, 0),
                byteArrayOf(0x07, 0, 0),
                byteArrayOf(0x00, 0, 0, 1),
                byteArrayOf(1, 0, 0, 1, 0, 32, 1),
                byteArrayOf(1, 0, 0, 0, 0, 32, 1, 0xFD.toByte()),
                byteArrayOf(1, 0, 0, 1, 0, 0, 1, 0xFD.toByte()),
                byteArrayOf(1, 0, 0, 1, 0, 32, 0, 19),
                byteArrayOf(2, 0, 0, 1, 0, 32, 2, 1),
                byteArrayOf(3, 0, 0),
                byteArrayOf(4, 0, 0, 0, 0),
                byteArrayOf(4, 0, 0, 1),
                byteArrayOf(4, 0, 0, 0, 1, 0),
                byteArrayOf(5, 0, 0, 1),
                byteArrayOf(6, 0, 0),
                byteArrayOf(6, 0, 0, 1, 2),
            )
        for ((i, value) in bad.withIndex()) assertFailsWith<GattStreamException>("case $i") { GattSegments.decode(value) }
        val random = Random(11)
        repeat(20_000) {
            val value = random.nextBytes(random.nextInt(0, 12))
            try {
                GattSegments.decode(value)
            } catch (e: GattStreamException) {
                // expected for most inputs
            }
        }
    }

    @Test
    fun sequenceDistanceSeparatesNextGapAndDuplicate() {
        assertEquals(0, GattSegments.distance(5, 5))
        assertEquals(1, GattSegments.distance(6, 5))
        assertEquals(0xFFFF, GattSegments.distance(4, 5))
        // Wrapping.
        assertEquals(0, GattSegments.distance(0, 0))
        assertEquals(1, GattSegments.distance(0, 0xFFFF))
        assertEquals(0xFFFF, GattSegments.distance(0xFFFF, 0))
    }

    @Test
    fun encodingRefusesOutOfRangeFields() {
        assertFailsWith<IllegalArgumentException> { GattSegments.encode(GattSegment.Open(0, 1, 0, 509)) }
        assertFailsWith<IllegalArgumentException> { GattSegments.encode(GattSegment.Open(0, 1, 32, 513)) }
        assertFailsWith<IllegalArgumentException> { GattSegments.encode(GattSegment.Close(65536)) }
        assertFailsWith<IllegalArgumentException> { GattSegments.data(0, ByteArray(4), 0, 0) }
    }

    @Test
    fun channelInfoPublishesThePsm() {
        assertEquals("01010085", hex(ChannelInfo(0x85).encode()))
        assertEquals("01000000", hex(ChannelInfo(null).encode()))
        assertEquals(0x85, ChannelInfo.decode(ChannelInfo(0x85).encode()).l2capPsm)
        assertNull(ChannelInfo.decode(ChannelInfo(null).encode()).l2capPsm)
        // Reserved flags and appended fields are ignored; a later version is read by its first four bytes.
        assertEquals(0x0101, ChannelInfo.decode(byteArrayOf(2, 0x03, 1, 1, 7, 7)).l2capPsm)
        assertNull(ChannelInfo.decode(byteArrayOf(1, 0x02, 0, 0x85.toByte())).l2capPsm)
        for (bad in listOf(ByteArray(0), byteArrayOf(1, 1, 0), byteArrayOf(0, 1, 0, 0x85.toByte()), byteArrayOf(1, 1, 0, 0))) {
            assertFailsWith<BluetoothProfileException> { ChannelInfo.decode(bad) }
        }
        assertFailsWith<IllegalArgumentException> { ChannelInfo(0) }
        assertFailsWith<IllegalArgumentException> { ChannelInfo(0x10000) }
    }

    @Test
    fun segmentSizeFollowsTheMtuWithinAttributeLimits() {
        assertEquals(20, DropBluetoothProfile.segmentSize(23))
        assertEquals(20, DropBluetoothProfile.segmentSize(10))
        assertEquals(182, DropBluetoothProfile.segmentSize(185))
        assertEquals(512, DropBluetoothProfile.segmentSize(517))
        assertEquals(512, DropBluetoothProfile.segmentSize(1024))
        assertEquals("0000df01-0000-1000-8000-00805f9b34fb", DropBluetoothProfile.SERVICE_UUID.toString())
    }
}
