package com.constrivo.drop.platform.android.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NearbyDevices
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.TrustState
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.platform.android.AndroidPlatform
import com.constrivo.drop.platform.android.capability.LocalRadioFacts
import com.constrivo.drop.platform.android.permission.RadioPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tunables of [AndroidDiscoveryController].
 *
 * @property carrier how this device's beacon body travels ([AndroidPlatform.beaconCarrier]: service data on Android).
 * @property pauseScanDuringTransfer N13: scanning pauses while a transfer streams.
 * @property wallClockSliceMillis the epoch timer re-reads the wall clock at least this often while the processor is
 *   awake, so a clock correction is noticed within one slice. It does not help in deep sleep, where coroutine delays
 *   stop: there the radio's per-set duration ends the old ID at the boundary, and the next wake-up
 *   ([AndroidDiscoveryController.wakeUp], a sighting) starts the new one.
 * @property retryMillis back-off after the radio or the advertisement builder failed.
 */
data class DiscoveryControllerConfig(
    val carrier: BeaconCarrier = AndroidPlatform.beaconCarrier,
    val pauseScanDuringTransfer: Boolean = true,
    val wallClockSliceMillis: Long = 30_000,
    val retryMillis: Long = 5_000,
) {
    init {
        require(wallClockSliceMillis > 0 && retryMillis > 0) { "durations must be positive" }
    }
}

/**
 * Discovery on Android (F-A1, F-A2, F-A5; spec changes N4, N13): owns the [BeaconRadio], feeds [NearbyDevices] with its
 * sightings (and the LAN events of WP7d), and turns the radar and foreground-service hooks into advertising and
 * scanning through [DiscoveryPolicy].
 *
 * - **Advertising** is rebuilt from [beaconState] with this device's `k_adv` whenever the state, the mode or the secret
 *   ([refreshAdvertisement]) changes, and at every epoch boundary ([BeaconAdvertisement.validUntilMillis], 15 min): the
 *   radio then restarts its advertising set, so the ephemeral ID and the radio address rotate together (N4). A
 *   `HIDDEN` visibility never advertises. The boundary timer is a coroutine delay, which does not run while the
 *   processor sleeps; the Android radio bounds each set to its epoch, so the old ID goes off the air at the boundary
 *   anyway, and the new one goes on air at the next wake-up: the timer itself, a scan result that arrives after the
 *   boundary, or [wakeUp], which the foreground service calls from an inexact wake-up alarm armed at
 *   [advertisement]'s `validUntilMillis` (WP7e).
 * - **Scanning** follows the plan's scan mode; a mode change resubscribes to [BeaconRadio.scan], and the Android radio
 *   conflates rapid changes under its scan throttle.
 * - **The radar model** runs while the radar or the service is up; [devices] is empty otherwise.
 *
 * Errors from the radio or the advertisement builder are retried after [DiscoveryControllerConfig.retryMillis] and
 * reported in [lastError]; nothing escapes [run]. Time comes from [wallClock] (epochs) and the coroutine dispatcher
 * (delays), so a test dispatcher drives it deterministically.
 *
 * @param advertisingSecret this device's current `k_adv` (32 bytes, S3), read at every rebuild; call
 *   [refreshAdvertisement] after a rotation so it applies at once.
 * @param beaconState what to advertise; build it with [phoneBeaconStates] from settings and [LocalRadioFacts].
 * @param permissions the radio permissions, re-emitted when they change (the UI re-reads them on resume).
 * @param trust the trusted peers' `k_adv` generations and this device's own current (and recently rotated) `k_adv`,
 *   so the radar resolves trusted peers and drops this device's own echo ([NearbyDevices.run]).
 */
class AndroidDiscoveryController(
    private val radio: BeaconRadio,
    private val nearby: NearbyDevices,
    private val crypto: CryptoProvider,
    private val advertisingSecret: () -> ByteArray,
    private val beaconState: Flow<LocalBeaconState>,
    private val permissions: Flow<RadioPermissionState>,
    private val wallClock: WallClock,
    private val trust: Flow<TrustState> = flowOf(TrustState()),
    private val lanEvents: Flow<LanEvent> = emptyFlow(),
    private val config: DiscoveryControllerConfig = DiscoveryControllerConfig(),
) {
    private data class Hooks(
        val radarVisible: Boolean = false,
        val foregroundService: Boolean = false,
        val transferActive: Boolean = false,
    )

    private val hooks = MutableStateFlow(Hooks())
    private val refresh = MutableStateFlow(0L)
    private val planState = MutableStateFlow(DiscoveryPlan.IDLE)
    private val errorState = MutableStateFlow<String?>(null)
    private val advertisedState = MutableStateFlow<BeaconAdvertisement?>(null)

    /** Makes the epoch timer read the wall clock now instead of at the end of its slice. */
    private val clockCheck = Channel<Unit>(Channel.CONFLATED)

    /** Nearby devices for the radar (empty while discovery is idle). */
    val devices: StateFlow<List<NearbyDevice>> get() = nearby.devices

    /** What discovery currently runs. */
    val plan: StateFlow<DiscoveryPlan> = planState.asStateFlow()

    /** The advertisement on air, or null; for diagnostics and for the session to share the next ID over the link. */
    val advertisement: StateFlow<BeaconAdvertisement?> = advertisedState.asStateFlow()

    /** The last radio or advertisement error, for the diagnostics log; null once things work again. */
    val lastError: StateFlow<String?> = errorState.asStateFlow()

    /** The radar screen became visible or hidden (WP8). */
    fun setRadarVisible(visible: Boolean) {
        hooks.update { it.copy(radarVisible = visible) }
    }

    /** The radio session's foreground service started or stopped (WP7e). */
    fun setForegroundService(running: Boolean) {
        hooks.update { it.copy(foregroundService = running) }
    }

    /** A transfer started or stopped streaming (N13: slow advertising, scanning paused). */
    fun setTransferActive(active: Boolean) {
        hooks.update { it.copy(transferActive = active) }
    }

    /** Rebuilds the advertisement now, e.g. after `AdvertisingSecretStore.rotate()` ("Forget", "Reset identity"). */
    fun refreshAdvertisement() {
        refresh.update { it + 1 }
    }

    /**
     * The processor woke up (the foreground service's wake-up alarm at [advertisement]'s `validUntilMillis`, WP7e, or
     * any other event the service sees, such as a Bluetooth power change or the screen turning on): reads the wall clock
     * now and, when the advertisement's epoch is over, advertises the new epoch's ID. Cheap; any thread.
     */
    fun wakeUp() {
        clockCheck.trySend(Unit)
    }

    /** Starts [run] in [scope]. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { run() }

    /** Runs discovery until cancelled; stops advertising on the way out. Never returns normally. */
    suspend fun run() {
        try {
            coroutineScope {
                val plans =
                    combine(hooks, beaconState, permissions) { h, state, p ->
                        DiscoveryPolicy.plan(
                            DiscoveryInputs(
                                h.radarVisible,
                                h.foregroundService,
                                h.transferActive,
                                state.visibility,
                                p.canAdvertise,
                                p.canScan,
                            ),
                            config.pauseScanDuringTransfer,
                        )
                    }.distinctUntilChanged()
                launch { plans.collect { planState.value = it } }
                launch { advertiseLoop() }
                launch { radarLoop() }
            }
        } finally {
            withContext(NonCancellable) {
                advertisedState.value = null
                runCatching { radio.stopAdvertising() }
            }
        }
    }

    private suspend fun advertiseLoop() {
        combine(planState.map { it.advertiseMode }.distinctUntilChanged(), beaconState.distinctUntilChanged(), refresh) { mode, state, _ ->
            mode to state
        }.collectLatest { (mode, state) ->
            if (mode == null || state.visibility == Visibility.HIDDEN) {
                advertisedState.value = null
                runCatching { radio.stopAdvertising() }
                return@collectLatest
            }
            advertiseUntilCancelled(mode, state)
        }
    }

    /** Advertises [state] in [mode], rebuilding the advertisement at each epoch boundary, until cancelled. */
    private suspend fun advertiseUntilCancelled(
        mode: RadioMode,
        state: LocalBeaconState,
    ) {
        while (true) {
            val advertisement =
                try {
                    BeaconAdvertisement.create(crypto, advertisingSecret(), state, config.carrier, wallClock.nowMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorState.value = "cannot build the advertisement: ${e.message}"
                    delay(config.retryMillis)
                    continue
                }
            try {
                radio.startAdvertising(advertisement, mode)
                advertisedState.value = advertisement
                errorState.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorState.value = "advertising failed: ${e.message}"
                delay(config.retryMillis)
                continue
            }
            awaitWallClock(advertisement.validUntilMillis)
        }
    }

    /**
     * Suspends until the wall clock reaches [unixMillis], re-reading it at least every slice and whenever [clockCheck]
     * fires ([wakeUp], a sighting after the boundary): delays do not run in deep sleep.
     */
    private suspend fun awaitWallClock(unixMillis: Long) {
        while (true) {
            val remaining = unixMillis - wallClock.nowMillis()
            if (remaining <= 0) return
            withTimeoutOrNull(minOf(remaining, config.wallClockSliceMillis)) { clockCheck.receive() }
        }
    }

    /** A scan result woke the processor: if the epoch on air is over, the timer (which slept) must look now. */
    private fun onSighting() {
        val validUntil = advertisedState.value?.validUntilMillis ?: return
        if (wallClock.nowMillis() >= validUntil) clockCheck.trySend(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun radarLoop() {
        planState.map { it.radarRunning }.distinctUntilChanged().collectLatest { running ->
            if (!running) return@collectLatest
            val sightings: Flow<BeaconSighting> =
                planState
                    .map { it.scanMode }
                    .distinctUntilChanged()
                    .flatMapLatest { mode -> if (mode == null) emptyFlow() else radio.scan(mode) }
                    .onEach { onSighting() }
                    .retryWhen { cause, _ ->
                        if (cause is CancellationException) return@retryWhen false
                        errorState.value = "scanning failed: ${cause.message}"
                        delay(config.retryMillis)
                        true
                    }
            nearby.run(sightings, lanEvents, trust)
        }
    }

    companion object {
        /**
         * This phone's beacon state from its settings and [facts] ([com.constrivo.drop.platform.android.capability
         * .AndroidCapabilityDetector.facts]): platform phone, the detected capabilities and network hint (N6), no Classic
         * address (S10: a phone cannot read its own).
         */
        fun phoneBeaconStates(
            visibility: Flow<Visibility>,
            nickname: Flow<String>,
            facts: Flow<LocalRadioFacts>,
        ): Flow<LocalBeaconState> =
            combine(visibility, nickname, facts) { v, name, f ->
                LocalBeaconState(
                    visibility = v,
                    platform = DevicePlatform.PHONE,
                    capabilities = f.capabilities,
                    networkHint = f.networkHint,
                    nickname = name,
                    classicAddress = null,
                )
            }.distinctUntilChanged()
    }
}
