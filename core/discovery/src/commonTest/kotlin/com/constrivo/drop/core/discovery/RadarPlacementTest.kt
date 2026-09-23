package com.constrivo.drop.core.discovery

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radar placement maths, design §3.2 (F‑A2). */
class RadarPlacementTest {
    private val phone = RadarGeometry.forViewport(widthDp = 360.0, heightDp = 740.0)

    @Test
    fun stableAngleGolden() {
        // Computed independently (Python) from FNV-1a 64 + the MurmurHash3 finaliser.
        assertClose(247.09709537338296, RadarPlacement.stableAngleDegrees("t:00112233445566778899aabbccddeeff"))
        assertClose(233.16912782770913, RadarPlacement.stableAngleDegrees("e:0123456789ab"))
        assertClose(159.61195669640634, RadarPlacement.stableAngleDegrees("e:0123456789ac"))
        assertClose(337.2370014205584, RadarPlacement.stableAngleDegrees(""))
    }

    @Test
    fun stableAnglesSpreadEvenly() {
        val buckets = IntArray(8)
        for (i in 0 until 4_000) {
            val a = RadarPlacement.stableAngleDegrees("e:${Bytes.hex(i.toLong(), 12)}")
            assertTrue(a >= 0.0 && a < 360.0)
            buckets[(a / 45).toInt()]++
        }
        for (count in buckets) assertTrue(count in 400..600, "bucket counts ${buckets.toList()}")
    }

    @Test
    fun viewportGeometry() {
        assertClose(108.0, phone.inner.radiusDp)
        assertClose(198.0, phone.middle.radiusDp)
        assertClose(288.0, phone.outer.radiusDp)
        assertClose(90.0, phone.inner.arcEndDegrees)
        // Outer ring: a 64 dp bubble must stay inside the 360 dp width: asin(148 / 288).
        assertClose(asin(148.0 / 288.0) * 180 / PI, phone.outer.arcEndDegrees)
        assertClose(-phone.outer.arcEndDegrees, phone.outer.arcStartDegrees)
        assertEquals(72.0, phone.minCenterDistanceDp)
        assertEquals(12, phone.maxBubbles)
    }

    @Test
    fun singleDeviceSitsAtItsHashAngle() {
        val layout = RadarPlacement.layout(listOf(RadarItem("t:abc", Ring.MIDDLE)), phone)
        val bubble = layout.bubbles.single()
        val g = phone.middle
        assertClose(g.arcStartDegrees + RadarPlacement.stableAngleDegrees("t:abc") / 360 * g.arcWidthDegrees, bubble.angleDegrees)
        assertClose(hypot(bubble.xDp, bubble.yDp), g.radiusDp)
        assertTrue(bubble.yDp <= 0.0, "bubbles sit above the avatar")
        assertNull(layout.overflow)
    }

    @Test
    fun fA2_placementIsDeterministicAndOrderIndependent() {
        val random = Random(12)
        repeat(200) {
            val items = randomItems(random, random.nextInt(1, 13))
            val a = RadarPlacement.layout(items, phone)
            val b = RadarPlacement.layout(items.shuffled(random), phone)
            assertEquals(a, b)
        }
    }

    @Test
    fun repulsionKeepsTheMinimumCentreDistance() {
        val random = Random(34)
        repeat(300) {
            val items = randomItems(random, random.nextInt(2, 13))
            val layout = RadarPlacement.layout(items, phone)
            assertNull(layout.overflow)
            assertEquals(items.size, layout.bubbles.size)
            assertMinimumDistance(layout.bubbles, phone.minCenterDistanceDp)
            for (b in layout.bubbles) {
                val g = phone.ring(b.ring)
                assertTrue(b.angleDegrees >= g.arcStartDegrees - 1e-9 && b.angleDegrees <= g.arcEndDegrees + 1e-9, "$b outside its arc")
            }
        }
    }

    @Test
    fun collidingTargetsSpreadSymmetrically() {
        // Two keys forced onto the same ring near each other: least squares keeps their mean angle.
        val geometry = RadarGeometry(RingGeometry(108.0), RingGeometry(198.0), RingGeometry(288.0, -30.0, 30.0))
        val keys = (0 until 500).map { "k$it" }
        val targets = keys.associateWith { geometry.inner.arcStartDegrees + RadarPlacement.stableAngleDegrees(it) / 2 }
        val (k1, k2) =
            keys
                .flatMap { a -> keys.filter { it > a }.map { a to it } }
                .first { (a, b) -> abs(targets.getValue(a) - targets.getValue(b)) < 1.0 && abs(targets.getValue(a)) < 45.0 }
        val layout = RadarPlacement.layout(listOf(RadarItem(k1, Ring.INNER), RadarItem(k2, Ring.INNER)), geometry)
        val placed = layout.bubbles.associateBy { it.key }
        assertClose(targets.getValue(k1) + targets.getValue(k2), placed.getValue(k1).angleDegrees + placed.getValue(k2).angleDegrees)
        assertMinimumDistance(layout.bubbles, 72.0)
    }

    @Test
    fun moreThanTwelveDevicesCollapseTheOuterRing() {
        val items =
            (0 until 5).map { RadarItem("i$it", Ring.INNER) } +
                (0 until 5).map { RadarItem("m$it", Ring.MIDDLE) } +
                (0 until 4).map { RadarItem("o$it", Ring.OUTER) }
        val layout = RadarPlacement.layout(items, phone)
        val overflow = assertNotNull(layout.overflow)
        assertEquals(listOf("o0", "o1", "o2", "o3"), overflow.keys)
        assertEquals(4, overflow.count)
        assertClose(phone.outer.arcEndDegrees, overflow.angleDegrees)
        assertEquals(10, layout.bubbles.size)
        assertTrue(layout.bubbles.none { it.ring == Ring.OUTER })
        assertMinimumDistance(layout.bubbles, 72.0)

        val twelve = RadarPlacement.layout(items.take(12), phone)
        assertNull(twelve.overflow)
        assertEquals(12, twelve.bubbles.size)
    }

    @Test
    fun aFullRingSpillsItsLowestPriorityDevices() {
        // The inner ring of a 360 dp phone holds at most 4 bubbles 72 dp apart on its 180° arc.
        val gap = RadarPlacement.angularGap(108.0, 108.0, 72.0)
        val capacity = (180.0 / gap).toInt() + 1
        val items =
            (0 until capacity + 3).map { RadarItem("i$it", Ring.INNER, trusted = it == capacity + 2, rssiDbm = -40.0 - it) } +
                RadarItem("o0", Ring.OUTER)
        val layout = RadarPlacement.layout(items, phone)
        val overflow = assertNotNull(layout.overflow)
        val shown = layout.bubbles.map { it.key }.toSet()
        assertEquals(capacity, shown.size)
        assertTrue("i${capacity + 2}" in shown, "trusted devices stay visible")
        assertTrue("i0" in shown, "stronger signals stay visible")
        assertEquals(listOf("i${capacity - 1}", "i$capacity", "i${capacity + 1}", "o0").sorted(), overflow.keys)
        assertMinimumDistance(layout.bubbles, 72.0)
    }

    @Test
    fun ringsCloserThanTheMinimumDistanceAreSolvedJointly() {
        // Rings only 50 dp apart (a viewport under 288 dp): bubbles on neighbouring rings can collide too.
        val tiny = RadarGeometry(RingGeometry(100.0), RingGeometry(150.0), RingGeometry(200.0))
        val random = Random(8)
        repeat(2_000) {
            // Up to two bubbles per ring always fit on this geometry, whatever their order.
            val items = randomItems(random, random.nextInt(2, 7), perRing = 2)
            val layout = RadarPlacement.layout(items, tiny)
            assertEquals(layout, RadarPlacement.layout(items.reversed(), tiny))
            assertMinimumDistance(layout.bubbles, 72.0)
        }
    }

    @Test
    fun layoutsThatCannotFitAreBestEffortAndStayInTheirArcs() {
        val tiny = RadarGeometry(RingGeometry(100.0), RingGeometry(150.0), RingGeometry(200.0))
        val random = Random(9)
        repeat(300) {
            val items = randomItems(random, random.nextInt(8, 13), perRing = 5)
            val layout = RadarPlacement.layout(items, tiny)
            assertEquals(layout, RadarPlacement.layout(items.shuffled(random), tiny))
            for (b in layout.bubbles) {
                val g = tiny.ring(b.ring)
                assertTrue(b.angleDegrees >= g.arcStartDegrees - 1e-9 && b.angleDegrees <= g.arcEndDegrees + 1e-9, "$b outside its arc")
            }
        }
    }

    @Test
    fun angularGapMatchesTheChordFormula() {
        assertClose(2 * asin(36.0 / 108.0) * 180 / PI, RadarPlacement.angularGap(108.0, 108.0, 72.0))
        assertEquals(0.0, RadarPlacement.angularGap(108.0, 198.0, 72.0), "rings 90 dp apart never collide")
        assertEquals(180.0, RadarPlacement.angularGap(10.0, 10.0, 72.0))
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RadarPlacement.layout(listOf(RadarItem("a", Ring.INNER), RadarItem("a", Ring.OUTER)), phone)
        }
    }

    /** [n] items with at most [perRing] on each ring (5 stays within the phone geometry's capacities, so nothing spills). */
    private fun randomItems(
        random: Random,
        n: Int,
        perRing: Int = 5,
    ): List<RadarItem> {
        require(n <= 3 * perRing)
        val limits = Ring.entries.associateWith { perRing }
        val counts = HashMap<Ring, Int>()
        val out = ArrayList<RadarItem>()
        var i = 0
        while (out.size < n) {
            val ring = Ring.entries.random(random)
            if ((counts[ring] ?: 0) >= limits.getValue(ring)) continue
            counts[ring] = (counts[ring] ?: 0) + 1
            out +=
                RadarItem(
                    "e:${Bytes.hex(random.nextLong(0, EphemeralId.MAX_VALUE), 12)}-${i++}",
                    ring,
                    random.nextBoolean(),
                    -50.0 - random.nextInt(40),
                )
        }
        return out
    }

    private fun assertMinimumDistance(
        bubbles: List<PlacedBubble>,
        minimum: Double,
    ) {
        for (i in bubbles.indices) {
            for (j in i + 1 until bubbles.size) {
                val d = hypot(bubbles[i].xDp - bubbles[j].xDp, bubbles[i].yDp - bubbles[j].yDp)
                assertTrue(d >= minimum - 1e-6, "${bubbles[i]} and ${bubbles[j]} are $d dp apart")
            }
        }
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")
}
