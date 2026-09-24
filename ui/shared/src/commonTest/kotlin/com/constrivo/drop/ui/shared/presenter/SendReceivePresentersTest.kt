package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.BrowserShareHint
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTab
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.theme.DropMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingPresenterTest {
    @Test
    fun fD1_countdownRunsThirtySecondsThenTimesOutOnce() =
        runTest {
            val fake = InMemoryDrop()
            val clocks = VirtualClocks(this)
            val p = IncomingPresenter(backgroundScope, fake.offers, fake, clocks)
            fake.offers.value = listOf(Fixtures.offer("o1", arrivedAt = 0))
            runCurrent()
            val card = assertNotNull(p.state.value)
            assertEquals(30_000, card.remainingMillis)
            assertEquals(1f, card.remainingFraction)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(20_000, p.state.value?.remainingMillis)
            advanceTimeBy(DropMotion.INCOMING_TIMEOUT_MILLIS)
            runCurrent()
            assertNull(p.state.value, "the card slides away on timeout")
            assertEquals(listOf("timeout:o1"), fake.calls)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("timeout:o1"), fake.calls, "timed out exactly once")
        }

    @Test
    fun fB3_alwaysAcceptNeedsTrustOrAConfirmedCode() =
        runTest {
            val fake = InMemoryDrop()
            val p = IncomingPresenter(backgroundScope, fake.offers, fake, VirtualClocks(this))
            fake.offers.value = listOf(Fixtures.offer("o1", arrivedAt = 0, trusted = false, sas = null))
            runCurrent()
            assertNull(p.state.value?.sas, "no code before the handshake result exists")
            fake.offers.value = listOf(Fixtures.offer("o1", arrivedAt = 0, trusted = false, sas = "042917"))
            runCurrent()
            assertEquals("042917", p.state.value?.sas, "the code shows as soon as it exists")
            assertFalse(p.state.value!!.canAlwaysAccept)
            p.onAlwaysAcceptChanged(true)
            runCurrent()
            assertFalse(p.state.value!!.alwaysAccept, "no auto-accept for an unconfirmed stranger")
            p.onSasConfirmed()
            runCurrent()
            assertTrue(p.state.value!!.sasConfirmed)
            assertTrue(p.state.value!!.canAlwaysAccept)
            p.onAlwaysAcceptChanged(true)
            runCurrent()
            p.onAccept()
            p.onAccept()
            runCurrent()
            assertEquals(listOf("pairReceive:o1", "accept:o1:true"), fake.calls, "a double tap answers once")
            assertNull(p.state.value)
        }

    @Test
    fun fD1_trustedSenderShowsNoCodeAndDeclineIsClean() =
        runTest {
            val fake = InMemoryDrop()
            val p = IncomingPresenter(backgroundScope, fake.offers, fake, VirtualClocks(this))
            fake.offers.value =
                listOf(Fixtures.offer("o2", arrivedAt = 5, trusted = true, sas = "123456"), Fixtures.offer("o1", arrivedAt = 0))
            runCurrent()
            assertEquals("o1", p.state.value?.offerId, "the oldest offer first")
            p.onDecline()
            runCurrent()
            val card = assertNotNull(p.state.value)
            assertEquals("o2", card.offerId)
            assertNull(card.sas, "a trusted sender is verified by key, no code")
            assertTrue(card.canAlwaysAccept)
            assertEquals(listOf("decline:o1"), fake.calls)
        }

    @Test
    fun designSection51_atMostSixPreviewsThenPlusN() =
        runTest {
            val fake = InMemoryDrop()
            val p = IncomingPresenter(backgroundScope, fake.offers, fake, VirtualClocks(this))
            fake.offers.value = listOf(Fixtures.offer("o1", arrivedAt = 0, count = 12))
            runCurrent()
            assertEquals(6, p.state.value?.previews?.size)
            assertEquals(6, p.state.value?.morePreviews)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilePickerPresenterTest {
    private fun photos(n: Int) = List(n) { PickedItem("p$it", "IMG_$it.jpg", 3_000_000L + it, FileKind.IMAGE) }

    @Test
    fun fC1_twoHundredPhotosShowTheCorrectCountAndTotal() =
        runTest {
            val fake = InMemoryDrop()
            fake.mediaItems.value = photos(300)
            val p = FilePickerPresenter(backgroundScope, fake)
            p.open("t:rohan", "Rohan")
            runCurrent()
            for (i in 0 until 200) p.toggle("p$i")
            runCurrent()
            val ui = assertNotNull(p.state.value)
            assertEquals(200, ui.selectedCount)
            assertEquals((0 until 200).sumOf { 3_000_000L + it }, ui.selectedBytes)
            assertTrue(ui.canSend)
            assertEquals(200, p.selection().count)
            assertEquals(1, ui.photos.first().selectionIndex)
            assertEquals(200, ui.photos[199].selectionIndex)
        }

    @Test
    fun fC1_deselectingRenumbersAndZeroDisablesSend() =
        runTest {
            val fake = InMemoryDrop()
            fake.mediaItems.value = photos(3)
            val p = FilePickerPresenter(backgroundScope, fake)
            p.open("k", null)
            runCurrent()
            p.toggle("p0")
            p.toggle("p1")
            p.toggle("p2")
            p.toggle("p0")
            runCurrent()
            assertEquals(listOf(0, 1, 2), p.state.value!!.photos.map { it.selectionIndex })
            p.toggle("p1")
            p.toggle("p2")
            p.toggle("unknown")
            runCurrent()
            assertFalse(p.state.value!!.canSend)
            assertEquals(0, p.state.value!!.selectedCount)
        }

    @Test
    fun decision8_appsTabOnlyBehindTheFlag() =
        runTest {
            val fake = InMemoryDrop()
            fake.appItems.value = listOf(PickedItem("a", "Maps.apk", 1, FileKind.APP))
            val off = FilePickerPresenter(backgroundScope, fake)
            off.open("k", null)
            off.selectTab(PickerTab.APPS)
            runCurrent()
            assertEquals(listOf(PickerTab.PHOTOS, PickerTab.FILES), off.state.value!!.tabs)
            assertEquals(PickerTab.PHOTOS, off.state.value!!.tab)
            assertTrue(off.state.value!!.apps.isEmpty())

            val on = FilePickerPresenter(backgroundScope, fake, FeatureFlags(apkSharing = true))
            on.open("k", null)
            on.selectTab(PickerTab.APPS)
            runCurrent()
            assertEquals(PickerTab.APPS, on.state.value!!.tab)
            assertEquals(1, on.state.value!!.apps.size)
        }

    @Test
    fun filesFromTheSystemPickerAreSelectedAtOnceWithoutDuplicates() =
        runTest {
            val fake = InMemoryDrop()
            val p = FilePickerPresenter(backgroundScope, fake)
            p.open("k", null)
            val doc = PickedItem("content://doc/1", "Tickets.pdf", null, FileKind.DOCUMENT)
            p.addFiles(listOf(doc, doc))
            p.addFiles(listOf(doc))
            runCurrent()
            assertEquals(1, p.state.value!!.files.size)
            assertEquals(1, p.state.value!!.selectedCount)
            assertEquals(0, p.state.value!!.selectedBytes, "an unknown size counts as zero")
            p.close()
            runCurrent()
            assertNull(p.state.value)
            assertEquals(0, p.selection().count)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShowQrPresenterTest {
    @Test
    fun fB5_encodesTheCodeRefreshesEveryFiveMinutesAndShowsTheRealBrowserHint() =
        runTest {
            val clocks = VirtualClocks(this)
            var issued = 0
            val hint = MutableStateFlow<BrowserShareHint?>(null)
            val source =
                object : MyCodeSource {
                    override suspend fun current(): MyCode {
                        issued++
                        val now = clocks.nowMillis()
                        return MyCode("drop1.payload$issued", "318204", now, now + 300_000)
                    }

                    override val browserHint = hint

                    override fun startBrowserShare() {
                        hint.value = BrowserShareHint("DIRECT-xy-Drop", "k7Qm2pX9", "http://drop.local:8765/t/7h2kq9x3m4pz/")
                    }
                }
            val p = ShowQrPresenter(backgroundScope, source, flowOf(SelfProfile("Asha", "self")), clocks)
            assertNull(p.state.value)
            p.open()
            runCurrent()
            val first = assertNotNull(p.state.value)
            assertEquals("drop1.payload1", first.matrix?.text)
            assertEquals("Asha", first.nickname)
            assertEquals(0f, first.refreshFraction)
            advanceTimeBy(150_000)
            runCurrent()
            assertEquals(0.5f, p.state.value!!.refreshFraction, 0.01f)
            advanceTimeBy(150_001)
            runCurrent()
            assertEquals(2, issued, "a new code is signed when the old one expires")
            assertEquals("drop1.payload2", p.state.value!!.matrix?.text)

            p.startBrowserShare()
            runCurrent()
            assertEquals(
                "http://drop.local:8765/t/7h2kq9x3m4pz/",
                p.state.value!!.browserHint?.url,
                "N15: the full address with port and token",
            )
            p.close()
            runCurrent()
            assertNull(p.state.value)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(2, issued, "nothing is signed while closed")
        }

    @Test
    fun aMissingCodeKeepsTheSheetAndRetries() =
        runTest {
            val clocks = VirtualClocks(this)
            val fake = InMemoryDrop(SelfProfile("Asha", "self"))
            val p = ShowQrPresenter(backgroundScope, fake, fake.profile, clocks)
            p.open()
            runCurrent()
            assertNull(p.state.value!!.matrix)
            fake.code = MyCode("drop1.late", null, clocks.nowMillis(), null)
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals("drop1.late", p.state.value!!.matrix?.text)
            assertEquals(0f, p.state.value!!.refreshFraction, "a static code has no refresh arc")
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserApprovalPresenterTest {
    @Test
    fun n15_promptsQueueInOrderAndAnswerByRequest() =
        runTest {
            val p = BrowserApprovalPresenter(backgroundScope)
            val first = async { p.ask(1, "192.168.49.23", "Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36") }
            val second = async { p.ask(2, "192.168.49.24\u0000<script>", null) }
            runCurrent()
            val shown = assertNotNull(p.state.value)
            assertEquals(1, shown.browserNumber)
            assertEquals("Chrome", shown.browser?.browser)
            assertEquals("Windows", shown.browser?.os)
            p.deny(shown.requestId + 100) // a stale id does nothing
            p.allow(shown.requestId)
            p.allow(shown.requestId) // repeated tap
            runCurrent()
            assertTrue(first.await())
            val next = assertNotNull(p.state.value)
            assertEquals(2, next.browserNumber)
            assertEquals("192.168.49.24script", next.remoteAddress, "untrusted text is sanitised")
            p.deny(next.requestId)
            assertFalse(second.await())
            runCurrent()
            assertNull(p.state.value)
        }

    @Test
    fun n15_cancelledRequestWithdrawsThePrompt() =
        runTest {
            val p = BrowserApprovalPresenter(backgroundScope)
            val pending = async { p.ask(1, "10.0.0.2", null) }
            runCurrent()
            assertNotNull(p.state.value)
            pending.cancel()
            runCurrent()
            assertNull(p.state.value)
            assertFailsWith<CancellationException> { pending.await() }
            assertFailsWith<IllegalArgumentException> { p.ask(0, "x", null) }
        }
}
