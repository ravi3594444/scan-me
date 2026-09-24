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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.ui.shared.components.LocalTapClock
import com.constrivo.drop.ui.shared.components.SheetHost
import com.constrivo.drop.ui.shared.components.SheetSpec
import com.constrivo.drop.ui.shared.components.TapGuard
import com.constrivo.drop.ui.shared.dashboard.DashboardCallbacks
import com.constrivo.drop.ui.shared.dashboard.DashboardScreen
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.onboarding.WelcomeCallbacks
import com.constrivo.drop.ui.shared.onboarding.WelcomeScreen
import com.constrivo.drop.ui.shared.platform.DropHaptics
import com.constrivo.drop.ui.shared.platform.LocalDropHaptics
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.radar.RadarCallbacks
import com.constrivo.drop.ui.shared.radar.RadarScreen
import com.constrivo.drop.ui.shared.receive.BrowserApprovalCallbacks
import com.constrivo.drop.ui.shared.receive.BrowserApprovalSheet
import com.constrivo.drop.ui.shared.receive.IncomingCallbacks
import com.constrivo.drop.ui.shared.receive.IncomingCard
import com.constrivo.drop.ui.shared.send.ScanQrCallbacks
import com.constrivo.drop.ui.shared.send.ScanQrScreen
import com.constrivo.drop.ui.shared.theme.DropTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            // The content above the buttons may scroll; Accept and Decline are always fully on screen, so the user can
            // answer inside the 30 s countdown without scrolling.
            assertTextFits("incoming card", TestTags.INCOMING_CARD, unclipped = false)
            assertFullyVisible("incoming card", TestTags.INCOMING_ACCEPT)
            assertFullyVisible("incoming card", TestTags.INCOMING_DECLINE)
        }
        ui(fontScale = 2f) {
            show { OverRadar { BrowserApprovalSheet(Samples.browserApproval, BrowserApprovalCallbacks()) } }
            assertFullyVisible("allow this computer", TestTags.BROWSER_ALLOW)
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

    /** The node tagged [tag] is laid out whole and not clipped by any parent or the screen. */
    private fun SkikoComposeUiTest.assertFullyVisible(
        where: String,
        tag: String,
    ) {
        val node = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val screen = onRoot().fetchSemanticsNode().boundsInRoot
        val b = node.boundsInRoot
        assertTrue(b.width >= node.size.width - 0.5f && b.height >= node.size.height - 0.5f, "$where: $tag is clipped at 200% ($b)")
        assertTrue(b.bottom <= screen.bottom + 0.5f && b.top >= screen.top - 0.5f, "$where: $tag is off screen at 200% ($b)")
    }

    @Composable
    private fun OverRadar(sheet: @Composable () -> Unit) =
        SheetHost(SheetSpec("sheet", content = sheet), onDismiss = {}) { Radar(Samples.radar(Samples.threeDevices)) }

    @Test
    fun designSection11_sheetsAreModalForScreenReaders() =
        ui {
            show { Incoming() }
            val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
            onNode(hasTestTag(TestTags.bubble("t:rohan")) and hasAnyAncestor(hidden), useUnmergedTree = true).assertExistsCompat()
            onNode(hasTestTag(TestTags.RADAR_BOTTOM_BAR) and hasAnyAncestor(hidden), useUnmergedTree = true).assertExistsCompat()
            val accept = onNodeWithTag(TestTags.INCOMING_ACCEPT, useUnmergedTree = true)
            accept.assert(!hasAnyAncestor(hidden) and !hidden)
        }

    /** Shows [content] with a tap clock the test moves; returns a setter for "now". */
    private fun SkikoComposeUiTest.showWithTapClock(content: @Composable () -> Unit): (Long) -> Unit {
        var now by mutableStateOf(0L)
        show { CompositionLocalProvider(LocalTapClock provides { now }, content = content) }
        return { now = it }
    }

    @Test
    fun securityPromptsIgnoreTapsWhileTheyAppear() {
        ui {
            var allowed = 0
            val setNow =
                showWithTapClock { BrowserApprovalSheet(Samples.browserApproval, BrowserApprovalCallbacks(onAllow = { allowed++ })) }
            onNodeWithTag(TestTags.BROWSER_ALLOW).performClick()
            waitForIdle()
            assertEquals(0, allowed, "a tap meant for what was underneath grants nothing")
            setNow(TapGuard.ARM_MILLIS)
            onNodeWithTag(TestTags.BROWSER_ALLOW).performClick()
            waitForIdle()
            assertEquals(1, allowed, "a deliberate tap works once the prompt has settled")
        }
        ui {
            var accepted = 0
            var confirmed = 0
            val setNow =
                showWithTapClock {
                    IncomingCard(
                        Samples.incoming(trusted = false),
                        IncomingCallbacks(onAccept = {
                            accepted++
                        }, onSasConfirmed = { confirmed++ }),
                    )
                }
            onNodeWithTag(TestTags.INCOMING_ACCEPT).performClick()
            onNodeWithText("Yes, it matches").performClick()
            waitForIdle()
            assertEquals(0 to 0, accepted to confirmed)
            setNow(TapGuard.ARM_MILLIS)
            onNodeWithTag(TestTags.INCOMING_ACCEPT).performClick()
            onNodeWithText("Yes, it matches").performClick()
            waitForIdle()
            assertEquals(1 to 1, accepted to confirmed)
        }
    }

    @Test
    fun designSection44_aScannedCodePlaysTheHaptic() =
        ui {
            var scanned = 0
            val haptics =
                object : DropHaptics {
                    override fun confirm() = Unit

                    override fun scanned() {
                        scanned++
                    }
                }
            var status by mutableStateOf(ScanStatus.SCANNING)
            show { CompositionLocalProvider(LocalDropHaptics provides haptics) { ScanQrScreen(status, ScanQrCallbacks()) } }
            waitForIdle()
            assertEquals(0, scanned)
            status = ScanStatus.SUCCESS
            waitForIdle()
            assertEquals(1, scanned)
            onNodeWithText("Found it").assertExistsCompat()
        }

    @Test
    fun screenshotsKeyTextsAreExact() =
        ui {
            // The screenshots guard layout; the texts that carry numbers are checked exactly here.
            show { Radar(Samples.sendingRadar) }
            // The resources put no-break spaces between numbers and units.
            onNode(hasText("44\u00A0MB/s · 45\u00A0s left"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasText("Wi‑Fi Direct · 2.4 GHz"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasText("Move closer for full speed"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasText("Everyone · 7\u00A0min"), useUnmergedTree = true).assertExistsCompat()
        }

    @Test
    fun designSection43_theShareBannerNamesTheFiles() =
        ui {
            show { Radar(Samples.shareBanner) }
            onNode(hasText("Sending 12 photos · 48\u00A0MB — tap a device"), useUnmergedTree = true).assertExistsCompat()
            onNode(hasText("IMG_2034.jpg, IMG_2035.jpg and 10 more"), useUnmergedTree = true).assertExistsCompat()
        }

    @Test
    fun fI4_theStatsAxisDoesNotMirrorInRightToLeftLayouts() =
        ui {
            show {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DashboardScreen(Samples.dashboard(DashboardTab.STATS), DashboardCallbacks())
                }
            }
            val thisWeek = onNode(hasText("This week"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val oldest = onNode(hasText("11 weeks ago"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(thisWeek.left > oldest.left, "\"This week\" stays under the newest bar, on the right")
        }

    @Test
    fun decision9_theLanguageSettingAppliesWithoutARestart() =
        ui {
            val previous = Locale.getDefault()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val controller = DropAppController(scope, DropDependencies.inMemory(wallClock = SystemWallClock))
                setContent { DropApp(controller, fontFamily = Screenshots.testFont, dark = false) }
                onNode(hasText("Nearby"), useUnmergedTree = true).assertExistsCompat()
                controller.dashboard.settings.setLanguage(AppLanguage.HINDI)
                waitForIdle()
                onNode(hasText("आस-पास"), useUnmergedTree = true).assertExistsCompat()
                controller.dashboard.settings.setLanguage(AppLanguage.SYSTEM)
                waitForIdle()
                onNode(hasText("Nearby"), useUnmergedTree = true).assertExistsCompat()
            } finally {
                scope.cancel()
                Locale.setDefault(previous)
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
