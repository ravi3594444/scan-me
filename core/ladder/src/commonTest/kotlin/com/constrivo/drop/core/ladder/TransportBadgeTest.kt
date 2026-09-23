package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.LinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The transport badge of F-F2 and design §8.3. */
class TransportBadgeTest {
    @Test
    fun fF2_badgeKeysAndTextsForEveryLink() {
        val cases =
            listOf(
                Triple(TransportBadge.of(LinkKind.P2P, 5180), "badge.p2p_5", "Wi\u2011Fi Direct \u00B7 5 GHz"),
                Triple(TransportBadge.of(LinkKind.P2P, 5745), "badge.p2p_5", "Wi\u2011Fi Direct \u00B7 5 GHz"),
                Triple(TransportBadge.of(LinkKind.P2P, 2437), "badge.p2p_24", "Wi\u2011Fi Direct \u00B7 2.4 GHz"),
                Triple(TransportBadge.of(LinkKind.P2P, 5955), "badge.p2p_6", "Wi\u2011Fi Direct \u00B7 6 GHz"),
                Triple(TransportBadge.of(LinkKind.P2P, 0), "badge.p2p", "Wi\u2011Fi Direct"),
                Triple(TransportBadge.of(LinkKind.P2P, null), "badge.p2p", "Wi\u2011Fi Direct"),
                Triple(TransportBadge.of(LinkKind.LAN, 5180), "badge.lan", "Same network"),
                Triple(TransportBadge.of(LinkKind.LAN, null), "badge.lan", "Same network"),
                Triple(TransportBadge.of(LinkKind.HOTSPOT, 2412), "badge.hotspot", "Hotspot \u00B7 2.4 GHz"),
                Triple(TransportBadge.of(LinkKind.HOTSPOT, 5200), "badge.hotspot", "Hotspot \u00B7 5 GHz"),
                Triple(TransportBadge.of(LinkKind.HOTSPOT, 6115), "badge.hotspot", "Hotspot \u00B7 6 GHz"),
                Triple(TransportBadge.of(LinkKind.HOTSPOT, 0), "badge.hotspot_plain", "Hotspot"),
                Triple(TransportBadge.of(LinkKind.BLUETOOTH, 2402), "badge.bt", "Bluetooth"),
            )
        for ((badge, key, text) in cases) {
            assertEquals(key, badge.key.key, "$badge")
            assertEquals(text, badge.englishText, "$badge")
        }
    }

    @Test
    fun fF2_textsUseTheDesignsCharacters() {
        val text = TransportBadge.of(LinkKind.P2P, 5180).englishText
        assertEquals(listOf('W', 'i', '\u2011', 'F', 'i', ' ', 'D'), text.take(7).toList())
        assertTrue(text.contains(" \u00B7 "))
        assertFalse(text.contains('-'))
    }

    @Test
    fun fF2_hotspotBadgeCarriesItsBandAsAParam() {
        assertEquals(mapOf(TransportBadge.PARAM_BAND to "2.4 GHz"), TransportBadge.of(LinkKind.HOTSPOT, 2462).params)
        assertEquals(emptyMap(), TransportBadge.of(LinkKind.P2P, 2462).params)
        assertEquals(emptyMap(), TransportBadge.of(LinkKind.HOTSPOT, null).params)
    }

    @Test
    fun fF2_bandBoundaries() {
        val expected =
            mapOf(
                2399 to null,
                2400 to WifiBand.BAND_2_4_GHZ,
                2500 to WifiBand.BAND_2_4_GHZ,
                2501 to null,
                4899 to null,
                4900 to WifiBand.BAND_5_GHZ,
                5900 to WifiBand.BAND_5_GHZ,
                5901 to null,
                5924 to null,
                5925 to WifiBand.BAND_6_GHZ,
                7125 to WifiBand.BAND_6_GHZ,
                7126 to null,
                58_320 to null,
                0 to null,
                -1 to null,
            )
        for ((freq, band) in expected) assertEquals(band, WifiBand.fromFrequency(freq), "$freq MHz")
        assertNull(WifiBand.fromFrequency(null))
    }

    @Test
    fun fE2_fiveGhzCheckIsAtLeast4900() {
        assertFalse(WifiBand.isFiveGhzOrAbove(4899))
        assertTrue(WifiBand.isFiveGhzOrAbove(4900))
        assertTrue(WifiBand.isFiveGhzOrAbove(6135))
        assertFalse(WifiBand.isFiveGhzOrAbove(null))
        assertFalse(WifiBand.isFiveGhzOrAbove(2484))
    }

    @Test
    fun inconsistentBadgesCannotBeBuilt() {
        assertFailsWith<IllegalArgumentException> { TransportBadge(BadgeKey.P2P_5, LinkKind.LAN) }
        assertFailsWith<IllegalArgumentException> { TransportBadge(BadgeKey.P2P_5, LinkKind.P2P, WifiBand.BAND_2_4_GHZ) }
        assertFailsWith<IllegalArgumentException> { TransportBadge(BadgeKey.HOTSPOT, LinkKind.HOTSPOT, null) }
        assertFailsWith<IllegalArgumentException> { TransportBadge(BadgeKey.LAN, LinkKind.LAN, WifiBand.BAND_5_GHZ) }
    }
}
