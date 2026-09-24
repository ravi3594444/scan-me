package com.constrivo.drop.ui.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.PermissionStatus
import com.constrivo.drop.ui.shared.presenter.PermissionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The just-in-time permission flow of architecture §11 on Android (F‑I1). The shared `PermissionGate` decides when to
 * ask and shows the explainer; this class answers what is granted ([PermissionMatrix]), shows the system dialog
 * through the current activity's `RequestMultiplePermissions` launcher, remembers active denials so a "don't ask
 * again" state is recognised as [PermissionStatus.BLOCKED], and opens the app's settings page for the recovery path.
 *
 * Battery optimisation ([DropPermission.BATTERY], asked once in onboarding) is read from `PowerManager` and requested
 * with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 *
 * One request runs at a time. A request suspends until its result arrives, including across a configuration change
 * (the result is delivered to the recreated activity, which forwards it here); if the requesting activity finishes
 * without a result, [onHostFinished] ends the wait with a denial.
 */
internal class AndroidPermissions(
    private val context: Context,
    private val prefs: ShellPreferences,
    private val bridge: ActivityBridge,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : PermissionController {
    private val serial = Mutex()
    private var pendingRuntime: CompletableDeferred<Map<String, Boolean>>? = null
    private var pendingScreen: CompletableDeferred<Unit>? = null
    private var requester: ActivityBridge.Host? = null
    private val version = MutableStateFlow(0)

    /** Changes whenever a grant may have changed: a request finished, or the app came back to the foreground. */
    val changes: StateFlow<Int> = version.asStateFlow()

    override fun status(permission: DropPermission): PermissionStatus =
        if (permission == DropPermission.BATTERY) {
            batteryStatus()
        } else {
            PermissionMatrix.status(permission, sdk, ::granted, ::showRationale, prefs::wasDenied)
        }

    override suspend fun request(permission: DropPermission): PermissionStatus =
        serial.withLock {
            val before = status(permission)
            if (before.usable || before == PermissionStatus.BLOCKED) return@withLock before
            val host = bridge.current ?: return@withLock before
            requester = host
            if (permission == DropPermission.BATTERY) {
                val deferred = CompletableDeferred<Unit>()
                pendingScreen = deferred
                host.startForResult(AndroidOnboarding.ignoreBatteryOptimisations(context))
                deferred.await()
            } else {
                val deferred = CompletableDeferred<Map<String, Boolean>>()
                pendingRuntime = deferred
                host.requestPermissions(PermissionMatrix.manifestPermissions(permission, sdk).toTypedArray())
                deferred.await()
            }
            requester = null
            refresh()
            status(permission)
        }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        bridge.start(intent)
    }

    /** The activity's `RequestMultiplePermissions` result. */
    fun onPermissionsResult(results: Map<String, Boolean>) {
        PermissionMatrix.deniedWithRationale(results, ::showRationale).forEach(prefs::markDenied)
        pendingRuntime?.complete(results)
        pendingRuntime = null
        refresh()
    }

    /** The user came back from the battery-optimisation screen. */
    fun onScreenResult() {
        pendingScreen?.complete(Unit)
        pendingScreen = null
        refresh()
    }

    /**
     * [host] is finishing for good (not a configuration change): a system dialog it opened will never report back, so
     * a request waiting on it ends as it stands.
     */
    fun onHostFinished(host: ActivityBridge.Host) {
        if (requester !== host) return
        pendingRuntime?.complete(emptyMap())
        pendingRuntime = null
        pendingScreen?.complete(Unit)
        pendingScreen = null
    }

    /** Re-reads grants (the app returned from Settings, where the user may have changed them). */
    fun refresh() = version.update { it + 1 }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun showRationale(permission: String): Boolean {
        val activity = bridge.current?.activity ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun batteryStatus(): PermissionStatus {
        val power = context.getSystemService(PowerManager::class.java) ?: return PermissionStatus.NOT_NEEDED
        return if (power.isIgnoringBatteryOptimizations(context.packageName)) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
}
