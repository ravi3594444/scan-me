package com.constrivo.drop.ui.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.R
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.presenter.DayCalendar
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.FeatureFlags
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.ui.shared.presenter.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android app's composition root: one [DropAppController] for the process, so the UI state survives
 * configuration changes and is shared by every activity (a share opens one in the sending app's task).
 *
 * The platform ports are real: permissions ([AndroidPermissions]), radio state ([RadioMonitor]), the gallery
 * ([MediaStoreLibrary]), system screens and files ([AndroidPlatformActions]), onboarding ([AndroidOnboarding]),
 * direct share ([DirectSharePublisher]) and the device's calendar. The engine ports (devices, transfers, offers,
 * History, trusted devices, stats, settings, the QR code) are the in-memory [InMemoryDrop] until WP7 connects the
 * `TransferService` (architecture §10.1: the UI binds to it and observes its `StateFlow`s); its radar is empty, so
 * the shell shows the "No devices yet" state. Main-thread confined.
 */
internal class AppGraph(
    private val app: Application,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ShellPreferences.store(app)
    private val prefs = ShellPreferences(store)
    val bridge = ActivityBridge(app)
    val permissions = AndroidPermissions(app, prefs, bridge)
    private val shareIds = DirectShareIds(store)
    private val uris = UriReader(app.contentResolver, app.getString(R.string.file_untitled))
    private val pickerOpen = MutableStateFlow(false)
    private val directShare = DirectSharePublisher(app, shareIds)
    private var avatarTarget: AvatarTarget? = null

    private val engine =
        InMemoryDrop(self = SelfProfile(nickname = prefs.nickname.orEmpty(), deviceKey = SELF_KEY))

    /** Settings from the engine, with the avatar picker and the nickname going through the platform. */
    private val settings: SettingsSource =
        object : SettingsSource by engine {
            override fun pickAvatar() = requestAvatar(AvatarTarget.SETTINGS)

            override fun setNickname(nickname: String) {
                prefs.nickname = nickname
                engine.setNickname(nickname)
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

    /** An activity came to the front: grants may have changed in Settings meanwhile. */
    fun onForeground() = permissions.refresh()

    /**
     * Handles a launch intent: a share (design §4.3), possibly through a direct-share target (F‑C3), or a launcher
     * shortcut of a device. Shared URIs are read off the main thread; an empty or unreadable share just opens the
     * radar.
     */
    fun handleIntent(intent: Intent) {
        if (ShareIntent.isShare(intent)) {
            val share = ShareIntent.read(intent) ?: return
            val target = shareIds.keyFor(share.shortcutId)
            if (target != null) share.shortcutId?.let(directShare::reportUsed)
            scope.launch {
                val items = withContext(Dispatchers.IO) { uris.items(share.streams, share.declaredMime) }
                if (items.isNotEmpty()) controller.onShareIntent(AttachedFiles(items), target)
            }
        } else if (intent.action == DirectSharePublisher.ACTION_OPEN_DEVICE) {
            val key = shareIds.keyFor(intent.getStringExtra(DirectSharePublisher.EXTRA_TARGET_ID)) ?: return
            if (controller.radar.state.value.bubbles
                    .any { it.key == key }
            ) {
                controller.onBubbleTap(key)
            }
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
    }
}
