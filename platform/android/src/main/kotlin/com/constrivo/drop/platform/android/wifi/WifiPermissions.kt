package com.constrivo.drop.platform.android.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A Wi-Fi operation of this package whose runtime permissions are checked before the platform is called (§11). */
enum class WifiOperation {
    /** Hosting or joining a Wi-Fi Direct group: `createGroup`, `connect`, `requestGroupInfo`, the connection broadcast. */
    WIFI_DIRECT,

    /** `WifiManager.startLocalOnlyHotspot`. */
    LOCAL_ONLY_HOTSPOT,

    /** Joining a network by `WifiNetworkSpecifier` (`ConnectivityManager.requestNetwork`): install-time permissions only. */
    NETWORK_SPECIFIER,

    /**
     * Talking to devices on the local network: LAN sockets and `NsdManager` announce and browse, which need
     * `ACCESS_LOCAL_NETWORK` on Android 17 for apps targeting API 37 (without it `NsdManager` shows a service picker).
     */
    LOCAL_NETWORK,
}

/**
 * What the permission checks read: the device's API level, the app's target API level (the rules depend on both), the
 * granted permissions and whether location services are on.
 */
class WifiPermissionContext(
    val sdk: Int,
    val targetSdk: Int,
    private val granted: (String) -> Boolean,
    private val locationEnabled: () -> Boolean = { true },
) {
    fun isGranted(permission: String): Boolean = granted(permission)

    fun isLocationEnabled(): Boolean = locationEnabled()

    /** [WifiPermissions.missing] for [operation]. */
    fun missing(operation: WifiOperation): List<String> = WifiPermissions.missing(sdk, targetSdk, operation, granted)

    /**
     * Checks [operation]'s permissions, and on Android 12 the location services the hotspot needs.
     *
     * @throws WifiLinkException [WifiLinkError.PERMISSION_MISSING] with the missing permissions, or
     *   [WifiLinkError.LOCATION_OFF].
     */
    fun require(operation: WifiOperation) {
        val missing = missing(operation)
        if (missing.isNotEmpty()) {
            throw WifiLinkException(
                WifiLinkError.PERMISSION_MISSING,
                "${operation.name.lowercase()} needs ${missing.joinToString()}",
                missingPermissions = missing,
            )
        }
        if (WifiPermissions.needsLocationServices(sdk, targetSdk, operation) && !locationEnabled()) {
            throw WifiLinkException(WifiLinkError.LOCATION_OFF, "Android 12 starts a local-only hotspot only with location services on")
        }
    }

    companion object {
        /** Everything granted and location on, on [sdk]; for tests. */
        fun allGranted(
            sdk: Int,
            targetSdk: Int = sdk,
        ): WifiPermissionContext = WifiPermissionContext(sdk, targetSdk, { true })

        /** The current state of [context]'s app. Cheap; every check reads the live grants. */
        fun of(context: Context): WifiPermissionContext {
            val app = context.applicationContext
            val location = app.getSystemService(LocationManager::class.java)
            return WifiPermissionContext(
                sdk = Build.VERSION.SDK_INT,
                targetSdk = app.applicationInfo.targetSdkVersion,
                granted = { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED },
                locationEnabled = { runCatching { location?.isLocationEnabled ?: false }.getOrDefault(false) },
            )
        }
    }
}

/**
 * The runtime permissions of the Wi-Fi operations (architecture §11 and §8 notes), as pure functions of the API levels:
 *
 * | Operation | Android 13+ (target 33+) | Android 12, or target below 33 |
 * | --- | --- | --- |
 * | Wi-Fi Direct | `NEARBY_WIFI_DEVICES` (`neverForLocation`) | `ACCESS_FINE_LOCATION` |
 * | Local-only hotspot | `NEARBY_WIFI_DEVICES` (`neverForLocation`) | `ACCESS_FINE_LOCATION`, and location services on |
 * | Network specifier join | none (install-time `CHANGE_NETWORK_STATE`) | none |
 * | LAN sockets and NSD | `ACCESS_LOCAL_NETWORK` on Android 17+ with target 37+ | none |
 *
 * Install-time permissions (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`,
 * `CHANGE_WIFI_MULTICAST_STATE`) are declared by this module's manifest and never listed as missing.
 */
object WifiPermissions {
    const val NEARBY_WIFI_DEVICES: String = "android.permission.NEARBY_WIFI_DEVICES"
    const val ACCESS_FINE_LOCATION: String = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_LOCAL_NETWORK: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 13, where `NEARBY_WIFI_DEVICES` replaces location for Wi-Fi Direct and the hotspot. */
    const val NEARBY_WIFI_SDK: Int = 33

    /** Android 17, where apps targeting it need `ACCESS_LOCAL_NETWORK` for the local network. */
    const val LOCAL_NETWORK_SDK: Int = 37

    /** The runtime permissions [operation] needs on a device at [sdk] for an app targeting [targetSdk], in request order. */
    fun required(
        sdk: Int,
        targetSdk: Int,
        operation: WifiOperation,
    ): List<String> {
        val nearby = sdk >= NEARBY_WIFI_SDK && targetSdk >= NEARBY_WIFI_SDK
        return when (operation) {
            WifiOperation.WIFI_DIRECT, WifiOperation.LOCAL_ONLY_HOTSPOT -> {
                listOf(if (nearby) NEARBY_WIFI_DEVICES else ACCESS_FINE_LOCATION)
            }

            WifiOperation.NETWORK_SPECIFIER -> {
                emptyList()
            }

            WifiOperation.LOCAL_NETWORK -> {
                if (sdk >= LOCAL_NETWORK_SDK && targetSdk >= LOCAL_NETWORK_SDK) listOf(ACCESS_LOCAL_NETWORK) else emptyList()
            }
        }
    }

    /** The permissions of [required] that [isGranted] denies. */
    fun missing(
        sdk: Int,
        targetSdk: Int,
        operation: WifiOperation,
        isGranted: (String) -> Boolean,
    ): List<String> = required(sdk, targetSdk, operation).filterNot(isGranted)

    /**
     * The local-only hotspot on location-based rules (Android 12, or a target below 33) refuses to start while
     * location services are off (`SecurityException: Location mode is not enabled`).
     */
    fun needsLocationServices(
        sdk: Int,
        targetSdk: Int,
        operation: WifiOperation,
    ): Boolean = operation == WifiOperation.LOCAL_ONLY_HOTSPOT && !(sdk >= NEARBY_WIFI_SDK && targetSdk >= NEARBY_WIFI_SDK)
}
