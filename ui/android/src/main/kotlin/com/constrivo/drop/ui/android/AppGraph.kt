package com.constrivo.drop.ui.android

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.constrivo.drop.R
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.platform.android.service.CodeScan
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.ClearPartialsResult
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.presenter.DayCalendar
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.FeatureFlags
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.ui.shared.presenter.Screen
import com.constrivo.drop.ui.shared.presenter.SettingsSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The Android app's composition root: one [DropAppController] for the process, so the UI state survives
 * configuration changes and is shared by every activity (a share opens one in the sending app's task).
 *
 * The platform ports are real: permissions ([AndroidPermissions]), radio state ([RadioMonitor]), the gallery
 * ([MediaStoreLibrary]), system screens and files ([AndroidPlatformActions]), onboarding ([AndroidOnboarding]),
 * direct share ([DirectSharePublisher]), the language ([AppLocales]) and the device's calendar. The engine ports
 * (devices, transfers, offers, History, trusted devices, stats, settings, the QR code) are [ServicePorts] over the
 * `TransferService`'s node through [TransferServiceClient] (architecture §10.1: the UI binds to it and observes its
 * `StateFlow`s). Main-thread confined.
 *
 * Shares (design §4.3) are owned by the activity that received them: Android revokes the sender's read grants when
 * that activity is destroyed, so its files are then dropped ([onHostFinished]); a recreated activity adopts its share
 * ([MainActivity], [ShareRestore]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AppGraph(
    private val app: Application,
) {
    // One port failing (a provider throwing, an I/O error) must not end the process; it is logged instead.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in the UI scope", e) },
        )
    private val store = ShellPreferences.store(app)
    private val prefs = ShellPreferences(store)
    val bridge = ActivityBridge(app)
    val permissions = AndroidPermissions(app, prefs, bridge)
    private val shareIds = DirectShareIds(store)
    private val uris = UriReader(app.contentResolver, app.getString(R.string.file_untitled))
    private val pickerOpen = MutableStateFlow(false)
    private val directShare = DirectSharePublisher(app, shareIds)
    private val locales = AppLocales(app, prefs)
    private var avatarTarget: AvatarTarget? = null

    // Shares and who received them.
    private val shareOwners = HashMap<String, ActivityBridge.Host>()
    private var reading: Pair<String, Job>? = null
    private var startedHosts = 0

    /** The UI's side of the transfer service (bound while an activity is started, architecture §10.1). */
    val client = TransferServiceClient(app, scope)

    /** The engine ports over the service's node (the radar, transfers, offers, History, devices, stats, the code). */
    val ports = ServicePorts(client, scope, decodePreview = ::decodePreview, openUri = ::openUri)

    /** The profile picture: kept in the process for now (not yet stored with the settings). */
    private val avatar = MutableStateFlow<ImageBitmap?>(null)

    private val appVersion: String =
        try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }

    private val nickname: Flow<String> = client.node.flatMapLatest { n -> n?.nickname ?: flowOf(prefs.nickname.orEmpty()) }

    private val profile: StateFlow<SelfProfile> =
        combine(client.node, nickname, avatar) { n, name, picture -> SelfProfile(name, n?.selfDeviceId ?: SELF_KEY, picture) }
            .stateIn(scope, SharingStarted.Eagerly, SelfProfile(prefs.nickname.orEmpty(), SELF_KEY))

    private fun defaultSettings(
        name: String,
        picture: ImageBitmap?,
    ): SettingsValues =
        SettingsValues(
            visibility = Visibility.TRUSTED_ONLY,
            prefer5Ghz = SettingKeys.PREFER_5_GHZ.defaultValue,
            keepScreenAwake = SettingKeys.KEEP_SCREEN_AWAKE.defaultValue,
            bundleSmallFiles = SettingKeys.BUNDLE_SMALL_FILES.defaultValue,
            saveLocationLabel = null,
            nickname = name,
            avatar = picture,
            language = locales.current(),
            crashReports = SettingKeys.CRASH_REPORTS.defaultValue,
            haptics = SettingKeys.HAPTICS.defaultValue,
            appVersion = appVersion,
        )

    /** Settings from the node's database, with the avatar, the nickname and the language going through the platform too. */
    private val settings: SettingsSource =
        object : SettingsSource {
            override val settings: Flow<SettingsValues> =
                combine(client.node.flatMapLatest { n -> n?.settings ?: flowOf(null) }, nickname, avatar) { snapshot, name, picture ->
                    if (snapshot == null) {
                        defaultSettings(name, picture)
                    } else {
                        val language = NodeMappers.language(snapshot, if (locales.inApp) null else locales.current())
                        NodeMappers.settings(snapshot, name, picture, language, appVersion)
                    }
                }

            override fun setVisibility(mode: Visibility) = ports.setVisibility(mode)

            override fun setPrefer5Ghz(enabled: Boolean) = ports.setSetting(SettingKeys.PREFER_5_GHZ, enabled)

            override fun setKeepScreenAwake(enabled: Boolean) = ports.setSetting(SettingKeys.KEEP_SCREEN_AWAKE, enabled)

            override fun setBundleSmallFiles(enabled: Boolean) = ports.setBundleSmallFiles(enabled)

            // A folder of the user's choice (SAF tree) is not offered yet: media go to the gallery, documents to Downloads.
            override fun pickSaveLocation() = Unit

            override suspend fun clearPartialFiles(): ClearPartialsResult = ports.clearPartialFiles()

            override fun setNickname(nickname: String) {
                prefs.nickname = nickname
                ports.setNickname(nickname)
            }

            override fun pickAvatar() = requestAvatar(AvatarTarget.SETTINGS)

            override fun removeAvatar() {
                avatar.value = null
            }

            override fun setLanguage(language: AppLanguage) {
                locales.apply(language)
                ports.setSetting(SettingKeys.LANGUAGE, language.tag)
            }

            override fun setCrashReports(enabled: Boolean) = ports.setSetting(SettingKeys.CRASH_REPORTS, enabled)

            override fun setHaptics(enabled: Boolean) = ports.setSetting(SettingKeys.HAPTICS, enabled)
        }

    private val onboarding =
        AndroidOnboarding(
            context = app,
            bridge = bridge,
            prefs = prefs,
            onFinished = { nickname ->
                prefs.nickname = nickname
                ports.setNickname(nickname)
                (app as? DropApplication)?.markOnboarded()
                controller.onboarding
                    ?.state
                    ?.value
                    ?.avatar
                    ?.let(::applyAvatar)
            },
            onPickAvatar = { requestAvatar(AvatarTarget.ONBOARDING) },
        )

    val controller: DropAppController =
        DropAppController(
            scope,
            DropDependencies(
                devices = ports.devices,
                transfers = ports.transfers,
                offers = ports.offers,
                received = ports.received,
                radio = RadioMonitor(app, permissions).state,
                visibility = ports.visibility,
                profile = profile,
                initialProfile = profile.value,
                radarActions = ports,
                incomingActions = ports,
                liveActions = ports,
                history = ports,
                trustedDevices = ports,
                stats = ports,
                settings = settings,
                initialSettings = defaultSettings(prefs.nickname.orEmpty(), null),
                myCode = ports,
                media = MediaStoreLibrary(app, permissions, pickerOpen),
                permissions = permissions,
                platform = AndroidPlatformActions(app, bridge, ports.receivedFiles),
                calendar = DayCalendar.System,
                wallClock = SystemWallClock,
                monotonicClock = SystemMonotonicClock,
                onboarding =
                    if (prefs.onboarded) {
                        null
                    } else {
                        OnboardingConfig(AndroidOnboarding.deviceName(app), AndroidOnboarding.brand(), onboarding)
                    },
                features = FeatureFlags(apkSharing = false),
            ),
        )

    init {
        scope.launch { controller.picker.state.collect { pickerOpen.value = it != null } }
        directShare.start(scope, controller.radar.state)
        // The radar on screen scans in its foreground mode (F-A2); the service hears it through the client.
        scope.launch { controller.screen.collect { client.setRadarVisible(startedHosts > 0 && it == Screen.RADAR) } }
        // A permission answered, or changed in Settings: the service reopens what it can (§11).
        scope.launch { permissions.changes.collect { client.refreshPermissions() } }
        // A nickname chosen before the service ran (onboarding) reaches the node once it is up.
        scope.launch {
            client.node.filterNotNull().collect { n ->
                val chosen = prefs.nickname
                if (!chosen.isNullOrBlank() && n.nickname.value != chosen) ports.setNickname(chosen)
            }
        }
    }

    /** Whether the "Haptic feedback" setting is on (design §11). */
    fun hapticsEnabled(): Boolean = controller.dashboard.settings.state.value.values.haptics

    /** Whether the shared UI applies the language itself (Android 12) rather than the system ([AppLocales]). */
    val languageInApp: Boolean get() = locales.inApp

    /** An activity started: grants may have changed in Settings meanwhile; the first one brings the UI to the front. */
    fun onHostStarted() {
        permissions.refresh()
        if (startedHosts++ == 0) {
            client.onUiStarted()
            client.setRadarVisible(controller.screen.value == Screen.RADAR)
            controller.onHostStarted()
        }
    }

    /** An activity stopped; when none is started, the UI is in the background. */
    fun onHostStopped() {
        startedHosts = (startedHosts - 1).coerceAtLeast(0)
        if (startedHosts == 0) {
            controller.onHostStopped()
            client.onUiStopped()
        }
    }

    /** The camera read [text] from a code (design §4.4): a verified device is selected, anything else says why not. */
    fun onScannedText(text: String) {
        scope.launch {
            val n = client.node.value ?: return@launch
            when (val result = withContext(Dispatchers.Default) { n.resolveCode(text) }) {
                is CodeScan.Verified -> controller.onCodeScanned(result.deviceKey)
                CodeScan.Expired -> controller.onScanProblem(ScanStatus.EXPIRED)
                is CodeScan.Invalid -> controller.onScanProblem(ScanStatus.INVALID)
            }
        }
    }

    /**
     * Handles a launch intent from [host]: a share (design §4.3), possibly through a direct-share target (F‑C3), or a
     * launcher shortcut of a device. Only streams the sender could grant are read ([SharedFiles.grantedStreams]), off
     * the main thread; a newer share cancels a read still running, so the banner never shows an older share. Returns
     * the share's id when the intent carried one, which [host] keeps across recreation.
     */
    fun handleIntent(
        intent: Intent,
        host: ActivityBridge.Host,
    ): String? {
        if (ShareIntent.isShare(intent)) {
            val share = ShareIntent.read(intent) ?: return null
            val streams = SharedFiles.grantedStreams(share.streams, share.readGranted, ownAuthorities())
            if (streams.isEmpty()) return null
            val target = shareIds.keyFor(share.shortcutId)
            if (target != null) share.shortcutId?.let(directShare::reportUsed)
            val id = UUID.randomUUID().toString()
            shareOwners[id] = host
            reading?.second?.cancel()
            val job =
                scope.launch {
                    val items = withContext(Dispatchers.IO) { uris.itemsOf(streams, share.declaredMime) }
                    if (items.isNotEmpty()) controller.onShareIntent(AttachedFiles(items), target, shareIds.nameFor(target), id)
                }
            reading = id to job
            job.invokeOnCompletion { if (reading?.first == id) reading = null }
            return id
        } else if (intent.action == DirectSharePublisher.ACTION_OPEN_DEVICE) {
            val key = shareIds.keyFor(intent.getStringExtra(DirectSharePublisher.EXTRA_TARGET_ID)) ?: return null
            if (controller.radar.state.value.bubbles
                    .any { it.key == key }
            ) {
                controller.onBubbleTap(key)
            }
        }
        return null
    }

    /** Whether share [id] is still waiting: its files attached, or still being read. */
    fun shareLive(id: String): Boolean = controller.holdsShare(id) || reading?.first == id

    /** A recreated activity took over share [id] (a configuration change). */
    fun adoptShare(
        id: String,
        host: ActivityBridge.Host,
    ) {
        if (shareLive(id)) shareOwners[id] = host
    }

    /**
     * [host] is finishing for good: a system dialog it opened will never report back, and the read grants of the
     * shares it received are gone, so their files are dropped (a send already running is the engine's to keep: WP7
     * gives its service its own grant).
     */
    fun onHostFinished(host: ActivityBridge.Host) {
        permissions.onHostFinished(host)
        val owned = shareOwners.filterValues { it === host }.keys
        for (id in owned) {
            shareOwners.remove(id)
            reading?.let { (readingId, job) -> if (readingId == id) job.cancel() }
            controller.releaseShare(id)
        }
    }

    /** The document picker's result (the picker's Files tab). */
    fun onDocumentsPicked(picked: List<Uri>) {
        if (picked.isEmpty()) return
        scope.launch {
            val items = withContext(Dispatchers.IO) { uris.items(picked) }
            if (items.isNotEmpty()) controller.onFilesPicked(items)
        }
    }

    /** The photo picker's result, for whichever flow asked. */
    fun onImagePicked(uri: Uri?) {
        val target = avatarTarget
        avatarTarget = null
        if (uri == null || target == null) return
        scope.launch {
            val image = withContext(Dispatchers.IO) { AvatarImages.decode(app.contentResolver, uri) } ?: return@launch
            when (target) {
                AvatarTarget.ONBOARDING -> controller.onboarding?.setAvatar(image)
                AvatarTarget.SETTINGS -> applyAvatar(image)
            }
        }
    }

    /** This app's own content-provider authorities (a share must not make it read its own providers for another app). */
    private fun ownAuthorities(): Set<String> =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.packageManager.getPackageInfo(
                        app.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
                }
            info.providers
                ?.flatMap { it.authority?.split(';').orEmpty() }
                ?.toSet()
                .orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            emptySet()
        }

    private fun requestAvatar(target: AvatarTarget) {
        val host = bridge.current ?: return
        avatarTarget = target
        host.pickImage()
    }

    private fun applyAvatar(image: ImageBitmap) {
        avatar.value = image
    }

    /** An Offer's preview (N12: at most 4 KiB from a stranger): decoded when it is a small image, else a glyph. */
    private fun decodePreview(preview: Preview): FileThumb? {
        if (!preview.mime.startsWith("image/")) return null
        val bytes = preview.data.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_PREVIEW_SIDE ||
            bounds.outHeight > MAX_PREVIEW_SIDE
        ) {
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return FileThumb.Picture(bitmap.asImageBitmap())
    }

    /** Opens a received file from History with the system viewer and a one-time read grant (F-D5: never automatic). */
    private fun openUri(
        uri: String,
        mime: String?,
    ) {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!bridge.start(intent)) Log.w(TAG, "nothing can open $uri")
    }

    private enum class AvatarTarget { ONBOARDING, SETTINGS }

    private companion object {
        /** The radar key of this device until the node reports its device id. */
        const val SELF_KEY = "self"

        /** Largest preview side decoded (the sender makes them 4 KiB JPEGs, N12). */
        const val MAX_PREVIEW_SIDE = 512
        const val TAG = "Drop"
    }
}
