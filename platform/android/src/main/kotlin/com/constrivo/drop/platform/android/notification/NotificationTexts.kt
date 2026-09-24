package com.constrivo.drop.platform.android.notification

import com.constrivo.drop.core.discovery.Visibility

/**
 * Every string the transfer service's notifications show (design §5, §8.3, §11), in the user's language. The app
 * supplies them from its resources (`ui/android` has the English and Hindi strings); the pure notification model and
 * its tests take any implementation. Names are the peer's nickname or custom name, already sanitised by the node.
 */
interface NotificationTexts {
    /** The channel names in system settings: progress, incoming offers, completed transfers, background visibility. */
    fun channelProgress(): String

    fun channelOffers(): String

    fun channelCompleted(): String

    fun channelSession(): String

    /** The radio-session notification while nothing transfers ("Visible to nearby devices"). */
    fun sessionTitle(): String

    /** Its second line for [visibility] ("Everyone can see this phone" / "Trusted devices can see this phone"). */
    fun sessionText(visibility: Visibility): String

    /** "Sending to {name}". */
    fun sending(peer: String): String

    /** "Receiving from {name}". */
    fun receiving(peer: String): String

    /** "{count} transfers" (several run at once). */
    fun severalTransfers(count: Int): String

    /** "Connecting…". */
    fun connecting(): String

    /** "Waiting for {name} to accept". */
    fun waitingForAnswer(peer: String): String

    /** "Reconnecting…" (S8). */
    fun reconnecting(): String

    /** "Waiting for {name}" (S8 parked). */
    fun waitingForPeer(peer: String): String

    /** "Checking files…". */
    fun verifying(): String

    /**
     * The progress line: "{percent}% · {speed} MB/s · {eta} left" (design §8.3 `transfer.speed`), leaving out what
     * is unknown.
     */
    fun progress(
        percent: Int,
        bytesPerSecond: Long?,
        etaMillis: Long?,
    ): String

    /** What TalkBack reads at every 25 % (design §11): "{title}, {percent} percent". */
    fun announce(
        title: String,
        percent: Int,
    ): String

    /** "{name} wants to send" (design §8.3 `incoming.title`). */
    fun offerTitle(peer: String): String

    /** "12 photos · 48 MB" (design §5.1 line 2). */
    fun offerSummary(
        fileCount: Int,
        totalBytes: Long,
        mimeHistogram: Map<String, Int>,
    ): String

    /** "Code {sas}: same on both screens?" for a first-time pairing (F-B3). */
    fun offerCode(sas: String): String

    fun accept(): String

    fun decline(): String

    fun cancel(): String

    fun open(): String

    /** "Sent to {name}" (design §8.3 `transfer.done`). */
    fun sent(
        peer: String,
        fileCount: Int,
    ): String

    /** "Received from {name}". */
    fun received(
        peer: String,
        fileCount: Int,
    ): String

    /** "Transfer with {name} failed" (+ the reason when there is one). */
    fun failed(
        peer: String,
        reason: String?,
    ): String

    /** "Transfer with {name} cancelled". */
    fun cancelled(peer: String): String

    /** "{name} declined". */
    fun declined(peer: String): String

    /** "No answer from {name}". */
    fun noAnswer(peer: String): String

    /** The browser page's title ("Page for a computer is on"). */
    fun browserTitle(): String

    /** "Join {ssid}, then open {url}" (N15), or the starting line when [ssid] is null. */
    fun browserText(
        ssid: String?,
        url: String?,
    ): String
}
