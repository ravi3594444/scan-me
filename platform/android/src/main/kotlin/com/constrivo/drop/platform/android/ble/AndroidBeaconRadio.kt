package com.constrivo.drop.platform.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.platform.android.AndroidClocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Tunables of [AndroidBeaconRadio].
 *
 * @property extendedScanning scan extended advertisements too (and every PHY) where the controller supports them.
 * @property deviceCacheSize scanned devices kept for [AndroidBeaconRadio.deviceFor].
 * @property rawBuffer scan results queued between the Bluetooth callback thread and the parser (newest win).
 * @property sightingBuffer sightings queued per collector of [AndroidBeaconRadio.scan] (newest win).
 */
data class BeaconRadioConfig(
    val advertising: AdvertisingConfig = AdvertisingConfig(),
    val throttle: ScanThrottleConfig = ScanThrottleConfig(),
    val extendedScanning: Boolean = true,
    val deviceCacheSize: Int = 256,
    val rawBuffer: Int = 512,
    val sightingBuffer: Int = 256,
    val advertiseStartTimeoutMillis: Long = 2_000,
    val advertiseFirstRetryMillis: Long = 2_000,
    val advertiseMaxRetryMillis: Long = 60_000,
    val counterPublishMillis: Long = 1_000,
)

/**
 * [BeaconRadio] on Android (F-A1, F-A2; architecture §5.1; spec changes S11, N4, N13).
 *
 * **Advertising** runs through `BluetoothLeAdvertiser.startAdvertisingSet`: a legacy set always (connectable, so a peer
 * can open GATT or L2CAP to the address it heard, and scannable for the nickname scan response), plus an extended,
 * connectable set with the whole nickname where the adapter supports extended advertising ([AdvertisingPlan]). Every
 * [startAdvertising] stops the running sets and starts new ones, and a new set gets a new random address, so when the
 * owner replaces the advertisement at [BeaconAdvertisement.validUntilMillis] the radio address rotates together with
 * the ephemeral ID (N4). Intervals are 100 ms in [RadioMode.FOREGROUND] and 1 s in [RadioMode.BACKGROUND].
 *
 * Each set is started with a duration that ends it at [BeaconAdvertisement.validUntilMillis]
 * ([AdvertisingPlan.durationUnits]), so the controller takes an ID off the air at its epoch boundary even while this
 * process sleeps and the owner's timer cannot run; a set that ends before its epoch does (the 655 s cap) is restarted,
 * and an advertisement whose epoch is over is never put on air again ([AdvertisingStatus.Expired]). Sets are tracked by
 * [AdvertisingSets] from before the platform hears of them, so a start whose caller is cancelled, a failure and a
 * time-out never leave one on air.
 *
 * **Scanning** uses one `BluetoothLeScanner` scan shared by all collectors of [scan], in the most demanding mode any
 * of them asks for, with two filters: our service UUID (the service-data carrier) and our company identifier followed by
 * the `"dr"` marker (the manufacturer-data carrier of Windows, S11). Low latency in the foreground, low power in the
 * background; filters are always set, so Android keeps delivering with the screen off. Starts go through
 * [ScanScheduler], which respects Android's five starts per 30 s and restarts the scan before its 30-minute
 * opportunistic downgrade. Results are parsed off the Bluetooth callback thread by [ScanRecordAdapter] with the LE
 * address as [BeaconSighting.radioAddress]; the device object is kept for [deviceFor].
 *
 * **Errors** (Bluetooth off, a missing permission, stack failures) never throw: they appear in [state], and the radio
 * resumes by itself when Bluetooth comes back (advertising and scanning are re-applied from what was last requested).
 *
 * Needs `BLUETOOTH_ADVERTISE` and `BLUETOOTH_SCAN` at runtime. Lint's MissingPermission is suppressed on the class:
 * every platform call is wrapped, and a SecurityException is reported as [AdvertisingStatus.CODE_PERMISSION] or
 * [ScanScheduler.ERROR_PERMISSION] instead. One instance per process; [close] releases it.
 */
@SuppressLint("MissingPermission")
class AndroidBeaconRadio(
    context: Context,
    private val config: BeaconRadioConfig = BeaconRadioConfig(),
    private val power: BluetoothPowerMonitor = BluetoothPowerMonitor(context),
    private val monotonic: MonotonicClock = AndroidClocks.elapsedRealtime,
    private val wallClock: WallClock = AndroidClocks.wall,
) : BeaconRadio,
    AutoCloseable {
    private val thread = HandlerThread("drop-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(BeaconRadioState(power = power.state.value))
    private val devices = DeviceCache<BluetoothDevice>(config.deviceCacheSize)
    private val serviceUuid = ParcelUuid.fromString(AdvertisingFormat.SERVICE_UUID_128)

    private val sightings = AtomicLong()
    private val malformed = AtomicLong()
    private val unsupported = AtomicLong()
    private val dropped = AtomicLong()
    private val scanStarts = AtomicLong()
    private val advertisingStarts = AtomicLong()

    /** Radio state and counters; see [BeaconRadioState]. */
    val state: StateFlow<BeaconRadioState> = mutableState.asStateFlow()

    private val adapter: BluetoothAdapter? get() = power.adapter

    /**
     * The `BluetoothDevice` the scan last reported for [address], carrying its address type, for the handshake
     * connector ([com.constrivo.drop.platform.android.bluetooth.BluetoothChannelConnector]).
     */
    fun deviceFor(address: String): BluetoothDevice? = devices.get(address)

    // =============================================================================================================
    // Advertising
    // =============================================================================================================

    private val advertiseMutex = Mutex()

    /** Every set on air or starting; nothing reaches the air without being tracked here. */
    private val sets = AdvertisingSets(config.advertiseStartTimeoutMillis)

    @Volatile private var desiredAdvertisement: Pair<BeaconAdvertisement, RadioMode>? = null

    @Volatile private var closed = false

    // --- guarded by advertiseMutex ---
    private var retryJob: Job? = null
    private var advertiseFailures = 0

    /** Restarts sets whose duration was capped before their epoch ends, for stacks that do not report the end. */
    private var capRestartJob: Job? = null

    /** Counts applications of the desired advertisement: a set's end matters only while its generation is current. */
    private var generation = 0L
    // ----------------------------------

    override suspend fun startAdvertising(
        advertisement: BeaconAdvertisement,
        mode: RadioMode,
    ) {
        desiredAdvertisement = advertisement to mode
        applyAdvertising(fromRetry = false)
    }

    override suspend fun stopAdvertising() {
        desiredAdvertisement = null
        applyAdvertising(fromRetry = false)
    }

    private suspend fun applyAdvertising(fromRetry: Boolean) {
        advertiseMutex.withLock { applyLocked(fromRetry) }
    }

    /**
     * Stops every set on air (or starting) and starts the desired advertisement's sets, each bounded to the
     * advertisement's epoch. Cancellation-safe: a set whose caller is cancelled while it starts is stopped
     * ([AdvertisingSets]), and the next application stops the sets that did start. Call under [advertiseMutex].
     */
    private suspend fun applyLocked(fromRetry: Boolean) {
        if (!fromRetry) {
            retryJob?.cancel()
            advertiseFailures = 0
        }
        retryJob = null
        capRestartJob?.cancel()
        capRestartJob = null
        val current = ++generation
        sets.stopAll()
        val (advertisement, mode) =
            desiredAdvertisement?.takeIf { !closed } ?: run {
                setAdvertising(AdvertisingStatus.Off)
                return
            }
        val duration = AdvertisingPlan.durationUnits(advertisement.validUntilMillis, wallClock.nowMillis())
        if (duration == null) {
            // The owner slept through the epoch boundary and has not replaced the advertisement yet: an old ID never
            // goes back on air (N4). The owner's next wake-up starts the new epoch's advertisement.
            setAdvertising(AdvertisingStatus.Expired(advertisement.validUntilMillis))
            return
        }
        val adapter = adapter
        val advertiser = if (power.state.value == BluetoothPower.ON) guarded { adapter?.bluetoothLeAdvertiser } else null
        if (adapter == null || advertiser == null) {
            // Bluetooth is off: onPower applies the advertisement again when it comes on.
            setAdvertising(AdvertisingStatus.WaitingForBluetooth)
            return
        }
        val capabilities =
            AdvertiserCapabilities(
                extendedAdvertising = guarded { adapter.isLeExtendedAdvertisingSupported } ?: false,
                le2mPhy = guarded { adapter.isLe2MPhySupported } ?: false,
                maxAdvertisingDataLength = guarded { adapter.leMaximumAdvertisingDataLength } ?: AdvertisingPlan.LEGACY_LIMIT,
            )
        val specs =
            try {
                AdvertisingPlan.sets(advertisement, mode, capabilities, config.advertising)
            } catch (e: IllegalStateException) {
                setAdvertising(AdvertisingStatus.Failed(AdvertisingStatus.ADVERTISE_FAILED_DATA_TOO_LARGE, retrying = false))
                return
            }
        var extendedRunning = false
        for (spec in specs) {
            val status = sets.start(AndroidAdvertisingSet(advertiser, spec, duration)) { onSetEnded(current) }
            if (status == AdvertisingSets.ADVERTISE_SUCCESS) {
                if (spec.kind == AdvertisingSetKind.EXTENDED) extendedRunning = true
                continue
            }
            if (spec.kind == AdvertisingSetKind.EXTENDED) continue // optional: the legacy set carries the beacon
            sets.stopAll()
            val retrying = AdvertisingStatus.isRetryable(status) && !closed
            setAdvertising(AdvertisingStatus.Failed(status, retrying))
            if (retrying) scheduleRetryLocked()
            return
        }
        advertiseFailures = 0
        setAdvertising(AdvertisingStatus.Advertising(mode, extendedRunning, advertisement.validUntilMillis))
        if (duration == AdvertisingPlan.MAX_DURATION_UNITS) {
            // Capped before the epoch ends: the stack's end report restarts the sets (onSetEnded); this timer does it on
            // stacks that do not report it, whenever the processor is awake.
            capRestartJob =
                scope.launch {
                    delay(duration * AdvertisingPlan.DURATION_UNIT_MILLIS)
                    advertiseMutex.withLock {
                        if (current == generation) {
                            // Cleared first, so applyLocked does not cancel the job it runs in.
                            capRestartJob = null
                            applyLocked(fromRetry = false)
                        }
                    }
                }
        }
    }

    /**
     * A set of [ofGeneration] left the air by itself: its duration ran out, at the epoch boundary or at the 655 s cap.
     * The advertisement is applied again: restarted for the rest of its epoch (a new address, the same ID), or reported
     * [AdvertisingStatus.Expired] once its epoch is over.
     */
    private fun onSetEnded(ofGeneration: Long) {
        scope.launch {
            advertiseMutex.withLock {
                // Nothing to do when a newer application replaced it (or the other set of the pair ended first).
                if (ofGeneration == generation) applyLocked(fromRetry = false)
            }
        }
    }

    private fun scheduleRetryLocked() {
        val shift = advertiseFailures.coerceIn(0, 10)
        advertiseFailures++
        val wait = minOf(config.advertiseMaxRetryMillis, config.advertiseFirstRetryMillis shl shift)
        retryJob =
            scope.launch {
                delay(wait)
                applyAdvertising(fromRetry = true)
            }
    }

    /**
     * One `BluetoothLeAdvertiser` advertising set for [spec], bounded to [durationUnits] × 10 ms so that the controller
     * (or the stack's wake-up timer) ends it at the epoch boundary even while this process sleeps.
     */
    private inner class AndroidAdvertisingSet(
        private val advertiser: BluetoothLeAdvertiser,
        private val spec: AdvertisingSetSpec,
        private val durationUnits: Int,
    ) : PlatformAdvertisingSet {
        @Volatile private var stopped = false

        @Volatile private var started: ((Int) -> Unit)? = null

        @Volatile private var ended: (() -> Unit)? = null

        private val callback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int,
                ) {
                    if (stopped) {
                        // Given up while it started. Android frees such a set itself; stop it again for stacks that do not.
                        if (status == AdvertisingSets.ADVERTISE_SUCCESS) guarded { advertiser.stopAdvertisingSet(this) }
                        return
                    }
                    started?.invoke(status)
                }

                override fun onAdvertisingEnabled(
                    advertisingSet: AdvertisingSet?,
                    enable: Boolean,
                    status: Int,
                ) {
                    // Nothing here calls enableAdvertising, so a set reported disabled is one whose duration ran out.
                    if (!enable && !stopped) ended?.invoke()
                }
            }

        override fun start(
            onStarted: (status: Int) -> Unit,
            onEnded: () -> Unit,
        ): Int? {
            started = onStarted
            ended = onEnded
            return try {
                advertiser.startAdvertisingSet(
                    parameters(spec),
                    advertiseData(spec),
                    spec.scanResponseManufacturerData?.let {
                        AdvertiseData.Builder().addManufacturerData(AdvertisingFormat.COMPANY_ID, it).build()
                    },
                    null,
                    null,
                    durationUnits,
                    0,
                    callback,
                    handler,
                )
                advertisingStarts.incrementAndGet()
                null
            } catch (e: SecurityException) {
                AdvertisingStatus.CODE_PERMISSION
            } catch (e: IllegalArgumentException) {
                // The platform refuses data that does not fit the set type.
                AdvertisingStatus.ADVERTISE_FAILED_DATA_TOO_LARGE
            } catch (e: IllegalStateException) {
                AdvertisingStatus.CODE_NOT_AVAILABLE
            } catch (e: NullPointerException) {
                // Some stacks throw from inside the binder proxy while the adapter turns off.
                AdvertisingStatus.CODE_NOT_AVAILABLE
            }
        }

        override fun stop() {
            stopped = true
            guarded { advertiser.stopAdvertisingSet(callback) }
        }
    }

    private fun parameters(spec: AdvertisingSetSpec): AdvertisingSetParameters {
        val builder =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(spec.kind == AdvertisingSetKind.LEGACY)
                .setConnectable(spec.connectable)
                .setScannable(spec.scannable)
                .setInterval(spec.interval)
                .setTxPowerLevel(spec.txPowerDbm)
        if (spec.kind == AdvertisingSetKind.EXTENDED) {
            builder.setPrimaryPhy(spec.primaryPhy).setSecondaryPhy(spec.secondaryPhy)
        }
        return builder.build()
    }

    private fun advertiseData(spec: AdvertisingSetSpec): AdvertiseData {
        val builder = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        if (spec.includeServiceUuid) builder.addServiceUuid(serviceUuid)
        spec.serviceData?.let { builder.addServiceData(serviceUuid, it) }
        spec.manufacturerData?.let { builder.addManufacturerData(AdvertisingFormat.COMPANY_ID, it) }
        return builder.build()
    }

    private fun setAdvertising(status: AdvertisingStatus) {
        mutableState.update { it.copy(advertising = status, counters = counters()) }
    }

    // =============================================================================================================
    // Scanning
    // =============================================================================================================

    private class Subscriber(
        val mode: RadioMode,
        val channel: SendChannel<BeaconSighting>,
    )

    private class RawResult(
        val record: ByteArray?,
        val rssi: Int,
        val address: String?,
        val timestampNanos: Long,
        val device: BluetoothDevice?,
    )

    private val scanLock = Any()
    private val scheduler = ScanScheduler(config.throttle)
    private val subscribers = HashMap<Long, Subscriber>()
    private var nextSubscriber = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var extendedScanRejected = false

    @Volatile private var lastScanExtended = false

    private val raw =
        Channel<RawResult>(config.rawBuffer, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }

    override fun scan(mode: RadioMode): Flow<BeaconSighting> =
        callbackFlow {
            val id =
                synchronized(scanLock) {
                    val id = nextSubscriber++
                    subscribers[id] = Subscriber(mode, channel)
                    scheduler.desire(ScanPlan.combined(subscribers.values.map { it.mode }), monotonic.elapsedMillis())
                    id
                }
            wake.trySend(Unit)
            awaitClose {
                synchronized(scanLock) {
                    subscribers.remove(id)
                    scheduler.desire(ScanPlan.combined(subscribers.values.map { it.mode }), monotonic.elapsedMillis())
                }
                wake.trySend(Unit)
            }
        }.buffer(config.sightingBuffer, BufferOverflow.DROP_OLDEST)

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                enqueue(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::enqueue)
            }

            override fun onScanFailed(errorCode: Int) {
                val code =
                    if (errorCode == ScanScheduler.SCAN_FAILED_FEATURE_UNSUPPORTED && lastScanExtended) {
                        // The controller claims extended advertising but refuses extended scanning: go legacy-only.
                        extendedScanRejected = true
                        SCAN_FAILED_INTERNAL_ERROR
                    } else {
                        errorCode
                    }
                synchronized(scanLock) { scheduler.failed(code, monotonic.elapsedMillis()) }
                wake.trySend(Unit)
            }
        }

    private fun enqueue(result: ScanResult) {
        val device = result.device
        raw.trySend(RawResult(result.scanRecord?.bytes, result.rssi, device?.address, result.timestampNanos, device))
    }

    private suspend fun dispatchLoop() {
        var lastPublish = 0L
        for (r in raw) {
            if (r.address != null && r.device != null) devices.put(r.address, r.device)
            val outcome =
                ScanRecordAdapter.parse(
                    record = r.record,
                    rssiDbm = r.rssi,
                    address = r.address,
                    timestampNanos = r.timestampNanos,
                    elapsedNowNanos = monotonic.elapsedMillis() * NANOS_PER_MILLI,
                    unixNowMillis = wallClock.nowMillis(),
                )
            when (outcome) {
                is ScanOutcome.Sighting -> {
                    sightings.incrementAndGet()
                    val targets =
                        synchronized(scanLock) {
                            scheduler.resultReceived()
                            subscribers.values.map { it.channel }
                        }
                    for (target in targets) target.trySend(outcome.sighting)
                }

                is ScanOutcome.Malformed -> {
                    malformed.incrementAndGet()
                }

                ScanOutcome.Unsupported -> {
                    unsupported.incrementAndGet()
                }

                ScanOutcome.NotDrop -> {
                    // Another product's advertisement matched a filter (the shared test company identifier).
                }
            }
            val now = monotonic.elapsedMillis()
            if (now - lastPublish >= config.counterPublishMillis) {
                lastPublish = now
                mutableState.update { it.copy(counters = counters()) }
            }
        }
    }

    private suspend fun scanLoop() {
        while (scope.isActive) {
            val now = monotonic.elapsedMillis()
            val action = synchronized(scanLock) { scheduler.next(now) }
            when (action) {
                is ScanAction.Start -> {
                    if (action.stopFirst) osStopScan()
                    val error = osStartScan(action.mode)
                    synchronized(scanLock) {
                        if (error == null) scheduler.started(action.mode, now) else scheduler.failed(error, now)
                    }
                }

                ScanAction.Stop -> {
                    osStopScan()
                    synchronized(scanLock) { scheduler.stopped() }
                }

                is ScanAction.Wait -> {
                    withTimeoutOrNull((action.atMillis - now).coerceAtLeast(1)) { wake.receive() }
                }

                ScanAction.Idle -> {
                    wake.receive()
                }
            }
            publishScanState()
        }
    }

    /** Calls `startScan`; null when the call went through (a later `onScanFailed` may still report a failure). */
    private fun osStartScan(mode: RadioMode): Int? {
        val adapter = adapter ?: return ScanScheduler.ERROR_NOT_AVAILABLE
        val scanner = guarded { adapter.bluetoothLeScanner } ?: return ScanScheduler.ERROR_NOT_AVAILABLE
        val extended =
            config.extendedScanning && !extendedScanRejected && (guarded { adapter.isLeExtendedAdvertisingSupported } ?: false)
        val parameters = ScanPlan.parameters(mode, extended)
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(parameters.scanMode)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(parameters.matchMode)
                .setReportDelay(0)
                .setLegacy(parameters.legacyOnly)
                .apply { if (parameters.allPhys) setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
                .build()
        return try {
            lastScanExtended = extended
            scanner.startScan(filters(), settings, scanCallback)
            scanStarts.incrementAndGet()
            null
        } catch (e: SecurityException) {
            ScanScheduler.ERROR_PERMISSION
        } catch (e: IllegalStateException) {
            ScanScheduler.ERROR_NOT_AVAILABLE
        } catch (e: IllegalArgumentException) {
            SCAN_FAILED_INTERNAL_ERROR
        }
    }

    private fun osStopScan() {
        guarded { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun filters(): List<ScanFilter> =
        listOf(
            ScanFilter.Builder().setServiceUuid(serviceUuid).build(),
            ScanFilter
                .Builder()
                .setManufacturerData(AdvertisingFormat.COMPANY_ID, ScanPlan.MANUFACTURER_FILTER_DATA, ScanPlan.MANUFACTURER_FILTER_MASK)
                .build(),
        )

    private fun publishScanState() {
        val now = monotonic.elapsedMillis()
        val schedule = synchronized(scanLock) { scheduler.schedule(now) }
        val status =
            when {
                schedule.desired == null && schedule.running == null -> {
                    ScanStatus.Off
                }

                power.state.value != BluetoothPower.ON -> {
                    ScanStatus.WaitingForBluetooth
                }

                schedule.unsupported -> {
                    ScanStatus.Failed(ScanScheduler.SCAN_FAILED_FEATURE_UNSUPPORTED, permanent = true)
                }

                schedule.blockedUntilMillis != null && schedule.desired != null -> {
                    if (schedule.running == null && schedule.lastFailure != null) {
                        ScanStatus.Failed(schedule.lastFailure, permanent = false)
                    } else {
                        ScanStatus.Throttled(schedule.desired, schedule.running, schedule.blockedUntilMillis)
                    }
                }

                schedule.running != null -> {
                    ScanStatus.Scanning(schedule.running)
                }

                schedule.lastFailure != null -> {
                    ScanStatus.Failed(schedule.lastFailure, permanent = false)
                }

                else -> {
                    ScanStatus.Off
                }
            }
        mutableState.update { it.copy(scanning = status, counters = counters()) }
    }

    // =============================================================================================================
    // Power and lifecycle
    // =============================================================================================================

    private suspend fun onPower(value: BluetoothPower) {
        mutableState.update { it.copy(power = value) }
        synchronized(scanLock) { scheduler.radioAvailable(value == BluetoothPower.ON, monotonic.elapsedMillis()) }
        wake.trySend(Unit)
        advertiseMutex.withLock {
            if (value != BluetoothPower.ON) {
                // The stack drops its sets with the adapter. Stopping them as well is harmless (every call is guarded)
                // and covers a stack that keeps a set through a BLE-only state.
                generation++
                sets.stopAll()
                retryJob?.cancel()
                retryJob = null
                capRestartJob?.cancel()
                capRestartJob = null
                if (desiredAdvertisement != null) setAdvertising(AdvertisingStatus.WaitingForBluetooth)
            } else if (desiredAdvertisement != null) {
                applyLocked(fromRetry = false)
            }
        }
    }

    /** Stops advertising and scanning and releases the callback thread. The radio cannot be used afterwards. */
    override fun close() {
        closed = true
        synchronized(scanLock) { subscribers.values.forEach { it.channel.close() } }
        osStopScan()
        // Every set on air or still starting; a start in progress gives up and stops its own set as well.
        sets.stopAll()
        scope.cancel()
        raw.close()
        power.stop()
        thread.quitSafely()
    }

    private fun counters() =
        BeaconRadioCounters(
            sightings = sightings.get(),
            malformed = malformed.get(),
            unsupported = unsupported.get(),
            dropped = dropped.get(),
            scanStarts = scanStarts.get(),
            advertisingStarts = advertisingStarts.get(),
        )

    /** Runs a platform call that may throw because of a missing permission or a dying adapter; null then. */
    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: NullPointerException) {
            // Some stacks throw from inside the binder proxy while the adapter turns off.
            null
        }

    // Last in the class body: property initialisers and init blocks run in textual order, and the loops started here use
    // every property above.
    init {
        power.start()
        scope.launch { power.state.collect { onPower(it) } }
        scope.launch { scanLoop() }
        scope.launch { dispatchLoop() }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** `ScanCallback.SCAN_FAILED_INTERNAL_ERROR`. */
        const val SCAN_FAILED_INTERNAL_ERROR = 3
    }
}
