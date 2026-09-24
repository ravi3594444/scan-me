package com.constrivo.drop.platform.android.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions the radios of this module use (architecture §11), with the API level from which each exists.
 * [manifestName] is the `android.permission.*` string.
 */
enum class RadioPermission(
    val manifestName: String,
    val sinceSdk: Int,
) {
    /** Scanning for beacons (`neverForLocation`). */
    BLUETOOTH_SCAN(Manifest.permission.BLUETOOTH_SCAN, 31),

    /** Sending the beacon. */
    BLUETOOTH_ADVERTISE(Manifest.permission.BLUETOOTH_ADVERTISE, 31),

    /** GATT, LE L2CAP and RFCOMM connections of the handshake channel, and `ACTION_REQUEST_ENABLE`. */
    BLUETOOTH_CONNECT(Manifest.permission.BLUETOOTH_CONNECT, 31),

    /** Wi-Fi Direct, hotspot and NSD without location (13+); asked at the same "radar open" moment (§11). */
    @SuppressLint("InlinedApi")
    NEARBY_WIFI_DEVICES(Manifest.permission.NEARBY_WIFI_DEVICES, 33),
}

/**
 * Which radio permissions are granted, and what that allows (F-A2, F-A6, T-22). The UI (WP8's permission gate) asks for
 * [missingForRadar] just in time when the radar opens; this module only reads the state and never shows a dialog.
 *
 * Degraded modes: without [RadioPermission.BLUETOOTH_ADVERTISE] the device still sees others but is invisible over
 * Bluetooth; without [RadioPermission.BLUETOOTH_SCAN] it is visible but its radar stays empty; without
 * [RadioPermission.BLUETOOTH_CONNECT] no Bluetooth handshake can run, so sending needs the LAN or a QR code.
 */
data class RadioPermissionState(
    val sdk: Int,
    val granted: Set<RadioPermission>,
) {
    /** The permissions this API level defines. */
    val applicable: List<RadioPermission> get() = RadioPermission.entries.filter { sdk >= it.sinceSdk }

    val canScan: Boolean get() = RadioPermission.BLUETOOTH_SCAN in granted
    val canAdvertise: Boolean get() = RadioPermission.BLUETOOTH_ADVERTISE in granted
    val canConnect: Boolean get() = RadioPermission.BLUETOOTH_CONNECT in granted

    /** Wi-Fi Direct and the hotspot without location: granted on 13+, or not needed below (Android 12 uses location). */
    val canUseNearbyWifi: Boolean get() =
        sdk < RadioPermission.NEARBY_WIFI_DEVICES.sinceSdk ||
            RadioPermission.NEARBY_WIFI_DEVICES in granted

    /** Every applicable permission missing for "radar open", in manifest form and §11 order. */
    val missingForRadar: List<String> get() = applicable.filter { it !in granted }.map { it.manifestName }

    /** True when nothing of [missingForRadar] is missing. */
    val radarReady: Boolean get() = missingForRadar.isEmpty()

    companion object {
        /** Everything granted, for tests and previews. */
        fun allGranted(sdk: Int): RadioPermissionState =
            RadioPermissionState(sdk, RadioPermission.entries.filter { sdk >= it.sinceSdk }.toSet())
    }
}

/** Reads [RadioPermissionState]. */
object RadioPermissions {
    /** Pure form: [isGranted] answers for a manifest permission name. Permissions above [sdk] never count as granted. */
    fun state(
        sdk: Int,
        isGranted: (String) -> Boolean,
    ): RadioPermissionState =
        RadioPermissionState(
            sdk,
            RadioPermission.entries.filterTo(mutableSetOf()) { sdk >= it.sinceSdk && isGranted(it.manifestName) },
        )

    /** The current state for [context]'s app. Cheap; call it again whenever the app returns to the foreground. */
    fun read(context: Context): RadioPermissionState =
        state(Build.VERSION.SDK_INT) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
