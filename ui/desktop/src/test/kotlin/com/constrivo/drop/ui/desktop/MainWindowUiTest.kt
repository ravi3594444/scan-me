package com.constrivo.drop.ui.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.DiscoverySource
import com.constrivo.drop.core.discovery.EphemeralId
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.RadarPlacement
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.ui.shared.presenter.RadarSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The main window's content at the design's 420 × 640 (design §9), rendered offscreen with Skia: the no-Bluetooth
 * banner with the static code, drop targets that sit on the bubbles, "Send to…" for a drop on empty space, a drop on a
 * bubble sending at once, the shortcuts, and the incoming card over the shell. The shared UI runs on its in-memory
 * backend; the node side of the same flows is covered in `DesktopAppTest`.
 */
@OptIn(ExperimentalTestApi::class)
class MainWindowUiTest {
    private val en = DesktopStrings.of(Locale.ENGLISH)

    private class Fixture(
        onboarding: Boolean = false,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = InMemoryDrop(self = SelfProfile(nickname = "Asha's laptop", deviceKey = "self"))
        val controller =
            DropAppController(
                scope,
                DropDependencies.inMemory(
                    fake,
                    wallClock = SystemWallClock,
                    onboarding = if (onboarding) OnboardingConfig("Asha's laptop", null, fake) else null,
                ),
            )
        val shell = ShellState(controller, scope, io = Dispatchers.Unconfined)
        val files: Path = Files.createTempDirectory("drop-ui-files")

        init {
            Files.writeString(files.resolve("a.txt"), "alpha")
            Files.writeString(files.resolve("b.jpg"), "beta")
        }

        fun paths(): List<Path> = listOf(files.resolve("a.txt"), files.resolve("b.jpg"))

        override fun close() {
            scope.cancel()
            Files.walk(files).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun device(
        key: String,
        nickname: String,
        ring: Ring,
        trusted: Boolean = false,
    ) = NearbyDevice(
        key = key,
        ephemeralId = EphemeralId(key.hashCode().toLong() and 0xFFFF_FFFFL),
        trustedDeviceId = if (trusted) "id-$key" else null,
        nickname = nickname,
        nicknameTruncated = false,
        platform = DevicePlatform.PHONE,
        visibility = Visibility.EVERYONE,
        capabilities = Capabilities.of(Capabilities.Flag.WIFI_5GHZ),
        networkHint = NetworkHint.NONE,
        ring = ring,
        stableAngleDegrees = RadarPlacement.stableAngleDegrees(key),
        smoothedRssiDbm = null,
        classicAddress = null,
        radioAddresses = emptyList(),
        carrier = null,
        lanEndpoints = emptyList(),
        sources = setOf(DiscoverySource.LAN),
        lastSeenElapsedMillis = 0,
    )

    private val devices =
        listOf(
            device("rohan", "Rohan's Pixel", Ring.INNER, trusted = true),
            device("meera", "Meera", Ring.MIDDLE),
            device("dev", "Dev's phone", Ring.OUTER),
        )

    private fun ui(
        fixture: Fixture,
        banner: DesktopBanner? = DesktopBanner.NoBluetooth("DROP:static-code-for-tests"),
        block: SkikoComposeUiTest.() -> Unit,
    ) = runSkikoComposeUiTest(
        size = Size(WindowPlacement.DEFAULT_WIDTH.toFloat(), WindowPlacement.DEFAULT_HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        try {
            setContent { DesktopShell(fixture.controller, fixture.shell, en, banner, dark = false) }
            waitForIdle()
            block()
        } finally {
            fixture.close()
        }
    }

    private fun SkikoComposeUiTest.exists(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun SkikoComposeUiTest.hasTextNode(text: String): Boolean =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRadarShowsTheNoBluetoothBannerWithTheStaticCode() {
        val f = Fixture()
        ui(f) {
            assertTrue(exists(DesktopTags.BANNER))
            assertTrue(exists(DesktopTags.BANNER_QR), "the static QR (F‑B6)")
            assertTrue(hasTextNode(en["banner.noBluetooth"]))
            assertTrue(exists(TestTags.RADAR))
            assertTrue(exists(DesktopTags.DROP_AREA))
            val window = onNodeWithTag(DesktopTags.DROP_AREA, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(WindowPlacement.DEFAULT_WIDTH.toFloat(), window.width)
            assertTrue(window.top > 0f, "the banner sits above the radar")
        }
    }

    @Test
    fun withoutANetworkTheBannerSaysSo() {
        val f = Fixture()
        ui(f, banner = DesktopBanner.NoNetwork) {
            assertTrue(hasTextNode(en["banner.noNetwork"]))
            assertFalse(exists(DesktopTags.BANNER_QR))
        }
    }

    /** The drop targets the shell computes must sit on the bubbles the shared radar draws, whatever reserve applies. */
    @Test
    fun dropTargetsSitOnTheBubbles() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertTargetsOnBubbles("plain radar")
            // The share banner (a drop waiting in "Send to…" has its own sheet, so attach as a share would).
            f.controller.onShareIntent(com.constrivo.drop.ui.shared.model.AttachedFiles(PortMappers.pickedItems(listOf(sendFile(f)))))
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertTargetsOnBubbles("with the share banner")
            f.controller.radar.clearAttachment()
            f.fake.setVisibility(Visibility.HIDDEN)
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertTargetsOnBubbles("with the Hidden notice")
        }
    }

    private fun sendFile(f: Fixture) = com.constrivo.drop.platform.desktop.files.SendFile(f.paths()[0], "a.txt", 5)

    private fun SkikoComposeUiTest.assertTargetsOnBubbles(where: String) {
        for (d in devices) {
            val bubble = onNodeWithTag(TestTags.bubble(d.key), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val target = onNodeWithTag(DesktopTags.dropTarget(d.key), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(abs(bubble.center.x - target.center.x) < 1.5f, "$where: ${d.key} x ${bubble.center} vs ${target.center}")
            assertTrue(abs(bubble.center.y - target.center.y) < 1.5f, "$where: ${d.key} y ${bubble.center} vs ${target.center}")
            assertTrue(target.width >= bubble.width, "$where: the target covers the bubble")
        }
    }

    @Test
    fun aDropOnEmptySpaceAsksSendToAndSendsToTheChosenDevice() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            waitForIdle()
            assertTrue(f.shell.onDrop(listOf(f.files), bubbleKey = null), "a dropped folder is taken")
            waitForIdle()
            assertTrue(exists(DesktopTags.SEND_TO))
            assertTrue(hasTextNode("Send to…"))
            assertTrue(hasTextNode("2 files · 9 B"), "the folder's two files")
            for (d in devices) assertTrue(exists(DesktopTags.sendToDevice(d.key)), d.key)
            onNodeWithTag(DesktopTags.sendToDevice("meera"), useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue("send:meera:2" in f.fake.calls, "${f.fake.calls}")
            assertFalse(exists(DesktopTags.SEND_TO))
            assertNull(f.controller.radar.state.value.attachment, "the files went")
        }
    }

    @Test
    fun aDropOnABubbleSendsAtOnce() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            waitForIdle()
            assertTrue(f.shell.onDrop(f.paths(), bubbleKey = "rohan"))
            waitForIdle()
            assertTrue("send:rohan:2" in f.fake.calls, "${f.fake.calls}")
            assertFalse(exists(DesktopTags.SEND_TO))
        }
    }

    /** The drop targets lie over the bubbles but take no pointer input: a click still opens the bubble's picker. */
    @Test
    fun clicksPassThroughTheDropTargets() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertTrue(exists(DesktopTags.dropTarget("meera")))
            onNodeWithTag(TestTags.bubble("meera"), useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(RadarSheet.PICKER, f.controller.radarSheet.value)
        }
    }

    @Test
    fun draggingShowsWhereTheDropGoes() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            waitForIdle()
            f.shell.onDragOver("meera")
            waitForIdle()
            assertTrue(exists(DesktopTags.DROP_HINT))
            assertTrue(hasTextNode("Drop to send to Meera"))
            f.shell.onDragOver(null)
            waitForIdle()
            assertTrue(hasTextNode("Drop files to send them"))
            f.shell.onDragEnded()
            waitForIdle()
            assertFalse(exists(DesktopTags.DROP_HINT))
        }
    }

    @Test
    fun escClosesSendToAndEnterSendsToTheHighlightedDevice() {
        val f = Fixture()
        ui(f) {
            f.fake.devices.value = devices
            waitForIdle()
            f.shell.onDrop(f.paths(), null)
            waitForIdle()
            assertTrue(exists(DesktopTags.SEND_TO))
            f.shell.perform(ShortcutAction.CANCEL) {}
            waitForIdle()
            assertFalse(exists(DesktopTags.SEND_TO))
            assertNull(f.controller.radar.state.value.attachment, "Esc lets the files go")

            f.shell.onDrop(f.paths(), null)
            waitForIdle()
            val listed = f.shell.sendToDevices(f.controller.radar.state.value.bubbles)
            f.shell.perform(ShortcutAction.NEXT) {}
            f.shell.perform(ShortcutAction.SEND) {}
            waitForIdle()
            assertTrue("send:${listed[1].key}:2" in f.fake.calls, "${f.fake.calls}")

            var picked = false
            f.shell.perform(ShortcutAction.PICK_FILES) { picked = true }
            assertTrue(picked)
        }
    }

    @Test
    fun aDropDuringOnboardingIsIgnored() {
        val f = Fixture(onboarding = true)
        ui(f) {
            assertTrue(exists(TestTags.ONBOARDING))
            assertFalse(exists(DesktopTags.BANNER), "the banner belongs to the radar")
            assertFalse(f.shell.onDrop(f.paths(), null))
            f.shell.onPicked(f.paths())
            waitForIdle()
            assertFalse(exists(DesktopTags.SEND_TO))
        }
    }

    @Test
    fun theIncomingCardShowsOverTheShell() {
        val f = Fixture()
        ui(f) {
            val offer =
                NodeOffer(
                    id = "0123456789abcdef0123456789abcdef",
                    senderDeviceId = "d1",
                    senderKey = "meera",
                    senderName = "Meera",
                    senderPlatform = DevicePlatform.PHONE,
                    trusted = false,
                    sas = "482913",
                    fileCount = 3,
                    totalBytes = 3_000_000,
                    mimeHistogram = mapOf("image/jpeg" to 3),
                    previewNames = emptyList(),
                    previews = emptyList(),
                    arrivedAtElapsedMillis = SystemMonotonicClock.elapsedMillis(),
                )
            f.fake.offers.value = listOf(PortMappers.incoming(offer) { null })
            waitForIdle()
            assertTrue(exists(TestTags.INCOMING_CARD))
            onNodeWithTag(TestTags.INCOMING_DECLINE, useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(f.fake.calls.any { it.startsWith("decline:") }, "${f.fake.calls}")
        }
    }
}
