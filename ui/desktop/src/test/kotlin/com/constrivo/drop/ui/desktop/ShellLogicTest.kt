package com.constrivo.drop.ui.desktop

import androidx.compose.ui.input.key.Key
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeEvent
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTransfer
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.presenter.Screen
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shell's rules of design §9 (drop targets, shortcuts, notifications), without a window. */
class ShellLogicTest {
    private val en = DesktopStrings.of(Locale.ENGLISH)

    private fun bubble(
        key: String,
        trusted: Boolean = false,
        busy: Boolean = false,
        ring: Ring = Ring.MIDDLE,
    ) = BubbleUi(
        key = key,
        name = key,
        initials = key.take(1),
        avatarHash = key.hashCode(),
        platform = DevicePlatform.PHONE,
        trusted = trusted,
        ring = ring,
        lanOnly = true,
        activity = if (busy) BubbleActivity.Completed("t-$key", Direction.SEND, "token") else null,
    )

    private fun radar(
        bubbles: List<BubbleUi>,
        notice: RadarNotice? = null,
    ) = RadarUiState.initial(SelfProfile("Asha", "self")).copy(bubbles = bubbles, notice = notice)

    // ---- drop targets ----

    @Test
    fun dropOnAnIdleBubbleSendsElseAsksSendTo() {
        val idle = bubble("a")
        val busy = bubble("b", busy = true)
        assertEquals(DropDecision.SendTo("a"), DropTargets.decide(Screen.RADAR, hasFiles = true, bubbleUnderPointer = idle))
        assertEquals(DropDecision.ChooseDevice, DropTargets.decide(Screen.RADAR, true, busy))
        assertEquals(DropDecision.ChooseDevice, DropTargets.decide(Screen.RADAR, true, null))
        assertEquals(DropDecision.ChooseDevice, DropTargets.decide(Screen.DASHBOARD, true, idle))
        assertEquals(DropDecision.Ignore, DropTargets.decide(Screen.ONBOARDING, true, idle))
        assertEquals(DropDecision.Ignore, DropTargets.decide(Screen.RADAR, hasFiles = false, bubbleUnderPointer = idle))
        // The open picker takes the files, whatever lies under the pointer; onboarding still ignores them.
        assertEquals(DropDecision.AddToPicker, DropTargets.decide(Screen.RADAR, true, null, pickerOpen = true))
        assertEquals(DropDecision.AddToPicker, DropTargets.decide(Screen.RADAR, true, idle, pickerOpen = true))
        assertEquals(DropDecision.AddToPicker, DropTargets.decide(Screen.DASHBOARD, true, null, pickerOpen = true))
        assertEquals(DropDecision.Ignore, DropTargets.decide(Screen.ONBOARDING, true, null, pickerOpen = true))
        assertEquals(DropDecision.Ignore, DropTargets.decide(Screen.RADAR, false, null, pickerOpen = true))
    }

    @Test
    fun onlyLocalFileUrisAreDropped() {
        val dir = Files.createTempDirectory("drop-uris")
        try {
            val file = Files.createFile(dir.resolve("a b.txt"))
            val paths =
                DropTargets.paths(
                    listOf(file.toUri().toString(), file.toUri().toString(), "https://example.com/x", "not a uri", "file://host/share/x"),
                )
            assertEquals(listOf(file), paths)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun bubbleTargetsFollowTheRadarLayout() {
        val state = radar(listOf(bubble("a", trusted = true, ring = Ring.INNER), bubble("b"), bubble("c", ring = Ring.OUTER)))
        val targets = RadarHitTest.targets(state, 420.0, 600.0)
        assertEquals(setOf("a", "b", "c"), targets.map { it.key }.toSet())
        val a = targets.first { it.key == "a" }
        val b = targets.first { it.key == "b" }
        assertEquals((64.0 + 10.0) / 2 + 8.0, a.radiusDp, "trusted bubbles are larger")
        assertEquals((56.0 + 10.0) / 2 + 8.0, b.radiusDp)
        for (t in targets) {
            assertEquals(t.key, RadarHitTest.bubbleAt(state, 420.0, 600.0, t.xDp, t.yDp)?.key)
            assertEquals(t.key, RadarHitTest.bubbleAt(state, 420.0, 600.0, t.xDp + t.radiusDp - 1, t.yDp)?.key)
            assertNull(RadarHitTest.bubbleAt(state, 420.0, 600.0, t.xDp + t.radiusDp + 40, t.yDp + 200)?.takeIf { it.key == t.key })
        }
        // Far corners and the bottom bar are never a bubble.
        assertNull(RadarHitTest.bubbleAt(state, 420.0, 600.0, 2.0, 2.0))
        assertNull(RadarHitTest.bubbleAt(state, 420.0, 600.0, 210.0, 590.0))
        assertNull(RadarHitTest.bubbleAt(state, 420.0, 600.0, -5.0, 300.0))
        // A notice moves the reserve down; too small an area has no targets.
        assertEquals(RadarHitTest.NOTICE_RESERVE_DP + 72.0, RadarHitTest.topReserve(radar(emptyList(), RadarNotice.HIDDEN)))
        assertTrue(RadarHitTest.targets(state, 420.0, 40.0).isEmpty())
    }

    // ---- keyboard ----

    private fun context(
        screen: Screen = Screen.RADAR,
        sendTo: Boolean = false,
        picker: Boolean = false,
        selection: Boolean = false,
    ) = ShortcutContext(screen, sendTo, picker, selection)

    @Test
    fun ctrlOPicksFilesAndCmdOOnMac() {
        assertEquals(ShortcutAction.PICK_FILES, KeyboardShortcuts.action(KeyPress(Key.O, ctrl = true), context(), DesktopOs.LINUX))
        assertEquals(ShortcutAction.PICK_FILES, KeyboardShortcuts.action(KeyPress(Key.O, ctrl = true), context(), DesktopOs.WINDOWS))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.O, meta = true), context(), DesktopOs.LINUX))
        assertEquals(ShortcutAction.PICK_FILES, KeyboardShortcuts.action(KeyPress(Key.O, meta = true), context(), DesktopOs.MAC))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.O, ctrl = true), context(), DesktopOs.MAC))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.O, ctrl = true, shift = true), context(), DesktopOs.LINUX))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.O), context(), DesktopOs.LINUX))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.O, ctrl = true), context(Screen.ONBOARDING), DesktopOs.LINUX))
    }

    @Test
    fun escCancelsAndEnterSendsOnlyWhenThereIsSomethingToSend() {
        val os = DesktopOs.LINUX
        assertEquals(ShortcutAction.CANCEL, KeyboardShortcuts.action(KeyPress(Key.Escape), context(), os))
        assertEquals(ShortcutAction.CANCEL, KeyboardShortcuts.action(KeyPress(Key.Escape), context(Screen.DASHBOARD), os))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.Escape), context(Screen.ONBOARDING), os))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.Escape, ctrl = true), context(), os))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.Enter), context(), os), "Enter reaches text fields")
        assertNull(KeyboardShortcuts.action(KeyPress(Key.Enter), context(picker = true, selection = false), os))
        assertEquals(ShortcutAction.SEND, KeyboardShortcuts.action(KeyPress(Key.Enter), context(picker = true, selection = true), os))
        assertEquals(ShortcutAction.SEND, KeyboardShortcuts.action(KeyPress(Key.NumPadEnter), context(sendTo = true), os))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.Enter, shift = true), context(sendTo = true), os))
        assertEquals(ShortcutAction.NEXT, KeyboardShortcuts.action(KeyPress(Key.DirectionDown), context(sendTo = true), os))
        assertEquals(ShortcutAction.PREVIOUS, KeyboardShortcuts.action(KeyPress(Key.DirectionUp), context(sendTo = true), os))
        assertNull(KeyboardShortcuts.action(KeyPress(Key.DirectionDown), context(), os))
    }

    // ---- notifications ----

    private fun finished(
        stage: NodeStage,
        direction: NodeDirection,
    ) = NodeEvent.TransferFinished(
        NodeTransfer(
            id = "t",
            direction = direction,
            peerKey = null,
            peerDeviceId = "d",
            peerName = "Rohan's Pixel",
            peerPlatform = DevicePlatform.PHONE,
            stage = stage,
            fileCount = 12,
            bytesTotal = 1,
            bytesDone = 1,
        ),
        folder = if (direction == NodeDirection.RECEIVE && stage == NodeStage.DONE) Paths.get("/home/asha/Received/Drop/Photos") else null,
        receivedFiles = 12,
    )

    @Test
    fun aReceivedDropNotifiesWithOpenOnItsFolder() {
        val n = assertNotNull(Notifications.finished(finished(NodeStage.DONE, NodeDirection.RECEIVE), en))
        assertEquals("Received from Rohan's Pixel", n.title)
        assertEquals("12 files in Photos", n.body)
        assertEquals(NotificationAction.OpenFolder(Paths.get("/home/asha/Received/Drop/Photos")), n.action)
        assertTrue(Notifications.shouldShow(n, windowFocused = true), "announced even in front")

        val sent = assertNotNull(Notifications.finished(finished(NodeStage.DONE, NodeDirection.SEND), en))
        assertEquals("Sent to Rohan's Pixel", sent.title)
        assertEquals("12 files delivered", sent.body)
        assertEquals(NotificationAction.ShowWindow, sent.action)
        assertTrue(!Notifications.shouldShow(sent, windowFocused = true) && Notifications.shouldShow(sent, windowFocused = false))

        val failed = assertNotNull(Notifications.finished(finished(NodeStage.FAILED, NodeDirection.RECEIVE), en))
        assertTrue(failed.warning)
        assertEquals("Transfer with Rohan's Pixel did not finish", failed.title)

        for (quiet in listOf(NodeStage.CANCELLED, NodeStage.DECLINED, NodeStage.NO_ANSWER)) {
            assertNull(Notifications.finished(finished(quiet, NodeDirection.SEND), en), "$quiet is the user's doing")
        }
    }

    @Test
    fun aClickSoonAfterANotificationRunsItsActionOnce() {
        var now = 0L
        val clicks = NotificationClicks(MonotonicClock { now }, windowMillis = 10_000)
        assertEquals(NotificationAction.ShowWindow, clicks.clicked())
        val folder = NotificationAction.OpenFolder(Paths.get("/tmp/x"))
        clicks.shown(DesktopNotification("t", "b", folder))
        now = 9_000
        assertEquals(folder, clicks.clicked())
        assertEquals(NotificationAction.ShowWindow, clicks.clicked(), "only once")
        clicks.shown(DesktopNotification("t", "b", folder))
        now = 30_000
        assertIs<NotificationAction.ShowWindow>(clicks.clicked(), "a late click opens the window")
    }
}
