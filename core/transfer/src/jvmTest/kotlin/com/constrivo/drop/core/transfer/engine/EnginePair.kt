package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.FaultyChannel
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StallableChannel
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two devices for the integration tests: identities and session configs, a receiver [DirectoryFileStore] on a temp
 * directory, a shared [InMemoryResumeStore], a primary connection (in memory, of [primaryKind], optionally throttled like
 * a Bluetooth link and cut on demand), reconnect sources, and Wi-Fi links over loopback TCP or in memory.
 */
internal class EnginePair(
    val primaryKind: LinkKind = LinkKind.BLUETOOTH,
    val primaryBytesPerSecond: Long? = null,
    val crypto: CryptoProvider = TestSupport.crypto,
    val clock: TransferClock = TransferClock.SYSTEM,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val resumeStore: ResumeStore = InMemoryResumeStore(),
    val debug: DebugHooks = DebugHooks.NONE,
    receiverStore: ((Path) -> FileStore)? = null,
    val lingerMillis: Long = 300,
    val reverifyOnResume: Boolean = true,
    val senderPower: PowerPolicy? = null,
    val receiverPower: PowerPolicy? = null,
) : AutoCloseable {
    val dir: Path = TestSupport.tempDir("pair")
    val senderConfig: SessionConfig = TestSupport.sessionConfig("Sender", provider = crypto)
    val receiverConfig: SessionConfig = TestSupport.sessionConfig("Receiver", provider = crypto)
    val directoryStore = DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"), io = io)
    val receiverStore: FileStore = receiverStore?.invoke(dir) ?: directoryStore
    val senderStore = DirectoryFileStore(dir.resolve("sender-partials"), dir.resolve("sender-unused"), io = io)
    val received: Path get() = dir.resolve("received")

    /** Scope of the current engines; [killEngines] cancels it (a simulated app kill). */
    var scope: CoroutineScope = newScope()
        private set

    /** The primary channels of the current session: the sender's end, the receiver's end. */
    var primaryEnds: Pair<FaultyChannel, FaultyChannel>? = null
        private set

    private val senderPrimaries = Channel<DataChannel>(Channel.UNLIMITED)
    private val receiverPrimaries = Channel<DataChannel>(Channel.UNLIMITED)
    private val listeners = ArrayList<TcpListener>()

    private fun newScope() = CoroutineScope(SupervisorJob() + dispatcher)

    fun engineConfig(
        session: SessionConfig,
        store: FileStore,
        power: PowerPolicy? = null,
    ): EngineConfig =
        EngineConfig(
            session = session,
            fileStore = store,
            resumeStore = resumeStore,
            clock = clock,
            io = io,
            compute = dispatcher,
            power = power,
            debug = debug,
            lingerMillis = lingerMillis,
            reverifyOnResume = reverifyOnResume,
        )

    val senderEngine: TransferEngine get() = TransferEngine(engineConfig(senderConfig, senderStore, senderPower), scope)
    val receiverEngine: TransferEngine get() = TransferEngine(engineConfig(receiverConfig, receiverStore, receiverPower), scope)

    /** The stallable layer of the current primary: `stall()` both to make the link go silent. */
    var primaryStalls: Pair<StallableChannel, StallableChannel>? = null
        private set

    /** A new primary channel pair of [primaryKind], paced at [primaryBytesPerSecond]. */
    fun newPrimary(cutAfterBytes: Long? = null): Pair<DataChannel, DataChannel> {
        val (a, b) = InMemoryDataChannel.pair(primaryKind, capacitySegments = 16, maxSegment = 16 * 1024)
        lateinit var ends: Pair<FaultyChannel, FaultyChannel>
        val left = FaultyChannel(a, primaryBytesPerSecond, cutAfterBytes) { ends.second.cutNow() }
        val right = FaultyChannel(b, primaryBytesPerSecond) { ends.first.cutNow() }
        ends = left to right
        primaryEnds = ends
        val stalls = StallableChannel(left) to StallableChannel(right)
        primaryStalls = stalls
        return stalls
    }

    /** The current primary goes silent in both directions without closing. */
    fun stallPrimary() {
        primaryStalls?.let {
            it.first.stalled = true
            it.second.stalled = true
        }
    }

    /** Handshake over a new primary: the sender initiates. */
    suspend fun connect(cutAfterBytes: Long? = null): Pair<SecureSession, SecureSession> =
        TestSupport.handshake(senderConfig, receiverConfig, newPrimary(cutAfterBytes))

    /** Makes a fresh primary available to both reconnect sources (the link is back). */
    fun offerReconnect() {
        val (a, b) = newPrimary()
        senderPrimaries.trySend(a)
        receiverPrimaries.trySend(b)
    }

    val senderReconnect = PrimaryLinkSource { senderPrimaries.receive() }
    val receiverReconnect = PrimaryLinkSource { receiverPrimaries.receive() }

    /** Starts a transfer of [files] and accepts it; returns (sender, receiver). */
    suspend fun start(
        files: List<SourceFile>,
        options: SendOptions = SendOptions(reconnect = senderReconnect),
        receive: ReceiveOptions = ReceiveOptions(reconnect = receiverReconnect),
        streamCount: Int = 8,
        sessions: Pair<SecureSession, SecureSession>? = null,
    ): Pair<Transfer, Transfer> {
        val (a, b) = sessions ?: connect()
        val sending = senderEngine.send(a, files, options)
        val incoming = scope.async { receiverEngine.receive(b, receive) }.await()
        val receiving = incoming.accept(streamCount = streamCount)
        return sending to receiving
    }

    /**
     * A Wi-Fi link of [generation] over loopback TCP: the sender hosts (LAN rule of S5) unless [senderHosts] is false.
     * Both sides bring it up; with [use] data and control move to it at once.
     */
    suspend fun attachTcp(
        sender: Transfer,
        receiver: Transfer,
        generation: Int,
        senderHosts: Boolean = true,
        use: Boolean = true,
        kind: LinkKind = LinkKind.LAN,
    ) {
        val listener = TcpListener(kind = kind, io = io)
        listeners += listener
        val host = DataLink(kind, generation, DataLinkRole.ACCEPT) { listener.accept() }
        val join = DataLink(kind, generation, DataLinkRole.CONNECT) { TcpDataChannel.connect("127.0.0.1", listener.port, kind, io = io) }
        coroutineScope {
            launch { (if (senderHosts) sender else receiver).connectLink(host, use) }
            launch { (if (senderHosts) receiver else sender).connectLink(join, use) }
        }
    }

    /** A Wi-Fi link of [generation] in memory; the returned list collects its channels so a test can cut them. */
    suspend fun attachMemory(
        sender: Transfer,
        receiver: Transfer,
        generation: Int,
        use: Boolean = true,
        kind: LinkKind = LinkKind.P2P,
        bytesPerSecond: Long? = null,
    ): MutableList<DataChannel> {
        val pending = Channel<DataChannel>(Channel.UNLIMITED)
        val all = java.util.Collections.synchronizedList(ArrayList<DataChannel>())
        val host = DataLink(kind, generation, DataLinkRole.ACCEPT) { pending.receive() }
        val join =
            DataLink(kind, generation, DataLinkRole.CONNECT) {
                val (x, y) = InMemoryDataChannel.pair(kind, capacitySegments = 128)
                val a = if (bytesPerSecond != null) FaultyChannel(x, bytesPerSecond) else x
                val b = if (bytesPerSecond != null) FaultyChannel(y, bytesPerSecond) else y
                all += a
                all += b
                pending.send(b)
                a
            }
        coroutineScope {
            launch { sender.connectLink(host, use) }
            launch { receiver.connectLink(join, use) }
        }
        return all
    }

    /** Cancels the engines' scope without letting them finish (a simulated app kill). */
    suspend fun killEngines() {
        scope.cancel()
        directoryStore.closeAll()
        senderStore.closeAll()
        primaryEnds?.let {
            it.first.cutNow()
            it.second.cutNow()
        }
        scope = newScope()
    }

    fun receivedFile(name: String): Path = received.resolve(name)

    fun receivedNames(): List<String> = Files.list(received).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    override fun close() {
        scope.cancel()
        listeners.forEach { it.close() }
        TestSupport.deleteTree(dir)
    }
}
