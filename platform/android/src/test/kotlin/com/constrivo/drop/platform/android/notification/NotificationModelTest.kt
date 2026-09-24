package com.constrivo.drop.platform.android.notification

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.service.BrowserShareStatus
import com.constrivo.drop.platform.android.service.NodeDirection
import com.constrivo.drop.platform.android.service.NodeOffer
import com.constrivo.drop.platform.android.service.NodeStage
import com.constrivo.drop.platform.android.service.NodeTransfer
import com.constrivo.drop.platform.android.service.ReceivedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notifications' content (design §5, F-D2), their 500 ms throttle and the 25 % announcements (design §11). */
class NotificationModelTest {
    /** Texts that spell out their arguments, so the tests read what the model chose. */
    private object Texts : NotificationTexts {
        override fun channelProgress() = "Transfers"

        override fun channelOffers() = "Incoming"

        override fun channelCompleted() = "Completed"

        override fun channelSession() = "Visibility"

        override fun sessionTitle() = "Visible nearby"

        override fun sessionText(visibility: Visibility) = "visibility $visibility"

        override fun sending(peer: String) = "Sending to $peer"

        override fun receiving(peer: String) = "Receiving from $peer"

        override fun severalTransfers(count: Int) = "$count transfers"

        override fun connecting() = "Connecting"

        override fun waitingForAnswer(peer: String) = "Waiting for $peer to accept"

        override fun reconnecting() = "Reconnecting"

        override fun waitingForPeer(peer: String) = "Waiting for $peer"

        override fun verifying() = "Checking"

        override fun progress(
            percent: Int,
            bytesPerSecond: Long?,
            etaMillis: Long?,
        ) = "$percent% $bytesPerSecond B/s $etaMillis ms"

        override fun announce(
            title: String,
            percent: Int,
        ) = "$title, $percent percent"

        override fun offerTitle(peer: String) = "$peer wants to send"

        override fun offerSummary(
            fileCount: Int,
            totalBytes: Long,
            mimeHistogram: Map<String, Int>,
        ) = "$fileCount files, $totalBytes bytes"

        override fun offerCode(sas: String) = "code $sas"

        override fun accept() = "Accept"

        override fun decline() = "Decline"

        override fun cancel() = "Cancel"

        override fun open() = "Open"

        override fun sent(
            peer: String,
            fileCount: Int,
        ) = "Sent $fileCount to $peer"

        override fun received(
            peer: String,
            fileCount: Int,
        ) = "Received $fileCount from $peer"

        override fun failed(
            peer: String,
            reason: String?,
        ) = "Failed with $peer: $reason"

        override fun cancelled(peer: String) = "Cancelled with $peer"

        override fun declined(peer: String) = "$peer declined"

        override fun noAnswer(peer: String) = "No answer from $peer"

        override fun browserTitle() = "Page for a computer"

        override fun browserText(
            ssid: String?,
            url: String?,
        ) = if (ssid == null) "Starting" else "Join $ssid, open $url"
    }

    private fun transfer(
        id: String = "a".repeat(32),
        direction: NodeDirection = NodeDirection.SEND,
        stage: NodeStage = NodeStage.TRANSFERRING,
        done: Long = 0,
        total: Long = 1_000,
        autoAccepted: Boolean = false,
    ) = NodeTransfer(
        id = id,
        direction = direction,
        peerKey = null,
        peerDeviceId = null,
        peerName = "Dev",
        peerPlatform = DevicePlatform.PHONE,
        stage = stage,
        fileCount = 3,
        bytesTotal = total,
        bytesDone = done,
        bytesPerSecond = 2_000,
        etaMillis = 5_000,
        autoAccepted = autoAccepted,
    )

    private fun ongoing(
        vararg transfers: NodeTransfer,
        browser: BrowserShareStatus = BrowserShareStatus.Idle,
    ) = NotificationModel.ongoing(transfers.toList(), browser, Visibility.EVERYONE, Texts)

    @Test
    fun `one transfer shows its stage, percentage and a Cancel action`() {
        val content = ongoing(transfer(done = 450))
        assertEquals(OngoingKind.PROGRESS, content.kind)
        assertEquals("Sending to Dev", content.title)
        assertEquals("45% 2000 B/s 5000 ms", content.text)
        assertEquals(45, content.percent)
        assertTrue(content.showProgress)
        assertEquals("a".repeat(32), content.cancelTransferId)

        val connecting = ongoing(transfer(stage = NodeStage.CONNECTING))
        assertEquals("Connecting", connecting.text)
        assertNull(connecting.percent, "indeterminate until bytes move")
        assertEquals("Waiting for Dev to accept", ongoing(transfer(stage = NodeStage.AWAITING_ACCEPT)).text)
        assertEquals("Reconnecting", ongoing(transfer(stage = NodeStage.RECONNECTING)).text)
        val parked = ongoing(transfer(stage = NodeStage.WAITING_FOR_PEER))
        assertEquals("Waiting for Dev", parked.text)
        assertFalse(parked.showProgress)
        assertEquals("Checking", ongoing(transfer(stage = NodeStage.VERIFYING, done = 1_000)).text)
        assertEquals("Receiving from Dev", ongoing(transfer(direction = NodeDirection.RECEIVE)).title)
    }

    @Test
    fun `an offer on the card is the heads-up's, an auto-accepted one shows at once`() {
        val card = transfer(direction = NodeDirection.RECEIVE, stage = NodeStage.AWAITING_ACCEPT)
        assertEquals(OngoingKind.SESSION, ongoing(card).kind)
        val auto = card.copy(autoAccepted = true)
        assertEquals(OngoingKind.PROGRESS, ongoing(auto).kind)
        assertEquals("Connecting", ongoing(auto).text)
        assertEquals(OngoingKind.SESSION, ongoing(transfer(stage = NodeStage.DONE)).kind, "finished ones are left out")
    }

    @Test
    fun `several transfers show their combined progress without a Cancel action`() {
        val content = ongoing(transfer(id = "a".repeat(32), done = 250), transfer(id = "b".repeat(32), done = 750, total = 3_000))
        assertEquals("2 transfers", content.title)
        assertEquals(25, content.percent)
        assertEquals("25% 4000 B/s 5000 ms", content.text)
        assertNull(content.cancelTransferId)
    }

    @Test
    fun `nothing running shows the browser page or the radio session`() {
        val starting = ongoing(browser = BrowserShareStatus.Starting)
        assertEquals(OngoingKind.BROWSER, starting.kind)
        assertEquals("Starting", starting.text)
        val ready =
            ongoing(
                browser =
                    BrowserShareStatus.Ready(
                        "http://drop.local:8080/t/x/",
                        "http://192.168.49.1:8080/t/x/",
                        "DIRECT-ab-Drop-cdef",
                        "pass",
                        2,
                    ),
            )
        assertEquals("Join DIRECT-ab-Drop-cdef, open http://drop.local:8080/t/x/", ready.text)
        assertNotEquals(starting.stageKey, ready.stageKey)
        val session = ongoing()
        assertEquals(OngoingKind.SESSION, session.kind)
        assertEquals("visibility EVERYONE", session.text)
    }

    @Test
    fun `percentages are exact and bounded`() {
        assertNull(NotificationModel.percentOf(5, 0))
        assertEquals(0, NotificationModel.percentOf(-5, 100))
        assertEquals(99, NotificationModel.percentOf(999, 1_000))
        assertEquals(100, NotificationModel.percentOf(2_000, 1_000))
        assertEquals(50, NotificationModel.percentOf(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }

    @Test
    fun `the heads-up carries the summary, the pairing code and the deadline`() {
        val offer =
            NodeOffer(
                id = "c".repeat(32),
                senderDeviceId = "d".repeat(32),
                senderKey = "k",
                senderName = "Dev",
                senderPlatform = DevicePlatform.PHONE,
                trusted = false,
                sas = "123456",
                fileCount = 12,
                totalBytes = 48_000_000,
                mimeHistogram = mapOf("image/jpeg" to 12),
                previewNames = emptyList(),
                previews = emptyList(),
                arrivedAtElapsedMillis = 1_000,
            )
        val content = NotificationModel.offer(offer, Texts)
        assertEquals("Dev wants to send", content.title)
        assertEquals("12 files, 48000000 bytes", content.text)
        assertEquals("code 123456", content.code)
        assertEquals(31_000, content.deadlineElapsedMillis)
        assertNull(NotificationModel.offer(offer.copy(trusted = true, sas = null), Texts).code)
    }

    @Test
    fun `completions open the one received file, else the transfer, and never an installer`() {
        val photo = ReceivedItem("x:0", "x", "content://media/1", "photo.jpg", "image/jpeg", "Dev", executable = false)
        val apk =
            ReceivedItem("x:0", "x", "content://media/2", "app.apk", "application/vnd.android.package-archive", "Dev", executable = true)
        val received = transfer(direction = NodeDirection.RECEIVE, stage = NodeStage.DONE)
        val one = NotificationModel.completion(received, listOf(photo), Texts)!!
        assertEquals("Received 1 from Dev", one.title)
        assertEquals(photo, one.openItem)
        assertTrue(one.offersOpen)
        val installer = NotificationModel.completion(received, listOf(apk), Texts)!!
        assertNull(installer.openItem, "an installer opens only after the warning (F-D5)")
        assertTrue(installer.offersOpen)
        val many = NotificationModel.completion(received, listOf(photo, photo.copy(id = "x:1")), Texts)!!
        assertNull(many.openItem)
        assertEquals("Received 2 from Dev", many.title)

        val sent = NotificationModel.completion(transfer(stage = NodeStage.DONE), emptyList(), Texts)!!
        assertEquals("Sent 3 to Dev", sent.title)
        assertFalse(sent.offersOpen)
        assertEquals(
            "Failed with Dev: broken",
            NotificationModel.completion(transfer(stage = NodeStage.FAILED).copy(failure = "broken"), emptyList(), Texts)!!.title,
        )
        assertEquals("Dev declined", NotificationModel.completion(transfer(stage = NodeStage.DECLINED), emptyList(), Texts)!!.title)
        assertEquals("No answer from Dev", NotificationModel.completion(transfer(stage = NodeStage.NO_ANSWER), emptyList(), Texts)!!.title)
        assertNull(NotificationModel.completion(received.copy(stage = NodeStage.DECLINED), emptyList(), Texts))
        assertNull(NotificationModel.completion(received.copy(stage = NodeStage.TRANSFERRING), emptyList(), Texts))
    }

    @Test
    fun `progress posts at most every 500 ms while a new stage goes out at once`() {
        val throttle = ProgressThrottle()
        val stage = listOf<Any>("t", NodeStage.TRANSFERRING)
        assertEquals(0, throttle.admit(stage, 1_000))
        assertEquals(400, throttle.admit(stage, 1_100))
        assertEquals(100, throttle.admit(stage, 1_400))
        assertEquals(0, throttle.admit(listOf<Any>("t", NodeStage.VERIFYING), 1_450), "a stage change is not held back")
        assertEquals(450, throttle.admit(listOf<Any>("t", NodeStage.VERIFYING), 1_500))
        assertEquals(0, throttle.admit(listOf<Any>("t", NodeStage.VERIFYING), 1_950))
        throttle.reset()
        assertEquals(0, throttle.admit(listOf<Any>("t", NodeStage.VERIFYING), 1_951), "after a reset the next post goes out")
        assertFailsWith<IllegalArgumentException> { ProgressThrottle(0) }
    }

    @Test
    fun `progress is announced once at every quarter`() {
        val announcer = ProgressAnnouncer()
        val heard = listOf(0, 10, 24, 25, 26, 49, 50, 80, 99, 100, 100).mapNotNull { announcer.milestone("t", it) }
        assertEquals(listOf(25, 50, 75, 100), heard)
        assertNull(announcer.milestone("t", null))
        assertEquals(50, announcer.milestone("resumed", 60), "a resumed transfer announces the quarter it passed")
        announcer.retain(listOf("resumed"))
        assertEquals(25, announcer.milestone("t", 30), "a forgotten transfer starts over")
    }
}
