package com.constrivo.drop.platform.android.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Architecture §11 and T-22: which radio permissions "radar open" needs and what each missing one disables. */
class RadioPermissionsTest {
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val advertise = "android.permission.BLUETOOTH_ADVERTISE"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val nearbyWifi = "android.permission.NEARBY_WIFI_DEVICES"

    @Test
    fun android12NeedsTheThreeBluetoothPermissions() {
        val none = RadioPermissions.state(31) { false }
        assertEquals(listOf(scan, advertise, connect), none.missingForRadar)
        assertFalse(none.canScan || none.canAdvertise || none.canConnect)
        // Wi-Fi Direct on 12 goes through location (WP8's gate), not NEARBY_WIFI_DEVICES.
        assertTrue(none.canUseNearbyWifi)
        val all = RadioPermissions.state(31) { true }
        assertTrue(all.radarReady)
        assertEquals(RadioPermissionState.allGranted(31), all)
    }

    @Test
    fun android13AddsNearbyWifiDevices() {
        val none = RadioPermissions.state(33) { false }
        assertEquals(listOf(scan, advertise, connect, nearbyWifi), none.missingForRadar)
        assertFalse(none.canUseNearbyWifi)
        val all = RadioPermissions.state(36) { true }
        assertTrue(all.radarReady && all.canUseNearbyWifi)
        assertEquals(RadioPermissionState.allGranted(36), all)
    }

    @Test
    fun eachMissingPermissionDisablesOnlyItsPart() {
        val noAdvertise = RadioPermissions.state(34) { it != advertise }
        assertTrue(noAdvertise.canScan && noAdvertise.canConnect)
        assertFalse(noAdvertise.canAdvertise)
        assertEquals(listOf(advertise), noAdvertise.missingForRadar)
        val onlyScan = RadioPermissions.state(34) { it == scan }
        assertTrue(onlyScan.canScan)
        assertFalse(onlyScan.canConnect)
        assertEquals(listOf(advertise, connect, nearbyWifi), onlyScan.missingForRadar)
    }

    @Test
    fun aPermissionTheApiLevelDoesNotDefineNeverCountsAsGranted() {
        val state = RadioPermissions.state(32) { true }
        assertFalse(RadioPermission.NEARBY_WIFI_DEVICES in state.granted)
        assertEquals(
            listOf(RadioPermission.BLUETOOTH_SCAN, RadioPermission.BLUETOOTH_ADVERTISE, RadioPermission.BLUETOOTH_CONNECT),
            state.applicable,
        )
    }
}
