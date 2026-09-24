package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.node.DesktopNode
import com.constrivo.drop.platform.desktop.node.NodeEvent
import com.constrivo.drop.platform.desktop.node.NodeOffer
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
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import com.constrivo.drop.ui.shared.presenter.DayCalendar
import com.constrivo.drop.ui.shared.presenter.DeviceSource
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.HistorySource
import com.constrivo.drop.ui.shared.presenter.IncomingActions
import com.constrivo.drop.ui.shared.presenter.LiveActions
import com.constrivo.drop.ui.shared.presenter.MediaLibrary
import com.constrivo.drop.ui.shared.presenter.MyCode
import com.constrivo.drop.ui.shared.presenter.MyCodeSource
import com.constrivo.drop.ui.shared.presenter.OnboardingActions
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.ui.shared.presenter.PermissionController
import com.constrivo.drop.ui.shared.presenter.PlatformActions
import com.constrivo.drop.ui.shared.presenter.RadarActions
import com.constrivo.drop.ui.shared.presenter.SettingsSource
import com.constrivo.drop.ui.shared.presenter.StatsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared UI's ports over a running [DesktopNode] (the desktop counterpart of `ui/android`'s `AppGraph` ports): the
 * radar shows real LAN peers, History, trusted devices, Stats and Settings read and write `core/data`, and the
 * platform actions open files and folders through [host]. Actions never block the UI thread: they start work in
 * [scope] (on [io] where they touch the disk).
 *
 * Not in the engine yet, so not offered: pausing a transfer and adding files to a running one (the Live tab shows
 * neither), avatars (desktops show initials), sharing the Stats card.
 */
class DesktopPorts(
    private val node: DesktopNode,
    private val scope: CoroutineScope,
    private val host: DesktopHost,
    private val strings: () -> DesktopStrings,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : RadarActions,
    IncomingActions,
    LiveActions,
    HistorySource,
    DeviceSource,
    StatsSource,
    SettingsSource,
    MyCodeSource,
    PlatformActions,
    OnboardingActions {
    private val receivedPaths = ConcurrentHashMap<String, Path>()

    /** Each waiting offer's card content, decoded once (its previews are pictures from a stranger, N12). */
    private val offerCards = ConcurrentHashMap<String, IncomingOffer>()

    /** Where the Files tab's "Browse files" result goes (the controller's `onFilesPicked`); set by the app. */
    @Volatile
    var onFilesChosen: (List<PickedItem>) -> Unit = {}

    /** Every port, for `DropAppController`; [onboarding] is null once a nickname was chosen. */
    fun dependencies(onboarding: OnboardingConfig?): DropDependencies {
        val nickname = node.nickname.value
        return DropDependencies(
            devices = node.devices,
            transfers = node.transfers.map { list -> list.map(PortMappers::snapshot) },
            // Off the UI thread, and once per offer: the offer list changes whenever any offer comes or goes.
            offers = node.offers.map(::incomingOffers).flowOn(compute),
            received = node.received.onEach { receivedPaths[it.id] = it.path }.map(PortMappers::received),
            radio = flowOf(radioState()),
            visibility = node.visibility.map(PortMappers::visibility),
            profile = node.nickname.map { SelfProfile(it, node.selfDeviceId) },
            initialProfile = SelfProfile(nickname, node.selfDeviceId),
            radarActions = this,
            incomingActions = this,
            liveActions = this,
            history = this,
            trustedDevices = this,
            stats = this,
            settings = this,
            initialSettings = node.settings.value?.let { PortMappers.settings(it, nickname, appVersion) } ?: defaultSettings(nickname),
            myCode = this,
            media = MediaLibrary.Empty,
            permissions = PermissionController.AllGranted,
            platform = this,
            calendar = DayCalendar.System,
            wallClock = SystemWallClock,
            monotonicClock = node.config.monotonicClock,
            onboarding = onboarding,
        )
    }

    /** The cards of [offers]: decoded once per offer id, forgotten once the offer is gone. */
    fun incomingOffers(offers: List<NodeOffer>): List<IncomingOffer> {
        val ids = offers.mapTo(HashSet()) { it.id }
        offerCards.keys.retainAll(ids)
        return offers.map { offer -> offerCards.getOrPut(offer.id) { PortMappers.incoming(offer) } }
    }

    /**
     * What the radar's notices need (design §8.1, §9). A desktop without Bluetooth has no "Turn on Bluetooth" state,
     * and a desktop off the network gets the shell's own banner ([DesktopBanner.NoNetwork]) instead of the phone's
     * "Wi‑Fi is off · Turn on", whose button a desktop app cannot honour; so Wi‑Fi counts as on here.
     */
    fun radioState(): RadioState =
        RadioState(
            bluetoothAvailable = node.bluetoothAvailable,
            bluetoothOn = node.bluetoothAvailable,
            wifiOn = true,
            nearbyPermission = true,
        )

    // ---- RadarActions ----

    override fun send(
        deviceKey: String,
        files: AttachedFiles,
    ) {
        launchIo { node.send(deviceKey, PortMappers.sendFiles(files.items)) }
    }

    override fun cancel(transferId: String) = node.cancel(transferId)

    override fun confirmPairing(transferId: String) = node.confirmPairing(transferId)

    // ---- IncomingActions ----

    override fun accept(
        offerId: String,
        alwaysAccept: Boolean,
    ) = node.accept(offerId, alwaysAccept)

    override fun decline(offerId: String) = node.decline(offerId)

    override fun confirmCode(offerId: String) = node.confirmCode(offerId)

    override fun timedOut(offerId: String) = node.offerTimedOut(offerId)

    // ---- LiveActions ----

    override fun pause(transferId: String) = Unit

    override fun resume(transferId: String) = Unit

    override fun addFiles(
        transferId: String,
        files: AttachedFiles,
    ) = Unit

    // ---- HistorySource ----

    override val history: Flow<List<HistoryEntry>>
        get() =
            combine(node.data.transfers.observeHistory(HISTORY_ROWS), node.transfers) { records, running ->
                val live = running.mapTo(HashSet()) { it.id }
                records.filterNot { it.isActive && it.id.toHex() in live }.map(PortMappers::history)
            }

    override suspend fun files(transferId: String): List<HistoryFile> {
        val id = parse(transferId) ?: return emptyList()
        return withContext(io) { node.data.transferFiles.files(id).map { PortMappers.historyFile(it) } }
    }

    override fun openFile(
        transferId: String,
        fileId: String,
    ) {
        val id = parse(transferId) ?: return
        val index = fileId.toIntOrNull() ?: return
        launchIo {
            val file = node.data.transferFiles.file(id, index) ?: return@launchIo
            PortMappers.pathOf(file.savedUri)?.let { host.open(it) }
        }
    }

    override fun resend(transferId: String) {
        launchIo { node.resend(transferId) }
    }

    override fun clear() {
        launchIo { node.clearHistory() }
    }

    // ---- DeviceSource ----

    override val trusted: Flow<List<TrustedDeviceEntry>>
        get() = node.data.devices.observeTrusted().map { list -> list.map(PortMappers::trusted) }

    override fun rename(
        deviceId: String,
        name: String,
    ) {
        launchIo { node.rename(deviceId, name.takeIf { it.isNotBlank() }) }
    }

    override fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ) {
        launchIo { node.setAutoAccept(deviceId, enabled) }
    }

    override fun forget(deviceId: String) {
        launchIo { node.forget(deviceId) }
    }

    // ---- StatsSource ----

    override val stats: Flow<StatsSnapshot>
        get() = node.data.stats.observe().map(PortMappers::stats)

    override fun shareStatsCard() = Unit

    // ---- SettingsSource ----

    override val settings: Flow<SettingsValues>
        get() = combine(node.settings.filterNotNull(), node.nickname) { s, nick -> PortMappers.settings(s, nick, appVersion) }

    override fun setVisibility(mode: Visibility) {
        launchIo { node.setVisibility(mode) }
    }

    override fun setPrefer5Ghz(enabled: Boolean) {
        launchIo { node.data.settings.set(SettingKeys.PREFER_5_GHZ, enabled) }
    }

    override fun setKeepScreenAwake(enabled: Boolean) {
        launchIo { node.data.settings.set(SettingKeys.KEEP_SCREEN_AWAKE, enabled) }
    }

    override fun setBundleSmallFiles(enabled: Boolean) {
        launchIo { node.setBundleSmallFiles(enabled) }
    }

    override fun pickSaveLocation() {
        scope.launch {
            val s = strings()
            val folder =
                host.chooseFolder(s["chooser.saveLocation"], s["chooser.saveLocation.select"], node.receivedFolder) ?: return@launch
            launchIo { node.setSaveLocation(folder) }
        }
    }

    override suspend fun clearPartialFiles(): ClearPartialsResult {
        val cleared = node.clearPartials()
        return ClearPartialsResult(cleared.filesRemoved, cleared.bytesFreed)
    }

    override fun setNickname(nickname: String) {
        launchIo { node.setNickname(nickname) }
    }

    override fun pickAvatar() = Unit

    override fun removeAvatar() = Unit

    override fun setLanguage(language: AppLanguage) {
        launchIo { node.data.settings.set(SettingKeys.LANGUAGE, language.tag) }
    }

    override fun setCrashReports(enabled: Boolean) {
        launchIo { node.data.settings.set(SettingKeys.CRASH_REPORTS, enabled) }
    }

    override fun setHaptics(enabled: Boolean) {
        launchIo { node.data.settings.set(SettingKeys.HAPTICS, enabled) }
    }

    // ---- MyCodeSource (F-B5 five-minute code with the LAN address, F-H4 browser path) ----

    /**
     * A code signed now for five minutes, with this computer's LAN address and control port (F‑H4: a phone that scans
     * it dials the computer directly, behind the handshake's identity check, where the network filters multicast);
     * its fallback is that address to type. The banner's static code (F‑B6) stays separate.
     */
    override suspend fun current(): MyCode {
        val code = node.oneTimeCode()
        return MyCode(
            code.payload,
            fallbackCode = code.fallback,
            issuedAtMillis = code.issuedAtMillis,
            expiresAtMillis = code.expiresAtMillis,
        )
    }

    override val browserShare: Flow<BrowserShareState>
        get() = node.browserShare.map { PortMappers.browserShare(it, strings()["browser.lanNetwork"], strings()["browser.noPassword"]) }

    override fun startBrowserShare(files: AttachedFiles) {
        node.startBrowserShare(PortMappers.sendFiles(files.items))
    }

    override fun stopBrowserShare() = node.stopBrowserShare()

    // ---- PlatformActions ----

    /** Desktops have no system Bluetooth dialog the app may open; the banner explains instead (design §9). */
    override fun turnOnBluetooth() = Unit

    override fun openWifiPanel() = Unit

    override fun openReceivedFile(fileId: String) {
        val path = receivedPaths[fileId] ?: return
        launchIo { host.open(path) }
    }

    /** "Share onward" has no desktop share sheet: show the file in its folder instead. */
    override fun shareReceivedFile(fileId: String) {
        val path = receivedPaths[fileId] ?: return
        launchIo { host.reveal(path) }
    }

    override fun openReceivedFolder() {
        val folder = node.receivedFolder
        launchIo {
            Files.createDirectories(folder)
            host.reveal(folder)
        }
    }

    override fun browseFiles() {
        scope.launch {
            val s = strings()
            val chosen = host.chooseFiles(s["chooser.files"], s["chooser.send"])
            if (chosen.isEmpty()) return@launch
            val items = runInterruptible(io) { SendItems.expand(chosen) }
            onFilesChosen(PortMappers.pickedItems(items.files))
        }
    }

    // ---- OnboardingActions ----

    override fun finish(nickname: String) {
        launchIo { node.setNickname(nickname) }
    }

    override fun openBrandSettings(brand: OemBrand) = Unit

    private fun launchIo(block: suspend () -> Unit) {
        scope.launch(io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The node reports its own problems; a failed action here is logged the same way.
                problems.tryEmit(NodeEvent.Problem("a UI action failed", e))
            }
        }
    }

    private fun parse(transferId: String): TransferId? = runCatching { TransferId.fromHex(transferId) }.getOrNull()

    private fun defaultSettings(nickname: String): SettingsValues =
        SettingsValues(
            visibility = Visibility.TRUSTED_ONLY,
            prefer5Ghz = true,
            keepScreenAwake = false,
            bundleSmallFiles = true,
            saveLocationLabel = null,
            nickname = nickname,
            avatar = null,
            language = AppLanguage.SYSTEM,
            crashReports = false,
            haptics = true,
            appVersion = appVersion,
        )

    /** Failures of UI actions, for the app's log. */
    val problems = MutableSharedFlow<NodeEvent.Problem>(extraBufferCapacity = PROBLEM_BUFFER)

    private companion object {
        /** History rows kept live (F‑G2 pages further back through the repository when needed). */
        const val HISTORY_ROWS = 500

        const val PROBLEM_BUFFER = 16
    }
}
