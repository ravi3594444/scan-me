package com.constrivo.drop.ui.shared.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DropColorsTest {
    @Test
    fun ringsFadeOutwardsAndBrightenInDarkMode() {
        val light = (0..2).map { DropColors.Light.ring(it, dark = false).alpha }
        listOf(0.35f, 0.20f, 0.10f).zip(light).forEach { (want, got) ->
            assertTrue(abs(want - got) < 0.01f, "ring alpha $got, want $want")
        }
        val dark = (0..2).map { DropColors.Dark.ring(it, dark = true).alpha }
        dark.zip(light).forEach { (d, l) -> assertTrue(d > l, "dark ring $d should be brighter than light ring $l") }
    }
}
