package com.constrivo.drop.ui.shared.send

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.components.IconAction
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.platform.LocalDropHaptics
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

@Immutable
class ScanQrCallbacks(
    val onClose: () -> Unit = {},
    val onAllowCamera: () -> Unit = {},
)

/**
 * "Scan to send" chrome (design §4.4): a full-screen [camera] slot the platform fills (CameraX + ZXing on Android),
 * a rounded viewfinder that turns the success colour when a code is recognised, the line "Scan the receiver's code",
 * and the outcomes (expired, not ours, no camera permission). On success the host returns to the radar with the device
 * selected; this screen only plays the haptic.
 */
@Composable
fun ScanQrScreen(
    status: ScanStatus,
    callbacks: ScanQrCallbacks,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit = {},
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val haptics = LocalDropHaptics.current
    LaunchedEffect(status) { if (status == ScanStatus.SUCCESS) haptics.scanned() }
    val frameColor by animateColorAsState(if (status == ScanStatus.SUCCESS) colors.success else Color.White)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (status != ScanStatus.NO_CAMERA_PERMISSION) camera()
        // Offscreen, so clearing the viewfinder reveals the camera instead of the window behind it.
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val side = minOf(size.width, size.height) * 0.7f
            val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.35f), size = size)
            drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(24.dp.toPx()), blendMode = BlendMode.Clear)
            drawRoundRect(frameColor, topLeft, Size(side, side), CornerRadius(24.dp.toPx()), style = Stroke(4.dp.toPx()))
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) {
                IconAction(DropIcons.Close, stringResource(Res.string.common_close), callbacks.onClose, tint = Color.White)
            }
            Box(Modifier.weight(1f))
            val message =
                when (status) {
                    ScanStatus.SCANNING -> stringResource(Res.string.scan_prompt)
                    ScanStatus.SUCCESS -> stringResource(Res.string.scan_success)
                    ScanStatus.EXPIRED -> stringResource(Res.string.scan_expired)
                    ScanStatus.INVALID -> stringResource(Res.string.scan_invalid)
                    ScanStatus.NO_CAMERA_PERMISSION -> stringResource(Res.string.scan_camera_permission)
                }
            Box(
                Modifier.clip(
                    RoundedCornerShape(16.dp),
                ).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(message, style = type.body, color = Color.White, textAlign = TextAlign.Center)
            }
            if (status == ScanStatus.NO_CAMERA_PERMISSION) {
                QuietButton(stringResource(Res.string.action_allow), callbacks.onAllowCamera, color = Color.White)
            }
            Box(Modifier.size(24.dp))
        }
    }
}
