package com.constrivo.drop.ui.android

import android.Manifest
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.constrivo.drop.R
import com.constrivo.drop.ui.shared.presenter.PlatformActions

/** Maps a received file's id to the URI the system can open (the engine's MediaStore or Downloads entry). */
internal fun interface ReceivedFileLocator {
    fun uriOf(fileId: String): Uri?

    companion object {
        /**
         * Ids that are `content:` URIs, as the engine's received files are (architecture §10.1: media through
         * MediaStore, documents in Downloads); anything else is not openable.
         */
        val ContentUris: ReceivedFileLocator =
            ReceivedFileLocator { id ->
                id.toUri().takeIf { it.scheme.equals(SharedFiles.CONTENT_SCHEME, ignoreCase = true) }
            }
    }
}

/**
 * The shared UI's platform actions on Android (architecture §10.1 "Radios off", design §5.2 tray, §4.1 Files tab).
 *
 * - "Turn on" Bluetooth asks with `ACTION_REQUEST_ENABLE`, the system's own dialog, so the user never leaves the app
 *   (F‑A6). That dialog needs `BLUETOOTH_CONNECT`; without it the Bluetooth settings page opens instead.
 * - Wi‑Fi opens the `Settings.Panel.ACTION_WIFI` panel: apps cannot switch Wi‑Fi on themselves on Android 10+.
 * - Received files open or share with a one-time read grant, never automatically (F‑D5); when no app can open one,
 *   a short message says so.
 * - "Browse files" is the system document picker; its result reaches the controller through [AppGraph].
 */
internal class AndroidPlatformActions(
    private val context: Context,
    private val bridge: ActivityBridge,
    private val files: ReceivedFileLocator = ReceivedFileLocator.ContentUris,
) : PlatformActions {
    override fun turnOnBluetooth() {
        val asked =
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bridge.start(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                false
            }
        if (!asked) bridge.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun openWifiPanel() {
        if (!bridge.start(Intent(Settings.Panel.ACTION_WIFI))) bridge.start(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    override fun openReceivedFile(fileId: String) {
        val uri = files.uriOf(fileId) ?: return tell(R.string.error_cannot_open)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, typeOf(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!bridge.start(intent)) tell(R.string.error_cannot_open)
    }

    override fun shareReceivedFile(fileId: String) {
        val uri = files.uriOf(fileId) ?: return tell(R.string.error_cannot_open)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(typeOf(uri))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The ClipData carries the grant through the chooser to the app the user picks.
        send.clipData = ClipData.newRawUri(null, uri)
        if (!bridge.start(Intent.createChooser(send, null))) tell(R.string.error_cannot_open)
    }

    override fun openReceivedFolder() {
        if (!bridge.start(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))) tell(R.string.error_cannot_open)
    }

    override fun browseFiles() {
        bridge.current?.openDocuments()
    }

    private fun typeOf(uri: Uri): String =
        try {
            context.contentResolver.getType(uri)
        } catch (_: SecurityException) {
            null
        } ?: ANY_TYPE

    private fun tell(
        @StringRes message: Int,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ANY_TYPE = "*/*"
    }
}
