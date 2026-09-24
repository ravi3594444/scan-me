package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Wi-Fi Direct rung (F-E2, F-F5; S5, N7, N9) against an in-memory framework: hosting with the ladder's credentials
 * and band, the measured frequency and capability bit 4, joining with `connect(config)` and its retries, the legacy
 * join, typed failures, timeouts, and the undo of a cancelled setup (the ladder's ownership rules).
 */
class AndroidP2pLinkProviderTest {
    private val crypto = JcaCryptoProvider()
    private val credentials = P2pCredentials.random(crypto)
    private val timeouts =
        P2pTimeouts(
            actionMillis = 1_000,
            queryMillis = 500,
            formationMillis = 4_000,
            joinAttemptMillis = 2_000,
            addressMillis = 1_000,
            removeMillis = 1_000,
            pollMillis = 100,
            retryMillis = 200,
        )
    private val ownerInterfaces = listOf(iface("p2p-wlan0-0", addr("127.0.0.1", 8)))
    private val clientInterfaces = listOf(iface("p2p-wlan0-1", addr("127.0.0.1", 8)))

    private fun TestScope.provider(
        radio: FakeP2pRadio,
        listener: RecordingListener = RecordingListener(),
        permissions: WifiPermissionContext = WifiPermissionContext.allGranted(34, 37),
        interfaces: InterfaceLookup = FakeInterfaces { ownerInterfaces + clientInterfaces },
        recorder: FiveGhzHostRecorder? = null,
        joiner: SpecifierJoiner? = null,
    ) = AndroidP2pLinkProvider(
        radio = radio,
        permissions = permissions,
        clock = virtualClock(),
        legacyJoiner = joiner,
        interfaces = interfaces,
        hostRecorder = recorder,
        listener = listener,
        timeouts = timeouts,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun hostRequest(
        five: Boolean = true,
        persistent: Boolean = false,
        attempt: Int = 0,
    ) = HostRequest(LinkMode.P2P, credentials, requestFiveGhz = five, persistent = persistent, attempt = attempt)

    // ---- Host ----

    @Test
    fun hostsAGroupWithTheLaddersCredentialsAndItsMeasuredFrequency() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val listener = RecordingListener()
            val store = InMemoryVerifiedHostStore()
            val published = mutableListOf<Boolean>()
            val provider = provider(radio, listener, recorder = FiveGhzHostRecorder(store, { published += it }))
            var handed: ActiveLink? = null
            val link = provider.host(hostRequest(persistent = true)) { handed = it }

            assertSame(handed, link, "the link reaches onUp before the call returns")
            assertEquals(listOf("createGroup"), radio.calls)
            val spec = radio.specs.single()
            assertEquals(P2pBand.GHZ_5, spec.band)
            assertTrue(spec.persistent)
            assertEquals(credentials.ssid, spec.networkName)
            assertEquals(LinkKind.P2P, link.kind)
            assertEquals(LinkRole.HOST, link.role)
            assertEquals(5180, link.frequencyMhz)
            assertEquals(credentials, link.credentials)
            assertEquals("127.0.0.1", link.localAddress)
            assertNotNull(link.localPort)
            val details = assertIs<LinkDetails.WifiDirectGroup>((link as SocketActiveLink).details)
            assertEquals(WifiBand.BAND_5_GHZ, details.band)
            assertEquals("p2p-wlan0-0", details.interfaceName)
            assertTrue(store.isVerified(), "a 5 GHz group verifies capability bit 4")
            assertEquals(listOf(true), published)
            assertIs<WifiLinkEvent.Up>(listener.events.last())
            assertSame(link, provider.activeLink)

            link.teardown()
            assertEquals("removeGroup", radio.calls.last())
            assertNull(radio.group)
            assertNull(provider.activeLink)
            assertEquals(1, listener.restores.size)
            assertFalse(listener.restores.single().displaced, "a group never takes the station down")
        }

    @Test
    fun aLegacyRungHostsTheSameGroupAndA24GhzRequestStaysOn24() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { frequencyMhz = 2437 }
            val store = InMemoryVerifiedHostStore()
            val provider = provider(radio, recorder = FiveGhzHostRecorder(store, {}))
            val link = provider.host(HostRequest(LinkMode.P2P_LEGACY, credentials, requestFiveGhz = false)) {}
            assertEquals(LinkMode.P2P_LEGACY, link.mode)
            assertEquals(P2pBand.GHZ_2_4, radio.specs.single().band)
            assertEquals(2437, link.frequencyMhz)
            assertFalse(store.isVerified(), "a 2.4 GHz group verifies nothing")
            link.teardown()
        }

    @Test
    fun theReFormIsANewGroupAfterTheFirstIsGone() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { frequencyMhz = 2412 }
            val provider = provider(radio)
            val first = provider.host(hostRequest()) {}
            first.teardown()
            radio.frequencyMhz = 5745
            val second = provider.host(hostRequest(attempt = 1)) {}
            assertEquals(5745, second.frequencyMhz)
            assertEquals(1, assertIs<LinkDetails.WifiDirectGroup>((second as SocketActiveLink).details).attempt)
            assertEquals(listOf("createGroup", "removeGroup", "createGroup"), radio.calls)
            second.teardown()
        }

    @Test
    fun aRefusedCreateGroupIsTypedAndLeavesNothingToUndo() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { createResult = P2pActionResult.Failure(P2pFailureCodes.BUSY) }
            val listener = RecordingListener()
            var handed = false
            val e = assertFailsWith<WifiLinkException> { provider(radio, listener).host(hostRequest()) { handed = true } }
            assertEquals(WifiLinkError.BUSY, e.error)
            assertEquals(P2pFailureCodes.BUSY, e.platformCode)
            assertFalse(handed)
            assertEquals(listOf("createGroup"), radio.calls, "a refused request formed no group, so none is removed")
            assertEquals(WifiLinkError.BUSY, assertIs<WifiLinkEvent.SetupFailed>(listener.events.last()).error)
        }

    @Test
    fun aCreateGroupWithoutAnswerTimesOutAndIsUndone() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { createResult = null }
            val e = assertFailsWith<WifiLinkException> { provider(radio).host(hostRequest()) {} }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
            assertEquals(listOf("createGroup", "removeGroup"), radio.calls)
        }

    @Test
    fun aGroupThatNeverFormsTimesOutAndIsRemoved() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { formsGroup = false }
            val start = currentTime
            val e = assertFailsWith<WifiLinkException> { provider(radio).host(hostRequest()) {} }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
            assertTrue(currentTime - start >= timeouts.formationMillis)
            assertEquals("removeGroup", radio.calls.last())
        }

    @Test
    fun aHostCancelledWhileFormingRemovesTheGroupBeforeTheCancellationCompletes() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { formationDelayMillis = 2_000 }
            val provider = provider(radio)
            var handed = false
            val job = launch { provider.host(hostRequest()) { handed = true } }
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf("createGroup"), radio.calls)
            job.cancelAndJoin()
            assertEquals(listOf("createGroup", "removeGroup"), radio.calls)
            assertFalse(handed)
            advanceTimeBy(5_000)
            assertNull(radio.group, "the half-formed group never comes up")
            assertNull(provider.activeLink)
        }

    @Test
    fun aLinkThatNeverReachedTheLadderIsTornDown() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val listener = RecordingListener()
            val provider = provider(radio, listener)
            assertFailsWith<IllegalStateException> { provider.host(hostRequest()) { throw IllegalStateException("ladder gone") } }
            assertEquals(listOf("createGroup", "removeGroup"), radio.calls)
            assertNull(provider.activeLink)
            assertEquals(1, listener.restores.size, "the built link ran its own teardown")
            provider.host(hostRequest()) {}.teardown()
        }

    @Test
    fun missingPermissionsAreTypedBeforeAnyCall() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val e =
                assertFailsWith<WifiLinkException> {
                    provider(radio, permissions = WifiPermissionContext(34, 37, { false })).host(hostRequest()) {}
                }
            assertEquals(WifiLinkError.PERMISSION_MISSING, e.error)
            assertEquals(listOf(WifiPermissions.NEARBY_WIFI_DEVICES), e.missingPermissions)
            val android12 =
                assertFailsWith<WifiLinkException> {
                    provider(radio, permissions = WifiPermissionContext(31, 37, { false })).join(JoinRequest(LinkMode.P2P, credentials)) {}
                }
            assertEquals(listOf(WifiPermissions.ACCESS_FINE_LOCATION), android12.missingPermissions)
            assertTrue(radio.calls.isEmpty())
        }

    @Test
    fun wifiDirectOffIsTyped() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { setEnabled(false) }
            assertEquals(WifiLinkError.WIFI_OFF, assertFailsWith<WifiLinkException> { provider(radio).host(hostRequest()) {} }.error)
            assertTrue(radio.calls.isEmpty())
        }

    @Test
    fun beforeTheFirstStateBroadcastTheFrameworkIsAsked() =
        runTest {
            val radio =
                FakeP2pRadio(backgroundScope).apply {
                    forgetBroadcasts()
                    framework = false
                }
            assertEquals(
                WifiLinkError.WIFI_OFF,
                assertFailsWith<WifiLinkException> {
                    provider(radio).join(JoinRequest(LinkMode.P2P, credentials)) {}
                }.error,
            )
            radio.framework = true
            provider(radio).host(hostRequest()) {}.teardown()
        }

    @Test
    fun aStaleGroupOfThisAppIsRemovedFirstAndAnotherAppsIsLeftAlone() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { presetGroup(P2pCredentials.random(crypto).ssid) }
            val listener = RecordingListener()
            val link = provider(radio, listener).host(hostRequest()) {}
            assertEquals(listOf("removeGroup", "createGroup"), radio.calls)
            assertTrue(listener.events.any { it is WifiLinkEvent.StaleGroupRemoved })
            link.teardown()

            val foreign = FakeP2pRadio(backgroundScope).apply { presetGroup("DIRECT-xy-Android_1f2e") }
            val e = assertFailsWith<WifiLinkException> { provider(foreign).host(hostRequest()) {} }
            assertEquals(WifiLinkError.BUSY, e.error)
            assertTrue(foreign.calls.isEmpty(), "another app's group is never removed")
        }

    @Test
    fun oneGroupAtATime() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val provider = provider(radio)
            val link = provider.host(hostRequest()) {}
            assertEquals(WifiLinkError.BUSY, assertFailsWith<WifiLinkException> { provider.host(hostRequest()) {} }.error)
            link.teardown()
            provider.host(hostRequest()) {}.teardown()
        }

    @Test
    fun aVanishedGroupAndAChannelChangeAreReported() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val listener = RecordingListener()
            val link = provider(radio, listener).host(hostRequest()) {} as SocketActiveLink
            radio.changeFrequency(5745)
            runCurrent()
            assertEquals(listOf(LinkKind.P2P to 5745), listener.frequencies)
            radio.dropGroup()
            runCurrent()
            assertEquals(listOf(LinkKind.P2P), listener.lost)
            assertTrue(link.isLost)
            link.teardown()
            assertEquals(listOf(LinkKind.P2P), listener.lost, "the teardown itself is no loss")
        }

    @Test
    fun aGroupThatCameUpUnderOtherCredentialsAnnouncesTheRealOnes() =
        runTest {
            val radio =
                FakeP2pRadio(backgroundScope).apply {
                    actualNetworkName = "DIRECT-zz-Drop-abcd"
                    actualPassphrase = "anotherpassphrase"
                }
            val listener = RecordingListener()
            val link = provider(radio, listener).host(hostRequest()) {}
            assertEquals(WifiCredentials("DIRECT-zz-Drop-abcd", "anotherpassphrase"), link.credentials)
            assertTrue(listener.events.any { it is WifiLinkEvent.CredentialsDiffer })
            link.teardown()
        }

    @Test
    fun formationIsFoundWithoutBroadcasts() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { broadcasts = false }
            val link = provider(radio).host(hostRequest()) {}
            assertEquals(5180, link.frequencyMhz)
            link.teardown()
        }

    @Test
    fun badCredentialsNeverReachThePlatform() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            assertFailsWith<LinkCredentialsException> {
                provider(radio).host(HostRequest(LinkMode.P2P, WifiCredentials("NOT-DIRECT", "abcdefgh"))) {}
            }
            assertTrue(radio.calls.isEmpty())
        }

    // ---- Join ----

    @Test
    fun joinsAsAClientWithTheOwnersCredentialsAndDialsOnlyTheOwner() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val listener = RecordingListener()
            val link = provider(radio, listener).join(JoinRequest(LinkMode.P2P, credentials, requestFiveGhz = true, persistent = true)) {}
            val spec = radio.specs.single()
            assertEquals(P2pBand.AUTO, spec.band, "a client scans every band")
            assertTrue(spec.persistent)
            assertEquals(LinkRole.JOIN, link.role)
            assertNull(link.localAddress)
            assertNull(link.credentials)
            assertEquals(5180, link.frequencyMhz)
            val details = assertIs<LinkDetails.WifiDirectGroup>((link as SocketActiveLink).details)
            assertEquals("127.0.0.1", details.groupOwnerAddress)
            assertEquals("p2p-wlan0-1", details.interfaceName)
            assertFailsWith<LinkAddressRefusedException> { link.connect("10.0.0.1", 8000) }
            link.teardown()
            assertEquals("removeGroup", radio.calls.last())
        }

    @Test
    fun aBusyFrameworkIsRetriedUntilTheGroupForms() =
        runTest {
            val radio =
                FakeP2pRadio(backgroundScope).apply {
                    connectResults += P2pActionResult.Failure(P2pFailureCodes.BUSY)
                    connectResults += P2pActionResult.Failure(P2pFailureCodes.ERROR)
                }
            val listener = RecordingListener()
            val link = provider(radio, listener).join(JoinRequest(LinkMode.P2P, credentials)) {}
            assertEquals(3, radio.calls.count { it == "connect" })
            assertEquals(2, listener.events.count { it is WifiLinkEvent.Retry })
            link.teardown()
        }

    @Test
    fun anOwnerThatIsNotUpYetIsJoinedOnTheNextAttempt() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { connectFormsFromAttempt = 2 }
            val link = provider(radio).join(JoinRequest(LinkMode.P2P, credentials)) {}
            assertEquals(listOf("connect", "cancelConnect", "connect"), radio.calls)
            link.teardown()
        }

    @Test
    fun aJoinWithoutADeadlineOfItsOwnEndsAtItsBound() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { formsGroup = false }
            val provider =
                AndroidP2pLinkProvider(
                    radio,
                    WifiPermissionContext.allGranted(34),
                    virtualClock(),
                    interfaces = FakeInterfaces { clientInterfaces },
                    timeouts = timeouts.copy(joinTotalMillis = 7_000),
                    io = StandardTestDispatcher(testScheduler),
                )
            val start = currentTime
            val e = assertFailsWith<WifiLinkException> { provider.join(JoinRequest(LinkMode.P2P, credentials)) {} }
            assertEquals(WifiLinkError.TIMEOUT, e.error)
            assertTrue(currentTime - start in 7_000..9_500, "${currentTime - start} ms")
            assertTrue(radio.calls.count { it == "connect" } >= 3, "tried again while the owner was not up")
        }

    @Test
    fun anUnsupportedJoinFailsAtOnce() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { connectResults += P2pActionResult.Failure(P2pFailureCodes.P2P_UNSUPPORTED) }
            val e = assertFailsWith<WifiLinkException> { provider(radio).join(JoinRequest(LinkMode.P2P, credentials)) {} }
            assertEquals(WifiLinkError.UNSUPPORTED, e.error)
            assertEquals(listOf("connect", "cancelConnect"), radio.calls, "no group formed, so none is removed")
        }

    @Test
    fun aJoinCancelledWhileConnectingCancelsTheConnect() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope).apply { formationDelayMillis = 1_500 }
            val provider = provider(radio)
            val job = launch { provider.join(JoinRequest(LinkMode.P2P, credentials)) {} }
            advanceTimeBy(700)
            runCurrent()
            job.cancelAndJoin()
            assertEquals(listOf("connect", "cancelConnect"), radio.calls)
            advanceTimeBy(5_000)
            assertNull(radio.group)
        }

    @Test
    fun aClientWithoutAnAddressIsTypedAndLeavesTheGroup() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val e =
                assertFailsWith<WifiLinkException> {
                    provider(radio, interfaces = FakeInterfaces { emptyList() }).join(JoinRequest(LinkMode.P2P, credentials)) {}
                }
            assertEquals(WifiLinkError.NO_ADDRESS, e.error)
            assertEquals(listOf("connect", "cancelConnect", "removeGroup"), radio.calls)
        }

    @Test
    fun theClientWaitsForItsDhcpAddress() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val interfaces = FakeInterfaces { emptyList() }
            val provider = provider(radio, interfaces = interfaces)
            val joined = async { provider.join(JoinRequest(LinkMode.P2P, credentials)) {} }
            advanceTimeBy(700)
            interfaces.snapshot = { clientInterfaces }
            val link = joined.await()
            assertEquals("p2p-wlan0-1", assertIs<LinkDetails.WifiDirectGroup>((link as SocketActiveLink).details).interfaceName)
            link.teardown()
        }

    @Test
    fun aPhoneWithoutWifiDirectJoinsAsALegacyClientBySpecifier() =
        runTest {
            val radio = FakeP2pRadio(backgroundScope)
            val requester = FakeRequester().apply { autoUp() }
            val joiner =
                SpecifierJoiner(requester, isForeground = { true }, clock = virtualClock(), io = StandardTestDispatcher(testScheduler))
            val provider = provider(radio, joiner = joiner)
            assertTrue(provider.supports(LinkMode.P2P_LEGACY, LinkRole.JOIN))
            val link = provider.join(JoinRequest(LinkMode.P2P_LEGACY, credentials)) {}
            assertEquals(LinkKind.P2P, link.kind)
            assertEquals(LinkMode.P2P_LEGACY, link.mode)
            val spec = requester.requests.single()
            assertEquals(credentials.ssid, spec.ssid)
            assertEquals(SpecifierSecurity.WPA2_PSK, spec.security)
            assertTrue(radio.calls.isEmpty(), "a legacy join does not use Wi-Fi Direct")
            link.teardown()
            assertEquals(1, requester.released)
        }

    @Test
    fun supportsFollowsTheHardware() =
        runTest {
            val provider = provider(FakeP2pRadio(backgroundScope))
            assertTrue(provider.supports(LinkMode.P2P, LinkRole.HOST))
            assertTrue(provider.supports(LinkMode.P2P, LinkRole.JOIN))
            assertTrue(provider.supports(LinkMode.P2P_LEGACY, LinkRole.HOST))
            assertFalse(provider.supports(LinkMode.P2P_LEGACY, LinkRole.JOIN), "no specifier joiner")
            assertFalse(provider.supports(LinkMode.HOTSPOT, LinkRole.HOST))
            val none = provider(FakeP2pRadio(backgroundScope, isAvailable = false))
            assertFalse(none.supports(LinkMode.P2P, LinkRole.HOST))
            assertEquals(WifiLinkError.UNSUPPORTED, assertFailsWith<WifiLinkException> { none.host(hostRequest()) {} }.error)
        }

    // ---- End to end ----

    @Test
    fun anOwnerAndAClientExchangeBytesOverTheirLinks() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val clock = com.constrivo.drop.core.discovery.SystemMonotonicClock
                val fast = timeouts.copy(pollMillis = 20)
                val ownerRadio = FakeP2pRadio(scope).apply { formationDelayMillis = 20 }
                val clientRadio = FakeP2pRadio(scope).apply { formationDelayMillis = 20 }
                val owner =
                    AndroidP2pLinkProvider(
                        ownerRadio,
                        WifiPermissionContext.allGranted(34),
                        clock,
                        interfaces =
                            FakeInterfaces {
                                ownerInterfaces
                            },
                        timeouts = fast,
                    )
                val client =
                    AndroidP2pLinkProvider(
                        clientRadio,
                        WifiPermissionContext.allGranted(34),
                        clock,
                        interfaces =
                            FakeInterfaces {
                                clientInterfaces
                            },
                        timeouts = fast,
                    )
                withTimeout(10_000) {
                    val hosted = owner.host(hostRequest()) {}
                    val joined =
                        client.join(
                            JoinRequest(LinkMode.P2P, credentials, hostAddress = hosted.localAddress, hostPort = hosted.localPort),
                        ) {
                        }
                    val accepted = async { hosted.accept(hosted.localPort!!) }
                    val out = joined.connect(hosted.localAddress!!, hosted.localPort!!)
                    val incoming = accepted.await()
                    out.write("drop".encodeToByteArray())
                    val buffer = ByteArray(4)
                    var read = 0
                    while (read < 4) read += incoming.read(buffer, read, 4 - read).also { if (it < 0) throw IOException("closed") }
                    assertContentEquals("drop".encodeToByteArray(), buffer)
                    joined.teardown()
                    hosted.teardown()
                }
            } finally {
                scope.cancel()
            }
        }
}
