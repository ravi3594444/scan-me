package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.InstallerFiles
import com.constrivo.drop.ui.shared.model.InstallerWarningUi
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTarget
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadioState
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.VisibilityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Platform actions the shared UI triggers but cannot perform itself (radio panels, opening files, pickers). */
interface PlatformActions {
    /** Android: `BluetoothAdapter.ACTION_REQUEST_ENABLE` (F‑A6: the user never leaves the app). */
    fun turnOnBluetooth()

    /** Android: `Settings.Panel.ACTION_WIFI` (apps cannot switch Wi‑Fi on themselves on Android 10+). */
    fun openWifiPanel()

    /**
     * Opens a received file with the system (never automatically, F‑D5). The controller shows the installer warning
     * first for installers and executables ([com.constrivo.drop.ui.shared.model.InstallerFiles]).
     */
    fun openReceivedFile(fileId: String)

    /** Shares a received file onward (tray long-press, design §5.2). */
    fun shareReceivedFile(fileId: String)

    fun openReceivedFolder()

    /** Opens the system document picker (SAF); the result comes back through [DropAppController.onFilesPicked]. */
    fun browseFiles()
}

/** Onboarding inputs: the device name to prefill, the OEM brand for the brand step, and where the result goes. */
class OnboardingConfig(
    val deviceName: String,
    val brand: OemBrand?,
    val actions: OnboardingActions,
)

/**
 * Every port the shared UI reads or calls (the app layers implement them over the engine and core/data). The
 * controller collects each state flow once and shares it between its presenters, so a cold upstream (a `callbackFlow`
 * that starts a scan) runs once; hot flows (`StateFlow`) are the natural fit.
 *
 * @property received finished files for the tray. The engine should emit without suspending (for example `tryEmit`
 *   into a buffer with `BufferOverflow.DROP_OLDEST`); the radar drains it at once and batches tray updates.
 * @property computeContext where heavy mapping runs off the main thread (History's day groups).
 */
class DropDependencies(
    val devices: Flow<List<NearbyDevice>>,
    val transfers: Flow<List<TransferSnapshot>>,
    val offers: Flow<List<IncomingOffer>>,
    val received: Flow<ReceivedFile>,
    val radio: Flow<RadioState>,
    val visibility: Flow<VisibilityState>,
    val profile: Flow<SelfProfile>,
    val initialProfile: SelfProfile,
    val radarActions: RadarActions,
    val incomingActions: IncomingActions,
    val liveActions: LiveActions,
    val history: HistorySource,
    val trustedDevices: DeviceSource,
    val stats: StatsSource,
    val settings: SettingsSource,
    val initialSettings: SettingsValues,
    val myCode: MyCodeSource,
    val media: MediaLibrary,
    val permissions: PermissionController,
    val platform: PlatformActions,
    val calendar: DayCalendar,
    val wallClock: WallClock,
    val monotonicClock: MonotonicClock,
    val onboarding: OnboardingConfig? = null,
    val features: FeatureFlags = FeatureFlags(),
    val computeContext: CoroutineContext = Dispatchers.Default,
) {
    companion object {
        /**
         * Every port backed by [fake] (tests, previews, and the app shells until the engine is wired): an empty radar,
         * empty History, all permissions granted.
         */
        fun inMemory(
            fake: InMemoryDrop = InMemoryDrop(),
            wallClock: WallClock,
            monotonicClock: MonotonicClock = SystemMonotonicClock,
            calendar: DayCalendar = DayCalendar.fixedOffset(0),
            permissions: PermissionController = PermissionController.AllGranted,
            onboarding: OnboardingConfig? = null,
            features: FeatureFlags = FeatureFlags(),
            computeContext: CoroutineContext = Dispatchers.Default,
        ): DropDependencies =
            DropDependencies(
                devices = fake.devices,
                transfers = fake.transfers,
                offers = fake.offers,
                received = fake.receivedFiles,
                radio = fake.radio,
                visibility = fake.visibility,
                profile = fake.profile,
                initialProfile = fake.profile.value,
                radarActions = fake,
                incomingActions = fake,
                liveActions = fake,
                history = fake,
                trustedDevices = fake,
                stats = fake,
                settings = fake,
                initialSettings = fake.settingsState.value,
                myCode = fake,
                media = fake,
                permissions = permissions,
                platform = fake,
                calendar = calendar,
                wallClock = wallClock,
                monotonicClock = monotonicClock,
                onboarding = onboarding,
                features = features,
                computeContext = computeContext,
            )
    }
}

/** Top-level screens (design §2). */
enum class Screen { ONBOARDING, RADAR, DASHBOARD, SCAN }

/**
 * The sheets the controller opens (design §2): over the radar, and the file picker also over the dashboard (the Live
 * tab's "Add files", F‑C5).
 */
enum class RadarSheet { PICKER, SHOW_QR, VISIBILITY, OVERFLOW }

/**
 * Wires the presenters of the shared UI to [DropDependencies] and holds navigation (design §2): onboarding, the radar
 * with its sheets, the dashboard and the scanner. Permissions are asked just in time through [permissions] (F‑I1):
 * Nearby when the radar first opens, media when a picker first opens, location (Android 12) before every send
 * whatever path starts it, notifications on the first transfer, the camera when the scanner opens, and the
 * battery-optimisation exemption once after onboarding. Main-thread confined.
 */
class DropAppController(
    private val scope: CoroutineScope,
    private val deps: DropDependencies,
) {
    // Each upstream is collected once and shared by the presenters that read it.
    private val devices = deps.devices.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    private val transfers = deps.transfers.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    private val profile = deps.profile.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val radar =
        RadarPresenter(
            scope = scope,
            devices = devices,
            transfers = transfers,
            radio = deps.radio,
            visibility = deps.visibility,
            self = profile,
            initialSelf = deps.initialProfile,
            actions = TransferStartActions(deps.radarActions),
            monotonicClock = deps.monotonicClock,
            wallClock = deps.wallClock,
            received = deps.received,
        )
    val incoming = IncomingPresenter(scope, deps.offers, AcceptActions(deps.incomingActions), deps.monotonicClock)
    val picker = FilePickerPresenter(scope, deps.media, deps.features)
    val showQr = ShowQrPresenter(scope, deps.myCode, profile, deps.wallClock)
    val browserApproval = BrowserApprovalPresenter(scope)
    val permissions = PermissionGate(deps.permissions)
    val dashboard =
        DashboardPresenter(
            live = LivePresenter(scope, transfers, deps.liveActions),
            history = HistoryPresenter(scope, deps.history, deps.calendar, deps.wallClock, deps.computeContext),
            devices = DevicesPresenter(scope, deps.trustedDevices, deps.wallClock),
            stats = StatsPresenter(scope, deps.stats),
            settings = SettingsPresenter(scope, deps.settings, deps.initialSettings),
        )
    val onboarding: OnboardingPresenter? =
        deps.onboarding?.let { config ->
            OnboardingPresenter(config.deviceName, config.brand, FinishOnboarding(config.actions))
        }

    /** A received installer waiting for the user to confirm opening it (F‑D5). */
    private sealed interface PendingOpen {
        val ui: InstallerWarningUi

        class Tray(
            val fileId: String,
            override val ui: InstallerWarningUi,
        ) : PendingOpen

        class History(
            val transferId: String,
            val fileId: String,
            override val ui: InstallerWarningUi,
        ) : PendingOpen
    }

    private val screenState = MutableStateFlow(if (onboarding != null) Screen.ONBOARDING else Screen.RADAR)
    private val sheetState = MutableStateFlow<RadarSheet?>(null)
    private val scanState = MutableStateFlow(ScanStatus.SCANNING)
    private val pendingOpen = MutableStateFlow<PendingOpen?>(null)
    private var scanSuccess: Job? = null
    private var notificationsAsked = false
    private var nearbyAsked = false
    private var batteryAsked = false
    private var batteryReoffered = false

    val screen: StateFlow<Screen> = screenState.asStateFlow()
    val radarSheet: StateFlow<RadarSheet?> = sheetState.asStateFlow()
    val scanStatus: StateFlow<ScanStatus> = scanState.asStateFlow()

    /** "Open this app installer?" on screen, or null (F‑D5). */
    val installerWarning: StateFlow<InstallerWarningUi?> =
        pendingOpen.map { it?.ui }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The language chosen in Settings (decision 9); the host applies it at once (F‑G5). */
    val language: StateFlow<AppLanguage> =
        dashboard.settings.state
            .map { it.values.language }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, deps.initialSettings.language)

    /**
     * Whether the host should keep the screen on (Settings "Keep screen awake", design §6): while the setting is on
     * and a transfer is actually moving. A paused transfer or one waiting hours for its peer does not count.
     */
    val keepScreenOn: StateFlow<Boolean> =
        combine(dashboard.settings.state, dashboard.live.state) { s, rows ->
            s.values.keepScreenAwake && rows.any { !it.paused && it.stage != TransferStage.WAITING_FOR_PEER }
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Whether [back] would do something (Android enables its back callback with this). */
    val canGoBack: StateFlow<Boolean> =
        combine(
            combine(screenState, sheetState, pendingOpen) { s, sheet, open -> Triple(s, sheet, open) },
            radar.state,
            dashboard.history.state,
            dashboard.devices.state,
        ) { (s, sheet, open), r, h, d ->
            s == Screen.DASHBOARD || s == Screen.SCAN || sheet != null || open != null || r.cancelConfirm != null ||
                r.senderPairing != null || h.detail != null || h.confirmClear || d.renaming != null || d.forgetting != null
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        if (screenState.value == Screen.RADAR) askNearby()
    }

    /** Bubble tap: sends attached files, or opens the picker (design §4.1, §4.3). */
    fun onBubbleTap(key: String) {
        when (radar.onBubbleTapped(key)) {
            BubbleTapResult.OPEN_PICKER -> {
                openPicker(
                    PickerTarget.Device(
                        key,
                        radar.state.value.bubbles.firstOrNull {
                            it.key == key
                        }?.name,
                    ),
                )
            }

            BubbleTapResult.SENT, BubbleTapResult.BUSY -> {
                Unit
            }
        }
    }

    private fun openPicker(target: PickerTarget) {
        closeSheet(keepSelection = target is PickerTarget.Device)
        picker.open(target)
        sheetState.value = RadarSheet.PICKER
        scope.launch { permissions.ensure(DropPermission.MEDIA) }
    }

    /** The picker's Send button: a send to the bubble, files added to a transfer, or the browser page started. */
    fun sendPicked() {
        val target = picker.target ?: return
        val files = picker.selection()
        if (files.items.isEmpty()) return
        picker.close()
        sheetState.value = null
        when (target) {
            is PickerTarget.Device -> {
                radar.send(target.key, files)
            }

            is PickerTarget.Transfer -> {
                dashboard.live.addFiles(target.transferId, files)
            }

            PickerTarget.Browser -> {
                openSheet(RadarSheet.SHOW_QR)
                showQr.startBrowserShare(files)
            }
        }
    }

    /** Files from the system document picker (the Files tab). */
    fun onFilesPicked(items: List<PickedItem>) = picker.addFiles(items)

    /** The picker's "Browse files" (Files tab). */
    fun platformBrowseFiles() = deps.platform.browseFiles()

    /** The Photos tab's "Allow" after media access was refused. */
    fun askMediaAgain() {
        scope.launch { permissions.ensure(DropPermission.MEDIA) }
    }

    /**
     * The Live tab's "Add files" (F‑C5): the picker opens over the dashboard for that transfer, and its button queues
     * the picked files into it ([LiveActions.addFiles]).
     */
    fun openAddFiles(transferId: String) {
        val row = dashboard.live.state.value.firstOrNull { it.transferId == transferId } ?: return
        if (!row.canAddFiles) return
        openPicker(PickerTarget.Transfer(transferId, row.peerName))
    }

    /**
     * "Send to a computer without the app" on the "Show my code" sheet (N15): the share-sheet files if some are
     * attached, else the picker opens for the browser page and its button starts it.
     */
    fun startBrowserShare() {
        val attached = radar.takeAttachment()
        if (attached != null) {
            showQr.startBrowserShare(attached)
        } else {
            openPicker(PickerTarget.Browser)
        }
    }

    /** Cancel on the Live tab: like the bubble's ×, it asks first past 100 MB (design §4.2). */
    fun cancelTransfer(transferId: String) = radar.onCancelTapped(transferId)

    /** Tray tap (design §5.2); files never open by themselves, and installers ask first (F‑D5). */
    fun platformOpenReceived(fileId: String) {
        val item = radar.state.value.tray.firstOrNull { it.id == fileId }
        if (item != null && item.installer) {
            pendingOpen.value = PendingOpen.Tray(fileId, InstallerWarningUi(item.name, item.senderName))
            return
        }
        deps.platform.openReceivedFile(fileId)
    }

    /** A file in the History detail sheet: received installers ask first (F‑D5). */
    fun openHistoryFile(
        transferId: String,
        fileId: String,
    ) {
        val detail = dashboard.history.state.value.detail
        val entry = detail?.row?.entry
        val file = detail?.files?.firstOrNull { it.id == fileId }
        if (entry != null && entry.id == transferId && file != null && entry.direction == Direction.RECEIVE &&
            InstallerFiles.isInstaller(file.name, file.mime, file.kind)
        ) {
            pendingOpen.value = PendingOpen.History(transferId, fileId, InstallerWarningUi(file.name, entry.peerName))
            return
        }
        dashboard.history.openFile(transferId, fileId)
    }

    /** "Open anyway" on the installer warning. */
    fun confirmOpenInstaller() {
        val open = pendingOpen.value ?: return
        pendingOpen.value = null
        when (open) {
            is PendingOpen.Tray -> deps.platform.openReceivedFile(open.fileId)
            is PendingOpen.History -> dashboard.history.openFile(open.transferId, open.fileId)
        }
    }

    fun dismissInstallerWarning() {
        pendingOpen.value = null
    }

    /** Tray long-press: share onward. */
    fun platformShareReceived(fileId: String) = deps.platform.shareReceivedFile(fileId)

    fun platformOpenFolder() = deps.platform.openReceivedFolder()

    /**
     * Files shared into the app (design §4.3). [directTarget] is a direct-share device (F‑C3) named [directTargetName];
     * [shareId] identifies the share so its host can [releaseShare] it. Whatever sheet was open closes properly (the
     * picker lowers its bubble and stops reading the gallery, "Show my code" stops refreshing).
     */
    fun onShareIntent(
        files: AttachedFiles,
        directTarget: String? = null,
        directTargetName: String? = null,
        shareId: String? = null,
    ) {
        radar.attach(files, directTarget, directTargetName, shareId)
        closeSheet()
        if (screenState.value != Screen.ONBOARDING) {
            cancelScanSuccess()
            screenState.value = Screen.RADAR
        }
    }

    /** Whether the files of share [shareId] are still attached (a recreated activity must not attach them twice). */
    fun holdsShare(shareId: String): Boolean = radar.holdsShare(shareId)

    /**
     * The activity that received share [shareId] is gone for good; Android revoked its read grants, so the files can
     * no longer be read and are dropped (with a direct target waiting for them).
     */
    fun releaseShare(shareId: String) = radar.releaseShare(shareId)

    /** Opens [sheet] in place of any other; the picker opens only through a bubble ([onBubbleTap]). */
    fun openSheet(sheet: RadarSheet) {
        if (sheet == RadarSheet.PICKER || sheetState.value == sheet) return
        closeSheet()
        if (sheet == RadarSheet.SHOW_QR) showQr.open()
        sheetState.value = sheet
    }

    fun closeSheet() = closeSheet(keepSelection = false)

    private fun closeSheet(keepSelection: Boolean) {
        when (sheetState.value) {
            RadarSheet.PICKER -> {
                picker.close()
                if (!keepSelection) radar.select(null)
            }

            RadarSheet.SHOW_QR -> {
                showQr.close()
            }

            else -> {}
        }
        sheetState.value = null
    }

    fun onNoticeAction(notice: RadarNotice) {
        when (notice) {
            RadarNotice.PERMISSION_MISSING -> scope.launch { permissions.ensure(DropPermission.NEARBY) }
            RadarNotice.BLUETOOTH_OFF -> deps.platform.turnOnBluetooth()
            RadarNotice.WIFI_OFF -> deps.platform.openWifiPanel()
            RadarNotice.HIDDEN -> openSheet(RadarSheet.VISIBILITY)
            RadarNotice.NO_DEVICES -> Unit
        }
    }

    /** The dashboard replaces the radar; the tray clears (design §5.2). */
    fun openDashboard(tab: DashboardTab? = null) {
        closeSheet()
        tab?.let { dashboard.selectTab(it) }
        radar.clearTray()
        screenState.value = Screen.DASHBOARD
    }

    fun openScanner() {
        closeSheet()
        cancelScanSuccess()
        scanState.value = ScanStatus.SCANNING
        screenState.value = Screen.SCAN
        scope.launch {
            if (!permissions.ensure(DropPermission.CAMERA)) scanState.value = ScanStatus.NO_CAMERA_PERMISSION
        }
    }

    /** Retries the camera from the scanner's "Allow" button. */
    fun allowCamera() {
        scope.launch {
            scanState.value = if (permissions.ensure(DropPermission.CAMERA)) ScanStatus.SCANNING else ScanStatus.NO_CAMERA_PERMISSION
        }
    }

    /**
     * The platform scanner recognised a verified code for the device with radar key [deviceKey] (design §4.4): the
     * viewfinder turns the success colour and the haptic plays ([ScanStatus.SUCCESS] for [SCAN_SUCCESS_HOLD_MILLIS]),
     * then the radar returns with that device selected and the picker open, or the attached files sent.
     */
    fun onCodeScanned(deviceKey: String) {
        if (screenState.value != Screen.SCAN || scanState.value == ScanStatus.SUCCESS) return
        scanState.value = ScanStatus.SUCCESS
        scanSuccess =
            scope.launch {
                delay(SCAN_SUCCESS_HOLD_MILLIS)
                scanSuccess = null
                if (screenState.value != Screen.SCAN) return@launch
                screenState.value = Screen.RADAR
                onBubbleTap(deviceKey)
            }
    }

    private fun cancelScanSuccess() {
        scanSuccess?.cancel()
        scanSuccess = null
    }

    /** The scanner read a code it cannot use ([ScanStatus.EXPIRED] or [ScanStatus.INVALID]). */
    fun onScanProblem(status: ScanStatus) {
        if (scanState.value == ScanStatus.SUCCESS) return
        scanState.value = status
    }

    /** "Not now" on the sender's code sheet: hidden for [transferId] without trusting the device (F‑B3). */
    fun pairLater(transferId: String) = radar.pairLater(transferId)

    /** Handles Back; false when there is nothing to go back from (the platform then leaves the app). */
    fun back(): Boolean {
        val history = dashboard.history.state.value
        val devices = dashboard.devices.state.value
        val radarState = radar.state.value
        val pairing = radarState.senderPairing
        when {
            // The global sheets first, in the order they stack (DropApp shows the most urgent one).
            pendingOpen.value != null -> {
                dismissInstallerWarning()
            }

            pairing != null -> {
                radar.pairLater(pairing.transferId)
            }

            radarState.cancelConfirm != null -> {
                radar.dismissCancel()
            }

            sheetState.value != null -> {
                closeSheet()
            }

            screenState.value == Screen.SCAN -> {
                cancelScanSuccess()
                screenState.value = Screen.RADAR
            }

            screenState.value == Screen.DASHBOARD && history.detail != null -> {
                dashboard.history.closeDetail()
            }

            screenState.value == Screen.DASHBOARD && history.confirmClear -> {
                dashboard.history.dismissClear()
            }

            screenState.value == Screen.DASHBOARD && devices.renaming != null -> {
                dashboard.devices.cancelRename()
            }

            screenState.value == Screen.DASHBOARD && devices.forgetting != null -> {
                dashboard.devices.cancelForget()
            }

            screenState.value == Screen.DASHBOARD -> {
                screenState.value = Screen.RADAR
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** The app's UI came to the front (Android: the first activity started). */
    fun onHostStarted() {
        showQr.resume()
    }

    /** No UI is visible any more (Android: the last activity stopped): "Show my code" stops ticking and re-signing. */
    fun onHostStopped() {
        showQr.pause()
    }

    /**
     * A transfer was killed in the background (design §7 step 3: the background step is "re-offered once"): asks for
     * the battery-optimisation exemption again, at most once per process. The app layer calls it at most once per
     * install.
     */
    fun reofferBackgroundRunning() {
        if (batteryReoffered) return
        batteryReoffered = true
        scope.launch { permissions.ensure(DropPermission.BATTERY) }
    }

    private fun askNearby() {
        if (nearbyAsked) return
        nearbyAsked = true
        scope.launch { permissions.ensure(DropPermission.NEARBY) }
    }

    private fun askNotificationsOnce() {
        if (notificationsAsked) return
        notificationsAsked = true
        scope.launch { permissions.ensure(DropPermission.NOTIFICATIONS) }
    }

    /** Architecture §11: the battery-optimisation exemption, "onboarding, once, skippable", on every device. */
    private fun askBatteryOnce() {
        if (batteryAsked) return
        batteryAsked = true
        scope.launch { permissions.ensure(DropPermission.BATTERY) }
    }

    /**
     * Every send, whatever starts it (the picker, a bubble tap with shared files, a direct share, a scanned code):
     * Android 12's location permission for Wi‑Fi Direct discovery first (architecture §11: "First send on Android
     * 12"; the send goes ahead either way, other links do not need it), then notifications on the first transfer.
     */
    private inner class TransferStartActions(
        private val delegate: RadarActions,
    ) : RadarActions by delegate {
        override fun send(
            deviceKey: String,
            files: AttachedFiles,
        ) {
            scope.launch {
                permissions.ensure(DropPermission.LOCATION_FOR_WIFI_DIRECT)
                delegate.send(deviceKey, files)
                askNotificationsOnce()
            }
        }
    }

    /** Asks for notifications on the first accepted Offer. */
    private inner class AcceptActions(
        private val delegate: IncomingActions,
    ) : IncomingActions by delegate {
        override fun accept(
            offerId: String,
            alwaysAccept: Boolean,
        ) {
            delegate.accept(offerId, alwaysAccept)
            askNotificationsOnce()
        }
    }

    /** Moves to the radar when onboarding finishes, then asks for Nearby (the radar's first open) and the battery exemption. */
    private inner class FinishOnboarding(
        private val delegate: OnboardingActions,
    ) : OnboardingActions by delegate {
        override fun finish(nickname: String) {
            delegate.finish(nickname)
            screenState.value = Screen.RADAR
            askNearby()
            askBatteryOnce()
        }
    }

    companion object {
        /** How long the scanner shows its success state (colour, haptic) before the radar returns (design §4.4). */
        const val SCAN_SUCCESS_HOLD_MILLIS: Long = 300
    }
}
