package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.WifiBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frequency-to-band classification for the 5 GHz check (§4, §9, F-E2) and channel numbers of the hotspot (N8). */
class WifiFrequenciesTest {
    @Test
    fun unknownFrequenciesAreNull() {
        for (value in listOf(null, 0, -1, Int.MIN_VALUE, 71_001, Int.MAX_VALUE)) assertNull(WifiFrequencies.valid(value), "$value")
        assertEquals(2412, WifiFrequencies.valid(2412))
    }

    @Test
    fun classifiesEveryBand() {
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiFrequencies.band(2412))
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiFrequencies.band(2484))
        assertEquals(WifiBand.BAND_5_GHZ, WifiFrequencies.band(5180))
        assertEquals(WifiBand.BAND_5_GHZ, WifiFrequencies.band(5825))
        assertEquals(WifiBand.BAND_5_GHZ, WifiFrequencies.band(4920), "the 4.9 GHz channels count as 5 GHz")
        assertEquals(WifiBand.BAND_6_GHZ, WifiFrequencies.band(5955))
        assertEquals(WifiBand.BAND_6_GHZ, WifiFrequencies.band(7115))
        assertNull(WifiFrequencies.band(0))
        assertNull(WifiFrequencies.band(60_480), "60 GHz is no badge band")
        assertNull(WifiFrequencies.band(3000))
    }

    @Test
    fun theFiveGhzCheckStartsAt4900() {
        assertTrue(WifiFrequencies.isFiveGhzOrAbove(4900))
        assertTrue(WifiFrequencies.isFiveGhzOrAbove(5745))
        assertTrue(WifiFrequencies.isFiveGhzOrAbove(6135))
        assertFalse(WifiFrequencies.isFiveGhzOrAbove(4899))
        assertFalse(WifiFrequencies.isFiveGhzOrAbove(2462))
        assertFalse(WifiFrequencies.isFiveGhzOrAbove(null))
        assertFalse(WifiFrequencies.isFiveGhzOrAbove(0))
    }

    @Test
    fun channelsMapToTheirCentreFrequencies() {
        val cases =
            mapOf(
                (WifiBand.BAND_2_4_GHZ to 1) to 2412,
                (WifiBand.BAND_2_4_GHZ to 6) to 2437,
                (WifiBand.BAND_2_4_GHZ to 13) to 2472,
                (WifiBand.BAND_2_4_GHZ to 14) to 2484,
                (WifiBand.BAND_5_GHZ to 36) to 5180,
                (WifiBand.BAND_5_GHZ to 149) to 5745,
                (WifiBand.BAND_5_GHZ to 165) to 5825,
                (WifiBand.BAND_5_GHZ to 177) to 5885,
                (WifiBand.BAND_5_GHZ to 184) to 4920,
                (WifiBand.BAND_5_GHZ to 196) to 4980,
                (WifiBand.BAND_6_GHZ to 1) to 5955,
                (WifiBand.BAND_6_GHZ to 2) to 5935,
                (WifiBand.BAND_6_GHZ to 37) to 6135,
                (WifiBand.BAND_6_GHZ to 233) to 7115,
            )
        for ((key, mhz) in cases) assertEquals(mhz, WifiFrequencies.channelToFrequencyMhz(key.first, key.second), "$key")
        val missing =
            listOf(
                WifiBand.BAND_2_4_GHZ to 0,
                WifiBand.BAND_2_4_GHZ to 15,
                WifiBand.BAND_5_GHZ to 0,
                WifiBand.BAND_5_GHZ to 180,
                WifiBand.BAND_5_GHZ to 197,
                WifiBand.BAND_6_GHZ to 234,
                WifiBand.BAND_6_GHZ to -3,
            )
        for ((band, channel) in missing) assertNull(WifiFrequencies.channelToFrequencyMhz(band, channel), "$band $channel")
    }
}
