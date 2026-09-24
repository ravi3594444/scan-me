package com.constrivo.drop.platform.android.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.platform.android.notification.LaunchTarget
import com.constrivo.drop.platform.android.notification.NotificationTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The instrumented tests' Application: a [TransferServiceHost] with plain English texts. */
class TestHostApplication :
    Application(),
    TransferServiceHost {
    override val serviceLog: ServiceLog by lazy { ServiceLog(AndroidClocks.wall) }
    override val notificationTexts: NotificationTexts = PlainTexts
    override val notificationIcon: Int = android.R.drawable.stat_sys_upload
    override val onboarded: StateFlow<Boolean> = MutableStateFlow(false)
    override val defaultNickname: String = "Lab"

    override fun launchIntent(target: LaunchTarget): Intent = Intent(Intent.ACTION_MAIN).setPackage(packageName)

    private object PlainTexts : NotificationTexts {
        override fun channelProgress() = "Transfers"

        override fun channelOffers() = "Incoming"

        override fun channelCompleted() = "Completed"

        override fun channelSession() = "Visibility"

        override fun sessionTitle() = "Visible to nearby devices"

        override fun sessionText(visibility: Visibility) = visibility.name

        override fun sending(peer: String) = "Sending to $peer"

        override fun receiving(peer: String) = "Receiving from $peer"

        override fun severalTransfers(count: Int) = "$count transfers"

        override fun connecting() = "Connecting"

        override fun waitingForAnswer(peer: String) = "Waiting for $peer"

        override fun reconnecting() = "Reconnecting"

        override fun waitingForPeer(peer: String) = "Waiting for $peer"

        override fun verifying() = "Checking"

        override fun progress(
            percent: Int,
            bytesPerSecond: Long?,
            etaMillis: Long?,
        ) = "$percent%"

        override fun announce(
            title: String,
            percent: Int,
        ) = "$title, $percent percent"

        override fun offerTitle(peer: String) = "$peer wants to send"

        override fun offerSummary(
            fileCount: Int,
            totalBytes: Long,
            mimeHistogram: Map<String, Int>,
        ) = "$fileCount files"

        override fun offerCode(sas: String) = sas

        override fun accept() = "Accept"

        override fun decline() = "Decline"

        override fun cancel() = "Cancel"

        override fun open() = "Open"

        override fun sent(
            peer: String,
            fileCount: Int,
        ) = "Sent to $peer"

        override fun received(
            peer: String,
            fileCount: Int,
        ) = "Received from $peer"

        override fun failed(
            peer: String,
            reason: String?,
        ) = "Failed"

        override fun cancelled(peer: String) = "Cancelled"

        override fun declined(peer: String) = "Declined"

        override fun noAnswer(peer: String) = "No answer"

        override fun browserTitle() = "Page for a computer"

        override fun browserText(
            ssid: String?,
            url: String?,
        ) = "$ssid $url"
    }
}

/**
 * On a device (lab, WP7e): the transfer service starts its node for a bound client (database, identity, radios; the
 * radios report missing permissions as state), and stops when the client leaves.
 */
@RunWith(AndroidJUnit4::class)
class TransferServiceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aBoundClientGetsARunningNode() {
        val connected = CompletableDeferred<TransferService.LocalBinder>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    connected.complete(binder as TransferService.LocalBinder)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        assertTrue(context.bindService(TransferService.bindIntent(context), connection, Context.BIND_AUTO_CREATE))
        try {
            runBlocking {
                val binder = withTimeout(CONNECT_MILLIS) { connected.await() }
                binder.setUiVisible(true)
                binder.refreshPermissions()
                val node = withTimeout(START_MILLIS) { binder.node.filterNotNull().first() }
                assertNull(binder.startFailure.value)
                assertEquals(32, node.selfDeviceId.length)
                assertTrue(binder.log.snapshot().any { it.message.startsWith("node started") }, binder.log.export())
                println("drop-lab: " + binder.log.snapshot().joinToString(" | ") { it.message })
            }
        } finally {
            context.unbindService(connection)
        }
    }

    private companion object {
        const val CONNECT_MILLIS = 10_000L
        const val START_MILLIS = 30_000L
    }
}
