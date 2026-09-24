package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeEvent
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.platform.desktop.node.NodeStage
import java.nio.file.Path

/** What clicking a notification does. */
sealed interface NotificationAction {
    /** "Open": the folder the files were saved in (design §9 "notification with 'Open' action on completion"). */
    data class OpenFolder(
        val folder: Path,
    ) : NotificationAction

    /** Bring the window to the front (an incoming card to answer, a finished send, a failure). */
    data object ShowWindow : NotificationAction
}

/** A desktop notification (the tray balloon on Windows, Notification Center on macOS, the notification daemon on Linux). */
data class DesktopNotification(
    val title: String,
    val body: String,
    val action: NotificationAction,
    val warning: Boolean = false,
    /** Shown even while the window has focus: a received drop is announced wherever the user is looking. */
    val evenWhenFocused: Boolean = false,
)

/**
 * The notifications of design §9, as pure functions of the node's events: a received drop ("Open" shows its folder),
 * a finished send and a transfer that did not finish, and an incoming card while the window is in the background.
 * Declines, cancels and unanswered offers were the user's doing or are shown in the window, so they stay silent.
 */
object Notifications {
    fun finished(
        event: NodeEvent.TransferFinished,
        strings: DesktopStrings,
    ): DesktopNotification? {
        val t = event.transfer
        val peer = t.peerName.ifBlank { strings["app.title"] }
        return when {
            t.stage == NodeStage.DONE && t.direction == NodeDirection.RECEIVE -> {
                val folder = event.folder ?: return null
                val count = event.receivedFiles.coerceAtLeast(1)
                DesktopNotification(
                    title = strings["notify.received.title", peer],
                    body = strings["notify.received.body", count, folder.fileName?.toString() ?: folder.toString()],
                    action = NotificationAction.OpenFolder(folder),
                    evenWhenFocused = true,
                )
            }

            t.stage == NodeStage.DONE -> {
                DesktopNotification(
                    title = strings["notify.sent.title", peer],
                    body = strings["notify.sent.body", t.fileCount.coerceAtLeast(1)],
                    action = NotificationAction.ShowWindow,
                )
            }

            t.stage == NodeStage.FAILED -> {
                DesktopNotification(
                    title = strings["notify.failed.title", peer],
                    body = strings["notify.failed.body"],
                    action = NotificationAction.ShowWindow,
                    warning = true,
                )
            }

            else -> {
                null
            }
        }
    }

    /** An incoming card waiting (design §5.1); shown only while the window is not in front. */
    fun incoming(
        offer: NodeOffer,
        strings: DesktopStrings,
    ): DesktopNotification =
        DesktopNotification(
            title = strings["notify.incoming.title", offer.senderName.ifBlank { strings["app.title"] }],
            body = strings["notify.incoming.body"],
            action = NotificationAction.ShowWindow,
        )

    /** Whether [notification] is shown now, with the window focused or not. */
    fun shouldShow(
        notification: DesktopNotification,
        windowFocused: Boolean,
    ): Boolean = notification.evenWhenFocused || !windowFocused
}

/**
 * The action of the notification last shown, for the tray icon's click. AWT reports a click on the balloon (Windows)
 * the same way as a double-click on the icon, without saying which notification it was: a click within
 * [windowMillis] of a notification runs that notification's action once, any other click shows the window.
 */
class NotificationClicks(
    private val clock: MonotonicClock,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private var pending: NotificationAction? = null
    private var shownAt = 0L

    @Synchronized
    fun shown(notification: DesktopNotification) {
        pending = notification.action
        shownAt = clock.elapsedMillis()
    }

    @Synchronized
    fun clicked(): NotificationAction {
        val action = pending
        pending = null
        return if (action != null && clock.elapsedMillis() - shownAt <= windowMillis) action else NotificationAction.ShowWindow
    }

    companion object {
        /** About as long as a balloon stays on screen. */
        const val DEFAULT_WINDOW_MILLIS: Long = 10_000
    }
}
