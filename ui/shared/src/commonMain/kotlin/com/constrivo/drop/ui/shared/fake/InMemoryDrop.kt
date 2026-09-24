package com.constrivo.drop.ui.shared.fake

import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.ClearPartialsResult
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.RadioState
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import com.constrivo.drop.ui.shared.model.VisibilityState
import com.constrivo.drop.ui.shared.presenter.DeviceSource
import com.constrivo.drop.ui.shared.presenter.HistorySource
import com.constrivo.drop.ui.shared.presenter.IncomingActions
import com.constrivo.drop.ui.shared.presenter.LiveActions
import com.constrivo.drop.ui.shared.presenter.MediaLibrary
import com.constrivo.drop.ui.shared.presenter.MyCode
import com.constrivo.drop.ui.shared.presenter.MyCodeSource
import com.constrivo.drop.ui.shared.presenter.OnboardingActions
import com.constrivo.drop.ui.shared.presenter.PlatformActions
import com.constrivo.drop.ui.shared.presenter.RadarActions
import com.constrivo.drop.ui.shared.presenter.SettingsSource
import com.constrivo.drop.ui.shared.presenter.StatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory implementation of every port of the shared UI, for tests, previews and the unwired app shells (before
 * the app layers connect the engine and core/data). State lives in public [MutableStateFlow]s a test can drive; every
 * action is recorded in [calls] as a short string (`"send:t:A:3"`, `"accept:o1:true"`), in order.
 *
 * Settings changes are applied to [settings] at once (F‑G5: no restart), and a visibility change also updates
 * [visibility], as the real repository does.
 */
class InMemoryDrop(
    self: SelfProfile = SelfProfile(nickname = "", deviceKey = "self"),
    settingsValues: SettingsValues = defaultSettings(self.nickname),
    /** The clock the "Everyone for 10 min" window ends on (the same one the controller's presenters use). */
    private val wallClock: WallClock = SystemWallClock,
) : RadarActions,
    IncomingActions,
    LiveActions,
    HistorySource,
    DeviceSource,
    StatsSource,
    SettingsSource,
    MyCodeSource,
    MediaLibrary,
    PlatformActions,
    OnboardingActions {
    val devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val transfers = MutableStateFlow<List<TransferSnapshot>>(emptyList())
    val offers = MutableStateFlow<List<IncomingOffer>>(emptyList())
    val receivedFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 64)
    val radio = MutableStateFlow(RadioState())
    val visibility = MutableStateFlow(VisibilityState(settingsValues.visibility))
    val profile = MutableStateFlow(self)
    val historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val historyFiles = MutableStateFlow<Map<String, List<HistoryFile>>>(emptyMap())
    val trustedDevices = MutableStateFlow<List<TrustedDeviceEntry>>(emptyList())
    val statsValues = MutableStateFlow(StatsSnapshot.EMPTY)
    val settingsState = MutableStateFlow(settingsValues)
    val mediaItems = MutableStateFlow<List<PickedItem>>(emptyList())
    val mediaAccessState = MutableStateFlow(true)
    val appItems = MutableStateFlow<List<PickedItem>>(emptyList())
    val browserShareState = MutableStateFlow<BrowserShareState>(BrowserShareState.Idle)

    /** How often the picker asked for more media ([requestMore]). */
    var mediaPageRequests: Int = 0
        private set

    /** The code "Show my code" displays; null makes [current] fail (no code yet). */
    var code: MyCode? = null

    /** What [clearPartialFiles] returns. */
    var clearResult: ClearPartialsResult = ClearPartialsResult(0, 0)

    private val log = MutableStateFlow<List<String>>(emptyList())

    /** Every action, in order. */
    val calls: List<String> get() = log.value

    private fun record(call: String) = log.update { it + call }

    // RadarActions
    override fun send(
        deviceKey: String,
        files: AttachedFiles,
    ) = record("send:$deviceKey:${files.count}")

    override fun cancel(transferId: String) = record("cancel:$transferId")

    override fun confirmPairing(transferId: String) = record("pairSend:$transferId")

    // IncomingActions
    override fun accept(
        offerId: String,
        alwaysAccept: Boolean,
    ) {
        record("accept:$offerId:$alwaysAccept")
        offers.update { list -> list.filterNot { it.id == offerId } }
    }

    override fun decline(offerId: String) {
        record("decline:$offerId")
        offers.update { list -> list.filterNot { it.id == offerId } }
    }

    override fun confirmCode(offerId: String) = record("pairReceive:$offerId")

    override fun timedOut(offerId: String) {
        record("timeout:$offerId")
        offers.update { list -> list.filterNot { it.id == offerId } }
    }

    // LiveActions
    override fun pause(transferId: String) = record("pause:$transferId")

    override fun resume(transferId: String) = record("resume:$transferId")

    override fun addFiles(
        transferId: String,
        files: AttachedFiles,
    ) = record("add:$transferId:${files.count}")

    // HistorySource
    override val history: Flow<List<HistoryEntry>> get() = historyEntries

    override suspend fun files(transferId: String): List<HistoryFile> = historyFiles.value[transferId].orEmpty()

    override fun openFile(
        transferId: String,
        fileId: String,
    ) = record("openFile:$transferId:$fileId")

    override fun resend(transferId: String) = record("resend:$transferId")

    override fun clear() {
        record("clearHistory")
        historyEntries.value = emptyList()
    }

    // DeviceSource
    override val trusted: Flow<List<TrustedDeviceEntry>> get() = trustedDevices

    override fun rename(
        deviceId: String,
        name: String,
    ) {
        record("rename:$deviceId:$name")
        trustedDevices.update { list -> list.map { if (it.id == deviceId) it.copy(name = name) else it } }
    }

    override fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ) {
        record("autoAccept:$deviceId:$enabled")
        trustedDevices.update { list -> list.map { if (it.id == deviceId) it.copy(autoAccept = enabled) else it } }
    }

    override fun forget(deviceId: String) {
        record("forget:$deviceId")
        trustedDevices.update { list -> list.filterNot { it.id == deviceId } }
    }

    // StatsSource
    override val stats: Flow<StatsSnapshot> get() = statsValues

    override fun shareStatsCard() = record("shareStats")

    // SettingsSource
    override val settings: Flow<SettingsValues> get() = settingsState

    override fun setVisibility(mode: Visibility) {
        record("visibility:$mode")
        settingsState.update { it.copy(visibility = mode) }
        // Like core/data's VisibilityPreference: the 10-minute window ends on the wall clock, then Trusted only.
        val end = if (mode == Visibility.EVERYONE_TEN_MINUTES) wallClock.nowMillis() + TEN_MINUTES_MILLIS else null
        visibility.value = VisibilityState(mode, expiresAtMillis = end)
    }

    override fun setPrefer5Ghz(enabled: Boolean) = setting("prefer5Ghz:$enabled") { it.copy(prefer5Ghz = enabled) }

    override fun setKeepScreenAwake(enabled: Boolean) = setting("keepAwake:$enabled") { it.copy(keepScreenAwake = enabled) }

    override fun setBundleSmallFiles(enabled: Boolean) = setting("bundle:$enabled") { it.copy(bundleSmallFiles = enabled) }

    override fun pickSaveLocation() = record("pickSaveLocation")

    override suspend fun clearPartialFiles(): ClearPartialsResult {
        record("clearPartials")
        return clearResult
    }

    override fun setNickname(nickname: String) {
        setting("nickname:$nickname") { it.copy(nickname = nickname) }
        profile.update { it.copy(nickname = nickname) }
    }

    override fun pickAvatar() = record("pickAvatar")

    override fun removeAvatar() = setting("removeAvatar") { it.copy(avatar = null) }

    override fun setLanguage(language: AppLanguage) = setting("language:$language") { it.copy(language = language) }

    override fun setCrashReports(enabled: Boolean) = setting("crashReports:$enabled") { it.copy(crashReports = enabled) }

    override fun setHaptics(enabled: Boolean) = setting("haptics:$enabled") { it.copy(haptics = enabled) }

    private fun setting(
        call: String,
        change: (SettingsValues) -> SettingsValues,
    ) {
        record(call)
        settingsState.update(change)
    }

    // MyCodeSource
    override suspend fun current(): MyCode = code ?: throw IllegalStateException("no code configured")

    override val browserShare: Flow<BrowserShareState> get() = browserShareState

    override fun startBrowserShare(files: AttachedFiles) {
        record("startBrowserShare:${files.count}")
        browserShareState.value = BrowserShareState.Starting
    }

    override fun stopBrowserShare() {
        record("stopBrowserShare")
        browserShareState.value = BrowserShareState.Idle
    }

    // MediaLibrary
    override val media: Flow<List<PickedItem>> get() = mediaItems
    override val mediaAccess: Flow<Boolean> get() = mediaAccessState
    override val apps: Flow<List<PickedItem>> get() = appItems

    override fun requestMore() {
        mediaPageRequests++
    }

    // PlatformActions
    override fun turnOnBluetooth() = record("turnOnBluetooth")

    override fun openWifiPanel() = record("openWifiPanel")

    override fun openReceivedFile(fileId: String) = record("openReceived:$fileId")

    override fun shareReceivedFile(fileId: String) = record("shareReceived:$fileId")

    override fun openReceivedFolder() = record("openFolder")

    override fun browseFiles() = record("browseFiles")

    // OnboardingActions
    override fun finish(nickname: String) {
        record("onboarded:$nickname")
        setNickname(nickname)
    }

    override fun openBrandSettings(brand: OemBrand) = record("brandSettings:$brand")

    companion object {
        private const val TEN_MINUTES_MILLIS = 10 * 60_000L

        fun defaultSettings(nickname: String): SettingsValues =
            SettingsValues(
                visibility = VisibilityState.DEFAULT.mode,
                prefer5Ghz = true,
                keepScreenAwake = false,
                bundleSmallFiles = true,
                saveLocationLabel = null,
                nickname = nickname,
                avatar = null,
                language = AppLanguage.SYSTEM,
                crashReports = false,
                haptics = true,
                appVersion = "0.1.0",
            )
    }
}
