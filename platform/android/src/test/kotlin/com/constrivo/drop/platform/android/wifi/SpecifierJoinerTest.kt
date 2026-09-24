package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.net.TcpListener
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `WifiNetworkSpecifier` joins (F-E3, N7, N8, T-15, F-E11): request construction, the foreground and first-join
 * approval outcomes, network-bound sockets, undo on failure and cancellation, and the restore accounting of teardown.
 */
class SpecifierJoinerTest {
    private val hotspot = WifiCredentials("AndroidShare_4821", "k3ns9wq2xb")
    private val spec = SpecifierRequestSpec.forHotspot(hotspot)

    private fun TestScope.joiner(
        requester: FakeRequester,
        foreground: Boolean = true,
        approvals: JoinApprovalStore = InMemoryJoinApprovalStore(),
        station: StationMonitor? = null,
        listener: WifiLinkListener = WifiLinkListener.NONE,
    ) = SpecifierJoiner(
        requester = requester,
        isForeground = { foreground },
        approvals = approvals,
        station = station,
        listener = listener,
        clock = virtualClock(),
        restore = RestorePolicy(budgetMillis = 5_000, maxWaitMillis = 10_000),
        io = StandardTestDispatcher(testScheduler),
    )

    // ---- Request construction ----

    @Test
    fun requestsCarryWhatASpecifierCanJoin() {
        val wpa2 = SpecifierRequestSpec.forHotspot(hotspot)
        assertEquals(SpecifierSecurity.WPA2_PSK, wpa2.security)
        assertEquals("AndroidShare_4821", wpa2.ssid)
        assertFalse(wpa2.toString().contains(hotspot.passphrase), "the passphrase is never printed")
        assertEquals(SpecifierSecurity.WPA3_SAE, SpecifierRequestSpec.forHotspot(hotspot, SpecifierSecurity.WPA3_SAE).security)
        assertEquals("DIRECT-ab-Drop-cdef", SpecifierRequestSpec.forGroup(WifiCredentials("DIRECT-ab-Drop-cdef", "passphrase12")).ssid)
        assertEquals(
            SpecifierRequestSpec("s", "12345678", bssid = "02:11:22:33:44:55"),
            SpecifierRequestSpec("s", "12345678", bssid = "02:11:22:33:44:55"),
        )
    }

    @Test
    fun whatNoSpecifierCanJoinIsRefused() {
        assertFailsWith<LinkCredentialsException> { SpecifierRequestSpec("", "12345678") }
        assertFailsWith<LinkCredentialsException> { SpecifierRequestSpec("x".repeat(33), "12345678") }
        assertFailsWith<LinkCredentialsException> { SpecifierRequestSpec("ssid", "short") }
        assertFailsWith<LinkCredentialsException> { SpecifierRequestSpec("ssid", "tab\tin-the-passphrase") }
        assertFailsWith<LinkCredentialsException>("a raw PSK") { SpecifierRequestSpec.forHotspot(WifiCredentials("ssid", "ab".repeat(32))) }
        assertFailsWith<LinkCredentialsException>("a group needs a DIRECT- name") { SpecifierRequestSpec.forGroup(hotspot) }
        for (bssid in listOf("01:11:22:33:44:55", "00:00:00:00:00:00", "02:11:22:33:44", "02-11-22-33-44-55", "zz:11:22:33:44:55")) {
            assertFailsWith<LinkCredentialsException>(bssid) { SpecifierRequestSpec("ssid", "12345678", bssid = bssid) }
        }
    }

    // ---- Joining ----

    @Test
    fun joinsWithNetworkBoundSocketsAndTheJoinedChannel() =
        runTest {
            val requester = FakeRequester().apply { autoUp(handle = 42, frequencyMhz = 5220) }
            val listener = RecordingListener()
            var handed: SocketActiveLink? = null
            val link = joiner(requester, listener = listener).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) { handed = it }
            assertEquals(link, handed)
            assertEquals(spec, requester.requests.single())
            assertEquals(LinkKind.HOTSPOT, link.kind)
            assertEquals(LinkRole.JOIN, link.role)
            assertEquals(5220, link.frequencyMhz)
            val details = assertIs<LinkDetails.JoinedNetwork>(link.details)
            assertEquals(42, details.networkHandle)
            assertEquals("wlan1", details.interfaceName)
            assertEquals(listOf("AndroidShare_4821"), listener.approvals, "the first join per SSID shows the system dialog")
            assertIs<WifiLinkEvent.Up>(listener.events.single())
            link.teardown()
            assertEquals(1, requester.released)
        }

    @Test
    fun anSsidJoinedBeforeNeedsNoApprovalAgain() =
        runTest {
            val approvals = InMemoryJoinApprovalStore()
            val listener = RecordingListener()
            val requester = FakeRequester().apply { autoUp() }
            joiner(requester, approvals = approvals, listener = listener).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {
            }.teardown()
            joiner(requester, approvals = approvals, listener = listener).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {
            }.teardown()
            assertEquals(listOf(spec.ssid), listener.approvals)
            assertTrue(approvals.isApproved(spec.ssid))
        }

    @Test
    fun theLinksSocketsAreBoundToTheJoinedNetwork() =
        runBlocking {
            val requester = FakeRequester().apply { autoUp() }
            val joiner =
                SpecifierJoiner(requester, isForeground = { true }, clock = com.constrivo.drop.core.discovery.SystemMonotonicClock)
            val link = joiner.join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            TcpListener(InetSocketAddress(LOOPBACK, 0), LinkKind.HOTSPOT).use { server ->
                val accepted = async { server.accept() }
                link.connect("127.0.0.1", server.port).close()
                accepted.await().close()
            }
            assertEquals(1, requester.binder.bound.size, "the socket went through the network's binder")
            assertFailsWith<LinkAddressRefusedException>("outside the hotspot's subnet") { link.connect("192.168.1.1", 80) }
            link.teardown()
        }

    @Test
    fun aBackgroundJoinFailsWithoutFilingARequest() =
        runTest {
            val requester = FakeRequester()
            val e =
                assertFailsWith<WifiLinkException> {
                    joiner(requester, foreground = false).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
                }
            assertEquals(WifiLinkError.BACKGROUND, e.error)
            assertTrue(requester.requests.isEmpty())
        }

    @Test
    fun anUnavailableNetworkIsTypedAndReleased() =
        runTest {
            val requester = FakeRequester().apply { script += NetworkEvent.Unavailable }
            val e = assertFailsWith<WifiLinkException> { joiner(requester).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {} }
            assertEquals(WifiLinkError.JOIN_UNAVAILABLE, e.error)
            assertEquals(1, requester.released)
        }

    @Test
    fun aNetworkLostBeforeItHadAnAddressIsTyped() =
        runTest {
            val binder = RecordingBinder()
            val requester =
                FakeRequester().apply {
                    script += NetworkEvent.Available(7, binder)
                    script += NetworkEvent.Lost(7)
                }
            assertEquals(
                WifiLinkError.LOST,
                assertFailsWith<WifiLinkException> {
                    joiner(requester).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
                }.error,
            )
            assertEquals(1, requester.released)
        }

    @Test
    fun aJoinThatNeverCompletesTimesOutAndIsReleased() =
        runTest {
            val requester = FakeRequester().apply { script += NetworkEvent.Available(7, RecordingBinder()) }
            val start = currentTime
            val e = assertFailsWith<WifiLinkException> { joiner(requester).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 3_000) {} }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
            assertEquals(3_000, currentTime - start)
            assertEquals(1, requester.released)
        }

    @Test
    fun aCancelledJoinReleasesTheRequestBeforeTheCancellationCompletes() =
        runTest {
            val requester = FakeRequester()
            var handed = false
            val job = launch { joiner(requester).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) { handed = true } }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, requester.requests.size)
            job.cancelAndJoin()
            assertEquals(1, requester.released)
            assertFalse(handed)
        }

    @Test
    fun aRefusedRequestIsTyped() =
        runTest {
            val security = FakeRequester().apply { failure = SecurityException("no CHANGE_NETWORK_STATE") }
            assertEquals(
                WifiLinkError.PERMISSION_MISSING,
                assertFailsWith<WifiLinkException> {
                    joiner(security).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
                }.error,
            )
            val tooMany = FakeRequester().apply { failure = IllegalStateException("too many requests") }
            assertEquals(
                WifiLinkError.FAILED,
                assertFailsWith<WifiLinkException> {
                    joiner(tooMany).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
                }.error,
            )
        }

    @Test
    fun oneJoinedNetworkAtATime() =
        runTest {
            val requester = FakeRequester().apply { autoUp() }
            val joiner = joiner(requester)
            val first = joiner.join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            assertEquals(first, joiner.activeLink)
            val group = SpecifierRequestSpec.forGroup(WifiCredentials("DIRECT-ab-Drop-cdef", "passphrase12"))
            assertEquals(
                WifiLinkError.BUSY,
                assertFailsWith<WifiLinkException> {
                    joiner.join(group, LinkKind.P2P, LinkMode.P2P_LEGACY, 6_000) {}
                }.error,
            )
            assertEquals(1, requester.requests.size)
            first.teardown()
            joiner.join(group, LinkKind.P2P, LinkMode.P2P_LEGACY, 6_000) {}.teardown()
            assertEquals(2, requester.released)
        }

    @Test
    fun aJoinedLinkThatNeverReachedTheLadderReleasesItsRequest() =
        runTest {
            val requester = FakeRequester().apply { autoUp() }
            val joiner = joiner(requester)
            assertFailsWith<IllegalStateException> {
                joiner.join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) { throw IllegalStateException("gone") }
            }
            assertEquals(1, requester.released)
            assertEquals(null, joiner.activeLink)
        }

    @Test
    fun onlyHotspotsAndGroupsAreJoinedBySpecifier() =
        runTest {
            assertFailsWith<IllegalArgumentException> { joiner(FakeRequester()).join(spec, LinkKind.LAN, LinkMode.LAN, 6_000) {} }
            assertFailsWith<IllegalArgumentException> { joiner(FakeRequester()).join(spec, LinkKind.P2P, LinkMode.P2P, 6_000) {} }
        }

    @Test
    fun aJoinedNetworksLossAndChannelChangesAreReported() =
        runTest {
            val requester = FakeRequester().apply { autoUp(handle = 9, frequencyMhz = 2437) }
            val listener = RecordingListener()
            val link = joiner(requester, listener = listener).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            runCurrent()
            requester.emit(NetworkEvent.CapabilitiesChanged(9, 2437))
            requester.emit(NetworkEvent.CapabilitiesChanged(9, 2462))
            requester.emit(NetworkEvent.CapabilitiesChanged(8, 5180))
            runCurrent()
            assertEquals(listOf(LinkKind.HOTSPOT to 2462), listener.frequencies)
            requester.emit(NetworkEvent.Lost(9))
            runCurrent()
            assertEquals(listOf(LinkKind.HOTSPOT), listener.lost)
            assertTrue(link.isLost)
            link.teardown()
        }

    // ---- Restore (F-E11) ----

    @Test
    fun aDisplacedStationIsAwaitedAndMeasuredAgainstTheBudget() =
        runTest {
            val station = FakeStation(connected = true)
            val requester = FakeRequester().apply { autoUp() }
            val listener = RecordingListener()
            val link = joiner(requester, station = station, listener = listener).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            station.set(false) // the join took the station's radio
            val teardown = async { link.teardown() }
            advanceTimeBy(3_000)
            station.set(true)
            teardown.await()
            val report = listener.restores.single()
            assertTrue(report.displaced && report.restored)
            assertEquals(3_000, report.durationMillis)
            assertFalse(report.overdue)
        }

    @Test
    fun aSlowRestoreIsOverdueAndANeverRestoredOneEndsAtTheWait() =
        runTest {
            val slowStation = FakeStation(connected = true)
            val slow = RecordingListener()
            val first =
                joiner(
                    FakeRequester().apply {
                        autoUp()
                    },
                    station = slowStation,
                    listener = slow,
                ).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            slowStation.set(false)
            val teardown = async { first.teardown() }
            advanceTimeBy(7_000)
            slowStation.set(true)
            teardown.await()
            assertTrue(slow.restores.single().overdue)

            val gone = FakeStation(connected = true)
            val never = RecordingListener()
            val second =
                joiner(
                    FakeRequester().apply {
                        autoUp()
                    },
                    station = gone,
                    listener = never,
                ).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            gone.set(false)
            val start = currentTime
            second.teardown()
            assertEquals(10_000, currentTime - start)
            val report = never.restores.single()
            assertFalse(report.restored)
            assertTrue(report.overdue)
        }

    @Test
    fun aStationThatStayedUpIsNotWaitedFor() =
        runTest {
            val station = FakeStation(connected = true)
            val listener = RecordingListener()
            val link =
                joiner(
                    FakeRequester().apply {
                        autoUp()
                    },
                    station = station,
                    listener = listener,
                ).join(spec, LinkKind.HOTSPOT, LinkMode.HOTSPOT, 6_000) {}
            val start = currentTime
            link.teardown()
            assertEquals(0, currentTime - start)
            assertFalse(listener.restores.single().displaced)
        }
}
