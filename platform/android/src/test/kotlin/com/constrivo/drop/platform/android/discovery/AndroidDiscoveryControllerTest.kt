package com.constrivo.drop.platform.android.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.NearbyDevices
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.TrustState
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.StationBand
import com.constrivo.drop.platform.android.capability.LocalRadioFacts
import com.constrivo.drop.platform.android.permission.RadioPermissionState
import com.constrivo.drop.platform.android.permission.RadioPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The discovery wiring (F-A1, F-A2, F-A5; N4, N13) with a fake radio on virtual time: which modes run for which hooks,
 * the advertising restart at every epoch boundary, and the radar fed from the scan.
 */
class AndroidDiscoveryControllerTest {
    private val crypto = JcaCryptoProvider()
    private val ownSecret = ByteArray(32) { 1 }

    /** Ten seconds before an epoch boundary, so the tests can cross one. */
    private val start = EphemeralIds.epochStartMillis(2_000_000) - 10_000

    private class FakeRadio : BeaconRadio {
        val advertised = ArrayList<Triple<Long, BeaconAdvertisement, RadioMode>>()
        var stops = 0
        val scans = ArrayList<RadioMode>()
        var activeScans = 0
        val air = MutableSharedFlow<BeaconSighting>(extraBufferCapacity = 64)
        var failures = 0
        var clock: () -> Long = { 0 }

        override suspend fun startAdvertising(
            advertisement: BeaconAdvertisement,
            mode: RadioMode,
        ) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("advertiser died")
            }
            advertised += Triple(clock(), advertisement, mode)
        }

        override suspend fun stopAdvertising() {
            stops++
        }

        override fun scan(mode: RadioMode): Flow<BeaconSighting> =
            flow {
                scans += mode
                activeScans++
                try {
                    air.collect { emit(it) }
                } finally {
                    activeScans--
                }
            }
    }

    private class Harness(
        val controller: AndroidDiscoveryController,
        val radio: FakeRadio,
        val state: MutableStateFlow<LocalBeaconState>,
        val permissions: MutableStateFlow<RadioPermissionState>,
        val secret: MutableStateFlow<ByteArray>,
        val wall: () -> Long,
    )

    private fun TestScope.harness(visibility: Visibility = Visibility.EVERYONE): Harness {
        val wall = { start + testScheduler.currentTime }
        val radio = FakeRadio().also { it.clock = wall }
        val state =
            MutableStateFlow(
                LocalBeaconState(
                    visibility,
                    DevicePlatform.PHONE,
                    Capabilities.of(Capabilities.Flag.WIFI_5GHZ),
                    NetworkHint(0x1234),
                    "Ana",
                ),
            )
        val permissions = MutableStateFlow(RadioPermissionState.allGranted(34))
        val secret = MutableStateFlow(ownSecret)
        val nearby = NearbyDevices(crypto, { wall() }, { testScheduler.currentTime })
        val controller =
            AndroidDiscoveryController(
                radio = radio,
                nearby = nearby,
                crypto = crypto,
                advertisingSecret = { secret.value },
                beaconState = state,
                permissions = permissions,
                wallClock = { wall() },
                // This device's own k_adv, so its echo is recognised and dropped.
                trust = flowOf(TrustState(ownAdvertisingSecrets = listOf(ownSecret))),
            )
        backgroundScope.launch { controller.run() }
        runCurrent()
        return Harness(controller, radio, state, permissions, secret, wall)
    }

    @Test
    fun theRadarAdvertisesFastAndScansWithLowLatency() =
        runTest {
            val h = harness()
            assertTrue(h.radio.advertised.isEmpty())
            h.controller.setRadarVisible(true)
            runCurrent()
            val (_, ad, mode) = h.radio.advertised.single()
            assertEquals(RadioMode.FOREGROUND, mode)
            assertEquals(EphemeralIds.at(crypto, ownSecret, h.wall()), ad.body.ephemeralId)
            assertEquals("Ana", ad.nickname)
            assertEquals(listOf(RadioMode.FOREGROUND), h.radio.scans)
            assertEquals(DiscoveryPlan(RadioMode.FOREGROUND, RadioMode.FOREGROUND, true), h.controller.plan.value)
            assertEquals(ad, h.controller.advertisement.value)
        }

    @Test
    fun advertisingRestartsExactlyAtEachEpochBoundary() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            val first = h.radio.advertised.single().second
            assertEquals(EphemeralIds.epochStartMillis(2_000_000), first.validUntilMillis)
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(1, h.radio.advertised.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, h.radio.advertised.size)
            val (at, second, _) = h.radio.advertised[1]
            assertEquals(EphemeralIds.epochStartMillis(2_000_000), at)
            assertEquals(EphemeralIds.derive(crypto, ownSecret, 2_000_000), second.body.ephemeralId)
            assertNotEquals(first.body.ephemeralId, second.body.ephemeralId)
            // And again 15 minutes later.
            advanceTimeBy(EphemeralIds.EPOCH_MILLIS)
            runCurrent()
            assertEquals(3, h.radio.advertised.size)
            assertEquals(EphemeralIds.derive(crypto, ownSecret, 2_000_001), h.radio.advertised[2].second.body.ephemeralId)
        }

    @Test
    fun theForegroundServiceAloneRunsInBackgroundMode() =
        runTest {
            val h = harness()
            h.controller.setForegroundService(true)
            runCurrent()
            assertEquals(RadioMode.BACKGROUND, h.radio.advertised.last().third)
            assertEquals(listOf(RadioMode.BACKGROUND), h.radio.scans)
            // Opening the radar switches both to foreground.
            h.controller.setRadarVisible(true)
            runCurrent()
            assertEquals(RadioMode.FOREGROUND, h.radio.advertised.last().third)
            assertEquals(listOf(RadioMode.BACKGROUND, RadioMode.FOREGROUND), h.radio.scans)
            assertEquals(1, h.radio.activeScans)
        }

    @Test
    fun aTransferKeepsTheDeviceVisibleButPausesScanning() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            h.controller.setTransferActive(true)
            runCurrent()
            assertEquals(RadioMode.BACKGROUND, h.radio.advertised.last().third)
            assertEquals(0, h.radio.activeScans)
            h.controller.setTransferActive(false)
            runCurrent()
            assertEquals(RadioMode.FOREGROUND, h.radio.advertised.last().third)
            assertEquals(1, h.radio.activeScans)
        }

    @Test
    fun hiddenStopsAdvertisingAndVisibilityChangesRebuildTheBeacon() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            h.state.value = h.state.value.copy(visibility = Visibility.HIDDEN)
            runCurrent()
            assertTrue(h.radio.stops >= 1)
            assertNull(h.controller.advertisement.value)
            val count = h.radio.advertised.size
            h.state.value = h.state.value.copy(visibility = Visibility.TRUSTED_ONLY)
            runCurrent()
            assertEquals(count + 1, h.radio.advertised.size)
            val trusted = h.radio.advertised.last().second
            assertEquals(Visibility.TRUSTED_ONLY, trusted.body.visibility)
            assertNull(trusted.nickname)
            assertTrue(trusted.body.networkHint.isNone)
        }

    @Test
    fun closingTheRadarWithoutAServiceStopsEverything() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            val stops = h.radio.stops
            h.controller.setRadarVisible(false)
            runCurrent()
            assertEquals(DiscoveryPlan.IDLE, h.controller.plan.value)
            assertEquals(stops + 1, h.radio.stops)
            assertEquals(0, h.radio.activeScans)
            assertTrue(h.controller.devices.value.isEmpty())
        }

    @Test
    fun sightingsReachTheRadar() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            val peer =
                BeaconAdvertisement.create(
                    crypto,
                    ByteArray(32) { 9 },
                    LocalBeaconState(Visibility.EVERYONE, DevicePlatform.LAPTOP, Capabilities.NONE, NetworkHint.NONE, "Ben"),
                    BeaconCarrier.MANUFACTURER_DATA,
                    h.wall(),
                )
            h.radio.air.emit(BeaconSighting.fromAdvertisingData(peer.advertisingData(), -55, "AA:BB:CC:00:11:22", h.wall())!!)
            advanceTimeBy(200)
            runCurrent()
            val device = h.controller.devices.value.single()
            assertEquals(peer.body.ephemeralId, device.ephemeralId)
            assertEquals(DevicePlatform.LAPTOP, device.platform)
            assertEquals("AA:BB:CC:00:11:22", device.radioAddresses.single().address)
            // Our own echo is dropped.
            val own = h.controller.advertisement.value!!
            h.radio.air.emit(BeaconSighting.fromAdvertisingData(own.advertisingData(), -40, "AA:BB:CC:00:11:33", h.wall())!!)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, h.controller.devices.value.size)
        }

    @Test
    fun missingPermissionsKeepTheRadiosOff() =
        runTest {
            val h = harness()
            h.permissions.value = RadioPermissions.state(34) { false }
            h.controller.setRadarVisible(true)
            runCurrent()
            assertTrue(h.radio.advertised.isEmpty())
            assertTrue(h.radio.scans.isEmpty())
            // Granted later: both start.
            h.permissions.value = RadioPermissionState.allGranted(34)
            runCurrent()
            assertEquals(1, h.radio.advertised.size)
            assertEquals(1, h.radio.activeScans)
        }

    @Test
    fun aRotatedSecretAppliesAtOnceOnRefresh() =
        runTest {
            val h = harness()
            h.controller.setRadarVisible(true)
            runCurrent()
            val rotated = ByteArray(32) { 77 }
            h.secret.value = rotated
            h.controller.refreshAdvertisement()
            runCurrent()
            assertEquals(EphemeralIds.at(crypto, rotated, h.wall()), h.radio.advertised.last().second.body.ephemeralId)
        }

    @Test
    fun radioFailuresAreReportedAndRetried() =
        runTest {
            val h = harness()
            h.radio.failures = 1
            h.controller.setRadarVisible(true)
            runCurrent()
            assertTrue(h.radio.advertised.isEmpty())
            assertTrue(h.controller.lastError.value!!.contains("advertiser died"))
            advanceTimeBy(DiscoveryControllerConfig().retryMillis)
            runCurrent()
            assertEquals(1, h.radio.advertised.size)
            assertNull(h.controller.lastError.value)
        }

    @Test
    fun aBadSecretIsReportedNotThrown() =
        runTest {
            val h = harness()
            h.secret.value = ByteArray(5)
            h.controller.setRadarVisible(true)
            runCurrent()
            assertTrue(h.radio.advertised.isEmpty())
            assertTrue(h.controller.lastError.value!!.startsWith("cannot build"))
            h.secret.value = ownSecret
            advanceTimeBy(DiscoveryControllerConfig().retryMillis)
            runCurrent()
            assertEquals(1, h.radio.advertised.size)
        }

    @Test
    fun phoneBeaconStatesCombineSettingsAndDetectedFacts() =
        runTest {
            val facts =
                LocalRadioFacts.UNKNOWN.copy(
                    capabilities = Capabilities.of(Capabilities.Flag.WIFI_DIRECT),
                    networkHint = NetworkHint(0x0A0B0C0D),
                    stationBand = StationBand.BAND_5_GHZ_OR_ABOVE,
                )
            val state =
                AndroidDiscoveryController.phoneBeaconStates(
                    flowOf(Visibility.EVERYONE_TEN_MINUTES),
                    flowOf("Cleo"),
                    flowOf(facts),
                ).first()
            assertEquals(
                LocalBeaconState(
                    Visibility.EVERYONE_TEN_MINUTES,
                    DevicePlatform.PHONE,
                    facts.capabilities,
                    facts.networkHint,
                    "Cleo",
                    null,
                ),
                state,
            )
        }
}
