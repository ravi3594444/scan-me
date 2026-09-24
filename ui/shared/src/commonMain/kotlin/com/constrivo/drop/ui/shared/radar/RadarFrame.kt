package com.constrivo.drop.ui.shared.radar

import androidx.compose.runtime.Immutable
import com.constrivo.drop.core.discovery.RadarGeometry
import com.constrivo.drop.core.discovery.RadarItem
import com.constrivo.drop.core.discovery.RadarPlacement
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.ui.shared.model.BubbleUi
import kotlin.math.max
import kotlin.math.min

/** A point in dp from the radar area's top-left corner. */
@Immutable
data class DpPoint(
    val x: Double,
    val y: Double,
)

/**
 * Where everything on the radar goes for one viewport (design §3.1, §3.2): the avatar at bottom-centre, the three ring
 * radii (30%, 55%, 80% of the shorter side, reduced when the area is too short for the outer ring), and each bubble's
 * centre from core/discovery [RadarPlacement] (hash-derived angle, 72 dp repulsion, "+N more" above 12 devices).
 */
@Immutable
data class RadarFrame(
    val avatarCenter: DpPoint,
    val ringRadii: List<Double>,
    val bubbles: Map<String, DpPoint>,
    val overflowKeys: List<String>,
    val overflowCenter: DpPoint?,
) {
    companion object {
        /** Room kept above the outer ring's bubbles for the title bar and the share banner. */
        const val DEFAULT_TOP_RESERVE_DP: Double = 72.0

        /** The largest bubble drawn (trusted, 64 dp) plus its 12 sp name label, for the vertical fit. */
        private const val BUBBLE_EXTENT_DP: Double = 64.0

        /**
         * Lays out [bubbles] in an area of [widthDp] × [heightDp] whose avatar centre sits [avatarBottomInsetDp] above
         * the area's bottom edge (design §3.1: 96 dp above the bar plus half the 56 dp avatar = 124 dp).
         *
         * Busy bubbles (sending or receiving) are never folded into "+N more": they rank before everyone else, and when
         * the outer ring collapses (more than 12 devices) a busy bubble on it is shown on the middle ring instead. Among
         * the rest, placement keeps core/discovery's order (trusted, then stronger signal).
         */
        fun compute(
            bubbles: List<BubbleUi>,
            widthDp: Double,
            heightDp: Double,
            avatarBottomInsetDp: Double = 124.0,
            topReserveDp: Double = DEFAULT_TOP_RESERVE_DP,
        ): RadarFrame {
            val width = max(widthDp, 1.0)
            val height = max(heightDp, 1.0)
            val center = DpPoint(width / 2, max(height - avatarBottomInsetDp, height / 2))
            // The outer ring (80%) plus a bubble must stay below the top reserve: cap the "shorter side" accordingly.
            val verticalRoom = center.y - topReserveDp - BUBBLE_EXTENT_DP / 2
            val side = max(min(min(width, height), verticalRoom / 0.8), 90.0)
            val geometry = RadarGeometry.forViewport(width, side)
            val distinct = bubbles.distinctBy { it.key }
            val collapsing = distinct.size > RadarGeometry.DEFAULT_MAX_BUBBLES
            val items =
                distinct
                    .map { b ->
                        RadarItem(
                            key = b.key,
                            ring = if (b.busy && collapsing && b.ring == Ring.OUTER) Ring.MIDDLE else b.ring,
                            trusted = b.trusted || b.busy,
                            rssiDbm = if (b.busy) Double.MAX_VALUE else b.rssiDbm,
                        )
                    }
            val layout = RadarPlacement.layout(items, geometry)
            val positions = layout.bubbles.associate { it.key to DpPoint(center.x + it.xDp, center.y + it.yDp) }
            val overflow = layout.overflow
            return RadarFrame(
                avatarCenter = center,
                ringRadii = listOf(geometry.inner.radiusDp, geometry.middle.radiusDp, geometry.outer.radiusDp),
                bubbles = positions,
                overflowKeys = overflow?.keys ?: emptyList(),
                overflowCenter = overflow?.let { DpPoint(center.x + it.xDp, center.y + it.yDp) },
            )
        }
    }
}
