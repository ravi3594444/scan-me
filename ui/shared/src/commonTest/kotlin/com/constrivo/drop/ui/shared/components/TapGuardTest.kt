package com.constrivo.drop.ui.shared.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapGuardTest {
    @Test
    fun tapsCountOnlyOnceThePromptHasBeenUpForAMoment() {
        var now = 1_000L
        val guard = TapGuard({ now })
        var taps = 0
        val action = guard.guard { taps++ }
        assertFalse(guard.allows(), "a tap during the slide-in is ignored")
        action()
        now += TapGuard.ARM_MILLIS - 1
        action()
        assertEquals(0, taps)
        now += 1
        assertTrue(guard.allows())
        action()
        assertEquals(1, taps)
    }
}
