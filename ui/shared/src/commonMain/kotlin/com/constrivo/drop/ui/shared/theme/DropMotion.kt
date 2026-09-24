package com.constrivo.drop.ui.shared.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/**
 * Motion tokens of design §3.3 and §4.2, in one place so the numbers can be checked against the table.
 *
 * | Element | Animation | Duration | Easing |
 * | --- | --- | --- | --- |
 * | Rings | pulse, opacity 0.35 → 0, scale 0.9 → 1.05, staggered 800 ms | 2.4 s loop | ease-out |
 * | Bubble appear | fade 0 → 1, scale 0.8 → 1 | 200 ms | standard decelerate |
 * | Bubble leave | fade to 0, scale to 0.9 | 200 ms | accelerate |
 * | Bubble selected | scale 1 → 1.3, progress ring appears at 0% | 180 ms | overshoot 1.05 |
 * | Reposition | ease to the new position | 400 ms | standard |
 * | Drop flyer | bottom-centre → bubble, scale 1 → 0.3, 60 ms stagger | 400 ms | cubic-bezier(0.2, 0.8, 0.2, 1) |
 * | Completion | bubble 1.15 → 1.0, tick, idle after 1.5 s | 250 ms | standard decelerate |
 * | Tray item | slide in with a spring | ≈ 300 ms | damping 0.7 |
 */
object DropMotion {
    const val RING_PULSE_MILLIS: Int = 2_400
    const val RING_STAGGER_MILLIS: Int = 800
    const val RING_OPACITY_START: Float = 0.35f
    const val RING_SCALE_START: Float = 0.9f
    const val RING_SCALE_END: Float = 1.05f

    /** Static ring opacity under reduced motion (design §3.3). */
    const val RING_STATIC_OPACITY: Float = 0.20f

    /** Ring opacity factor while Bluetooth is off or a permission is missing ("ring dimmed", design §8.1). */
    const val RING_DIMMED_FACTOR: Float = 0.4f

    const val APPEAR_MILLIS: Int = 200
    const val APPEAR_SCALE_START: Float = 0.8f
    const val LEAVE_MILLIS: Int = 200
    const val LEAVE_SCALE_END: Float = 0.9f
    const val SELECT_MILLIS: Int = 180
    const val SELECTED_SCALE: Float = 1.3f
    const val SELECT_OVERSHOOT: Float = 1.05f
    const val REPOSITION_MILLIS: Int = 400

    const val FLYER_MILLIS: Int = 400
    const val FLYER_STAGGER_MILLIS: Int = 60
    const val FLYER_END_SCALE: Float = 0.3f
    const val MAX_FLYERS: Int = 8

    const val COMPLETION_POP_MILLIS: Int = 250
    const val COMPLETION_POP_SCALE: Float = 1.15f

    /** How long the completion tick stays before the bubble returns to idle (design §4.2). */
    const val COMPLETION_HOLD_MILLIS: Long = 1_500

    /**
     * Tray spring: damping ratio 0.7 settling in about 300 ms. For an under-damped spring the envelope decays as
     * `e^(−ζωt)`; settling to 2% needs `ζωt ≈ 4`, so ω = 4 / (0.7 × 0.3 s) ≈ 19 rad/s and stiffness = ω² ≈ 360.
     */
    const val TRAY_DAMPING: Float = 0.7f
    const val TRAY_STIFFNESS: Float = 360f

    /** One dot's trip along the arc between the avatar and a busy bubble, [STREAM_DOTS] dots at a time (design §4.2). */
    const val STREAM_MILLIS: Int = 1_400
    const val STREAM_DOTS: Int = 6

    /** How far the flyers' and the stream's arcs bow upward, as a share of the straight distance. */
    const val FLIGHT_BOW: Double = 0.22

    /** The glow behind a busy bubble breathes over this long, between these multiples of the bubble's radius. */
    const val HALO_MILLIS: Int = 1_600
    const val HALO_SCALE_MIN: Float = 1.25f
    const val HALO_SCALE_MAX: Float = 1.55f

    /** One turn of the waiting ring ("Waiting for Dev…", connecting, reconnecting). */
    const val SWEEP_MILLIS: Int = 1_100

    /** The success burst behind the completion tick, and the splash where the drop flyers land. */
    const val BURST_MILLIS: Int = 700
    const val SPLASH_MILLIS: Int = 500

    /** The ripples around the sender's avatar on the incoming card (design §5.1). */
    const val RIPPLE_MILLIS: Int = 1_800

    /** Incoming card countdown (design §5.1, architecture §7.8). */
    const val INCOMING_TIMEOUT_MILLIS: Long = 30_000

    /** "Standard decelerate" (Material): cubic-bezier(0, 0, 0.2, 1). */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** "Accelerate" (Material): cubic-bezier(0.4, 0, 1, 1). */
    val Accelerate: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** "Standard" (Material): cubic-bezier(0.4, 0, 0.2, 1). */
    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** CSS ease-out: cubic-bezier(0, 0, 0.58, 1). */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** The drop flyers' curve (design §4.2). */
    val Flyer: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** Rises to [SELECT_OVERSHOOT] of the travelled distance at 60% of the time, then settles at exactly 1. */
    val Overshoot: Easing = OvershootEasing(SELECT_OVERSHOOT)
}

/**
 * An easing that overshoots its target by `overshoot − 1` of the distance (for 1.05, 5%) and settles back: a
 * decelerating rise to the peak over the first 60% of the time, then an ease back to 1.
 */
class OvershootEasing(
    private val overshoot: Float,
) : Easing {
    init {
        require(overshoot >= 1f) { "overshoot must be at least 1" }
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return if (t <= PEAK) {
            val u = t / PEAK
            overshoot * (1 - (1 - u) * (1 - u))
        } else {
            val u = (t - PEAK) / (1 - PEAK)
            overshoot + (1 - overshoot) * (u * u * (3 - 2 * u))
        }
    }

    private companion object {
        const val PEAK = 0.6f
    }
}

/** Sizes of design §1, §3.1, §4.2, §11. */
object DropDimens {
    val avatar = 56.dp
    val avatarSmall = 48.dp
    val bubble = 56.dp
    val bubbleTrusted = 64.dp
    val shield = 14.dp
    val platformGlyph = 18.dp
    val icon = 24.dp
    val progressRing = 4.dp
    val flyer = 48.dp
    val minTouch = 48.dp

    /** The avatar's bottom edge sits this far above the bottom bar (design §3.1). */
    val avatarAboveBar = 96.dp

    /** Minimum centre-to-centre distance of bubbles (design §3.2). */
    val bubbleSpacing = 72.dp

    val qrMin = 240.dp
    val gutter = 16.dp
    val trayThumb = 56.dp
}
