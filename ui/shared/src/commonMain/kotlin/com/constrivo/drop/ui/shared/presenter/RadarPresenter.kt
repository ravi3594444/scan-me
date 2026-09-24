package com.constrivo.drop.ui.shared.presenter

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
    /** Starts a send of [files] to the device with radar key [deviceKey] (F‑C1, F‑C2). */
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
 * - Files from the share sheet are attached until a bubble is tapped; a direct-share target (F‑C3) receives them as
 *   soon as its bubble appears.
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
        val attachment: AttachedFiles? = null,
        val directTarget: String? = null,
        val tray: List<TrayItemUi> = emptyList(),
        val cancelConfirm: CancelConfirmUi? = null,
        val selectedKey: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val tick = MutableStateFlow(0L)

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
        scope.launch {
            received.collect { file ->
                local.update { l ->
                    val item = TrayItemUi(file.id, file.name, file.thumb)
                    l.copy(tray = (l.tray.filterNot { it.id == file.id } + item).takeLast(MAX_TRAY_ITEMS))
                }
            }
        }
        // Direct share (F‑C3): the chosen device gets the files as soon as its bubble is on the radar.
        scope.launch {
            combine(devices, local) { d, l -> d to l }.collect { (d, l) ->
                val target = l.directTarget ?: return@collect
                val files = l.attachment ?: return@collect
                if (d.any { it.key == target }) {
                    local.update { it.copy(attachment = null, directTarget = null) }
                    actions.send(target, files)
                }
            }
        }
    }

    /** Taps a bubble: sends attached files, or asks the host to open the picker (and raises the bubble). */
    fun onBubbleTapped(key: String): BubbleTapResult {
        val bubble = latestBubbles.firstOrNull { it.key == key }
        if (bubble?.activity is BubbleActivity.Active) return BubbleTapResult.BUSY
        val files = local.value.attachment
        if (files != null) {
            local.update { it.copy(attachment = null, directTarget = null, selectedKey = null) }
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
     * direct-share target chosen in the system sheet (F‑C3); null clears it. An empty list clears the attachment.
     */
    fun attach(
        files: AttachedFiles?,
        directTarget: String? = null,
    ) {
        val kept = files?.takeIf { it.items.isNotEmpty() }
        local.update { it.copy(attachment = kept, directTarget = if (kept != null) directTarget else null) }
    }

    fun clearAttachment() = attach(null)

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
        local.update { it.copy(confirmedPairings = it.confirmedPairings + transferId) }
        actions.confirmPairing(transferId)
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
        val bubbles =
            devices
                .distinctBy { it.key }
                .sortedBy { it.key }
                .map { d -> bubbleOf(d, activityFor(byPeer[d.key].orEmpty(), now)) }
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
            attachment = local.attachment?.let { AttachmentUi(it.summary, it.totalBytes) },
            tray = local.tray,
            cancelConfirm = local.cancelConfirm?.takeIf { c -> transfers.any { it.id == c.transferId && !it.stage.isFinal } },
            selectedKey = selected,
            senderPairing = pairingOf(transfers, local.confirmedPairings),
        )
    }

    private fun pairingOf(
        transfers: List<TransferSnapshot>,
        confirmed: Set<String>,
    ): SenderPairingUi? {
        for (t in transfers) {
            val code = t.pairingCode ?: continue
            if (t.direction == Direction.SEND && !t.stage.isFinal && t.id !in confirmed) return SenderPairingUi(t.id, t.peerName, code)
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
        private const val MINUTE_MILLIS = 60_000L
    }
}
