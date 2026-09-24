package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.ThermalLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPowerPolicyTest {
    @Test
    fun `the OS hook is held once however many transfers ask, and each release counts once`() {
        var acquired = 0
        var released = 0
        val policy =
            DesktopPowerPolicy {
                acquired++
                Releasable { released++ }
            }
        assertEquals(ThermalLevel.NONE, policy.thermal.value)
        val a = policy.keepAwake("a")
        val b = policy.keepAwake("b")
        assertEquals(1, acquired)
        assertEquals(2, policy.holdCount.value)
        a.release()
        a.release()
        assertEquals(0, released, "b still holds it")
        assertEquals(1, policy.holdCount.value)
        b.release()
        assertEquals(1, released)
        assertEquals(0, policy.holdCount.value)
        policy.keepAwake("c").release()
        assertEquals(2, acquired)
        assertEquals(2, released)
    }

    @Test
    fun `a failing hook does not break keep-awake requests`() {
        val policy = DesktopPowerPolicy { error("no inhibitor") }
        val hold = policy.keepAwake("x")
        assertEquals(1, policy.holdCount.value)
        hold.release()
        assertEquals(0, policy.holdCount.value)
    }
}
