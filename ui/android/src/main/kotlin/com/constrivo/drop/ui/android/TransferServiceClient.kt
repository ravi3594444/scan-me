package com.constrivo.drop.ui.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.constrivo.drop.platform.android.service.AndroidNode
import com.constrivo.drop.platform.android.service.ServiceLog
import com.constrivo.drop.platform.android.service.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The UI's side of the [TransferService] (architecture §10.1: "the UI binds to it and observes `StateFlow`s"): bound
 * while an activity of the app is started, unbound when the last one stops, so the service decides alone what runs in
 * the background (S9). [node] is the running node, or null while unbound or starting; the ports read it and act on it.
 * What the UI tells the service (the radar is shown, an activity is in front) is kept and replayed on every bind.
 * Main-thread confined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TransferServiceClient(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val binder = MutableStateFlow<TransferService.LocalBinder?>(null)
    private var bound = false
    private var radarVisible = false
    private var uiVisible = false

    /** The running node, or null (unbound, starting, or its start failed: [startFailure]). */
    val node: StateFlow<AndroidNode?> =
        binder.flatMapLatest { it?.node ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Why the node could not start (a database of a newer app, an identity that does not open), or null. */
    val startFailure: StateFlow<String?> =
        binder.flatMapLatest { it?.startFailure ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The service's ring-buffer log (architecture §14), while bound. */
    val log: ServiceLog? get() = binder.value?.log

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                val local = service as? TransferService.LocalBinder ?: return
                local.setUiVisible(uiVisible)
                local.setRadarVisible(radarVisible)
                local.refreshPermissions()
                binder.value = local
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // The service's process is the app's own: this only happens while the process dies.
                binder.value = null
            }
        }

    /** An activity started: bind (creating the service when needed) and say the UI is in front. */
    fun onUiStarted() {
        uiVisible = true
        // Started as well as bound: a rotation, a picker or a system screen unbinds for a moment, and a service that is
        // only bound would be destroyed with its node each time; started, it keeps the node and lingers when idle.
        try {
            context.startService(TransferService.bindIntent(context))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start the transfer service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot start the transfer service", e)
        }
        if (!bound) {
            bound =
                try {
                    context.bindService(TransferService.bindIntent(context), connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    Log.e(TAG, "cannot bind the transfer service", e)
                    false
                }
        }
        binder.value?.setUiVisible(true)
    }

    /** The last activity stopped: the service now runs only for what needs it in the background. */
    fun onUiStopped() {
        uiVisible = false
        radarVisible = false
        binder.value?.let {
            it.setRadarVisible(false)
            it.setUiVisible(false)
        }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        binder.value = null
    }

    /** The radar screen is shown (F-A2): discovery scans in its foreground mode. */
    fun setRadarVisible(visible: Boolean) {
        if (radarVisible == visible) return
        radarVisible = visible
        binder.value?.setRadarVisible(visible)
    }

    /** The user answered a permission request, or came back from the system settings (§11). */
    fun refreshPermissions() {
        binder.value?.refreshPermissions()
    }

    /**
     * Hands the read grants of [uris] to the service before a send uses them (architecture §10.1 "Share sheet"), so the
     * send outlives the activity that received the share. Only while the UI is in front, where Android allows the start.
     */
    fun holdGrants(uris: List<String>) {
        val content = uris.filter { it.startsWith(SharedFiles.CONTENT_SCHEME + ":") }.map(Uri::parse)
        if (content.isEmpty()) return
        try {
            TransferService.holdGrants(context, content)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not hand the share's grants to the transfer service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not hand the share's grants to the transfer service", e)
        }
    }

    private companion object {
        const val TAG = "Drop"
    }
}
