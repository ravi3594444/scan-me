package com.constrivo.drop.ui.shared

import com.constrivo.drop.core.ladder.BadgeKey
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.Side
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the string resources (decision 9, design §8): English and Hindi have the same keys and placeholders, every
 * count uses a plural with `one` and `other`, and the copy that design §8 and core/ladder define is mirrored exactly.
 */
class StringResourcesTest {
    private class Resources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    ) {
        val keys: Set<String> get() = strings.keys + plurals.keys

        fun text(key: String): String = strings[key] ?: fail("missing string $key")

        fun plural(
            key: String,
            quantity: String,
        ): String = plurals[key]?.get(quantity) ?: fail("missing plural $key/$quantity")
    }

    private val dir = File("src/commonMain/composeResources")

    private fun load(folder: String): Resources {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "$folder/strings.xml"))
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> {
                    assertTrue(strings.put(name, e.textContent) == null, "$folder: duplicate $name")
                }

                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] =
                        (0 until items.length).associate { j ->
                            (items.item(j) as Element).let {
                                it.getAttribute("quantity") to
                                    it.textContent
                            }
                        }
                }

                else -> {
                    fail("$folder: unexpected <${e.tagName}>")
                }
            }
        }
        return Resources(strings, plurals)
    }

    private val en = load("values")
    private val hi = load("values-hi")

    private fun placeholders(text: String): Set<String> = Regex("%(\\d+)\\$([sd])").findAll(text).map { it.value }.toSet()

    /** Collapses the no-break spaces the resources use between numbers and units. */
    private fun plain(text: String): String = text.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test
    fun decision9_hindiHasEveryKeyWithTheSamePlaceholders() {
        assertEquals(en.keys, hi.keys, "values-hi must translate every key and nothing else")
        for ((key, text) in en.strings) {
            assertTrue(text.isNotBlank(), "empty string $key")
            assertTrue(hi.text(key).isNotBlank(), "empty Hindi string $key")
            assertEquals(placeholders(text), placeholders(hi.text(key)), "placeholders of $key")
        }
        for ((key, forms) in en.plurals) {
            for (res in listOf(en, hi)) {
                val quantities = res.plurals.getValue(key)
                assertTrue("one" in quantities && "other" in quantities, "plural $key needs one and other")
                assertEquals(placeholders(forms.getValue("other")), placeholders(quantities.getValue("other")), "placeholders of $key")
            }
        }
        val source = File(dir, "values-hi/strings.xml").readText()
        assertTrue("NEEDS NATIVE-SPEAKER REVIEW" in source, "the Hindi file is marked for native-speaker review")
        assertTrue(hi.strings.values.any { it.any { c -> c in 'ऀ'..'ॿ' } }, "Hindi strings are in Devanagari")
    }

    @Test
    fun onlyPositionalPlaceholdersThatComposeResourcesSupport() {
        for ((key, text) in en.strings + hi.strings) {
            val stray = Regex("%(?!\\d+\\$[sd])").find(text)
            assertTrue(stray == null, "$key uses an unsupported format: $text")
            assertTrue('{' !in text && '}' !in text, "$key still has a design-style {placeholder}: $text")
        }
    }

    @Test
    fun fF2_badgeCopyMirrorsCoreLadderExactly() {
        for (key in BadgeKey.entries) {
            val resource = key.key.replace('.', '_')
            val badge =
                when (key) {
                    BadgeKey.P2P_5 -> TransportBadge.of(LinkKind.P2P, 5180)
                    BadgeKey.P2P_24 -> TransportBadge.of(LinkKind.P2P, 2437)
                    BadgeKey.P2P_6 -> TransportBadge.of(LinkKind.P2P, 5955)
                    BadgeKey.P2P_UNKNOWN_BAND -> TransportBadge.of(LinkKind.P2P, null)
                    BadgeKey.LAN -> TransportBadge.LAN
                    BadgeKey.HOTSPOT -> TransportBadge.of(LinkKind.HOTSPOT, 2412)
                    BadgeKey.HOTSPOT_UNKNOWN_BAND -> TransportBadge.of(LinkKind.HOTSPOT, 0)
                    BadgeKey.BLUETOOTH -> TransportBadge.BLUETOOTH
                }
            val text = en.text(resource).replace("%1\$s", WifiBand.BAND_2_4_GHZ.label)
            assertEquals(badge.englishText, text, "badge $resource")
        }
        assertEquals(WifiBand.BAND_2_4_GHZ.label, en.text("band_24"))
        assertEquals(WifiBand.BAND_5_GHZ.label, en.text("band_5"))
        assertEquals(WifiBand.BAND_6_GHZ.label, en.text("band_6"))
    }

    @Test
    fun fF3_hintCopyMirrorsCoreLadderExactly() {
        val cases =
            listOf(
                LadderHint.band24() to en.text("hint_band24"),
                LadderHint.peerBand24Only("Asha") to en.text("hint_peer_band24_only").replace("%1\$s", "Asha"),
                LadderHint.peerBand24Only(null) to en.text("hint_peer_band24_only_unnamed"),
                LadderHint.sdcard() to en.text("hint_sdcard"),
                LadderHint.thermal() to en.text("hint_thermal"),
                LadderHint.bundling(240) to en.plural("hint_bundling", "other").replace("%1\$d", "240"),
                LadderHint(HintCode.BUNDLING) to en.text("hint_bundling_many"),
                LadderHint.btFallback() to en.text("hint_bt_fallback"),
                LadderHint.lanSlow() to en.text("hint_lan_slow"),
                LadderHint.stationBand24(Side.LOCAL, null) to en.text("hint_sta_band24"),
                LadderHint.stationBand24(Side.PEER, "Asha") to en.text("hint_sta_band24_peer").replace("%1\$s", "Asha"),
                LadderHint.stationBand24(Side.PEER, null) to en.text("hint_sta_band24_peer_unnamed"),
            )
        for ((hint, text) in cases) assertEquals(hint.englishText, text, "hint ${hint.copyKey}")
        assertEquals(HintCode.entries.toSet(), cases.map { it.first.code }.toSet(), "every hint code has copy")
    }

    /** The table rows of `docs/design.md` §8 as (first cell, quoted texts). */
    private val designRows: List<Pair<String, List<String>>> by lazy {
        File("../../docs/design.md").readLines().filter { it.startsWith("| ") }.map { line ->
            val first = line.removePrefix("| ").substringBefore(" |")
            first to Regex("\"([^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
        }
    }

    private fun design(firstCell: String): List<String> =
        designRows.firstOrNull { it.first == firstCell }?.second ?: fail("design row $firstCell")

    @Test
    fun designSection83_coreCopyKeysUseTheDesignText() {
        val simple =
            mapOf(
                "`radar.title`" to "radar_title",
                "`action.scan`" to "action_scan",
                "`action.show_qr`" to "action_show_qr",
                "`action.dashboard`" to "action_dashboard",
                "`incoming.accept`" to "incoming_accept",
                "`incoming.decline`" to "incoming_decline",
                "`pair.code`" to "pair_code",
                "`pair.confirm`" to "pair_confirm",
                "`transfer.resumed`" to "transfer_resumed",
                "`privacy.statement`" to "privacy_statement",
                "`badge.lan`" to "badge_lan",
                "`badge.bt`" to "badge_bt",
            )
        for ((row, key) in simple) assertEquals(design(row).single(), en.text(key), key)

        fun filled(text: String) = plain(text).replace(Regex("%\\d\\$[sd]"), "#")

        fun designFilled(text: String) = text.replace(Regex("\\{[a-z]+}"), "#")
        assertEquals(designFilled(design("`incoming.title`").single()), filled(en.text("incoming_title")))
        assertEquals(designFilled(design("`incoming.always`").single()), filled(en.text("incoming_always")))
        assertEquals(designFilled(design("`transfer.speed`").single()), filled(en.text("transfer_speed")))
        assertEquals(designFilled(design("`send.button`").single()), filled(en.plural("send_button", "other")))
        assertEquals(designFilled(design("`stats.saved`").single()), filled(en.text("stats_saved")))
        assertEquals(designFilled(design("`badge.hotspot`").single()), filled(en.text("badge_hotspot")))
        assertEquals(listOf(en.text("transfer_done_sent"), en.text("transfer_done_received")), design("`transfer.done`"))
        assertEquals(
            listOf("visibility_everyone", "visibility_ten_min", "visibility_trusted", "visibility_hidden").map { en.text(it) },
            design("`visibility.everyone` / `.ten_min` / `.trusted` / `.hidden`"),
        )
    }

    @Test
    fun designSection81_emptyAndErrorStatesUseTheDesignText() {
        assertEquals(design("No devices yet").single(), en.text("empty_no_devices"))
        assertEquals(design("Bluetooth off").first(), en.text("empty_bluetooth_off"))
        assertEquals(design("Wi‑Fi off").first(), en.text("empty_wifi_off"))
        assertEquals(design("Permission missing").first(), en.text("empty_permission"))
        assertEquals(design("Hidden mode").single(), en.text("empty_hidden"))
        val row = designRows.first { it.first == "Bluetooth off" }
        assertTrue(row.second.size == 1 && File("../../docs/design.md").readText().contains("[Turn on]"))
        assertEquals("Turn on", en.text("action_turn_on"))
        assertEquals("Allow", en.text("action_allow"))
    }

    @Test
    fun n15_computerHintCarriesNetworkPasswordAndAddress() {
        val hint = en.text("qr_computer_hint")
        assertEquals(setOf("%1\$s", "%2\$s", "%3\$s"), placeholders(hint), "SSID, password and the full URL are filled in")
        assertTrue("DROP" !in hint && "drop.local" !in hint, "no placeholder network name or bare host in the copy")
    }
}
