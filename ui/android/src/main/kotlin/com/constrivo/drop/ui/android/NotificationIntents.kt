package com.constrivo.drop.ui.android

import android.content.Context
import android.content.Intent
import com.constrivo.drop.platform.android.notification.LaunchTarget

/**
 * The activity intents behind the transfer service's notifications (WP7ef; design §5): every tap opens [MainActivity],
 * which then shows the incoming card, the transfer's History entry, or opens the received file (never an installer,
 * F-D5: those open only through the in-app warning).
 */
internal object NotificationIntents {
    const val ACTION_SHOW_OFFER: String = "com.constrivo.drop.action.SHOW_OFFER"
    const val ACTION_SHOW_TRANSFER: String = "com.constrivo.drop.action.SHOW_TRANSFER"
    const val ACTION_OPEN_RECEIVED: String = "com.constrivo.drop.action.OPEN_RECEIVED"
    const val EXTRA_ID: String = "com.constrivo.drop.extra.NOTIFICATION_ID"
    const val EXTRA_URI: String = "com.constrivo.drop.extra.URI"
    const val EXTRA_MIME: String = "com.constrivo.drop.extra.MIME"

    fun launch(
        context: Context,
        target: LaunchTarget,
    ): Intent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return when (target) {
            LaunchTarget.App -> {
                intent.setAction(Intent.ACTION_MAIN)
            }

            is LaunchTarget.Offer -> {
                intent.setAction(ACTION_SHOW_OFFER).putExtra(EXTRA_ID, target.offerId)
            }

            is LaunchTarget.Transfer -> {
                intent.setAction(ACTION_SHOW_TRANSFER).putExtra(EXTRA_ID, target.transferId)
            }

            is LaunchTarget.Received -> {
                intent
                    .setAction(ACTION_OPEN_RECEIVED)
                    .putExtra(EXTRA_ID, target.item.id)
                    .putExtra(EXTRA_URI, target.item.uri)
                    .putExtra(EXTRA_MIME, target.item.mimeType)
            }
        }
    }

    /** Whether [intent] came from one of these notifications. */
    fun isNotification(intent: Intent): Boolean = intent.action in setOf(ACTION_SHOW_OFFER, ACTION_SHOW_TRANSFER, ACTION_OPEN_RECEIVED)
}
