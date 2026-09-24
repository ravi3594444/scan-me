package com.constrivo.drop.ui.android

import android.Manifest
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Architecture §11: which permissions each feature asks for, per Android version, and how a denial is read. */
class PermissionMatrixTest {
    private val android12 = 31
    private val android12L = 32
    private val android13 = 33
    private val android14 = 34
    private val android16 = 36

    private val none: (String) -> Boolean = { false }
    private val every: (String) -> Boolean = { true }

    private fun only(permission: String): (String) -> Boolean = { it == permission }

    private fun allBut(permission: String): (String) -> Boolean = { it != permission }

    private fun permissions(
        permission: DropPermission,
        sdk: Int,
    ) = PermissionMatrix.manifestPermissions(permission, sdk)

    /** The status with [granted] grants, no rationale wanted and nothing denied before, unless given. */
    private fun status(
        permission: DropPermission,
        sdk: Int,
        granted: (String) -> Boolean,
        rationale: (String) -> Boolean = none,
        deniedBefore: (String) -> Boolean = none,
    ) = PermissionMatrix.status(permission, sdk, granted, rationale, deniedBefore)

    @Test
    fun architecture11_nearbyIsBluetoothPlusNearbyWifiFrom13() {
        val bluetooth =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        val withWifi = bluetooth + Manifest.permission.NEARBY_WIFI_DEVICES
        assertEquals(bluetooth, permissions(DropPermission.NEARBY, android12))
        assertEquals(bluetooth, permissions(DropPermission.NEARBY, android12L))
        assertEquals(withWifi, permissions(DropPermission.NEARBY, android13))
        assertEquals(withWifi, permissions(DropPermission.NEARBY, android16))
    }

    @Test
    fun architecture11_locationOnlyOnAndroid12() {
        val location = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(location, permissions(DropPermission.LOCATION_FOR_WIFI_DIRECT, android12))
        assertEquals(location, permissions(DropPermission.LOCATION_FOR_WIFI_DIRECT, android12L))
        assertTrue(permissions(DropPermission.LOCATION_FOR_WIFI_DIRECT, android13).isEmpty())
        assertEquals(PermissionStatus.NOT_NEEDED, status(DropPermission.LOCATION_FOR_WIFI_DIRECT, android13, none))
    }

    @Test
    fun architecture11_approximateLocationDoesNotEnableWifiDirect() {
        val coarse = only(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = only(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(PermissionStatus.DENIED, status(DropPermission.LOCATION_FOR_WIFI_DIRECT, android12, coarse))
        assertEquals(PermissionStatus.GRANTED, status(DropPermission.LOCATION_FOR_WIFI_DIRECT, android12, fine))
    }

    @Test
    fun architecture11_mediaPerVersion() {
        val media = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), permissions(DropPermission.MEDIA, android12))
        assertEquals(media, permissions(DropPermission.MEDIA, android13))
        assertEquals(media + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, permissions(DropPermission.MEDIA, android14))
    }

    @Test
    fun android14PartialPhotoAccessCountsAsGranted() {
        val selected = only(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(PermissionStatus.GRANTED, status(DropPermission.MEDIA, android14, selected))
        assertEquals(PermissionStatus.DENIED, status(DropPermission.MEDIA, android13, selected))
    }

    @Test
    fun architecture11_notificationsFrom13AndCameraAlways() {
        assertEquals(PermissionStatus.NOT_NEEDED, status(DropPermission.NOTIFICATIONS, android12, none))
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permissions(DropPermission.NOTIFICATIONS, android13))
        for (sdk in listOf(android12, android13, android16)) {
            assertEquals(listOf(Manifest.permission.CAMERA), permissions(DropPermission.CAMERA, sdk))
        }
        assertFailsWith<IllegalArgumentException> { status(DropPermission.BATTERY, android13, none) }
    }

    @Test
    fun fI1_grantedOnlyWhenEveryPermissionIs() {
        assertEquals(PermissionStatus.DENIED, status(DropPermission.NEARBY, android13, allBut(Manifest.permission.BLUETOOTH_ADVERTISE)))
        assertEquals(PermissionStatus.GRANTED, status(DropPermission.NEARBY, android13, every))
    }

    @Test
    fun fI1_blockedOnlyAfterAnActiveDenialWhenTheSystemStopsAskingForARationale() {
        val camera = DropPermission.CAMERA
        // Never asked: no rationale yet, not blocked.
        assertEquals(PermissionStatus.DENIED, status(camera, android13, none))
        // Denied once: the system wants a rationale, the dialog can still show.
        assertEquals(PermissionStatus.DENIED, status(camera, android13, none, rationale = every, deniedBefore = every))
        // Denied again ("don't ask again"): no rationale any more.
        assertEquals(PermissionStatus.BLOCKED, status(camera, android13, none, rationale = none, deniedBefore = every))
    }

    @Test
    fun fI1_aDialogDismissedWithBackIsNotRecordedAsADenial() {
        val dismissed = mapOf(Manifest.permission.CAMERA to false)
        assertTrue(PermissionMatrix.deniedWithRationale(dismissed, none).isEmpty())
        val denied = mapOf(Manifest.permission.CAMERA to false, Manifest.permission.BLUETOOTH_SCAN to true)
        assertEquals(setOf(Manifest.permission.CAMERA), PermissionMatrix.deniedWithRationale(denied, every))
    }
}
