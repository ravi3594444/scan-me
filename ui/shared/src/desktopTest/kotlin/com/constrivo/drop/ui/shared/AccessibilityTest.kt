package com.constrivo.drop.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.constrivo.drop.ui.shared.components.SheetHost
import com.constrivo.drop.ui.shared.components.SheetSpec
import com.constrivo.drop.ui.shared.dashboard.DashboardCallbacks
import com.constrivo.drop.ui.shared.dashboard.DashboardScreen
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.onboarding.WelcomeCallbacks
import com.constrivo.drop.ui.shared.onboarding.WelcomeScreen
import com.constrivo.drop.ui.shared.radar.RadarCallbacks
import com.constrivo.drop.ui.shared.radar.RadarScreen
import com.constrivo.drop.ui.shared.receive.IncomingCallbacks
import com.constrivo.drop.ui.shared.receive.IncomingCard
import com.constrivo.drop.ui.shared.theme.DropTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accessibility checks of design §11 on the semantics tree: 48 dp touch targets, spoken labels ("Rohan's Pixel, right
 * here, trusted", double-tap to send), progress announced every 25%, text at 200% neither clipped nor cut short, the
 * Hindi resources in use under a Hindi locale, and a radar that stays put in right-to-left layouts.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {
    private fun ui(
        fontScale: Float = 1f,
        block: SkikoComposeUiTest.() -> Unit,
    ) = runSkikoComposeUiTest(
        size = Size(Screenshots.WIDTH_DP.toFloat(), Screenshots.HEIGHT_DP.toFloat()),
        density = Density(1f, fontScale),
    ) {
        Locale.setDefault(Locale.US)
        block()
    }

    private fun SkikoComposeUiTest.show(content: @Composable () -> Unit) =
        setContent {
            DropTheme(dark = false, fontFamily = Screenshots.testFont, content = content)
        }

    @Composable
    private fun Radar(state: RadarUiState) = RadarScreen(state, RadarCallbacks())

    @Composable
    private fun Incoming() =
        SheetHost(SheetSpec("incoming") { IncomingCard(Samples.incoming(trusted = false), IncomingCallbacks()) }, onDismiss = {}) {
            Radar(Samples.radar(Samples.threeDevices))
        }

    private fun SkikoComposeUiTest.clickables(): List<SemanticsNode> =
        onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()

    private fun SkikoComposeUiTest.assertTouchTargets(screen: String) {
        val nodes = clickables()
        assertTrue(nodes.isNotEmpty(), "$screen has clickable nodes")
        for (node in nodes) {
            // The layout size, not the on-screen bounds: targets scrolled out of view are clipped to nothing, and the
            // touch bounds include any minimum-touch-target extension (density 1, so pixels are dp).
            val touch = node.touchBoundsInRoot
            val width = maxOf(node.size.width.toFloat(), touch.width)
            val height = maxOf(node.size.height.toFloat(), touch.height)
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)
            assertTrue(width >= 47.5f && height >= 47.5f, "$screen: target $label is $width × $height dp, below 48 dp")
        }
    }

    @Test
    fun designSection11_touchTargetsAreAtLeast48dp() {
        val screens: List<Pair<String, @Composable () -> Unit>> =
            listOf(
                "radar" to @Composable { Radar(Samples.radar(Samples.threeDevices)) },
                "sending" to @Composable { Radar(Samples.sendingRadar) },
                "tray" to @Composable { Radar(Samples.tray) },
                "notice" to @Composable { Radar(Samples.bluetoothOff) },
                "incoming" to @Composable { Incoming() },
                "welcome" to @Composable { WelcomeScreen(Samples.welcome, WelcomeCallbacks()) },
            ) +
                DashboardTab.entries.map { tab ->
                    "dashboard $tab" to
                        @Composable { DashboardScreen(Samples.dashboard(tab), DashboardCallbacks()) }
                }
        for ((name, content) in screens) {
            ui {
                show(content)
                waitForIdle()
                assertTouchTargets(name)
            }
        }
    }

    @Test
    fun designSection11_bubblesAreLabelledAndSayDoubleTapToSend() =
        ui {
            show { Radar(Samples.radar(Samples.threeDevices)) }
            val node = onNode(hasContentDescription("Rohan's Pixel, right here, trusted"), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("send", node.config[SemanticsActions.OnClick].label, "TalkBack reads 'double-tap to send'")
            onNode(hasContentDescription("Meera, nearby"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasContentDescription("You, Asha Verma"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasContentDescription("Visibility: Trusted only"), useUnmergedTree = true).assertExistsCompat()
        }

    @Test
    fun designSection11_progressIsAnnouncedEvery25Percent() =
        ui {
            var fraction by mutableStateOf(0.42f)
            show {
                val state = Samples.sendingRadar
                val bubbles = state.bubbles.map { b -> if (b.activity != null) b.copy(activity = Samples.sending(fraction)) else b }
                Radar(state.copy(bubbles = bubbles))
            }
            val announcer = { onNodeWithTag(TestTags.PROGRESS_ANNOUNCER, useUnmergedTree = true).fetchSemanticsNode() }
            assertEquals(listOf("Sending to Rohan's Pixel, 25 percent"), announcer().config[SemanticsProperties.ContentDescription])
            assertEquals(LiveRegionMode.Polite, announcer().config[SemanticsProperties.LiveRegion])
            fraction = 0.49f
            waitForIdle()
            assertEquals(
                listOf("Sending to Rohan's Pixel, 25 percent"),
                announcer().config[SemanticsProperties.ContentDescription],
                "unchanged inside a step",
            )
            fraction = 0.51f
            waitForIdle()
            assertEquals(listOf("Sending to Rohan's Pixel, 50 percent"), announcer().config[SemanticsProperties.ContentDescription])
        }

    /**
     * Every text under [tag] is laid out whole: no line is ellipsized or cut off vertically, and nothing leaves the
     * screen sideways. With [unclipped] the texts must also be fully visible (not clipped by a parent); scrolling
     * containers such as the incoming card pass false, since text below the fold is reachable by scrolling.
     */
    private fun SkikoComposeUiTest.assertTextFits(
        where: String,
        tag: String,
        unclipped: Boolean = true,
    ) {
        val window = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val texts =
            onAllNodes(
                hasText("", substring = true) and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
        assertTrue(texts.isNotEmpty(), "$where has text")
        for (node in texts) {
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            for (layout in layouts) {
                assertFalse(layout.didOverflowHeight, "$where: '$text' is cut off at 200%")
                assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) }, "$where: '$text' is ellipsized at 200%")
            }
            val b: Rect = node.boundsInRoot
            if (b.isEmpty) continue
            assertTrue(b.left >= window.left - 0.5f && b.right <= window.right + 0.5f, "$where: '$text' leaves the screen horizontally")
            if (unclipped) {
                assertTrue(b.width >= node.size.width - 0.5f && b.height >= node.size.height - 0.5f, "$where: '$text' is clipped at 200%")
            }
        }
    }

    @Test
    fun designSection11_twoHundredPercentTextDoesNotClipTheBarOrTheCard() {
        ui(fontScale = 2f) {
            show { Radar(Samples.radar(Samples.threeDevices)) }
            assertTextFits("bottom bar", TestTags.RADAR_BOTTOM_BAR)
            assertTouchTargets("radar at 200%")
        }
        ui(fontScale = 2f) {
            show { Incoming() }
            assertTextFits("incoming card", TestTags.INCOMING_CARD, unclipped = false)
        }
        ui(fontScale = 2f) {
            show { Radar(Samples.bluetoothOff) }
            assertTextFits("notice card", TestTags.RADAR_NOTICE)
        }
        ui(fontScale = 2f) {
            show { DashboardScreen(Samples.dashboard(DashboardTab.SETTINGS), DashboardCallbacks()) }
            assertTextFits("settings", TestTags.DASHBOARD, unclipped = false)
        }
    }

    @Test
    fun decision9_hindiLocaleUsesTheHindiStrings() =
        ui {
            val previous = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("hi-IN"))
                show { Radar(Samples.radar()) }
                onNode(hasText("आस-पास"), useUnmergedTree = true).assertExistsCompat()
                onNode(hasText("स्कैन करके भेजें"), useUnmergedTree = true).assertExistsCompat()
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun fI4_radarGeometryIsTheSameInRightToLeftLayouts() {
        fun boundsOf(direction: LayoutDirection): Rect {
            var bounds = Rect.Zero
            ui {
                show { CompositionLocalProvider(LocalLayoutDirection provides direction) { Radar(Samples.radar(Samples.threeDevices)) } }
                bounds = onNodeWithTag(TestTags.bubble("t:rohan"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            }
            return bounds
        }
        assertEquals(boundsOf(LayoutDirection.Ltr), boundsOf(LayoutDirection.Rtl), "bubbles are placed geometrically, not mirrored")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() {
        fetchSemanticsNode()
    }
}
