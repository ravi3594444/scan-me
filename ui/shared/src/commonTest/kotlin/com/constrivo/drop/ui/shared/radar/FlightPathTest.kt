package com.constrivo.drop.ui.shared.radar

import com.constrivo.drop.ui.shared.theme.DropMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightPathTest {
    private val avatar = DpPoint(180.0, 600.0)

    @Test
    fun designSection42_theArcStartsAtTheAvatarAndEndsAtTheBubble() {
        val path = FlightPath(avatar, DpPoint(80.0, 300.0))
        assertNear(avatar, path.at(0.0))
        assertNear(DpPoint(80.0, 300.0), path.at(1.0))
    }

    @Test
    fun designSection42_theArcBowsUpTheScreenOnEitherSide() {
        for (bubble in listOf(DpPoint(60.0, 320.0), DpPoint(300.0, 320.0), DpPoint(20.0, 590.0))) {
            val path = FlightPath(avatar, bubble)
            val straight = DpPoint((avatar.x + bubble.x) / 2, (avatar.y + bubble.y) / 2)
            assertTrue(path.at(0.5).y < straight.y, "the middle of the arc to $bubble is above the straight line")
        }
    }

    @Test
    fun designSection42_aBubbleStraightAboveBowsToTheRight() {
        val path = FlightPath(avatar, DpPoint(180.0, 200.0))
        assertTrue(path.at(0.5).x > avatar.x)
        // A receive walks the same pair the other way round and bows the same way.
        assertTrue(FlightPath(DpPoint(180.0, 200.0), avatar).at(0.5).x > avatar.x)
    }

    @Test
    fun designSection42_theBowIsAShareOfTheDistanceUpToACap() {
        val near = FlightPath(avatar, DpPoint(180.0, 500.0))
        // 100 dp apart: the control point sits 22 dp off the line, and the curve's middle half of that.
        assertEquals(100 * DropMotion.FLIGHT_BOW / 2, abs(near.at(0.5).x - avatar.x), 1e-9)
        val far = FlightPath(avatar, DpPoint(180.0, -2_000.0))
        assertEquals(FlightPath.MAX_BOW_DP / 2, abs(far.at(0.5).x - avatar.x), 1e-9)
    }

    @Test
    fun designSection42_aZeroLengthPathStaysPut() {
        val path = FlightPath(avatar, avatar)
        assertNear(avatar, path.at(0.5))
    }

    private fun assertNear(
        expected: DpPoint,
        actual: DpPoint,
    ) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }
}
