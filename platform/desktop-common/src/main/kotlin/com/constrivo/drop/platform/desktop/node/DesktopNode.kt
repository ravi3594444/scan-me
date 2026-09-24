package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.handshake.ExpectedPeer
import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.crypto.qr.QrLink
import com.constrivo.drop.core.crypto.qr.QrLinkKind
import com.constrivo.drop.core.crypto.qr.QrPayload
import com.constrivo.drop.core.crypto.qr.QrPayloadCodec
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import com.constrivo.drop.core.data.Device
import com.constrivo.drop.core.data.DeviceIds
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.DuplicateRecordException
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.NewTransferFile
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.data.SettingsSnapshot
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferRecord
import com.constrivo.drop.core.data.TrustedDeviceKeys
import com.constrivo.drop.core.data.VisibilityPreference
import com.constrivo.drop.core.data.toTrustedPeerLookup
import com.constrivo.drop.core.data.toTrustedPeers
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NearbyDevices
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.TrustState
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.LadderInput
import com.constrivo.drop.core.ladder.LadderNegotiation
import com.constrivo.drop.core.ladder.LadderPlan
import com.constrivo.drop.core.ladder.LadderPlanner
import com.constrivo.drop.core.ladder.LadderRunner
import com.constrivo.drop.core.ladder.LinkAgreement
import com.constrivo.drop.core.ladder.LinkFacts
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.RadioState
import com.constrivo.drop.core.ladder.engine.LadderTransferBridge
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.core.transfer.engine.FileStatus
import com.constrivo.drop.core.transfer.engine.IncomingTransfer
import com.constrivo.drop.core.transfer.engine.ReceiveOptions
import com.constrivo.drop.core.transfer.engine.SendOptions
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferEngine
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.engine.TransferStats
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.core.transfer.net.TcpSocketFactory
import com.constrivo.drop.core.transfer.receive.FileTypes
import com.constrivo.drop.core.transfer.receive.ResumeRecord
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.DialCandidate
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.core.transfer.session.EndpointDialer
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import com.constrivo.drop.platform.common.DataResumeStore
import com.constrivo.drop.platform.common.FileResumePlanStore
import com.constrivo.drop.platform.common.PartialsSweeper
import com.constrivo.drop.platform.desktop.DesktopPowerPolicy
import com.constrivo.drop.platform.desktop.data.DesktopDatabase
import com.constrivo.drop.platform.desktop.files.MarkingFileStore
import com.constrivo.drop.platform.desktop.files.PublishedFileEvent
import com.constrivo.drop.platform.desktop.files.SendFile
import com.constrivo.drop.platform.desktop.lan.LanLinkProvider
import com.constrivo.drop.platform.desktop.lan.RebindableLanDiscovery
import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.PathSharedFile
import com.constrivo.drop.web.ReceiveOffer
import com.constrivo.drop.web.ReceiveServer
import com.constrivo.drop.web.ReceiveSession
import com.constrivo.drop.web.ReceiveToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.constrivo.drop.core.protocol.AdvertisingSecret as WireAdvertisingSecret

/**
 * The desktop composition root (WP10a; architecture §3, §10.2): one per process, it wires
 *
 * - **identity and secrets**: the software Ed25519 identity and this device's advertising secret `k_adv` (S3) from the
 *   [DesktopNodeConfig.secrets] store, one [HandshakeGuard] for every responder (§6 note);
 * - **the database** (`core/data`): History rows for every transfer, trust and auto-accept, settings, and the
 *   receiver's resume state through [DataResumeStore], with the 24 h sweep of partial files ([PartialsSweeper]);
 * - **discovery**: [NearbyDevices] over the LAN (mDNS, [DesktopNodeConfig.lan]) and whatever BLE radios the OS module
 *   brings (none in WP10a), with this device's record re-announced at every epoch (N4) and on every visibility,
 *   nickname or address change (F‑A5), and withdrawn while Hidden;
 * - **the LAN binding**: one address ([lanAddress]) for the control listener, the LAN links, the browser page and the
 *   record; [setLanAddress] moves all of them when the machine changes networks;
 * - **transfers**: senders dial the peer's LAN endpoints behind the handshake identity check ([EndpointDialer]); the
 *   engine runs over that primary LAN connection and the ladder's LAN rung adds the parallel streams
 *   ([LanLinkProvider], joined only at the peer's own address; "no Bluetooth": the desktop never plans a Bluetooth or
 *   Wi‑Fi Direct rung while it has no radios). Every inbound connection starts a new session, behind limits on
 *   connections that have not authenticated yet ([InboundGate]). A link that drops is resumed by offering the same
 *   transfer again on a new session (S8, T‑07): the sender re-dials for [NodeTuning.reconnectWindowMillis], then
 *   waits for the peer to be seen again, and the receiver retires its interrupted run and resumes from its record,
 *   without a card for a transfer it accepted in this process and with one (the resume prompt) after a restart; a send
 *   interrupted by an app restart is offered again when its trusted peer shows up. So a reconnect names its transfer,
 *   and a new send from the same peer is never mistaken for one.
 * - **trust**: a session counts as trusted only when its handshake proved the pairing both ways
 *   ([HandshakeResult.peerProvedTrust]) and this device trusts the peer; otherwise both screens show the SAS (F‑B3),
 *   and a confirmed code stores (or replaces) the pairing's recognition secret. After a pairing each side exchanges
 *   `TrustShare`s with the peer in a short session of its own once no transfer with it runs, retried when the peer is
 *   seen again, so each radar recognises the other (S3); the sender's code stays up after a small first send ends until
 *   its user answers it. Also the static QR code (F‑B6), the five-minute code with the LAN address (F‑H4), "Forget"
 *   with a `k_adv` rotation (F‑G3), and auto-accept for trusted devices that have it on (F‑D2), decided on the
 *   verified identity, never on a beacon (F‑B4);
 * - **the browser receive page** for a computer without the app (F‑H4 no-Bluetooth PC path; `web-receive`).
 *
 * The UI reads the [StateFlow]s and calls the actions; actions never block (they start work in the node's scope).
 * Call [start] once, then [stop] (or [close]) once; [stop] ends the ladders before cancelling anything (F‑E11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopNode(
    val config: DesktopNodeConfig,
) : AutoCloseable {
    private val crypto = config.crypto
    private val tuning = config.tuning
    private val services = config.services
    private val eventFlow = MutableSharedFlow<NodeEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val nodeJob = SupervisorJob()
    private val failureHandler = CoroutineExceptionHandler { _, e -> report("unexpected failure", e) }
    private val scope = CoroutineScope(nodeJob + config.io + failureHandler)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /**
     * The control listener's accepted connections do their socket I/O here: silent connections from the LAN can hold
     * these threads, never the engine's or the database's ([DesktopNodeConfig.io]; on `Dispatchers.IO` this view has
     * threads of its own).
     */
    private val controlIo = config.io.limitedParallelism(CONTROL_IO_PARALLELISM)
    private val gate = InboundGate(tuning.maxUnauthenticated, tuning.maxUnauthenticatedPerHost)
    private val waitingForOffer = AtomicInteger()

    private val guard = HandshakeGuard(config.handshakeClock)
    private val power = DesktopPowerPolicy(services.keepAwake)
    private val nearby = NearbyDevices(crypto, config.wallClock, config.monotonicClock)

    private lateinit var dataRef: DropData
    private lateinit var identity: IdentityKey
    private lateinit var advertising: AdvertisingSecretStore
    private lateinit var resumeStore: DataResumeStore
    private lateinit var sweeper: PartialsSweeper
    private lateinit var senderStore: DirectoryFileStore
    private lateinit var selfId: String

    /** The LAN address and the control listener bound to it; replaced by [setLanAddress]. */
    private class LanBinding(
        val address: InetAddress,
        val listener: TcpListener,
    )

    private val binding = MutableStateFlow<LanBinding?>(null)
    private val bindingLock = Mutex()
    private val lanAddressState = MutableStateFlow(config.lanAddress)

    private val ownSecrets = MutableStateFlow<List<ByteArray>>(emptyList())

    @Volatile
    private var ownGeneration = 0
    private val trustedKeys = MutableStateFlow<List<TrustedDeviceKeys>>(emptyList())

    @Volatile
    private var lookup: TrustedPeerLookup = TrustedPeerLookup.NONE

    /** Peers' `TrustShare`s that arrived before this side's user confirmed the pairing (S3), by device id. */
    private val pendingShares = ConcurrentHashMap<String, TrustShare>()

    /** Trust exchanges to run after a pairing (S3), by the peer's device id. */
    private val trustSyncs = ConcurrentHashMap<String, TrustSync>()
    private val trustSyncSignal = MutableStateFlow(0)

    private val settingsState = MutableStateFlow<SettingsSnapshot?>(null)
    private val effectiveVisibilityState = MutableStateFlow(VisibilityPreference.DEFAULT.mode)
    private val trackers = MutableStateFlow<List<TransferTracker>>(emptyList())
    private val offerState = MutableStateFlow<List<NodeOffer>>(emptyList())
    private val pending = ConcurrentHashMap<String, PendingOffer>()
    private val receivedFlow =
        MutableSharedFlow<ReceivedItem>(extraBufferCapacity = RECEIVED_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val browserState = MutableStateFlow<BrowserShareStatus>(BrowserShareStatus.Idle)
    private var browserJob: Job? = null
    private val jobs = ArrayList<Job>()

    /** Answers "Allow this computer?" for the browser receive page (N15); the UI's presenter. Refuses until set. */
    @Volatile
    var browserApprover: BrowserApprover = BrowserApprover { false }

    /** The database; available once [start] returned. */
    val data: DropData get() = dataRef

    /** This device's id (`device_id` hex, architecture §12); available once [start] returned. */
    val selfDeviceId: String get() = selfId

    /** The control listener's port (announced in the mDNS record); available once [start] returned. */
    val controlPort: Int get() = checkNotNull(binding.value) { "the node is not started" }.listener.port

    /** The LAN interface address everything network-facing is bound to now ([setLanAddress]). */
    val lanAddress: StateFlow<InetAddress> = lanAddressState.asStateFlow()

    /** Whether a BLE radio is available (false in WP10a: the no-Bluetooth banner shows, design §9). */
    val bluetoothAvailable: Boolean get() = services.bluetoothAvailable

    /** Capability bits this device announces (§5.2): bit 12 while it has no Bluetooth the app can drive. */
    val capabilities: Capabilities =
        if (services.bluetoothAvailable) Capabilities.NONE else Capabilities.of(Capabilities.Flag.DESKTOP_WITHOUT_BLUETOOTH)

    /** Devices on the radar (F‑A2, F‑A3), nearest first. */
    val devices: StateFlow<List<NearbyDevice>> = nearby.devices

    /** Running and just-finished transfers (finished ones stay for [NodeTuning.finishedRetentionMillis]). */
    val transfers: StateFlow<List<NodeTransfer>> =
        trackers
            .flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.state }) { it.toList() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Offers waiting for the user, oldest first (F‑D1). */
    val offers: StateFlow<List<NodeOffer>> = offerState.asStateFlow()

    /** Received files as they are published (F‑D3); never suspends the engine, drops the oldest when nobody reads. */
    val received: SharedFlow<ReceivedItem> = receivedFlow.asSharedFlow()

    /** Completions, pairings and problems. */
    val events: SharedFlow<NodeEvent> = eventFlow.asSharedFlow()

    /** Every setting (F‑G5), once the database is open. */
    val settings: StateFlow<SettingsSnapshot?> = settingsState.asStateFlow()

    /** The chosen visibility with its 10-minute window (the chip, F‑A5). */
    val visibility: StateFlow<VisibilityPreference> =
        settingsState.map {
            it?.visibility ?: VisibilityPreference.DEFAULT
        }.stateIn(scope, SharingStarted.Eagerly, VisibilityPreference.DEFAULT)

    /** The visibility in force now (a 10-minute window that ended reads as the mode it reverts to). */
    val effectiveVisibility: StateFlow<Visibility> = effectiveVisibilityState.asStateFlow()

    /** This device's nickname (F‑I3): the setting, else [DesktopNodeConfig.defaultNickname]. */
    val nickname: StateFlow<String> =
        settingsState
            .map { nicknameOf(it?.nickname) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, nicknameOf(null))

    /** The browser receive path (F‑H4). */
    val browserShare: StateFlow<BrowserShareStatus> = browserState.asStateFlow()

    /** The Received folder new transfers are saved to (Settings "Save location", else `~/Received/<App>/`). */
    val receivedFolder: Path get() = config.directories.receivedFolder(settingsState.value?.saveLocation)

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    /**
     * Opens the database, loads the identity, binds the control listener and starts discovery, the announcement,
     * the accept loop and the 24 h sweep; sends an app restart interrupted wait for their peers again (T‑07).
     *
     * @throws IllegalStateException when called twice.
     * @throws IOException when the listener cannot bind or a directory cannot be created.
     * @throws com.constrivo.drop.platform.desktop.SecretStorageException when the stored identity cannot be read.
     */
    suspend fun start() {
        check(started.compareAndSet(false, true)) { "a DesktopNode starts once" }
        withContext(config.io) {
            config.directories.ensureCreated()
            dataRef = config.database ?: DesktopDatabase.open(
                config.directories.database,
                config.secrets,
                crypto,
                config.wallClock,
                config.calendar,
                config.io,
            )
            identity = SoftwareIdentityKeyStore(config.secrets, crypto).loadOrCreate()
            selfId = DeviceIds.of(crypto, identity.publicKey)
            advertising = AdvertisingSecretStore(config.secrets, crypto)
            ownSecrets.value = listOf(advertising.current().bytes())
            ownGeneration = data.devices.ownAdvertisingGeneration()
            refreshTrust()
            settingsState.value = data.settings.snapshot()
            effectiveVisibilityState.value = data.settings.effectiveVisibility()
            binding.value = LanBinding(config.lanAddress, bindListener(config.lanAddress, config.controlPort))
            senderStore = DirectoryFileStore(config.directories.partials, config.directories.received, io = config.io)
            resumeStore =
                DataResumeStore(data, FileResumePlanStore({ id -> config.directories.partials.resolve(id.toHex()) }, config.io)) { m, e ->
                    report(m, e)
                }
            sweeper =
                PartialsSweeper(
                    data,
                    senderStore,
                    config.wallClock,
                    intervalMillis = tuning.sweepIntervalMillis,
                    onError = { report("the 24 h clean-up of partial files failed", it) },
                )
        }
        jobs += scope.launch { data.settings.observeAll().collect { settingsState.value = it } }
        jobs += scope.launch { data.settings.observeEffectiveVisibility().collect { effectiveVisibilityState.value = it } }
        jobs +=
            scope.launch {
                data.devices.observeTrustedKeys { id, e -> report("the secrets of device $id do not open", e) }.collect { keys ->
                    setTrust(keys)
                }
            }
        val trust = combine(ownSecrets, trustedKeys) { own, keys -> TrustState(own, keys.toTrustedPeers()) }
        val sightings = services.beaconRadios.map { it.scan(RadioMode.FOREGROUND) }.merge()
        jobs += nearby.launchIn(scope, sightings, lanEvents(), trust)
        jobs += scope.launch { announceLoop() }
        jobs += scope.launch { binding.filterNotNull().collectLatest { acceptLoop(it) } }
        jobs += scope.launch { trustSyncLoop() }
        jobs += sweeper.launchIn(scope)
        jobs += scope.launch { restoreSends() }
    }

    /**
     * Stops everything within a few seconds, whatever blocks: offers are declined, the ladders tear their links down
     * (F‑E11) and every running transfer is left to resume later (its partial files, resume record and History row
     * stay; a sender offers it again after the next start, the receiver resumes it from its record, T‑07), the record
     * is withdrawn and the database is closed if the node opened it. Idempotent.
     */
    suspend fun stop() {
        if (!started.get() || !stopping.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            val deadline = config.monotonicClock.elapsedMillis() + STOP_BUDGET_MILLIS
            // Each step runs on its own and is waited for at most its share of the budget: one that blocks its thread
            // (JmDNS's goodbyes, a stuck socket, a database call) is left to finish behind instead of holding up the
            // quit, and once the budget is spent the remaining steps still start, unwaited.
            val steps = CoroutineScope(SupervisorJob() + config.io)

            suspend fun step(
                max: Long = STOP_WAIT_MILLIS,
                block: suspend () -> Unit,
            ) {
                val job = steps.launch { runCatching { block() } }
                val left = minOf(max, deadline - config.monotonicClock.elapsedMillis())
                if (left > 0) withTimeoutOrNull(left) { job.join() }
            }
            stopBrowserShare()
            browserJob?.let { job -> step { job.join() } }
            for (offer in pending.values) runCatching { offer.incoming.decline(DeclineReason.BUSY) }
            binding.value?.listener?.let { runCatching { it.close() } }
            val attempts = trackers.value.mapNotNull { it.attempt }
            step { for (a in attempts) runCatching { a.bridge?.runner?.value?.closeAndAwait() } }
            step { config.lan.withdraw() }
            for (radio in services.beaconRadios) step { radio.stopAdvertising() }
            for (a in attempts) {
                a.retired.complete(Unit)
                a.scope.cancel()
                step(ATTEMPT_CLOSE_WAIT_MILLIS) { a.session.close() }
            }
            trackers.value.forEach { it.abandon() }
            for (job in jobs) job.cancel()
            nodeJob.cancel()
            step { nodeJob.join() }
            for (tracker in trackers.value) tracker.store?.let { s -> step(ATTEMPT_CLOSE_WAIT_MILLIS) { s.directory.closeAll() } }
            if (config.database == null) step { data.close() }
        }
    }

    /** [stop], blocking. */
    override fun close() = runBlocking { stop() }

    // =====================================================================================================
    // The LAN binding (F-H5: start at login before Wi-Fi is up; a network change)
    // =====================================================================================================

    /**
     * Moves the node to [address] (the machine joined another network, or its address changed): the control listener
     * binds it (the same port when free), mDNS moves to it ([RebindableLanDiscovery]) and the record is announced there
     * with the new endpoint; new LAN links and browser pages use it. A transfer on the old address loses its link and
     * resumes over the new one (S8). No-op for the current address.
     *
     * @throws IOException when no port can be bound on [address] (the node stays on the old address).
     */
    suspend fun setLanAddress(address: InetAddress) {
        require(!address.isAnyLocalAddress) { "bind to the LAN interface address, never the wildcard" }
        checkRunning()
        bindingLock.withLock {
            val old = binding.value ?: return
            if (old.address == address) return
            val port = if (config.controlPort != 0) config.controlPort else old.listener.port
            val listener = withContext(config.io) { bindListener(address, port, fallbackToAny = config.controlPort == 0) }
            lanAddressState.value = address
            binding.value = LanBinding(address, listener)
            runCatching { old.listener.close() }
            (config.lan as? RebindableLanDiscovery)?.let { lan ->
                try {
                    lan.rebind(address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("mDNS could not move to ${address.hostAddress}", e)
                }
            }
        }
    }

    /** A control listener on [address]:[port], or on a free port when [port] is taken and [fallbackToAny]. */
    private fun bindListener(
        address: InetAddress,
        port: Int,
        fallbackToAny: Boolean = false,
    ): TcpListener =
        try {
            TcpListener(InetSocketAddress(address, port), LinkKind.LAN, controlIo)
        } catch (e: BindException) {
            if (!fallbackToAny || port == 0) throw e
            TcpListener(InetSocketAddress(address, 0), LinkKind.LAN, controlIo)
        }

    /** mDNS events; a browse that ends or fails is started again, so discovery never stops for good. */
    private fun lanEvents() =
        flow {
            var backoff = BROWSE_RETRY_MILLIS
            while (currentCoroutineContext().isActive) {
                try {
                    config.lan.browse().collect {
                        backoff = BROWSE_RETRY_MILLIS
                        emit(it)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("mDNS browsing stopped; trying again", e)
                }
                delay(backoff)
                backoff = minOf(backoff * 2, BROWSE_RETRY_MAX_MILLIS)
            }
        }

    // =====================================================================================================
    // Sending
    // =====================================================================================================

    /**
     * Sends [files] to the device on the radar under [deviceKey] (F‑C1, F‑C2, F‑C6): dials its LAN endpoints behind
     * the handshake identity check, shows the SAS on this side when the session proved no pairing, offers the files and
     * runs the transfer, offering it again if its link drops (S8). Returns the transfer id at once (the transfer shows
     * as [NodeStage.CONNECTING]), or null when [files] is empty or the device is no longer on the radar.
     */
    fun send(
        deviceKey: String,
        files: List<SendFile>,
    ): String? {
        checkRunning()
        if (files.isEmpty()) return null
        val device = devices.value.firstOrNull { it.key == deviceKey } ?: return null
        val id = TransferId(crypto.randomBytes(TransferId.SIZE))
        val tracker =
            newTracker(
                id,
                NodeDirection.SEND,
                NodeTransfer(
                    id = id.toHex(),
                    direction = NodeDirection.SEND,
                    peerKey = deviceKey,
                    peerDeviceId = device.trustedDeviceId,
                    peerName = device.nickname.orEmpty(),
                    peerPlatform = device.platform,
                    stage = NodeStage.CONNECTING,
                    fileCount = files.size,
                    bytesTotal = files.sumOf { it.size },
                    bytesDone = 0,
                ),
            )
        tracker.sendSpec = SendSpec(files, bundleSmall = settingsState.value?.bundleSmallFiles ?: true)
        addTracker(tracker)
        scope.launch { runSend(tracker, device) }
        return id.toHex()
    }

    /** Sends the same files to the same device again (History "Send again", F‑G2); null when that is impossible now. */
    suspend fun resend(transferId: String): String? {
        val id = parseId(transferId) ?: return null
        val record = data.transfers.get(id) ?: return null
        if (record.direction != TransferDirection.SEND) return null
        val files =
            data.transferFiles.files(id).mapNotNull { f ->
                val path = f.savedUri?.let(::sourcePathOf)
                path?.takeIf { Files.isRegularFile(it) }?.let { SendFile(it, f.name, Files.size(it)) }
            }
        if (files.isEmpty()) return null
        val key = devices.value.firstOrNull { it.trustedDeviceId == record.peerDeviceId }?.key ?: return null
        return send(key, files)
    }

    /**
     * The whole life of a send: the first session (from the radar's [device]; null for a send restored after a
     * restart, which starts waiting for its peer), then attempt after attempt until the transfer ends (S8).
     */
    private suspend fun runSend(
        tracker: TransferTracker,
        device: NearbyDevice?,
        interruptedAt: Long? = null,
    ) {
        val spec = checkNotNull(tracker.sendSpec)
        var dialed: Dialed? = null
        var since = interruptedAt
        if (device != null) {
            dialed =
                try {
                    openFirstSession(tracker, device) ?: return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HandshakeException) {
                    return failEarly(tracker, "handshake refused: ${e.reason}")
                } catch (e: Exception) {
                    return failEarly(tracker, describe(e), e)
                }
        }
        var restored = device == null
        while (true) {
            val current = dialed ?: reconnectSession(tracker, since ?: now(), restored) ?: return
            dialed = null
            restored = false
            if (tracker.cancelRequested) {
                // Cancelled while it dialled: the Offer never goes out.
                closeQuietly(current.session)
                if (!tracker.hasRow) {
                    tracker.update { it.copy(stage = NodeStage.CANCELLED) }
                    tracker.abandon()
                } else {
                    tracker.abort(NodeStage.CANCELLED, null)
                }
                scheduleRemoval(tracker)
                return
            }
            val resumed = since != null
            val attempt =
                try {
                    startSendAttempt(tracker, spec, current, resumed)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { closeQuietly(current.session) }
                    throw e
                } catch (e: Exception) {
                    closeQuietly(current.session)
                    if (!resumed) return failEarly(tracker, describe(e), e)
                    report("sending ${tracker.hex} again failed", e)
                    tracker.abort(NodeStage.FAILED, describe(e))
                    scheduleRemoval(tracker)
                    return
                }
            when (val end = awaitAttempt(attempt, stopOnInterruption = true)) {
                is AttemptEnd.Final -> {
                    finishTracker(tracker, end.progress, folder = null)
                    endAttempt(attempt)
                    return
                }

                AttemptEnd.Interrupted -> {
                    retire(tracker, attempt)
                    tracker.markInterrupted()
                    since = now()
                }

                AttemptEnd.Retired -> {
                    return
                }
            }
        }
    }

    /**
     * Dials [device] for a first send and records what the handshake verified. A device this one trusts that the radar
     * could not resolve (its `k_adv` is not here yet) is dialled once more with the pairing's trusted proof, so the
     * transfer runs trusted and the `TrustShare`s cross (S3); when that proof is not answered either, the first
     * session carries the transfer and both screens show the SAS. Null when the device has no endpoint (the tracker
     * then shows the failure).
     */
    private suspend fun openFirstSession(
        tracker: TransferTracker,
        device: NearbyDevice,
    ): Dialed? {
        val endpoints = device.lanEndpoints.map { Endpoint(it.host, it.port) }
        if (endpoints.isEmpty()) {
            failEarly(tracker, "the device has no LAN endpoint")
            return null
        }
        val expected = device.trustedDeviceId?.let { expectationFor(it) }
        var dialed = dial(endpoints, expected)
        try {
            val peerKey = dialed.session.handshake.peerIdentityKey
            val secret = lookup.recognitionSecretFor(peerKey)
            if (expected == null && secret != null && !dialed.session.handshake.peerProvedTrust) {
                val proven =
                    try {
                        dial(listOf(dialed.endpoint), ExpectedPeer(peerKey, secret))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (proven != null && proven.session.handshake.peerProvedTrust) {
                    closeQuietly(dialed.session)
                    dialed = proven
                } else {
                    proven?.let { closeQuietly(it.session) }
                }
            }
            val result = dialed.session.handshake
            val peerId = DeviceIds.of(crypto, peerKey)
            val peerPlatform = platformOf(result.peerPlatform)
            recordPeer(peerKey, result.peerNickname, peerPlatform)
            tracker.peerIdentity = peerKey
            tracker.endpoints = (listOf(dialed.endpoint) + endpoints).distinct()
            tracker.update {
                it.copy(peerDeviceId = peerId, peerName = result.peerNickname.ifEmpty { it.peerName }, peerPlatform = peerPlatform)
            }
            if (!isMutual(result)) {
                // F-B3: the code of this first session, the same on both screens, until this side's user answers it.
                tracker.pairingResult = result
                tracker.setPairingCode(result.sas)
            }
            return dialed
        } catch (e: Throwable) {
            // Nothing owns the session yet, so it closes here.
            withContext(NonCancellable) { closeQuietly(dialed.session) }
            throw e
        }
    }

    /** Starts the engine for one attempt of a send on [session]; its `Offer` goes out at once. */
    private suspend fun startSendAttempt(
        tracker: TransferTracker,
        spec: SendSpec,
        dialed: Dialed,
        resumed: Boolean,
    ): Attempt {
        val session = dialed.session
        val result = session.handshake
        val sources = ArrayList<SourceFile>(spec.files.size)
        val attemptScope = attemptScope()
        try {
            // Opened one by one inside the try: a file that went missing closes the ones opened before it.
            for (file in spec.files) sources += senderStore.source(file.path, file.name)
            val plan = plan(result, TransferRole.SENDER)
            val lanRung = tuning.lanRung
            val options =
                SendOptions(
                    transferId = tracker.id,
                    linkOptions =
                        if (lanRung) {
                            LadderNegotiation.offerOptions(
                                plan,
                                p2pCredentials = null,
                                lanAddress = lanAddress.value.hostAddress,
                            )
                        } else {
                            emptyList()
                        },
                    chunkSize = spec.chunkSize,
                    bundleSmall = spec.bundleSmall,
                )
            val engine = TransferEngine(engineConfig(sessionConfig(), senderStore) { tracker.onPersist(it) }, attemptScope)
            val transfer = engine.send(session, sources, options)
            val peerAddress = dialed.remote
            val bridge =
                if (!lanRung) {
                    null
                } else {
                    LadderTransferBridge(transfer, attemptScope) { ladderSession, base ->
                        val agreement = LadderNegotiation.adopt(plan, null, transfer.accept.value?.link) { P2pCredentials.random(crypto) }
                        ladderRunner(agreement, ladderSession, base, attemptScope, peerAddress)
                    }.also { transfer.linkListener = it }
                }
            val attempt = Attempt(attemptScope, session, transfer, bridge, sources, peerAddress)
            tracker.attach(attempt, capabilities, Capabilities(result.peerCaps), resumed)
            if (!tracker.isAwake) tracker.holdAwake(power.keepAwake("sending ${tracker.hex}"))
            if (!tracker.hasRow) {
                createSendHistory(
                    tracker,
                    DeviceIds.of(crypto, result.peerIdentityKey),
                    transfer.offer.mimeHistogram,
                    spec.files,
                )
            }
            return attempt
        } catch (e: Throwable) {
            attemptScope.cancel()
            withContext(NonCancellable) { for (s in sources) runCatching { s.close() } }
            throw e
        }
    }

    private suspend fun createSendHistory(
        tracker: TransferTracker,
        peerId: String,
        histogram: Map<String, Int>,
        files: List<SendFile>,
    ) {
        try {
            data.transfers.create(NewTransfer(tracker.id, peerId, TransferDirection.SEND, files.sumOf { it.size }, files.size, histogram))
            files
                .mapIndexed { i, f -> NewTransferFile(i, f.name, null, f.size, uri = f.path.toUri().toString()) }
                .chunked(FILE_BATCH)
                .forEach { data.transferFiles.add(tracker.id, it) }
            tracker.recordCreated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("History row for transfer ${tracker.hex} could not be written", e)
        }
    }

    /**
     * S8 for a send whose link dropped at [since] (monotonic): re-dial the peer with back-off for the reconnect window,
     * then wait for it to be seen again, until the parked window ends. A [restored] send (an app restart) starts parked
     * and tries as soon as its peer is on the radar. Returns the new session, or null when the transfer ended here
     * (cancelled, given up) or the node stops.
     */
    private suspend fun reconnectSession(
        tracker: TransferTracker,
        since: Long,
        restored: Boolean,
    ): Dialed? {
        val parkedUntil = since + tuning.parkedWindowMillis
        var windowEnd = since + tuning.reconnectWindowMillis
        var backoff = RECONNECT_BACKOFF_MILLIS
        // What the radar showed of the peer when the last dials failed: parking waits for something newer.
        var seenBefore: List<NearbyDevice>? = if (restored) emptyList() else null
        while (true) {
            if (stopping.get()) return null
            if (tracker.cancelRequested) {
                tracker.abort(NodeStage.CANCELLED, null)
                scheduleRemoval(tracker)
                return null
            }
            val now = now()
            if (now >= parkedUntil) {
                tracker.abort(NodeStage.CANCELLED, "${tracker.state.value.peerName.ifEmpty { "the device" }} did not come back")
                scheduleRemoval(tracker)
                return null
            }
            if (now >= windowEnd) {
                // Parked: nothing is dialled until the peer is seen again (or once a minute, the radar may be stale).
                tracker.markInterrupted(NodeStage.WAITING_FOR_PEER)
                waitForPeer(tracker, parkedUntil - now, seenBefore ?: peerSightings(tracker, devices.value))
                seenBefore = null
                windowEnd = minOf(now() + tuning.reconnectWindowMillis, parkedUntil)
                backoff = RECONNECT_BACKOFF_MILLIS
                continue
            }
            tracker.markInterrupted(NodeStage.RECONNECTING)
            tryDialPeer(tracker)?.let { return it }
            val waitFor = minOf(backoff, (windowEnd - now()).coerceAtLeast(1))
            withTimeoutOrNull(waitFor) { tracker.wake.drop(1).first() }
            backoff = minOf(backoff * 2, RECONNECT_BACKOFF_MAX_MILLIS)
        }
    }

    /** Waits until the radar shows [tracker]'s peer other than [before] (seen again), a wake-up, or [maxWait]. */
    private suspend fun waitForPeer(
        tracker: TransferTracker,
        maxWait: Long,
        before: List<NearbyDevice>,
    ) {
        withTimeoutOrNull(minOf(maxWait, PARKED_RETRY_MILLIS)) {
            merge(
                devices.map { peerSightings(tracker, it) }.filter { it.isNotEmpty() && it != before }.map { },
                tracker.wake.drop(1).map { },
            ).first()
        }
    }

    /** One try at a new session with [tracker]'s peer: what the radar shows for it now, then its known endpoints. */
    private suspend fun tryDialPeer(tracker: TransferTracker): Dialed? {
        val peerKey = tracker.peerIdentity ?: return null
        val endpoints = candidateEndpoints(tracker)
        if (endpoints.isEmpty()) return null
        return try {
            val dialed = dial(endpoints, ExpectedPeer(peerKey, lookup.recognitionSecretFor(peerKey)))
            tracker.endpoints = (listOf(dialed.endpoint) + tracker.endpoints).distinct().take(MAX_REMEMBERED_ENDPOINTS)
            dialed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Where [tracker]'s peer may be: the endpoints used before, then the radar's entries for the same device or host. */
    private fun candidateEndpoints(tracker: TransferTracker): List<Endpoint> {
        val fresh = peerSightings(tracker, devices.value).flatMap { d -> d.lanEndpoints.map { Endpoint(it.host, it.port) } }
        return (fresh + tracker.endpoints).distinct()
    }

    /**
     * The radar entries that may be [tracker]'s peer: resolved to its device id, its radar key, or a stranger at a host
     * it used (a restarted peer announces a new port; its rotating ID may be unknown). Resolved entries come first; the
     * handshake's identity check rejects any other device.
     */
    private fun peerSightings(
        tracker: TransferTracker,
        list: List<NearbyDevice>,
    ): List<NearbyDevice> {
        val t = tracker.state.value
        val hosts = tracker.endpoints.mapTo(HashSet()) { it.host }
        val resolved = list.filter { d -> t.peerDeviceId != null && d.trustedDeviceId == t.peerDeviceId }
        val others =
            list.filter { d ->
                d !in resolved &&
                    ((t.peerKey != null && d.key == t.peerKey) || (d.trustedDeviceId == null && d.lanEndpoints.any { it.host in hosts }))
            }
        return resolved + others
    }

    /**
     * Sends that an app restart interrupted (T‑07): each unfinished send to a trusted device whose files are all still
     * there waits for its peer and offers the transfer again with its id, so the receiver resumes it. A stranger cannot
     * be recognised on the radar after a restart; its row is left to the 24 h clean-up.
     */
    private suspend fun restoreSends() {
        val rows = runCatching { data.transfers.active() }.getOrElse { return }
        for (row in rows) {
            if (row.direction != TransferDirection.SEND || trackers.value.any { it.id == row.id }) continue
            val restored = runCatching { restoredSend(row) }.getOrNull() ?: continue
            val (tracker, remaining) = restored
            addTracker(tracker)
            val since = now() - (tuning.parkedWindowMillis - remaining).coerceAtLeast(tuning.reconnectWindowMillis)
            scope.launch { runSend(tracker, device = null, interruptedAt = since) }
        }
    }

    /** A tracker for the unfinished send [row] with how long it may still wait, or null when it cannot be resumed. */
    private suspend fun restoredSend(row: TransferRecord): Pair<TransferTracker, Long>? {
        val keys = data.devices.trustedKeys(row.peerDeviceId) ?: return null
        val peerKey = keys.device.identityKey() ?: return null
        val remaining = row.updatedAtMillis + tuning.parkedWindowMillis - config.wallClock.nowMillis()
        if (remaining <= 0) return null
        val rows = data.transferFiles.files(row.id)
        if (rows.size != row.fileCount || rows.indices.any { rows[it].index != it }) return null
        val files =
            rows.map { f ->
                val path = f.savedUri?.let(::sourcePathOf) ?: return null
                if (!Files.isRegularFile(path) || Files.size(path) != f.size) return null
                SendFile(path, f.name, f.size)
            }
        val bundle = HintCode.BUNDLING in row.hints || (settingsState.value?.bundleSmallFiles ?: true)
        val tracker =
            newTracker(
                row.id,
                NodeDirection.SEND,
                NodeTransfer(
                    id = row.id.toHex(),
                    direction = NodeDirection.SEND,
                    peerKey = null,
                    peerDeviceId = row.peerDeviceId,
                    peerName = row.peerName,
                    peerPlatform = row.peerPlatform,
                    stage = NodeStage.WAITING_FOR_PEER,
                    fileCount = row.fileCount,
                    bytesTotal = row.bytesTotal,
                    bytesDone = row.bytesDone,
                    mimeHistogram = row.mimeHistogram,
                ),
            )
        tracker.recordCreated()
        tracker.markAccepted()
        tracker.peerIdentity = peerKey
        tracker.sendSpec = SendSpec(files, bundle)
        return tracker to remaining
    }

    /** "Yes, it matches" on the sender's code (F‑B3), during the transfer or after it ended: the peer becomes trusted here. */
    fun confirmPairing(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId && it.direction == NodeDirection.SEND } ?: return
        val result = tracker.pairingResult ?: return
        if (tracker.pairingCode == null) return
        tracker.setPairingCode(null)
        scope.launch {
            storeTrust(result)
            scheduleTrustSync(result.peerIdentityKey, tracker.endpoints)
        }
    }

    /** "Not now" on a finished send's code (F‑B3): the code goes without trusting the device. */
    fun dismissPairing(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId && it.direction == NodeDirection.SEND } ?: return
        tracker.setPairingCode(null)
    }

    /**
     * Cancels a transfer (design §4.2 ×; §7.8 `Cancel{user}`); the receiver clears its partial files. A send's code goes
     * with it.
     */
    fun cancel(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId } ?: return
        if (tracker.direction == NodeDirection.SEND) tracker.setPairingCode(null)
        val transfer = tracker.transfer
        if (transfer != null) {
            transfer.cancel(CancelReason.USER)
        } else {
            tracker.cancelRequested = true
            tracker.wake.update { it + 1 }
            if (tracker.state.value.stage == NodeStage.CONNECTING) tracker.update { it.copy(stage = NodeStage.CANCELLED) }
        }
    }

    // =====================================================================================================
    // Receiving
    // =====================================================================================================

    private suspend fun acceptLoop(current: LanBinding) {
        while (currentCoroutineContext().isActive) {
            val channel =
                try {
                    current.listener.accept(lowLatency = true)
                } catch (e: IOException) {
                    if (stopping.get() || binding.value !== current) return
                    report("the control listener failed", e)
                    delay(ACCEPT_RETRY_MILLIS)
                    continue
                }
            val ticket = gate.tryEnter(channel.remoteAddress?.address?.hostAddress)
            if (ticket == null) {
                // More connections than may be unauthenticated at once, or too many from this host: refused at once.
                runCatching { channel.close() }
                continue
            }
            scope.launch { handleInbound(channel, ticket) }
        }
    }

    private suspend fun handleInbound(
        raw: TcpDataChannel,
        ticket: InboundGate.Ticket,
    ) {
        val sessionConfig = sessionConfig()
        val session =
            try {
                val hello =
                    try {
                        withTimeoutOrNull(tuning.helloWaitMillis) { HelloPeek.read(raw) }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { raw.close() }
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (hello == null) {
                    raw.close()
                    return
                }
                try {
                    SessionHandshake.respond(ReplayChannel(raw, hello.frame), sessionConfig)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HandshakeException) {
                    report("an inbound handshake was refused: ${e.reason}", null)
                    return
                } catch (e: Exception) {
                    return
                }
            } finally {
                ticket.close()
            }
        if (waitingForOffer.incrementAndGet() > tuning.maxWaitingForOffer) {
            waitingForOffer.decrementAndGet()
            closeQuietly(session)
            return
        }
        val counted = AtomicBoolean(true)
        val offerArrived = { if (counted.compareAndSet(true, false)) waitingForOffer.decrementAndGet() }
        try {
            onInboundSession(session, sessionConfig, raw.remoteAddress?.address, offerArrived)
        } finally {
            offerArrived()
        }
    }

    private suspend fun onInboundSession(
        session: SecureSession,
        sessionConfig: SessionConfig,
        remote: InetAddress?,
        offerArrived: () -> Unit,
    ) {
        val result = session.handshake
        val peerKey = result.peerIdentityKey
        val peerId = DeviceIds.of(crypto, peerKey)
        val peerPlatform = platformOf(result.peerPlatform)
        if (!recordPeer(peerKey, result.peerNickname, peerPlatform)) {
            closeQuietly(session)
            return
        }
        val device = runCatching { data.devices.find(peerId) }.getOrNull()
        val mutual = isMutual(result) && device?.isTrusted == true
        val attemptScope = attemptScope()
        val intake = OfferIntake(peerKey)
        val engine = TransferEngine(receiveEngineConfig(sessionConfig, intake), attemptScope)
        val incoming =
            try {
                withTimeoutOrNull(if (mutual) tuning.offerWaitMillis else tuning.untrustedOfferWaitMillis) {
                    engine.receive(session, ReceiveOptions())
                }
            } catch (e: CancellationException) {
                attemptScope.cancel()
                throw e
            } catch (e: Exception) {
                // A session without an Offer: the trust exchange after a pairing (S3), or a peer that went away.
                null
            }
        offerArrived()
        if (incoming == null) {
            closeQuietly(session)
            attemptScope.cancel()
            return
        }
        onOffer(OfferContext(incoming, session, result, peerId, peerPlatform, device, mutual, intake, attemptScope, remote))
    }

    /** Everything [onOffer] needs about one inbound `Offer`. */
    private class OfferContext(
        val incoming: IncomingTransfer,
        val session: SecureSession,
        val result: HandshakeResult,
        val peerId: String,
        val peerPlatform: DevicePlatform,
        val device: Device?,
        val mutual: Boolean,
        val intake: OfferIntake,
        val scope: CoroutineScope,
        val remote: InetAddress?,
    )

    private suspend fun onOffer(ctx: OfferContext) {
        val incoming = ctx.incoming
        val offer = incoming.offer
        val id = offer.transferId
        val intake = ctx.intake
        val resumedTracker = intake.resumed
        val existing = if (resumedTracker == null) runCatching { data.transfers.get(id) }.getOrNull() else null
        // An id this device already has for another peer, or for a send of its own, is not this peer's to take.
        val foreign = existing != null && (existing.peerDeviceId != ctx.peerId || existing.direction != TransferDirection.RECEIVE)
        val refusal =
            when {
                intake.collision || foreign -> DeclineReason.BUSY

                // Already finished here (a re-offer whose Complete was lost): nothing to take again.
                intake.finishedHere || (existing != null && !existing.isActive) -> DeclineReason.OTHER

                intake.storeFailure != null -> DeclineReason.STORAGE

                else -> null
            }
        if (refusal != null) {
            if (refusal == DeclineReason.STORAGE) report("the Received folder cannot be used; the offer was declined", intake.storeFailure)
            if (incoming.isCompatible) incoming.decline(refusal)
            withTimeoutOrNull(REFUSAL_WAIT_MILLIS) { incoming.transfer.await() }
            ctx.scope.cancel()
            closeQuietly(ctx.session)
            return
        }
        val name = ctx.device?.displayName?.ifEmpty { null } ?: ctx.result.peerNickname
        val radarKey = devices.value.firstOrNull { it.trustedDeviceId == ctx.peerId }?.key
        val tracker =
            resumedTracker ?: newTracker(
                id,
                NodeDirection.RECEIVE,
                NodeTransfer(
                    id = id.toHex(),
                    direction = NodeDirection.RECEIVE,
                    peerKey = radarKey,
                    peerDeviceId = ctx.peerId,
                    peerName = name,
                    peerPlatform = ctx.peerPlatform,
                    stage = NodeStage.AWAITING_ACCEPT,
                    fileCount = offer.fileCount,
                    bytesTotal = offer.totalBytes,
                    bytesDone = 0,
                    mimeHistogram = offer.mimeHistogram,
                ),
            )
        tracker.peerIdentity = ctx.result.peerIdentityKey
        tracker.store = intake.store
        intake.tracker = tracker
        if (resumedTracker == null) {
            addTracker(tracker)
            createReceiveHistory(tracker, ctx.peerId, offer.totalBytes, offer.fileCount, offer.mimeHistogram)
        }
        val plan = plan(ctx.result, TransferRole.RECEIVER)
        val decision = LadderNegotiation.accept(plan, offer.linkOptions) { P2pCredentials.random(crypto) }
        val bridge =
            if (!tuning.lanRung) {
                null
            } else {
                LadderTransferBridge(incoming.transfer, ctx.scope) { ladderSession, base ->
                    ladderRunner(decision.agreement, ladderSession, base, ctx.scope, ctx.remote)
                }.also { incoming.transfer.linkListener = it }
            }
        val attempt = Attempt(ctx.scope, ctx.session, incoming.transfer, bridge, emptyList(), ctx.remote)
        tracker.attach(attempt, capabilities, Capabilities(ctx.result.peerCaps), resumed = resumedTracker != null)
        var waiting: PendingOffer? = null
        // An Offer of an unsupported protocol version was declined `incompatible` by the engine already.
        if (incoming.isCompatible) {
            when {
                resumedTracker != null -> {
                    // The user accepted this transfer in this process; its sender offers it again after a drop (S8).
                    scope.launch { acceptResumed(tracker, incoming, decision.intent) }
                }

                ctx.mutual && ctx.device?.autoAccept == true -> {
                    val auto = pendingOffer(ctx, tracker, decision.intent, name, radarKey)
                    scope.launch { acceptPending(auto, alwaysAccept = false) }
                }

                pending.size >= tuning.maxPendingOffers -> {
                    incoming.decline(DeclineReason.BUSY)
                }

                else -> {
                    val card = pendingOffer(ctx, tracker, decision.intent, name, radarKey)
                    waiting = card
                    pending[tracker.hex] = card
                    publishOffers()
                }
            }
        }
        // The card goes when the Offer is answered here, times out (§7.8) or is cancelled by the sender.
        val card = waiting
        val cardWatch =
            card?.let {
                scope.launch {
                    incoming.transfer.state.first { s -> s.phase != TransferPhase.OFFERED }
                    withdrawOffer(tracker.hex, it)
                }
            }
        val end = awaitAttempt(attempt, stopOnInterruption = false)
        cardWatch?.cancel()
        card?.let { withdrawOffer(tracker.hex, it) }
        if (end is AttemptEnd.Final) {
            finishTracker(tracker, end.progress, folderOf(end.progress))
            endAttempt(attempt)
        }
    }

    private fun pendingOffer(
        ctx: OfferContext,
        tracker: TransferTracker,
        intent: LinkIntent?,
        name: String,
        radarKey: String?,
    ): PendingOffer {
        val offer = ctx.incoming.offer
        val resume = ctx.incoming.isResume
        return PendingOffer(
            incoming = ctx.incoming,
            tracker = tracker,
            result = ctx.result,
            deviceId = ctx.peerId,
            intent = intent,
            offer =
                NodeOffer(
                    id = tracker.hex,
                    senderDeviceId = ctx.peerId,
                    senderKey = radarKey ?: "d:${ctx.peerId}",
                    senderName = name,
                    senderPlatform = ctx.peerPlatform,
                    trusted = ctx.mutual,
                    // A resume's code was its first session's; a new one would not match the sender's screen.
                    sas = if (ctx.mutual || resume) null else ctx.result.sas,
                    fileCount = offer.fileCount,
                    totalBytes = offer.totalBytes,
                    mimeHistogram = offer.mimeHistogram,
                    previewNames = offer.previewNames,
                    previews = offer.previews,
                    arrivedAtElapsedMillis = config.monotonicClock.elapsedMillis(),
                    isResume = resume,
                ),
        )
    }

    /**
     * Decides, once the engine read an `Offer` and asks for its resume record, where the transfer goes: an attempt of a
     * transfer this process already accepted from the same peer is retired first (the new one resumes it with the same
     * file store); an id this process holds for another peer, or for an offer still waiting for its user, is a
     * collision, declined `busy`; anything else gets a new store on the Received folder, or none when the folder cannot
     * be used (declined `storage`).
     */
    private inner class OfferIntake(
        private val peerKey: ByteArray,
    ) {
        val fileStore = DeferredFileStore()

        @Volatile
        var store: ReceiveStore? = null

        @Volatile
        var resumed: TransferTracker? = null

        @Volatile
        var collision = false

        @Volatile
        var finishedHere = false

        @Volatile
        var storeFailure: Exception? = null

        /** The tracker the offer ended up with, for the engine's persistence calls. */
        @Volatile
        var tracker: TransferTracker? = null

        val resumeStoreView: ResumeStore =
            object : ResumeStore by resumeStore {
                override suspend fun load(transferId: TransferId): ResumeRecord? {
                    decide(transferId)
                    return resumeStore.load(transferId)
                }
            }

        private suspend fun decide(id: TransferId) {
            val live = trackers.value.firstOrNull { it.id == id }
            if (live != null) {
                val samePeer = live.direction == NodeDirection.RECEIVE && live.peerIdentity?.contentEquals(peerKey) == true
                when {
                    samePeer && live.isFinished -> {
                        finishedHere = true
                    }

                    samePeer && live.everAccepted -> {
                        live.attempt?.let { retire(live, it) }
                        resumed = live
                        store = live.store
                        live.store?.let { fileStore.delegate = it.marking }
                        if (live.store == null) useNewStore()
                    }

                    else -> {
                        collision = true
                    }
                }
                return
            }
            useNewStore()
        }

        private fun useNewStore() {
            try {
                val created = receiveStore()
                store = created
                fileStore.delegate = created.marking
            } catch (e: Exception) {
                storeFailure = e
                fileStore.delegate = UnavailableFileStore
            }
        }
    }

    private suspend fun createReceiveHistory(
        tracker: TransferTracker,
        peerId: String,
        totalBytes: Long,
        fileCount: Int,
        histogram: Map<String, Int>,
    ) {
        try {
            data.transfers.create(NewTransfer(tracker.id, peerId, TransferDirection.RECEIVE, totalBytes, fileCount, histogram))
            tracker.recordCreated()
        } catch (e: DuplicateRecordException) {
            // The same transfer again after an app kill (T-07): its row and resume record are already there.
            val existing = runCatching { data.transfers.get(tracker.id) }.getOrNull()
            if (existing != null && existing.isActive && existing.peerDeviceId == peerId &&
                existing.direction == TransferDirection.RECEIVE
            ) {
                tracker.recordCreated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("History row for transfer ${tracker.hex} could not be written", e)
        }
    }

    /** "Accept" on the incoming card; [alwaysAccept] turns on auto-accept for this (trusted) sender (F‑D2). */
    fun accept(
        offerId: String,
        alwaysAccept: Boolean,
    ) {
        val waiting = pending[offerId] ?: return
        scope.launch { acceptPending(waiting, alwaysAccept) }
    }

    private suspend fun acceptPending(
        waiting: PendingOffer,
        alwaysAccept: Boolean,
    ) {
        if (!waiting.answered.compareAndSet(false, true)) return
        withdrawOffer(waiting.tracker.hex, waiting)
        waiting.trustJob?.join()
        if (alwaysAccept) {
            val set = runCatching { data.devices.setAutoAccept(waiting.deviceId, true) }.getOrDefault(false)
            if (!set) report("auto-accept needs a trusted device; ${waiting.deviceId} is not", null)
        }
        waiting.tracker.markAccepted()
        if (!waiting.tracker.isAwake) waiting.tracker.holdAwake(power.keepAwake("receiving ${waiting.tracker.hex}"))
        try {
            waiting.incoming.accept(link = waiting.intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("accepting ${waiting.tracker.hex} failed", e)
        }
    }

    /** The in-process resume of an accepted transfer (its sender offered it again after a drop): accepted at once. */
    private suspend fun acceptResumed(
        tracker: TransferTracker,
        incoming: IncomingTransfer,
        intent: LinkIntent?,
    ) {
        if (!tracker.isAwake) tracker.holdAwake(power.keepAwake("receiving ${tracker.hex}"))
        try {
            incoming.accept(link = intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("resuming ${tracker.hex} failed", e)
        }
    }

    /** "Decline" on the incoming card (§7.2 `Decline{user}`). */
    fun decline(offerId: String) = answer(offerId, DeclineReason.USER)

    /** The card's 30 s ran out (design §5.1; `Decline{timeout}`). */
    fun offerTimedOut(offerId: String) = answer(offerId, DeclineReason.TIMEOUT)

    private fun answer(
        offerId: String,
        reason: DeclineReason,
    ) {
        val waiting = pending[offerId] ?: return
        if (!waiting.answered.compareAndSet(false, true)) return
        withdrawOffer(offerId, waiting)
        // A code confirmed just before is stored first, so the trust exchange after this transfer finds it (S3).
        scope.launch {
            waiting.trustJob?.join()
            waiting.incoming.decline(reason)
        }
    }

    /** "Yes, it matches" on the incoming card (F‑B3): the sender becomes trusted on this device. */
    fun confirmCode(offerId: String) {
        val waiting = pending[offerId] ?: return
        if (waiting.offer.trusted || waiting.offer.sas == null || waiting.trustJob != null) return
        waiting.trustJob =
            scope.launch {
                storeTrust(waiting.result)
                // The sender exchanges the TrustShares once this transfer ends; should it not reach this device, this
                // side tries too (its endpoints are the radar's entries on the sender's address).
                val hosts = listOfNotNull((waiting.tracker.attempt?.peerAddress)?.hostAddress)
                scheduleTrustSync(waiting.result.peerIdentityKey, emptyList(), hosts, RECEIVER_SYNC_DELAY_MILLIS)
            }
    }

    private fun withdrawOffer(
        id: String,
        offer: PendingOffer,
    ) {
        if (pending.remove(id, offer)) publishOffers()
    }

    private fun publishOffers() {
        offerState.value = pending.values.map { it.offer }.sortedBy { it.arrivedAtElapsedMillis }
    }

    /**
     * A fresh file store for a received transfer: the Received folder may have changed in Settings meanwhile.
     *
     * @throws IOException when the folder cannot be created or used (an unplugged drive, a read-only share).
     */
    private fun receiveStore(): ReceiveStore {
        val destination = receivedFolder
        val directory = DirectoryFileStore(config.directories.partials, destination, io = config.io)
        if (!Files.isWritable(destination)) throw IOException("the Received folder $destination is not writable")
        val marking =
            MarkingFileStore(
                directory,
                services.downloadMarker,
                onMarkError = { path, e -> report("could not mark $path as downloaded", e) },
                onPublished = ::onPublished,
            )
        return ReceiveStore(directory, marking)
    }

    private fun onPublished(event: PublishedFileEvent) {
        val path = event.path ?: return
        receivedFlow.tryEmit(
            ReceivedItem(
                id = "${event.drop.transferId}:${event.fileIndex}",
                transferId = event.drop.transferId,
                path = path,
                name = event.file.name,
                mimeType = event.mimeType,
                senderName = event.drop.senderName,
                executable = FileTypes.isExecutable(event.file.name, event.mimeType),
            ),
        )
    }

    // =====================================================================================================
    // Attempts
    // =====================================================================================================

    /** A scope for one engine run: a child of the node's, cancelled alone when the attempt is retired. */
    private fun attemptScope(): CoroutineScope = CoroutineScope(SupervisorJob(nodeJob) + config.io + failureHandler)

    /** Waits until [attempt]'s transfer ends, the sender's link drops ([stopOnInterruption]), or it is retired. */
    private suspend fun awaitAttempt(
        attempt: Attempt,
        stopOnInterruption: Boolean,
    ): AttemptEnd =
        coroutineScope {
            val end = CompletableDeferred<AttemptEnd>()
            val watchers =
                buildList {
                    add(launch { end.complete(AttemptEnd.Final(attempt.transfer.await())) })
                    add(
                        launch {
                            attempt.retired.await()
                            end.complete(AttemptEnd.Retired)
                        },
                    )
                    if (stopOnInterruption) {
                        add(
                            launch {
                                attempt.transfer.state.first { it.phase.isInterrupted }
                                end.complete(AttemptEnd.Interrupted)
                            },
                        )
                    }
                }
            try {
                end.await()
            } finally {
                watchers.forEach { it.cancel() }
            }
        }

    /**
     * Ends [attempt] without ending its transfer (a newer attempt takes over, S8): the tracker stops following it, the
     * ladder tears its links down (F‑E11), then the engine's scope is cancelled, which keeps the partial files and the
     * resume record as an app kill would, and its handles are closed. Idempotent; a second caller waits for the first.
     */
    private suspend fun retire(
        tracker: TransferTracker,
        attempt: Attempt,
    ) {
        if (!attempt.retiring.compareAndSet(false, true)) {
            attempt.retirementDone.await()
            return
        }
        withContext(NonCancellable) {
            try {
                attempt.retired.complete(Unit)
                if (tracker.attempt === attempt) tracker.detach()
                withTimeoutOrNull(LADDER_CLOSE_WAIT_MILLIS) { attempt.bridge?.runner?.value?.closeAndAwait() }
                attempt.scope.cancel()
                withTimeoutOrNull(STOP_WAIT_MILLIS) { attempt.scope.coroutineContext[Job]?.join() }
                withTimeoutOrNull(ATTEMPT_CLOSE_WAIT_MILLIS) { runCatching { attempt.session.close() } }
                for (source in attempt.sources) runCatching { source.close() }
                tracker.store?.let { s -> withTimeoutOrNull(ATTEMPT_CLOSE_WAIT_MILLIS) { runCatching { s.directory.closeAll() } } }
            } finally {
                attempt.retirementDone.complete(Unit)
            }
        }
    }

    /** After an attempt's transfer ended: its ladder is closed ([finishTracker] waited for it), so its scope can go. */
    private fun endAttempt(attempt: Attempt) {
        attempt.scope.cancel()
    }

    private fun ladderRunner(
        agreement: LinkAgreement,
        ladderSession: com.constrivo.drop.core.ladder.LadderSession,
        base: Int,
        runnerScope: CoroutineScope,
        peerAddress: InetAddress?,
    ): LadderRunner =
        LadderRunner(
            agreement.plan,
            // Joined only at the session peer's own address (a LinkReady naming any other host is refused).
            listOf(LanLinkProvider(lanAddress.value, config.io, peerAddress = peerAddress)),
            ladderSession,
            runnerScope,
            config.monotonicClock,
            agreement.config(base),
            tuning.lifecycle,
        )

    // =====================================================================================================
    // Trust (F-B3, F-B4, F-B5, F-B6, F-G3; S3)
    // =====================================================================================================

    /**
     * Whether the session's handshake proved the pairing both ways: the peer answered with this pairing's recognition
     * secret (so both devices hold the same one) and this device trusts it. Only then is it trusted; otherwise both
     * screens show the SAS and a confirmed code replaces the pairing (F‑B3, F‑B4).
     */
    private fun isMutual(result: HandshakeResult): Boolean =
        result.peerProvedTrust && lookup.recognitionSecretFor(result.peerIdentityKey) != null

    private suspend fun storeTrust(result: HandshakeResult) {
        val peerId = DeviceIds.of(crypto, result.peerIdentityKey)
        val share = pendingShares.remove(peerId)
        try {
            // A confirmed code replaces the recognition secret: the pair now holds this session's (F-B3).
            val stored = data.devices.trust(peerId, result.recognitionSecret, share?.let { secretOf(it) }, share?.generation)
            if (!stored) report("could not trust device $peerId", null)
            refreshTrust()
            val name = data.devices.find(peerId)?.displayName ?: result.peerNickname
            if (share != null) eventFlow.tryEmit(NodeEvent.Paired(peerId, name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            share?.let { pendingShares[peerId] = it }
            report("could not store the pairing with $peerId", e)
        }
    }

    /** A pending trust exchange with one peer (S3). */
    private class TrustSync(
        val peerKey: ByteArray,
        val peerId: String,
    ) {
        val endpoints: MutableSet<Endpoint> = ConcurrentHashMap.newKeySet()
        val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        var notBefore = 0L

        @Volatile
        var attempts = 0

        @Volatile
        var running = false

        @Volatile
        var lastSightings: Set<Endpoint> = emptySet()

        /** A wake-up at [notBefore] is scheduled. */
        val wakeScheduled = AtomicBoolean(false)
    }

    /**
     * Exchanges `TrustShare`s with the peer of [peerKey] once no transfer with it runs (S3), trying [endpoints] and the
     * radar's entries for it or at [hosts]; retried after a failure and when the peer is seen again.
     */
    private fun scheduleTrustSync(
        peerKey: ByteArray,
        endpoints: Collection<Endpoint>,
        hosts: Collection<String> = emptyList(),
        delayMillis: Long = 0,
    ) {
        val peerId = DeviceIds.of(crypto, peerKey)
        val sync = trustSyncs.computeIfAbsent(peerId) { TrustSync(peerKey.copyOf(), peerId) }
        sync.endpoints += endpoints
        sync.hosts += hosts + endpoints.map { it.host }
        sync.notBefore = maxOf(sync.notBefore, now() + delayMillis)
        trustSyncSignal.update { it + 1 }
    }

    /** Starts the pending trust exchanges that can run now, whenever the radar, the transfers or the queue change. */
    private suspend fun trustSyncLoop() {
        merge(trustSyncSignal, devices.map { }, transfers.map { }).conflate().collect {
            for (sync in trustSyncs.values) maybeStartSync(sync)
            delay(TRUST_SYNC_LOOP_SPACING_MILLIS)
        }
    }

    private fun maybeStartSync(sync: TrustSync) {
        if (sync.running) return
        val now = now()
        val candidates = syncCandidates(sync)
        val sightings = candidates.toSet()
        // A new sighting of the peer retries at once; otherwise the back-off holds.
        val seenAgain = sync.attempts > 0 && sightings.isNotEmpty() && sightings != sync.lastSightings
        if (now < sync.notBefore && !seenAgain) {
            if (sync.wakeScheduled.compareAndSet(false, true)) {
                scope.launch {
                    delay(sync.notBefore - now)
                    sync.wakeScheduled.set(false)
                    trustSyncSignal.update { it + 1 }
                }
            }
            return
        }
        if (candidates.isEmpty()) return
        val busy = transfers.value.any { t -> !t.stage.isFinal && (t.peerDeviceId == sync.peerId) }
        if (busy) return
        sync.running = true
        sync.lastSightings = sightings
        scope.launch {
            val outcome =
                try {
                    runTrustSync(sync, candidates)
                } finally {
                    sync.running = false
                }
            when (outcome) {
                SyncOutcome.DONE, SyncOutcome.ONE_SIDED, SyncOutcome.GONE -> {
                    trustSyncs.remove(sync.peerId, sync)
                }

                SyncOutcome.RETRY -> {
                    sync.attempts++
                    if (sync.attempts >= NodeTuning.TRUST_SYNC_ATTEMPTS) {
                        trustSyncs.remove(sync.peerId, sync)
                        report("the advertising secrets with ${sync.peerId} could not be exchanged; they go out at the next session", null)
                    } else {
                        sync.notBefore = now() + tuning.trustSyncRetryMillis * (1L shl (sync.attempts - 1))
                    }
                }
            }
            trustSyncSignal.update { it + 1 }
        }
    }

    /**
     * Where the peer of [sync] may be: the radar's entries resolved to it, its known endpoints, then strangers at one of
     * its hosts (its rotating ID cannot be resolved before the exchange).
     */
    private fun syncCandidates(sync: TrustSync): List<Endpoint> {
        val list = devices.value
        val resolved = list.filter { it.trustedDeviceId == sync.peerId }
        val byHost = list.filter { d -> d.trustedDeviceId == null && d.lanEndpoints.any { it.host in sync.hosts } }
        return ((resolved + byHost).flatMap { d -> d.lanEndpoints.map { Endpoint(it.host, it.port) } } + sync.endpoints)
            .sortedBy { e -> if (resolved.any { d -> d.lanEndpoints.any { it.host == e.host && it.port == e.port } }) 0 else 1 }
            .distinct()
    }

    private enum class SyncOutcome { DONE, ONE_SIDED, GONE, RETRY }

    /**
     * After a pairing, both devices trust each other but neither may have the other's advertising secret yet (S3):
     * this side opens a short session whose handshake carries the pairing's trusted proof. When the peer answers it,
     * both `TrustShare`s cross (each side's session config sends its own once the proof is mutual), the peer's is
     * stored, and the session closes; when it does not, the peer did not confirm the code and nothing is shared.
     */
    private suspend fun runTrustSync(
        sync: TrustSync,
        candidates: List<Endpoint>,
    ): SyncOutcome {
        val keys =
            try {
                data.devices.trustedKeys(sync.peerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return SyncOutcome.GONE
        val session =
            try {
                dial(candidates, ExpectedPeer(sync.peerKey, keys.recognitionSecret())).session
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SyncOutcome.RETRY
            }
        try {
            if (!session.handshake.peerProvedTrust) return SyncOutcome.ONE_SIDED
            val share = withTimeoutOrNull(tuning.trustSyncWaitMillis) { readTrustShare(session) } ?: return SyncOutcome.RETRY
            onTrustShare(sync.peerKey, share).join()
            return SyncOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncOutcome.RETRY
        } finally {
            withContext(NonCancellable) { closeQuietly(session) }
        }
    }

    /** The peer's first `TrustShare` on [session], or null when the session ends without one. */
    private suspend fun readTrustShare(session: SecureSession): TrustShare? {
        while (true) {
            val header = session.primary.readHeader() ?: return null
            if (header.type != FrameType.CONTROL) {
                session.primary.skipPayload(header)
                continue
            }
            val message = session.primary.readControl(header)
            if (message is TrustShare) return message
        }
    }

    /** A peer's `TrustShare` (S3): stored when the peer is trusted, kept until then otherwise. */
    private fun onTrustShare(
        peerKey: ByteArray,
        share: TrustShare,
    ): Job =
        scope.launch {
            val peerId = DeviceIds.of(crypto, peerKey)
            try {
                val stored = data.devices.storeAdvertisingSecret(peerId, secretOf(share), share.generation)
                if (stored) {
                    pendingShares.remove(peerId)
                    refreshTrust()
                    val name = data.devices.find(peerId)?.displayName.orEmpty()
                    eventFlow.tryEmit(NodeEvent.Paired(peerId, name))
                } else if (data.devices.find(peerId)?.isTrusted != true) {
                    pendingShares[peerId] = share
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("could not store the advertising secret of $peerId", e)
            }
        }

    /**
     * The `TrustShare` this device sends at the start of a session (S3): only when the session proved the pairing both
     * ways, so the advertising secret goes to a device that holds this pairing, never on a one-sided trust.
     */
    private fun trustShareFor(result: HandshakeResult): TrustShare? {
        if (!isMutual(result)) return null
        val own = ownSecrets.value.firstOrNull() ?: return null
        return TrustShare(WireAdvertisingSecret(own), ownGeneration)
    }

    private fun secretOf(share: TrustShare): AdvertisingSecret = AdvertisingSecret.fromPeer(share.secret.toByteArray())

    /** Renames a trusted device (Devices tab, F‑G3). */
    suspend fun rename(
        deviceId: String,
        name: String?,
    ): Boolean = data.devices.rename(deviceId, name)

    /** Turns auto-accept on or off for a trusted device (F‑D2). */
    suspend fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ): Boolean = data.devices.setAutoAccept(deviceId, enabled)

    /**
     * "Forget" (F‑G3): the device is no longer trusted, and this device's `k_adv` is rotated so the forgotten device can
     * no longer recognise its beacon (S3); the remaining trusted devices get the new secret at their next session.
     */
    suspend fun forget(deviceId: String): Boolean {
        val forgotten = data.devices.forget(deviceId)
        if (!forgotten) return false
        trustSyncs.remove(deviceId)
        pendingShares.remove(deviceId)
        ownGeneration = data.devices.advanceOwnAdvertisingGeneration()
        val fresh = advertising.rotate()
        ownSecrets.value = listOf(fresh.bytes())
        refreshTrust()
        return true
    }

    /**
     * This device's static QR code (F‑B6, architecture §6.3): identity and the current beacon ID, no link and no
     * expiry. A phone that scans it verifies the device without a SAS and connects once it finds it on the LAN.
     */
    fun staticCode(): String {
        val own = ownSecrets.value.first()
        val eph = EphemeralIds.at(crypto, own, config.wallClock.nowMillis())
        return QrPayloadCodec(crypto).createStatic(identity, eph.toByteArray())
    }

    /**
     * "Show my code" (F‑B5, F‑H4, architecture §6.3): a code signed now and valid for five minutes, with this device's
     * LAN address and control port as a LAN link, so a phone that scans it dials this computer directly (behind the
     * handshake's identity check) where the network filters multicast and the radar cannot find it. Its fallback is
     * that address to type. Without a network (loopback) it carries no link.
     */
    fun oneTimeCode(): NodeCode {
        val nowMillis = config.wallClock.nowMillis()
        val own = ownSecrets.value.first()
        val eph = EphemeralIds.at(crypto, own, nowMillis)
        val address = lanAddress.value
        val endpoint = if (address.isLoopbackAddress) null else Endpoint(address.hostAddress, controlPort)
        val link = endpoint?.let { QrLink(QrLinkKind.LAN, ssid = null, passphrase = null, address = it.host, port = it.port) }
        val nowSeconds = (nowMillis / 1000).coerceAtLeast(1)
        val payload = QrPayloadCodec(crypto).createOneTime(identity, eph.toByteArray(), link, nowSeconds)
        return NodeCode(payload, endpoint?.toString(), nowMillis, (nowSeconds + QrPayload.ONE_TIME_VALIDITY_SECONDS) * 1000)
    }

    private suspend fun refreshTrust() {
        val keys = data.devices.trustedKeys { id, e -> report("the secrets of device $id do not open", e) }
        setTrust(keys)
    }

    private fun setTrust(keys: List<TrustedDeviceKeys>) {
        lookup = keys.toTrustedPeerLookup()
        trustedKeys.value = keys
    }

    /** The expected identity for a device the radar resolved as trusted [deviceId], with its pairing's proof. */
    private suspend fun expectationFor(deviceId: String): ExpectedPeer? =
        runCatching { data.devices.trustedKeys(deviceId) }.getOrNull()?.let { keys ->
            keys.device.identityKey()?.let { ExpectedPeer(it, keys.recognitionSecret()) }
        }

    // =====================================================================================================
    // Settings
    // =====================================================================================================

    /** Visibility (F‑A5): applies to the announcement and to handshakes at once. */
    suspend fun setVisibility(mode: Visibility) {
        data.settings.setVisibility(mode)
        effectiveVisibilityState.value = data.settings.effectiveVisibility()
    }

    /** The nickname (F‑I3); null goes back to the default. */
    suspend fun setNickname(name: String?) = data.settings.set(SettingKeys.NICKNAME, name)

    suspend fun setBundleSmallFiles(enabled: Boolean) = data.settings.set(SettingKeys.BUNDLE_SMALL_FILES, enabled)

    /** The Received folder (an absolute path), or null for `~/Received/<App>/`. */
    suspend fun setSaveLocation(path: Path?) = data.settings.set(SettingKeys.SAVE_LOCATION, path?.toAbsolutePath()?.toString())

    /**
     * "Clear partial files" (F‑G5): parked and reconnecting receives are cancelled first (they would keep writing),
     * then `core/data`'s cleaner deletes the partial files of every transfer that cannot use them any more, and of the
     * unfinished receives an earlier run of the app left behind (no engine of this process holds them; they can no
     * longer resume).
     */
    suspend fun clearPartials(): PartialsCleared {
        val interrupted =
            trackers.value.filter {
                it.direction == NodeDirection.RECEIVE &&
                    it.state.value.stage.let { s -> s == NodeStage.RECONNECTING || s == NodeStage.WAITING_FOR_PEER }
            }
        for (tracker in interrupted) tracker.transfer?.cancel(CancelReason.USER)
        val held = trackers.value.filter { !it.isFinished }.mapTo(HashSet()) { it.id }
        val leftBehind =
            runCatching { data.transfers.active() }
                .getOrDefault(emptyList())
                .filter { it.direction == TransferDirection.RECEIVE && it.id !in held }
                .map { it.id }
        val before = partialUsage()
        sweeper.cleaner.clearPartials(interrupted.map { it.id } + leftBehind)
        val after = partialUsage()
        return PartialsCleared((before.first - after.first).coerceAtLeast(0), (before.second - after.second).coerceAtLeast(0))
    }

    /**
     * History "Clear" (F‑G2): deletes every finished transfer with its files and manifests (the partial files of
     * received ones first); running transfers stay. Returns how many went.
     */
    suspend fun clearHistory(): Int = data.transfers.clearHistory { id -> sweeper.deletePartials(id) }

    /** Deletes one finished transfer from History (its partial files first); false when it is running or missing. */
    suspend fun deleteFromHistory(transferId: String): Boolean {
        val id = parseId(transferId) ?: return false
        return data.transfers.delete(id) { sweeper.deletePartials(it) }
    }

    /** Partial files (not resume plans) under the partial root and their total size. */
    private suspend fun partialUsage(): Pair<Int, Long> =
        withContext(config.io) {
            val root = config.directories.partials
            if (!Files.isDirectory(root)) return@withContext 0 to 0L
            var files = 0
            var bytes = 0L
            Files.walk(root).use { paths ->
                for (p in paths) {
                    if (Files.isRegularFile(p) && p.fileName.toString().endsWith(".part")) {
                        files++
                        bytes += runCatching { Files.size(p) }.getOrDefault(0L)
                    }
                }
            }
            files to bytes
        }

    // =====================================================================================================
    // Browser receive page (F-H4, architecture §10.3)
    // =====================================================================================================

    /**
     * Serves [files] to a browser on the LAN (a computer without the app): the receive server binds the LAN address,
     * each new browser is asked about through [browserApprover] (N15), and the page stops 60 s after the last download.
     */
    fun startBrowserShare(files: List<SendFile>) {
        checkRunning()
        if (files.isEmpty()) return
        browserJob?.cancel()
        browserState.value = BrowserShareStatus.Starting
        browserJob =
            scope.launch {
                var server: ReceiveServer? = null
                try {
                    val offer = ReceiveOffer(nickname.value, files.map { PathSharedFile(it.path, it.name) })
                    val session =
                        ReceiveSession(
                            ReceiveToken.generate(),
                            offer,
                            { request -> browserApprover.approve(request) },
                            monotonicClock = config.monotonicClock,
                            wallClock = config.wallClock,
                        )
                    val running = ReceiveServer(session, lanAddress.value, 0)
                    server = running
                    running.start()
                    browserState.value = BrowserShareStatus.Ready(running.ipUrl(), files.size)
                    running.awaitStopped()
                    browserState.value = BrowserShareStatus.Idle
                } catch (e: CancellationException) {
                    browserState.value = BrowserShareStatus.Idle
                    throw e
                } catch (e: Exception) {
                    report("the browser receive page could not start", e)
                    browserState.value = BrowserShareStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
                } finally {
                    withContext(NonCancellable) { server?.let { runCatching { it.stop() } } }
                }
            }
    }

    /** Stops offering the page; downloads in progress end with it. */
    fun stopBrowserShare() {
        browserJob?.cancel()
        browserJob = null
        browserState.value = BrowserShareStatus.Idle
    }

    // =====================================================================================================
    // Discovery (F-A1, F-A3, F-A5; N4)
    // =====================================================================================================

    private suspend fun announceLoop() {
        combine(effectiveVisibilityState, nickname, ownSecrets, binding.filterNotNull()) { v, n, s, b ->
            Announcement(v, n, s.firstOrNull(), b.address.hostAddress, b.listener.port)
        }.distinctUntilChanged()
            .collectLatest { a ->
                val kAdv = a.secret ?: return@collectLatest
                if (a.visibility == Visibility.HIDDEN) {
                    runCatching { config.lan.withdraw() }
                    for (radio in services.beaconRadios) runCatching { radio.stopAdvertising() }
                    return@collectLatest
                }
                var announcedEpoch = -1L
                while (true) {
                    val now = config.wallClock.nowMillis()
                    val epoch = EphemeralIds.epochAt(now)
                    if (epoch != announcedEpoch) {
                        announce(a, kAdv, now)
                        announcedEpoch = epoch
                    }
                    delay((EphemeralIds.millisUntilNextEpoch(now) + EPOCH_SLACK_MILLIS).coerceAtMost(ANNOUNCE_RECHECK_MILLIS))
                }
            }
    }

    private suspend fun announce(
        a: Announcement,
        kAdv: ByteArray,
        now: Long,
    ) {
        val state = LocalBeaconState(a.visibility, config.platform, capabilities, NetworkHint.NONE, a.nickname)
        try {
            val record = MdnsRecord.create(crypto, kAdv, state, a.port, now)
            config.lan.announce(record.toLanService(a.host))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("announcing on the LAN failed", e)
        }
        for (radio in services.beaconRadios) {
            try {
                radio.startAdvertising(BeaconAdvertisement.create(crypto, kAdv, state, services.beaconCarrier, now), RadioMode.FOREGROUND)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Bluetooth advertising failed", e)
            }
        }
    }

    private data class Announcement(
        val visibility: Visibility,
        val nickname: String,
        val secret: ByteArray?,
        val host: String,
        val port: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Announcement && visibility == other.visibility && nickname == other.nickname && host == other.host &&
                port == other.port && (secret?.contentEquals(other.secret) ?: (other.secret == null))

        override fun hashCode(): Int =
            ((visibility.hashCode() * 31 + nickname.hashCode()) * 31 + (secret?.contentHashCode() ?: 0)) * 31 + host.hashCode() + port
    }

    // =====================================================================================================
    // Plumbing
    // =====================================================================================================

    private fun sessionConfig(): SessionConfig {
        val local = LocalPeerInfo.forDevice(crypto, capabilities.bits, nickname.value, config.platform.code)
        val visibility = effectiveVisibilityState.value
        return SessionConfig(
            crypto = crypto,
            identity = identity,
            local = local,
            guard = guard,
            trustedPeers = TrustedPeerLookup { key -> lookup.recognitionSecretFor(key) },
            requireTrustedProof = visibility == Visibility.TRUSTED_ONLY || visibility == Visibility.HIDDEN,
            clock = config.handshakeClock,
            timeoutMillis = tuning.handshakeTimeoutMillis,
            trustShare = ::trustShareFor,
        )
    }

    private fun engineConfig(
        session: SessionConfig,
        store: FileStore,
        resume: ResumeStore = resumeStore,
        onPersist: (com.constrivo.drop.core.protocol.TransferState) -> Unit,
    ): EngineConfig =
        EngineConfig(
            session = session,
            fileStore = store,
            resumeStore = resume,
            clock = config.transferClock,
            io = config.io,
            power = power,
            lingerMillis = tuning.lingerMillis,
            onPersist = onPersist,
            onTrustShare = { key, share -> onTrustShare(key, share) },
        )

    private fun receiveEngineConfig(
        session: SessionConfig,
        intake: OfferIntake,
    ): EngineConfig = engineConfig(session, intake.fileStore, intake.resumeStoreView) { state -> intake.tracker?.onPersist(state) }

    private fun plan(
        result: HandshakeResult,
        role: TransferRole,
    ): LadderPlan {
        val local = LinkFacts(capabilities, config.platform, NetworkHint.NONE, hostingAllowed = false)
        val peer = LinkFacts(Capabilities(result.peerCaps), platformOf(result.peerPlatform))
        val radio = RadioState(wifiEnabled = null, bluetoothEnabled = services.bluetoothAvailable)
        // Connected over the LAN already: the peer is on this network whatever mDNS shows (N6).
        return LadderPlanner.plan(LadderInput(local, peer, role, radio, lanReachable = true, peerName = result.peerNickname))
    }

    /** A session with the peer at the first of [endpoints] that proves [expected] (or any identity when null). */
    private class Dialed(
        val session: SecureSession,
        val endpoint: Endpoint,
        val remote: InetAddress?,
    )

    /**
     * Tries [endpoints] in order behind the identity check ([EndpointDialer]); the sockets leave from the LAN address,
     * so a session is on the network the node is bound to and its peer's address is the one its LAN links may join.
     */
    private suspend fun dial(
        endpoints: List<Endpoint>,
        expected: ExpectedPeer?,
    ): Dialed {
        val sessionConfig = sessionConfig()
        val local = lanAddress.value
        val factory = TcpSocketFactory { SocketChannel.open().also { it.bind(InetSocketAddress(local, 0)) } }
        // EndpointDialer tries one candidate at a time: the last one connected is the one whose handshake succeeded.
        var used: Endpoint? = null
        var remote: InetAddress? = null
        val candidates =
            endpoints.map { endpoint ->
                DialCandidate(endpoint.toString()) {
                    TcpDataChannel
                        .connect(
                            endpoint.host,
                            endpoint.port,
                            LinkKind.LAN,
                            tuning.dialTimeoutMillis.toInt(),
                            lowLatency = true,
                            factory = factory,
                            io = config.io,
                        ).also {
                            used = endpoint
                            remote = it.remoteAddress?.address
                        }
                }
            }
        val session =
            EndpointDialer.dial(candidates, tuning.dialTimeoutMillis) { channel ->
                SessionHandshake.initiate(channel, sessionConfig, expected)
            }
        return Dialed(session, checkNotNull(used), remote)
    }

    /** Records the peer met through a verified handshake (F‑B4); false when its id collides with another key. */
    private suspend fun recordPeer(
        identityKey: ByteArray,
        nickname: String,
        platform: DevicePlatform,
    ): Boolean =
        try {
            data.devices.recordPeer(identityKey, nickname, platform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not record the peer ${DeviceIds.of(crypto, identityKey)}", e)
            false
        }

    private fun newTracker(
        id: TransferId,
        direction: NodeDirection,
        initial: NodeTransfer,
    ): TransferTracker = TransferTracker(id, direction, initial, data, scope, config.monotonicClock, tuning.progressPersistMillis, ::report)

    private fun addTracker(tracker: TransferTracker) {
        trackers.update { it + tracker }
    }

    private suspend fun finishTracker(
        tracker: TransferTracker,
        final: TransferProgress,
        folder: Path?,
    ) {
        tracker.finish(final, tracker.transfer?.state?.value, folder)
        val done = final.files.count { it.status == FileStatus.DONE }
        eventFlow.tryEmit(
            NodeEvent.TransferFinished(
                tracker.state.value,
                folder.takeIf { tracker.direction == NodeDirection.RECEIVE },
                if (tracker.direction == NodeDirection.RECEIVE) done else 0,
            ),
        )
        tracker.bridge?.runner?.value?.let { runner -> withTimeoutOrNull(LADDER_CLOSE_WAIT_MILLIS) { runner.awaitClosed() } }
        scheduleRemoval(tracker)
    }

    /**
     * Drops a finished tracker from [transfers] after [NodeTuning.finishedRetentionMillis], and not while its sender's
     * code waits for an answer (at most [NodeTuning.pairingRetentionMillis]; the code then goes unanswered).
     */
    private fun scheduleRemoval(tracker: TransferTracker) {
        scope.launch {
            delay(tuning.finishedRetentionMillis)
            if (tracker.pairingCode != null) {
                withTimeoutOrNull(tuning.pairingRetentionMillis) { tracker.pairingState.first { it == null } }
                tracker.setPairingCode(null)
            }
            trackers.update { list -> list - tracker }
        }
    }

    private fun failEarly(
        tracker: TransferTracker,
        message: String,
        error: Throwable? = null,
    ) {
        report("sending ${tracker.hex} failed: $message", error)
        tracker.setPairingCode(null)
        tracker.update { it.copy(stage = NodeStage.FAILED, failure = message) }
        tracker.abandon()
        scheduleRemoval(tracker)
    }

    /** The folder the received files went to: the per-drop subfolder above 20 files, else the Received folder. */
    private fun folderOf(final: TransferProgress): Path? =
        final.files
            .firstNotNullOfOrNull { f -> f.savedUri?.let(MarkingFileStore::pathOf) }
            ?.parent

    private fun sourcePathOf(uri: String): Path? = MarkingFileStore.pathOf(uri) ?: runCatching { Paths.get(uri) }.getOrNull()

    private fun nicknameOf(setting: String?): String =
        Nicknames.normalize(setting ?: config.defaultNickname)?.text
            ?: Nicknames.normalize(config.defaultNickname)?.text
            ?: DEFAULT_NICKNAME

    private fun platformOf(code: Int): DevicePlatform =
        if (code in
            0..DevicePlatform.MAX_CODE
        ) {
            DevicePlatform.fromCode(code)
        } else {
            DevicePlatform.UNKNOWN
        }

    private fun parseId(hex: String): TransferId? = runCatching { TransferId.fromHex(hex) }.getOrNull()

    private fun now(): Long = config.monotonicClock.elapsedMillis()

    private fun describe(e: Throwable): String = e.message ?: e::class.simpleName.orEmpty()

    private suspend fun closeQuietly(session: SecureSession) {
        runCatching { session.close() }
    }

    private fun checkRunning() {
        check(started.get() && !stopping.get()) { "the node is not running" }
    }

    private fun report(
        message: String,
        error: Throwable?,
    ) {
        eventFlow.tryEmit(NodeEvent.Problem(message, error))
    }

    // Tests: the engine's counters and Offer of the attempt a transfer runs now (or ran last).
    internal fun statsOf(transferId: String): TransferStats? = trackers.value.firstOrNull { it.hex == transferId }?.transfer?.stats

    internal fun offerOf(transferId: String): com.constrivo.drop.core.protocol.Offer? =
        trackers.value.firstOrNull { it.hex == transferId }?.transfer?.offer

    internal val unauthenticatedConnections: Int get() = gate.size

    /** An Offer on the card, with what the node needs to answer it. */
    private class PendingOffer(
        val incoming: IncomingTransfer,
        val tracker: TransferTracker,
        val result: HandshakeResult,
        val deviceId: String,
        val intent: LinkIntent?,
        val offer: NodeOffer,
    ) {
        val answered = AtomicBoolean(false)

        @Volatile
        var trustJob: Job? = null
    }

    private companion object {
        const val EVENT_BUFFER = 256
        const val RECEIVED_BUFFER = 1024
        const val FILE_BATCH = 1000
        const val ACCEPT_RETRY_MILLIS = 250L
        const val STOP_WAIT_MILLIS = 3_000L
        const val STOP_BUDGET_MILLIS = 6_000L
        const val ATTEMPT_CLOSE_WAIT_MILLIS = 1_000L
        const val LADDER_CLOSE_WAIT_MILLIS = 10_000L
        const val REFUSAL_WAIT_MILLIS = 5_000L
        const val EPOCH_SLACK_MILLIS = 50L
        const val ANNOUNCE_RECHECK_MILLIS = 60_000L
        const val DEFAULT_NICKNAME = "Computer"
        const val CONTROL_IO_PARALLELISM = 64
        const val BROWSE_RETRY_MILLIS = 1_000L
        const val BROWSE_RETRY_MAX_MILLIS = 30_000L
        const val RECONNECT_BACKOFF_MILLIS = 500L
        const val RECONNECT_BACKOFF_MAX_MILLIS = 8_000L
        const val PARKED_RETRY_MILLIS = 60_000L
        const val MAX_REMEMBERED_ENDPOINTS = 4
        const val RECEIVER_SYNC_DELAY_MILLIS = 3_000L
        const val TRUST_SYNC_LOOP_SPACING_MILLIS = 50L
    }
}

/** What "Clear partial files" removed (F‑G5). */
data class PartialsCleared(
    val filesRemoved: Int,
    val bytesFreed: Long,
)

/**
 * A code for "Show my code" (F‑B5, F‑H4): the signed QR [payload], the address to type when the QR cannot be scanned
 * ([fallback], `host:port`, or null without a network), and its validity on the wall clock.
 */
data class NodeCode(
    val payload: String,
    val fallback: String?,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
)
