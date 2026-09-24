package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.WifiBand

/**
 * Channel frequencies as Android reports them (`WifiP2pGroup.getFrequency()`, `WifiInfo.getFrequency()`, a
 * `SoftApConfiguration` channel), classified with core/ladder's [WifiBand] for the 5 GHz check of architecture §4 and
 * §9 and the badge (F-F2).
 */
object WifiFrequencies {
    /** Highest frequency taken as real (60 GHz 802.11ad channels end below 71 GHz). */
    const val MAX_FREQ_MHZ: Int = 71_000

    private const val BAND_24_BASE_MHZ = 2407
    private const val CHANNEL_14 = 14
    private const val CHANNEL_14_MHZ = 2484
    private const val BAND_5_BASE_MHZ = 5000
    private const val BAND_49_BASE_MHZ = 4000
    private const val BAND_6_BASE_MHZ = 5950
    private const val BAND_6_CHANNEL_2_MHZ = 5935
    private const val MHZ_PER_CHANNEL = 5

    /** [freqMhz] when it is a real channel frequency, null for Android's "unknown" values (`0`, `-1`) and garbage. */
    fun valid(freqMhz: Int?): Int? = freqMhz?.takeIf { it in 1..MAX_FREQ_MHZ }

    /** The band of [freqMhz], or null when unknown or outside 2.4, 5 and 6 GHz. */
    fun band(freqMhz: Int?): WifiBand? = WifiBand.fromFrequency(valid(freqMhz))

    /** [freqMhz] passes the 5 GHz check (4900 MHz or more, §4): what capability bit 4 records for a hosted group. */
    fun isFiveGhzOrAbove(freqMhz: Int?): Boolean = WifiBand.isFiveGhzOrAbove(valid(freqMhz))

    /**
     * The centre frequency of [channel] in [band] (IEEE 802.11 channelisation): 2.4 GHz channels 1–13 at
     * 2407 + 5 × n and channel 14 at 2484; 5 GHz channels 1–177 at 5000 + 5 × n and the Japanese 4.9 GHz channels
     * 182–196 at 4000 + 5 × n; 6 GHz channels 1–233 at 5950 + 5 × n, with channel 2 at 5935. Null for channel 0
     * ("any channel", as a `SoftApConfiguration` reports an automatic choice) and for channels that do not exist in the
     * band.
     */
    fun channelToFrequencyMhz(
        band: WifiBand,
        channel: Int,
    ): Int? =
        when (band) {
            WifiBand.BAND_2_4_GHZ -> {
                when (channel) {
                    in 1..13 -> BAND_24_BASE_MHZ + MHZ_PER_CHANNEL * channel
                    CHANNEL_14 -> CHANNEL_14_MHZ
                    else -> null
                }
            }

            WifiBand.BAND_5_GHZ -> {
                when (channel) {
                    in 1..177 -> BAND_5_BASE_MHZ + MHZ_PER_CHANNEL * channel
                    in 182..196 -> BAND_49_BASE_MHZ + MHZ_PER_CHANNEL * channel
                    else -> null
                }
            }

            WifiBand.BAND_6_GHZ -> {
                when (channel) {
                    2 -> BAND_6_CHANNEL_2_MHZ
                    in 1..233 -> BAND_6_BASE_MHZ + MHZ_PER_CHANNEL * channel
                    else -> null
                }
            }
        }
}
