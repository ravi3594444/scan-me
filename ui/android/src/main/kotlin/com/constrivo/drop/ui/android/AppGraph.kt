package com.constrivo.drop.ui.android

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.R
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.presenter.DayCalendar
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.FeatureFlags
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.ui.shared.presenter.SettingsSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
 * (devices, transfers, offers, History, trusted devices, stats, settings, the QR code) are the in-memory [InMemoryDrop]
 * until WP7 connects the `TransferService` (architecture §10.1: the UI binds to it and observes its `StateFlow`s); its
 * radar is empty, so the shell shows the "No devices yet" state. Main-thread confined.
 *
 * Shares (design §4.3) are owned by the activity that received them: Android revokes the sender's read grants when
 * that activity is destroyed, so its files are then dropped ([onHostFinished]); a recreated activity adopts its share
 * ([MainActivity], [ShareRestore]).
 */
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

    private val engine =
        InMemoryDrop(
            self = SelfProfile(nickname = prefs.nickname.orEmpty(), deviceKey = SELF_KEY),
            settingsValues = InMemoryDrop.defaultSettings(prefs.nickname.orEmpty()).copy(language = locales.current()),
        )

    /** Settings from the engine, with the avatar picker, the nickname and the language going through the platform. */
    private val settings: SettingsSource =
        object : SettingsSource by engine {
            override fun pickAvatar() = requestAvatar(AvatarTarget.SETTINGS)

            override fun setNickname(nickname: String) {
                prefs.nickname = nickname
                engine.setNickname(nickname)
            }

            override fun setLanguage(language: AppLanguage) {
                engine.setLanguage(language)
                locales.apply(language)
            }
        }

    private val onboarding =
        AndroidOnboarding(
            context = app,
            bridge = bridge,
            prefs = prefs,
            onFinished = { nickname ->
                engine.finish(nickname)
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
                devices = engine.devices,
                transfers = engine.transfers,
                offers = engine.offers,
                received = engine.receivedFiles,
                radio = RadioMonitor(app, permissions).state,
                visibility = engine.visibility,
                profile = engine.profile,
                initialProfile = engine.profile.value,
                radarActions = engine,
                incomingActions = engine,
                liveActions = engine,
                history = engine,
                trustedDevices = engine,
                stats = engine,
                settings = settings,
                initialSettings = engine.settingsState.value,
                myCode = engine,
                media = MediaStoreLibrary(app, permissions, pickerOpen),
                permissions = permissions,
                platform = AndroidPlatformActions(app, bridge),
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
    }

    /** Whether the "Haptic feedback" setting is on (design §11). */
    fun hapticsEnabled(): Boolean = controller.dashboard.settings.state.value.values.haptics

    /** Whether the shared UI applies the language itself (Android 12) rather than the system ([AppLocales]). */
    val languageInApp: Boolean get() = locales.inApp

    /** An activity started: grants may have changed in Settings meanwhile; the first one brings the UI to the front. */
    fun onHostStarted() {
        permissions.refresh()
        if (startedHosts++ == 0) controller.onHostStarted()
    }

    /** An activity stopped; when none is started, the UI is in the background. */
    fun onHostStopped() {
        startedHosts = (startedHosts - 1).coerceAtLeast(0)
        if (startedHosts == 0) controller.onHostStopped()
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
        engine.settingsState.update { it.copy(avatar = image) }
        engine.profile.update { it.copy(avatar = image) }
    }

    private enum class AvatarTarget { ONBOARDING, SETTINGS }

    private companion object {
        /** The in-memory engine's own radar key; the real engine uses the identity key's hash. */
        const val SELF_KEY = "self"
        const val TAG = "Drop"
    }
}
