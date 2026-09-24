package com.constrivo.drop.ui.android

import android.Manifest
import android.annotation.SuppressLint
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.PermissionStatus

/**
 * The Android permissions behind each [DropPermission], by API level (architecture §11), and how their grant state
 * becomes a [PermissionStatus]. Pure (API levels are plain integers and the grant state comes in as functions) so the
 * matrix is unit-tested off-device.
 *
 * Differences from the §11 table:
 * - Android 12 asks for `ACCESS_COARSE_LOCATION` together with `ACCESS_FINE_LOCATION`: since API 31 a fine-location
 *   request without the coarse one is ignored. Only fine location makes Wi‑Fi Direct discovery work, so choosing
 *   "approximate" in the dialog still counts as denied.
 * - Media asks for images and videos only: the picker's grid shows photos and videos (design §4.1), audio and every
 *   other file come through the system document picker, which needs no permission. On Android 14+
 *   `READ_MEDIA_VISUAL_USER_SELECTED` is asked for too, and "Select photos" (partial access) counts as granted.
 * - [DropPermission.BATTERY] is not a runtime permission; [AndroidPermissions] reads it from `PowerManager`.
 *
 * Lint's `InlinedApi` is suppressed: the Android 13 and 14 permission names are plain strings, returned only for the
 * API levels that define them.
 */
@SuppressLint("InlinedApi")
internal object PermissionMatrix {
    /** Android 13 (`Build.VERSION_CODES.TIRAMISU`). */
    const val API_33: Int = 33

    /** Android 14 (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`). */
    const val API_34: Int = 34

    /** The runtime permissions [permission] needs on API level [sdk]; empty when nothing is asked. */
    fun manifestPermissions(
        permission: DropPermission,
        sdk: Int,
    ): List<String> =
        when (permission) {
            DropPermission.NEARBY -> {
                buildList {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                    if (sdk >= API_33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }

            DropPermission.LOCATION_FOR_WIFI_DIRECT -> {
                if (sdk < API_33) {
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                } else {
                    emptyList()
                }
            }

            DropPermission.MEDIA -> {
                when {
                    sdk >= API_34 -> {
                        listOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        )
                    }

                    sdk >= API_33 -> {
                        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    }

                    else -> {
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }

            DropPermission.NOTIFICATIONS -> {
                if (sdk >= API_33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            }

            DropPermission.CAMERA -> {
                listOf(Manifest.permission.CAMERA)
            }

            DropPermission.BATTERY -> {
                emptyList()
            }
        }

    /** Whether [permission] is usable given the grant state of each runtime permission. */
    fun isGranted(
        permission: DropPermission,
        sdk: Int,
        granted: (String) -> Boolean,
    ): Boolean {
        val needed = manifestPermissions(permission, sdk)
        return when {
            permission == DropPermission.MEDIA && sdk >= API_34 -> {
                (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }

            permission == DropPermission.LOCATION_FOR_WIFI_DIRECT -> {
                needed.isEmpty() || granted(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            else -> {
                needed.all(granted)
            }
        }
    }

    /**
     * The status of a runtime-permission [permission] (not [DropPermission.BATTERY]).
     *
     * Android does not say whether a permission is blocked ("don't ask again"), so this infers it: a missing
     * permission is [PermissionStatus.BLOCKED] when the user denied it before ([deniedBefore]: a result came back
     * denied while the system wanted a rationale) and the system no longer wants a rationale, which it only stops
     * wanting once the dialog will not be shown again. A dialog dismissed with Back is not a denial and never marks
     * [deniedBefore], so it cannot lead to a false "blocked".
     *
     * `shouldShowRequestPermissionRationale` needs an activity: without one ([rationaleKnown] false, for example while
     * the process starts before the first activity attaches) its "false" means nothing, so a permission is never
     * inferred [PermissionStatus.BLOCKED] then; the shared gate reads the status again once its explainer is answered.
     *
     * @param showRationale `shouldShowRequestPermissionRationale` for each permission.
     */
    fun status(
        permission: DropPermission,
        sdk: Int,
        granted: (String) -> Boolean,
        showRationale: (String) -> Boolean,
        deniedBefore: (String) -> Boolean,
        rationaleKnown: Boolean = true,
    ): PermissionStatus {
        require(permission != DropPermission.BATTERY) { "battery optimisation is not a runtime permission" }
        val needed = manifestPermissions(permission, sdk)
        if (needed.isEmpty()) return PermissionStatus.NOT_NEEDED
        if (isGranted(permission, sdk, granted)) return PermissionStatus.GRANTED
        val missing = needed.filterNot(granted)
        val blocked = rationaleKnown && missing.any { deniedBefore(it) && !showRationale(it) }
        return if (blocked) PermissionStatus.BLOCKED else PermissionStatus.DENIED
    }

    /**
     * The permissions of a request result that the user actively denied: denied while the system now wants a rationale.
     * These are the ones [status] later treats as "denied before".
     */
    fun deniedWithRationale(
        results: Map<String, Boolean>,
        showRationale: (String) -> Boolean,
    ): Set<String> = results.filter { (name, ok) -> !ok && showRationale(name) }.keys
}
