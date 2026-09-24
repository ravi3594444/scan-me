package com.constrivo.drop.platform.android.permission

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings

/**
 * The one-tap system screens that turn a radio on without leaving the app (F-A6, architecture §10.1). Apps cannot
 * toggle Wi-Fi since Android 10, so Wi-Fi opens the system panel. The UI launches these with an activity-result
 * launcher; this module never starts an activity itself.
 */
object RadioEnableIntents {
    /**
     * The system "Allow the app to turn on Bluetooth?" dialog (`BluetoothAdapter.ACTION_REQUEST_ENABLE`). Needs
     * `BLUETOOTH_CONNECT`; without it use [bluetoothSettings].
     */
    fun enableBluetooth(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** The Bluetooth settings page, the fallback when `BLUETOOTH_CONNECT` is not granted. */
    fun bluetoothSettings(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    /** The Wi-Fi panel that slides over the app (`Settings.Panel.ACTION_WIFI`). */
    fun wifiPanel(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    /** The Wi-Fi settings page, for builds without the panel (the intent resolves to nothing). */
    fun wifiSettings(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    /**
     * The intent to use for Bluetooth: the enable dialog when [permissions] allow it, else the settings page (the
     * dialog would throw a SecurityException without `BLUETOOTH_CONNECT`).
     */
    fun forBluetooth(permissions: RadioPermissionState): Intent = if (permissions.canConnect) enableBluetooth() else bluetoothSettings()
}
