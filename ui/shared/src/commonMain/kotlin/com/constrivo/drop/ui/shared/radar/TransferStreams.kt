package com.constrivo.drop.ui.shared.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A curved path between two radar points, for the drop flyers and the transfer stream (design §4.2): a quadratic
 * Bézier whose control point bows the straight line upward on screen (to the right for a vertical line) by [bow] of
 * its length, at most [MAX_BOW_DP], so files travel in a thrown arc rather than along a ruler.
 */
internal class FlightPath(
    val from: DpPoint,
    val to: DpPoint,
    bow: Double = DropMotion.FLIGHT_BOW,
) {
    val control: DpPoint

    init {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        control =
            if (length < MIN_LENGTH_DP) {
                from
            } else {
                // The unit normal pointing up the screen (screen y grows downward), or right when the line is vertical.
                var nx = -dy / length
                var ny = dx / length
                if (ny > 0 || (ny == 0.0 && nx < 0)) {
                    nx = -nx
                    ny = -ny
                }
                val amount = minOf(length * bow, MAX_BOW_DP)
                DpPoint((from.x + to.x) / 2 + nx * amount, (from.y + to.y) / 2 + ny * amount)
            }
    }

    /** The point at [t]: [from] at 0, [to] at 1. */
    fun at(t: Double): DpPoint {
        val u = 1 - t
        return DpPoint(
            u * u * from.x + 2 * u * t * control.x + t * t * to.x,
            u * u * from.y + 2 * u * t * control.y + t * t * to.y,
        )
    }

    companion object {
        const val MAX_BOW_DP: Double = 90.0
        private const val MIN_LENGTH_DP = 1e-6
    }
}

private class Stream(
    val path: FlightPath,
    val receiving: Boolean,
    val flowing: Boolean,
)

/**
 * While files move (design §4.2, §5.2), a faint dashed arc joins the avatar and each busy bubble, and glowing dots
 * flow along it: out of the avatar for a send, into it for a receive, like a stream of files between the two phones.
 * A paused transfer and reduced motion keep the arc without the dots. Drawn under the bubbles and the avatar, which
 * hide the arc's ends; the dots' phase is read only while drawing.
 */
@Composable
internal fun TransferStreams(
    bubbles: List<BubbleUi>,
    frame: RadarFrame,
) {
    val streams =
        bubbles.mapNotNull { bubble ->
            val active = bubble.activity as? BubbleActivity.Active ?: return@mapNotNull null
            if (active.stage != TransferStage.TRANSFERRING) return@mapNotNull null
            val at = frame.bubbles[bubble.key] ?: return@mapNotNull null
            Stream(FlightPath(frame.avatarCenter, at), active.direction == Direction.RECEIVE, flowing = !active.paused)
        }
    if (streams.isEmpty()) return
    val colors = LocalDropColors.current
    val phase =
        if (LocalReducedMotion.current) {
            null
        } else {
            rememberInfiniteTransition().animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(DropMotion.STREAM_MILLIS, easing = LinearEasing)),
            )
        }
    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), GAP.toPx()))
        for (stream in streams) {
            drawPath(
                arcPath(stream.path),
                colors.accent.copy(alpha = ARC_ALPHA),
                style = Stroke(ARC_WIDTH.toPx(), cap = StrokeCap.Round, pathEffect = dash),
            )
            val p = phase?.value ?: continue
            if (!stream.flowing) continue
            for (i in 0 until DropMotion.STREAM_DOTS) {
                val along = (p + i.toFloat() / DropMotion.STREAM_DOTS) % 1f
                // 0 and 1 are inside the avatar and the bubble: the dots fade in after one and out before the other.
                val fade = sin(PI * along).toFloat()
                val t = TRIM + (1 - 2 * TRIM) * (if (stream.receiving) 1f - along else along)
                val c = stream.path.at(t).toOffset(density)
                drawCircle(colors.accent.copy(alpha = GLOW_ALPHA * fade), radius = GLOW_RADIUS.toPx() * (0.6f + 0.4f * fade), center = c)
                drawCircle(colors.accent.copy(alpha = DOT_ALPHA * fade), radius = DOT_RADIUS.toPx() * (0.7f + 0.3f * fade), center = c)
            }
        }
    }
}

private fun DrawScope.arcPath(path: FlightPath): Path {
    val from = path.at(TRIM).toOffset(density)
    val to = path.at(1 - TRIM).toOffset(density)
    // The trimmed piece of a quadratic Bézier is itself one, with this control point.
    val control = path.at(0.5).toOffset(density).let { mid -> Offset(2 * mid.x - (from.x + to.x) / 2, 2 * mid.y - (from.y + to.y) / 2) }
    return Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
}

private fun DpPoint.toOffset(density: Float) = Offset(x.toFloat() * density, y.toFloat() * density)

/** The share of the arc at each end that lies under the avatar or the bubble. */
private const val TRIM = 0.08

private const val ARC_ALPHA = 0.3f
private val ARC_WIDTH = 2.dp
private val DASH = 2.dp
private val GAP = 7.dp
private const val DOT_ALPHA = 0.9f
private val DOT_RADIUS = 4.dp
private const val GLOW_ALPHA = 0.3f
private val GLOW_RADIUS = 9.dp
