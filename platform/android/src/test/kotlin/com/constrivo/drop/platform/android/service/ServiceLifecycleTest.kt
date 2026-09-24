package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.discovery.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The transfer service's lifecycle (S9, architecture §10.1): `connectedDevice` for the radio session, `dataSync` added
 * while a transfer runs, and the stop 60 s after the last transfer unless visibility needs the radio session.
 */
class ServiceLifecycleTest {
    private val cd = ForegroundKind.CONNECTED_DEVICE
    private val ds = ForegroundKind.DATA_SYNC

    private fun inputs(
        now: Long,
        activity: NodeActivity = NodeActivity(),
        onboarded: Boolean = true,
        visibility: Visibility = Visibility.HIDDEN,
        dataSyncAllowed: Boolean = true,
    ) = ServiceInputs(activity, onboarded, visibility, dataSyncAllowed, now)

    @Test
    fun `a transfer runs under both types and the stop comes 60 s after it ends`() {
        val lifecycle = ServiceLifecycle()
        val idle = lifecycle.update(inputs(0))
        assertEquals(ServicePhase.IDLE, idle.phase)
        assertEquals(emptySet(), idle.foreground)
        assertEquals(60_000, idle.stopAtElapsedMillis)

        val sending = lifecycle.update(inputs(1_000, NodeActivity(active = 1)))
        assertEquals(ServicePhase.TRANSFERRING, sending.phase)
        assertEquals(setOf(cd, ds), sending.foreground)
        assertNull(sending.stopAtElapsedMillis)

        val done = lifecycle.update(inputs(5_000, NodeActivity(lastEndedAtElapsedMillis = 5_000)))
        assertEquals(ServicePhase.IDLE, done.phase)
        assertEquals(emptySet(), done.foreground)
        assertEquals(65_000, done.stopAtElapsedMillis)

        // Nothing changed: the stop time holds, it does not slide with every evaluation.
        assertEquals(65_000, lifecycle.update(inputs(30_000, NodeActivity(lastEndedAtElapsedMillis = 5_000))).stopAtElapsedMillis)
    }

    @Test
    fun `visibility keeps the radio session under connectedDevice alone`() {
        val lifecycle = ServiceLifecycle()
        for (visibility in listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY)) {
            val decision = lifecycle.update(inputs(0, visibility = visibility))
            assertEquals(ServicePhase.RADIO_SESSION, decision.phase, "$visibility")
            assertEquals(setOf(cd), decision.foreground)
            assertNull(decision.stopAtElapsedMillis)
        }
        val transfer = lifecycle.update(inputs(10, NodeActivity(active = 2), visibility = Visibility.TRUSTED_ONLY))
        assertEquals(setOf(cd, ds), transfer.foreground)
        val after = lifecycle.update(inputs(20, NodeActivity(lastEndedAtElapsedMillis = 20), visibility = Visibility.TRUSTED_ONLY))
        assertEquals(ServicePhase.RADIO_SESSION, after.phase, "no stop while visibility needs the radio session")
        assertEquals(setOf(cd), after.foreground)
        assertNull(after.stopAtElapsedMillis)
    }

    @Test
    fun `Hidden, or onboarding not finished, needs no radio session`() {
        assertEquals(ServicePhase.IDLE, ServiceLifecycle.phaseOf(inputs(0, visibility = Visibility.HIDDEN)))
        assertEquals(ServicePhase.IDLE, ServiceLifecycle.phaseOf(inputs(0, onboarded = false, visibility = Visibility.EVERYONE)))
    }

    @Test
    fun `waiting transfers and offers keep the radio session, and the linger starts when they go`() {
        val lifecycle = ServiceLifecycle()
        assertEquals(ServicePhase.RADIO_SESSION, lifecycle.update(inputs(0, NodeActivity(parked = 1))).phase)
        assertEquals(ServicePhase.RADIO_SESSION, lifecycle.update(inputs(100, NodeActivity(pendingOffers = 1))).phase)
        val gone = lifecycle.update(inputs(2_000, NodeActivity()))
        assertEquals(ServicePhase.IDLE, gone.phase)
        assertEquals(62_000, gone.stopAtElapsedMillis, "60 s after the phone stopped needing the service")
    }

    @Test
    fun `the browser page is a transfer`() {
        val decision = ServiceLifecycle().update(inputs(0, NodeActivity(browserShare = true)))
        assertEquals(ServicePhase.TRANSFERRING, decision.phase)
        assertEquals(setOf(cd, ds), decision.foreground)
    }

    @Test
    fun `a spent dataSync limit runs transfers under connectedDevice`() {
        val decision = ServiceLifecycle().update(inputs(0, NodeActivity(active = 1), dataSyncAllowed = false))
        assertEquals(ServicePhase.TRANSFERRING, decision.phase)
        assertEquals(setOf(cd), decision.foreground)
    }

    @Test
    fun `a transfer that ended later than the phase change moves the stop`() {
        val lifecycle = ServiceLifecycle(lingerMillis = 1_000)
        lifecycle.update(inputs(0, NodeActivity(parked = 1)))
        // The parked transfer was given up at 5 s, but the evaluation only ran at 7 s: the linger counts from 7 s.
        assertEquals(8_000, lifecycle.update(inputs(7_000, NodeActivity(lastEndedAtElapsedMillis = 5_000))).stopAtElapsedMillis)
        // A later end (a send that failed early without ever being active) moves it on.
        assertEquals(10_000, lifecycle.update(inputs(8_500, NodeActivity(lastEndedAtElapsedMillis = 9_000))).stopAtElapsedMillis)
        assertFailsWith<IllegalArgumentException> { ServiceLifecycle(lingerMillis = -1) }
    }
}
