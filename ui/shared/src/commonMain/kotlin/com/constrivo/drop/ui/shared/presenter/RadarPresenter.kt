package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.AttachmentUi
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.CancelConfirmUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.InstallerFiles
import com.constrivo.drop.ui.shared.model.ProgressAnnouncements
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.RadioState
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SenderPairingUi
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.TrayItemUi
import com.constrivo.drop.ui.shared.model.VisibilityState
import com.constrivo.drop.ui.shared.model.VisibilityUi
import com.constrivo.drop.ui.shared.theme.DropMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the radar asks the engine to do (the app layer implements it). */
interface RadarActions {
    /**
     * Starts a send of [files] to the device with radar key [deviceKey] (F‑C1, F‑C2).
     *
     * On Android the items are `content:` URIs. Those from the share sheet are readable only while the activity that
     * received the share is alive: Android revokes the sender's grant when that activity is destroyed. The engine
     * must take its own grant before the send can outlive it (WP7: start the `TransferService` with an Intent that
     * carries the URIs in its `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`, which the service keeps until it
     * stops). Picker and document URIs are readable by this app for as long as it runs.
     */
    fun send(
        deviceKey: String,
        files: AttachedFiles,
    )

    /** Cancels a running transfer (design §4.2 ×). */
    fun cancel(transferId: String)

    /** The sender confirmed that the six-digit code matches (F‑B3); the engine stores the trust. */
    fun confirmPairing(transferId: String)
}

/** What a bubble tap did, so the host knows whether to open the file picker. */
enum class BubbleTapResult {
    /** No attachment: the host opens the file picker for the bubble (design §4.1). */
    OPEN_PICKER,

    /** Files from the share sheet went to the device (design §4.3: two taps from Gallery). */
    SENT,

    /** The bubble is sending or receiving; nothing happens. */
    BUSY,
}

/**
 * The radar presenter (F‑A2, F‑C2, F‑C4, F‑D3; design §3, §4.2, §4.3, §5.2, §8.1): maps core/discovery's
 * [NearbyDevice] list and the engine's [TransferSnapshot]s into one [RadarUiState].
 *
 * - Bubbles keep core/discovery's ring (smoothed RSSI with hysteresis) and key; placement happens in the view
 *   ([com.constrivo.drop.ui.shared.radar.RadarFrame]) because it depends on the viewport.
 * - A transfer shows on its peer's bubble while it runs; when it ends the bubble shows the completion tick for
 *   [DropMotion.COMPLETION_HOLD_MILLIS] (1.5 s, design §4.2) or the end caption ("Declined", "No answer") for
 *   [ENDED_HOLD_MILLIS], then returns to idle. Only transfers seen running get either, so an old result never pops.
 * - A send gets a drop-animation token for its first [DROP_TOKEN_WINDOW_MILLIS], so reopening the radar mid-transfer
 *   does not replay the flight.
 * - The × asks for confirmation only past [CANCEL_CONFIRM_BYTES] (100 MB, design §4.2).
 * - The notice is the most important of design §8.1: missing permission, Bluetooth off, Wi‑Fi off, hidden, no one.
 * - The visibility chip counts down the minutes of "Everyone for 10 min" on [wallClock].
 * - Files from the share sheet are attached until a bubble is tapped; a direct-share target (F‑C3) receives them if
 *   its bubble appears within [DIRECT_SHARE_WINDOW_MILLIS], after which they wait for a tap like any share. Taking
 *   the files is atomic, so they are sent once whichever path gets there first.
 * - Received files reach the tray in batches every [TRAY_BATCH_MILLIS], so thousands of small files (F‑E7) cost a few
 *   radar updates a second, and the collector never makes the engine wait.
 * - A stranger's rotating ID (15-minute epochs, core/discovery `EphemeralId`) makes it reappear under a new key. Near
 *   an epoch boundary a new stranger with the same name and platform as one that has just gone quiet is shown as that
 *   bubble moving ([BubbleUi.replacesKey]), not as a second device; see [StrangerRotation].
 * - The sender's code sheet (F‑B3) can be put aside for a transfer without trusting the device; tapping the bubble
 *   brings it back while the transfer runs.
 *
 * Time comes only from [monotonicClock], [wallClock] and the scope's `delay`, so a test dispatcher makes it
 * deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadarPresenter(
    private val scope: CoroutineScope,
    devices: Flow<List<NearbyDevice>>,
    transfers: Flow<List<TransferSnapshot>>,
    radio: Flow<RadioState>,
    visibility: Flow<VisibilityState>,
    self: Flow<SelfProfile>,
    initialSelf: SelfProfile,
    private val actions: RadarActions,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    received: Flow<ReceivedFile> = emptyFlow(),
) {
    private data class Local(
        val confirmedPairings: Set<String> = emptySet(),
        val laterPairings: Set<String> = emptySet(),
        val attachment: AttachedFiles? = null,
        /** Identifies the share that [attachment] came from, so its host can release it ([releaseShare]). */
        val shareId: String? = null,
        val directTarget: String? = null,
        val directTargetName: String? = null,
        /** When the direct target stops receiving the files by itself, on the monotonic clock. */
        val directDeadline: Long? = null,
        val tray: List<TrayItemUi> = emptyList(),
        val cancelConfirm: CancelConfirmUi? = null,
        val selectedKey: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val tick = MutableStateFlow(0L)
    private val rotation = StrangerRotation()
    private val pendingTray = ArrayList<ReceivedFile>()
    private var trayFlush: Job? = null

    // Touched only by the single state collector (stateIn), in order.
    private val seenRunning = HashSet<String>()
    private val endedAt = HashMap<String, Long>()
    private val firstSeen = HashMap<String, Long>()
    private var latestTransfers: List<TransferSnapshot> = emptyList()
    private var latestBubbles: List<BubbleUi> = emptyList()

    private val visibilityUi: Flow<VisibilityUi> =
        visibility.distinctUntilChanged().flatMapLatest { v -> visibilityTicker(v) }

    private val inputs =
        combine(devices, transfers, radio) { d, t, r -> Triple(d, t, r) }

    val state: StateFlow<RadarUiState> =
        combine(inputs, visibilityUi, self, local, tick) { (d, t, r), v, me, l, _ ->
            map(d, t, r, v, me, l)
        }.stateIn(scope, SharingStarted.Eagerly, RadarUiState.initial(initialSelf))

    init {
        // Received files: the collector only queues (it never makes the engine wait); the tray updates in batches.
        scope.launch {
            received.collect { file ->
                pendingTray += file
                if (trayFlush == null) {
                    trayFlush =
                        scope.launch {
                            delay(TRAY_BATCH_MILLIS)
                            flushTray()
                        }
                }
            }
        }
        // Direct share (F‑C3): the chosen device gets the files as soon as its bubble is on the radar. Follows the
        // mapped state (devices and attachment together), so the device list is collected only once.
        scope.launch {
            state.collect { st ->
                val target = local.value.directTarget ?: return@collect
                if (st.bubbles.any { it.key == target }) takeAttachmentFor(target)?.let { actions.send(target, it) }
            }
        }
    }

    private fun flushTray() {
        trayFlush = null
        if (pendingTray.isEmpty()) return
        val batch = pendingTray.takeLast(MAX_TRAY_ITEMS)
        pendingTray.clear()
        local.update { l ->
            val ids = batch.mapTo(HashSet()) { it.id }
            val items =
                batch.map { f ->
                    TrayItemUi(f.id, f.name, f.thumb, InstallerFiles.isInstaller(f.name, f.mime, f.kind), f.senderName)
                }
            l.copy(tray = (l.tray.filterNot { it.id in ids } + items).takeLast(MAX_TRAY_ITEMS))
        }
    }

    /**
     * Takes the attached files for the direct target [target] if they are still waiting for it and its window is
     * open; null otherwise (a tap took them, or they now wait for a tap). Atomic, so the files go out once.
     */
    private fun takeAttachmentFor(target: String): AttachedFiles? {
        while (true) {
            val cur = local.value
            if (cur.directTarget != target) return null
            val files = cur.attachment ?: return null
            val deadline = cur.directDeadline
            if (deadline != null && monotonicClock.elapsedMillis() >= deadline) {
                if (local.compareAndSet(cur, cur.copy(directTarget = null, directTargetName = null, directDeadline = null))) return null
                continue
            }
            if (local.compareAndSet(cur, cur.withoutAttachment())) return files
        }
    }

    /** Takes the attached files whoever they were for (a bubble tap, the browser path); null when there are none. */
    fun takeAttachment(): AttachedFiles? {
        while (true) {
            val cur = local.value
            val files = cur.attachment ?: return null
            if (local.compareAndSet(cur, cur.withoutAttachment().copy(selectedKey = null))) return files
        }
    }

    private fun Local.withoutAttachment() =
        copy(attachment = null, shareId = null, directTarget = null, directTargetName = null, directDeadline = null)

    /** Taps a bubble: sends attached files, or asks the host to open the picker (and raises the bubble). */
    fun onBubbleTapped(key: String): BubbleTapResult {
        val bubble = latestBubbles.firstOrNull { it.key == key }
        val activity = bubble?.activity
        if (activity is BubbleActivity.Active) {
            // A code sheet put aside for this transfer comes back (F‑B3: the code stays reachable until it ends).
            if (activity.transferId in local.value.laterPairings) {
                local.update { it.copy(laterPairings = it.laterPairings - activity.transferId) }
            }
            return BubbleTapResult.BUSY
        }
        val files = takeAttachment()
        if (files != null) {
            actions.send(key, files)
            return BubbleTapResult.SENT
        }
        local.update { it.copy(selectedKey = key) }
        return BubbleTapResult.OPEN_PICKER
    }

    /** The picker for [key] opened ([key] set) or closed without sending (null). */
    fun select(key: String?) {
        local.update { it.copy(selectedKey = key) }
    }

    /** Sends picked files to [key] (the picker's Send button). */
    fun send(
        key: String,
        files: AttachedFiles,
    ) {
        if (files.items.isEmpty()) return
        local.update { it.copy(selectedKey = null) }
        actions.send(key, files)
    }

    /**
     * Files from the share sheet (design §4.3); replaces any earlier attachment. [directTarget] is the radar key of a
     * direct-share target chosen in the system sheet (F‑C3), named [directTargetName] in the banner while the files
     * wait for it; null clears it. [shareId] identifies the share for [releaseShare]. An empty list clears the
     * attachment.
     */
    fun attach(
        files: AttachedFiles?,
        directTarget: String? = null,
        directTargetName: String? = null,
        shareId: String? = null,
    ) {
        val kept = files?.takeIf { it.items.isNotEmpty() }
        val target = if (kept != null) directTarget else null
        val deadline = target?.let { monotonicClock.elapsedMillis() + DIRECT_SHARE_WINDOW_MILLIS }
        local.update {
            it.copy(
                attachment = kept,
                shareId = if (kept != null) shareId else null,
                directTarget = target,
                directTargetName = if (target != null) directTargetName else null,
                directDeadline = deadline,
            )
        }
        if (deadline != null) {
            scope.launch {
                delay(DIRECT_SHARE_WINDOW_MILLIS)
                // The window closed without the device: the files stay attached and wait for a tap.
                local.update { l ->
                    if (l.directDeadline ==
                        deadline
                    ) {
                        l.copy(directTarget = null, directTargetName = null, directDeadline = null)
                    } else {
                        l
                    }
                }
            }
        }
    }

    fun clearAttachment() = attach(null)

    /** Whether the files of share [shareId] are still attached (not sent, cleared or replaced). */
    fun holdsShare(shareId: String): Boolean = local.value.let { it.attachment != null && it.shareId == shareId }

    /**
     * The host of share [shareId] is gone for good (Android revoked the URI grants with it): its files, and a direct
     * target waiting for them, are dropped. Another share's files are left alone.
     */
    fun releaseShare(shareId: String) {
        local.update { if (it.shareId == shareId) it.withoutAttachment() else it }
    }

    /** The × on a busy bubble: cancels at once, or asks first past 100 MB (design §4.2). */
    fun onCancelTapped(transferId: String) {
        val t = latestTransfers.firstOrNull { it.id == transferId } ?: return
        if (t.stage.isFinal) return
        if (t.bytesDone > CANCEL_CONFIRM_BYTES) {
            local.update { it.copy(cancelConfirm = CancelConfirmUi(t.id, t.peerName, t.direction, t.bytesDone, t.bytesTotal)) }
        } else {
            actions.cancel(transferId)
        }
    }

    fun confirmCancel() {
        val confirm = local.value.cancelConfirm ?: return
        local.update { it.copy(cancelConfirm = null) }
        actions.cancel(confirm.transferId)
    }

    /** "Yes, it matches" on the sender's pairing sheet. */
    fun confirmPairing(transferId: String) {
        local.update { it.copy(confirmedPairings = it.confirmedPairings + transferId, laterPairings = it.laterPairings - transferId) }
        actions.confirmPairing(transferId)
    }

    /**
     * "Not now" (or Back, or the scrim) on the sender's pairing sheet: hides it for [transferId] without trusting the
     * device. The transfer goes on; tapping the peer's bubble shows the code again while it runs.
     */
    fun pairLater(transferId: String) {
        local.update { it.copy(laterPairings = it.laterPairings + transferId) }
    }

    fun dismissCancel() {
        local.update { it.copy(cancelConfirm = null) }
    }

    /** The user left the radar: the tray clears (design §5.2; History keeps everything). */
    fun clearTray() {
        local.update { it.copy(tray = emptyList()) }
    }

    private fun map(
        devices: List<NearbyDevice>,
        transfers: List<TransferSnapshot>,
        radio: RadioState,
        visibility: VisibilityUi,
        self: SelfProfile,
        local: Local,
    ): RadarUiState {
        val now = monotonicClock.elapsedMillis()
        track(transfers, now)
        latestTransfers = transfers
        val byPeer = transfers.filter { it.peerKey != null }.groupBy { it.peerKey!! }
        val busyKeys = transfers.filter { !it.stage.isFinal }.mapNotNullTo(HashSet()) { it.peerKey }
        val rotated = rotation.apply(devices.distinctBy { it.key }, busyKeys, now, wallClock.nowMillis())
        rotated.recheckInMillis?.let(::scheduleTick)
        val bubbles =
            rotated.shown
                .sortedBy { it.key }
                .map { d -> bubbleOf(d, activityFor(byPeer[d.key].orEmpty(), now), rotated.replaces[d.key]) }
        latestBubbles = bubbles
        val notice =
            when {
                !radio.nearbyPermission -> RadarNotice.PERMISSION_MISSING
                radio.bluetoothAvailable && !radio.bluetoothOn -> RadarNotice.BLUETOOTH_OFF
                !radio.wifiOn -> RadarNotice.WIFI_OFF
                visibility.mode == Visibility.HIDDEN -> RadarNotice.HIDDEN
                bubbles.isEmpty() -> RadarNotice.NO_DEVICES
                else -> null
            }
        val selected = local.selectedKey?.takeIf { key -> bubbles.any { it.key == key } }
        return RadarUiState(
            self = self,
            selfInitials = Avatars.initials(self.nickname),
            selfAvatarHash = Avatars.hash(self.deviceKey),
            bubbles = bubbles,
            notice = notice,
            visibility = visibility,
            attachment = local.attachment?.let { attachmentUi(it, local.directTargetName.takeIf { _ -> local.directTarget != null }) },
            tray = local.tray,
            cancelConfirm = local.cancelConfirm?.takeIf { c -> transfers.any { it.id == c.transferId && !it.stage.isFinal } },
            selectedKey = selected,
            senderPairing = pairingOf(transfers, local.confirmedPairings + local.laterPairings),
        )
    }

    private fun attachmentUi(
        files: AttachedFiles,
        waitingFor: String?,
    ): AttachmentUi {
        val names = files.items.take(AttachmentUi.MAX_NAMES).map { it.name }
        return AttachmentUi(files.summary, files.totalBytes, names, files.count - names.size, waitingFor)
    }

    private fun pairingOf(
        transfers: List<TransferSnapshot>,
        hidden: Set<String>,
    ): SenderPairingUi? {
        for (t in transfers) {
            val code = t.pairingCode ?: continue
            if (t.direction == Direction.SEND && !t.stage.isFinal && t.id !in hidden) return SenderPairingUi(t.id, t.peerName, code)
        }
        return null
    }

    /** Records when transfers were first seen and when they ended, and schedules the re-evaluations. */
    private fun track(
        transfers: List<TransferSnapshot>,
        now: Long,
    ) {
        val ids = HashSet<String>()
        for (t in transfers) {
            ids += t.id
            if (firstSeen.putIfAbsentCompat(t.id, now)) scheduleTick(DROP_TOKEN_WINDOW_MILLIS)
            if (!t.stage.isFinal) {
                seenRunning += t.id
                endedAt.remove(t.id)
            } else if (t.id in seenRunning && t.id !in endedAt) {
                endedAt[t.id] = now
                scheduleTick(if (t.stage == TransferStage.DONE) DropMotion.COMPLETION_HOLD_MILLIS else ENDED_HOLD_MILLIS)
            }
        }
        // Forget transfers the engine no longer reports.
        seenRunning.retainAll(ids)
        endedAt.keys.retainAll(ids)
        firstSeen.keys.retainAll(ids)
    }

    private fun scheduleTick(afterMillis: Long) {
        scope.launch {
            delay(afterMillis)
            tick.update { it + 1 }
        }
    }

    /** The activity a bubble shows: a running transfer first, else a transfer that just ended (within its hold). */
    private fun activityFor(
        transfers: List<TransferSnapshot>,
        now: Long,
    ): BubbleActivity? {
        val running = transfers.filter { !it.stage.isFinal }.maxByOrNull { firstSeen[it.id] ?: 0L }
        if (running != null) return active(running, now)
        val ended =
            transfers
                .filter { it.id in endedAt }
                .maxByOrNull { endedAt.getValue(it.id) } ?: return null
        val age = now - endedAt.getValue(ended.id)
        return if (ended.stage == TransferStage.DONE) {
            if (age < DropMotion.COMPLETION_HOLD_MILLIS) BubbleActivity.Completed(ended.id, ended.direction, ended.id) else null
        } else {
            if (age < ENDED_HOLD_MILLIS) BubbleActivity.Ended(ended.id, ended.direction, ended.stage) else null
        }
    }

    private fun active(
        t: TransferSnapshot,
        now: Long,
    ): BubbleActivity.Active {
        val fraction = t.fraction
        val fresh = now - (firstSeen[t.id] ?: now) < DROP_TOKEN_WINDOW_MILLIS
        val flying = t.direction == Direction.SEND && fresh
        return BubbleActivity.Active(
            transferId = t.id,
            direction = t.direction,
            stage = t.stage,
            fraction = fraction,
            bytesDone = t.bytesDone,
            bytesTotal = t.bytesTotal,
            bytesPerSecond = t.bytesPerSecond,
            etaMillis = t.etaMillis,
            badge = t.badge,
            hint = t.hint,
            paused = t.paused,
            resumed = t.resumed,
            dropToken = if (flying) t.id else null,
            flyers = t.thumbnails.take(DropMotion.MAX_FLYERS),
            fileCount = t.summary.count,
            announcedPercent = ProgressAnnouncements.step(fraction),
            confirmCancel = t.bytesDone > CANCEL_CONFIRM_BYTES,
        )
    }

    private fun bubbleOf(
        d: NearbyDevice,
        activity: BubbleActivity?,
        replacesKey: String?,
    ): BubbleUi =
        BubbleUi(
            key = d.key,
            name = d.nickname,
            initials = Avatars.initials(d.nickname),
            avatarHash = Avatars.hash(d.trustedDeviceId ?: d.key),
            platform = d.platform,
            trusted = d.trusted,
            ring = d.ring,
            lanOnly = d.lanOnly,
            rssiDbm = d.smoothedRssiDbm,
            activity = activity,
            replacesKey = replacesKey,
        )

    /** Emits the chip state now and again each time the minutes left (or the window itself) change. */
    private fun visibilityTicker(v: VisibilityState): Flow<VisibilityUi> {
        val end = v.expiresAtMillis
        if (v.mode != Visibility.EVERYONE_TEN_MINUTES || end == null) return flowOf(VisibilityUi(v.effectiveAt(wallClock.nowMillis())))
        return flow {
            while (true) {
                val now = wallClock.nowMillis()
                emit(VisibilityUi(v.effectiveAt(now), v.minutesLeftAt(now)))
                val left = end - now
                if (left <= 0) break
                delay((left - 1) % MINUTE_MILLIS + 1)
            }
        }
    }

    private fun <K, V> HashMap<K, V>.putIfAbsentCompat(
        key: K,
        value: V,
    ): Boolean {
        if (containsKey(key)) return false
        put(key, value)
        return true
    }

    companion object {
        /** The × confirms only past this many bytes (design §4.2: "> 100 MB", decimal as in the UI, S6). */
        const val CANCEL_CONFIRM_BYTES: Long = 100_000_000

        /** How long "Declined", "No answer" or a failure stays on the bubble. */
        const val ENDED_HOLD_MILLIS: Long = 3_000

        /** A send plays its drop animation only within this long of first being seen. */
        const val DROP_TOKEN_WINDOW_MILLIS: Long = 2_000

        const val MAX_TRAY_ITEMS: Int = 50

        /** Received files reach the tray at most this often (F‑E7: 5,000 photos in about 90 s). */
        const val TRAY_BATCH_MILLIS: Long = 150

        /**
         * How long a direct-share target (F‑C3) receives the files by itself once they are attached. The share sheet
         * lists devices that are on the radar, so one that has not appeared by then left; the files then wait for a
         * tap instead of going out whenever that device next shows up, possibly hours later.
         */
        const val DIRECT_SHARE_WINDOW_MILLIS: Long = 45_000
        private const val MINUTE_MILLIS = 60_000L
    }
}

/**
 * Follows strangers across their 15-minute ID rotation (core/discovery `EphemeralIds`, WP1 carry-forward): a stranger
 * that core cannot link reappears under a new radar key at an epoch boundary, while its old key lingers until core's
 * 5 s beacon timeout, so the same person would show twice.
 *
 * Within [windowMillis] of a boundary (wall clock), a new stranger with the same nickname and platform as a stranger
 * already on the radar is held back for up to [holdMillis]. If the old one goes quiet (not heard for
 * [silentMillis]) meanwhile, it is taken off the radar and the new one takes its place, drawn as the old bubble moving
 * to the new position ([BubbleUi.replacesKey]). If the old one is still heard, both are different devices and the new
 * one appears normally. Trusted devices keep their key across rotations and never take part; neither does a busy
 * bubble. Confined to the presenter's collector.
 */
internal class StrangerRotation(
    private val windowMillis: Long = 30_000,
    private val silentMillis: Long = 1_500,
    private val holdMillis: Long = 3_000,
) {
    class Result(
        val shown: List<NearbyDevice>,
        /** New key → the old key whose bubble it replaces. */
        val replaces: Map<String, String>,
        /** Evaluate again after this long (a held-back device is waiting for a decision). */
        val recheckInMillis: Long?,
    )

    private val firstSeen = HashMap<String, Long>()

    /** New key → old key, while the new one is held back. */
    private val pending = HashMap<String, String>()

    /** New key → old key, once decided; the old key stays hidden while core still lists it. */
    private val replaced = HashMap<String, String>()

    fun apply(
        devices: List<NearbyDevice>,
        busyKeys: Set<String>,
        now: Long,
        wallNow: Long,
    ): Result {
        val present = devices.associateBy { it.key }
        firstSeen.keys.retainAll(present.keys)
        pending.entries.removeAll { (new, old) -> new !in present || old !in present }
        replaced.keys.retainAll(present.keys)
        val nearBoundary = nearEpochBoundary(wallNow)
        for (d in devices) {
            if (firstSeen.containsKey(d.key)) continue
            firstSeen[d.key] = now
            if (!nearBoundary || !d.isNamedStranger() || d.key in busyKeys) continue
            val taken = pending.values.toSet() + replaced.values
            val candidates =
                devices.filter { o ->
                    o.key != d.key && o.key !in taken && o.key !in pending && o.key !in busyKeys && o.isNamedStranger() &&
                        o.nickname == d.nickname && o.platform == d.platform && (firstSeen[o.key] ?: now) < now
                }
            if (candidates.size == 1) pending[d.key] = candidates.single().key
        }
        var recheck: Long? = null
        for ((new, old) in pending.entries.toList()) {
            val quietFor = now - present.getValue(old).lastSeenElapsedMillis
            val heldFor = now - firstSeen.getValue(new)
            when {
                quietFor >= silentMillis -> {
                    pending.remove(new)
                    replaced[new] = old
                }

                heldFor >= holdMillis -> {
                    pending.remove(new)
                }

                else -> {
                    val next = minOf(silentMillis - quietFor, holdMillis - heldFor).coerceAtLeast(1)
                    recheck = recheck?.let { minOf(it, next) } ?: next
                }
            }
        }
        val hidden = pending.keys + replaced.values
        return Result(devices.filter { it.key !in hidden }, replaced.toMap(), recheck)
    }

    private fun NearbyDevice.isNamedStranger() = trustedDeviceId == null && !nickname.isNullOrBlank()

    private fun nearEpochBoundary(wallNow: Long): Boolean {
        if (wallNow < 0) return false
        val into = wallNow % EphemeralIds.EPOCH_MILLIS
        return minOf(into, EphemeralIds.EPOCH_MILLIS - into) <= windowMillis
    }
}
