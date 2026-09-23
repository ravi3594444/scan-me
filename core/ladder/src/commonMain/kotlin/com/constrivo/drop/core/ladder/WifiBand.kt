package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities

/**
 * The Wi-Fi band a measured channel frequency falls in (F-F2, design §8.3). [label] is the English band text used in
 * the transport badge ("Hotspot · {band}") and the hints.
 */
enum class WifiBand(
    val label: String,
) {
    /** 2400–2500 MHz. */
    BAND_2_4_GHZ("2.4 GHz"),

    /** 4900–5900 MHz (the 4.9 GHz public-safety channels included). */
    BAND_5_GHZ("5 GHz"),

    /** 5925–7125 MHz (Wi-Fi 6E / 7). */
    BAND_6_GHZ("6 GHz"),
    ;

    companion object {
        /**
         * Lowest channel frequency that passes the 5 GHz check of architecture §4 ("actual freq >= 5 GHz"): the 4.9 GHz
         * channels count, as the plan's WP5 text says (`freq_mhz >= 4900`).
         */
        const val FIVE_GHZ_MIN_MHZ: Int = 4900

        /**
         * The band of [freqMhz], or null when it is unknown (`null`, `0`, as `LinkReady.freq_mhz` reports an unknown
         * channel) or outside the three bands (for example an 802.11ad channel near 60 GHz, or a bogus value).
         */
        fun fromFrequency(freqMhz: Int?): WifiBand? =
            when (freqMhz) {
                null -> null
                in 2400..2500 -> BAND_2_4_GHZ
                in 4900..5900 -> BAND_5_GHZ
                in 5925..7125 -> BAND_6_GHZ
                else -> null
            }

        /** True when [freqMhz] passes the 5 GHz verification of architecture §4 and §9 (at least [FIVE_GHZ_MIN_MHZ]). */
        fun isFiveGhzOrAbove(freqMhz: Int?): Boolean = freqMhz != null && freqMhz >= FIVE_GHZ_MIN_MHZ
    }
}

/**
 * The band of a device's station (infrastructure Wi-Fi) interface, which on chipsets without dual-band concurrency
 * pins a Wi-Fi Direct group it hosts to the same channel (spec change N9).
 */
enum class StationBand {
    /** Not connected to a Wi-Fi network, or the device does not publish it (Trusted-only beacons clear bits 11 and 13). */
    NONE,

    /** Connected on 2.4 GHz: a group this device hosts is likely pinned to 2.4 GHz. */
    BAND_2_4_GHZ,

    /** Connected on 5 GHz or above. */
    BAND_5_GHZ_OR_ABOVE,
    ;

    companion object {
        /**
         * Reads the station band from capability bits 11 (a network hint is present) and 13 (`STATION_ON_5GHZ`,
         * architecture §5.2 as changed by WP1). Bit 11 without bit 13 reads as 2.4 GHz; without bit 11 the band is
         * [NONE].
         */
        fun fromCapabilities(capabilities: Capabilities): StationBand =
            when {
                Capabilities.Flag.CONNECTED_TO_WIFI !in capabilities -> NONE
                Capabilities.Flag.STATION_ON_5GHZ in capabilities -> BAND_5_GHZ_OR_ABOVE
                else -> BAND_2_4_GHZ
            }
    }
}
