package com.constrivo.drop.ui.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity

/**
 * Connects the app-wide ports (which outlive activities: configuration changes, a share opening a second task) to the
 * Activity-bound parts they need: activity-result launchers and a context to start system screens from. The most
 * recently started [Host] is current. Main-thread confined.
 */
internal class ActivityBridge(
    private val appContext: Context,
) {
    /** What an activity offers the ports. Results come back through [AppGraph], not through this interface. */
    interface Host {
        val activity: ComponentActivity

        /** `RequestMultiplePermissions` (the result goes to [AndroidPermissions.onPermissionsResult]). */
        fun requestPermissions(permissions: Array<String>)

        /** A system screen whose return matters (battery optimisation; the result goes to [AndroidPermissions]). */
        fun startForResult(intent: Intent)

        /** The system document picker, several files, any type (SAF `OpenMultipleDocuments`). */
        fun openDocuments()

        /** The system photo picker, one image (the avatar). */
        fun pickImage()
    }

    private val hosts = ArrayList<Host>()

    /** The activity in front, or null when none is alive (the app runs only its engine). */
    val current: Host? get() = hosts.lastOrNull()

    /** Called from `onStart`; the host becomes current. */
    fun attach(host: Host) {
        hosts.remove(host)
        hosts.add(host)
    }

    /** Called from `onDestroy`. */
    fun detach(host: Host) {
        hosts.remove(host)
    }

    /**
     * Starts [intent] from the current activity (or, with none, as a new task) and reports whether something handled
     * it. Other apps' screens can be missing or unexported on a given OS build, so failure is an answer, not an error.
     */
    fun start(intent: Intent): Boolean {
        val activity = current?.activity
        return try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                appContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
