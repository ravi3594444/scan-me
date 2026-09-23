package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

/**
 * The radar's model (F‑A2, F‑A3, F‑A5, design §3.2–3.3): merges the Bluetooth sightings of [BeaconRadio.scan] and the
 * mDNS events of [LanDiscovery.browse] into one [StateFlow] of immutable [NearbyDevice] lists.
 *
 * All rules live in [NearbyDeviceTracker]; this class feeds it from the flows, stamps every event with [clock], and
 * wakes itself exactly when a smoothing window closes or a device is due to leave (5 s after its last beacon), so it
 * does no periodic work while the radar is empty. Time comes only from [clock] and the coroutine dispatcher's
 * `delay`, so a test dispatcher with a clock reading its virtual time makes it fully deterministic.
 *
 * @param ownAdvertisingSecret this device's `k_adv`, so its own beacon and mDNS record never appear on its radar.
 */
class NearbyDevices(
    crypto: CryptoProvider,
    private val clock: WallClock,
    ownAdvertisingSecret: ByteArray? = null,
    config: NearbyConfig = NearbyConfig(),
) {
    private val tracker = NearbyDeviceTracker(crypto, ownAdvertisingSecret, config)
    private val running = Mutex()
    private val state = MutableStateFlow<List<NearbyDevice>>(emptyList())
    private val counterState = MutableStateFlow(DiscoveryCounters())

    /** Nearby devices, nearest ring first. Empty while [run] is not running. */
    val devices: StateFlow<List<NearbyDevice>> = state.asStateFlow()

    /** Drop counters of the current run, for the diagnostics log. */
    val counters: StateFlow<DiscoveryCounters> = counterState.asStateFlow()

    /**
     * Collects [sightings], [lanEvents] and [trustedPeers] until cancelled; never returns normally. A source flow that
     * completes simply stops contributing; one that fails ends the run with its exception, so platform flows should
     * handle their own radio errors. When the run stops, [devices] becomes empty, so a radar reopened later starts
     * clean.
     *
     * @throws IllegalStateException if another [run] of this instance is active.
     */
    suspend fun run(
        sightings: Flow<BeaconSighting>,
        lanEvents: Flow<LanEvent> = emptyFlow(),
        trustedPeers: Flow<Collection<TrustedPeer>> = flowOf(emptyList()),
    ) {
        check(running.tryLock()) { "NearbyDevices.run is already active" }
        try {
            tracker.clear()
            coroutineScope {
                val events = Channel<Event>(Channel.BUFFERED)
                launch { trustedPeers.collect { events.send(Event.Peers(it)) } }
                launch { sightings.collect { events.send(Event.Sighting(it)) } }
                launch { lanEvents.collect { events.send(Event.Lan(it)) } }
                val alarm = MutableStateFlow<Alarm?>(null)
                launch {
                    alarm.collectLatest { a ->
                        if (a != null) {
                            val wait = a.atMillis - clock.nowMillis()
                            if (wait > 0) delay(wait)
                            events.send(Event.Tick)
                        }
                    }
                }
                var generation = 0L
                for (event in events) {
                    val now = clock.nowMillis()
                    var changed =
                        when (event) {
                            is Event.Sighting -> tracker.onSighting(event.sighting, now)
                            is Event.Lan -> tracker.onLanEvent(event.event, now)
                            is Event.Peers -> tracker.setTrustedPeers(event.peers)
                            Event.Tick -> false
                        }
                    if (tracker.advanceTo(now)) changed = true
                    if (changed) state.value = tracker.snapshot()
                    counterState.value = tracker.counters
                    alarm.value = tracker.nextDeadlineMillis()?.let { Alarm(it, ++generation) }
                }
            }
        } finally {
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
        trustedPeers: Flow<Collection<TrustedPeer>> = flowOf(emptyList()),
    ): Job = scope.launch { run(sightings, lanEvents, trustedPeers) }

    private sealed interface Event {
        class Sighting(
            val sighting: BeaconSighting,
        ) : Event

        class Lan(
            val event: LanEvent,
        ) : Event

        class Peers(
            val peers: Collection<TrustedPeer>,
        ) : Event

        data object Tick : Event
    }

    /** A wake-up request; [generation] makes every request distinct so the state flow never conflates two. */
    private data class Alarm(
        val atMillis: Long,
        val generation: Long,
    )
}
