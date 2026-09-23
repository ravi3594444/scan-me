package com.constrivo.drop.core.discovery

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * One ring of the radar in dp around the avatar centre (design §3.1). Angles are degrees clockwise from 12 o'clock
 * (straight up from the avatar); bubbles on this ring stay within `[arcStartDegrees, arcEndDegrees]`.
 */
data class RingGeometry(
    val radiusDp: Double,
    val arcStartDegrees: Double = -90.0,
    val arcEndDegrees: Double = 90.0,
) {
    init {
        require(radiusDp > 0.0) { "ring radius must be positive" }
        require(arcStartDegrees <= arcEndDegrees && arcEndDegrees - arcStartDegrees <= 360.0) { "bad arc" }
    }

    val arcWidthDegrees: Double get() = arcEndDegrees - arcStartDegrees
}

/**
 * The radar's geometry: the three rings, the minimum bubble centre distance (72 dp in the UI, design §3.2) and the
 * bubble count above which the outer ring collapses into "+N more" (12).
 */
data class RadarGeometry(
    val inner: RingGeometry,
    val middle: RingGeometry,
    val outer: RingGeometry,
    val minCenterDistanceDp: Double = DEFAULT_MIN_CENTER_DISTANCE_DP,
    val maxBubbles: Int = DEFAULT_MAX_BUBBLES,
) {
    init {
        require(minCenterDistanceDp >= 0.0) { "minimum distance must not be negative" }
        require(maxBubbles >= 1) { "maxBubbles must be at least 1" }
    }

    fun ring(ring: Ring): RingGeometry =
        when (ring) {
            Ring.INNER -> inner
            Ring.MIDDLE -> middle
            Ring.OUTER -> outer
        }

    companion object {
        const val DEFAULT_MIN_CENTER_DISTANCE_DP: Double = 72.0
        const val DEFAULT_MAX_BUBBLES: Int = 12

        /**
         * Design §3.1 for a viewport: ring radii 30%, 55% and 80% of the shorter side, each ring's arc limited so a
         * bubble of [bubbleDiameterDp] stays inside the viewport width (the avatar sits at bottom centre). Vertical
         * fit is left to the UI.
         */
        fun forViewport(
            widthDp: Double,
            heightDp: Double,
            bubbleDiameterDp: Double = 64.0,
            minCenterDistanceDp: Double = DEFAULT_MIN_CENTER_DISTANCE_DP,
        ): RadarGeometry {
            require(widthDp > 0.0 && heightDp > 0.0) { "viewport must be positive" }
            val side = min(widthDp, heightDp)
            val halfRoom = widthDp / 2 - bubbleDiameterDp / 2

            fun ring(fraction: Double): RingGeometry {
                val r = side * fraction
                val limit =
                    when {
                        halfRoom <= 0.0 -> 0.0
                        r <= halfRoom -> 90.0
                        else -> asin(halfRoom / r) * 180.0 / PI
                    }
                return RingGeometry(r, -limit, limit)
            }
            return RadarGeometry(ring(0.30), ring(0.55), ring(0.80), minCenterDistanceDp)
        }
    }
}

/** A device to place: its stable [key] (see [RadarPlacement.stableAngleDegrees]), its ring and its priority facts. */
data class RadarItem(
    val key: String,
    val ring: Ring,
    val trusted: Boolean = false,
    val rssiDbm: Double? = null,
)

/** A placed bubble. `x` grows to the right and `y` downwards (screen coordinates), both in dp from the avatar centre. */
data class PlacedBubble(
    val key: String,
    val ring: Ring,
    val angleDegrees: Double,
    val xDp: Double,
    val yDp: Double,
)

/** The "+N more" bubble on the outer ring; [keys] (sorted) are the devices listed when it is opened. */
data class OverflowBubble(
    val keys: List<String>,
    val angleDegrees: Double,
    val xDp: Double,
    val yDp: Double,
) {
    val count: Int get() = keys.size
}

data class RadarLayout(
    val bubbles: List<PlacedBubble>,
    val overflow: OverflowBubble?,
)

/**
 * Radar placement maths of design §3.2, as pure deterministic functions: the same input always gives the same
 * output, independent of input order.
 *
 * 1. Each device gets a stable angle from a hash of its key, mapped proportionally onto its ring's arc, so a
 *    trusted device (keyed by its device ID) keeps its position between sessions.
 * 2. More than [RadarGeometry.maxBubbles] devices: the outer ring collapses into one "+N more" bubble at the end of
 *    the outer arc. A ring that cannot physically hold its devices at the minimum distance also spills its
 *    lowest-priority devices (untrusted first, then weaker signal, then key order) into that bubble.
 * 3. Repulsion: on each ring bubbles are spread along the arc to at least [RadarGeometry.minCenterDistanceDp]
 *    centre to centre, moving them as little as possible (least squares, solved exactly by pool-adjacent-violators)
 *    while keeping their angular order. When rings are closer together than the minimum distance (viewports whose
 *    shorter side is under about 288 dp), bubbles on neighbouring rings are separated too, by solving the same
 *    least-squares problem over all bubbles at once; only a layout that cannot fit is best effort.
 *
 * mDNS-only devices have no RSSI and are passed in on [Ring.MIDDLE] (design §3.2).
 */
object RadarPlacement {
    private const val OVERFLOW_KEY = "\u0000overflow"
    private const val MAX_SWEEPS = 5_000
    private const val SOLVER_TOLERANCE = 1e-9
    private const val EPSILON = 1e-9

    /**
     * A stable angle in `[0, 360)` for [key]: FNV-1a 64 over its UTF-8 bytes, avalanched with the MurmurHash3
     * finaliser, top 53 bits scaled. Identical on every platform.
     */
    fun stableAngleDegrees(key: String): Double {
        var h = 0xcbf29ce484222325uL.toLong()
        for (b in key.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        h = h xor (h ushr 33)
        h *= 0xff51afd7ed558ccduL.toLong()
        h = h xor (h ushr 33)
        h *= 0xc4ceb9fe1a85ec53uL.toLong()
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble() * 360.0
    }

    /**
     * Places [items] (distinct keys) on [geometry].
     *
     * @throws IllegalArgumentException when two items share a key.
     */
    fun layout(
        items: List<RadarItem>,
        geometry: RadarGeometry,
    ): RadarLayout {
        require(items.map { it.key }.toSet().size == items.size) { "radar items must have distinct keys" }
        val byRing = Ring.entries.associateWith { ring -> items.filter { it.ring == ring }.sortedBy { it.key } }
        val kept = HashMap<Ring, List<RadarItem>>()
        val spilled = ArrayList<RadarItem>()
        var collapse = items.size > geometry.maxBubbles
        for (ring in listOf(Ring.INNER, Ring.MIDDLE)) {
            val members = byRing.getValue(ring)
            val capacity = capacity(geometry, ring)
            if (members.size > capacity) {
                val ranked = members.sortedWith(PRIORITY)
                kept[ring] = ranked.take(capacity)
                spilled += ranked.drop(capacity)
                collapse = true
            } else {
                kept[ring] = members
            }
        }
        val outer = byRing.getValue(Ring.OUTER)
        if (!collapse && outer.size > capacity(geometry, Ring.OUTER)) collapse = true
        if (collapse) spilled += outer
        kept[Ring.OUTER] = if (collapse) emptyList() else outer

        // Angles to solve, per ring: key → target angle.
        val slots = ArrayList<Slot>()
        for (ring in Ring.entries) {
            val g = geometry.ring(ring)
            for (item in kept.getValue(ring)) {
                slots += Slot(item.key, ring, g.arcStartDegrees + stableAngleDegrees(item.key) / 360.0 * g.arcWidthDegrees)
            }
        }
        if (spilled.isNotEmpty()) slots += Slot(OVERFLOW_KEY, Ring.OUTER, geometry.outer.arcEndDegrees)
        for (ring in Ring.entries) projectRing(slots, ring, geometry)
        solveAcrossRings(slots, geometry)

        val bubbles =
            slots
                .filter { it.key != OVERFLOW_KEY }
                .sortedBy { it.key }
                .map { s -> position(s, geometry).let { (x, y) -> PlacedBubble(s.key, s.ring, s.angle, x, y) } }
        val overflow =
            slots.firstOrNull { it.key == OVERFLOW_KEY }?.let { s ->
                val (x, y) = position(s, geometry)
                OverflowBubble(spilled.map { it.key }.sorted(), s.angle, x, y)
            }
        return RadarLayout(bubbles, overflow)
    }

    /** Minimum angular separation, in degrees, for two bubbles on radii [r1] and [r2] to be [distance] apart. */
    internal fun angularGap(
        r1: Double,
        r2: Double,
        distance: Double,
    ): Double {
        if (distance <= 0.0) return 0.0
        val c = (r1 * r1 + r2 * r2 - distance * distance) / (2 * r1 * r2)
        return when {
            c >= 1.0 -> 0.0
            c <= -1.0 -> 180.0
            else -> acos(c) * 180.0 / PI
        }
    }

    private class Slot(
        val key: String,
        val ring: Ring,
        val target: Double,
    ) {
        var angle: Double = target
    }

    private val PRIORITY =
        compareByDescending<RadarItem> { it.trusted }
            .thenByDescending { it.rssiDbm ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.key }

    private fun capacity(
        geometry: RadarGeometry,
        ring: Ring,
    ): Int {
        val g = geometry.ring(ring)
        if (geometry.minCenterDistanceDp > 2 * g.radiusDp) return 1
        val gap = angularGap(g.radiusDp, g.radiusDp, geometry.minCenterDistanceDp)
        if (gap <= 0.0) return Int.MAX_VALUE
        // A full circle has no ends: n bubbles need n gaps instead of n − 1.
        val n = floor((g.arcWidthDegrees + EPSILON) / gap).toInt()
        return if (isFullCircle(g)) maxOf(1, n) else n + 1
    }

    private fun isFullCircle(g: RingGeometry): Boolean = g.arcWidthDegrees >= 360.0 - EPSILON

    /** Usable end of the arc: on a full circle one gap is kept free at the seam so the ends do not touch. */
    private fun arcEnd(
        g: RingGeometry,
        gap: Double,
    ): Double = if (isFullCircle(g)) maxOf(g.arcStartDegrees, g.arcEndDegrees - gap) else g.arcEndDegrees

    /** Least-squares placement of one ring's slots with the minimum gap, keeping their order and staying in the arc. */
    private fun projectRing(
        slots: List<Slot>,
        ring: Ring,
        geometry: RadarGeometry,
    ) {
        val members = slots.filter { it.ring == ring }.sortedWith(compareBy<Slot> { it.angle }.thenBy { it.key })
        if (members.isEmpty()) return
        val g = geometry.ring(ring)
        val gap = angularGap(g.radiusDp, g.radiusDp, geometry.minCenterDistanceDp)
        val n = members.size
        val shifted = DoubleArray(n) { members[it].angle - it * gap }
        val fitted = isotonic(shifted)
        val lo = g.arcStartDegrees
        val end = arcEnd(g, gap)
        val hi = maxOf(lo, end - (n - 1) * gap)
        for (i in 0 until n) members[i].angle = minOf(end, fitted[i].coerceIn(lo, hi) + i * gap)
    }

    /** Pool-adjacent-violators: the non-decreasing sequence closest to [values] in least squares. */
    private fun isotonic(values: DoubleArray): DoubleArray {
        val means = DoubleArray(values.size)
        val sizes = IntArray(values.size)
        var top = 0
        for (v in values) {
            var mean = v
            var size = 1
            while (top > 0 && means[top - 1] > mean) {
                top--
                mean = (means[top] * sizes[top] + mean * size) / (sizes[top] + size)
                size += sizes[top]
            }
            means[top] = mean
            sizes[top] = size
            top++
        }
        val out = DoubleArray(values.size)
        var k = 0
        for (b in 0 until top) repeat(sizes[b]) { out[k++] = means[b] }
        return out
    }

    /**
     * When two rings are closer than the minimum distance, bubbles on different rings can collide too. Then the whole
     * layout is solved at once, keeping the targets' angular order: least-squares displacement from the targets
     * subject to every pairwise separation and the arc bounds, by Hildreth's dual coordinate ascent (which converges
     * to the optimum of this convex problem), followed by one exact repair pass that removes the solver's residual.
     * Whether the layout fits at all is known beforehand from the earliest and latest feasible angle of each bubble
     * (longest paths through the ordered constraints); a layout that cannot fit (only on tiny viewports) skips the
     * solver and gets the repair pass alone, clamped to the arcs: best effort.
     */
    private fun solveAcrossRings(
        slots: List<Slot>,
        geometry: RadarGeometry,
    ) {
        val order = slots.sortedWith(compareBy<Slot> { it.target }.thenBy { it.key })
        val n = order.size
        val pairI = ArrayList<Int>()
        val pairJ = ArrayList<Int>()
        val pairGap = ArrayList<Double>()
        var crossRing = false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = order[i]
                val b = order[j]
                // Same-ring spacing only needs neighbours on that ring; cross-ring pairs all need checking.
                if (a.ring == b.ring && (i + 1 until j).any { order[it].ring == a.ring }) continue
                val g = angularGap(geometry.ring(a.ring).radiusDp, geometry.ring(b.ring).radiusDp, geometry.minCenterDistanceDp)
                if (g <= 0.0) continue
                if (a.ring != b.ring) crossRing = true
                pairI += i
                pairJ += j
                pairGap += g
            }
        }
        if (!crossRing) return
        val lower = DoubleArray(n)
        val upper = DoubleArray(n)
        for (i in 0 until n) {
            val g = geometry.ring(order[i].ring)
            lower[i] = g.arcStartDegrees
            upper[i] = maxOf(lower[i], arcEnd(g, angularGap(g.radiusDp, g.radiusDp, geometry.minCenterDistanceDp)))
        }
        // Constraints are sorted by (i, j), so one forward and one backward pass give the longest paths.
        val earliest = lower.copyOf()
        for (k in pairGap.indices) earliest[pairJ[k]] = maxOf(earliest[pairJ[k]], earliest[pairI[k]] + pairGap[k])
        val latest = upper.copyOf()
        for (k in pairGap.indices.reversed()) latest[pairI[k]] = minOf(latest[pairI[k]], latest[pairJ[k]] - pairGap[k])
        val fits = (0 until n).all { earliest[it] <= latest[it] + EPSILON }
        val x = DoubleArray(n) { if (fits) order[it].target else order[it].angle }
        if (fits) hildreth(x, lower, upper, pairI, pairJ, pairGap)
        // Repair: push each bubble just past its predecessors, never beyond its latest feasible angle.
        for (j in 0 until n) x[j] = minOf(latest[j], maxOf(x[j], earliest[j]))
        for (k in pairGap.indices) {
            val j = pairJ[k]
            x[j] = minOf(latest[j], maxOf(x[j], x[pairI[k]] + pairGap[k]))
        }
        for (i in 0 until n) order[i].angle = x[i].coerceIn(lower[i], upper[i])
    }

    /** Hildreth's method for `min ½‖x − target‖²` subject to `x[j] − x[i] ≥ gap` and `lower ≤ x ≤ upper`, in place. */
    private fun hildreth(
        x: DoubleArray,
        lower: DoubleArray,
        upper: DoubleArray,
        pairI: List<Int>,
        pairJ: List<Int>,
        pairGap: List<Double>,
    ) {
        val n = x.size
        val pairDual = DoubleArray(pairGap.size)
        val lowerDual = DoubleArray(n)
        val upperDual = DoubleArray(n)
        for (sweep in 0 until MAX_SWEEPS) {
            var worst = 0.0
            for (k in pairGap.indices) {
                val i = pairI[k]
                val j = pairJ[k]
                val slack = x[j] - x[i] - pairGap[k]
                if (-slack > worst) worst = -slack
                val updated = maxOf(0.0, pairDual[k] - slack / 2)
                val d = updated - pairDual[k]
                if (d != 0.0) {
                    pairDual[k] = updated
                    x[j] += d
                    x[i] -= d
                }
            }
            for (i in 0 until n) {
                val low = x[i] - lower[i]
                if (-low > worst) worst = -low
                val l = maxOf(0.0, lowerDual[i] - low)
                x[i] += l - lowerDual[i]
                lowerDual[i] = l
                val high = upper[i] - x[i]
                if (-high > worst) worst = -high
                val u = maxOf(0.0, upperDual[i] - high)
                x[i] -= u - upperDual[i]
                upperDual[i] = u
            }
            if (worst <= SOLVER_TOLERANCE) return
        }
    }

    private fun position(
        slot: Slot,
        geometry: RadarGeometry,
    ): Pair<Double, Double> {
        val r = geometry.ring(slot.ring).radiusDp
        val rad = slot.angle * PI / 180.0
        return Pair(r * sin(rad), -r * cos(rad))
    }
}
