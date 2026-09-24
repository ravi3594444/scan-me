package com.constrivo.drop.ui.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.LifecycleOwner
import com.constrivo.drop.ui.shared.DropApp

/**
 * The Android app's single activity (architecture §10.1): it hosts the shared [DropApp] over the process-wide
 * controller, receives shares (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` for any type, design §4.3, including direct-share
 * targets, F‑C3) and owns the activity-result launchers the platform ports use: runtime permissions, the
 * battery-optimisation screen, the document picker and the photo picker. Back goes to the controller first (sheets,
 * the dashboard, the scanner); only when it has nothing to close does the system handle it.
 */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as DropApplication).graph

    /** The share this activity received (its read grants live and die with this activity), if any. */
    private var shareId: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.permissions.onPermissionsResult(it) }
    private val screenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { graph.permissions.onScreenResult() }
    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { graph.onDocumentsPicked(it) }
    private val imageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { graph.onImagePicked(it) }

    private val host =
        object : ActivityBridge.Host {
            override val activity: ComponentActivity get() = this@MainActivity

            override fun requestPermissions(permissions: Array<String>) = permissionLauncher.launch(permissions)

            override fun startForResult(intent: Intent) {
                try {
                    screenLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    graph.permissions.onScreenResult()
                }
            }

            override fun openDocuments() {
                try {
                    documentLauncher.launch(arrayOf(ANY_TYPE))
                } catch (_: ActivityNotFoundException) {
                    // No document provider (some managed profiles); the Files tab stays as it is.
                }
            }

            override fun pickImage() = imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph.bridge.attach(host)
        // A recreated activity must not attach the same share twice (rotation), but must read it again when the
        // process died while it was still waiting (the grants survive with the activity, the controller does not).
        val saved = savedInstanceState?.getString(KEY_SHARE_ID)
        val pending = savedInstanceState?.getBoolean(KEY_SHARE_PENDING) == true
        val live = saved != null && graph.shareLive(saved)
        shareId =
            when (ShareRestore.decide(savedInstanceState != null, saved, pending, live)) {
                ShareRestore.Action.HANDLE -> graph.handleIntent(intent, host)
                ShareRestore.Action.ADOPT -> saved?.also { graph.adoptShare(it, host) }
                ShareRestore.Action.SKIP -> null
            }
        setContent { ActivityContent(graph) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        graph.handleIntent(intent, host)?.let { shareId = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shareId?.let {
            outState.putString(KEY_SHARE_ID, it)
            outState.putBoolean(KEY_SHARE_PENDING, graph.shareLive(it))
        }
    }

    override fun onStart() {
        super.onStart()
        graph.bridge.attach(host)
        graph.onHostStarted()
    }

    override fun onStop() {
        graph.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        graph.bridge.detach(host)
        if (!isChangingConfigurations) graph.onHostFinished(host)
        super.onDestroy()
    }

    private companion object {
        const val ANY_TYPE = "*/*"
        const val KEY_SHARE_ID = "drop.shareId"
        const val KEY_SHARE_PENDING = "drop.sharePending"
    }
}

@Composable
private fun ActivityContent(graph: AppGraph) {
    val controller = graph.controller
    val canGoBack by controller.canGoBack.collectAsState()
    BackHandler(enabled = canGoBack) { controller.back() }
    val view = LocalView.current
    val haptics = remember(view) { AndroidHaptics(view, graph::hapticsEnabled) }
    // Settings "Keep screen awake" (design §6): on while the setting is on and a transfer is moving.
    val keepScreenOn by controller.keepScreenOn.collectAsState()
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // The activity hosting this content owns the camera's lifecycle.
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    DropApp(
        controller,
        reducedMotion = rememberReducedMotion(),
        haptics = haptics,
        applyLanguage = graph.languageInApp,
        // Scan to send (design §4.4): the back camera, each decoded code resolved by the service's node.
        camera = { QrCameraPreview(lifecycleOwner, onText = graph::onScannedText) },
    )
}

/** The system's "Remove animations" setting, followed while the activity shows (design §3.3 reduced motion). */
@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver

    fun read() = SystemMotion.isReduced(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = read()
                }
            }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
