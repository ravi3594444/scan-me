package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.discovery.LanDiscovery
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import com.constrivo.drop.platform.android.wifi.MulticastLockHolder
import com.constrivo.drop.platform.android.wifi.WifiOperation
import com.constrivo.drop.platform.android.wifi.WifiPermissionContext
import com.constrivo.drop.platform.android.wifi.withTimeoutOrThrow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Timeouts and back-off of [NsdLanDiscovery].
 *
 * @property resolveAttempts tries of one resolve that `NsdManager` refused with `FAILURE_ALREADY_ACTIVE` (before API 34
 *   one resolve runs at a time).
 */
data class NsdTimeouts(
    val registerMillis: Long = 5_000,
    val unregisterMillis: Long = 2_000,
    val resolveMillis: Long = 5_000,
    val resolveRetryMillis: Long = 200,
    val resolveAttempts: Int = 3,
    val browseRetryMillis: Long = 1_000,
    val browseRetryMaxMillis: Long = 30_000,
) {
    init {
        require(
            listOf(registerMillis, unregisterMillis, resolveMillis, resolveRetryMillis, browseRetryMillis).all { it > 0 } &&
                resolveAttempts > 0 && browseRetryMaxMillis >= browseRetryMillis,
        ) { "NSD timeouts must be positive" }
    }
}

/**
 * core/discovery's [LanDiscovery] on `NsdManager` (architecture §5.4 and §8 "LAN discovery", F-A3): announces this
 * device's DNS-SD record of type [AppIdentity.MDNS_SERVICE_TYPE] with the TXT keys of `MdnsRecord`
 * ([NsdTxtMapping]) and browses for other devices.
 *
 * - **Announce.** [announce] registers the instance and returns once `NsdManager` confirmed it
 *   ([LanDiscoveryException] with the platform's code, or [NsdError.TIMEOUT]); announcing again (the next epoch's
 *   instance name, N4) first withdraws the previous registration, so exactly one record is live. [withdraw] sends the
 *   goodbye. Android may rename an instance to resolve a conflict ([registeredName]); peers read the TXT, not the name.
 * - **Browse.** [browse] is a cold flow; each collector runs its own discovery. On API 34+ every found instance is
 *   watched with `registerServiceInfoCallback`, which reports its current addresses and TXT and when it goes; before,
 *   found instances are resolved once each, one at a time (the platform allows only one resolve), retried while the
 *   platform is busy. A resolved instance is [LanEvent.Found] with its IPv4 address first (then a routable IPv6, then a
 *   link-local one with its scope); lost ones are [LanEvent.Lost]. A discovery that fails or stops is started again
 *   with back-off and what it had found is reported lost; errors go to [onError] and the flow itself never fails.
 *   Nothing here is authenticated: `NearbyDevices` parses the TXT and the handshake checks the identity.
 * - **Permissions.** On Android 17 an app targeting API 37 needs `ACCESS_LOCAL_NETWORK` (without it `NsdManager` would
 *   show a service picker): [announce] fails with [NsdError.PERMISSION_DENIED] and the permissions to request, and
 *   [browse] waits, retrying, until it is granted.
 * - **Multicast.** Before the Tiramisu SDK extension 7 `NsdManager` needs the app to hold a Wi-Fi multicast lock:
 *   pass [multicast] then ([MulticastLockHolder.needsLockForNsd]); a hold is kept while announced and while browsing.
 *
 * Thread-safe.
 */
class NsdLanDiscovery(
    private val api: NsdApi,
    private val permissions: WifiPermissionContext? = null,
    private val multicast: MulticastLockHolder? = null,
    private val timeouts: NsdTimeouts = NsdTimeouts(),
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) : LanDiscovery {
    private val mutex = Mutex()
    private var current: Registration? = null

    @Volatile private var currentName: String? = null

    /** The instance name the platform registered the current announcement under, or null when none is live. */
    val registeredName: String? get() = currentName

    /**
     * Registers [service], replacing the previous announcement.
     *
     * @throws LanDiscoveryException when the platform refuses or does not answer, the TXT breaks the DNS-SD rules
     *   ([NsdError.BAD_PARAMETERS]), or a permission is missing ([NsdError.PERMISSION_DENIED]).
     * @throws IllegalArgumentException for a port outside 1–65535 or a blank instance name.
     */
    override suspend fun announce(service: LanService) {
        require(service.port in 1..MAX_PORT) { "port ${service.port} out of range" }
        require(service.instanceName.isNotBlank()) { "instance name must not be blank" }
        if (service.instanceName.encodeToByteArray().size > MAX_INSTANCE_BYTES) {
            throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "instance name longer than $MAX_INSTANCE_BYTES bytes")
        }
        requirePermission()
        val attributes = NsdTxtMapping.toAttributes(service.txt)
        mutex.withLock {
            unregisterLocked()
            val registration =
                registerLocked(NsdRegistration(service.instanceName, AppIdentity.MDNS_SERVICE_TYPE, service.port, attributes))
            current = registration
            currentName = registration.name
        }
    }

    override suspend fun withdraw() {
        mutex.withLock { unregisterLocked() }
    }

    override fun browse(): Flow<LanEvent> =
        channelFlow {
            val hold = multicast?.hold("nsd-browse")
            try {
                var backoff = timeouts.browseRetryMillis
                while (true) {
                    val missing = permissions?.missing(WifiOperation.LOCAL_NETWORK).orEmpty()
                    val ran =
                        if (missing.isNotEmpty()) {
                            onError("browsing the LAN needs ${missing.joinToString()}", null)
                            false
                        } else {
                            browseSession(this)
                        }
                    if (ran) backoff = timeouts.browseRetryMillis
                    delay(backoff)
                    backoff = minOf(backoff * 2, timeouts.browseRetryMaxMillis)
                }
            } finally {
                hold?.close()
            }
        }.buffer(Channel.UNLIMITED)

    // ---- Announce ----

    private class Registration(
        val handle: NsdHandle,
        val name: String,
        val unregistered: CompletableDeferred<Unit>,
        val hold: MulticastLockHolder.Hold?,
    )

    private suspend fun registerLocked(registration: NsdRegistration): Registration {
        val registered = CompletableDeferred<String>()
        val unregistered = CompletableDeferred<Unit>()
        val callback =
            object : NsdRegistrationCallback {
                override fun onRegistered(serviceName: String) {
                    registered.complete(serviceName.ifBlank { registration.serviceName })
                }

                override fun onRegistrationFailed(errorCode: Int) {
                    registered.completeExceptionally(
                        LanDiscoveryException(NsdError.fromPlatform(errorCode), "registerService failed with code $errorCode"),
                    )
                }

                override fun onUnregistered() {
                    unregistered.complete(Unit)
                }

                override fun onUnregistrationFailed(errorCode: Int) {
                    unregistered.complete(Unit)
                    onError("unregisterService failed with code $errorCode", null)
                }
            }
        val handle =
            try {
                api.register(registration, callback)
            } catch (e: IllegalArgumentException) {
                throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "registerService refused: ${e.message}", cause = e)
            } catch (e: RuntimeException) {
                throw LanDiscoveryException(NsdError.INTERNAL_ERROR, "registerService refused: ${e.message}", cause = e)
            }
        var done = false
        try {
            val name =
                withTimeoutOrThrow(timeouts.registerMillis, { LanDiscoveryException(NsdError.TIMEOUT, "no answer to registerService") }) {
                    registered.await()
                }
            val hold = multicast?.hold("nsd-announce")
            done = true
            return Registration(handle, name, unregistered, hold)
        } finally {
            if (!done) withContext(NonCancellable) { handle.close() }
        }
    }

    private suspend fun unregisterLocked() {
        val registration = current ?: return
        current = null
        currentName = null
        withContext(NonCancellable) {
            registration.handle.close()
            withTimeoutOrNull(timeouts.unregisterMillis) { registration.unregistered.await() }
            registration.hold?.close()
        }
    }

    private fun requirePermission() {
        val missing = permissions?.missing(WifiOperation.LOCAL_NETWORK).orEmpty()
        if (missing.isNotEmpty()) {
            throw LanDiscoveryException(NsdError.PERMISSION_DENIED, "announcing on the LAN needs ${missing.joinToString()}", missing)
        }
    }

    // ---- Browse ----

    private sealed interface BrowseEvent {
        data object Started : BrowseEvent

        data class StartFailed(
            val code: Int,
        ) : BrowseEvent

        data class Found(
            val service: NsdFoundService,
        ) : BrowseEvent

        data class Lost(
            val name: String,
        ) : BrowseEvent

        data class Resolved(
            val name: String,
            val service: NsdResolvedService,
        ) : BrowseEvent

        data class WatchFailed(
            val name: String,
            val code: Int,
        ) : BrowseEvent

        data object Stopped : BrowseEvent
    }

    /** One discovery until it fails or stops; true when it had started. Throws only a cancellation. */
    private suspend fun browseSession(out: SendChannel<LanEvent>): Boolean =
        coroutineScope {
            val events = Channel<BrowseEvent>(Channel.UNLIMITED)
            val handle =
                try {
                    api.discover(AppIdentity.MDNS_SERVICE_TYPE, discoveryCallback(events))
                } catch (e: RuntimeException) {
                    onError("discoverServices refused", e)
                    return@coroutineScope false
                }
            val watches = HashMap<String, NsdHandle>()
            val reported = LinkedHashSet<String>()
            val resolveQueue = Channel<NsdFoundService>(Channel.UNLIMITED)
            val resolver =
                if (api.supportsServiceWatch) {
                    null
                } else {
                    launch {
                        for (service in resolveQueue) {
                            resolveOnce(service)?.let {
                                events.send(BrowseEvent.Resolved(service.serviceName, it))
                            }
                        }
                    }
                }
            var started = false
            try {
                loop@ for (event in events) {
                    when (event) {
                        BrowseEvent.Started -> {
                            started = true
                        }

                        is BrowseEvent.StartFailed -> {
                            onError("discoverServices failed with code ${event.code}", null)
                            break@loop
                        }

                        is BrowseEvent.Found -> {
                            val service = event.service
                            if (!NsdTxtMapping.isOwnServiceType(service.serviceType) || service.serviceName.isBlank()) continue@loop
                            if (api.supportsServiceWatch) {
                                if (service.serviceName !in watches) watches[service.serviceName] = watch(service, events)
                            } else {
                                resolveQueue.send(service)
                            }
                        }

                        is BrowseEvent.Lost -> {
                            watches.remove(event.name)?.close()
                            if (reported.remove(event.name)) out.send(LanEvent.Lost(event.name))
                        }

                        is BrowseEvent.Resolved -> {
                            val service = toLanService(event.name, event.service)
                            if (service != null) {
                                reported += event.name
                                out.send(LanEvent.Found(service))
                            }
                        }

                        is BrowseEvent.WatchFailed -> {
                            watches.remove(event.name)?.close()
                            onError("watching ${event.name} failed with code ${event.code}", null)
                        }

                        BrowseEvent.Stopped -> {
                            break@loop
                        }
                    }
                }
                // The discovery ended (failed or stopped): nothing it found is followed any more.
                for (name in reported) out.send(LanEvent.Lost(name))
                started
            } finally {
                resolver?.cancel()
                resolveQueue.close()
                withContext(NonCancellable) {
                    watches.values.forEach { it.close() }
                    handle.close()
                }
            }
        }

    private fun discoveryCallback(events: SendChannel<BrowseEvent>): NsdDiscoveryCallback =
        object : NsdDiscoveryCallback {
            override fun onStarted() {
                events.trySend(BrowseEvent.Started)
            }

            override fun onStartFailed(errorCode: Int) {
                events.trySend(BrowseEvent.StartFailed(errorCode))
            }

            override fun onFound(service: NsdFoundService) {
                events.trySend(BrowseEvent.Found(service))
            }

            override fun onLost(service: NsdFoundService) {
                events.trySend(BrowseEvent.Lost(service.serviceName))
            }

            override fun onStopped() {
                events.trySend(BrowseEvent.Stopped)
            }
        }

    private fun watch(
        found: NsdFoundService,
        events: SendChannel<BrowseEvent>,
    ): NsdHandle {
        val name = found.serviceName
        return try {
            api.watch(
                found,
                object : NsdWatchCallback {
                    override fun onUpdated(service: NsdResolvedService) {
                        events.trySend(BrowseEvent.Resolved(name, service))
                    }

                    override fun onLost() {
                        events.trySend(BrowseEvent.Lost(name))
                    }

                    override fun onFailed(errorCode: Int) {
                        events.trySend(BrowseEvent.WatchFailed(name, errorCode))
                    }
                },
            )
        } catch (e: RuntimeException) {
            onError("registerServiceInfoCallback refused for $name", e)
            NsdHandle {}
        }
    }

    private sealed interface ResolveOutcome {
        class Resolved(
            val service: NsdResolvedService,
        ) : ResolveOutcome

        class Failed(
            val code: Int,
        ) : ResolveOutcome
    }

    /** Resolves [service] once, retrying while the platform is busy with another resolve; null when it cannot. */
    private suspend fun resolveOnce(service: NsdFoundService): NsdResolvedService? {
        repeat(timeouts.resolveAttempts) {
            val result = CompletableDeferred<ResolveOutcome>()
            val handle =
                try {
                    api.resolve(
                        service,
                        object : NsdResolveCallback {
                            override fun onResolved(service: NsdResolvedService) {
                                result.complete(ResolveOutcome.Resolved(service))
                            }

                            override fun onResolveFailed(errorCode: Int) {
                                result.complete(ResolveOutcome.Failed(errorCode))
                            }
                        },
                    )
                } catch (e: RuntimeException) {
                    onError("resolveService refused for ${service.serviceName}", e)
                    return null
                }
            val outcome =
                try {
                    withTimeoutOrNull(timeouts.resolveMillis) { result.await() }
                } finally {
                    handle.close()
                }
            when (outcome) {
                is ResolveOutcome.Resolved -> {
                    return outcome.service
                }

                is ResolveOutcome.Failed -> {
                    if (outcome.code != NsdError.ALREADY_ACTIVE.platformCode) {
                        onError("resolving ${service.serviceName} failed with code ${outcome.code}", null)
                        return null
                    }
                }

                null -> {
                    onError("resolving ${service.serviceName} timed out", null)
                    return null
                }
            }
            delay(timeouts.resolveRetryMillis)
        }
        return null
    }

    companion object {
        private const val MAX_PORT = 65535

        /** A DNS label: the instance name is one. */
        private const val MAX_INSTANCE_BYTES = 63

        /**
         * A resolved service as a [LanService] named [name]: its preferred address ([preferredHost]), port and TXT.
         * Null without a usable address, with a port outside 1–65535, or without TXT keys yet (a later update may bring
         * them).
         */
        fun toLanService(
            name: String,
            resolved: NsdResolvedService,
        ): LanService? {
            if (resolved.port !in 1..MAX_PORT) return null
            val host = preferredHost(resolved.hosts) ?: return null
            val txt = NsdTxtMapping.fromAttributes(resolved.attributes)
            if (txt.isEmpty()) return null
            return LanService(name, host, resolved.port, txt)
        }

        /**
         * The address to dial among [hosts]: IPv4 first, then a routable IPv6 address, then a link-local IPv6 address
         * with its scope (dialable on Android as `fe80::…%wlan0`). Loopback, wildcard and multicast addresses never.
         */
        fun preferredHost(hosts: List<InetAddress>): String? {
            val usable = hosts.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
            val pick =
                usable.firstOrNull { it is Inet4Address }
                    ?: usable.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }
                    ?: usable.firstOrNull { it is Inet6Address }
            return pick?.hostAddress
        }
    }
}
