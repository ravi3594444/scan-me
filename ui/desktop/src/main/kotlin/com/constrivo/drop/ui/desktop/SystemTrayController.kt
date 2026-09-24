package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.discovery.Visibility
import java.awt.AWTException
import java.awt.CheckboxMenuItem
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * The tray / menu-bar icon (design §9) on `java.awt.SystemTray`: it renders a [TrayView] from [TrayModel] (icon for
 * the state, tooltip, menu) and shows [DesktopNotification]s as the icon's messages. Clicking the icon (or, on
 * Windows, a message) runs [onClick]; menu entries run [onAction] and [onVisibility].
 *
 * Everything touching AWT runs on the event dispatch thread. Where the desktop has no tray (headless, some Linux
 * desktops without a status-notifier bridge), [install] returns false and the app keeps its window open instead of
 * hiding to the tray.
 */
class SystemTrayController(
    private val onClick: () -> Unit,
    private val onAction: (TrayAction) -> Unit,
    private val onVisibility: (Visibility) -> Unit,
) {
    private var icon: TrayIcon? = null
    private var shownMenu: List<TrayMenuItem>? = null
    private var shownState: TrayState? = null
    private var shownFrame = -1
    private var lastView: TrayView? = null

    /** Adds the icon showing [view]; false when this desktop has no tray. Call on the event dispatch thread. */
    fun install(view: TrayView): Boolean {
        check(EventQueue.isDispatchThread()) { "the tray is changed on the event dispatch thread" }
        if (icon != null) return true
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()
        val size = tray.trayIconSize
        val created = TrayIcon(TrayIcons.render(view.state, maxOf(size.width, size.height, MIN_ICON)), view.tooltip)
        created.isImageAutoSize = true
        created.addActionListener { onClick() }
        return try {
            tray.add(created)
            icon = created
            shownState = view.state
            shownFrame = 0
            update(view, 0)
            true
        } catch (_: AWTException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Shows [view] with the transferring arc at [frame]; rebuilds the menu only when it changed. */
    fun update(
        view: TrayView,
        frame: Int,
    ) {
        check(EventQueue.isDispatchThread()) { "the tray is changed on the event dispatch thread" }
        val current = icon ?: return
        lastView = view
        val effectiveFrame = if (view.state == TrayState.TRANSFERRING) frame else 0
        if (view.state != shownState || effectiveFrame != shownFrame) {
            val size = SystemTray.getSystemTray().trayIconSize
            current.image = TrayIcons.render(view.state, maxOf(size.width, size.height, MIN_ICON), effectiveFrame)
            shownState = view.state
            shownFrame = effectiveFrame
        }
        if (current.toolTip != view.tooltip) current.toolTip = view.tooltip
        if (view.menu != shownMenu) {
            current.popupMenu = buildMenu(view.menu)
            shownMenu = view.menu
        }
    }

    /** Shows [notification] as the icon's message; false without an icon. */
    fun notify(notification: DesktopNotification): Boolean {
        check(EventQueue.isDispatchThread()) { "the tray is changed on the event dispatch thread" }
        val current = icon ?: return false
        val type = if (notification.warning) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO
        current.displayMessage(notification.title, notification.body, type)
        return true
    }

    fun remove() {
        check(EventQueue.isDispatchThread()) { "the tray is changed on the event dispatch thread" }
        val current = icon ?: return
        icon = null
        shownMenu = null
        SystemTray.getSystemTray().remove(current)
    }

    val installed: Boolean get() = icon != null

    /** AWT already flipped the clicked box: show the model's state again until the change arrives. */
    private fun resetMenu() {
        shownMenu = null
        lastView?.let { update(it, shownFrame) }
    }

    private fun buildMenu(items: List<TrayMenuItem>): PopupMenu {
        val menu = PopupMenu()
        for (item in items) {
            when (item) {
                is TrayMenuItem.Action -> {
                    menu.add(MenuItem(item.label).apply { addActionListener { onAction(item.action) } })
                }

                is TrayMenuItem.Toggle -> {
                    menu.add(
                        CheckboxMenuItem(item.label, item.checked).apply {
                            addItemListener {
                                resetMenu()
                                onAction(item.action)
                            }
                        },
                    )
                }

                is TrayMenuItem.Choices -> {
                    val sub = Menu(item.label)
                    for (choice in item.choices) {
                        sub.add(
                            CheckboxMenuItem(choice.label, choice.checked).apply {
                                // AWT toggles the box itself; the menu is rebuilt from the model, then from the mode the node took.
                                addItemListener {
                                    resetMenu()
                                    onVisibility(choice.visibility)
                                }
                            },
                        )
                    }
                    menu.add(sub)
                }

                TrayMenuItem.Separator -> {
                    menu.addSeparator()
                }
            }
        }
        return menu
    }

    private companion object {
        const val MIN_ICON = 16
    }
}
