package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge

/** Radio facts the radar's empty and error states need (design §8.1). */
@Immutable
data class RadioState(
    /** False on a desktop without Bluetooth: no "Turn on Bluetooth" state (the desktop shows its own banner, §9). */
    val bluetoothAvailable: Boolean = true,
    val bluetoothOn: Boolean = true,
    val wifiOn: Boolean = true,
    /** Nearby devices (Android 12+: `BLUETOOTH_SCAN` etc., architecture §11) is granted. */
    val nearbyPermission: Boolean = true,
)

/**
 * The stored visibility (F‑A5): the chosen [mode], and for "Everyone for 10 min" the end of the window and the mode
 * that follows ([revertTo]), as `core/data`'s `VisibilityPreference` keeps them.
 */
@Immutable
data class VisibilityState(
    val mode: Visibility,
    val expiresAtMillis: Long? = null,
    val revertTo: Visibility = Visibility.TRUSTED_ONLY,
) {
    /** The mode in force at [nowMillis] (a 10-minute window that has ended shows [revertTo]). */
    fun effectiveAt(nowMillis: Long): Visibility =
        if (mode == Visibility.EVERYONE_TEN_MINUTES && (expiresAtMillis == null || nowMillis >= expiresAtMillis)) revertTo else mode

    /** Minutes left in the window, rounded up (the chip's "Everyone · 7 min"), or null outside a window. */
    fun minutesLeftAt(nowMillis: Long): Int? {
        val end = expiresAtMillis ?: return null
        if (effectiveAt(nowMillis) != Visibility.EVERYONE_TEN_MINUTES) return null
        val left = end - nowMillis
        return ((left + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }

    companion object {
        /** Plan decision 2: Trusted only by default. */
        val DEFAULT = VisibilityState(Visibility.TRUSTED_ONLY)
    }
}

/** This device as shown at the bottom of the radar (design §3.1) and on the "Show my code" sheet. */
@Immutable
data class SelfProfile(
    val nickname: String,
    val deviceKey: String,
    val avatar: ImageBitmap? = null,
)

/** The one notice the radar shows, most important first (design §8.1). */
enum class RadarNotice {
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    WIFI_OFF,
    HIDDEN,
    NO_DEVICES,
    ;

    /** Whether the notice carries a button ("Allow", "Turn on", "Change"); "No devices" does not. */
    val hasAction: Boolean get() = this != NO_DEVICES

    /** Whether the rings are dimmed (design §8.1: "Ring dimmed" for Bluetooth and Wi‑Fi off, lock for permissions). */
    val dimsRings: Boolean get() = this == PERMISSION_MISSING || this == BLUETOOTH_OFF || this == WIFI_OFF
}

/** The visibility chip (design §2, §3.1): mode plus remaining minutes for "Everyone · 10 min". */
@Immutable
data class VisibilityUi(
    val mode: Visibility,
    val minutesLeft: Int? = null,
)

/** One device bubble (design §3.1, §3.2). */
@Immutable
data class BubbleUi(
    val key: String,
    val name: String?,
    val initials: String?,
    val avatarHash: Int,
    val platform: DevicePlatform,
    val trusted: Boolean,
    val ring: Ring,
    val lanOnly: Boolean,
    val rssiDbm: Double? = null,
    val activity: BubbleActivity? = null,
    /**
     * The key of the bubble this one replaces: the same stranger under its new rotating ID (15-minute epochs). The view
     * starts this bubble where that one was and moves it, instead of showing a new device appearing.
     */
    val replacesKey: String? = null,
) {
    /** Busy bubbles are never collapsed into "+N more" (they rank first in placement). */
    val busy: Boolean get() = activity != null
}

/** What a bubble is doing (design §4.2, §5.2). */
@Immutable
sealed interface BubbleActivity {
    val transferId: String
    val direction: Direction

    /**
     * Sending or receiving: the 4 dp ring, "44 MB/s · 45 s left", the badge and the hint.
     *
     * @property dropToken non-null for a send that just started or a receive whose bytes just started moving; the drop
     *   animation plays once per token, from the sending side to the receiving one.
     * @property announcedPercent the last 25% step reached, for the accessibility live region (design §11).
     * @property confirmCancel the × asks for confirmation because more than 100 MB have moved (design §4.2).
     */
    data class Active(
        override val transferId: String,
        override val direction: Direction,
        val stage: TransferStage,
        val fraction: Float,
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long?,
        val etaMillis: Long?,
        val badge: TransportBadge?,
        val hint: LadderHint?,
        val paused: Boolean,
        val resumed: Boolean,
        val dropToken: String?,
        val flyers: List<FileThumb>,
        val fileCount: Int,
        val announcedPercent: Int,
        val confirmCancel: Boolean,
    ) : BubbleActivity

    /** The completion pop and tick, shown for 1.5 s (design §4.2); [token] triggers the pop and the haptic once. */
    data class Completed(
        override val transferId: String,
        override val direction: Direction,
        val token: String,
    ) : BubbleActivity

    /** A transfer that ended otherwise ("Declined", "No answer", "Cancelled", failed), shown for a few seconds. */
    data class Ended(
        override val transferId: String,
        override val direction: Direction,
        val stage: TransferStage,
    ) : BubbleActivity
}

/**
 * The share-sheet banner "Sending 12 photos · 48 MB — tap a device" (design §4.3).
 *
 * @property names the first file names, so the user sees what will be sent; [moreCount] counts the rest.
 * @property waitingFor the name of a direct-share target (F‑C3) the files go to as soon as it is on the radar; null when
 *   a tap decides.
 */
@Immutable
data class AttachmentUi(
    val summary: ItemSummary,
    val totalBytes: Long,
    val names: List<String> = emptyList(),
    val moreCount: Int = 0,
    val waitingFor: String? = null,
) {
    companion object {
        /** How many names the banner lists. */
        const val MAX_NAMES: Int = 2
    }
}

/**
 * A thumbnail in the tray (design §5.2), newest last (on the right).
 *
 * @property installer the file installs or runs code ([InstallerFiles]); opening it asks first (F‑D5).
 */
@Immutable
data class TrayItemUi(
    val id: String,
    val name: String,
    val thumb: FileThumb,
    val installer: Boolean = false,
    val senderName: String? = null,
)

/** The confirm sheet for cancelling past 100 MB (design §4.2). */
@Immutable
data class CancelConfirmUi(
    val transferId: String,
    val peerName: String,
    val direction: Direction,
    val bytesDone: Long,
    val bytesTotal: Long,
)

/** The radar screen (design §3, §4.2, §4.3, §5.2, §8.1). */
@Immutable
data class RadarUiState(
    val self: SelfProfile,
    val selfInitials: String?,
    val selfAvatarHash: Int,
    val bubbles: List<BubbleUi> = emptyList(),
    val notice: RadarNotice? = RadarNotice.NO_DEVICES,
    val visibility: VisibilityUi = VisibilityUi(VisibilityState.DEFAULT.mode),
    val attachment: AttachmentUi? = null,
    val tray: List<TrayItemUi> = emptyList(),
    val cancelConfirm: CancelConfirmUi? = null,
    /** The bubble whose file picker is open (scaled to 1.3× before a send starts, design §3.3 "selected"). */
    val selectedKey: String? = null,
    /** A first-time send waiting for the user to compare the six-digit code (design §5.1: the code on both screens). */
    val senderPairing: SenderPairingUi? = null,
) {
    val ringsDimmed: Boolean get() = notice?.dimsRings == true

    companion object {
        fun initial(self: SelfProfile): RadarUiState =
            RadarUiState(self = self, selfInitials = Avatars.initials(self.nickname), selfAvatarHash = Avatars.hash(self.deviceKey))
    }
}
