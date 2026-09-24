package com.constrivo.drop.platform.android.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The advertising-set bookkeeping of [AndroidBeaconRadio] (N4, F-A5): whatever happens to a start (success, failure,
 * time-out, a cancelled caller, a concurrent stop), no set is left on air untracked.
 */
class AdvertisingSetsTest {
    private val timeout = 2_000L

    /**
     * An advertising set as Android's stack treats it: [answer] delivers `onAdvertisingSetStarted`; a set stopped before
     * the stack answered is freed as soon as it starts, so it never reaches the air.
     */
    private class FakeSet(
        private val refuse: Int? = null,
    ) : PlatformAdvertisingSet {
        private var onStarted: ((Int) -> Unit)? = null
        var onEnded: (() -> Unit)? = null
        var starts = 0
        var stops = 0
        private var startedByStack = false

        val onAir: Boolean get() = startedByStack && stops == 0

        override fun start(
            onStarted: (status: Int) -> Unit,
            onEnded: () -> Unit,
        ): Int? {
            starts++
            this.onStarted = onStarted
            this.onEnded = onEnded
            return refuse
        }

        /** The stack's late answer. */
        fun answer(status: Int) {
            if (status == AdvertisingSets.ADVERTISE_SUCCESS) startedByStack = true
            onStarted?.invoke(status)
        }

        override fun stop() {
            stops++
        }
    }

    private fun TestScope.startInBackground(
        sets: AdvertisingSets,
        set: FakeSet,
    ) = backgroundScope.async { sets.start(set) }.also { runCurrent() }

    @Test
    fun aStartedSetIsTrackedUntilStopped() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val set = FakeSet()
            val starting = startInBackground(sets, set)
            set.answer(AdvertisingSets.ADVERTISE_SUCCESS)
            assertEquals(AdvertisingSets.ADVERTISE_SUCCESS, starting.await())
            assertTrue(set.onAir && sets.isLive(set))
            sets.stopAll()
            assertFalse(set.onAir)
            assertEquals(0, sets.count)
        }

    @Test
    fun aCallerCancelledWhileTheStackStartsTheSetLeavesNothingOnAir() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val set = FakeSet()
            // The discovery controller's collectLatest cancels a start whenever the plan or the beacon state changes.
            val starting = startInBackground(sets, set)
            assertEquals(1, sets.count, "tracked before the stack answered")
            starting.cancelAndJoin()
            assertEquals(1, set.stops)
            assertEquals(0, sets.count)
            // The stack starts it a moment later: a set stopped while it registered never reaches the air.
            set.answer(AdvertisingSets.ADVERTISE_SUCCESS)
            assertFalse(set.onAir)
            // And a later stopAll() (Hidden, the next epoch, close) has nothing left to miss.
            sets.stopAll()
            assertEquals(1, set.stops)
        }

    @Test
    fun stopAllReachesASetThatIsStillStarting() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val set = FakeSet()
            val starting = startInBackground(sets, set)
            sets.stopAll()
            assertEquals(1, set.stops)
            set.answer(AdvertisingSets.ADVERTISE_SUCCESS)
            assertEquals(AdvertisingStatus.CODE_NOT_AVAILABLE, starting.await())
            assertFalse(set.onAir)
            assertEquals(0, sets.count)
        }

    @Test
    fun aTimedOutStartIsStoppedSoALateStartStaysOffTheAir() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val set = FakeSet()
            val starting = startInBackground(sets, set)
            advanceTimeBy(timeout + 1)
            assertEquals(AdvertisingStatus.CODE_TIMEOUT, starting.await())
            assertEquals(1, set.stops)
            set.answer(AdvertisingSets.ADVERTISE_SUCCESS)
            assertFalse(set.onAir)
            assertEquals(0, sets.count)
        }

    @Test
    fun failuresAreReportedAndLeaveNothingTracked() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val failing = FakeSet()
            val starting = startInBackground(sets, failing)
            failing.answer(AdvertisingStatus.ADVERTISE_FAILED_DATA_TOO_LARGE)
            assertEquals(AdvertisingStatus.ADVERTISE_FAILED_DATA_TOO_LARGE, starting.await())
            val refused = FakeSet(refuse = AdvertisingStatus.CODE_PERMISSION)
            assertEquals(AdvertisingStatus.CODE_PERMISSION, sets.start(refused))
            assertEquals(0, sets.count)
            assertFalse(failing.onAir || refused.onAir)
        }

    @Test
    fun theEndOfASetReachesItsOwner() =
        runTest {
            val sets = AdvertisingSets(timeout)
            val set = FakeSet()
            var ended = 0
            val starting = backgroundScope.async { sets.start(set) { ended++ } }
            runCurrent()
            set.answer(AdvertisingSets.ADVERTISE_SUCCESS)
            starting.await()
            set.onEnded?.invoke()
            assertEquals(1, ended)
        }
}
