package com.constrivo.drop.ui.shared.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The "alive" states of a transfer (design §4.2, §5.1, §5.2): a glow that breathes while files move, a ring that
// circles while the other side is awaited, a burst on success, ripples around an incoming sender. Every animated value
// is read only while drawing, so they redraw without recomposing (F‑C4: 60 fps); each holds still, or is left out,
// under reduced motion (design §3.3).

/**
 * A soft glow of [diameter] behind a busy bubble or the receiving avatar, reaching out to between
 * [DropMotion.HALO_SCALE_MIN] and [DropMotion.HALO_SCALE_MAX] of its radius and back while files move; steady half-way
 * under reduced motion. Drawn past its bounds, so it sits behind a circle of the same size.
 */
@Composable
fun Halo(
    diameter: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val breath =
        if (LocalReducedMotion.current) {
            null
        } else {
            rememberInfiniteTransition().animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(DropMotion.HALO_MILLIS, easing = DropMotion.Standard), RepeatMode.Reverse),
            )
        }
    Canvas(modifier.size(diameter)) {
        val b = breath?.value ?: 0.5f
        val scale = DropMotion.HALO_SCALE_MIN + (DropMotion.HALO_SCALE_MAX - DropMotion.HALO_SCALE_MIN) * b
        val radius = size.minDimension / 2 * scale
        val edge = 1f / scale
        val strong = color.copy(alpha = color.alpha * (HALO_ALPHA - HALO_ALPHA_BREATH * b))
        drawCircle(
            Brush.radialGradient(
                0f to strong,
                edge to strong,
                1f to strong.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

private const val HALO_ALPHA = 0.45f
private const val HALO_ALPHA_BREATH = 0.2f

/**
 * The ring of a bubble whose other side is awaited ("Waiting for Dev…", connecting, reconnecting): a bright head with
 * a fading tail that circles it once every [DropMotion.SWEEP_MILLIS]; standing at 12 o'clock under reduced motion.
 */
@Composable
fun SweepRing(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = DropDimens.progressRing,
) {
    val turn =
        if (LocalReducedMotion.current) {
            null
        } else {
            rememberInfiniteTransition().animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(DropMotion.SWEEP_MILLIS, easing = LinearEasing)),
            )
        }
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val head = (turn?.value ?: 0f) - 90f + SWEEP_DEGREES
        val segment = SWEEP_DEGREES / SWEEP_SEGMENTS
        for (i in 0 until SWEEP_SEGMENTS) {
            // The first segment is the tail's faint end, the last one the bright, rounded head.
            val alpha = (i + 1f) / SWEEP_SEGMENTS
            drawArc(
                color.copy(alpha = color.alpha * alpha),
                startAngle = head - SWEEP_DEGREES + i * segment,
                sweepAngle = segment + SEGMENT_OVERLAP,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = if (i == SWEEP_SEGMENTS - 1) StrokeCap.Round else StrokeCap.Butt),
            )
        }
    }
}

private const val SWEEP_DEGREES = 110f
private const val SWEEP_SEGMENTS = 10

/** Segments overlap by a fraction of a degree, so no hairline shows between them. */
private const val SEGMENT_OVERLAP = 0.6f

/**
 * The success burst of [diameter] behind the completion tick (design §4.2): a ring and [BURST_SPARKS] sparks spread
 * out from the bubble and fade while [progress] runs from 0 to 1; nothing outside that range.
 */
@Composable
fun CompletionBurst(
    progress: () -> Float,
    diameter: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(diameter)) {
        val p = progress()
        if (p <= 0f || p >= 1f) return@Canvas
        val r0 = size.minDimension / 2
        val fade = 1f - p
        drawCircle(
            color.copy(alpha = color.alpha * BURST_RING_ALPHA * fade),
            radius = r0 * (1f + BURST_RING_GROWTH * p),
            center = center,
            style = Stroke(width = BURST_RING_WIDTH.toPx() * fade + 0.5f),
        )
        val distance = r0 * (BURST_SPARK_START + BURST_SPARK_TRAVEL * p)
        val sparkRadius = BURST_SPARK_RADIUS.toPx() * (1f - 0.6f * p)
        for (k in 0 until BURST_SPARKS) {
            val angle = (k * 360.0 / BURST_SPARKS + BURST_SPARK_OFFSET_DEGREES) * PI / 180.0
            val spark = Offset(center.x + (cos(angle) * distance).toFloat(), center.y + (sin(angle) * distance).toFloat())
            drawCircle(color.copy(alpha = color.alpha * fade), radius = sparkRadius, center = spark)
        }
    }
}

private const val BURST_SPARKS = 10
private const val BURST_SPARK_OFFSET_DEGREES = 18.0
private const val BURST_SPARK_START = 1.05f
private const val BURST_SPARK_TRAVEL = 0.8f
private val BURST_SPARK_RADIUS = 3.dp
private const val BURST_RING_ALPHA = 0.6f
private const val BURST_RING_GROWTH = 0.9f
private val BURST_RING_WIDTH = 3.dp

/**
 * Two rings that ripple out by [reach] from a circle of [diameter] (the incoming sender's avatar, design §5.1), one
 * every half [DropMotion.RIPPLE_MILLIS]; left out under reduced motion. Drawn past its bounds.
 */
@Composable
fun Ripples(
    diameter: Dp,
    reach: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (LocalReducedMotion.current) return
    val transition = rememberInfiniteTransition()
    val waves =
        List(RIPPLE_WAVES) { i ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        tween(DropMotion.RIPPLE_MILLIS, easing = DropMotion.EaseOut),
                        RepeatMode.Restart,
                        StartOffset(i * DropMotion.RIPPLE_MILLIS / RIPPLE_WAVES),
                    ),
            )
        }
    Canvas(modifier.size(diameter)) {
        val r0 = size.minDimension / 2
        val extra = reach.toPx()
        for (wave in waves) {
            val p = wave.value
            drawCircle(
                color.copy(alpha = color.alpha * RIPPLE_ALPHA * (1f - p)),
                radius = r0 + extra * p,
                center = center,
                style = Stroke(RIPPLE_WIDTH.toPx()),
            )
        }
    }
}

private const val RIPPLE_WAVES = 2
private const val RIPPLE_ALPHA = 0.5f
private val RIPPLE_WIDTH = 2.dp
