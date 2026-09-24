package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.handshake.ExpectedPeer
import com.constrivo.drop.core.crypto.handshake.HandshakeClock
import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.crypto.qr.QrFailure
import com.constrivo.drop.core.crypto.qr.QrLink
import com.constrivo.drop.core.crypto.qr.QrLinkKind
import com.constrivo.drop.core.crypto.qr.QrPayload
import com.constrivo.drop.core.crypto.qr.QrPayloadCodec
import com.constrivo.drop.core.crypto.qr.QrPayloadException
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import com.constrivo.drop.core.data.Device
import com.constrivo.drop.core.data.DeviceIds
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.DuplicateRecordException
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.NewTransferFile
import com.constrivo.drop.core.data.SettingKey
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.data.SettingsSnapshot
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferRecord
import com.constrivo.drop.core.data.TrustedDeviceKeys
import com.constrivo.drop.core.data.VisibilityPreference
import com.constrivo.drop.core.data.toTrustedPeerLookup
import com.constrivo.drop.core.data.toTrustedPeers
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NearbyDevices
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.TrustState
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.ladder.LadderInput
import com.constrivo.drop.core.ladder.LadderNegotiation
import com.constrivo.drop.core.ladder.LadderPlan
import com.constrivo.drop.core.ladder.LadderPlanner
import com.constrivo.drop.core.ladder.LadderRunner
import com.constrivo.drop.core.ladder.LadderSession
import com.constrivo.drop.core.ladder.LinkAgreement
import com.constrivo.drop.core.ladder.LinkFacts
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.RadioState
import com.constrivo.drop.core.ladder.Side
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.ladder.engine.LadderTransferBridge
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.core.transfer.engine.FileStatus
import com.constrivo.drop.core.transfer.engine.IncomingTransfer
import com.constrivo.drop.core.transfer.engine.ReceiveOptions
import com.constrivo.drop.core.transfer.engine.SendOptions
import com.constrivo.drop.core.transfer.engine.TransferEngine
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.engine.TransferStats
import com.constrivo.drop.core.transfer.receive.FileTypes
import com.constrivo.drop.core.transfer.receive.ResumeRecord
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.DialCandidate
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.core.transfer.session.EndpointDialer
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.platform.android.capability.LocalRadioFacts
import com.constrivo.drop.platform.android.discovery.AndroidDiscoveryController
import com.constrivo.drop.platform.android.discovery.DiscoveryControllerConfig
import com.constrivo.drop.platform.android.permission.RadioPermissionState
import com.constrivo.drop.platform.common.DataResumeStore
import com.constrivo.drop.platform.common.FileResumePlanStore
import com.constrivo.drop.platform.common.PartialsSweeper
import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.ReceiveOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.constrivo.drop.core.protocol.AdvertisingSecret as WireAdvertisingSecret

/**
 * Everything an [AndroidNode] is built from (the transfer service's inputs; plain classes and flows, so the node runs
 * on the JVM in tests).
 *
 * @property openDatabase opens `core/data` (on a device: SQLCipher keyed from the Keystore-wrapped secrets, F-J2);
 *   blocking, called once on [io]. The node closes it on [AndroidNode.stop].
 * @property secrets the Keystore-wrapped `AndroidSecretStorage` (N11): identity seed, `k_adv`, the database key.
 * @property beaconRadio WP7a's `AndroidBeaconRadio`; the node's [AndroidDiscoveryController] owns it.
 * @property bluetooth WP7b's listener and connector.
 * @property stores MediaStore storage (N14) and the content-URI sources.
 * @property radioFacts `AndroidCapabilityDetector.facts`: capability bits, network hint, radio power.
 * @property radioPermissions the radio permissions, re-read by the service whenever the app returns (§11).
 * @property wifiProviders the ladder's rungs on this phone (WP7c Wi-Fi Direct, WP7d hotspot and LAN), taken as they
 *   come; with none, transfers stay on Bluetooth and no link is offered.
 * @property lanDialer dials a LAN endpoint (a desktop from mDNS or a scanned code, F-H4); null disables the LAN path.
 * @property lanEvents WP7d's mDNS browse, for the radar.
 * @property browserHost hosts the browser receive page (F-D6); null when no link provider can host.
 * @property log a line for the ring-buffer log (architecture §14).
 */
class AndroidNodeConfig(
    val openDatabase: () -> DropData,
    val secrets: SecretStorage,
    val crypto: CryptoProvider,
    val beaconRadio: BeaconRadio,
    val bluetooth: BluetoothPaths,
    val stores: NodeStores,
    val power: PowerPolicy,
    val defaultNickname: String,
    val radioFacts: StateFlow<LocalRadioFacts>,
    val radioPermissions: StateFlow<RadioPermissionState>,
    val wallClock: WallClock,
    val monotonicClock: MonotonicClock,
    val wifiProviders: List<WifiLinkProvider> = emptyList(),
    val lanDialer: LanDialer? = null,
    val lanEvents: Flow<LanEvent> = emptyFlow(),
    val browserHost: BrowserHost? = null,
    val handshakeClock: HandshakeClock = HandshakeClock.SYSTEM,
    val transferClock: TransferClock = TransferClock.SYSTEM,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val tuning: NodeTuning = NodeTuning(),
    val discovery: DiscoveryControllerConfig = DiscoveryControllerConfig(),
    val log: (String) -> Unit = {},
)

/**
 * The phone's composition root inside the transfer service (WP7e; architecture §3, §10.1): one per service, it wires
 *
 * - **identity and secrets**: the software Ed25519 identity and this device's `k_adv` (S3) from the Keystore-wrapped
 *   store (N11), one [HandshakeGuard] for every responder (§6 note);
 * - **the database** (`core/data` over SQLCipher, F-J2): History rows for every transfer, trust and auto-accept,
 *   settings, the receiver's resume state through [DataResumeStore], and the 24 h sweep of partials ([PartialsSweeper]);
 * - **discovery**: [NearbyDevices] on the monotonic clock fed by WP7a's radio through its [discovery] controller, with
 *   this phone's beacon rebuilt from the effective visibility, the nickname and the detected capabilities (F-A1,
 *   F-A4, F-A5, N4), and advertising slowed and scanning paused while a transfer streams (N13);
 * - **transfers**: a send dials the peer over Bluetooth (WP7b: L2CAP, else the GATT stream; RFCOMM toward desktops)
 *   or its LAN endpoints, behind the handshake identity check ([EndpointDialer]); every inbound Bluetooth channel
 *   starts a new session. The engine runs over that primary channel, the ladder ([LadderTransferBridge] per transfer,
 *   over [AndroidNodeConfig.wifiProviders]) adds the Wi-Fi links. A link that drops is resumed by offering the same
 *   transfer again on a new session (S8, T-07), as on the desktops; a send interrupted by a restart is offered again
 *   when its trusted peer shows up;
 * - **trust**: SAS pairing on mutual proof only (F-B3, F-B4), the trust exchange that stores the peer's `k_adv` from its
 *   `TrustShare` (S3), "Forget" with a `k_adv` rotation (F-G3), auto-accept for trusted devices (F-D2), and codes: the
 *   five-minute code for "Show my code" and verified scanned codes (F-B5, T-12);
 * - **storage**: received files straight into MediaStore or the picked folder (N14), read from content URIs when sent;
 * - **the browser receive page** for a computer without the app (F-D6) through [AndroidNodeConfig.browserHost].
 *
 * The UI and the service read the [StateFlow]s and call the actions; actions never block. Call [start] once, then
 * [stop] once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNode(
    val config: AndroidNodeConfig,
) {
    private val crypto = config.crypto
    private val tuning = config.tuning
    private val eventFlow = MutableSharedFlow<NodeEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val nodeJob = SupervisorJob()
    private val failureHandler = CoroutineExceptionHandler { _, e -> report("unexpected failure", e) }
    private val scope = CoroutineScope(nodeJob + config.io + failureHandler)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val inboundSlots = Semaphore(tuning.maxInboundHandshakes)
    private val waitingForOffer = AtomicInteger()

    private val guard = HandshakeGuard(config.handshakeClock)
    private val nearby = NearbyDevices(crypto, config.wallClock, config.monotonicClock)

    private lateinit var dataRef: DropData
    private lateinit var identity: IdentityKey
    private lateinit var advertising: AdvertisingSecretStore
    private lateinit var resumeStore: ResumeStore
    private lateinit var sweeper: PartialsSweeper
    private lateinit var selfId: String

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

    /** Devices verified by a scanned code (F-B5), by radar key: sends to them expect exactly that identity. */
    private val codeTargets = ConcurrentHashMap<String, CodeTarget>()

    private val settingsState = MutableStateFlow<SettingsSnapshot?>(null)
    private val effectiveVisibilityState = MutableStateFlow(VisibilityPreference.DEFAULT.mode)
    private val trackers = MutableStateFlow<List<TransferTracker>>(emptyList())
    private val offerState = MutableStateFlow<List<NodeOffer>>(emptyList())
    private val pending = ConcurrentHashMap<String, PendingOffer>()
    private val receivedFlow =
        MutableSharedFlow<ReceivedItem>(extraBufferCapacity = RECEIVED_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val browserState = MutableStateFlow<BrowserShareStatus>(BrowserShareStatus.Idle)
    private val lastEnded = MutableStateFlow<Long?>(null)
    private var browserJob: Job? = null
    private val jobs = ArrayList<Job>()

    /** Answers "Allow this computer?" for the browser receive page (N15): the UI's presenter. Refuses until set. */
    @Volatile
    var browserApprover: BrowserApprover = BrowserApprover { false }

    /** The database; available once [start] returned. */
    val data: DropData get() = dataRef

    /** This device's id (`device_id` hex, architecture §12); available once [start] returned. */
    val selfDeviceId: String get() = selfId

    /** Every setting (F-G5), once the database is open. */
    val settings: StateFlow<SettingsSnapshot?> = settingsState.asStateFlow()

    /** The chosen visibility with its 10-minute window (the chip, F-A5). */
    val visibility: StateFlow<VisibilityPreference> =
        settingsState.map {
            it?.visibility ?: VisibilityPreference.DEFAULT
        }.stateIn(scope, SharingStarted.Eagerly, VisibilityPreference.DEFAULT)

    /** The visibility in force now (a 10-minute window that ended reads as the mode it reverts to). */
    val effectiveVisibility: StateFlow<Visibility> = effectiveVisibilityState.asStateFlow()

    /** This device's nickname (F-I3): the setting, else [AndroidNodeConfig.defaultNickname]. */
    val nickname: StateFlow<String> =
        settingsState
            .map { nicknameOf(it?.nickname) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, nicknameOf(null))

    /**
     * Discovery (WP7a): the radar, the beacon and the hooks the service and the UI flip (`setRadarVisible`,
     * `setForegroundService`, `wakeUp`). Runs once [start] returned.
     */
    val discovery: AndroidDiscoveryController =
        AndroidDiscoveryController(
            radio = config.beaconRadio,
            nearby = nearby,
            crypto = crypto,
            advertisingSecret = { ownSecrets.value.firstOrNull() ?: throw IllegalStateException("no advertising secret yet") },
            beaconState = AndroidDiscoveryController.phoneBeaconStates(effectiveVisibilityState, nickname, config.radioFacts),
            permissions = config.radioPermissions,
            wallClock = config.wallClock,
            trust = combine(ownSecrets, trustedKeys) { own, keys -> TrustState(own, keys.toTrustedPeers()) },
            lanEvents = config.lanEvents,
            config = config.discovery,
        )

    /** Devices on the radar (F-A2), nearest first. */
    val devices: StateFlow<List<NearbyDevice>> get() = discovery.devices

    /** Running and just-finished transfers (finished ones stay for [NodeTuning.finishedRetentionMillis]). */
    val transfers: StateFlow<List<NodeTransfer>> =
        trackers
            .flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.state }) { it.toList() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Offers waiting for the user, oldest first (F-D1). */
    val offers: StateFlow<List<NodeOffer>> = offerState.asStateFlow()

    /** Received files as they are published (F-D3); never suspends the engine, drops the oldest when nobody reads. */
    val received: SharedFlow<ReceivedItem> = receivedFlow.asSharedFlow()

    /** Completions, pairings and problems. */
    val events: SharedFlow<NodeEvent> = eventFlow.asSharedFlow()

    /** The browser receive path (F-D6). */
    val browserShare: StateFlow<BrowserShareStatus> = browserState.asStateFlow()

    /** What keeps the service running (S9): the service's [ServiceLifecycle] reads it. */
    val activity: StateFlow<NodeActivity> =
        combine(transfers, offerState, browserState, lastEnded) { list, offers, browser, ended ->
            NodeActivity(
                // An offer on the card moves no bytes yet: the radio session holds it, `dataSync` starts at Accept.
                active =
                    list.count {
                        it.stage.isActive && !(it.direction == NodeDirection.RECEIVE && it.stage == NodeStage.AWAITING_ACCEPT)
                    },
                parked = list.count { it.stage == NodeStage.WAITING_FOR_PEER },
                pendingOffers = offers.size,
                browserShare = browser is BrowserShareStatus.Starting || browser is BrowserShareStatus.Ready,
                lastEndedAtElapsedMillis = ended,
            )
        }.stateIn(scope, SharingStarted.Eagerly, NodeActivity())

    /** Capability bits this device announces and plans with (§5.2, F-A4). */
    private val capabilities: Capabilities get() = config.radioFacts.value.capabilities

    // =====================================================================================================
    // Lifecycle
    // =====================================================================================================

    /**
     * Opens the database, loads the identity and `k_adv`, and starts discovery, the Bluetooth servers and their accept
     * loop, the trust exchange and the 24 h sweep; sends an app restart interrupted wait for their peers again (T-07).
     *
     * @throws IllegalStateException when called twice.
     * @throws com.constrivo.drop.core.data.DatabaseVersionException for a database written by a newer app.
     * @throws com.constrivo.drop.core.crypto.CryptoException when the stored identity cannot be read (the app offers
     *   "Reset identity", F-B1).
     */
    suspend fun start() {
        check(started.compareAndSet(false, true)) { "an AndroidNode starts once" }
        withContext(config.io) {
            dataRef = config.openDatabase()
            // One identity and one advertising secret per install, even if two nodes start at once (a service
            // destroyed and recreated while the first start still runs): created under one process-wide lock.
            synchronized(IDENTITY_LOCK) {
                identity = SoftwareIdentityKeyStore(config.secrets, crypto).loadOrCreate()
                advertising = AdvertisingSecretStore(config.secrets, crypto)
                ownSecrets.value = listOf(advertising.current().bytes())
            }
            selfId = DeviceIds.of(crypto, identity.publicKey)
            ownGeneration = data.devices.ownAdvertisingGeneration()
            refreshTrust()
            settingsState.value = data.settings.snapshot()
            effectiveVisibilityState.value = data.settings.effectiveVisibility()
            val plans = FileResumePlanStore({ id -> config.stores.planDirectory(id) }, config.io)
            resumeStore = config.stores.recordingResumeStore(DataResumeStore(data, plans) { m, e -> report(m, e) })
            sweeper =
                PartialsSweeper(
                    data,
                    config.stores.partials,
                    config.wallClock,
                    intervalMillis = tuning.sweepIntervalMillis,
                    onError = { report("the 24 h clean-up of partial files failed", it) },
                )
        }
        jobs += scope.launch { data.settings.observeAll().collect { settingsState.value = it } }
        jobs += scope.launch { data.settings.observeEffectiveVisibility(visibilityRecheck).collect { effectiveVisibilityState.value = it } }
        jobs +=
            scope.launch {
                data.devices.observeTrustedKeys { id, e -> report("the secrets of device $id do not open", e) }.collect { setTrust(it) }
            }
        jobs += discovery.launchIn(scope)
        jobs += scope.launch { config.bluetooth.serve() }
        jobs += scope.launch { acceptLoop() }
        jobs += scope.launch { trustSyncLoop() }
        jobs += scope.launch { followStreaming() }
        jobs += sweeper.launchIn(scope)
        jobs += scope.launch { restoreSends() }
        config.log("node started as $selfId")
    }

    /**
     * Stops everything within a few seconds, whatever blocks: offers are declined, the ladders tear their links down
     * (F-E11) and every running transfer is left to resume later (its partial files, resume record and History row
     * stay; a sender offers it again after the next start, the receiver resumes it from its record, T-07), and the
     * database is closed. Idempotent.
     */
    suspend fun stop() {
        if (!started.get() || !stopping.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            val deadline = config.monotonicClock.elapsedMillis() + STOP_BUDGET_MILLIS
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
            val attempts = trackers.value.mapNotNull { it.attempt }
            step { for (a in attempts) runCatching { a.bridge?.runner?.value?.closeAndAwait() } }
            for (a in attempts) {
                a.retired.complete(Unit)
                a.scope.cancel()
                step(ATTEMPT_CLOSE_WAIT_MILLIS) { a.session.close() }
                for (source in a.sources) runCatching { source.close() }
            }
            trackers.value.forEach { it.abandon() }
            for (job in jobs) job.cancel()
            nodeJob.cancel()
            step { nodeJob.join() }
            step { data.close() }
        }
        config.log("node stopped")
    }

    // =====================================================================================================
    // Sending
    // =====================================================================================================

    /**
     * Sends [items] to the device with radar key [deviceKey] (F-C1, F-C2, F-C3), or to a device verified by a scanned
     * code (`q:` keys, F-B5): dials it behind the handshake identity check, shows the SAS on this side when the session
     * proved no pairing, offers the files and runs the transfer, offering it again if its link drops (S8). Returns the
     * transfer id at once (the transfer shows as [NodeStage.CONNECTING]), or null when [items] is empty or the device
     * is neither on the radar nor reachable through a scanned code.
     */
    fun send(
        deviceKey: String,
        items: List<SendItem>,
    ): String? {
        checkRunning()
        if (items.isEmpty()) return null
        val device = devices.value.firstOrNull { it.key == deviceKey }
        val code = codeTargets[deviceKey]?.takeIf { it.isFresh(now()) }
        if (device == null && code == null) return null
        val id = TransferId(crypto.randomBytes(TransferId.SIZE))
        val tracker =
            newTracker(
                id,
                NodeDirection.SEND,
                NodeTransfer(
                    id = id.toHex(),
                    direction = NodeDirection.SEND,
                    peerKey = deviceKey,
                    peerDeviceId = device?.trustedDeviceId ?: code?.deviceId,
                    peerName = device?.nickname.orEmpty(),
                    peerPlatform = device?.platform ?: DevicePlatform.UNKNOWN,
                    stage = NodeStage.CONNECTING,
                    fileCount = items.size,
                    bytesTotal = items.sumOf { it.size ?: 0L },
                    bytesDone = 0,
                ),
            )
        tracker.sendSpec = SendSpec(items, bundleSmall = settingsState.value?.bundleSmallFiles ?: true)
        addTracker(tracker)
        scope.launch { runSend(tracker, SendTarget(device, code)) }
        return id.toHex()
    }

    /** Sends the same files to the same device again (History "Send again", F-G2); null when that is impossible now. */
    suspend fun resend(transferId: String): String? {
        val id = parseId(transferId) ?: return null
        val record = data.transfers.get(id) ?: return null
        if (record.direction != TransferDirection.SEND) return null
        val items =
            data.transferFiles.files(id).mapNotNull { f -> f.savedUri?.let { SendItem(it, f.name, f.size) } }
        if (items.isEmpty()) return null
        val key = devices.value.firstOrNull { it.trustedDeviceId == record.peerDeviceId }?.key ?: return null
        return send(key, items)
    }

    /** Where a send goes: a radar entry, a scanned code, or both (a scanned device that is also on the radar). */
    private class SendTarget(
        val device: NearbyDevice?,
        val code: CodeTarget?,
    )

    /**
     * The whole life of a send: the first session (from the radar or a code; none for a send restored after a restart,
     * which starts waiting for its peer), then attempt after attempt until the transfer ends (S8).
     */
    private suspend fun runSend(
        tracker: TransferTracker,
        target: SendTarget?,
        interruptedAt: Long? = null,
    ) {
        val spec = checkNotNull(tracker.sendSpec)
        var dialed: Dialed? = null
        var since = interruptedAt
        if (target != null) {
            dialed =
                try {
                    openFirstSession(tracker, target) ?: return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HandshakeException) {
                    return failEarly(tracker, "handshake refused: ${e.reason}")
                } catch (e: Exception) {
                    return failEarly(tracker, describe(e), e)
                }
        }
        var restored = target == null
        while (true) {
            val current = dialed ?: reconnectSession(tracker, since ?: now(), restored) ?: return
            dialed = null
            restored = false
            if (tracker.cancelRequested) {
                closeQuietly(current)
                if (!tracker.hasRow) {
                    tracker.update { it.copy(stage = NodeStage.CANCELLED) }
                    tracker.abandon()
                    markEnded()
                } else {
                    tracker.abort(NodeStage.CANCELLED, null)
                    markEnded()
                }
                scheduleRemoval(tracker)
                return
            }
            val resumed = since != null
            val attempt =
                try {
                    startSendAttempt(tracker, spec, current, resumed)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { closeQuietly(current) }
                    throw e
                } catch (e: Exception) {
                    closeQuietly(current)
                    if (!resumed) return failEarly(tracker, describe(e), e)
                    report("sending ${tracker.hex} again failed", e)
                    tracker.abort(NodeStage.FAILED, describe(e))
                    markEnded()
                    scheduleRemoval(tracker)
                    return
                }
            when (val end = awaitAttempt(attempt, stopOnInterruption = true)) {
                is AttemptEnd.Final -> {
                    finishTracker(tracker, end.progress)
                    endAttempt(current, attempt)
                    return
                }

                AttemptEnd.Interrupted -> {
                    retire(tracker, attempt, current)
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
     * Dials [target] for a first send and records what the handshake verified. A device this one trusts that the radar
     * could not resolve (its `k_adv` is not here yet) is dialled once more with the pairing's trusted proof, so the
     * transfer runs trusted and the `TrustShare`s cross (S3). Null when the device cannot be reached by any path (the
     * tracker then shows the failure).
     */
    private suspend fun openFirstSession(
        tracker: TransferTracker,
        target: SendTarget,
    ): Dialed? {
        val candidates = candidatesFor(target.device, target.code, emptyList())
        if (candidates.isEmpty()) {
            failEarly(tracker, "the device has no Bluetooth or network address")
            return null
        }
        val expected =
            target.code?.let { ExpectedPeer(it.identityKey, lookup.recognitionSecretFor(it.identityKey)) }
                ?: target.device?.trustedDeviceId?.let { expectationFor(it) }
        var dialed = dial(candidates, expected)
        try {
            val peerKey = dialed.session.handshake.peerIdentityKey
            val secret = lookup.recognitionSecretFor(peerKey)
            if (expected == null && secret != null && !dialed.session.handshake.peerProvedTrust) {
                val proven =
                    try {
                        dial(candidatesFor(target.device, target.code, emptyList()), ExpectedPeer(peerKey, secret))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                if (proven != null && proven.session.handshake.peerProvedTrust) {
                    closeQuietly(dialed)
                    dialed = proven
                } else {
                    proven?.let { closeQuietly(it) }
                }
            }
            val result = dialed.session.handshake
            val peerId = DeviceIds.of(crypto, peerKey)
            val peerPlatform = platformOf(result.peerPlatform)
            recordPeer(peerKey, result.peerNickname, peerPlatform)
            tracker.peerIdentity = peerKey
            dialed.endpoint?.let { tracker.endpoints = listOf(it) }
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
            withContext(NonCancellable) { closeQuietly(dialed) }
            throw e
        }
    }

    /** Starts the engine for one attempt of a send on [dialed]; its `Offer` goes out at once. */
    private suspend fun startSendAttempt(
        tracker: TransferTracker,
        spec: SendSpec,
        dialed: Dialed,
        resumed: Boolean,
    ): Attempt {
        val session = dialed.session
        val result = session.handshake
        val sources = ArrayList<SourceFile>(spec.items.size)
        val attemptScope = attemptScope()
        try {
            // Opened one by one inside the try: a file that went missing closes the ones opened before it.
            for (item in spec.items) {
                val source = config.stores.sources.openSource(item.uri)
                sources += if (item.name.isNotBlank() && item.name != source.name) NamedSource(source, item.name) else source
            }
            val plan = plan(result, TransferRole.SENDER, lanReachable = dialed.endpoint != null)
            val ownCredentials = if (plan.groupOwner == Side.LOCAL) p2pCredentials(result) else null
            val options =
                SendOptions(
                    transferId = tracker.id,
                    linkOptions = if (ladderAvailable) LadderNegotiation.offerOptions(plan, ownCredentials) else emptyList(),
                    chunkSize = spec.chunkSize,
                    bundleSmall = spec.bundleSmall,
                )
            val engine =
                TransferEngine(engineConfig(sessionConfig(), config.stores.sources, resumeStore) { tracker.onPersist(it) }, attemptScope)
            val transfer = engine.send(session, sources, options)
            val bridge =
                if (!ladderAvailable) {
                    null
                } else {
                    LadderTransferBridge(transfer, attemptScope) { ladderSession, base ->
                        val agreement =
                            LadderNegotiation.adopt(plan, ownCredentials, transfer.accept.value?.link) { p2pCredentials(result) }
                        ladderRunner(agreement, ladderSession, base, attemptScope, trusted = isMutual(result))
                    }.also { transfer.linkListener = it }
                }
            val attempt = Attempt(attemptScope, session, transfer, bridge, sources)
            tracker.attach(attempt, capabilities, Capabilities(result.peerCaps), resumed)
            if (!tracker.isAwake) tracker.holdAwake(config.power.keepAwake("sending ${tracker.hex}"))
            if (!tracker.hasRow) {
                createSendHistory(
                    tracker,
                    DeviceIds.of(crypto, result.peerIdentityKey),
                    transfer.offer.mimeHistogram,
                    spec,
                    sources,
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
        spec: SendSpec,
        sources: List<SourceFile>,
    ) {
        try {
            val total = sources.sumOf { it.size }
            data.transfers.create(NewTransfer(tracker.id, peerId, TransferDirection.SEND, total, sources.size, histogram))
            spec.items
                .mapIndexed { i, item -> NewTransferFile(i, sources[i].name, sources[i].mimeType, sources[i].size, uri = item.uri) }
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
     * and tries as soon as its peer is on the radar. Returns the new session, or null when the transfer ended here.
     */
    private suspend fun reconnectSession(
        tracker: TransferTracker,
        since: Long,
        restored: Boolean,
    ): Dialed? {
        val parkedUntil = since + tuning.parkedWindowMillis
        var windowEnd = since + tuning.reconnectWindowMillis
        var backoff = RECONNECT_BACKOFF_MILLIS
        var seenBefore: List<NearbyDevice>? = if (restored) emptyList() else null
        while (true) {
            if (stopping.get()) return null
            if (tracker.cancelRequested) {
                tracker.abort(NodeStage.CANCELLED, null)
                markEnded()
                scheduleRemoval(tracker)
                return null
            }
            val now = now()
            if (now >= parkedUntil) {
                tracker.abort(NodeStage.CANCELLED, "${tracker.state.value.peerName.ifEmpty { "the device" }} did not come back")
                markEnded()
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
        val sightings = peerSightings(tracker, devices.value)
        val candidates = sightings.flatMap { candidatesFor(it, null, emptyList()) } + candidatesFor(null, null, tracker.endpoints)
        if (candidates.isEmpty()) return null
        return try {
            val dialed = dial(candidates.distinctBy { it.label }, ExpectedPeer(peerKey, lookup.recognitionSecretFor(peerKey)))
            dialed.endpoint?.let { e -> tracker.endpoints = (listOf(e) + tracker.endpoints).distinct().take(MAX_REMEMBERED_ENDPOINTS) }
            dialed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The radar entries that may be [tracker]'s peer: resolved to its device id, or its radar key (a stranger until it
     * rotates). The handshake's identity check rejects any other device.
     */
    private fun peerSightings(
        tracker: TransferTracker,
        list: List<NearbyDevice>,
    ): List<NearbyDevice> {
        val t = tracker.state.value
        val resolved = list.filter { d -> t.peerDeviceId != null && d.trustedDeviceId == t.peerDeviceId }
        val others = list.filter { d -> d !in resolved && t.peerKey != null && d.key == t.peerKey }
        return resolved + others
    }

    /**
     * Sends that an app restart interrupted (T-07): each unfinished send to a trusted device whose files can still be
     * read waits for its peer and offers the transfer again with its id, so the receiver resumes it. A share's files
     * whose grant died with the old process cannot be read; their rows are left to the 24 h clean-up.
     */
    private suspend fun restoreSends() {
        val rows = runCatching { data.transfers.active() }.getOrElse { return }
        for (row in rows) {
            if (row.direction != TransferDirection.SEND || trackers.value.any { it.id == row.id }) continue
            val restored = runCatching { restoredSend(row) }.getOrNull() ?: continue
            val (tracker, remaining) = restored
            addTracker(tracker)
            val since = now() - (tuning.parkedWindowMillis - remaining).coerceAtLeast(tuning.reconnectWindowMillis)
            scope.launch { runSend(tracker, target = null, interruptedAt = since) }
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
        val items =
            rows.map { f ->
                val uri = f.savedUri ?: return null
                // The source must still be readable with the same size, or the Offer would not match the receiver's record.
                val source = runCatching { config.stores.sources.openSource(uri) }.getOrNull() ?: return null
                val size = source.size
                runCatching { source.close() }
                if (size != f.size) return null
                SendItem(uri, f.name, f.size)
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
        tracker.sendSpec = SendSpec(items, bundle)
        return tracker to remaining
    }

    /** "Yes, it matches" on the sender's code (F-B3), during the transfer or after it ended: the peer becomes trusted here. */
    fun confirmPairing(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId && it.direction == NodeDirection.SEND } ?: return
        val result = tracker.pairingResult ?: return
        if (tracker.pairingCode == null) return
        tracker.setPairingCode(null)
        scope.launch {
            storeTrust(result)
            scheduleTrustSync(result.peerIdentityKey, tracker.endpoints, radarKeys = listOfNotNull(tracker.state.value.peerKey))
        }
    }

    /** "Not now" on a finished send's code (F-B3): the code goes without trusting the device. */
    fun dismissPairing(transferId: String) {
        val tracker = trackers.value.firstOrNull { it.hex == transferId && it.direction == NodeDirection.SEND } ?: return
        tracker.setPairingCode(null)
    }

    /** Cancels a transfer (design §4.2 ×; §7.8 `Cancel{user}`); the receiver clears its partial files. */
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

    private suspend fun acceptLoop() {
        for (channel in config.bluetooth.incoming) {
            if (!inboundSlots.tryAcquire()) {
                // More handshakes at once than the phone serves: closed at once (§13; the peer retries).
                runCatching { channel.close() }
                continue
            }
            scope.launch {
                try {
                    handleInbound(channel)
                } finally {
                    inboundSlots.release()
                }
            }
        }
    }

    /** Runs the responder handshake on an inbound channel and hands the session on; a failure closes the channel. */
    internal suspend fun handleInbound(channel: DataChannel) {
        val sessionConfig = sessionConfig()
        val session =
            try {
                SessionHandshake.respond(channel, sessionConfig)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { channel.close() } }
                throw e
            } catch (e: HandshakeException) {
                report("an inbound handshake was refused: ${e.reason}", null)
                return
            } catch (e: Exception) {
                return
            }
        if (waitingForOffer.incrementAndGet() > tuning.maxWaitingForOffer) {
            waitingForOffer.decrementAndGet()
            closeQuietly(session)
            return
        }
        val counted = AtomicBoolean(true)
        val offerArrived = { if (counted.compareAndSet(true, false)) waitingForOffer.decrementAndGet() }
        try {
            onInboundSession(session, sessionConfig, offerArrived)
        } finally {
            offerArrived()
        }
    }

    private suspend fun onInboundSession(
        session: SecureSession,
        sessionConfig: SessionConfig,
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
        onOffer(OfferContext(incoming, session, result, peerId, peerPlatform, device, mutual, intake, attemptScope))
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
                intake.finishedHere || (existing != null && !existing.isActive) -> DeclineReason.OTHER
                intake.storeFailure != null -> DeclineReason.STORAGE
                else -> null
            }
        if (refusal != null) {
            if (refusal == DeclineReason.STORAGE) report("the save location cannot be used; the offer was declined", intake.storeFailure)
            if (incoming.isCompatible) incoming.decline(refusal)
            withTimeoutOrNull(REFUSAL_WAIT_MILLIS) { incoming.transfer.await() }
            ctx.scope.cancel()
            closeQuietly(ctx.session)
            return
        }
        val name = ctx.device?.displayName?.ifEmpty { null } ?: ctx.result.peerNickname
        config.stores.setSender(id, name)
        val radarKey = devices.value.firstOrNull { it.trustedDeviceId == ctx.peerId }?.key
        val autoAccept = resumedTracker == null && ctx.mutual && ctx.device?.autoAccept == true
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
                    autoAccepted = autoAccept,
                ),
            )
        tracker.peerIdentity = ctx.result.peerIdentityKey
        tracker.store = intake.store
        intake.tracker = tracker
        if (resumedTracker == null) {
            addTracker(tracker)
            createReceiveHistory(tracker, ctx.peerId, offer.totalBytes, offer.fileCount, offer.mimeHistogram)
        }
        val plan = plan(ctx.result, TransferRole.RECEIVER, lanReachable = false)
        val decision =
            LadderNegotiation.accept(plan, if (ladderAvailable) offer.linkOptions else emptyList()) {
                p2pCredentials(ctx.result)
            }
        val bridge =
            if (!ladderAvailable) {
                null
            } else {
                LadderTransferBridge(incoming.transfer, ctx.scope) { ladderSession, base ->
                    ladderRunner(decision.agreement, ladderSession, base, ctx.scope, trusted = ctx.mutual)
                }.also { incoming.transfer.linkListener = it }
            }
        val attempt = Attempt(ctx.scope, ctx.session, incoming.transfer, bridge, emptyList())
        tracker.attach(attempt, capabilities, Capabilities(ctx.result.peerCaps), resumed = resumedTracker != null)
        var waiting: PendingOffer? = null
        // An Offer of an unsupported protocol version was declined `incompatible` by the engine already.
        if (incoming.isCompatible) {
            when {
                resumedTracker != null -> {
                    // The user accepted this transfer in this process; its sender offers it again after a drop (S8).
                    scope.launch { acceptResumed(tracker, incoming, decision.intent) }
                }

                autoAccept -> {
                    // F-D2: no card, the progress notification only.
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
            finishTracker(tracker, end.progress)
            endAttempt(null, attempt)
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
                    senderKey = radarKey ?: "$DEVICE_KEY_PREFIX${ctx.peerId}",
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
     * store); an id this process holds for another peer, or for an offer still waiting for its user, is a collision,
     * declined `busy`; anything else gets a new store at the current save location, or none when that cannot be used
     * (declined `storage`).
     */
    private inner class OfferIntake(
        private val peerKey: ByteArray,
    ) {
        val fileStore = DeferredFileStore()

        @Volatile
        var store: FileStore? = null

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
                        live.attempt?.let { retire(live, it, null) }
                        resumed = live
                        store = live.store
                        live.store?.let { fileStore.delegate = it }
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
                val created = ReportingFileStore(config.stores.receiveStore(settingsState.value?.saveLocation)) { onPublished(it) }
                store = created
                fileStore.delegate = created
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

    /** "Accept" on the incoming card or its notification; [alwaysAccept] turns on auto-accept (trusted only, F-D2). */
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
        if (!waiting.tracker.isAwake) waiting.tracker.holdAwake(config.power.keepAwake("receiving ${waiting.tracker.hex}"))
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
        if (!tracker.isAwake) tracker.holdAwake(config.power.keepAwake("receiving ${tracker.hex}"))
        try {
            incoming.accept(link = intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("resuming ${tracker.hex} failed", e)
        }
    }

    /** "Decline" on the incoming card or its notification (§7.2 `Decline{user}`). */
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
        scope.launch {
            waiting.trustJob?.join()
            waiting.incoming.decline(reason)
        }
    }

    /** "Yes, it matches" on the incoming card (F-B3): the sender becomes trusted on this device. */
    fun confirmCode(offerId: String) {
        val waiting = pending[offerId] ?: return
        if (waiting.offer.trusted || waiting.offer.sas == null || waiting.trustJob != null) return
        waiting.trustJob =
            scope.launch {
                storeTrust(waiting.result)
                // The sender exchanges the TrustShares once this transfer ends; should it not reach this device, this
                // side tries too, a little later.
                scheduleTrustSync(
                    waiting.result.peerIdentityKey,
                    emptyList(),
                    RECEIVER_SYNC_DELAY_MILLIS,
                    radarKeys = listOfNotNull(waiting.offer.senderKey.takeUnless { it.startsWith(DEVICE_KEY_PREFIX) }),
                )
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

    private fun onPublished(event: PublishedEvent) {
        val tracker = trackers.value.firstOrNull { it.hex == event.transferId }
        val item =
            ReceivedItem(
                id = "${event.transferId}:${event.fileIndex}",
                transferId = event.transferId,
                uri = event.file.uri,
                name = event.file.name,
                mimeType = event.mimeType,
                senderName = event.drop.senderName,
                executable = FileTypes.isExecutable(event.file.name, event.mimeType),
            )
        tracker?.onPublished(event.fileIndex, item)
        receivedFlow.tryEmit(item)
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
     * ladder tears its links down (F-E11), then the engine's scope is cancelled, which keeps the partial files and the
     * resume record as an app kill would, and its handles are closed. Idempotent; a second caller waits for the first.
     */
    private suspend fun retire(
        tracker: TransferTracker,
        attempt: Attempt,
        dialed: Dialed?,
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
                dialed?.let { withTimeoutOrNull(ATTEMPT_CLOSE_WAIT_MILLIS) { closeQuietly(it) } }
                for (source in attempt.sources) runCatching { source.close() }
            } finally {
                attempt.retirementDone.complete(Unit)
            }
        }
    }

    /** After an attempt's transfer ended: its ladder is closed ([finishTracker] waited for it), so its scope can go. */
    private suspend fun endAttempt(
        dialed: Dialed?,
        attempt: Attempt,
    ) {
        attempt.scope.cancel()
        for (source in attempt.sources) runCatching { source.close() }
        dialed?.let { withContext(NonCancellable) { withTimeoutOrNull(ATTEMPT_CLOSE_WAIT_MILLIS) { closeQuietly(it) } } }
    }

    /** Whether the ladder has any Wi-Fi rung to run on this phone (WP7c/d's providers). */
    private val ladderAvailable: Boolean get() = config.wifiProviders.isNotEmpty()

    private fun ladderRunner(
        agreement: LinkAgreement,
        ladderSession: LadderSession,
        base: Int,
        runnerScope: CoroutineScope,
        trusted: Boolean,
    ): LadderRunner =
        LadderRunner(
            agreement.plan,
            config.wifiProviders,
            ladderSession,
            runnerScope,
            config.monotonicClock,
            agreement.config(base, persistent = trusted),
            tuning.lifecycle,
        )

    /** Wi-Fi Direct credentials this phone brings as group owner (S5, N7): stable for a trusted pair, else random. */
    private fun p2pCredentials(result: HandshakeResult): WifiCredentials {
        val secret = lookup.recognitionSecretFor(result.peerIdentityKey)
        return if (secret != null && isMutual(result)) P2pCredentials.forTrustedPair(crypto, secret) else P2pCredentials.random(crypto)
    }

    // =====================================================================================================
    // Trust (F-B3, F-B4, F-B5, F-G3; S3)
    // =====================================================================================================

    /**
     * Whether the session's handshake proved the pairing both ways: the peer answered with this pairing's recognition
     * secret and this device trusts it. Only then is it trusted; otherwise both screens show the SAS (F-B3, F-B4).
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

        /**
         * The radar keys the peer was seen under while it was still a stranger here: until this device has the peer's
         * `k_adv` its beacon cannot resolve to its device id, and over Bluetooth there is no endpoint to remember, so the
         * exchange dials the stranger entry, behind the identity check that only the peer passes.
         */
        val radarKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        var notBefore = 0L

        @Volatile
        var attempts = 0

        @Volatile
        var running = false

        @Volatile
        var lastSightings: Set<String> = emptySet()

        val wakeScheduled = AtomicBoolean(false)
    }

    /** Exchanges `TrustShare`s with the peer of [peerKey] once no transfer with it runs (S3); retried after a failure. */
    private fun scheduleTrustSync(
        peerKey: ByteArray,
        endpoints: Collection<Endpoint>,
        delayMillis: Long = 0,
        radarKeys: Collection<String> = emptyList(),
    ) {
        val peerId = DeviceIds.of(crypto, peerKey)
        val sync = trustSyncs.computeIfAbsent(peerId) { TrustSync(peerKey.copyOf(), peerId) }
        sync.endpoints += endpoints
        sync.radarKeys += radarKeys
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
        val sightings = candidates.mapTo(HashSet()) { it.label }
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
        val busy = transfers.value.any { t -> !t.stage.isFinal && t.peerDeviceId == sync.peerId }
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

    /** Where the peer of [sync] may be: the radar's entries resolved to it (Bluetooth, LAN), then its known endpoints. */
    private fun syncCandidates(sync: TrustSync): List<PeerPath> {
        val resolved =
            devices.value.filter {
                it.trustedDeviceId == sync.peerId || (it.trustedDeviceId == null && it.key in sync.radarKeys)
            }
        return (resolved.flatMap { candidatesFor(it, null, emptyList()) } + candidatesFor(null, null, sync.endpoints.toList()))
            .distinctBy { it.label }
    }

    private enum class SyncOutcome { DONE, ONE_SIDED, GONE, RETRY }

    /**
     * After a pairing both devices trust each other but neither may have the other's advertising secret yet (S3): this
     * side opens a short session whose handshake carries the pairing's trusted proof. When the peer answers it, both
     * `TrustShare`s cross, the peer's is stored, and the session closes.
     */
    private suspend fun runTrustSync(
        sync: TrustSync,
        candidates: List<PeerPath>,
    ): SyncOutcome {
        val keys =
            try {
                data.devices.trustedKeys(sync.peerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return SyncOutcome.GONE
        val dialed =
            try {
                dial(candidates, ExpectedPeer(sync.peerKey, keys.recognitionSecret()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SyncOutcome.RETRY
            }
        try {
            if (!dialed.session.handshake.peerProvedTrust) return SyncOutcome.ONE_SIDED
            val share = withTimeoutOrNull(tuning.trustSyncWaitMillis) { readTrustShare(dialed.session) } ?: return SyncOutcome.RETRY
            onTrustShare(sync.peerKey, share).join()
            return SyncOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncOutcome.RETRY
        } finally {
            withContext(NonCancellable) { closeQuietly(dialed) }
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

    /** The `TrustShare` this device sends at the start of a session (S3): only when the session proved the pairing. */
    private fun trustShareFor(result: HandshakeResult): TrustShare? {
        if (!isMutual(result)) return null
        val own = ownSecrets.value.firstOrNull() ?: return null
        return TrustShare(WireAdvertisingSecret(own), ownGeneration)
    }

    private fun secretOf(share: TrustShare): AdvertisingSecret = AdvertisingSecret.fromPeer(share.secret.toByteArray())

    /** Renames a trusted device (Devices tab, F-G3). */
    suspend fun rename(
        deviceId: String,
        name: String?,
    ): Boolean = data.devices.rename(deviceId, name)

    /** Turns auto-accept on or off for a trusted device (F-D2). */
    suspend fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ): Boolean = data.devices.setAutoAccept(deviceId, enabled)

    /**
     * "Forget" (F-G3): the device is no longer trusted, and this device's `k_adv` is rotated so the forgotten device can
     * no longer recognise its beacon (S3); the advertisement is rebuilt at once, and the remaining trusted devices get
     * the new secret at their next session.
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
        discovery.refreshAdvertisement()
        return true
    }

    /**
     * "Show my code" (F-B5, architecture §6.3): a code signed now and valid for five minutes, with this device's
     * identity and current beacon ID. A phone has no fixed address to put in it (its links exist only during a
     * transfer), so a scanner finds it by that ID on its radar and connects expecting exactly this identity.
     */
    fun oneTimeCode(): NodeCode {
        checkRunning()
        val nowMillis = config.wallClock.nowMillis()
        val own = ownSecrets.value.first()
        val eph = EphemeralIds.at(crypto, own, nowMillis)
        val nowSeconds = (nowMillis / 1000).coerceAtLeast(1)
        val payload = QrPayloadCodec(crypto).createOneTime(identity, eph.toByteArray(), link = null, nowEpochSeconds = nowSeconds)
        return NodeCode(
            payload,
            fallback = null,
            issuedAtMillis = nowMillis,
            // The code names this epoch's beacon ID, which a scanner matches on its radar: the sheet re-signs it when
            // the ID rotates (every 15 min, §5.3) as well as when the payload's five minutes run out.
            expiresAtMillis =
                minOf(
                    (nowSeconds + QrPayload.ONE_TIME_VALIDITY_SECONDS) * 1000,
                    nowMillis - nowMillis % EphemeralIds.EPOCH_MILLIS + EphemeralIds.EPOCH_MILLIS,
                ),
        )
    }

    /**
     * A code the camera read (F-B5, T-12): verified (signature, device id, expiry), then mapped to where the device is.
     * The device on the radar that advertises the code's beacon ID, or the trusted device it names, keeps its radar key;
     * a device only reachable through the code's link (a desktop's LAN address) gets a `q:` key. Either way sends to it
     * expect the code's identity, so nothing else can answer (the pairing itself still needs the SAS: trust is mutual).
     */
    fun resolveCode(text: String): CodeScan {
        checkRunning()
        val payload =
            try {
                QrPayloadCodec(crypto).parse(text.trim(), (config.wallClock.nowMillis() / 1000).coerceAtLeast(1))
            } catch (e: QrPayloadException) {
                return if (e.reason == QrFailure.EXPIRED) CodeScan.Expired else CodeScan.Invalid(e.reason)
            }
        if (payload.identityKey.contentEquals(identity.publicKey)) return CodeScan.Invalid(null)
        val deviceId = DeviceIds.of(crypto, payload.identityKey)
        val eph = payload.ephemeralId
        val onRadar =
            devices.value.firstOrNull { it.trustedDeviceId == deviceId }
                ?: devices.value.firstOrNull { it.ephemeralId.toByteArray().contentEquals(eph) }
        val link = payload.link?.takeIf { it.kind == QrLinkKind.LAN && config.lanDialer != null }
        if (onRadar == null && link == null) return CodeScan.Invalid(null)
        val key = onRadar?.key ?: "$CODE_KEY_PREFIX$deviceId"
        val expires = now() + CODE_TARGET_MILLIS
        codeTargets[key] = CodeTarget(payload.identityKey, deviceId, link, expires)
        codeTargets.entries.removeIf { !it.value.isFresh(now()) }
        return CodeScan.Verified(key, deviceId)
    }

    /** A device verified by a scanned code: its identity, and the code's LAN link when it has one. */
    private class CodeTarget(
        identityKey: ByteArray,
        val deviceId: String,
        val link: QrLink?,
        private val expiresAtElapsedMillis: Long,
    ) {
        val identityKey: ByteArray = identityKey.copyOf()

        fun isFresh(now: Long): Boolean = now < expiresAtElapsedMillis
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

    /** Ticks when the effective visibility must be read again (the 10-minute window's end, an alarm; F-A5). */
    private val visibilityTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val visibilityRecheck: Flow<Unit> = visibilityTicks

    /** Re-reads the effective visibility now (the service's alarm at the end of "Everyone for 10 min"). */
    fun recheckVisibility() {
        visibilityTicks.tryEmit(Unit)
        discovery.wakeUp()
    }

    /** Visibility (F-A5): applies to the beacon and to handshakes at once. */
    suspend fun setVisibility(mode: Visibility) {
        data.settings.setVisibility(mode)
        effectiveVisibilityState.value = data.settings.effectiveVisibility()
    }

    /** The nickname (F-I3); null goes back to the default. */
    suspend fun setNickname(name: String?) = data.settings.set(SettingKeys.NICKNAME, name)

    /** Any other setting of design §6 (F-G5): it applies at once through the settings flow. */
    suspend fun <T> setSetting(
        key: SettingKey<T>,
        value: T,
    ) = data.settings.set(key, value)

    /**
     * "Clear partial files" (F-G5): parked and reconnecting receives are cancelled first (they would keep writing), then
     * `core/data`'s cleaner deletes the partials of every transfer that cannot use them any more, and of the unfinished
     * receives an earlier run of the app left behind.
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
        val candidates = interrupted.map { it.id } + leftBehind
        // Measured before they go, for "Cleared 12 MB"; an unreadable size counts as nothing freed.
        val sizes =
            withContext(config.io) {
                candidates.associateWith { id ->
                    runCatching { config.stores.partialBytes(id) }.getOrDefault(0L)
                }
            }
        val report = sweeper.cleaner.clearPartials(candidates)
        return PartialsCleared(report.purged.size, report.failed.size, report.purged.sumOf { sizes[it] ?: 0L })
    }

    /** History "Clear" (F-G2): deletes every finished transfer with its files (partials first); returns how many went. */
    suspend fun clearHistory(): Int = data.transfers.clearHistory { id -> sweeper.deletePartials(id) }

    // =====================================================================================================
    // Browser receive page (F-D6, architecture §10.3)
    // =====================================================================================================

    /**
     * Serves [items] to a computer without the app (F-D6): [AndroidNodeConfig.browserHost] brings up the phone's group
     * or hotspot and the page, each new browser is asked about through [browserApprover] (N15), and the page stops 60 s
     * after the last download.
     */
    fun startBrowserShare(items: List<SendItem>) {
        checkRunning()
        if (items.isEmpty()) return
        val host = config.browserHost
        browserJob?.cancel()
        if (host == null) {
            browserState.value = BrowserShareStatus.Failed("no Wi-Fi link can be hosted on this phone yet")
            return
        }
        browserState.value = BrowserShareStatus.Starting
        browserJob =
            scope.launch {
                val sources = ArrayList<SourceFile>()
                val awake = config.power.keepAwake("browser page")
                try {
                    for (item in items) sources += config.stores.sources.openSource(item.uri)
                    val files = sources.mapIndexed { i, s -> SourceSharedFile(s, items[i].name.ifBlank { s.name }) }
                    host.serve(ReceiveOffer(nickname.value, files), { request -> browserApprover.approve(request) }) { ready ->
                        browserState.value = ready
                    }
                    browserState.value = BrowserShareStatus.Idle
                } catch (e: CancellationException) {
                    browserState.value = BrowserShareStatus.Idle
                    throw e
                } catch (e: Exception) {
                    report("the browser receive page could not start", e)
                    browserState.value = BrowserShareStatus.Failed(describe(e))
                } finally {
                    awake.release()
                    withContext(NonCancellable) { for (s in sources) runCatching { s.close() } }
                }
            }
    }

    /** Stops offering the page; downloads in progress end with it. */
    fun stopBrowserShare() {
        browserJob?.cancel()
        browserJob = null
        if (browserState.value !is BrowserShareStatus.Failed) browserState.value = BrowserShareStatus.Idle
    }

    // =====================================================================================================
    // Plumbing
    // =====================================================================================================

    /** N13: while a transfer streams, advertising slows and scanning pauses (the discovery controller's hook). */
    private suspend fun followStreaming() {
        transfers
            .map { list -> list.any { it.stage == NodeStage.TRANSFERRING || it.stage == NodeStage.VERIFYING } }
            .distinctUntilChanged()
            .collect { discovery.setTransferActive(it) }
    }

    private fun sessionConfig(): SessionConfig {
        val local = LocalPeerInfo.forDevice(crypto, capabilities.bits, nickname.value, DevicePlatform.PHONE.code)
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
        resume: ResumeStore,
        onPersist: (TransferState) -> Unit,
    ): EngineConfig =
        EngineConfig(
            session = session,
            fileStore = store,
            resumeStore = resume,
            clock = config.transferClock,
            io = config.io,
            power = config.power,
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
        lanReachable: Boolean,
    ): LadderPlan {
        val facts = config.radioFacts.value
        val local = LinkFacts(capabilities, DevicePlatform.PHONE, facts.networkHint)
        val peer = LinkFacts(Capabilities(result.peerCaps), platformOf(result.peerPlatform))
        // Without a Wi-Fi rung on this phone the plan is Bluetooth only: nothing is offered or accepted that cannot run.
        val radio = RadioState(wifiEnabled = facts.wifiEnabled && ladderAvailable, bluetoothEnabled = facts.bluetoothEnabled)
        return LadderPlanner.plan(LadderInput(local, peer, role, radio, lanReachable = lanReachable, peerName = result.peerNickname))
    }

    /**
     * A session with the peer and how it was reached: [endpoint] for a LAN dial, which a new session after a drop
     * tries again (S8); null over Bluetooth, whose addresses the radar keeps current.
     */
    private class Dialed(
        val session: SecureSession,
        val endpoint: Endpoint?,
    )

    /** One way to reach a peer: [endpoint] when it is a LAN address, so a session dialled there remembers it. */
    private class PeerPath(
        val label: String,
        val endpoint: Endpoint?,
        val connect: suspend () -> DataChannel,
    )

    /**
     * The ways to reach a peer, best first: Bluetooth when the radar heard it (WP7b picks L2CAP, the GATT stream or
     * RFCOMM), then its LAN endpoints from mDNS, [extra] endpoints known from earlier sessions, and a scanned code's
     * link. Every one is tried behind the handshake identity check.
     */
    private fun candidatesFor(
        device: NearbyDevice?,
        code: CodeTarget?,
        extra: List<Endpoint>,
    ): List<PeerPath> {
        val out = ArrayList<PeerPath>()
        if (device != null && (device.radioAddresses.isNotEmpty() || device.classicAddress != null)) {
            out += PeerPath("bluetooth ${device.key}", null) { config.bluetooth.connect(device) }
        }
        val dialer = config.lanDialer
        if (dialer != null) {
            val endpoints = LinkedHashSet<Endpoint>()
            device?.lanEndpoints?.forEach { e -> runCatching { Endpoint(e.host, e.port) }.getOrNull()?.let(endpoints::add) }
            endpoints += extra
            code?.link?.let { link -> runCatching { Endpoint(link.address, link.port) }.getOrNull()?.let(endpoints::add) }
            for (endpoint in endpoints) out += PeerPath(LAN_LABEL + endpoint, endpoint) { dialer.connect(endpoint) }
        }
        return out
    }

    /**
     * Tries [paths] in order behind the identity check ([EndpointDialer]). The dialer tries one path at a time, so the
     * path whose channel opened last is the one the returned session runs on.
     *
     * @throws com.constrivo.drop.core.transfer.session.DialException when no path yields a session.
     */
    private suspend fun dial(
        paths: List<PeerPath>,
        expected: ExpectedPeer?,
    ): Dialed {
        val sessionConfig = sessionConfig()
        var reached: PeerPath? = null
        val candidates =
            paths.map { path ->
                DialCandidate(path.label) {
                    val channel = path.connect()
                    reached = path
                    channel
                }
            }
        val session =
            EndpointDialer.dial(candidates, tuning.dialTimeoutMillis) { channel ->
                SessionHandshake.initiate(channel, sessionConfig, expected)
            }
        return Dialed(session, reached?.endpoint)
    }

    /** Records the peer met through a verified handshake (F-B4); false when its id collides with another key. */
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
    ) {
        tracker.finish(final, tracker.transfer?.state?.value)
        markEnded()
        val received =
            if (tracker.direction == NodeDirection.RECEIVE) {
                final.files.filter { it.status == FileStatus.DONE }.mapNotNull { tracker.published[it.index] }
            } else {
                emptyList()
            }
        eventFlow.tryEmit(NodeEvent.TransferFinished(tracker.state.value, received))
        config.log("${if (tracker.direction == NodeDirection.RECEIVE) "receive" else "send"} ${tracker.hex} ended ${final.phase}")
        if (tracker.direction == NodeDirection.RECEIVE) config.stores.forget(tracker.id)
        tracker.bridge?.runner?.value?.let { runner -> withTimeoutOrNull(LADDER_CLOSE_WAIT_MILLIS) { runner.awaitClosed() } }
        scheduleRemoval(tracker)
    }

    private fun markEnded() {
        lastEnded.value = config.monotonicClock.elapsedMillis()
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
        markEnded()
        scheduleRemoval(tracker)
    }

    private fun nicknameOf(setting: String?): String =
        Nicknames.normalize(setting ?: config.defaultNickname)?.text
            ?: Nicknames.normalize(config.defaultNickname)?.text
            ?: DEFAULT_NICKNAME

    private fun platformOf(code: Int): DevicePlatform =
        if (code in 0..DevicePlatform.MAX_CODE) DevicePlatform.fromCode(code) else DevicePlatform.UNKNOWN

    private fun parseId(hex: String): TransferId? = runCatching { TransferId.fromHex(hex) }.getOrNull()

    private fun now(): Long = config.monotonicClock.elapsedMillis()

    private fun describe(e: Throwable): String = e.message ?: e::class.simpleName.orEmpty()

    private suspend fun closeQuietly(session: SecureSession) {
        runCatching { session.close() }
    }

    private suspend fun closeQuietly(dialed: Dialed) {
        runCatching { dialed.session.close() }
    }

    private fun checkRunning() {
        check(started.get() && !stopping.get()) { "the node is not running" }
    }

    private fun report(
        message: String,
        error: Throwable?,
    ) {
        eventFlow.tryEmit(NodeEvent.Problem(message, error))
        config.log(if (error == null) message else "$message: ${error::class.simpleName}: ${error.message}")
    }

    // Tests: the engine's counters of the attempt a transfer runs now (or ran last).
    internal fun statsOf(transferId: String): TransferStats? = trackers.value.firstOrNull { it.hex == transferId }?.transfer?.stats

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

    companion object {
        /** Serialises the identity and advertising-secret creation of every node in the process. */
        private val IDENTITY_LOCK = Any()

        /** The key prefix of a device reached only through a scanned code (F-B5). */
        const val CODE_KEY_PREFIX: String = "q:"

        /** The key prefix of an offer's sender that is not on the radar ([NodeOffer.senderKey]): its device id follows. */
        const val DEVICE_KEY_PREFIX: String = "d:"

        /** How long a scanned code's device stays sendable: the code's own validity. */
        const val CODE_TARGET_MILLIS: Long = QrPayload.ONE_TIME_VALIDITY_SECONDS * 1000

        private const val LAN_LABEL = "lan "
        private const val EVENT_BUFFER = 256
        private const val RECEIVED_BUFFER = 1024
        private const val FILE_BATCH = 1000
        private const val STOP_WAIT_MILLIS = 3_000L
        private const val STOP_BUDGET_MILLIS = 6_000L
        private const val ATTEMPT_CLOSE_WAIT_MILLIS = 1_000L
        private const val LADDER_CLOSE_WAIT_MILLIS = 10_000L
        private const val REFUSAL_WAIT_MILLIS = 5_000L
        private const val DEFAULT_NICKNAME = "Phone"
        private const val RECONNECT_BACKOFF_MILLIS = 500L
        private const val RECONNECT_BACKOFF_MAX_MILLIS = 8_000L
        private const val PARKED_RETRY_MILLIS = 60_000L
        private const val MAX_REMEMBERED_ENDPOINTS = 4
        private const val RECEIVER_SYNC_DELAY_MILLIS = 3_000L
        private const val TRUST_SYNC_LOOP_SPACING_MILLIS = 50L
    }
}
