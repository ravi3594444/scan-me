package com.constrivo.drop.platform.android.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The §11 permission rules of the Wi-Fi operations (F-E2, F-E3, F-A3; T-22, T-24). */
class WifiPermissionsTest {
    private val nearby = WifiPermissions.NEARBY_WIFI_DEVICES
    private val fine = WifiPermissions.ACCESS_FINE_LOCATION
    private val local = WifiPermissions.ACCESS_LOCAL_NETWORK

    @Test
    fun android13PlusUsesNearbyWifiDevicesForWifiDirectAndTheHotspot() {
        for (sdk in 33..37) {
            assertEquals(listOf(nearby), WifiPermissions.required(sdk, 37, WifiOperation.WIFI_DIRECT), "sdk $sdk")
            assertEquals(listOf(nearby), WifiPermissions.required(sdk, 37, WifiOperation.LOCAL_ONLY_HOTSPOT), "sdk $sdk")
        }
    }

    @Test
    fun android12UsesFineLocation() {
        for (sdk in 31..32) {
            assertEquals(listOf(fine), WifiPermissions.required(sdk, 37, WifiOperation.WIFI_DIRECT))
            assertEquals(listOf(fine), WifiPermissions.required(sdk, 37, WifiOperation.LOCAL_ONLY_HOTSPOT))
        }
    }

    @Test
    fun anAppTargetingBelow33KeepsTheLocationRuleOnNewDevices() {
        assertEquals(listOf(fine), WifiPermissions.required(34, 32, WifiOperation.WIFI_DIRECT))
        assertTrue(WifiPermissions.needsLocationServices(34, 32, WifiOperation.LOCAL_ONLY_HOTSPOT))
    }

    @Test
    fun specifierJoinsNeedNoRuntimePermission() {
        for (sdk in 31..37) assertEquals(emptyList(), WifiPermissions.required(sdk, 37, WifiOperation.NETWORK_SPECIFIER))
    }

    @Test
    fun theLocalNetworkPermissionAppliesFromAndroid17ForAppsTargeting37() {
        for (sdk in 31..36) assertEquals(emptyList(), WifiPermissions.required(sdk, 37, WifiOperation.LOCAL_NETWORK), "sdk $sdk")
        assertEquals(listOf(local), WifiPermissions.required(37, 37, WifiOperation.LOCAL_NETWORK))
        assertEquals(emptyList(), WifiPermissions.required(37, 36, WifiOperation.LOCAL_NETWORK), "target 36 keeps the old rule")
    }

    @Test
    fun missingListsOnlyWhatIsNotGranted() {
        assertEquals(listOf(nearby), WifiPermissions.missing(34, 37, WifiOperation.WIFI_DIRECT) { false })
        assertEquals(emptyList(), WifiPermissions.missing(34, 37, WifiOperation.WIFI_DIRECT) { it == nearby })
        val android12 = WifiPermissions.missing(31, 37, WifiOperation.WIFI_DIRECT) { it == nearby }
        assertEquals(listOf(fine), android12, "nearby does not exist on 12")
    }

    @Test
    fun onlyTheHotspotOnLocationRulesNeedsLocationServices() {
        assertTrue(WifiPermissions.needsLocationServices(31, 37, WifiOperation.LOCAL_ONLY_HOTSPOT))
        assertTrue(WifiPermissions.needsLocationServices(32, 37, WifiOperation.LOCAL_ONLY_HOTSPOT))
        assertFalse(WifiPermissions.needsLocationServices(33, 37, WifiOperation.LOCAL_ONLY_HOTSPOT))
        assertFalse(WifiPermissions.needsLocationServices(31, 37, WifiOperation.WIFI_DIRECT))
    }

    @Test
    fun requireThrowsTypedErrorsWithThePermissionsToRequest() {
        val denied = WifiPermissionContext(34, 37, { false })
        val error = assertFailsWith<WifiLinkException> { denied.require(WifiOperation.WIFI_DIRECT) }
        assertEquals(WifiLinkError.PERMISSION_MISSING, error.error)
        assertEquals(listOf(nearby), error.missingPermissions)

        val locationOff = WifiPermissionContext(31, 37, { true }, locationEnabled = { false })
        val off = assertFailsWith<WifiLinkException> { locationOff.require(WifiOperation.LOCAL_ONLY_HOTSPOT) }
        assertEquals(WifiLinkError.LOCATION_OFF, off.error)
        locationOff.require(WifiOperation.WIFI_DIRECT)

        WifiPermissionContext.allGranted(37).require(WifiOperation.LOCAL_NETWORK)
        WifiPermissionContext(33, 37, { true }, locationEnabled = { false }).require(WifiOperation.LOCAL_ONLY_HOTSPOT)
    }
}
