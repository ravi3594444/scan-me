package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The text columns and their domain enums (architecture §12). */
class ColumnsTest {
    private fun <T> roundTrips(
        codec: ColumnCodec<T>,
        values: Iterable<T>,
    ) {
        for (value in values) assertEquals(value, codec.decode(codec.encode(value)), "$value")
    }

    @Test
    fun enumsRoundTripThroughTheirColumnText() {
        roundTrips(TransferStatus, TransferStatus.entries)
        roundTrips(TransferDirection, TransferDirection.entries)
        roundTrips(TransferFileStatus, TransferFileStatus.entries)
        roundTrips(WifiBand, WifiBand.entries)
        roundTrips(LinkKindColumn, LinkKind.entries)
        roundTrips(DevicePlatformColumn, DevicePlatform.entries)
    }

    @Test
    fun columnTextIsTheSpecifiedVocabulary() {
        assertEquals(
            listOf("offered", "accepted", "streaming", "interrupted", "verifying", "done", "failed", "cancelled"),
            TransferStatus.entries.map { TransferStatus.encode(it) },
        )
        assertEquals(listOf("send", "receive"), TransferDirection.entries.map { it.dbValue })
        assertEquals(listOf("lan", "p2p", "hotspot", "bluetooth"), LinkKind.entries.map { LinkKindColumn.encode(it) })
        assertEquals(listOf("2.4", "5", "6"), WifiBand.entries.map { it.dbValue })
        assertEquals(
            listOf("phone", "laptop", "desktop", "browser", "unknown"),
            DevicePlatform.entries.map { DevicePlatformColumn.encode(it) },
        )
        val terminal = TransferStatus.entries.filter { it.isTerminal }
        assertEquals(listOf(TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED), terminal)
    }

    @Test
    fun unknownTextIsCorruption() {
        assertFailsWith<DataCorruptionException> { TransferStatus.decode("paused") }
        assertFailsWith<DataCorruptionException> { TransferStatus.decode("DONE") }
        assertFailsWith<DataCorruptionException> { TransferDirection.decode("") }
        assertFailsWith<DataCorruptionException> { TransferFileStatus.decode("lost") }
        assertFailsWith<DataCorruptionException> { WifiBand.decode("2.4GHz") }
        assertFailsWith<DataCorruptionException> { LinkKindColumn.decode("wifi") }
        assertFailsWith<DataCorruptionException> { DevicePlatformColumn.decode("tablet") }
        assertFailsWith<DataCorruptionException> { DevicePlatformColumn.decode("") }
    }

    @Test
    fun hintCodesKeepFiringOrderOnce() {
        val hints = listOf(HintCode.THERMAL, HintCode.BAND24, HintCode.THERMAL, HintCode.STATION_BAND24)
        assertEquals("thermal,band24,sta_band24", HintCodesColumn.encode(hints))
        assertEquals(
            listOf(HintCode.THERMAL, HintCode.BAND24, HintCode.STATION_BAND24),
            HintCodesColumn.decode("thermal,band24,sta_band24"),
        )
        assertEquals(emptyList(), HintCodesColumn.decode(""))
        assertEquals("", HintCodesColumn.encode(emptyList()))
        roundTrips(HintCodesColumn, listOf(HintCode.entries, listOf(HintCode.LAN_SLOW)))
        assertFailsWith<DataCorruptionException> { HintCodesColumn.decode("thermal,,band24") }
        assertFailsWith<DataCorruptionException> { HintCodesColumn.decode("thermal,warp") }
        assertFailsWith<DataCorruptionException> { HintCodesColumn.decode(",") }
    }

    @Test
    fun mimeHistogramsEscapeTheirSeparators() {
        val histogram = linkedMapOf("image/*" to 10, "application/pdf" to 2, "text/plain; charset=utf-8" to 1, "a,b%c=d\u0001" to 3)
        val encoded = MimeHistogramColumn.encode(histogram)
        assertEquals("image/*=10,application/pdf=2,text/plain; charset%3Dutf-8=1,a%2Cb%25c%3Dd%01=3", encoded)
        assertEquals(histogram, MimeHistogramColumn.decode(encoded))
        assertEquals(histogram.keys.toList(), MimeHistogramColumn.decode(encoded).keys.toList(), "order is kept")
        assertEquals(emptyMap(), MimeHistogramColumn.decode(""))
        assertEquals("", MimeHistogramColumn.encode(emptyMap()))
        roundTrips(MimeHistogramColumn, listOf(mapOf("vidéo/mp4" to 1, "🎵/x" to 7)))
    }

    @Test
    fun malformedHistogramsAreCorruption() {
        val malformed =
            listOf(
                "image/*",
                "image/*=",
                "=3",
                "image/*=0",
                "image/*=-1",
                "image/*=+1",
                "image/*=1=2",
                "a=1,,b=2",
                "a=1,a=2",
                "a%=1",
                "a%2=1",
                "a%zz=1",
                "a%41=1",
                "a=99999999999",
            )
        for (text in malformed) {
            assertFailsWith<DataCorruptionException>(text) { MimeHistogramColumn.decode(text) }
        }
        assertFailsWith<IllegalArgumentException> { MimeHistogramColumn.encode(mapOf("" to 1)) }
        assertFailsWith<IllegalArgumentException> { MimeHistogramColumn.encode(mapOf("a" to 0)) }
    }

    @Test
    fun bandsFollowTheMeasuredFrequency() {
        assertNull(WifiBand.fromFrequencyMhz(0), "no Wi-Fi link")
        assertNull(WifiBand.fromFrequencyMhz(2399))
        assertEquals(WifiBand.GHZ_2_4, WifiBand.fromFrequencyMhz(2412))
        assertEquals(WifiBand.GHZ_2_4, WifiBand.fromFrequencyMhz(2484))
        assertNull(WifiBand.fromFrequencyMhz(3000))
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5180))
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5825))
        assertEquals(WifiBand.GHZ_5, WifiBand.fromFrequencyMhz(5924))
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(5925))
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(5955))
        assertEquals(WifiBand.GHZ_6, WifiBand.fromFrequencyMhz(7115))
        assertNull(WifiBand.fromFrequencyMhz(7126))
        assertNull(WifiBand.fromFrequencyMhz(-5180))
    }

    @Test
    fun deviceIdsAreThirtyTwoLowerCaseHexDigits() {
        assertEquals(true, DeviceIds.isValid("0123456789abcdef0123456789abcdef"))
        assertEquals(false, DeviceIds.isValid("0123456789ABCDEF0123456789abcdef"))
        assertEquals(false, DeviceIds.isValid("0123456789abcdef0123456789abcde"))
        assertEquals(false, DeviceIds.isValid("0123456789abcdef0123456789abcdeg"))
        assertFailsWith<IllegalArgumentException> { DeviceIds.requireValid("x") }
        assertFailsWith<DataCorruptionException> { transferIdFromDb("ABCDEF0123456789abcdef0123456789") }
        assertFailsWith<DataCorruptionException> { transferIdFromDb("00") }
        assertFailsWith<DataCorruptionException> { deviceIdFromDb("") }
        assertFailsWith<DataCorruptionException> { booleanFromDb(2, "flag") }
        assertFailsWith<DataCorruptionException> { intFromDb(Long.MAX_VALUE, "n") }
    }
}
