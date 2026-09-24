package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.handshake.ExpectedPeer
import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.crypto.qr.QrPayloadCodec
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import com.constrivo.drop.core.data.DeviceIds
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.DuplicateRecordException
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.NewTransferFile
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.data.SettingsSnapshot
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferFileStatus
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
import com.constrivo.drop.core.ladder.LinkFacts
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.RadioState
import com.constrivo.drop.core.ladder.engine.LadderTransferBridge
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.core.transfer.engine.FileStatus
import com.constrivo.drop.core.transfer.engine.IncomingTransfer
import com.constrivo.drop.core.transfer.engine.PrimaryLinkSource
import com.constrivo.drop.core.transfer.engine.ReceiveOptions
import com.constrivo.drop.core.transfer.engine.SendOptions
import com.constrivo.drop.core.transfer.engine.TransferEngine
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.core.transfer.receive.FileTypes
import com.constrivo.drop.core.transfer.session.DialCandidate
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.core.transfer.session.EndpointDialer
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionException
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import com.constrivo.drop.platform.desktop.DesktopPowerPolicy
import com.constrivo.drop.platform.desktop.data.DataResumeStore
import com.constrivo.drop.platform.desktop.data.DesktopDatabase
import com.constrivo.drop.platform.desktop.data.FileResumePlanStore
import com.constrivo.drop.platform.desktop.data.PartialsSweeper
import com.constrivo.drop.platform.desktop.files.MarkingFileStore
import com.constrivo.drop.platform.desktop.files.PublishedFileEvent
import com.constrivo.drop.platform.desktop.files.SendFile
import com.constrivo.drop.platform.desktop.lan.LanLinkProvider
import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.PathSharedFile
import com.constrivo.drop.web.ReceiveOffer
import com.constrivo.drop.web.ReceiveServer
import com.constrivo.drop.web.ReceiveSession
import com.constrivo.drop.web.ReceiveToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.constrivo.drop.core.protocol.AdvertisingSecret as WireAdvertisingSecret

/**
 * The desktop composition root (WP10a; architecture §3, §10.2): one per process, it wires
 *
 * - **identity and secrets**: the software Ed25519 identity and this device's advertising secret `k_adv` (S3) from the
 *   [DesktopNodeConfig.secrets] store, one [HandshakeGuard] for every responder (§6 note);
 * - **the database** (`core/data`): History rows for every transfer, trust and auto-accept, settings, and the
 *   receiver's resume state through [DataResumeStore], with the 24 h sweep of partial files ([PartialsSweeper]);
 * - **discovery**: [NearbyDevices] over the LAN (mDNS, [DesktopNodeConfig.lan]) and whatever BLE radios the OS module
 *   brings (none in WP10a), with this device's record re-announced at every epoch (N4) and on every visibility or
 *   nickname change (F‑A5), and withdrawn while Hidden;
 * - **transfers**: a control listener on the LAN address; senders dial the peer's LAN endpoints behind the handshake
 *   identity check ([EndpointDialer]); the engine runs over that primary LAN connection and the ladder's LAN rung adds
 *   the parallel streams ([LanLinkProvider], "no Bluetooth": the desktop never plans a Bluetooth or Wi‑Fi Direct rung
 *   while it has no radios); reconnects run the handshake again (N3), routed to the waiting receiver by the identity
 *   its `Hello` claims;
 * - **trust**: the SAS on both screens for a first pairing (F‑B3), stored on each side when its user confirms it, the
 *   advertising secrets exchanged in a short follow-up session so each side's radar recognises the other (S3), a
 *   static QR code with the identity (F‑B6), "Forget" with a `k_adv` rotation (F‑G3), and auto-accept for trusted
 *   devices that have it on (F‑D2), decided on the verified identity, never on a beacon (F‑B4);
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
    private val scope =
        CoroutineScope(SupervisorJob() + config.io + CoroutineExceptionHandler { _, e -> report("unexpected failure", e) })
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    private val guard = HandshakeGuard(config.handshakeClock)
    private val power = DesktopPowerPolicy(services.keepAwake)
    private val lanLinks = LanLinkProvider(config.lanAddress, config.io)
    private val nearby = NearbyDevices(crypto, config.wallClock, config.monotonicClock)
    private val waiters = ReconnectWaiters()

    private lateinit var dataRef: DropData
    private lateinit var identity: IdentityKey
    private lateinit var advertising: AdvertisingSecretStore
    private lateinit var listener: TcpListener
    private lateinit var resumeStore: DataResumeStore
    private lateinit var sweeper: PartialsSweeper
    private lateinit var senderStore: DirectoryFileStore
    private lateinit var selfId: String

    private val ownSecrets = MutableStateFlow<List<ByteArray>>(emptyList())

    @Volatile
    private var ownGeneration = 0
    private val trustedKeys = MutableStateFlow<List<TrustedDeviceKeys>>(emptyList())

    @Volatile
    private var lookup: TrustedPeerLookup = TrustedPeerLookup.NONE

    /** Peers' `TrustShare`s that arrived before this side's user confirmed the pairing (S3), by device id. */
    private val pendingShares = ConcurrentHashMap<String, TrustShare>()

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
    val controlPort: Int get() = listener.port

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
     * the accept loop and the 24 h sweep.
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
            listener = TcpListener(InetSocketAddress(config.lanAddress, config.controlPort), LinkKind.LAN, config.io)
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
        val lanEvents = config.lan.browse().catch { report("mDNS browsing stopped", it) }
        jobs += nearby.launchIn(scope, sightings, lanEvents, trust)
        jobs += scope.launch { announceLoop() }
        jobs += scope.launch { acceptLoop() }
        jobs += scope.launch { watchRediscovery() }
        jobs += sweeper.launchIn(scope)
    }

    /**
     * Stops everything: offers are declined, the ladders tear their links down (F‑E11) and running transfers are left
     * to resume later (their partial files and resume records stay, T‑07), the record is withdrawn and the database is
     * closed if the node opened it. Idempotent.
     */
    suspend fun stop() {
        if (!started.get() || !stopping.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            stopBrowserShare()
            browserJob?.let { withTimeoutOrNull(STOP_WAIT_MILLIS) { it.join() } }
            for (offer in pending.values) runCatching { offer.incoming.decline(DeclineReason.BUSY) }
            runCatching { listener.close() }
            val ladders = trackers.value.mapNotNull { it.bridge?.runner?.value }
            withTimeoutOrNull(STOP_WAIT_MILLIS) { for (runner in ladders) runCatching { runner.closeAndAwait() } }
            withTimeoutOrNull(STOP_WAIT_MILLIS) { runCatching { config.lan.withdraw() } }
            for (radio in services.beaconRadios) withTimeoutOrNull(STOP_WAIT_MILLIS) { runCatching { radio.stopAdvertising() } }
            trackers.value.forEach { it.abandon() }
            for (job in jobs) job.cancel()
            scope.coroutineContext[Job]?.cancelAndJoin()
            if (config.database == null) runCatching { data.close() }
        }
    }

    /** [stop], blocking. */
    override fun close() = runBlocking { stop() }

    // =====================================================================================================
    // Sending
    // =====================================================================================================

    /**
     * Sends [files] to the device on the radar under [deviceKey] (F‑C1, F‑C2, F‑C6): dials its LAN endpoints behind
     * the handshake identity check, shows the SAS on this side for a first pairing, offers the files and runs the
     * transfer. Returns the transfer id at once (the transfer shows as [NodeStage.CONNECTING]), or null when [files] is
     * empty or the device is no longer on the radar.
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
            TransferTracker(
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
                data,
                scope,
                config.monotonicClock,
                tuning.progressPersistMillis,
                ::report,
            )
        addTracker(tracker)
        scope.launch { runSend(tracker, device, files) }
        return id.toHex()
    }

    /** Sends the same files to the same device again (History "Send again", F‑G2); null when that is impossible now. */
    suspend fun resend(transferId: String): String? {
        val id = parseId(transferId) ?: return null
        val record = data.transfers.get(id) ?: return null
        if (record.direction != TransferDirection.SEND) return null
        val files =
            data.transferFiles.files(id).mapNotNull { f ->
                val path = f.savedUri?.let(MarkingFileStore::pathOf) ?: f.savedUri?.let { runCatching { Paths.get(it) }.getOrNull() }
                path?.takeIf { Files.isRegularFile(it) }?.let { SendFile(it, f.name, Files.size(it)) }
            }
        if (files.isEmpty()) return null
        val key = devices.value.firstOrNull { it.trustedDeviceId == record.peerDeviceId }?.key ?: return null
        return send(key, files)
    }

    private suspend fun runSend(
        tracker: TransferTracker,
        device: NearbyDevice,
        files: List<SendFile>,
    ) {
        val endpoints = device.lanEndpoints.map { Endpoint(it.host, it.port) }
        if (endpoints.isEmpty()) return failEarly(tracker, "the device has no LAN endpoint")
        try {
            val expected =
                device.trustedDeviceId?.let { id ->
                    runCatching { data.devices.trustedKeys(id) }.getOrNull()?.let { keys ->
                        keys.device.identityKey()?.let { ExpectedPeer(it, keys.recognitionSecret()) }
                    }
                }
            val sessionConfig = sessionConfig()
            val session =
                EndpointDialer.dial(candidates(endpoints), tuning.dialTimeoutMillis) { channel ->
                    SessionHandshake.initiate(channel, sessionConfig, expected)
                }
            val result = session.handshake
            val peerKey = result.peerIdentityKey
            val peerId = DeviceIds.of(crypto, peerKey)
            val peerPlatform = platformOf(result.peerPlatform)
            recordPeer(peerKey, result.peerNickname, peerPlatform)
            val trusted = lookup.recognitionSecretFor(peerKey) != null
            tracker.peerIdentity = peerKey
            tracker.session = session
            tracker.endpoints = endpoints
            tracker.update {
                it.copy(peerDeviceId = peerId, peerName = result.peerNickname.ifEmpty { it.peerName }, peerPlatform = peerPlatform)
            }
            if (!trusted) tracker.setPairingCode(result.sas)
            val sources: List<SourceFile> = files.map { senderStore.source(it.path, it.name) }
            val plan = plan(result, TransferRole.SENDER)
            val options =
                SendOptions(
                    transferId = tracker.id,
                    linkOptions = LadderNegotiation.offerOptions(plan, p2pCredentials = null, lanAddress = config.lanAddress.hostAddress),
                    bundleSmall = settingsState.value?.bundleSmallFiles ?: true,
                    reconnect = PrimaryLinkSource { reconnectChannel(tracker) },
                )
            val engine = TransferEngine(engineConfig(sessionConfig, senderStore, tracker), scope)
            val transfer = engine.send(session, sources, options)
            val bridge =
                LadderTransferBridge(transfer, scope) { ladderSession, base ->
                    val agreement = LadderNegotiation.adopt(plan, null, transfer.accept.value?.link) { P2pCredentials.random(crypto) }
                    LadderRunner(
                        agreement.plan,
                        listOf(lanLinks),
                        ladderSession,
                        scope,
                        config.monotonicClock,
                        agreement.config(base),
                        tuning.lifecycle,
                    )
                }
            transfer.linkListener = bridge
            tracker.attach(transfer, bridge, capabilities, Capabilities(result.peerCaps))
            tracker.holdAwake(power.keepAwake("sending ${tracker.hex}"))
            createSendHistory(tracker, peerId, transfer.offer.mimeHistogram, files)
            val final = transfer.await()
            finishTracker(tracker, final, folder = null)
            if (tracker.pairedHere && final.phase == TransferPhase.DONE) scope.launch { syncTrust(peerKey, endpoints) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeException) {
            failEarly(tracker, "handshake refused: ${e.reason}")
        } catch (e: Exception) {
            failEarly(tracker, e.message ?: e::class.simpleName.orEmpty(), e)
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

    /** The first connection a sender's reconnect tries (N3): the endpoints used before, then the radar's current ones. */
    private suspend fun reconnectChannel(tracker: TransferTracker): TcpDataChannel {
        val peerId = tracker.state.value.peerDeviceId
        val fresh =
            devices.value
                .filter { (peerId != null && it.trustedDeviceId == peerId) || it.key == tracker.state.value.peerKey }
                .flatMap { d -> d.lanEndpoints.map { Endpoint(it.host, it.port) } }
        var last: Exception? = null
        for (endpoint in (tracker.endpoints + fresh).distinct()) {
            try {
                return TcpDataChannel.connect(
                    endpoint.host,
                    endpoint.port,
                    LinkKind.LAN,
                    tuning.dialTimeoutMillis.toInt(),
                    lowLatency = true,
                    io = config.io,
                )
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("the peer is not on the network")
    }

    /** "Yes, it matches" on the sender's code sheet (F‑B3): the peer becomes trusted on this device. */
    fun confirmPairing(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId && it.direction == NodeDirection.SEND } ?: return
        val session = tracker.session ?: return
        if (tracker.pairingCode == null) return
        tracker.setPairingCode(null)
        tracker.pairedHere = true
        scope.launch { storeTrust(session.handshake) }
    }

    /** Cancels a transfer (design §4.2 ×; §7.8 `Cancel{user}`); the receiver clears its partial files. */
    fun cancel(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId } ?: return
        val transfer = tracker.transfer
        if (transfer != null) {
            transfer.cancel(CancelReason.USER)
        } else {
            tracker.update { it.copy(stage = NodeStage.CANCELLED) }
        }
    }

    // =====================================================================================================
    // Receiving
    // =====================================================================================================

    private suspend fun acceptLoop() {
        while (currentCoroutineContext().isActive) {
            val channel =
                try {
                    listener.accept(lowLatency = true)
                } catch (e: IOException) {
                    if (stopping.get()) return
                    report("the control listener failed", e)
                    delay(ACCEPT_RETRY_MILLIS)
                    continue
                }
            scope.launch { handleInbound(channel) }
        }
    }

    private suspend fun handleInbound(raw: TcpDataChannel) {
        val hello =
            try {
                withTimeoutOrNull(HELLO_WAIT_MILLIS) { HelloPeek.read(raw) }
            } catch (e: CancellationException) {
                raw.close()
                throw e
            } catch (e: Exception) {
                null
            }
        if (hello == null) {
            raw.close()
            return
        }
        val channel = ReplayChannel(raw, hello.frame)
        if (waiters.offer(hello.claimedIdentity, channel)) return
        val sessionConfig = sessionConfig()
        val session =
            try {
                SessionHandshake.respond(channel, sessionConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HandshakeException) {
                report("an inbound handshake was refused: ${e.reason}", null)
                return
            } catch (e: Exception) {
                return
            }
        onInboundSession(session, sessionConfig)
    }

    private suspend fun onInboundSession(
        session: SecureSession,
        sessionConfig: SessionConfig,
    ) {
        val result = session.handshake
        val peerKey = result.peerIdentityKey
        val peerId = DeviceIds.of(crypto, peerKey)
        val peerPlatform = platformOf(result.peerPlatform)
        if (!recordPeer(peerKey, result.peerNickname, peerPlatform)) {
            session.close()
            return
        }
        val trackerRef = AtomicReference<TransferTracker?>()
        val store = receiveStore()
        val engine = TransferEngine(engineConfig(sessionConfig, store, null, trackerRef), scope)
        val incoming =
            try {
                withTimeoutOrNull(tuning.offerWaitMillis) {
                    engine.receive(session, ReceiveOptions(reconnect = waiters.sourceFor(peerKey)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A session without an Offer: the trust exchange after a pairing (S3), or a peer that went away.
                null
            }
        if (incoming == null) {
            runCatching { session.close() }
            return
        }
        onOffer(incoming, session, result, peerId, peerPlatform, trackerRef)
    }

    private suspend fun onOffer(
        incoming: IncomingTransfer,
        session: SecureSession,
        result: HandshakeResult,
        peerId: String,
        peerPlatform: DevicePlatform,
        trackerRef: AtomicReference<TransferTracker?>,
    ) {
        val offer = incoming.offer
        val id = offer.transferId
        val device = runCatching { data.devices.find(peerId) }.getOrNull()
        val trusted = device?.isTrusted == true && lookup.recognitionSecretFor(result.peerIdentityKey) != null
        val name = device?.displayName?.ifEmpty { null } ?: result.peerNickname
        val radarKey = devices.value.firstOrNull { it.trustedDeviceId == peerId }?.key
        val tracker =
            TransferTracker(
                id,
                NodeDirection.RECEIVE,
                NodeTransfer(
                    id = id.toHex(),
                    direction = NodeDirection.RECEIVE,
                    peerKey = radarKey,
                    peerDeviceId = peerId,
                    peerName = name,
                    peerPlatform = peerPlatform,
                    stage = NodeStage.AWAITING_ACCEPT,
                    fileCount = offer.fileCount,
                    bytesTotal = offer.totalBytes,
                    bytesDone = 0,
                    mimeHistogram = offer.mimeHistogram,
                ),
                data,
                scope,
                config.monotonicClock,
                tuning.progressPersistMillis,
                ::report,
            )
        tracker.peerIdentity = result.peerIdentityKey
        tracker.session = session
        trackerRef.set(tracker)
        addTracker(tracker)
        createReceiveHistory(tracker, peerId, offer.totalBytes, offer.fileCount, offer.mimeHistogram)
        val plan = plan(result, TransferRole.RECEIVER)
        val decision = LadderNegotiation.accept(plan, offer.linkOptions) { P2pCredentials.random(crypto) }
        val bridge =
            LadderTransferBridge(incoming.transfer, scope) { ladderSession, base ->
                val agreement = decision.agreement
                LadderRunner(
                    agreement.plan,
                    listOf(lanLinks),
                    ladderSession,
                    scope,
                    config.monotonicClock,
                    agreement.config(base),
                    tuning.lifecycle,
                )
            }
        incoming.transfer.linkListener = bridge
        tracker.attach(incoming.transfer, bridge, capabilities, Capabilities(result.peerCaps))
        val waiting =
            PendingOffer(
                incoming = incoming,
                tracker = tracker,
                result = result,
                deviceId = peerId,
                intent = decision.intent,
                offer =
                    NodeOffer(
                        id = tracker.hex,
                        senderDeviceId = peerId,
                        senderKey = radarKey ?: "d:$peerId",
                        senderName = name,
                        senderPlatform = peerPlatform,
                        trusted = trusted,
                        sas = if (trusted) null else result.sas,
                        fileCount = offer.fileCount,
                        totalBytes = offer.totalBytes,
                        mimeHistogram = offer.mimeHistogram,
                        previewNames = offer.previewNames,
                        previews = offer.previews,
                        arrivedAtElapsedMillis = config.monotonicClock.elapsedMillis(),
                    ),
            )
        // An Offer of an unsupported protocol version was declined `incompatible` by the engine already.
        if (incoming.isCompatible) {
            when {
                trusted && device.autoAccept -> {
                    scope.launch { acceptPending(waiting, alwaysAccept = false) }
                }

                pending.size >= tuning.maxPendingOffers -> {
                    incoming.decline(DeclineReason.BUSY)
                }

                else -> {
                    pending[tracker.hex] = waiting
                    publishOffers()
                }
            }
        }
        // The card goes when the Offer is answered here, times out (§7.8) or is cancelled by the sender.
        val cardWatch =
            scope.launch {
                incoming.transfer.state.first { it.phase != TransferPhase.OFFERED }
                withdrawOffer(tracker.hex)
            }
        val final = incoming.transfer.await()
        cardWatch.cancel()
        withdrawOffer(tracker.hex)
        finishTracker(tracker, final, folderOf(final))
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
        withdrawOffer(waiting.tracker.hex)
        waiting.trustJob?.join()
        if (alwaysAccept) {
            val set = runCatching { data.devices.setAutoAccept(waiting.deviceId, true) }.getOrDefault(false)
            if (!set) report("auto-accept needs a trusted device; ${waiting.deviceId} is not", null)
        }
        waiting.tracker.holdAwake(power.keepAwake("receiving ${waiting.tracker.hex}"))
        try {
            waiting.incoming.accept(link = waiting.intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("accepting ${waiting.tracker.hex} failed", e)
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
        withdrawOffer(offerId)
        waiting.incoming.decline(reason)
    }

    /** "Yes, it matches" on the incoming card (F‑B3): the sender becomes trusted on this device. */
    fun confirmCode(offerId: String) {
        val waiting = pending[offerId] ?: return
        if (waiting.offer.trusted || waiting.offer.sas == null || waiting.trustJob != null) return
        waiting.trustJob = scope.launch { storeTrust(waiting.result) }
    }

    private fun withdrawOffer(id: String) {
        if (pending.remove(id) != null) publishOffers()
    }

    private fun publishOffers() {
        offerState.value = pending.values.map { it.offer }.sortedBy { it.arrivedAtElapsedMillis }
    }

    /** A fresh file store per received transfer: the Received folder may have changed in Settings meanwhile. */
    private fun receiveStore(): MarkingFileStore {
        val destination = receivedFolder
        val directory = DirectoryFileStore(config.directories.partials, destination, io = config.io)
        return MarkingFileStore(
            directory,
            services.downloadMarker,
            onMarkError = { path, e -> report("could not mark $path as downloaded", e) },
            onPublished = ::onPublished,
        )
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
    // Trust (F-B3, F-B4, F-B5, F-B6, F-G3; S3)
    // =====================================================================================================

    private suspend fun storeTrust(result: HandshakeResult) {
        val peerId = DeviceIds.of(crypto, result.peerIdentityKey)
        val share = pendingShares.remove(peerId)
        try {
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

    /**
     * After a pairing, both devices trust each other but neither has the other's advertising secret yet (S3): the
     * sender opens a short session to the receiver whose handshake carries a trusted proof, both `TrustShare`s cross
     * inside it, and it closes. The receiver's engine takes the sender's share while it waits for an `Offer`.
     */
    private suspend fun syncTrust(
        peerKey: ByteArray,
        endpoints: List<Endpoint>,
    ) {
        val peerId = DeviceIds.of(crypto, peerKey)
        try {
            val keys = data.devices.trustedKeys(peerId) ?: return
            val expected = ExpectedPeer(peerKey, keys.recognitionSecret())
            val session =
                EndpointDialer.dial(candidates(endpoints), tuning.dialTimeoutMillis) { channel ->
                    SessionHandshake.initiate(channel, sessionConfig(), expected)
                }
            try {
                withTimeoutOrNull(tuning.trustSyncWaitMillis) {
                    while (true) {
                        val header = session.primary.readHeader() ?: break
                        if (header.type != FrameType.CONTROL) {
                            session.primary.skipPayload(header)
                            continue
                        }
                        val message = session.primary.readControl(header)
                        if (message is TrustShare) {
                            onTrustShare(peerKey, message).join()
                            break
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { runCatching { session.close() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("sharing the advertising secret with $peerId failed; it goes out at the next session", e)
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

    /** The `TrustShare` this device sends to a peer it trusts, at the start of every session with it (S3). */
    private fun trustShareFor(peerIdentityKey: ByteArray): TrustShare? {
        if (lookup.recognitionSecretFor(peerIdentityKey) == null) return null
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

    private suspend fun refreshTrust() {
        val keys = data.devices.trustedKeys { id, e -> report("the secrets of device $id do not open", e) }
        setTrust(keys)
    }

    private fun setTrust(keys: List<TrustedDeviceKeys>) {
        lookup = keys.toTrustedPeerLookup()
        trustedKeys.value = keys
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
     * then `core/data`'s cleaner deletes the partial files of every transfer that cannot use them any more.
     */
    suspend fun clearPartials(): PartialsCleared {
        val released =
            trackers.value.filter {
                it.direction == NodeDirection.RECEIVE &&
                    it.state.value.stage.let { s -> s == NodeStage.RECONNECTING || s == NodeStage.WAITING_FOR_PEER }
            }
        for (tracker in released) tracker.transfer?.cancel(CancelReason.USER)
        val before = partialUsage()
        sweeper.cleaner.clearPartials(released.map { it.id })
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
                    val running = ReceiveServer(session, config.lanAddress, 0)
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
        combine(effectiveVisibilityState, nickname, ownSecrets) { v, n, s -> Announcement(v, n, s.firstOrNull()) }
            .distinctUntilChanged()
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
                        announce(a.visibility, a.nickname, kAdv, now)
                        announcedEpoch = epoch
                    }
                    delay((EphemeralIds.millisUntilNextEpoch(now) + EPOCH_SLACK_MILLIS).coerceAtMost(ANNOUNCE_RECHECK_MILLIS))
                }
            }
    }

    private suspend fun announce(
        visibility: Visibility,
        nick: String,
        kAdv: ByteArray,
        now: Long,
    ) {
        val state = LocalBeaconState(visibility, config.platform, capabilities, NetworkHint.NONE, nick)
        try {
            val record = MdnsRecord.create(crypto, kAdv, state, controlPort, now)
            config.lan.announce(record.toLanService(config.lanAddress.hostAddress))
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
    ) {
        override fun equals(other: Any?): Boolean =
            other is Announcement && visibility == other.visibility && nickname == other.nickname &&
                (secret?.contentEquals(other.secret) ?: (other.secret == null))

        override fun hashCode(): Int = (visibility.hashCode() * 31 + nickname.hashCode()) * 31 + (secret?.contentHashCode() ?: 0)
    }

    /** A parked transfer's peer is back on the radar (S8): reconnect actively. */
    private suspend fun watchRediscovery() {
        var present = emptySet<String>()
        devices.collect { list ->
            val now = list.flatMap { listOfNotNull(it.key, it.trustedDeviceId) }.toSet()
            val appeared = now - present
            present = now
            if (appeared.isEmpty()) return@collect
            for (tracker in trackers.value) {
                val t = tracker.state.value
                if (t.stage != NodeStage.WAITING_FOR_PEER) continue
                if (t.peerKey in appeared || t.peerDeviceId in appeared) tracker.transfer?.peerRediscovered()
            }
        }
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
            trustShare = { result -> trustShareFor(result.peerIdentityKey) },
        )
    }

    private fun engineConfig(
        session: SessionConfig,
        store: FileStore,
        tracker: TransferTracker?,
        trackerRef: AtomicReference<TransferTracker?>? = null,
    ): EngineConfig =
        EngineConfig(
            session = session,
            fileStore = store,
            resumeStore = resumeStore,
            clock = config.transferClock,
            io = config.io,
            power = power,
            lingerMillis = tuning.lingerMillis,
            onPersist = { state -> (tracker ?: trackerRef?.get())?.onPersist(state) },
            onTrustShare = { key, share -> onTrustShare(key, share) },
        )

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

    private fun candidates(endpoints: List<Endpoint>): List<DialCandidate> =
        endpoints.map { endpoint ->
            DialCandidate(endpoint.toString()) {
                TcpDataChannel.connect(
                    endpoint.host,
                    endpoint.port,
                    LinkKind.LAN,
                    tuning.dialTimeoutMillis.toInt(),
                    lowLatency = true,
                    io = config.io,
                )
            }
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
                if (tracker.direction ==
                    NodeDirection.RECEIVE
                ) {
                    done
                } else {
                    0
                },
            ),
        )
        tracker.bridge?.runner?.value?.let { runner -> withTimeoutOrNull(LADDER_CLOSE_WAIT_MILLIS) { runner.awaitClosed() } }
        scope.launch {
            delay(tuning.finishedRetentionMillis)
            trackers.update { list -> list - tracker }
        }
    }

    private fun failEarly(
        tracker: TransferTracker,
        message: String,
        error: Throwable? = null,
    ) {
        report("sending ${tracker.hex} failed: $message", error)
        tracker.update { it.copy(stage = NodeStage.FAILED, failure = message, pairingCode = null) }
        tracker.abandon()
        scope.launch {
            delay(tuning.finishedRetentionMillis)
            trackers.update { list -> list - tracker }
        }
    }

    /** The folder the received files went to: the per-drop subfolder above 20 files, else the Received folder. */
    private fun folderOf(final: TransferProgress): Path? =
        final.files
            .firstNotNullOfOrNull { f -> f.savedUri?.let(MarkingFileStore::pathOf) }
            ?.parent

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

    private fun checkRunning() {
        check(started.get() && !stopping.get()) { "the node is not running" }
    }

    private fun report(
        message: String,
        error: Throwable?,
    ) {
        eventFlow.tryEmit(NodeEvent.Problem(message, error))
    }

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
        const val HELLO_WAIT_MILLIS = 10_000L
        const val ACCEPT_RETRY_MILLIS = 250L
        const val STOP_WAIT_MILLIS = 5_000L
        const val LADDER_CLOSE_WAIT_MILLIS = 10_000L
        const val EPOCH_SLACK_MILLIS = 50L
        const val ANNOUNCE_RECHECK_MILLIS = 60_000L
        const val DEFAULT_NICKNAME = "Computer"
    }
}

/** What "Clear partial files" removed (F‑G5). */
data class PartialsCleared(
    val filesRemoved: Int,
    val bytesFreed: Long,
)
