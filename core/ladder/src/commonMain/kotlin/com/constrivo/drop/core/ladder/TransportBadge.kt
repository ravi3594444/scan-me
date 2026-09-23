package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.LinkKind

/**
 * Copy keys of the transport badge (design §8.3). The first five are the design's keys; [P2P_6], [P2P_UNKNOWN_BAND]
 * and [HOTSPOT_UNKNOWN_BAND] are added by WP5 for links the design table does not cover (a 6 GHz group, or a channel
 * that was not reported) and need design sign-off.
 */
enum class BadgeKey(
    val key: String,
) {
    P2P_5("badge.p2p_5"),
    P2P_24("badge.p2p_24"),
    P2P_6("badge.p2p_6"),
    P2P_UNKNOWN_BAND("badge.p2p"),
    LAN("badge.lan"),

    /** Takes the `band` param. */
    HOTSPOT("badge.hotspot"),
    HOTSPOT_UNKNOWN_BAND("badge.hotspot_plain"),
    BLUETOOTH("badge.bt"),
}

/**
 * The transport badge (F-F2, design §8.3): what the link carrying data is and, for Wi-Fi Direct and the hotspot, the
 * band of its measured channel. The badge must match the actual link and measured frequency (F-F2 acceptance), so it
 * is built only from the reported frequency, never from the band that was requested.
 *
 * @property params values for the copy's placeholders: `band` for [BadgeKey.HOTSPOT].
 */
data class TransportBadge(
    val key: BadgeKey,
    val kind: LinkKind,
    val band: WifiBand? = null,
) {
    init {
        val expected =
            when (key) {
                BadgeKey.P2P_5 -> LinkKind.P2P to WifiBand.BAND_5_GHZ
                BadgeKey.P2P_24 -> LinkKind.P2P to WifiBand.BAND_2_4_GHZ
                BadgeKey.P2P_6 -> LinkKind.P2P to WifiBand.BAND_6_GHZ
                BadgeKey.P2P_UNKNOWN_BAND -> LinkKind.P2P to null
                BadgeKey.LAN -> LinkKind.LAN to null
                BadgeKey.HOTSPOT -> LinkKind.HOTSPOT to band
                BadgeKey.HOTSPOT_UNKNOWN_BAND -> LinkKind.HOTSPOT to null
                BadgeKey.BLUETOOTH -> LinkKind.BLUETOOTH to null
            }
        require(kind == expected.first && band == expected.second) { "badge $key does not match $kind at $band" }
        require(key != BadgeKey.HOTSPOT || band != null) { "the hotspot badge names its band" }
    }

    val params: Map<String, String> get() = if (key == BadgeKey.HOTSPOT && band != null) mapOf(PARAM_BAND to band.label) else emptyMap()

    /**
     * The English text exactly as design §8.3, with the non-breaking hyphen (U+2011) of "Wi-Fi" and the middle dot
     * (U+00B7) of the design.
     */
    val englishText: String
        get() =
            when (key) {
                BadgeKey.P2P_5 -> "Wi\u2011Fi Direct \u00B7 5 GHz"
                BadgeKey.P2P_24 -> "Wi\u2011Fi Direct \u00B7 2.4 GHz"
                BadgeKey.P2P_6 -> "Wi\u2011Fi Direct \u00B7 6 GHz"
                BadgeKey.P2P_UNKNOWN_BAND -> "Wi\u2011Fi Direct"
                BadgeKey.LAN -> "Same network"
                BadgeKey.HOTSPOT -> "Hotspot \u00B7 ${band?.label}"
                BadgeKey.HOTSPOT_UNKNOWN_BAND -> "Hotspot"
                BadgeKey.BLUETOOTH -> "Bluetooth"
            }

    companion object {
        const val PARAM_BAND: String = "band"

        val BLUETOOTH: TransportBadge = TransportBadge(BadgeKey.BLUETOOTH, LinkKind.BLUETOOTH)
        val LAN: TransportBadge = TransportBadge(BadgeKey.LAN, LinkKind.LAN)

        /**
         * The badge for data on [kind] whose channel measured [freqMhz] (null or 0 when unknown; `LinkReady.freq_mhz`).
         * Band from the frequency: 2400–2500 MHz is 2.4 GHz, 4900–5900 MHz 5 GHz, 5925–7125 MHz 6 GHz.
         */
        fun of(
            kind: LinkKind,
            freqMhz: Int?,
        ): TransportBadge {
            val band = WifiBand.fromFrequency(freqMhz)
            return when (kind) {
                LinkKind.LAN -> {
                    LAN
                }

                LinkKind.BLUETOOTH -> {
                    BLUETOOTH
                }

                LinkKind.HOTSPOT -> {
                    TransportBadge(if (band == null) BadgeKey.HOTSPOT_UNKNOWN_BAND else BadgeKey.HOTSPOT, kind, band)
                }

                LinkKind.P2P -> {
                    TransportBadge(
                        when (band) {
                            WifiBand.BAND_2_4_GHZ -> BadgeKey.P2P_24
                            WifiBand.BAND_5_GHZ -> BadgeKey.P2P_5
                            WifiBand.BAND_6_GHZ -> BadgeKey.P2P_6
                            null -> BadgeKey.P2P_UNKNOWN_BAND
                        },
                        kind,
                        band,
                    )
                }
            }
        }
    }
}
