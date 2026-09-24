package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.crypto.frame.FrameLimitException
import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.Cancel
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.Complete
import com.constrivo.drop.core.protocol.ControlMessage
import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.Decline
import com.constrivo.drop.core.protocol.FileDone
import com.constrivo.drop.core.protocol.FileList
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.Heartbeat
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.Resume
import com.constrivo.drop.core.protocol.Retransmit
import com.constrivo.drop.core.protocol.SessionRole
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.TransferEffect
import com.constrivo.drop.core.protocol.TransferEvent
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.protocol.TransferStateMachine
import com.constrivo.drop.core.protocol.TransferTimer
import com.constrivo.drop.core.protocol.Transition
import com.constrivo.drop.core.protocol.TruncatedFrameException
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.LowLatencyChannel
import com.constrivo.drop.core.transfer.ThermalLevel
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.flow.ArrivalMeter
import com.constrivo.drop.core.transfer.flow.CountingChannel
import com.constrivo.drop.core.transfer.flow.StreamCountPolicy
import com.constrivo.drop.core.transfer.flow.ThroughputMeter
import com.constrivo.drop.core.transfer.session.SecureConnection
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionException
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.core.transfer.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** One authenticated connection of the current session, as the run tracks it. */
internal class Conn(
    val secure: SecureConnection,
    /** The session this connection belongs to; connections of an older session are ignored. */
    val epoch: Int,
    /** The data link it belongs to; null for the primary connection. */
    val link: LinkRecord?,
) {
    @Volatile
    var alive: Boolean = true

    @Volatile
    var downHandled: Boolean = false

    /** Sender: at most [ProtocolConstants.STREAM_WINDOW] unacknowledged units on this stream (§7.4). */
    val window = Semaphore(ProtocolConstants.STREAM_WINDOW)

    var reader: Job? = null

    val kind: LinkKind get() = secure.kind
    val streamId: Int get() = secure.chunkStreamId
    val isPrimary: Boolean get() = link == null
    val carriesControl: Boolean get() = secure.purpose == StreamPurpose.CONTROL

    override fun toString(): String = secure.toString()
}

/** A data link handed to the run, and its connections. */
internal class LinkRecord(
    val link: DataLink,
    val epoch: Int,
) {
    val conns = ArrayList<Conn>()
    var controlConn: Conn? = null
    var job: Job? = null

    @Volatile
    var lost: Boolean = false

    val generation: Int get() = link.generation
}

/**
 * One transfer on one device: the actor that drives the [TransferStateMachine] (architecture §7.7, S8), the session and
 * its connections, the control route (N13), heartbeats and the watchdog (§7.8), reconnects with a new handshake (N3),
 * the ticker (§7.4 meter, stream count, hints), and progress publication. The role-specific data paths live in
 * [SendSide] and [ReceiveSide].
 *
 * Threading: reducer state, the peer's control route and the effects are confined to the actor coroutine; control
 * frames leave through one writer coroutine ([outbox]), which owns our own control route; connection readers post
 * what they read to the actor.
 */
internal class TransferRun(
    val config: EngineConfig,
    val transferId: TransferId,
    val role: TransferRole,
    firstSession: SecureSession,
    parentScope: CoroutineScope,
    private val linkSource: PrimaryLinkSource?,
    val fileCount: Int,
    val totalBytes: Long,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope: CoroutineScope =
        CoroutineScope(
            parentScope.coroutineContext + job +
                CoroutineExceptionHandler { _, e -> post(RunEvent.Failure(e)) },
        )

    private val lock = TransferLock()
    private val events = Channel<RunEvent>(Channel.UNLIMITED)
    private val outbox = Channel<Out>(Channel.UNLIMITED)
    private val machine = TransferStateMachine(config.timeouts)

    val sessionRole: SessionRole = firstSession.role
    val peerIdentityKey: ByteArray = firstSession.peerIdentityKey
    val peerName: String = firstSession.handshake.peerNickname

    @Volatile
    var machineState: TransferState = machine.start(transferId, role, fileCount, totalBytes, now()).state
        private set

    val stateFlow = MutableStateFlow(machineState)
    val progressFlow = MutableStateFlow(TransferProgress(transferId, role, machineState.phase, peerName, fileCount, totalBytes, 0))
    val result = CompletableDeferred<TransferProgress>()
    val acceptFlow = MutableStateFlow<Accept?>(null)

    /** Receiver: bytes that arrived per link kind, for the ladder's throughput samples (§4). */
    val arrivals = ArrivalMeter()

    // ---- session ----
    @Volatile
    var epoch: Int = 0
        private set

    @Volatile
    var session: SecureSession = firstSession
        private set

    @Volatile
    var primary: Conn = Conn(firstSession.primary, 0, null)
        private set

    @Volatile
    private var sessionAlive = true
    private val conns = ArrayList<Conn>()
    private val links = HashMap<Int, LinkRecord>()

    // ---- control routing (N13) ----
    @Volatile
    private var outRoute: Conn = primary
    private var peerRoute: Conn? = primary
    private var pendingPeerStream: Int? = null
    private val buffered = HashMap<Conn, ArrayDeque<ControlMessage>>()

    @Volatile
    private var lastPeerHeartbeat: Long? = null

    // ---- sides ----
    var sender: SendSide? = null
    var receiver: ReceiveSide? = null

    @Volatile
    var listener: TransferLinkListener? = null

    // ---- timers and jobs ----
    private val timers = HashMap<TransferTimer, Job>()
    private var reconnectJob: Job? = null
    private var tickerJob: Job? = null
    private var resumeWaitJob: Job? = null
    private var terminalHandled = false
    private var sentFinal = false

    @Volatile
    private var waitingForPeer = false

    @Volatile
    private var lastFeed = Long.MIN_VALUE / 2

    // ---- meter, streams, hints ----
    val meter = ThroughputMeter()
    private val policyLock = TransferLock()
    private var policy = StreamCountPolicy()

    /** The stream count the policy wants now (§7.4), read by the sender's scheduler and the link openers. */
    val streamTarget = MutableStateFlow(ProtocolConstants.DEFAULT_STREAMS)

    /** Bumped whenever a connection comes or goes, to wake the link openers. */
    val connSignal = MutableStateFlow(0)
    private val hintLock = TransferLock()
    private val localHints = HashSet<HintCode>()
    private val peerHints = HashMap<HintCode, Long>()
    private var lastThermalHintAt = Long.MIN_VALUE / 2
    private var failure: String? = null

    /** The receiver's stream limit from `Accept.stream_count`. */
    @Volatile
    var acceptedStreamCount: Int = ProtocolConstants.MAX_STREAMS

    init {
        conns += primary
    }

    fun now(): Long = config.clock.nowMillis()

    // =====================================================================================================
    // Start and the actor
    // =====================================================================================================

    /** Starts the actor, the primary reader and the control writer; [afterStart] runs first in the actor. */
    fun start(afterStart: suspend () -> Unit) {
        val initial = machine.start(transferId, role, fileCount, totalBytes, now())
        machineState = initial.state
        scope.launch {
            apply(initial)
            try {
                afterStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                localFailure(e)
            }
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    localFailure(e)
                }
            }
        }
        scope.launch { controlWriter() }
        startReader(primary)
    }

    fun post(event: RunEvent) {
        events.trySend(event)
    }

    /** Runs [block] in the actor. */
    fun call(block: suspend () -> Unit) = post(RunEvent.Call(block))

    fun reduceLater(event: TransferEvent) = post(RunEvent.Machine(event))

    private suspend fun handle(event: RunEvent) {
        when (event) {
            is RunEvent.Machine -> reduce(event.event)
            is RunEvent.Control -> onControl(event.conn, event.message)
            is RunEvent.ConnDown -> onConnDown(event.conn, event.error)
            is RunEvent.Timer -> reduce(TransferEvent.TimerFired(event.timer))
            is RunEvent.Call -> event.block()
            is RunEvent.Failure -> localFailure(event.error)
        }
    }

    /** Applies [event] to the state machine (actor only). */
    fun reduce(event: TransferEvent) {
        val transition = machine.reduce(machineState, event, now())
        if (transition.handled) apply(transition)
    }

    private fun apply(transition: Transition) {
        val before = machineState
        machineState = transition.state
        stateFlow.value = transition.state
        val terminal = transition.state.phase.isTerminal && !terminalHandled && !before.phase.isTerminal
        for (effect in transition.effects) execute(effect, terminal)
        if (terminal) onTerminal()
    }

    private fun execute(
        effect: TransferEffect,
        terminal: Boolean,
    ) {
        when (effect) {
            is TransferEffect.Send -> {
                if (terminal) {
                    sentFinal = true
                    outbox.trySend(Out.Final(effect.message))
                } else {
                    sendControl(effect.message)
                }
            }

            is TransferEffect.StartTimer -> {
                startTimer(effect.timer, effect.atMillis)
            }

            is TransferEffect.CancelTimer -> {
                timers.remove(effect.timer)?.cancel()
            }

            TransferEffect.Persist -> {
                runCatching { config.onPersist(machineState) }
            }

            TransferEffect.NotifyUi -> {
                publishProgress()
            }

            TransferEffect.StartStreaming -> {
                onStartStreaming()
            }

            TransferEffect.StartReconnect -> {
                waitingForPeer = false
                // Heartbeat lost: the old session is dead even if its sockets are still open (N3: new keys next).
                dropSession()
                startReconnect()
            }

            TransferEffect.StopReconnect -> {
                reconnectJob?.cancel()
                reconnectJob = null
            }

            TransferEffect.WatchForPeer -> {
                waitingForPeer = true
            }

            TransferEffect.StopWatchingForPeer -> {
                waitingForPeer = false
            }

            is TransferEffect.FileFailed -> {
                receiver?.onFileFailed(effect.fileIndex)
            }

            TransferEffect.ClearPartials -> {
                receiver?.markClearPartials()
            }

            TransferEffect.ReleaseLink -> {
                return // done in onTerminal, after the final message
            }
        }
    }

    private fun startTimer(
        timer: TransferTimer,
        atMillis: Long,
    ) {
        timers.remove(timer)?.cancel()
        timers[timer] =
            scope.launch {
                delay((atMillis - now()).coerceAtLeast(0))
                post(RunEvent.Timer(timer))
            }
    }

    private fun localFailure(error: Throwable) {
        if (machineState.phase.isTerminal) return
        failure = error.message ?: error::class.simpleName
        reduce(TransferEvent.LocalCancel(CancelReason.OTHER))
    }

    /** The peer broke the protocol on an authenticated stream: `Cancel(protocol)`, `Failed`. */
    fun protocolViolation(detail: String) {
        if (machineState.phase.isTerminal) return
        failure = detail
        reduce(TransferEvent.ProtocolViolation(detail))
    }

    // =====================================================================================================
    // Streaming, ticker, terminal
    // =====================================================================================================

    private fun onStartStreaming() {
        listener?.onTransferStarted()
        // The stream count and thermal hint hold from the first chunk on, not from the first tick.
        updateStreamsAndHints(now())
        sender?.startStreaming()
        receiver?.startStreaming()
        startTicker()
    }

    private fun startTicker() {
        if (tickerJob != null) return
        tickerJob =
            scope.launch {
                var last = now()
                var lastHeartbeat = now()
                while (isActive) {
                    if (!machineState.phase.isConnected) {
                        // Interrupted or parked (up to 24 h): nothing to measure or send until a link is back.
                        publishProgress()
                        stateFlow.first { it.phase.isConnected || it.phase.isTerminal }
                        last = now()
                        lastHeartbeat = now()
                    }
                    delay(ThroughputMeter.SAMPLE_MILLIS)
                    val t = now()
                    val interval = (t - last).coerceAtLeast(1)
                    last = t
                    meter.sample(interval)
                    reportThroughput()
                    if (t - lastHeartbeat >= ProtocolConstants.HEARTBEAT_INTERVAL_MS && machineState.phase.isConnected) {
                        lastHeartbeat = t
                        outbox.trySend(Out.Beat)
                    }
                    updateStreamsAndHints(t)
                    publishProgress()
                }
            }
    }

    private fun reportThroughput() {
        val l = listener ?: return
        when (role) {
            TransferRole.RECEIVER -> arrivals.drain().forEach { (kind, bytes) -> l.onThroughputSample(kind, bytes) }
            TransferRole.SENDER -> sender?.drainAckedByKind()?.forEach { (kind, bytes) -> l.onThroughputSample(kind, bytes) }
        }
    }

    private fun updateStreamsAndHints(t: Long) {
        val thermal = config.power?.thermal?.value ?: ThermalLevel.NONE
        val hot = thermal >= StreamCountPolicy.THERMAL_LEVEL
        if (hot) {
            addLocalHint(HintCode.THERMAL)
            if (t - lastThermalHintAt >= EngineLimits.THERMAL_HINT_REPEAT_MILLIS && machineState.phase.isConnected) {
                lastThermalHintAt = t
                sendControl(Hint(HintCode.THERMAL, emptyMap(), transferId))
            }
        } else {
            hintLock.withLock { localHints.remove(HintCode.THERMAL) }
        }
        val peerHot = hintLock.withLock { (peerHints[HintCode.THERMAL] ?: Long.MIN_VALUE) > t }
        val target = policyLock.withLock { policy.target(meter.lastSecondBytesPerSecond, thermal, peerHot) }
        if (streamTarget.value != target) streamTarget.value = target
    }

    fun setStreamLimit(limit: Int) {
        acceptedStreamCount = limit
        policyLock.withLock { policy = StreamCountPolicy(limit.coerceIn(1, ProtocolConstants.MAX_STREAMS)) }
        streamTarget.value = minOf(streamTarget.value, limit)
    }

    fun addLocalHint(code: HintCode) {
        hintLock.withLock { localHints += code }
    }

    private fun onHint(message: Hint) {
        val code = message.hintCode ?: return
        if (code == HintCode.PEER_BAND24_ONLY) return
        val until = if (code == HintCode.THERMAL) now() + EngineLimits.PEER_THERMAL_MILLIS else Long.MAX_VALUE
        hintLock.withLock { peerHints[code] = until }
        publishProgress()
    }

    private fun hints(t: Long): Set<HintCode> =
        hintLock.withLock {
            val out = HashSet(localHints)
            for ((code, until) in peerHints) if (until > t) out += code
            out
        }

    @Volatile
    private var lastPublishAt = Long.MIN_VALUE / 2

    @Volatile
    private var publishedBytes = 0L

    /**
     * Bytes moved: publish now when the last publication is [PUBLISH_MIN_MILLIS] old or this is the first progress, so the
     * UI sees movement at once (F-E5, F-G1); otherwise the next tick publishes.
     */
    fun progressChanged(bytesDone: Long) {
        val t = now()
        if ((publishedBytes == 0L && bytesDone > 0) || t - lastPublishAt >= PUBLISH_MIN_MILLIS) publishProgress()
    }

    fun publishProgress() {
        val state = machineState
        val t = now()
        val bytesDone: Long
        val files: List<FileProgress>
        val streams: Int
        when (role) {
            TransferRole.SENDER -> {
                val s = sender
                bytesDone = s?.bytesDone ?: 0
                files = s?.files?.snapshot() ?: emptyList()
                streams = s?.activeStreams ?: 0
            }

            TransferRole.RECEIVER -> {
                val r = receiver
                bytesDone = r?.bytesDone ?: 0
                files = r?.files?.snapshot() ?: emptyList()
                streams = lock.withLock { conns.count { it.alive && !it.isPrimary } }
            }
        }
        lastPublishAt = t
        publishedBytes = bytesDone
        val remaining = (totalBytes - bytesDone).coerceAtLeast(0)
        val streaming = state.phase == TransferPhase.STREAMING_BLUETOOTH || state.phase == TransferPhase.STREAMING_WIFI
        progressFlow.value =
            TransferProgress(
                transferId = transferId,
                role = role,
                phase = state.phase,
                peerName = peerName,
                fileCount = fileCount,
                bytesTotal = totalBytes,
                bytesDone = bytesDone,
                bytesPerSecond = if (state.phase.isTerminal) 0.0 else meter.bytesPerSecond,
                etaMillis = if (streaming) meter.etaMillis(remaining) else null,
                linkKind = state.link,
                freqMhz = state.freqMhz,
                streams = streams,
                hints = if (state.phase.isTerminal) emptySet() else hints(t),
                files = files,
                cancelReason = state.cancelReason,
                declineReason = state.declineReason,
                completeStatus = state.completeStatus,
                failure = failure ?: state.failure,
                waitingForPeer = waitingForPeer && state.phase == TransferPhase.PARKED,
            )
    }

    private fun onTerminal() {
        terminalHandled = true
        reconnectJob?.cancel()
        tickerJob?.cancel()
        resumeWaitJob?.cancel()
        timers.values.forEach { it.cancel() }
        timers.clear()
        val state = machineState
        scope.launch {
            withContext(NonCancellable) {
                try {
                    sender?.stop()
                    receiver?.finish(state)
                } catch (e: Throwable) {
                    failure = failure ?: e.message
                }
                publishProgress()
                // Every message queued so far goes out before the connections close.
                val drained = CompletableDeferred<Unit>()
                outbox.trySend(Out.Drain(drained))
                withTimeoutOrNull(config.lingerMillis + DRAIN_EXTRA_MILLIS) { drained.await() }
                if (sentFinal && config.lingerMillis > 0) {
                    // We sent the last word: give the peer the chance to read it and close first.
                    val readers = lock.withLock { conns.mapNotNull { it.reader } }
                    withTimeoutOrNull(config.lingerMillis) { readers.joinAll() }
                }
                closeAll()
                outbox.close()
                listener?.onTransferEnded(cancelled = state.phase != TransferPhase.DONE)
                result.complete(progressFlow.value)
            }
            job.cancel()
        }
    }

    // =====================================================================================================
    // Control out
    // =====================================================================================================

    /** Queues [message] for the control route. Dropped when no route exists (while interrupted). */
    fun sendControl(message: ControlMessage) {
        outbox.trySend(Out.Message(message))
    }

    /** Suspends until every control message queued so far was handed to its connection. */
    suspend fun drainOutbox() {
        val done = CompletableDeferred<Unit>()
        if (outbox.trySend(Out.Drain(done)).isFailure) return
        done.await()
    }

    /** Moves this side's control to link [generation]'s control stream with `ControlMoved` (N13). */
    fun moveControl(generation: Int) =
        call {
            val record = lock.withLock { links[generation] } ?: return@call
            val conn = record.controlConn?.takeIf { it.alive } ?: return@call
            outbox.trySend(Out.Move(conn, generation))
        }

    private suspend fun controlWriter() {
        for (item in outbox) {
            when (item) {
                is Out.Message -> {
                    writeRouted(item.message)
                }

                is Out.Move -> {
                    val current = currentOutRoute()
                    if (current !== item.conn && item.conn.alive) {
                        if (current != null) writeOn(current, ControlMoved(item.conn.streamId, item.generation))
                        outRoute = item.conn
                    }
                }

                is Out.Reset -> {
                    outRoute = item.conn
                }

                Out.Beat -> {
                    val beat = Heartbeat(now().coerceAtLeast(0), lastPeerHeartbeat)
                    val route = currentOutRoute()
                    if (route != null) writeOn(route, beat)
                    val p = primary
                    if (p !== route && p.alive && p.epoch == epoch) writeOn(p, beat)
                }

                is Out.Final -> {
                    val targets = LinkedHashSet<Conn>()
                    currentOutRoute()?.let { targets += it }
                    lock.withLock { conns.filter { it.alive && it.carriesControl } }.forEach { targets += it }
                    for (conn in targets) writeOn(conn, item.message)
                }

                is Out.Drain -> {
                    item.done.complete(Unit)
                }
            }
        }
    }

    private suspend fun writeRouted(message: ControlMessage) {
        repeat(2) {
            val conn = currentOutRoute() ?: return
            if (writeOn(conn, message)) return
        }
    }

    /** Writes [message] on [conn]; false (and the connection reported down) when that fails. */
    private suspend fun writeOn(
        conn: Conn,
        message: ControlMessage,
    ): Boolean {
        if (!conn.alive) return false
        return try {
            conn.secure.sendControl(message)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            connFailed(conn, e)
            false
        }
    }

    /** Our control route: the one we moved to, or the primary, or any live control stream of this session. */
    private fun currentOutRoute(): Conn? {
        val route = outRoute
        if (route.alive && route.epoch == epoch) return route
        val fallback = fallbackRoute() ?: return null
        outRoute = fallback
        if (fallback !== route && route.epoch == epoch) call { routeReverted() }
        return fallback
    }

    private fun fallbackRoute(): Conn? {
        val p = primary
        if (p.alive && p.epoch == epoch) return p
        return lock.withLock { conns.firstOrNull { it.alive && it.carriesControl && it.epoch == epoch } }
    }

    /** Our control route died and fell back: the receiver re-states what it misses, since acks may have been lost. */
    private fun routeReverted() {
        if (!machineState.phase.isConnected) return
        receiver?.sendResume()
    }

    // =====================================================================================================
    // Readers and control in
    // =====================================================================================================

    fun startReader(conn: Conn) {
        conn.reader =
            scope.launch(config.io) {
                var error: Throwable? = null
                try {
                    while (true) {
                        val header = conn.secure.readHeader() ?: break
                        if (conn.epoch == epoch) feedWatchdog()
                        when (header.type) {
                            FrameType.CONTROL -> {
                                val message = conn.secure.readControl(header) ?: continue
                                post(RunEvent.Control(conn, message))
                            }

                            FrameType.CHUNK -> {
                                val r = receiver ?: throw ProtocolException("the sender received a chunk frame")
                                r.onChunk(conn, header)
                            }

                            else -> {
                                throw ProtocolException("unexpected ${header.type} frame on $conn")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e
                } finally {
                    post(RunEvent.ConnDown(conn, error))
                }
            }
    }

    private fun feedWatchdog() {
        val t = now()
        if (t - lastFeed >= EngineLimits.WATCHDOG_FEED_MILLIS) {
            lastFeed = t
            post(RunEvent.Machine(TransferEvent.HeartbeatReceived))
        }
    }

    /** Sequences control from the peer's route (N13): messages from another stream wait until a `ControlMoved` names it. */
    private fun onControl(
        conn: Conn,
        message: ControlMessage,
    ) {
        if (conn.epoch != epoch || (machineState.phase.isTerminal && message !is Heartbeat)) return
        if (message is Heartbeat) {
            lastPeerHeartbeat = message.t
            return
        }
        val route = peerRoute
        if (route === conn || (route == null && conn.isPrimary && pendingPeerStream == null)) {
            dispatch(conn, message)
            return
        }
        val queue = buffered.getOrPut(conn) { ArrayDeque() }
        if (queue.size >= EngineLimits.MAX_BUFFERED_CONTROL) {
            protocolViolation("too many control messages on $conn before a ControlMoved named it")
            return
        }
        queue.addLast(message)
    }

    private fun switchPeerRoute(message: ControlMoved) {
        val target = lock.withLock { conns.firstOrNull { it.alive && it.streamId == message.streamId && !it.isPrimary } }
        if (target == null) {
            peerRoute = null
            pendingPeerStream = message.streamId
            return
        }
        setPeerRoute(target)
    }

    private fun setPeerRoute(conn: Conn) {
        peerRoute = conn
        pendingPeerStream = null
        val queue = buffered.remove(conn) ?: return
        while (queue.isNotEmpty() && peerRoute === conn) dispatch(conn, queue.removeFirst())
        if (queue.isNotEmpty()) buffered.getOrPut(conn) { ArrayDeque() }.addAll(0, queue)
    }

    private fun dispatch(
        conn: Conn,
        message: ControlMessage,
    ) {
        when (message) {
            is Heartbeat -> {
                return
            }

            is Cancel -> {
                if (message.transferId == transferId) reduce(TransferEvent.CancelReceived(message))
            }

            is Complete -> {
                if (message.transferId == transferId) reduce(TransferEvent.CompleteReceived(message))
            }

            is Hint -> {
                if (message.transferId == null || message.transferId == transferId) onHint(message)
            }

            is TrustShare -> {
                runCatching { config.onTrustShare(peerIdentityKey, message) }
            }

            is LinkReady -> {
                listener?.onPeerLinkReady(message)
            }

            is ControlMoved -> {
                switchPeerRoute(message)
                listener?.onPeerControlMoved(message)
            }

            else -> {
                when (role) {
                    TransferRole.SENDER -> senderMessage(message)
                    TransferRole.RECEIVER -> receiverMessage(message)
                }
            }
        }
    }

    private fun senderMessage(message: ControlMessage) {
        val s = sender ?: return
        when (message) {
            is Accept -> {
                if (message.transferId != transferId) return
                if (machineState.phase == TransferPhase.OFFERED) {
                    try {
                        s.accepted(message)
                    } catch (e: ProtocolException) {
                        protocolViolation(e.message ?: "bad Accept")
                        return
                    }
                    acceptFlow.value = message
                }
                reduce(TransferEvent.AcceptReceived(message))
            }

            is Decline -> {
                if (message.transferId == transferId) reduce(TransferEvent.DeclineReceived(message))
            }

            is com.constrivo.drop.core.protocol.Ack -> {
                if (message.transferId == transferId) s.onAck(message)
            }

            is Resume -> {
                if (message.transferId != transferId) return
                resumeWaitJob?.cancel()
                try {
                    s.onResume(message)
                } catch (e: ProtocolException) {
                    protocolViolation(e.message ?: "bad Resume")
                    return
                }
                reduce(TransferEvent.Resumed(primary.kind))
            }

            is Retransmit -> {
                if (message.transferId != transferId) return
                try {
                    s.onRetransmit(message)
                } catch (e: ProtocolException) {
                    protocolViolation(e.message ?: "bad Retransmit")
                    return
                }
                reduce(TransferEvent.RetransmitRequested(message))
            }

            is Offer, is FileList, is FileDone -> {
                protocolViolation("the receiver sent ${message.type}")
            }

            else -> {
                return
            }
        }
    }

    private fun receiverMessage(message: ControlMessage) {
        val r = receiver ?: return
        when (message) {
            is FileList -> {
                if (message.transferId == transferId) r.onFileList(message)
            }

            is FileDone -> {
                if (message.transferId == transferId) r.onFileDone(message)
            }

            is Offer -> {
                return // a repeated offer on the same session
            }

            is Accept, is Decline, is com.constrivo.drop.core.protocol.Ack, is Resume, is Retransmit -> {
                protocolViolation("the sender sent ${message.type}")
            }

            else -> {
                return
            }
        }
    }

    // =====================================================================================================
    // Connections, links and sessions
    // =====================================================================================================

    fun connFailed(
        conn: Conn,
        error: Throwable?,
    ) {
        if (!conn.alive) return
        conn.alive = false
        post(RunEvent.ConnDown(conn, error))
    }

    private fun onConnDown(
        conn: Conn,
        error: Throwable?,
    ) {
        conn.alive = false
        if (conn.downHandled) return
        conn.downHandled = true
        scope.launch { withContext(NonCancellable) { runCatching { conn.secure.close() } } }
        if (conn.epoch != epoch) return
        lock.withLock {
            conns.remove(conn)
            conn.link?.conns?.remove(conn)
        }
        connSignal.value++
        if (machineState.phase.isTerminal) return
        if (error is ProtocolException && error !is TruncatedFrameException && sessionAlive) {
            protocolViolation(error.message ?: "protocol violation on $conn")
            return
        }
        if (error is FrameLimitException) {
            // A key reached its limit: a new handshake gives fresh keys (N3).
            sessionLost()
            return
        }
        sender?.onConnDown(conn)
        if (peerRoute === conn || (peerRoute == null && pendingPeerStream == null)) {
            fallbackRoute()?.let { setPeerRoute(it) }
        }
        val record = conn.link
        // A link whose control stream died is gone (the group left, the host stopped): its other streams may be
        // zombies (a connect that landed in a backlog nobody accepts from), so they are closed with it.
        if (record != null && !record.lost && (conn === record.controlConn || lock.withLock { record.conns.none { it.alive } })) {
            record.lost = true
            val rest =
                lock.withLock {
                    links.remove(record.generation)
                    record.conns.toList()
                }
            record.job?.cancel()
            for (other in rest) connFailed(other, null)
            sender?.onLinkLost(record.generation)
            listener?.onLinkLost(record.link.kind, record.generation)
        }
        if (outRoute === conn) currentOutRoute()
        if (fallbackRoute() == null) sessionLost()
    }

    private fun sessionLost() {
        if (dropSession()) reduce(TransferEvent.LinkLost)
    }

    /**
     * Closes the current session's connections without telling the state machine (it already knows when the heartbeat
     * watchdog fired); false when there was no live session.
     */
    private fun dropSession(): Boolean {
        if (!sessionAlive) return false
        sessionAlive = false
        val lost = epoch
        closeAll()
        sender?.onSessionLost()
        listener?.onSessionLost(lost)
        return true
    }

    private fun closeAll() {
        val all =
            lock.withLock {
                val list = conns.toList()
                conns.clear()
                list
            }
        for (conn in all) conn.alive = false
        val records =
            lock.withLock {
                val list = links.values.toList()
                links.clear()
                list
            }
        for (record in records) {
            record.lost = true
            record.job?.cancel()
        }
        scope.launch {
            withContext(NonCancellable) {
                for (conn in all) runCatching { conn.secure.close() }
                runCatching { primary.secure.close() }
            }
        }
    }

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        val source = linkSource ?: return
        reconnectJob =
            scope.launch {
                var backoff = EngineLimits.RECONNECT_BACKOFF_MILLIS
                while (isActive) {
                    try {
                        val channel = source.next()
                        val fresh = handshakeAgain(channel)
                        call { installSession(fresh) }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delay(backoff)
                        backoff = minOf(backoff * 2, EngineLimits.RECONNECT_BACKOFF_MAX_MILLIS)
                    }
                }
            }
    }

    /** A new handshake with the same peer in the same role, requiring its identity (N3). */
    suspend fun handshakeAgain(channel: DataChannel): SecureSession =
        if (sessionRole == SessionRole.INITIATOR) {
            SessionHandshake.initiate(channel, config.session, config.session.expectedPeer(peerIdentityKey))
        } else {
            SessionHandshake.respond(channel, config.session, peerIdentityKey)
        }

    /** Replaces the lost session with [fresh] (after [handshakeAgain]) and exchanges `Resume` (§7.6). */
    fun installSession(fresh: SecureSession) {
        if (machineState.phase.isTerminal) {
            scope.launch { withContext(NonCancellable) { runCatching { fresh.close() } } }
            return
        }
        if (sessionAlive) closeAll()
        reconnectJob?.cancel()
        reconnectJob = null
        epoch++
        session = fresh
        val p = Conn(fresh.primary, epoch, null)
        primary = p
        lock.withLock {
            conns.clear()
            conns += p
        }
        buffered.clear()
        peerRoute = p
        pendingPeerStream = null
        sessionAlive = true
        outbox.trySend(Out.Reset(p))
        startReader(p)
        meter.resetRate()
        listener?.onSessionStarted(epoch)
        when (role) {
            TransferRole.RECEIVER -> {
                receiver?.sendResume()
                reduce(TransferEvent.Resumed(p.kind))
            }

            TransferRole.SENDER -> {
                sender?.onSessionStarted()
                resumeWaitJob?.cancel()
                val waitingEpoch = epoch
                resumeWaitJob =
                    scope.launch {
                        delay(EngineLimits.RESUME_WAIT_MILLIS)
                        call { if (epoch == waitingEpoch && machineState.phase.isInterrupted) sessionLostAgain() }
                    }
            }
        }
    }

    private fun sessionLostAgain() {
        dropSession()
        startReconnect()
    }

    /**
     * Brings up [link]'s first data stream (`StreamOpen`, purpose control) under the current session and keeps opening
     * (joiner) or accepting (host) more streams for it in the background. With [use] the sender moves data onto it and
     * both sides move control to it (N13), as a stand-alone engine without the ladder does.
     */
    suspend fun connectLink(
        link: DataLink,
        use: Boolean,
    ) {
        val record = LinkRecord(link, epoch)
        openConn(record, StreamPurpose.CONTROL)
        record.job =
            scope.launch(config.io) {
                if (link.role == DataLinkRole.CONNECT) openerLoop(record) else acceptLoop(record)
            }
        if (use) {
            useLink(link.generation)
            moveControl(link.generation)
        }
    }

    /** Moves the sender's data to link [generation] (null: the Bluetooth stream), as the ladder's data link says. */
    fun useLink(generation: Int?) =
        call {
            if (generation == null) {
                sender?.useGeneration(null)
                return@call
            }
            val record = lock.withLock { links[generation] } ?: return@call
            sender?.useGeneration(generation)
            reduce(TransferEvent.WifiStreamConnected(record.link.kind, record.link.freqMhz))
        }

    private suspend fun openConn(
        record: LinkRecord,
        purpose: StreamPurpose,
    ): Conn {
        val link = record.link
        val atSession = session
        val raw = link.open()
        // The receiver counts Wi-Fi bytes as the socket delivers them: the ladder's LAN check cannot wait for 4 MiB chunks.
        val channel = if (role == TransferRole.RECEIVER) CountingChannel(raw, arrivals) else raw
        val secure =
            try {
                if (link.role == DataLinkRole.CONNECT) {
                    atSession.openStream(channel, link.kind, transferId, purpose, link.generation)
                } else {
                    val accepted = atSession.acceptStream(channel, link.kind)
                    val open = checkNotNull(accepted.open)
                    if (open.transferId != transferId) throw ProtocolException("data stream for another transfer")
                    if (open.generation != link.generation) {
                        throw ProtocolException("data stream names generation ${open.generation}, the link is ${link.generation}")
                    }
                    accepted
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { channel.close() } }
                throw e
            }
        if (secure.purpose == StreamPurpose.CONTROL) (channel as? LowLatencyChannel)?.setLowLatency(true)
        val registered = CompletableDeferred<Conn?>()
        call { registered.complete(registerConn(record, secure, atSession)) }
        return registered.await() ?: run {
            withContext(NonCancellable) { runCatching { channel.close() } }
            throw SessionException("the session changed while the stream opened")
        }
    }

    private fun registerConn(
        record: LinkRecord,
        secure: SecureConnection,
        atSession: SecureSession,
    ): Conn? {
        if (atSession !== session || !sessionAlive || record.epoch != epoch || record.lost || machineState.phase.isTerminal) return null
        val conn = Conn(secure, epoch, record)
        val added =
            lock.withLock {
                val existing = links[record.generation]
                if (existing != null && existing !== record) {
                    false
                } else {
                    links[record.generation] = record
                    conns += conn
                    record.conns += conn
                    true
                }
            }
        if (!added) return null
        if (secure.purpose == StreamPurpose.CONTROL && record.controlConn == null) record.controlConn = conn
        startReader(conn)
        if (pendingPeerStream == conn.streamId) setPeerRoute(conn)
        sender?.onConnAdded(conn)
        connSignal.value++
        return conn
    }

    /** Joiner: keeps the link's stream count at the policy's target (§7.4); new streams signal a raise to the peer. */
    private suspend fun openerLoop(record: LinkRecord) {
        var failures = 0
        while (!record.lost) {
            val want = minOf(streamTarget.value, acceptedStreamCount)
            val have = lock.withLock { record.conns.count { it.alive } }
            if (have in 1 until want) {
                try {
                    openConn(record, StreamPurpose.DATA)
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HandshakeException) {
                    return
                } catch (e: Exception) {
                    if (++failures >= MAX_OPEN_FAILURES) return
                    delay(EngineLimits.RECONNECT_BACKOFF_MILLIS * failures)
                }
                continue
            }
            if (have == 0) return
            val seenTarget = streamTarget.value
            val seenConns = connSignal.value
            kotlinx.coroutines.flow.combine(streamTarget, connSignal) { a, b -> a to b }.first { (a, b) ->
                a != seenTarget || b != seenConns
            }
        }
    }

    /** Host: accepts every further stream the joiner opens for this link. */
    private suspend fun acceptLoop(record: LinkRecord) {
        var failures = 0
        while (!record.lost) {
            try {
                openConn(record, StreamPurpose.DATA)
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (record.lost || ++failures >= MAX_OPEN_FAILURES) return
                delay(ACCEPT_RETRY_MILLIS)
            }
        }
    }

    // =====================================================================================================
    // Public operations
    // =====================================================================================================

    fun cancel(reason: CancelReason) = call { reduce(TransferEvent.LocalCancel(reason)) }

    fun peerRediscovered() = call { reduce(TransferEvent.PeerRediscovered) }

    /** A new primary channel from the app (a manual reconnect): handshake again (N3), then resume. */
    suspend fun reconnectWith(channel: DataChannel) {
        val fresh = handshakeAgain(channel)
        val done = CompletableDeferred<Unit>()
        call {
            installSession(fresh)
            done.complete(Unit)
        }
        done.await()
    }

    /** Sends a `LinkReady` for the ladder (§7.2). */
    fun sendLinkReady(message: LinkReady) = sendControl(message)

    /** Live connections of the current session (tests). */
    fun liveConnections(): List<SecureConnection> = lock.withLock { conns.filter { it.alive }.map { it.secure } }

    fun connectionsOf(generation: Int): List<Conn> = lock.withLock { links[generation]?.conns?.filter { it.alive } ?: emptyList() }

    companion object {
        private const val MAX_OPEN_FAILURES = 5
        private const val PUBLISH_MIN_MILLIS = 100L
        private const val ACCEPT_RETRY_MILLIS = 50L
        private const val DRAIN_EXTRA_MILLIS = 3_000L
    }
}

/** Inputs of the run's actor. */
internal sealed interface RunEvent {
    class Machine(
        val event: TransferEvent,
    ) : RunEvent

    class Control(
        val conn: Conn,
        val message: ControlMessage,
    ) : RunEvent

    class ConnDown(
        val conn: Conn,
        val error: Throwable?,
    ) : RunEvent

    class Timer(
        val timer: TransferTimer,
    ) : RunEvent

    class Call(
        val block: suspend () -> Unit,
    ) : RunEvent

    class Failure(
        val error: Throwable,
    ) : RunEvent
}

/** Items for the control writer. */
internal sealed interface Out {
    class Message(
        val message: ControlMessage,
    ) : Out

    class Move(
        val conn: Conn,
        val generation: Int,
    ) : Out

    class Reset(
        val conn: Conn,
    ) : Out

    data object Beat : Out

    class Final(
        val message: ControlMessage,
    ) : Out

    class Drain(
        val done: CompletableDeferred<Unit>,
    ) : Out
}
