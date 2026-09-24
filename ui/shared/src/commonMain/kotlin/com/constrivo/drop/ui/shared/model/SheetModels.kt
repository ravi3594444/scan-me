package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.ui.shared.qr.QrMatrix

/**
 * An Offer waiting for an answer (F‑D1, design §5.1), as the app layer reports it from the engine.
 *
 * @property sas the six-digit code of a first-time pairing; null for a trusted sender, or until the handshake result
 *   exists (the card then shows it as soon as it arrives).
 * @property previews up to six thumbnails from the summary Offer (N12); more are ignored.
 * @property arrivedAtMillis when the Offer arrived, on the presenter's monotonic clock.
 */
@Immutable
data class IncomingOffer(
    val id: String,
    val senderKey: String,
    val senderName: String,
    val senderPlatform: DevicePlatform,
    val trusted: Boolean,
    val sas: String?,
    val summary: ItemSummary,
    val totalBytes: Long,
    val previews: List<FileThumb>,
    val arrivedAtMillis: Long,
    val senderAvatar: ImageBitmap? = null,
    val timeoutMillis: Long = 30_000,
) {
    init {
        require(totalBytes >= 0) { "size must not be negative" }
        require(timeoutMillis > 0) { "timeout must be positive" }
    }
}

/** The incoming card (design §5.1). */
@Immutable
data class IncomingCardUi(
    val offerId: String,
    val senderName: String,
    val senderInitials: String?,
    val senderAvatarHash: Int,
    val senderAvatar: ImageBitmap?,
    val senderPlatform: DevicePlatform,
    val trusted: Boolean,
    val summary: ItemSummary,
    val totalBytes: Long,
    /** At most [MAX_PREVIEWS]; [morePreviews] counts the files beyond them ("+N"). */
    val previews: List<FileThumb>,
    val morePreviews: Int,
    /** Null for a trusted sender or before the handshake result exists. */
    val sas: String?,
    val sasConfirmed: Boolean,
    val alwaysAccept: Boolean,
    /** "Always accept" needs a trusted sender or a confirmed code (F‑B3: no trust without the SAS). */
    val canAlwaysAccept: Boolean,
    val remainingMillis: Long,
    val remainingFraction: Float,
) {
    companion object {
        const val MAX_PREVIEWS: Int = 6
    }
}

/** The sender's first-time pairing code while the receiver decides (design §5.1: the code on both screens). */
@Immutable
data class SenderPairingUi(
    val transferId: String,
    val peerName: String,
    val code: String,
)

/** Tabs of the file picker (design §4.1); [APPS] only with the APK feature flag (decision 8). */
enum class PickerTab { PHOTOS, FILES, APPS }

/** One pickable item with its selection position (1-based count badge, design §4.1), or 0 when not selected. */
@Immutable
data class PickableUi(
    val item: PickedItem,
    val selectionIndex: Int,
) {
    val selected: Boolean get() = selectionIndex > 0
}

/** What the file picker picks for (design §4.1, F‑C5, N15). */
@Immutable
sealed interface PickerTarget {
    /** A bubble on the radar: Send starts a transfer to it. */
    data class Device(
        val key: String,
        val name: String?,
    ) : PickerTarget

    /** A running transfer (the Live tab's "Add files", F‑C5): the files are queued into it. */
    data class Transfer(
        val transferId: String,
        val peerName: String,
    ) : PickerTarget

    /** The browser receive page for a computer without the app (N15). */
    data object Browser : PickerTarget
}

/** The file picker sheet (design §4.1, F‑C1). */
@Immutable
data class FilePickerUi(
    val target: PickerTarget,
    val tabs: List<PickerTab>,
    val tab: PickerTab,
    val photos: List<PickableUi>,
    val photosAccess: Boolean,
    val files: List<PickableUi>,
    val apps: List<PickableUi>,
    val selectedCount: Int,
    val selectedBytes: Long,
) {
    val canSend: Boolean get() = selectedCount > 0

    /** The bubble the sheet is for, when it is for a device. */
    val targetKey: String? get() = (target as? PickerTarget.Device)?.key

    /** The name in the sheet's title, when there is one. */
    val targetName: String?
        get() =
            when (target) {
                is PickerTarget.Device -> target.name
                is PickerTarget.Transfer -> target.peerName
                PickerTarget.Browser -> null
            }
}

/** Credentials and address for the browser receive path (design §4.4 footer with N15: the real values). */
@Immutable
data class BrowserShareHint(
    val ssid: String,
    val password: String,
    /** The full address, `http://drop.local:<port>/t/<token>/`. */
    val url: String,
    /**
     * The same page by IP address, `http://192.168.49.1:<port>/t/<token>/` (design §10: "the IP shown as fallback",
     * web-receive's `ReceiveServer.ipUrl()`), for computers that do not resolve `drop.local`.
     */
    val ipUrl: String? = null,
)

/** Where the browser receive path stands (architecture §10.3), as the app layer reports it. */
@Immutable
sealed interface BrowserShareState {
    /** Not running. */
    data object Idle : BrowserShareState

    /** The hotspot or group and the server are starting. */
    data object Starting : BrowserShareState

    /** Running: the page is served at [hint]. */
    data class Ready(
        val hint: BrowserShareHint,
    ) : BrowserShareState

    /** It could not start (no hotspot or group, no free port); the sheet offers to try again. */
    data object Failed : BrowserShareState
}

/** The "Show my code" sheet (design §4.4, F‑B5). */
@Immutable
data class ShowQrUi(
    val nickname: String,
    val matrix: QrMatrix?,
    val fallbackCode: String?,
    /** Elapsed share of the 5-minute validity, for the refresh arc. */
    val refreshFraction: Float,
    val browserHint: BrowserShareHint?,
    /** The browser path is being prepared (hotspot or group starting). */
    val browserStarting: Boolean,
    /** The browser path could not start. */
    val browserFailed: Boolean = false,
    /** A QR code of the page's address, once the browser path runs (a phone or tablet without the app can scan it). */
    val browserMatrix: QrMatrix? = null,
    /** The sheet shows [browserMatrix] instead of the app's code. */
    val showingBrowserCode: Boolean = false,
)

/** "This is an app installer" (F‑D5), shown before a received installer or executable is opened. */
@Immutable
data class InstallerWarningUi(
    val fileName: String,
    /** Who sent it, or null when unknown. */
    val senderName: String?,
)

/** The scanner's chrome state (design §4.4); the camera preview itself is a platform slot. */
enum class ScanStatus { SCANNING, SUCCESS, EXPIRED, INVALID, NO_CAMERA_PERMISSION }

/** "Allow this computer?" (N15), shown when a browser opens the receive page. */
@Immutable
data class BrowserApprovalUi(
    val requestId: Long,
    val browserNumber: Int,
    val remoteAddress: String,
    val browser: BrowserDescription?,
)

/** The overflow list behind "+N more" (design §3.2). */
@Immutable
data class OverflowUi(
    val devices: List<BubbleUi>,
)
