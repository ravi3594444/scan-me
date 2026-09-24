package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadioState
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.VisibilityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Platform actions the shared UI triggers but cannot perform itself (radio panels, opening files, pickers). */
interface PlatformActions {
    /** Android: `BluetoothAdapter.ACTION_REQUEST_ENABLE` (F‑A6: the user never leaves the app). */
    fun turnOnBluetooth()

    /** Android: `Settings.Panel.ACTION_WIFI` (apps cannot switch Wi‑Fi on themselves on Android 10+). */
    fun openWifiPanel()

    /** Opens a received file with the system (never automatically, F‑D5). */
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
 * Every port the shared UI reads or calls (the app layers implement them over the engine and core/data). Flows are
 * collected on the controller's scope.
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
            )
    }
}

/** Top-level screens (design §2). */
enum class Screen { ONBOARDING, RADAR, DASHBOARD, SCAN }

/** Sheets over the radar (design §2). */
enum class RadarSheet { PICKER, SHOW_QR, VISIBILITY, OVERFLOW }

/**
 * Wires the presenters of the shared UI to [DropDependencies] and holds navigation (design §2): onboarding, the radar
 * with its sheets, the dashboard and the scanner. Permissions are asked just in time through [permissions] (F‑I1):
 * Nearby when the radar first opens, media and (Android 12) location when a picker first opens, notifications on
 * the first transfer, the camera when the scanner opens. Main-thread confined.
 */
class DropAppController(
    private val scope: CoroutineScope,
    private val deps: DropDependencies,
) {
    val radar =
        RadarPresenter(
            scope = scope,
            devices = deps.devices,
            transfers = deps.transfers,
            radio = deps.radio,
            visibility = deps.visibility,
            self = deps.profile,
            initialSelf = deps.initialProfile,
            actions = TransferStartActions(deps.radarActions),
            monotonicClock = deps.monotonicClock,
            wallClock = deps.wallClock,
            received = deps.received,
        )
    val incoming = IncomingPresenter(scope, deps.offers, AcceptActions(deps.incomingActions), deps.monotonicClock)
    val picker = FilePickerPresenter(scope, deps.media, deps.features)
    val showQr = ShowQrPresenter(scope, deps.myCode, deps.profile, deps.wallClock)
    val browserApproval = BrowserApprovalPresenter(scope)
    val permissions = PermissionGate(deps.permissions)
    val dashboard =
        DashboardPresenter(
            live = LivePresenter(scope, deps.transfers, deps.liveActions),
            history = HistoryPresenter(scope, deps.history, deps.calendar, deps.wallClock),
            devices = DevicesPresenter(scope, deps.trustedDevices, deps.wallClock),
            stats = StatsPresenter(scope, deps.stats),
            settings = SettingsPresenter(scope, deps.settings, deps.initialSettings),
        )
    val onboarding: OnboardingPresenter? =
        deps.onboarding?.let { config ->
            OnboardingPresenter(config.deviceName, config.brand, FinishOnboarding(config.actions))
        }

    private val screenState = MutableStateFlow(if (onboarding != null) Screen.ONBOARDING else Screen.RADAR)
    private val sheetState = MutableStateFlow<RadarSheet?>(null)
    private val scanState = MutableStateFlow(ScanStatus.SCANNING)
    private var notificationsAsked = false
    private var nearbyAsked = false

    val screen: StateFlow<Screen> = screenState.asStateFlow()
    val radarSheet: StateFlow<RadarSheet?> = sheetState.asStateFlow()
    val scanStatus: StateFlow<ScanStatus> = scanState.asStateFlow()

    /** Whether [back] would do something (Android enables its back callback with this). */
    val canGoBack: StateFlow<Boolean> =
        combine(screenState, sheetState, radar.state, dashboard.history.state, dashboard.devices.state) { s, sheet, r, h, d ->
            s == Screen.DASHBOARD || s == Screen.SCAN || sheet != null || r.cancelConfirm != null || h.detail != null ||
                h.confirmClear || d.renaming != null || d.forgetting != null
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        if (screenState.value == Screen.RADAR) askNearby()
    }

    /** Bubble tap: sends attached files, or opens the picker (design §4.1, §4.3). */
    fun onBubbleTap(key: String) {
        when (radar.onBubbleTapped(key)) {
            BubbleTapResult.OPEN_PICKER -> openPicker(key)
            BubbleTapResult.SENT, BubbleTapResult.BUSY -> Unit
        }
    }

    private fun openPicker(key: String) {
        val name = radar.state.value.bubbles.firstOrNull { it.key == key }?.name
        picker.open(key, name)
        sheetState.value = RadarSheet.PICKER
        scope.launch { permissions.ensure(DropPermission.MEDIA) }
    }

    /** The picker's Send button. */
    fun sendPicked() {
        val key = picker.targetKey ?: return
        val files = picker.selection()
        if (files.items.isEmpty()) return
        picker.close()
        sheetState.value = null
        scope.launch {
            permissions.ensure(DropPermission.LOCATION_FOR_WIFI_DIRECT)
            radar.send(key, files)
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

    /** Tray tap (design §5.2); files never open by themselves (F‑D5). */
    fun platformOpenReceived(fileId: String) = deps.platform.openReceivedFile(fileId)

    /** Tray long-press: share onward. */
    fun platformShareReceived(fileId: String) = deps.platform.shareReceivedFile(fileId)

    fun platformOpenFolder() = deps.platform.openReceivedFolder()

    /** Files shared into the app (design §4.3); [directTarget] is a direct-share device (F‑C3). */
    fun onShareIntent(
        files: AttachedFiles,
        directTarget: String? = null,
    ) {
        radar.attach(files, directTarget)
        if (screenState.value != Screen.ONBOARDING) screenState.value = Screen.RADAR
        sheetState.value = null
    }

    fun openSheet(sheet: RadarSheet) {
        if (sheet == RadarSheet.PICKER) return
        if (sheet == RadarSheet.SHOW_QR) showQr.open()
        sheetState.value = sheet
    }

    fun closeSheet() {
        when (sheetState.value) {
            RadarSheet.PICKER -> {
                picker.close()
                radar.select(null)
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
     * The platform scanner recognised a verified code for the device with radar key [deviceKey] (design §4.4): back to
     * the radar with that device selected and the picker open, or the attached files sent.
     */
    fun onCodeScanned(deviceKey: String) {
        scanState.value = ScanStatus.SUCCESS
        screenState.value = Screen.RADAR
        onBubbleTap(deviceKey)
    }

    /** The scanner read a code it cannot use ([ScanStatus.EXPIRED] or [ScanStatus.INVALID]). */
    fun onScanProblem(status: ScanStatus) {
        scanState.value = status
    }

    /** Handles Back; false when there is nothing to go back from (the platform then leaves the app). */
    fun back(): Boolean {
        val history = dashboard.history.state.value
        val devices = dashboard.devices.state.value
        when {
            radar.state.value.cancelConfirm != null -> radar.dismissCancel()
            sheetState.value != null -> closeSheet()
            screenState.value == Screen.SCAN -> screenState.value = Screen.RADAR
            screenState.value == Screen.DASHBOARD && history.detail != null -> dashboard.history.closeDetail()
            screenState.value == Screen.DASHBOARD && history.confirmClear -> dashboard.history.dismissClear()
            screenState.value == Screen.DASHBOARD && devices.renaming != null -> dashboard.devices.cancelRename()
            screenState.value == Screen.DASHBOARD && devices.forgetting != null -> dashboard.devices.cancelForget()
            screenState.value == Screen.DASHBOARD -> screenState.value = Screen.RADAR
            else -> return false
        }
        return true
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

    /** Asks for notifications on the first send (architecture §11: "First transfer"). */
    private inner class TransferStartActions(
        private val delegate: RadarActions,
    ) : RadarActions by delegate {
        override fun send(
            deviceKey: String,
            files: AttachedFiles,
        ) {
            delegate.send(deviceKey, files)
            askNotificationsOnce()
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

    /** Moves to the radar when onboarding finishes, then asks for Nearby (the radar's first open). */
    private inner class FinishOnboarding(
        private val delegate: OnboardingActions,
    ) : OnboardingActions by delegate {
        override fun finish(nickname: String) {
            delegate.finish(nickname)
            screenState.value = Screen.RADAR
            askNearby()
        }
    }
}
