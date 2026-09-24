package com.constrivo.drop.ui.shared.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.model.Formats
import com.constrivo.drop.ui.shared.model.ShowQrUi
import com.constrivo.drop.ui.shared.qr.QrMatrix
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

@Immutable
class ShowQrCallbacks(
    val onStartBrowserShare: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * "Show my code" (F‑B5, design §4.4 with N15): a QR code of at least 240 dp, dark on white in both themes so every
 * camera reads it, with the refresh arc around it; the nickname; the six-digit fallback code; and the "computer without
 * the app" hint carrying the real network name, password and full address.
 */
@Composable
fun ShowQrSheet(
    state: ShowQrUi,
    callbacks: ShowQrCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(stringResource(Res.string.qr_title, state.nickname))
            val description = stringResource(Res.string.a11y_qr, state.nickname)
            Box(
                Modifier.size(DropDimens.qrMin + FRAME_GAP * 2).semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                RefreshArc(state.refreshFraction, Modifier.matchParentSize())
                Box(
                    Modifier.size(DropDimens.qrMin).clip(RoundedCornerShape(12.dp)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    state.matrix?.let { QrCode(it, Modifier.size(DropDimens.qrMin)) }
                }
            }
            state.fallbackCode?.let {
                Text(
                    stringResource(Res.string.qr_fallback, Formats.sas(it)),
                    style = type.body,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
            }
            val hint = state.browserHint
            when {
                hint != null -> {
                    Text(
                        stringResource(Res.string.qr_computer_hint, hint.ssid, hint.password, hint.url),
                        style = type.caption,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                state.browserStarting -> {
                    Text(stringResource(Res.string.qr_computer_starting), style = type.caption, color = colors.textMuted)
                }

                else -> {
                    QuietButton(stringResource(Res.string.qr_computer_start), callbacks.onStartBrowserShare)
                }
            }
            QuietButton(stringResource(Res.string.common_close), callbacks.onClose, color = colors.textMuted)
        }
    }
}

private val FRAME_GAP = 10.dp

/**
 * The subtle progress arc of design §4.4, drawn along a rounded frame around the code: it starts full at 12 o'clock
 * and shrinks clockwise as the code's 5 minutes pass.
 */
@Composable
private fun RefreshArc(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val r = 18.dp.toPx()
        val w = size.width - inset
        val h = size.height - inset
        val frame =
            Path().apply {
                // Start at top centre and go clockwise, so the remaining time reads like a clock hand.
                moveTo(size.width / 2, inset)
                lineTo(w - r, inset)
                quadraticTo(w, inset, w, inset + r)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(inset + r, h)
                quadraticTo(inset, h, inset, h - r)
                lineTo(inset, inset + r)
                quadraticTo(inset, inset, inset + r, inset)
                close()
            }
        drawPath(frame, colors.outline, style = Stroke(stroke))
        val measure = PathMeasure().apply { setPath(frame, false) }
        val remaining = Path()
        measure.getSegment(0f, measure.length * (1f - fraction.coerceIn(0f, 1f)), remaining, true)
        drawPath(remaining, colors.accent, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Draws [matrix] with its four-module quiet zone, modules snapped to whole pixels so the code stays crisp. */
@Composable
fun QrCode(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    dark: Color = Color.Black,
) {
    Canvas(modifier) {
        val total = matrix.size + 2 * QrMatrix.QUIET_ZONE
        val module = kotlin.math.floor(minOf(size.width, size.height) / total)
        if (module <= 0f) return@Canvas
        val drawn = module * total
        val left = (size.width - drawn) / 2 + module * QrMatrix.QUIET_ZONE
        val top = (size.height - drawn) / 2 + module * QrMatrix.QUIET_ZONE
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) drawRect(dark, Offset(left + x * module, top + y * module), Size(module, module))
            }
        }
    }
}
