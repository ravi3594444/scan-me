package com.constrivo.drop.ui.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.ui.shared.DropApp

/** Desktop window: 420 × 640, resizable (design §9). Tray, drop zone and radar arrive in WP10a. */
fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = AppIdentity.DISPLAY_NAME,
            state = rememberWindowState(width = 420.dp, height = 640.dp),
        ) {
            DropApp()
        }
    }
