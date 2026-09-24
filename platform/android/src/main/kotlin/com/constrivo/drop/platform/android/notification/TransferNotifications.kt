package com.constrivo.drop.platform.android.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.constrivo.drop.platform.android.service.ReceivedItem

/** Where a tap on a notification leads; the app turns it into an activity [Intent] ([TransferNotifications]). */
sealed interface LaunchTarget {
    /** The app on its current screen. */
    data object App : LaunchTarget

    /** The app with the incoming card of [offerId] (design §5.1). */
    data class Offer(
        val offerId: String,
    ) : LaunchTarget

    /** The app's History entry of [transferId] (several files, or an installer that opens only after the warning). */
    data class Transfer(
        val transferId: String,
    ) : LaunchTarget

    /** "Open" on a completed receive of one file (F-D3). */
    data class Received(
        val item: ReceivedItem,
    ) : LaunchTarget
}

/**
 * The transfer service's notifications (design §5, F-D2, §11), one channel per purpose:
 *
 * - **progress** ([CHANNEL_PROGRESS], low importance): the ongoing foreground-service notification of the running
 *   transfers or the browser page, updated at most every 500 ms ([ProgressThrottle]) with a Cancel action for a single
 *   transfer, and an accessibility announcement at every 25 % ([ProgressAnnouncer]) through the ticker text, which is
 *   what the system hands to accessibility services;
 * - **incoming offers** ([CHANNEL_OFFERS], high importance): a heads-up with Accept and Decline while the app is in the
 *   background, never a full-screen intent, withdrawn at the end of its 30 s;
 * - **completed** ([CHANNEL_COMPLETED], default importance): the end of a transfer, with "Open" for a receive;
 * - **background visibility** ([CHANNEL_SESSION], minimum importance): the foreground-service notification while the
 *   phone stays visible to nearby devices with nothing transferring (S9's radio session).
 *
 * Nothing is posted without `POST_NOTIFICATIONS` (asked at the first transfer, §11); the foreground service still
 * runs, which Android shows in its task manager.
 *
 * @param launchIntent the app's activity intent for a [LaunchTarget].
 * @param serviceIntent an intent to the transfer service for an action with its transfer or offer id.
 */
class TransferNotifications(
    context: Context,
    private val texts: NotificationTexts,
    @param:DrawableRes private val smallIcon: Int,
    private val launchIntent: (LaunchTarget) -> Intent,
    private val serviceIntent: (action: String, id: String?) -> Intent,
    private val cancelAction: String,
    private val acceptAction: String,
    private val declineAction: String,
) {
    private val appContext = context.applicationContext
    private val manager: NotificationManager? = appContext.getSystemService(NotificationManager::class.java)

    /** Creates (or renames, after a language change) the four channels; idempotent. */
    fun createChannels() {
        val manager = manager ?: return
        val channels =
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, texts.channelProgress(), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_OFFERS, texts.channelOffers(), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_COMPLETED, texts.channelCompleted(), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SESSION, texts.channelSession(), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
        runCatching { manager.createNotificationChannels(channels) }
    }

    /**
     * Whether posting shows anything: `POST_NOTIFICATIONS` granted (a runtime permission from Android 13; Android 12
     * has none) and notifications not blocked for the app.
     */
    fun canPost(): Boolean {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager?.areNotificationsEnabled() == true
    }

    /** The ongoing notification for [content]; [announcement] is read by accessibility services (design §11). */
    fun ongoing(
        content: OngoingContent,
        announcement: String?,
    ): Notification {
        val session = content.kind == OngoingKind.SESSION
        val builder =
            NotificationCompat
                .Builder(appContext, if (session) CHANNEL_SESSION else CHANNEL_PROGRESS)
                .setSmallIcon(smallIcon)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(if (session) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(activity(LaunchTarget.App, "open"))
                .setForegroundServiceBehavior(
                    if (session) NotificationCompat.FOREGROUND_SERVICE_DEFAULT else NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
                )
        if (content.showProgress) builder.setProgress(PROGRESS_MAX, content.percent ?: 0, content.percent == null)
        if (announcement != null) builder.setTicker(announcement)
        content.cancelTransferId?.let { id ->
            builder.addAction(0, texts.cancel(), service(cancelAction, id))
        }
        return builder.build()
    }

    /** Posts (or replaces) the ongoing notification outside `startForeground` (a service not in the foreground). */
    fun postOngoing(notification: Notification) {
        if (!canPost()) return
        runCatching { manager?.notify(ONGOING_ID, notification) }
    }

    fun cancelOngoing() {
        runCatching { manager?.cancel(ONGOING_ID) }
    }

    /**
     * The heads-up of an incoming offer (design §5.1) while the app is in the background: Accept, Decline, the pairing
     * code when there is one, and a timeout at the offer's deadline ([nowElapsedMillis] on the same clock).
     */
    fun showOffer(
        content: OfferContent,
        nowElapsedMillis: Long,
    ) {
        if (!canPost()) return
        val remaining = content.deadlineElapsedMillis - nowElapsedMillis
        if (remaining <= 0) return
        val text = listOfNotNull(content.text, content.code).joinToString("\n")
        val notification =
            NotificationCompat
                .Builder(appContext, CHANNEL_OFFERS)
                .setSmallIcon(smallIcon)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setTimeoutAfter(remaining)
                .setLocalOnly(true)
                .setContentIntent(activity(LaunchTarget.Offer(content.offerId), "offer:${content.offerId}"))
                .addAction(0, texts.accept(), service(acceptAction, content.offerId))
                .addAction(0, texts.decline(), service(declineAction, content.offerId))
                .build()
        runCatching { manager?.notify(offerTag(content.offerId), OFFER_ID, notification) }
    }

    fun cancelOffer(offerId: String) {
        runCatching { manager?.cancel(offerTag(offerId), OFFER_ID) }
    }

    /** The end of a transfer (the completion channel), with "Open" for a receive. */
    fun showCompletion(content: CompletionContent) {
        if (!canPost()) return
        val target =
            when {
                !content.offersOpen -> LaunchTarget.App
                content.openItem != null -> LaunchTarget.Received(content.openItem)
                else -> LaunchTarget.Transfer(content.transferId)
            }
        val open = activity(target, "done:${content.transferId}")
        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_COMPLETED)
                .setSmallIcon(smallIcon)
                .setContentTitle(content.title)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setCategory(if (content.success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(open)
        content.text?.let { builder.setContentText(it) }
        if (content.offersOpen) builder.addAction(0, texts.open(), open)
        runCatching { manager?.notify(completionTag(content.transferId), COMPLETED_ID, builder.build()) }
    }

    private fun activity(
        target: LaunchTarget,
        identifier: String,
    ): PendingIntent {
        val intent = launchIntent(target).setIdentifier(identifier).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun service(
        action: String,
        id: String,
    ): PendingIntent {
        val intent = serviceIntent(action, id).setIdentifier("$action:$id")
        return PendingIntent.getService(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_PROGRESS: String = "transfers"
        const val CHANNEL_OFFERS: String = "incoming"
        const val CHANNEL_COMPLETED: String = "completed"
        const val CHANNEL_SESSION: String = "visibility"

        /** The ongoing (foreground-service) notification's id. */
        const val ONGOING_ID: Int = 1
        const val OFFER_ID: Int = 2
        const val COMPLETED_ID: Int = 3

        private const val PROGRESS_MAX = 100

        fun offerTag(offerId: String): String = "offer:$offerId"

        fun completionTag(transferId: String): String = "done:$transferId"
    }
}
