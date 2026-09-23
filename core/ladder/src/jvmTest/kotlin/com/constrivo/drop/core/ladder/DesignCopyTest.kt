package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The badge and hint copy must be exactly the English strings of `docs/design.md` §8.2 and §8.3, down to the
 * non-breaking hyphen and middle dot, so this test reads them from the design document itself.
 */
class DesignCopyTest {
    /** Tests run with the module directory (core/ladder) as the working directory. */
    private val design: String =
        File(System.getProperty("user.dir")).canonicalFile.parentFile.parentFile
            .resolve("docs/design.md")
            .readText()

    /** The quoted text in the design table row that starts with [firstCell]. */
    private fun row(firstCell: String): String {
        val line = design.lines().first { it.startsWith("| $firstCell |") }
        return Regex("\"([^\"]+)\"").findAll(line).last().groupValues[1]
    }

    @Test
    fun fF2_badgeStringsMatchDesign83() {
        assertEquals(row("`badge.p2p_5`"), TransportBadge.of(LinkKind.P2P, 5180).englishText)
        assertEquals(row("`badge.p2p_24`"), TransportBadge.of(LinkKind.P2P, 2437).englishText)
        assertEquals(row("`badge.lan`"), TransportBadge.of(LinkKind.LAN, null).englishText)
        assertEquals(row("`badge.bt`"), TransportBadge.BLUETOOTH.englishText)
        val hotspot = row("`badge.hotspot`")
        assertEquals(hotspot.replace("{band}", "2.4 GHz"), TransportBadge.of(LinkKind.HOTSPOT, 2412).englishText)
        assertEquals(hotspot.replace("{band}", "5 GHz"), TransportBadge.of(LinkKind.HOTSPOT, 5180).englishText)
        assertTrue(hotspot.contains('\u00B7'))
        assertTrue(row("`badge.p2p_5`").contains('\u2011'))
    }

    @Test
    fun fF3_hintStringsMatchDesign82() {
        assertEquals(row("Link on 2.4 GHz but both support 5 GHz"), LadderHint.band24().englishText)
        assertEquals(row("Other device is 2.4 GHz only").replace("{Name}", "Asha"), LadderHint.peerBand24Only("Asha").englishText)
        assertEquals(row("Destination is a microSD card"), LadderHint.sdcard().englishText)
        assertEquals(row("Thermal throttling reported"), LadderHint.thermal().englishText)
        assertEquals(row("Bundling many small files").replace("{N}", "240"), LadderHint.bundling(240).englishText)
        assertEquals(row("Bluetooth fallback"), LadderHint.btFallback().englishText)
        assertEquals(row("LAN path slower than 10 MB/s"), LadderHint.lanSlow().englishText)
    }

    @Test
    fun n9_stationHintMatchesThePlan() {
        val plan =
            File(System.getProperty("user.dir")).canonicalFile.parentFile.parentFile
                .resolve("docs/implementation-plan.md")
                .readText()
        val text = LadderHint.stationBand24(Side.LOCAL, null).englishText
        assertTrue(plan.contains("\"$text\""), text)
        assertEquals(HintCode.STATION_BAND24, LadderHint.stationBand24(Side.LOCAL, null).code)
    }
}
