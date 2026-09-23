package com.constrivo.drop.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserSessionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun sessions(
        approver: BrowserApprover,
        maxBrowsers: Int = 4,
        timeout: Long = 60_000,
    ) = BrowserSessions(approver, maxBrowsers, timeout, Random(3), scope)

    @Test
    fun aCookieOnlyWorksFromTheAddressItWasIssuedTo() {
        val s = sessions(ScriptedApprover { true })
        val first = assertNotNull(s.claim("192.168.49.10", "UA"))
        assertSame(first, s.find(first.id, "192.168.49.10"))
        assertNull(s.find(first.id, "192.168.49.11"), "a copied cookie is useless from another computer")
        assertNull(s.find("forged", "192.168.49.10"))
        assertNull(s.find(null, "192.168.49.10"))
    }

    @Test
    fun sessionIdsAreUniqueUrlSafeAnd128Bits() {
        val s = sessions(ScriptedApprover { true }, maxBrowsers = 50)
        val ids = (1..50).map { assertNotNull(s.claim("10.0.0.$it", null)).id }
        assertEquals(50, ids.toSet().size)
        ids.forEach { assertTrue(it.matches(Regex("[A-Za-z0-9_-]{22}")), it) }
    }

    @Test
    fun everyBrowserIsAskedForAndNumbered() =
        runBlocking<Unit> {
            val approver = ScriptedApprover { it.browserNumber == 1 }
            val s = sessions(approver)
            val first = assertNotNull(s.claim("10.0.0.1", "Firefox"))
            val second = assertNotNull(s.claim("10.0.0.2", null))
            withTimeout(5_000) {
                first.decision.await()
                second.decision.await()
            }
            assertEquals(BrowserState.APPROVED, first.state)
            assertEquals(BrowserState.DENIED, second.state)
            assertEquals(listOf(1, 2), approver.requests.map { it.browserNumber }.sorted())
            assertEquals("Firefox", approver.requests.first { it.browserNumber == 1 }.userAgent)
            assertEquals(listOf(BrowserState.APPROVED, BrowserState.DENIED), s.states())
        }

    @Test
    fun browsersPastTheCapAreRefusedWithoutAsking() {
        val approver = ScriptedApprover { true }
        val s = sessions(approver, maxBrowsers = 1)
        assertNotNull(s.claim("10.0.0.1", null))
        assertNull(s.claim("10.0.0.2", null))
        assertNull(s.claim("10.0.0.1", null))
        assertEquals(1, s.states().size)
    }

    @Test
    fun aThrowingApproverDenies() =
        runBlocking<Unit> {
            val s = sessions({ error("UI crashed") })
            val browser = assertNotNull(s.claim("10.0.0.1", null))
            assertEquals(BrowserState.DENIED, withTimeout(5_000) { browser.decision.await() })
            assertEquals(BrowserState.DENIED, browser.state)
            assertFalse(s.retry(browser), "a no is final")
        }

    @Test
    fun anUnansweredRequestExpiresAndCanBeAskedAgain() =
        runBlocking<Unit> {
            val asked = AtomicInteger()
            val s =
                sessions(
                    {
                        if (asked.incrementAndGet() == 1) awaitCancellation()
                        it.browserNumber == 1
                    },
                    timeout = 50,
                )
            val browser = assertNotNull(s.claim("10.0.0.1", "UA"))
            assertEquals(BrowserState.PENDING, browser.state)
            assertFalse(s.retry(browser), "nothing to retry while the phone is being asked")
            assertEquals(BrowserState.EXPIRED, withTimeout(5_000) { browser.decision.await() })
            assertEquals(BrowserState.EXPIRED, browser.state, "no answer is not a no")

            assertTrue(s.retry(browser))
            assertEquals(BrowserState.PENDING, browser.state)
            assertEquals(BrowserState.APPROVED, withTimeout(5_000) { browser.decision.await() })
            assertEquals(2, asked.get())
            assertEquals(listOf(BrowserState.APPROVED), s.states(), "the same session, no new slot")
            assertFalse(s.retry(browser))
        }

    @Test
    fun shuttingDownDeniesPendingBrowsers() =
        runBlocking<Unit> {
            val approver = ScriptedApprover()
            val s = sessions(approver)
            val browser = assertNotNull(s.claim("10.0.0.1", null))
            s.denyPending()
            assertEquals(BrowserState.DENIED, browser.state)
            // A late "yes" from the phone does not change the answer.
            withTimeout(5_000) { approver.asked.await() }
            approver.answer(1, true)
            assertEquals(BrowserState.DENIED, browser.state)
        }

    @Test
    fun longUserAgentsAreCut() =
        runBlocking<Unit> {
            val approver = ScriptedApprover { true }
            val s = sessions(approver)
            val browser = assertNotNull(s.claim("10.0.0.1", "x".repeat(10_000)))
            assertEquals(256, browser.userAgent!!.length)
            withTimeout(5_000) { browser.decision.await() }
            assertEquals(256, approver.requests.single().userAgent!!.length)
        }
}
