package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BrowserShareHint
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTab
import com.constrivo.drop.ui.shared.model.PickerTarget
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
    fun fC1_selectedPhotosStaySelectedWhenTheLibraryWindowMoves() =
        runTest {
            val fake = InMemoryDrop()
            fake.mediaItems.value = photos(120)
            val p = FilePickerPresenter(backgroundScope, fake)
            p.open("t:rohan", "Rohan")
            runCurrent()
            for (i in 100 until 120) p.toggle("p$i")
            // New photos arrive and push the oldest (selected) ones out of the library's 120-item window.
            fake.mediaItems.value = List(30) { PickedItem("new$it", "NEW_$it.jpg", 1_000, FileKind.IMAGE) } + photos(90)
            runCurrent()
            val ui = p.state.value!!
            assertEquals(20, ui.selectedCount, "selections outside the loaded list still count")
            assertEquals((100 until 120).sumOf { 3_000_000L + it }, ui.selectedBytes)
            assertEquals((100 until 120).map { "p$it" }, p.selection().items.map { it.id })
            p.toggle("p100")
            runCurrent()
            assertEquals(19, p.state.value!!.selectedCount, "and can still be deselected")
        }

    @Test
    fun fC1_theGridAsksTheLibraryForMore() =
        runTest {
            val fake = InMemoryDrop()
            val p = FilePickerPresenter(backgroundScope, fake)
            p.loadMore()
            assertEquals(0, fake.mediaPageRequests, "nothing is read while the picker is closed")
            p.open("k", null)
            p.loadMore()
            assertEquals(1, fake.mediaPageRequests)
        }

    @Test
    fun fC5_thePickerCanBeForARunningTransferOrTheBrowserPage() =
        runTest {
            val fake = InMemoryDrop()
            val p = FilePickerPresenter(backgroundScope, fake)
            p.open(PickerTarget.Transfer("tx", "Rohan"))
            runCurrent()
            assertEquals(PickerTarget.Transfer("tx", "Rohan"), p.state.value!!.target)
            assertEquals("Rohan", p.state.value!!.targetName)
            assertNull(p.targetKey, "not a bubble")
            p.open(PickerTarget.Browser)
            runCurrent()
            assertEquals(PickerTarget.Browser, p.target)
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
    /** A [MyCodeSource] on the virtual clock whose browser path the test drives. */
    private class ScriptedCodes(
        private val clocks: VirtualClocks,
        private val lifetimeMillis: Long = 300_000,
    ) : MyCodeSource {
        var issued = 0
        val share = MutableStateFlow<BrowserShareState>(BrowserShareState.Idle)
        val started = ArrayList<Int>()
        var stopped = 0

        override suspend fun current(): MyCode {
            issued++
            val now = clocks.nowMillis()
            return MyCode("drop1.payload$issued", "318204", now, now + lifetimeMillis)
        }

        override val browserShare = share

        override fun startBrowserShare(files: AttachedFiles) {
            started += files.count
            share.value = BrowserShareState.Starting
        }

        override fun stopBrowserShare() {
            stopped++
            share.value = BrowserShareState.Idle
        }
    }

    private val hint =
        BrowserShareHint("DIRECT-xy-Drop", "k7Qm2pX9", "http://drop.local:8765/t/7h2kq9x3m4pz/", "http://192.168.49.1:8765/t/7h2kq9x3m4pz/")

    private val files = AttachedFiles(listOf(PickedItem("u1", "a.pdf", 10, FileKind.DOCUMENT)))

    @Test
    fun fB5_encodesTheCodeRefreshesEveryFiveMinutesAndShowsTheRealBrowserHint() =
        runTest {
            val clocks = VirtualClocks(this)
            val source = ScriptedCodes(clocks)
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
            assertEquals(2, source.issued, "a new code is signed when the old one expires")
            assertEquals("drop1.payload2", p.state.value!!.matrix?.text)

            p.startBrowserShare(files)
            runCurrent()
            assertEquals(listOf(1), source.started, "the page serves the chosen files")
            assertTrue(p.state.value!!.browserStarting)
            source.share.value = BrowserShareState.Ready(hint)
            runCurrent()
            val ready = p.state.value!!
            assertEquals("http://drop.local:8765/t/7h2kq9x3m4pz/", ready.browserHint?.url, "N15: the full address with port and token")
            assertEquals("http://192.168.49.1:8765/t/7h2kq9x3m4pz/", ready.browserHint?.ipUrl, "design §10: the IP shown as fallback")
            assertEquals("http://192.168.49.1:8765/t/7h2kq9x3m4pz/", ready.browserMatrix?.text, "the page's own QR code")
            assertFalse(ready.showingBrowserCode)
            p.toggleBrowserCode()
            runCurrent()
            assertTrue(p.state.value!!.showingBrowserCode)

            p.close()
            runCurrent()
            assertNull(p.state.value)
            assertEquals(1, source.stopped, "closing the sheet stops the browser path it started")
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(2, source.issued, "nothing is signed while closed")
        }

    @Test
    fun n15_aFailedStartIsShownAndCanBeRetried() =
        runTest {
            val clocks = VirtualClocks(this)
            val source = ScriptedCodes(clocks)
            val p = ShowQrPresenter(backgroundScope, source, flowOf(SelfProfile("Asha", "self")), clocks)
            p.open()
            p.startBrowserShare(files)
            source.share.value = BrowserShareState.Failed
            runCurrent()
            assertTrue(p.state.value!!.browserFailed)
            assertFalse(p.state.value!!.browserStarting, "no endless \"Starting the network…\"")
            p.startBrowserShare(files)
            runCurrent()
            assertEquals(listOf(1, 1), source.started)
            assertTrue(p.state.value!!.browserStarting)
            p.close()
            // Closing a sheet that never started the page does not stop anything.
            val q = ShowQrPresenter(backgroundScope, source, flowOf(SelfProfile("Asha", "self")), clocks)
            q.open()
            q.close()
            assertEquals(1, source.stopped)
        }

    @Test
    fun anAlreadyExpiredCodeDoesNotMakeTheLoopSpin() =
        runTest {
            val clocks = VirtualClocks(this)
            // A caching source: it hands back the same code, whose end has already passed, until a second later.
            var calls = 0
            val stale = MyCode("drop1.stale", null, clocks.nowMillis() - 300_000, clocks.nowMillis() - 1)
            val source =
                object : MyCodeSource {
                    override suspend fun current(): MyCode {
                        calls++
                        return stale
                    }

                    override val browserShare = flowOf(BrowserShareState.Idle)

                    override fun startBrowserShare(files: AttachedFiles) = Unit

                    override fun stopBrowserShare() = Unit
                }
            val p = ShowQrPresenter(backgroundScope, source, flowOf(SelfProfile("Asha", "self")), clocks)
            p.open()
            runCurrent()
            assertEquals(1, calls, "one fetch, then a pause before asking again")
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(calls in 5..7, "about one fetch a second, not a tight loop ($calls)")
            assertEquals("drop1.stale", p.state.value!!.matrix?.text)
        }

    @Test
    fun theRefreshLoopStopsInTheBackground() =
        runTest {
            val clocks = VirtualClocks(this)
            val source = ScriptedCodes(clocks)
            val p = ShowQrPresenter(backgroundScope, source, flowOf(SelfProfile("Asha", "self")), clocks)
            p.open()
            runCurrent()
            assertTrue(p.isRefreshing)
            p.pause()
            advanceTimeBy(900_000)
            runCurrent()
            assertEquals(1, source.issued, "no re-signing while the app is in the background")
            assertFalse(p.isRefreshing)
            p.resume()
            runCurrent()
            assertEquals(2, source.issued, "a fresh code as soon as the app is back")
            assertTrue(p.isRefreshing)
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
