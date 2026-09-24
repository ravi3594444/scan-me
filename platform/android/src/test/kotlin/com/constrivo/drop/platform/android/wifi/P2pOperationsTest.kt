package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.P2pCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The callback-to-suspend adapters of Wi-Fi Direct (§8): answers, failure codes, timeouts, cancellation. */
class P2pOperationsTest {
    private val timeouts = P2pTimeouts(actionMillis = 1_000, queryMillis = 500, formationMillis = 4_000, pollMillis = 100)
    private val spec =
        P2pConfigMapping.forHost(
            HostRequest(LinkMode.P2P, P2pCredentials.random(JcaCryptoProvider()), requestFiveGhz = true),
        )

    @Test
    fun answersPassThrough() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val ops = P2pOperations(radio, timeouts)
            assertEquals(P2pActionResult.Success, ops.action("createGroup") { createGroup(spec, it) })
            radio.createResult = P2pActionResult.Failure(P2pFailureCodes.BUSY)
            assertEquals(P2pActionResult.Failure(P2pFailureCodes.BUSY), ops.action("createGroup") { createGroup(spec, it) })
        }

    @Test
    fun requireTypesEveryFailureCode() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val ops = P2pOperations(radio, timeouts)
            val expected =
                mapOf(
                    P2pFailureCodes.ERROR to WifiLinkError.FAILED,
                    P2pFailureCodes.P2P_UNSUPPORTED to WifiLinkError.UNSUPPORTED,
                    P2pFailureCodes.BUSY to WifiLinkError.BUSY,
                    P2pFailureCodes.NO_PERMISSION to WifiLinkError.PERMISSION_MISSING,
                    77 to WifiLinkError.FAILED,
                )
            for ((code, error) in expected) {
                radio.createResult = P2pActionResult.Failure(code)
                val e = assertFailsWith<WifiLinkException> { ops.require("createGroup") { createGroup(spec, it) } }
                assertEquals(error, e.error, "code $code")
                assertEquals(code, e.platformCode)
            }
        }

    @Test
    fun noAnswerIsATimeoutAfterItsBound() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { createResult = null }
            val ops = P2pOperations(radio, timeouts)
            val start = currentTime
            val e = assertFailsWith<WifiLinkException> { ops.action("createGroup") { createGroup(spec, it) } }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
            assertEquals(1_000, currentTime - start)
        }

    @Test
    fun secondAndLateAnswersAreIgnored() =
        runTest {
            val ops = P2pOperations(FakeP2pRadio(backgroundScope), timeouts)
            val answer =
                ops.action("twice") { done ->
                    done(P2pActionResult.Success)
                    done(P2pActionResult.Failure(P2pFailureCodes.ERROR))
                }
            assertEquals(P2pActionResult.Success, answer)

            var late: ((P2pActionResult) -> Unit)? = null
            assertFailsWith<WifiLinkException> { ops.action("late") { late = it } }
            late!!(P2pActionResult.Success)
        }

    @Test
    fun platformExceptionsAreTyped() =
        runTest {
            val ops = P2pOperations(FakeP2pRadio(backgroundScope), timeouts)
            val security = assertFailsWith<WifiLinkException> { ops.action("createGroup") { throw SecurityException("revoked") } }
            assertEquals(WifiLinkError.PERMISSION_MISSING, security.error)
            val bad = assertFailsWith<WifiLinkException> { ops.action("createGroup") { throw IllegalArgumentException("bad config") } }
            assertEquals(WifiLinkError.FAILED, bad.error)
        }

    @Test
    fun cancellingAWaitEndsItAndTheLateAnswerIsDropped() =
        runTest {
            val ops = P2pOperations(FakeP2pRadio(backgroundScope), timeouts)
            var answer: ((P2pActionResult) -> Unit)? = null
            val job = launch { ops.action("createGroup") { answer = it } }
            runCurrent()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            answer!!(P2pActionResult.Success)
        }

    @Test
    fun formationIsSeenFromTheBroadcastAndTheQueries() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { formationDelayMillis = 300 }
            val ops = P2pOperations(radio, timeouts)
            radio.createGroup(spec) {}
            val start = currentTime
            val formed = ops.awaitFormed(asOwner = true, timeoutMillis = 4_000)
            assertTrue(formed.connection.groupFormed && formed.group.isGroupOwner)
            assertEquals(5180, formed.group.frequencyMhz)
            assertEquals(300, currentTime - start)
        }

    @Test
    fun withoutBroadcastsTheQueriesArePolled() =
        runTest {
            val radio =
                FakeP2pRadio(backgroundScope).apply {
                    formationDelayMillis = 350
                    broadcasts = false
                }
            val ops = P2pOperations(radio, timeouts)
            radio.createGroup(spec) {}
            val start = currentTime
            ops.awaitFormed(asOwner = true, timeoutMillis = 4_000)
            assertTrue(currentTime - start in 350..450, "found by the next poll, ${currentTime - start} ms")
        }

    @Test
    fun aGroupInTheOtherRoleIsNotTheOneAwaited() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { presetGroup("DIRECT-ab-Drop-cdef", owner = false) }
            val ops = P2pOperations(radio, timeouts)
            assertNull(ops.probeFormed(asOwner = true))
            val e = assertFailsWith<WifiLinkException> { ops.awaitFormed(asOwner = true, timeoutMillis = 1_000) }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
        }

    @Test
    fun anEnclosingDeadlineStaysACancellation() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { answerQueries = false }
            val ops = P2pOperations(radio, timeouts)
            // The ladder's own deadline (shorter than the query bound) must cancel, not surface as a query timeout.
            assertNull(withTimeoutOrNull(300) { ops.awaitFormed(asOwner = true, timeoutMillis = 4_000) })
        }

    @Test
    fun awaitNoGroupFollowsTheRemoval() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { presetGroup("DIRECT-ab-Drop-cdef") }
            val ops = P2pOperations(radio, timeouts)
            val gone = async { ops.awaitNoGroup(2_000) }
            advanceTimeBy(250)
            radio.removeGroup {}
            assertTrue(gone.await())

            radio.presetGroup("DIRECT-ab-Drop-cdef")
            assertFalse(ops.awaitNoGroup(1_000))
        }
}
