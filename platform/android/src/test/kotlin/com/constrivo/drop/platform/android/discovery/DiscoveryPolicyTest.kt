package com.constrivo.drop.platform.android.discovery

import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals

/** Architecture §5.1 timing and spec change N13, as the pure discovery policy. */
class DiscoveryPolicyTest {
    private val granted = DiscoveryInputs(visibility = Visibility.EVERYONE, canAdvertise = true, canScan = true)

    @Test
    fun theRadarRunsFastAdvertisingAndALowLatencyScan() {
        assertEquals(
            DiscoveryPlan(RadioMode.FOREGROUND, RadioMode.FOREGROUND, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true)),
        )
        // With the service running as well, the radar wins.
        assertEquals(
            DiscoveryPlan(RadioMode.FOREGROUND, RadioMode.FOREGROUND, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, foregroundService = true)),
        )
    }

    @Test
    fun theServiceAloneRunsSlowAdvertisingAndALowPowerScan() {
        assertEquals(
            DiscoveryPlan(RadioMode.BACKGROUND, RadioMode.BACKGROUND, true),
            DiscoveryPolicy.plan(granted.copy(foregroundService = true)),
        )
    }

    @Test
    fun aTransferKeepsAdvertisingSlowlyAndPausesScanning() {
        assertEquals(
            DiscoveryPlan(RadioMode.BACKGROUND, null, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, foregroundService = true, transferActive = true)),
        )
        assertEquals(
            DiscoveryPlan(RadioMode.BACKGROUND, RadioMode.FOREGROUND, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, transferActive = true), pauseScanDuringTransfer = false),
        )
    }

    @Test
    fun nothingRunsWithoutTheRadarOrTheService() {
        assertEquals(DiscoveryPlan.IDLE, DiscoveryPolicy.plan(granted))
        assertEquals(DiscoveryPlan.IDLE, DiscoveryPolicy.plan(granted.copy(transferActive = true)))
    }

    @Test
    fun hiddenNeverAdvertisesButStillScans() {
        assertEquals(
            DiscoveryPlan(null, RadioMode.FOREGROUND, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, visibility = Visibility.HIDDEN)),
        )
        for (visibility in listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY)) {
            assertEquals(
                RadioMode.FOREGROUND,
                DiscoveryPolicy.plan(granted.copy(radarVisible = true, visibility = visibility)).advertiseMode,
            )
        }
    }

    @Test
    fun missingPermissionsSwitchOffOnlyTheirPart() {
        assertEquals(
            DiscoveryPlan(null, RadioMode.FOREGROUND, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, canAdvertise = false)),
        )
        assertEquals(
            DiscoveryPlan(RadioMode.FOREGROUND, null, true),
            DiscoveryPolicy.plan(granted.copy(radarVisible = true, canScan = false)),
        )
        assertEquals(DiscoveryPlan(null, null, true), DiscoveryPolicy.plan(DiscoveryInputs(radarVisible = true)))
    }
}
