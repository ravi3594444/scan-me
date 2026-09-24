package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.EphemeralId
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.platform.android.wifi.MulticastLockApi
import com.constrivo.drop.platform.android.wifi.MulticastLockHolder
import com.constrivo.drop.platform.android.wifi.WifiPermissionContext
import com.constrivo.drop.platform.android.wifi.WifiPermissions
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LAN discovery on `NsdManager` (F-A3, §5.4, N4) against an in-memory `NsdManager`: registration and its failures,
 * watched and resolved browsing, restarts with back-off, and the Android 17 local-network permission.
 */
class NsdLanDiscoveryTest {
    private class FakeNsdApi(
        override val supportsServiceWatch: Boolean = true,
    ) : NsdApi {
        val calls = CopyOnWriteArrayList<String>()
        val registrations = CopyOnWriteArrayList<NsdRegistration>()

        /** Answers a registration; null: never answers. */
        var registerAnswer: ((NsdRegistration, NsdRegistrationCallback) -> Unit)? = { r, cb -> cb.onRegistered(r.serviceName) }
        var discovery: NsdDiscoveryCallback? = null
        var startAnswer: (NsdDiscoveryCallback) -> Unit = { it.onStarted() }
        var discoverFailure: RuntimeException? = null
        val watches = ConcurrentHashMap<String, NsdWatchCallback>()
        var resolver: (NsdFoundService, NsdResolveCallback) -> Unit = { _, _ -> }

        override fun register(
            registration: NsdRegistration,
            callback: NsdRegistrationCallback,
        ): NsdHandle {
            calls += "register:${registration.serviceName}"
            registrations += registration
            registerAnswer?.invoke(registration, callback)
            return NsdHandle {
                calls += "unregister:${registration.serviceName}"
                callback.onUnregistered()
            }
        }

        override fun discover(
            serviceType: String,
            callback: NsdDiscoveryCallback,
        ): NsdHandle {
            discoverFailure?.let { throw it }
            calls += "discover:$serviceType"
            discovery = callback
            startAnswer(callback)
            return NsdHandle {
                calls += "stopDiscovery"
                if (discovery === callback) discovery = null
            }
        }

        override fun resolve(
            service: NsdFoundService,
            callback: NsdResolveCallback,
        ): NsdHandle {
            calls += "resolve:${service.serviceName}"
            resolver(service, callback)
            return NsdHandle {}
        }

        override fun watch(
            service: NsdFoundService,
            callback: NsdWatchCallback,
        ): NsdHandle {
            calls += "watch:${service.serviceName}"
            watches[service.serviceName] = callback
            return NsdHandle {
                calls += "unwatch:${service.serviceName}"
                watches.remove(service.serviceName)
            }
        }

        fun found(
            name: String,
            type: String = "_drop._tcp.",
        ) = discovery!!.onFound(NsdFoundService(name, type))

        fun lost(name: String) = discovery!!.onLost(NsdFoundService(name, "_drop._tcp."))
    }

    private val record = MdnsRecord(EphemeralId.parseHex("0123456789ab"), Capabilities(0x0029), DevicePlatform.PHONE, "Asha", 40404)
    private val service = record.toLanService("192.168.1.20")
    private val attributes: Map<String, ByteArray?> = record.toTxt().mapValues { it.value.encodeToByteArray() }

    private fun resolved(
        name: String = record.instanceName,
        hosts: List<InetAddress> = listOf(InetAddress.getByName("fe80::1%1"), InetAddress.getByName("192.168.1.20")),
        port: Int = 40404,
        txt: Map<String, ByteArray?> = attributes,
    ) = NsdResolvedService(name, port, hosts, txt)

    private fun TestScope.discovery(
        api: FakeNsdApi,
        permissions: WifiPermissionContext? = null,
        multicast: MulticastLockHolder? = null,
        errors: MutableList<String> = mutableListOf(),
    ) = NsdLanDiscovery(
        api,
        permissions,
        multicast,
        NsdTimeouts(
            registerMillis = 2_000,
            resolveMillis = 1_000,
            browseRetryMillis = 1_000,
        ),
        {
            message,
            _,
            ->
            errors +=
                message
        },
    )

    private class FakeLock : MulticastLockApi {
        override var isHeld = false

        override fun acquire() {
            isHeld = true
        }

        override fun release() {
            isHeld = false
        }
    }

    // ---- Announce ----

    @Test
    fun announcesTheRecordAndKeepsOneRegistrationLive() =
        runTest {
            val api = FakeNsdApi()
            val discovery = discovery(api)
            discovery.announce(service)
            val registration = api.registrations.single()
            assertEquals(record.instanceName, registration.serviceName)
            assertEquals(AppIdentity.MDNS_SERVICE_TYPE, registration.serviceType)
            assertEquals(40404, registration.port)
            assertEquals(record.toTxt(), registration.attributes)
            assertEquals(record.instanceName, discovery.registeredName)

            val next =
                MdnsRecord(EphemeralId.parseHex("ba9876543210"), record.capabilities, record.platform, record.nickname, record.controlPort)
            discovery.announce(next.toLanService("192.168.1.20"))
            assertEquals(
                listOf("register:${record.instanceName}", "unregister:${record.instanceName}", "register:${next.instanceName}"),
                api.calls,
            )

            discovery.withdraw()
            discovery.withdraw()
            assertEquals("unregister:${next.instanceName}", api.calls.last())
            assertEquals(4, api.calls.size)
            assertNull(discovery.registeredName)
        }

    @Test
    fun aRenamedRegistrationIsReported() =
        runTest {
            val api = FakeNsdApi().apply { registerAnswer = { r, cb -> cb.onRegistered("${r.serviceName} (2)") } }
            val discovery = discovery(api)
            discovery.announce(service)
            assertEquals("${record.instanceName} (2)", discovery.registeredName)
        }

    @Test
    fun registrationFailuresAreTypedAndUndone() =
        runTest {
            val refused = FakeNsdApi().apply { registerAnswer = { _, cb -> cb.onRegistrationFailed(NsdError.MAX_LIMIT.platformCode) } }
            assertEquals(NsdError.MAX_LIMIT, assertFailsWith<LanDiscoveryException> { discovery(refused).announce(service) }.error)
            assertEquals("unregister:${record.instanceName}", refused.calls.last())

            val silent = FakeNsdApi().apply { registerAnswer = null }
            val start = currentTime
            assertEquals(NsdError.TIMEOUT, assertFailsWith<LanDiscoveryException> { discovery(silent).announce(service) }.error)
            assertEquals(2_000, currentTime - start)
            assertEquals("unregister:${record.instanceName}", silent.calls.last())
        }

    @Test
    fun badRecordsNeverReachThePlatform() =
        runTest {
            val api = FakeNsdApi()
            val badTxt = service.copy(txt = mapOf("k=v" to "x"))
            assertEquals(NsdError.BAD_PARAMETERS, assertFailsWith<LanDiscoveryException> { discovery(api).announce(badTxt) }.error)
            assertEquals(
                NsdError.BAD_PARAMETERS,
                assertFailsWith<LanDiscoveryException> {
                    discovery(api).announce(service.copy(instanceName = "x".repeat(64)))
                }.error,
            )
            assertFailsWith<IllegalArgumentException> { discovery(api).announce(service.copy(port = 0)) }
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun android17NeedsTheLocalNetworkPermission() =
        runTest {
            val api = FakeNsdApi()
            val e = assertFailsWith<LanDiscoveryException> { discovery(api, WifiPermissionContext(37, 37, { false })).announce(service) }
            assertEquals(NsdError.PERMISSION_DENIED, e.error)
            assertEquals(listOf(WifiPermissions.ACCESS_LOCAL_NETWORK), e.missingPermissions)
            assertTrue(api.calls.isEmpty())
            discovery(api, WifiPermissionContext(36, 37, { false })).announce(service)
        }

    @Test
    fun theMulticastLockIsHeldWhileAnnounced() =
        runTest {
            val lock = FakeLock()
            val holder = MulticastLockHolder(lock)
            val discovery = discovery(FakeNsdApi(), multicast = holder)
            discovery.announce(service)
            assertTrue(lock.isHeld)
            discovery.withdraw()
            assertFalse(lock.isHeld)
        }

    // ---- Browse ----

    @Test
    fun watchedServicesAreFoundUpdatedAndLost() =
        runTest {
            val api = FakeNsdApi(supportsServiceWatch = true)
            val events = CopyOnWriteArrayList<LanEvent>()
            val job = backgroundScope.launch { discovery(api).browse().collect { events += it } }
            runCurrent()
            assertEquals(listOf("discover:${AppIdentity.MDNS_SERVICE_TYPE}"), api.calls)

            api.found(record.instanceName)
            api.found("printer", type = "_ipp._tcp.")
            runCurrent()
            assertEquals(
                listOf("discover:${AppIdentity.MDNS_SERVICE_TYPE}", "watch:${record.instanceName}"),
                api.calls,
                "other types are ignored",
            )

            api.watches.getValue(record.instanceName).onUpdated(resolved(txt = emptyMap()))
            runCurrent()
            assertTrue(events.isEmpty(), "no TXT yet: nothing to report")

            api.watches.getValue(record.instanceName).onUpdated(resolved())
            runCurrent()
            assertEquals(listOf<LanEvent>(LanEvent.Found(LanService(record.instanceName, "192.168.1.20", 40404, record.toTxt()))), events)

            api.watches.getValue(record.instanceName).onLost()
            runCurrent()
            assertEquals(LanEvent.Lost(record.instanceName), events.last())
            assertTrue("unwatch:${record.instanceName}" in api.calls)
            job.cancel()
        }

    @Test
    fun beforeApi34ServicesAreResolvedOneAtATimeAndRetriedWhenBusy() =
        runTest {
            var attempts = 0
            val api =
                FakeNsdApi(supportsServiceWatch = false).apply {
                    resolver = { service, callback ->
                        attempts++
                        if (attempts == 1) {
                            callback.onResolveFailed(NsdError.ALREADY_ACTIVE.platformCode)
                        } else {
                            callback.onResolved(resolved(name = service.serviceName))
                        }
                    }
                }
            val events = CopyOnWriteArrayList<LanEvent>()
            backgroundScope.launch { discovery(api).browse().collect { events += it } }
            runCurrent()
            api.found(record.instanceName)
            advanceTimeBy(1_000)
            assertEquals(2, attempts)
            assertEquals(listOf<LanEvent>(LanEvent.Found(LanService(record.instanceName, "192.168.1.20", 40404, record.toTxt()))), events)
            api.lost(record.instanceName)
            runCurrent()
            assertEquals(LanEvent.Lost(record.instanceName), events.last())
        }

    @Test
    fun aResolveThatFailsOrHangsIsSkipped() =
        runTest {
            val errors = mutableListOf<String>()
            val api =
                FakeNsdApi(supportsServiceWatch = false).apply {
                    // "drop-bad" fails at once; every other resolve never answers.
                    resolver = { service, callback ->
                        val bad = service.serviceName == "drop-bad"
                        if (bad) callback.onResolveFailed(NsdError.INTERNAL_ERROR.platformCode)
                    }
                }
            val events = CopyOnWriteArrayList<LanEvent>()
            backgroundScope.launch { discovery(api, errors = errors).browse().collect { events += it } }
            runCurrent()
            api.found("drop-bad")
            api.found("drop-silent")
            advanceTimeBy(1_500)
            assertTrue(events.isEmpty())
            assertEquals(2, errors.size)
        }

    @Test
    fun aFailedDiscoveryIsRestartedWithBackOff() =
        runTest {
            var starts = 0
            val errors = mutableListOf<String>()
            val api =
                FakeNsdApi().apply {
                    startAnswer =
                        { if (++starts < 3) it.onStartFailed(NsdError.INTERNAL_ERROR.platformCode) else it.onStarted() }
                }
            backgroundScope.launch { discovery(api, errors = errors).browse().collect {} }
            runCurrent()
            assertEquals(1, starts)
            advanceTimeBy(1_001)
            assertEquals(2, starts)
            advanceTimeBy(2_001)
            assertEquals(3, starts)
            assertEquals(2, errors.size)
            assertEquals(2, api.calls.count { it == "stopDiscovery" }, "each failed discovery was stopped")
        }

    @Test
    fun aStoppedDiscoveryReportsWhatItFoundAsLostAndStartsAgain() =
        runTest {
            val api = FakeNsdApi()
            val events = CopyOnWriteArrayList<LanEvent>()
            backgroundScope.launch { discovery(api).browse().collect { events += it } }
            runCurrent()
            api.found(record.instanceName)
            runCurrent()
            api.watches.getValue(record.instanceName).onUpdated(resolved())
            runCurrent()
            api.discovery!!.onStopped()
            runCurrent()
            assertEquals(LanEvent.Lost(record.instanceName), events.last())
            advanceTimeBy(1_001)
            assertEquals(2, api.calls.count { it.startsWith("discover:") })
        }

    @Test
    fun stoppingTheCollectionStopsDiscoveryAndWatches() =
        runTest {
            val lock = FakeLock()
            val api = FakeNsdApi()
            val job = backgroundScope.launch { discovery(api, multicast = MulticastLockHolder(lock)).browse().collect {} }
            runCurrent()
            assertTrue(lock.isHeld, "the lock is held while browsing")
            api.found(record.instanceName)
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue("stopDiscovery" in api.calls)
            assertTrue("unwatch:${record.instanceName}" in api.calls)
            assertFalse(lock.isHeld)
        }

    @Test
    fun browsingWaitsForTheLocalNetworkPermission() =
        runTest {
            var granted = false
            val permissions = WifiPermissionContext(37, 37, { granted })
            val errors = mutableListOf<String>()
            val api = FakeNsdApi()
            backgroundScope.launch { discovery(api, permissions, errors = errors).browse().collect {} }
            advanceTimeBy(1_500)
            assertTrue(api.calls.isEmpty(), "no discovery (and no service picker) without the permission")
            assertTrue(errors.isNotEmpty())
            granted = true
            advanceTimeBy(2_001)
            assertEquals(1, api.calls.count { it.startsWith("discover:") })
        }

    @Test
    fun theDialledAddressPrefersIpv4ThenRoutableIpv6() {
        val v4 = InetAddress.getByName("192.168.1.20")
        val global = InetAddress.getByName("2001:db8::20")
        val linkLocal = InetAddress.getByName("fe80::20%1")
        assertEquals("192.168.1.20", NsdLanDiscovery.preferredHost(listOf(linkLocal, global, v4)))
        assertEquals("2001:db8:0:0:0:0:0:20", NsdLanDiscovery.preferredHost(listOf(linkLocal, global)))
        assertEquals(linkLocal.hostAddress, NsdLanDiscovery.preferredHost(listOf(linkLocal)))
        assertNull(NsdLanDiscovery.preferredHost(listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("224.0.0.251"))))
        assertNull(NsdLanDiscovery.preferredHost(emptyList()))
    }

    @Test
    fun onlyUsableResolutionsBecomeServices() {
        assertNull(NsdLanDiscovery.toLanService("n", resolved(port = 0)))
        assertNull(NsdLanDiscovery.toLanService("n", resolved(port = 70_000)))
        assertNull(NsdLanDiscovery.toLanService("n", resolved(hosts = emptyList())))
        assertNull(NsdLanDiscovery.toLanService("n", resolved(txt = mapOf("flag" to null))))
        assertEquals("n", NsdLanDiscovery.toLanService("n", resolved())?.instanceName)
    }
}
