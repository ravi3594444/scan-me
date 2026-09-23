package com.constrivo.drop.web

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferActivityTest {
    private val clock = FakeMonotonicClock()
    private val activity = TransferActivity(clock, 60_000)

    @Test
    fun neverIdleBeforeTheFirstTransferFinished() {
        clock.advance(10 * 60_000)
        assertFalse(activity.isIdle())
        activity.started()
        clock.advance(10 * 60_000)
        assertFalse(activity.isIdle())
    }

    @Test
    fun idleExactlySixtySecondsAfterTheLastTransfer() {
        activity.started()
        activity.finished()
        clock.advance(59_999)
        assertFalse(activity.isIdle())
        clock.advance(1)
        assertTrue(activity.isIdle())
    }

    @Test
    fun aTransferInProgressKeepsTheServerUp() {
        activity.started()
        activity.started()
        activity.finished()
        clock.advance(120_000)
        assertFalse(activity.isIdle(), "one download is still running")
        activity.finished()
        clock.advance(59_000)
        assertFalse(activity.isIdle())
        clock.advance(1_000)
        assertTrue(activity.isIdle())
    }

    @Test
    fun aNewTransferRestartsTheTimer() {
        activity.started()
        activity.finished()
        clock.advance(50_000)
        activity.started()
        clock.advance(50_000)
        assertFalse(activity.isIdle())
        activity.finished()
        clock.advance(30_000)
        assertFalse(activity.isIdle())
        clock.advance(30_000)
        assertTrue(activity.isIdle())
    }

    @Test
    fun trackCountsFailuresAsFinished() {
        runBlocking {
            assertFailsWith<IllegalStateException> { activity.track { error("browser went away") } }
        }
        assertEquals(TransferActivity.Snapshot(0, 1, 0, 0), activity.snapshot())
        clock.advance(60_000)
        assertTrue(activity.isIdle())
    }

    @Test
    fun countsBytes() {
        activity.addSent(10)
        activity.addSent(5)
        activity.addReceived(7)
        assertEquals(TransferActivity.Snapshot(0, 0, 15, 7), activity.snapshot())
    }

    @Test
    fun unbalancedFinishIsAProgrammingError() {
        assertFailsWith<IllegalStateException> { activity.finished() }
        assertFailsWith<IllegalArgumentException> { TransferActivity(clock, 0) }
    }
}
