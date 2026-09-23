package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/**
 * The radar's model (F‑A2, F‑A3, F‑A5, design §3.2–3.3): merges the Bluetooth sightings of [BeaconRadio.scan] and the
 * mDNS events of [LanDiscovery.browse] into one [StateFlow] of immutable [NearbyDevice] lists.
 *
 * All rules live in [NearbyDeviceTracker]; this class feeds it from the flows and stamps every event with both clocks:
 * [monotonicClock] for every duration (expiry 5 s after the last beacon, smoothing windows, alarms), [wallClock] only
 * for the epoch of the rotating IDs, so a wall-clock correction neither keeps departed devices nor drops present ones.
 * It wakes itself exactly when a smoothing window closes, a device is due to leave or a held-back publish is due, so
 * it does no periodic work while the radar is empty. Events that arrive together are applied as one batch, and the
 * list is republished at most every [NearbyConfig.minPublishIntervalMillis] (10 Hz), so a flood of packets costs
 * neither a list per packet nor a timer per packet. Time comes only from the two clocks and the coroutine dispatcher's
 * `delay`, so a test dispatcher with a monotonic clock reading its virtual time makes it fully deterministic.
 */
class NearbyDevices(
    crypto: CryptoProvider,
    private val wallClock: WallClock,
    private val monotonicClock: MonotonicClock,
    private val config: NearbyConfig = NearbyConfig(),
) {
    private val tracker = NearbyDeviceTracker(crypto, config)
    private val running = Mutex()
    private val state = MutableStateFlow<List<NearbyDevice>>(emptyList())
    private val counterState = MutableStateFlow(DiscoveryCounters())

    @Volatile
    private var linkRequests: SendChannel<Event.Link>? = null

    /** Nearby devices, nearest ring first. Empty while [run] is not running. */
    val devices: StateFlow<List<NearbyDevice>> = state.asStateFlow()

    /** Drop counters of the current run, for the diagnostics log. */
    val counters: StateFlow<DiscoveryCounters> = counterState.asStateFlow()

    /**
     * Collects [sightings], [lanEvents] and [trust] until cancelled; never returns normally. [trust] carries this
     * device's own advertising secrets together with the trusted peers, so a rotation of the own `k_adv` ("Forget",
     * "Reset identity") takes effect without restarting the radar. A source flow that completes simply stops
     * contributing; one that fails ends the run with its exception, so platform flows should handle their own radio
     * errors. When the run stops, [devices] becomes empty, so a radar reopened later starts clean.
     *
     * @throws IllegalStateException if another [run] of this instance is active.
     */
    suspend fun run(
        sightings: Flow<BeaconSighting>,
        lanEvents: Flow<LanEvent> = emptyFlow(),
        trust: Flow<TrustState> = flowOf(TrustState()),
    ) {
        check(running.tryLock()) { "NearbyDevices.run is already active" }
        try {
            tracker.clear()
            coroutineScope {
                val events = Channel<Event>(Channel.BUFFERED)
                val links = Channel<Event.Link>(Channel.UNLIMITED)
                linkRequests = links
                launch { trust.collect { events.send(Event.Trust(it)) } }
                launch { sightings.collect { events.send(Event.Sighting(it)) } }
                launch { lanEvents.collect { events.send(Event.Lan(it)) } }
                launch { for (request in links) events.send(request) }
                val alarm = MutableStateFlow<Alarm?>(null)
                launch {
                    alarm.collectLatest { a ->
                        if (a != null) {
                            val wait = a.atMillis - monotonicClock.elapsedMillis()
                            if (wait > 0) delay(wait)
                            events.send(Event.Tick(a.generation))
                        }
                    }
                }
                var generation = 0L
                var armedAt: Long? = null
                var dirty = false
                var lastPublished: Long? = null
                for (first in events) {
                    var event: Event? = first
                    while (event != null) {
                        val now = read()
                        if (tracker.advanceTo(now.elapsedMillis)) dirty = true
                        val changed =
                            when (event) {
                                is Event.Sighting -> tracker.onSighting(event.sighting, now)
                                is Event.Lan -> tracker.onLanEvent(event.event, now)
                                is Event.Trust -> tracker.setTrust(event.trust, now)
                                is Event.Link -> tracker.link(event.key, event.nextId)
                                is Event.Tick -> false.also { if (event.generation == generation) armedAt = null }
                            }
                        if (changed) dirty = true
                        event = events.tryReceive().getOrNull()
                    }
                    val now = monotonicClock.elapsedMillis()
                    if (tracker.advanceTo(now)) dirty = true
                    counterState.value = tracker.counters
                    var publishAt: Long? = null
                    if (dirty) {
                        val last = lastPublished
                        if (last == null || now - last >= config.minPublishIntervalMillis) {
                            state.value = tracker.snapshot()
                            lastPublished = now
                            dirty = false
                        } else {
                            publishAt = last + config.minPublishIntervalMillis
                        }
                    }
                    val wake = listOfNotNull(tracker.nextDeadlineMillis(), publishAt).minOrNull()
                    if (wake != armedAt) {
                        armedAt = wake
                        alarm.value = wake?.let { Alarm(it, ++generation) }
                    }
                }
            }
        } finally {
            linkRequests = null
            tracker.clear()
            state.value = emptyList()
            running.unlock()
        }
    }

    /** Starts [run] in [scope]. */
    fun launchIn(
        scope: CoroutineScope,
        sightings: Flow<BeaconSighting>,
        lanEvents: Flow<LanEvent> = emptyFlow(),
        trust: Flow<TrustState> = flowOf(TrustState()),
    ): Job = scope.launch { run(sightings, lanEvents, trust) }

    /**
     * Asks the running radar to keep bubble [key] for the device's next rotating ID [nextId], learned over an
     * authenticated session ([NearbyDeviceTracker.link]); applied in order with the other events.
     *
     * @return false when no [run] is active (the request is dropped).
     */
    fun link(
        key: String,
        nextId: EphemeralId,
    ): Boolean = linkRequests?.trySend(Event.Link(key, nextId))?.isSuccess ?: false

    private fun read(): ClockReading = ClockReading(monotonicClock.elapsedMillis(), wallClock.nowMillis().coerceAtLeast(0))

    private sealed interface Event {
        class Sighting(
            val sighting: BeaconSighting,
        ) : Event

        class Lan(
            val event: LanEvent,
        ) : Event

        class Trust(
            val trust: TrustState,
        ) : Event

        class Link(
            val key: String,
            val nextId: EphemeralId,
        ) : Event

        class Tick(
            val generation: Long,
        ) : Event
    }

    /** A wake-up request; [generation] makes every request distinct so the state flow never conflates two. */
    private data class Alarm(
        val atMillis: Long,
        val generation: Long,
    )
}
