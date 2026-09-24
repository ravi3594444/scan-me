package com.constrivo.drop.ui.android

import android.content.Context
import android.text.format.Formatter
import com.constrivo.drop.R
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.notification.NotificationTexts
import java.util.Locale

/**
 * The notifications' strings (design §5, §8.3, §11) from this app's resources, English and Hindi (decision 9), in the
 * language of Settings → Language: [context] is the app's context on Android 13+ (the per-app locale applies to it)
 * and one configured with the in-app choice on Android 12 ([AppLocales]).
 */
internal class AndroidNotificationTexts(
    private val context: () -> Context,
) : NotificationTexts {
    private fun string(
        id: Int,
        vararg args: Any,
    ): String = context().getString(id, *args)

    private fun plural(
        id: Int,
        count: Int,
    ): String = context().resources.getQuantityString(id, count, count)

    private val locale: Locale get() = context().resources.configuration.locales[0] ?: Locale.getDefault()

    override fun channelProgress() = string(R.string.notification_channel_progress)

    override fun channelOffers() = string(R.string.notification_channel_offers)

    override fun channelCompleted() = string(R.string.notification_channel_completed)

    override fun channelSession() = string(R.string.notification_channel_visibility)

    override fun sessionTitle() = string(R.string.notification_session_title)

    override fun sessionText(visibility: Visibility) =
        when (visibility) {
            Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES -> string(R.string.notification_session_everyone)
            Visibility.TRUSTED_ONLY -> string(R.string.notification_session_trusted)
            Visibility.HIDDEN -> string(R.string.notification_session_hidden)
        }

    override fun sending(peer: String) = string(R.string.notification_sending, peer)

    override fun receiving(peer: String) = string(R.string.notification_receiving, peer)

    override fun severalTransfers(count: Int) = plural(R.plurals.notification_transfers, count)

    override fun connecting() = string(R.string.notification_connecting)

    override fun waitingForAnswer(peer: String) = string(R.string.notification_waiting_answer, peer)

    override fun reconnecting() = string(R.string.notification_reconnecting)

    override fun waitingForPeer(peer: String) = string(R.string.notification_waiting_peer, peer)

    override fun verifying() = string(R.string.notification_verifying)

    override fun progress(
        percent: Int,
        bytesPerSecond: Long?,
        etaMillis: Long?,
    ): String {
        val parts = ArrayList<String>(3)
        parts += string(R.string.notification_percent, percent)
        bytesPerSecond?.takeIf { it > 0 }?.let { bps ->
            parts += string(R.string.notification_speed, String.format(locale, "%.1f", bps / BYTES_PER_MB))
        }
        etaMillis?.takeIf { it > 0 }?.let { eta ->
            parts +=
                if (eta >= MINUTE_MILLIS) {
                    string(R.string.notification_eta_minutes, ((eta + MINUTE_MILLIS - 1) / MINUTE_MILLIS).toInt())
                } else {
                    string(R.string.notification_eta_seconds, ((eta + SECOND_MILLIS - 1) / SECOND_MILLIS).toInt())
                }
        }
        return parts.joinToString(SEPARATOR)
    }

    override fun announce(
        title: String,
        percent: Int,
    ) = string(R.string.notification_announce, title, percent)

    override fun offerTitle(peer: String) = string(R.string.notification_offer_title, peer)

    override fun offerSummary(
        fileCount: Int,
        totalBytes: Long,
        mimeHistogram: Map<String, Int>,
    ) = plural(R.plurals.notification_files, fileCount) + SEPARATOR + Formatter.formatShortFileSize(context(), totalBytes.coerceAtLeast(0))

    override fun offerCode(sas: String) = string(R.string.notification_offer_code, sas)

    override fun accept() = string(R.string.notification_accept)

    override fun decline() = string(R.string.notification_decline)

    override fun cancel() = string(R.string.notification_cancel)

    override fun open() = string(R.string.notification_open)

    override fun sent(
        peer: String,
        fileCount: Int,
    ) = string(R.string.notification_sent, peer)

    override fun received(
        peer: String,
        fileCount: Int,
    ) = string(R.string.notification_received, peer)

    /** The reason is the engine's, in English and technical; the notification names the peer only. */
    override fun failed(
        peer: String,
        reason: String?,
    ) = string(R.string.notification_failed, peer)

    override fun cancelled(peer: String) = string(R.string.notification_cancelled, peer)

    override fun declined(peer: String) = string(R.string.notification_declined, peer)

    override fun noAnswer(peer: String) = string(R.string.notification_no_answer, peer)

    override fun browserTitle() = string(R.string.notification_browser_title)

    override fun browserText(
        ssid: String?,
        url: String?,
    ) = if (ssid == null ||
        url == null
    ) {
        string(R.string.notification_browser_starting)
    } else {
        string(R.string.notification_browser_ready, ssid, url)
    }

    private companion object {
        const val SEPARATOR = " · "
        const val BYTES_PER_MB = 1_000_000.0
        const val MINUTE_MILLIS = 60_000L
        const val SECOND_MILLIS = 1_000L
    }
}
