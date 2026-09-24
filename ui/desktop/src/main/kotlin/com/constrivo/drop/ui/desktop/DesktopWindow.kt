package com.constrivo.drop.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The app's one window (design §9): 420 × 640 by default, resizable, back where it was last time. Closing it hides it
 * to the tray when there is one ([trayInstalled]); otherwise closing ends the app. Started from the login entry
 * ([startHidden]) it stays hidden until the tray's "Open".
 */
@OptIn(FlowPreview::class)
@Composable
fun ApplicationScope.DesktopWindow(
    app: DesktopApp,
    placements: WindowPlacementStore,
    trayInstalled: Boolean,
    startHidden: Boolean,
) {
    val restored = remember { WindowPlacement.restore(placements.load(), screenAreas()) }
    val state =
        rememberWindowState(
            position =
                if (restored.x != null && restored.y != null) {
                    WindowPosition(restored.x.dp, restored.y.dp)
                } else {
                    WindowPosition.Aligned(Alignment.Center)
                },
            size = DpSize(restored.width.dp, restored.height.dp),
        )
    var visible by remember { mutableStateOf(!(startHidden && trayInstalled)) }
    val strings by app.strings.collectAsState()
    val banner by app.banner.collectAsState()
    val finishedPairing by app.finishedPairing.collectAsState()
    val icon = remember { BitmapPainter(TrayIcons.render(TrayState.IDLE, ICON_SIZE).toComposeImageBitmap()) }

    LaunchedEffect(app) {
        app.windowRequests.collect { request ->
            when (request) {
                WindowRequest.SHOW -> {
                    visible = true
                }

                WindowRequest.QUIT -> {
                    placementOf(state)?.let(placements::save)
                    exitApplication()
                }
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { placementOf(state) }.debounce(PLACEMENT_SAVE_DELAY_MILLIS).collect { p -> p?.let(placements::save) }
    }
    LaunchedEffect(visible) { if (visible) app.controller.onHostStarted() else app.controller.onHostStopped() }

    Window(
        onCloseRequest = {
            placementOf(state)?.let(placements::save)
            if (trayInstalled) visible = false else app.requestQuit()
        },
        state = state,
        visible = visible,
        title = strings["app.title"],
        icon = icon,
        onPreviewKeyEvent = { event ->
            event.type == KeyEventType.KeyDown &&
                app.onKey(KeyPress(event.key, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed, event.isShiftPressed))
        },
    ) {
        DisposableEffect(window) {
            window.minimumSize = Dimension(WindowPlacement.MIN_WIDTH, WindowPlacement.MIN_HEIGHT)
            val focus =
                object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) {
                        app.windowFocused.value = true
                    }

                    override fun windowLostFocus(e: WindowEvent) {
                        app.windowFocused.value = false
                    }
                }
            window.addWindowFocusListener(focus)
            onDispose { window.removeWindowFocusListener(focus) }
        }
        LaunchedEffect(visible) {
            if (visible) {
                window.toFront()
                window.requestFocus()
            } else {
                app.windowFocused.value = false
            }
        }
        DesktopShell(app.controller, app.shell, strings, banner, pairing = finishedPairing, pairingActions = app.finishedPairingActions)
    }
}

/** The window's placement to remember; null while the OS still decides where it goes. */
private fun placementOf(state: WindowState): WindowPlacement? {
    val position = state.position
    if (!position.isSpecified) return null
    val size = state.size
    if (!size.isSpecified) return null
    return WindowPlacement(position.x.value.toInt(), position.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt())
}

/** Every screen's usable area (without task bars and docks); empty when headless. */
private fun screenAreas(): List<ScreenArea> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    val toolkit = Toolkit.getDefaultToolkit()
    return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val config = device.defaultConfiguration
        val bounds = config.bounds
        val insets = toolkit.getScreenInsets(config)
        ScreenArea(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }
}

private const val ICON_SIZE = 64
private const val PLACEMENT_SAVE_DELAY_MILLIS = 500L
