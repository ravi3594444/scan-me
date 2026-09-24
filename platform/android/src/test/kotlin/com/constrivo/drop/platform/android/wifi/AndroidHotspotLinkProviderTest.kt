package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The local-only hotspot (F-E3, F-E11; N8, N15): system-generated credentials and band, typed start failures, the
 * hotspot interface, undo of a cancelled start, the user stopping it, station displacement and its restore, and the
 * specifier join.
 */
class AndroidHotspotLinkProviderTest {
    private class FakeReservation(
        override val config: HotspotConfigValues,
    ) : HotspotReservation {
        /** 1 once closed; the platform reservation is idempotent, so repeated closes count once. */
        var closed = 0

        override fun close() {
            closed = 1
        }
    }

    private class FakeHotspotRadio(
        private val scope: CoroutineScope,
    ) : HotspotRadio {
        var config =
            HotspotConfigValues(
                ssidBytes = "AndroidShare_4821".encodeToByteArray(),
                ssidText = null,
                passphrase = "k3ns9wq2xb",
                securityType = HotspotSecurity.WPA3_SAE_TRANSITION.platformValue,
                bssid = null,
                channels = null,
            )
        var startDelayMillis = 300L
        var failReason: Int? = null
        var answers = true
        var startFailure: RuntimeException? = null
        var starts = 0
        var callback: HotspotCallback? = null
        val reservations = CopyOnWriteArrayList<FakeReservation>()

        override fun start(callback: HotspotCallback) {
            startFailure?.let { throw it }
            starts++
            this.callback = callback
            if (!answers) return
            scope.launch {
                delay(startDelayMillis)
                val reason = failReason
                if (reason != null) callback.onFailed(reason) else callback.onStarted(FakeReservation(config).also { reservations += it })
            }
        }

        override fun knownNetworkInterfaces(): Set<String> = setOf("wlan0")

        fun lateStart() = callback!!.onStarted(FakeReservation(config).also { reservations += it })

        fun stop() = callback!!.onStopped()
    }

    private val station = iface("wlan0", addr("192.168.1.23", 24))
    private val hotspotInterface = iface("swlan0", addr("192.168.87.1", 24))

    private fun TestScope.provider(
        radio: FakeHotspotRadio?,
        permissions: WifiPermissionContext = WifiPermissionContext.allGranted(34, 37),
        stationMonitor: StationMonitor? = null,
        staAp: Boolean = false,
        allowDisplacement: Boolean = true,
        listener: RecordingListener = RecordingListener(),
        joiner: SpecifierJoiner? = null,
        interfaces: InterfaceLookup =
            FakeInterfaces { if (radio?.reservations.isNullOrEmpty()) listOf(station) else listOf(station, hotspotInterface) },
    ) = AndroidHotspotLinkProvider(
        radio = radio,
        permissions = permissions,
        clock = virtualClock(),
        joiner = joiner,
        interfaces = interfaces,
        station = stationMonitor,
        staApConcurrency = { staAp },
        allowStationDisplacement = allowDisplacement,
        listener = listener,
        timeouts =
            HotspotTimeouts(
                startMillis = 5_000,
                addressMillis = 1_000,
                pollMillis = 100,
                abandonWaitMillis = 2_000,
                joinMillis = 6_000,
            ),
        restore = RestorePolicy(budgetMillis = 5_000, maxWaitMillis = 10_000),
        io = StandardTestDispatcher(testScheduler),
        listenerFactory = LOOPBACK_LISTENERS,
    )

    private val request = HostRequest(LinkMode.HOTSPOT)

    @Test
    fun hostsWithTheSystemsCredentialsOnTheHotspotInterface() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val listener = RecordingListener()
            val provider = provider(radio, listener = listener)
            var handed: ActiveLink? = null
            val link = provider.host(request) { handed = it }
            assertSame(handed, link)
            assertEquals(WifiCredentials("AndroidShare_4821", "k3ns9wq2xb"), link.credentials)
            assertEquals("192.168.87.1", link.localAddress)
            assertTrue(link.localPort!! > 0)
            assertEquals(LinkRole.HOST, link.role)
            assertNull(link.frequencyMhz, "the band is hidden before API 36; the joiner measures it")
            val details = assertIs<LinkDetails.Hotspot>((link as SocketActiveLink).details)
            assertEquals("swlan0", details.interfaceName)
            assertEquals(HotspotSecurity.WPA3_SAE_TRANSITION, details.hotspot.security)
            assertTrue(details.hotspot.joinableWithWpa2)
            assertEquals(details.hotspot, provider.hosted.value, "the real SSID and password for the browser hint (N15)")
            assertIs<WifiLinkEvent.Up>(listener.events.last())

            link.teardown()
            assertEquals(1, radio.reservations.single().closed)
            assertNull(provider.hosted.value)
            assertEquals(1, listener.restores.size)
        }

    @Test
    fun theBandIsReadWhereTheSystemReportsIt() =
        runTest {
            val radio =
                FakeHotspotRadio(backgroundScope).apply {
                    config = config.copy(channels = mapOf(HotspotConfigReading.BAND_5GHZ to 36))
                }
            val link = provider(radio).host(request) {}
            assertEquals(5180, link.frequencyMhz)
            assertEquals(setOf(WifiBand.BAND_5_GHZ), assertIs<LinkDetails.Hotspot>((link as SocketActiveLink).details).hotspot.bands)
            link.teardown()
        }

    @Test
    fun startFailuresAreTyped() =
        runTest {
            val cases =
                mapOf(
                    HotspotFailureCodes.ERROR_NO_CHANNEL to WifiLinkError.NO_CHANNEL,
                    HotspotFailureCodes.ERROR_GENERIC to WifiLinkError.FAILED,
                    HotspotFailureCodes.ERROR_INCOMPATIBLE_MODE to WifiLinkError.INCOMPATIBLE_MODE,
                    HotspotFailureCodes.ERROR_TETHERING_DISALLOWED to WifiLinkError.DISALLOWED,
                )
            for ((reason, error) in cases) {
                val radio = FakeHotspotRadio(backgroundScope).apply { failReason = reason }
                val e = assertFailsWith<WifiLinkException> { provider(radio).host(request) {} }
                assertEquals(error, e.error, "reason $reason")
                assertEquals(reason, e.platformCode)
            }
        }

    @Test
    fun synchronousRefusalsAreTyped() =
        runTest {
            val cases =
                listOf(
                    SecurityException("Location mode is not enabled.") to WifiLinkError.LOCATION_OFF,
                    SecurityException("UID 10123 does not have NEARBY_WIFI_DEVICES") to WifiLinkError.PERMISSION_MISSING,
                    IllegalStateException("Caller already has an active LocalOnlyHotspot request") to WifiLinkError.BUSY,
                    UnsupportedOperationException("no soft AP") to WifiLinkError.FAILED,
                )
            for ((failure, error) in cases) {
                val radio = FakeHotspotRadio(backgroundScope).apply { startFailure = failure }
                assertEquals(error, assertFailsWith<WifiLinkException> { provider(radio).host(request) {} }.error, failure.message)
            }
        }

    @Test
    fun permissionsAndLocationAreCheckedBeforeTheCall() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val android12 = WifiPermissionContext(31, 37, { true }, locationEnabled = { false })
            assertEquals(
                WifiLinkError.LOCATION_OFF,
                assertFailsWith<WifiLinkException> {
                    provider(radio, android12).host(request) {}
                }.error,
            )
            val denied = WifiPermissionContext(34, 37, { false })
            val e = assertFailsWith<WifiLinkException> { provider(radio, denied).host(request) {} }
            assertEquals(listOf(WifiPermissions.NEARBY_WIFI_DEVICES), e.missingPermissions)
            assertEquals(0, radio.starts)
        }

    @Test
    fun aStartWithoutAnswerTimesOutAndALateReservationIsClosed() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope).apply { answers = false }
            val start = currentTime
            assertEquals(WifiLinkError.TIMEOUT, assertFailsWith<WifiLinkException> { provider(radio).host(request) {} }.error)
            assertEquals(5_000 + 2_000, currentTime - start, "the timeout, then the bounded wait for a late answer")
            radio.lateStart()
            assertEquals(1, radio.reservations.single().closed, "a reservation after the give-up is closed at once")
        }

    @Test
    fun aCancelledStartClosesTheReservationThatArrivesAfterwards() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope).apply { startDelayMillis = 1_000 }
            val provider = provider(radio)
            var handed = false
            val job = launch { provider.host(request) { handed = true } }
            advanceTimeBy(200)
            runCurrent()
            job.cancelAndJoin()
            assertEquals(1, radio.reservations.single().closed, "undone before the cancellation completed")
            assertFalse(handed)
            assertNull(provider.hosted.value)
        }

    @Test
    fun aHotspotThatNeverReachedTheLadderIsStopped() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val provider = provider(radio)
            assertFailsWith<IllegalStateException> { provider.host(request) { throw IllegalStateException("gone") } }
            assertEquals(1, radio.reservations.single().closed)
            assertNull(provider.hosted.value)
            provider.host(request) {}.teardown()
        }

    @Test
    fun aHotspotWithoutAnInterfaceAddressIsTypedAndStopped() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val e = assertFailsWith<WifiLinkException> { provider(radio, interfaces = FakeInterfaces { listOf(station) }).host(request) {} }
            assertEquals(WifiLinkError.NO_ADDRESS, e.error)
            assertEquals(1, radio.reservations.single().closed)
        }

    @Test
    fun aHotspotTheSystemStartedWithoutPassphraseIsRefused() =
        runTest {
            val radio =
                FakeHotspotRadio(backgroundScope).apply {
                    config = config.copy(securityType = HotspotSecurity.OPEN.platformValue, passphrase = null)
                }
            assertEquals(WifiLinkError.FAILED, assertFailsWith<WifiLinkException> { provider(radio).host(request) {} }.error)
            assertEquals(1, radio.reservations.single().closed)
        }

    @Test
    fun aHotspotTheUserStopsIsReportedLost() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val listener = RecordingListener()
            val link = provider(radio, listener = listener).host(request) {} as SocketActiveLink
            radio.stop()
            runCurrent()
            assertEquals(listOf(LinkKind.HOTSPOT), listener.lost)
            assertTrue(link.isLost)
            link.teardown()
            assertEquals(1, radio.reservations.single().closed)
        }

    @Test
    fun withoutStaApConcurrencyTheStationReturnsAfterTheHotspot() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val monitor = FakeStation(connected = true)
            val listener = RecordingListener()
            val link = provider(radio, stationMonitor = monitor, staAp = false, listener = listener).host(request) {}
            assertTrue(assertIs<LinkDetails.Hotspot>((link as SocketActiveLink).details).displacesStation)
            monitor.set(false)
            val teardown = async { link.teardown() }
            advanceTimeBy(2_500)
            monitor.set(true)
            teardown.await()
            val report = listener.restores.single()
            assertTrue(report.displaced && report.restored)
            assertEquals(2_500, report.durationMillis)
        }

    @Test
    fun displacingTheStationCanBeRefused() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val connected = FakeStation(connected = true)
            val e =
                assertFailsWith<WifiLinkException> {
                    provider(radio, stationMonitor = connected, allowDisplacement = false).host(request) {}
                }
            assertEquals(WifiLinkError.INCOMPATIBLE_MODE, e.error)
            assertEquals(0, radio.starts)
            val link = provider(radio, stationMonitor = connected, staAp = true, allowDisplacement = false).host(request) {}
            assertFalse(assertIs<LinkDetails.Hotspot>((link as SocketActiveLink).details).displacesStation)
            link.teardown()
        }

    @Test
    fun oneHotspotAtATime() =
        runTest {
            val radio = FakeHotspotRadio(backgroundScope)
            val provider = provider(radio)
            val link = provider.host(request) {}
            assertEquals(WifiLinkError.BUSY, assertFailsWith<WifiLinkException> { provider.host(request) {} }.error)
            link.teardown()
            provider.host(request) {}.teardown()
        }

    @Test
    fun joinsWithTheHostsCredentialsBySpecifier() =
        runTest {
            val requester = FakeRequester().apply { autoUp(frequencyMhz = 2412) }
            val joiner =
                SpecifierJoiner(requester, isForeground = { true }, clock = virtualClock(), io = StandardTestDispatcher(testScheduler))
            val provider = provider(null, joiner = joiner)
            val credentials = WifiCredentials("AndroidShare_4821", "k3ns9wq2xb")
            val link = provider.join(JoinRequest(LinkMode.HOTSPOT, credentials, hostAddress = "127.0.0.1", hostPort = 4000)) {}
            assertEquals(LinkKind.HOTSPOT, link.kind)
            assertEquals(LinkRole.JOIN, link.role)
            assertEquals(2412, link.frequencyMhz)
            assertEquals(SpecifierRequestSpec.forHotspot(credentials), requester.requests.single())
            link.teardown()
            assertEquals(1, requester.released)
        }

    @Test
    fun supportsFollowsWhatWasGiven() =
        runTest {
            val none = provider(null)
            assertFalse(none.supports(LinkMode.HOTSPOT, LinkRole.HOST))
            assertFalse(none.supports(LinkMode.HOTSPOT, LinkRole.JOIN))
            assertEquals(WifiLinkError.UNSUPPORTED, assertFailsWith<WifiLinkException> { none.host(request) {} }.error)
            val hostOnly = provider(FakeHotspotRadio(backgroundScope))
            assertTrue(hostOnly.supports(LinkMode.HOTSPOT, LinkRole.HOST))
            assertFalse(hostOnly.supports(LinkMode.P2P, LinkRole.HOST))
        }

    // ---- Configuration reading ----

    @Test
    fun readsTheSsidFromBytesOrText() {
        val base = HotspotConfigValues("Näher".encodeToByteArray(), null, "passphrase", 1, null, null)
        assertEquals("Näher", HotspotConfigReading.ssidOf(base))
        assertEquals("Legacy", HotspotConfigReading.ssidOf(base.copy(ssidBytes = null, ssidText = "\"Legacy\"")))
        assertEquals("Fallback", HotspotConfigReading.ssidOf(base.copy(ssidBytes = byteArrayOf(-1, -2), ssidText = "Fallback")))
        assertNull(HotspotConfigReading.ssidOf(base.copy(ssidBytes = null, ssidText = null)))
        assertNull(HotspotConfigReading.ssidOf(base.copy(ssidBytes = ByteArray(0), ssidText = "")))
    }

    @Test
    fun readsSecurityBandsAndAFixedChannel() {
        val base = HotspotConfigValues("s".encodeToByteArray(), null, "k3ns9wq2xb", 3, "02:aa:bb:cc:dd:ee", null)
        val sae = HotspotConfigReading.details(base)
        assertEquals(HotspotSecurity.WPA3_SAE, sae.security)
        assertFalse(sae.joinableWithWpa2, "an SAE-only hotspot is phone to phone")
        assertEquals(SpecifierSecurity.WPA3_SAE, sae.security.specifierSecurity)
        assertEquals("02:aa:bb:cc:dd:ee", sae.bssid)
        assertTrue(sae.bands.isEmpty())

        val auto = HotspotConfigReading.details(base.copy(securityType = 1, channels = mapOf(HotspotConfigReading.BAND_2GHZ to 0)))
        assertEquals(setOf(WifiBand.BAND_2_4_GHZ), auto.bands)
        assertNull(auto.frequencyMhz, "channel 0 is an automatic choice")

        val dual =
            HotspotConfigReading.details(
                base.copy(
                    channels =
                        mapOf(
                            HotspotConfigReading.BAND_2GHZ to 6,
                            HotspotConfigReading.BAND_5GHZ to 149,
                        ),
                ),
            )
        assertEquals(setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ), dual.bands)
        assertNull(dual.frequencyMhz, "two bands: the channel is not known")

        val six = HotspotConfigReading.details(base.copy(channels = mapOf(HotspotConfigReading.BAND_6GHZ to 37, 8 to 1)))
        assertEquals(setOf(WifiBand.BAND_6_GHZ), six.bands, "60 GHz is ignored")
        assertNull(HotspotConfigReading.details(base.copy(bssid = "ff:ff:ff:ff:ff:ff")).bssid)
        assertFalse(sae.toString().contains("k3ns9wq2xb"), "the passphrase is never printed")
        assertFalse(base.toString().contains("k3ns9wq2xb"))
    }

    @Test
    fun hotspotsNoPassphraseCanJoinAreRefused() {
        val base = HotspotConfigValues("s".encodeToByteArray(), null, "passphrase", 1, null, null)
        for (security in listOf(HotspotSecurity.OPEN, HotspotSecurity.WPA3_OWE, HotspotSecurity.WPA3_OWE_TRANSITION)) {
            assertEquals(
                WifiLinkError.FAILED,
                assertFailsWith<WifiLinkException> {
                    HotspotConfigReading.details(base.copy(securityType = security.platformValue))
                }.error,
            )
        }
        assertFailsWith<WifiLinkException> { HotspotConfigReading.details(base.copy(passphrase = null)) }
        assertFailsWith<WifiLinkException> { HotspotConfigReading.details(base.copy(passphrase = "short")) }
        assertFailsWith<WifiLinkException> { HotspotConfigReading.details(base.copy(ssidBytes = null)) }
        assertEquals(HotspotSecurity.UNKNOWN, HotspotConfigReading.details(base.copy(securityType = 99)).security)
    }

    @Test
    fun failureCodesAreTyped() {
        assertEquals(WifiLinkError.NO_CHANNEL, HotspotFailureCodes.error(1))
        assertEquals(WifiLinkError.FAILED, HotspotFailureCodes.error(2))
        assertEquals(WifiLinkError.INCOMPATIBLE_MODE, HotspotFailureCodes.error(3))
        assertEquals(WifiLinkError.DISALLOWED, HotspotFailureCodes.error(4))
        assertEquals(WifiLinkError.FAILED, HotspotFailureCodes.error(9))
        assertEquals("ERROR_INCOMPATIBLE_MODE", HotspotFailureCodes.describe(3))
    }
}
