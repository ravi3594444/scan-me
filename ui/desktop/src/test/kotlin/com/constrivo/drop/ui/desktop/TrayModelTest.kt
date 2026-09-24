package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.data.VisibilityPreference
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTransfer
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Design §9 tray: the three states, the menu, the animated arc; all without a display. */
class TrayModelTest {
    private val en = DesktopStrings.of(Locale.ENGLISH)

    private fun transfer(
        id: String,
        stage: NodeStage,
        direction: NodeDirection = NodeDirection.SEND,
        code: String? = null,
    ) = NodeTransfer(
        id = id,
        direction = direction,
        peerKey = "k-$id",
        peerDeviceId = null,
        peerName = "Rohan's Pixel",
        peerPlatform = DevicePlatform.PHONE,
        stage = stage,
        fileCount = 3,
        bytesTotal = 300,
        bytesDone = 100,
        bytesPerSecond = null,
        etaMillis = null,
        badge = null,
        hint = null,
        mimeHistogram = emptyMap(),
        pairingCode = code,
    )

    private fun offer(id: String) =
        NodeOffer(
            id = id,
            senderDeviceId = "d-$id",
            senderKey = "k-$id",
            senderName = "Meera",
            senderPlatform = DevicePlatform.PHONE,
            trusted = false,
            sas = "123456",
            fileCount = 1,
            totalBytes = 10,
            mimeHistogram = emptyMap(),
            previewNames = emptyList(),
            previews = emptyList(),
            arrivedAtElapsedMillis = 0,
            timeoutMillis = 60_000,
        )

    @Test
    fun attentionWinsOverTransferringWhichWinsOverIdle() {
        assertEquals(TrayState.IDLE, TrayModel.state(0, 0, 0, 0))
        assertEquals(TrayState.TRANSFERRING, TrayModel.state(0, 0, 0, 2))
        assertEquals(TrayState.ATTENTION, TrayModel.state(1, 0, 0, 2))
        assertEquals(TrayState.ATTENTION, TrayModel.state(0, 1, 0, 0))
        assertEquals(TrayState.ATTENTION, TrayModel.state(0, 0, 1, 0))
    }

    @Test
    fun stateFollowsTheNodeFinishedTransfersDoNotCount() {
        assertEquals(TrayState.IDLE, TrayModel.state(emptyList(), listOf(transfer("a", NodeStage.DONE)), browserPrompt = false))
        assertEquals(TrayState.TRANSFERRING, TrayModel.state(emptyList(), listOf(transfer("a", NodeStage.TRANSFERRING)), false))
        assertEquals(TrayState.ATTENTION, TrayModel.state(listOf(offer("o")), emptyList(), false))
        assertEquals(TrayState.ATTENTION, TrayModel.state(emptyList(), emptyList(), browserPrompt = true))
        // The sender's code waits for the user; the receiver's code is on the incoming card (an offer).
        assertEquals(
            TrayState.ATTENTION,
            TrayModel.state(emptyList(), listOf(transfer("a", NodeStage.AWAITING_ACCEPT, code = "123456")), false),
        )
        assertEquals(
            TrayState.TRANSFERRING,
            TrayModel.state(emptyList(), listOf(transfer("a", NodeStage.AWAITING_ACCEPT, NodeDirection.RECEIVE, "123456")), false),
        )
        assertEquals(TrayState.IDLE, TrayModel.state(emptyList(), listOf(transfer("a", NodeStage.FAILED, code = "123456")), false))
    }

    @Test
    fun menuIsOpenVisibilityReceivedFolderQuitAndAutoStartWhereAvailable() {
        val view =
            TrayModel.view(
                TrayState.IDLE,
                Visibility.TRUSTED_ONLY,
                autoStartAvailable = false,
                autoStartEnabled = false,
                strings = en,
            )
        assertEquals("Drop", view.tooltip)
        val labels = view.menu.map { it.label }
        assertEquals(listOf("Open", "Visibility", "Received folder", "", "Quit"), labels)
        val choices = (view.menu[1] as TrayMenuItem.Choices).choices
        assertEquals(TrayModel.VISIBILITY_ORDER, choices.map { it.visibility })
        assertEquals(listOf(false, false, true, false), choices.map { it.checked })
        assertEquals(listOf("Everyone for 10 min", "Everyone", "Trusted only", "Hidden"), choices.map { it.label })
        assertEquals(TrayAction.OPEN, (view.menu[0] as TrayMenuItem.Action).action)
        assertEquals(TrayAction.QUIT, (view.menu.last() as TrayMenuItem.Action).action)

        val withAutoStart =
            TrayModel.view(
                TrayState.TRANSFERRING,
                Visibility.HIDDEN,
                autoStartAvailable = true,
                autoStartEnabled = true,
                strings = en,
            )
        val toggle = withAutoStart.menu.filterIsInstance<TrayMenuItem.Toggle>().single()
        assertEquals("Start at login", toggle.label)
        assertTrue(toggle.checked)
        assertEquals(TrayAction.TOGGLE_AUTOSTART, toggle.action)
        assertEquals("Drop — transferring", withAutoStart.tooltip)
        assertEquals(
            "Drop — someone wants to send you files",
            TrayModel.view(TrayState.ATTENTION, Visibility.HIDDEN, false, false, en).tooltip,
        )
    }

    @Test
    fun menuSpeaksHindi() {
        val hi = DesktopStrings.of(Locale.forLanguageTag("hi"))
        val view = TrayModel.view(TrayState.IDLE, Visibility.EVERYONE, false, false, hi)
        assertEquals("खोलें", view.menu[0].label)
        assertEquals("बंद करें", view.menu.last().label)
    }

    @Test
    fun theTenMinuteWindowShowsUntilItEnds() {
        val start = 1_000_000L
        val window =
            VisibilityPreference(
                Visibility.EVERYONE_TEN_MINUTES,
                start + VisibilityPreference.EVERYONE_WINDOW_MILLIS,
                Visibility.TRUSTED_ONLY,
            )
        assertEquals(Visibility.EVERYONE_TEN_MINUTES, TrayModel.shownVisibility(window, start + 1))
        assertEquals(Visibility.TRUSTED_ONLY, TrayModel.shownVisibility(window, start + VisibilityPreference.EVERYONE_WINDOW_MILLIS))
        assertEquals(Visibility.HIDDEN, TrayModel.shownVisibility(VisibilityPreference(Visibility.HIDDEN, null, Visibility.HIDDEN), start))
    }

    @Test
    fun theArcTurnsOnceInTwelveFrames() {
        assertEquals(0, TrayModel.arcStartDegrees(0))
        assertEquals(30, TrayModel.arcStartDegrees(1))
        assertEquals(0, TrayModel.arcStartDegrees(TrayModel.ARC_FRAMES))
        assertEquals(330, TrayModel.arcStartDegrees(-1))
    }

    @Test
    fun iconsDifferByStateAndFrame() {
        val idle = TrayIcons.render(TrayState.IDLE, 32)
        val attention = TrayIcons.render(TrayState.ATTENTION, 32)
        val frame0 = TrayIcons.render(TrayState.TRANSFERRING, 32, 0)
        val frame3 = TrayIcons.render(TrayState.TRANSFERRING, 32, 3)
        assertEquals(32, idle.width)
        assertEquals(32, idle.height)
        assertNotEquals(pixels(idle), pixels(attention))
        assertNotEquals(pixels(idle), pixels(frame0))
        assertNotEquals(pixels(frame0), pixels(frame3))
        assertEquals(pixels(frame0), pixels(TrayIcons.render(TrayState.TRANSFERRING, 32, TrayModel.ARC_FRAMES)))
        // The attention badge sits top-right in the warning colour; the idle icon has nothing there.
        assertTrue(close(Color(attention.getRGB(28, 3), true), TrayIcons.ATTENTION))
        assertFalse(close(Color(idle.getRGB(28, 3), true), TrayIcons.ATTENTION))
        // The middle is the device dot in the accent colour.
        assertTrue(close(Color(idle.getRGB(16, 16), true), TrayIcons.ACCENT))
        assertFailsWith<IllegalArgumentException> { TrayIcons.render(TrayState.IDLE, 4) }
    }

    private fun pixels(image: BufferedImage): List<Int> =
        (0 until image.height).flatMap { y ->
            (0 until image.width).map { x -> image.getRGB(x, y) }
        }

    private fun close(
        a: Color,
        b: Color,
    ): Boolean =
        a.alpha > 200 && kotlin.math.abs(a.red - b.red) < 24 && kotlin.math.abs(a.green - b.green) < 24 &&
            kotlin.math.abs(a.blue - b.blue) < 24
}
