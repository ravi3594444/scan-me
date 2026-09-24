package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.discovery.RadioMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Architecture §5.1 scan timing, the shared scan's combined mode, and the scanned-device cache. */
class ScanPlanTest {
    @Test
    fun foregroundIsLowLatencyAndBackgroundLowPower() {
        val foreground = ScanPlan.parameters(RadioMode.FOREGROUND, extendedScanning = false)
        assertEquals(
            ScanParameters(ScanPlan.SCAN_MODE_LOW_LATENCY, legacyOnly = true, allPhys = false, matchMode = ScanPlan.MATCH_MODE_AGGRESSIVE),
            foreground,
        )
        val background = ScanPlan.parameters(RadioMode.BACKGROUND, extendedScanning = true)
        assertEquals(
            ScanParameters(ScanPlan.SCAN_MODE_LOW_POWER, legacyOnly = false, allPhys = true, matchMode = ScanPlan.MATCH_MODE_STICKY),
            background,
        )
    }

    @Test
    fun theSharedScanRunsInTheMostDemandingWantedMode() {
        assertNull(ScanPlan.combined(emptyList()))
        assertEquals(RadioMode.BACKGROUND, ScanPlan.combined(listOf(RadioMode.BACKGROUND, RadioMode.BACKGROUND)))
        assertEquals(RadioMode.FOREGROUND, ScanPlan.combined(listOf(RadioMode.BACKGROUND, RadioMode.FOREGROUND)))
    }

    @Test
    fun theManufacturerFilterMatchesTheDrMarker() {
        assertContentEquals(byteArrayOf(0x64, 0x72), ScanPlan.MANUFACTURER_FILTER_DATA)
        assertContentEquals(byteArrayOf(-1, -1), ScanPlan.MANUFACTURER_FILTER_MASK)
    }

    @Test
    fun theDeviceCacheIsAnLruKeyedByAddressInAnyCase() {
        val cache = DeviceCache<String>(2)
        cache.put("aa:bb:cc:dd:ee:01", "one")
        cache.put("AA:BB:CC:DD:EE:02", "two")
        assertEquals("one", cache.get("AA:BB:CC:DD:EE:01"))
        // "one" was used last, so "two" is evicted.
        cache.put("AA:BB:CC:DD:EE:03", "three")
        assertNull(cache.get("AA:BB:CC:DD:EE:02"))
        assertEquals("one", cache.get("aa:bb:cc:dd:ee:01"))
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertFailsWith<IllegalArgumentException> { DeviceCache<String>(0) }
    }
}
