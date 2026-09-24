package com.constrivo.drop.core.ladder.engine

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.ladder.AttemptStatus
import com.constrivo.drop.core.ladder.LadderInput
import com.constrivo.drop.core.ladder.LadderNegotiation
import com.constrivo.drop.core.ladder.LadderPhase
import com.constrivo.drop.core.ladder.LadderPlan
import com.constrivo.drop.core.ladder.LadderPlanner
import com.constrivo.drop.core.ladder.LadderRunner
import com.constrivo.drop.core.ladder.LadderState
import com.constrivo.drop.core.ladder.LadderTimeouts
import com.constrivo.drop.core.ladder.LinkEndReason
import com.constrivo.drop.core.ladder.LinkLifecycle
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.TEST_CREDENTIALS
import com.constrivo.drop.core.ladder.phone
import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.core.transfer.engine.SendOptions
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferEngine
import com.constrivo.drop.core.transfer.engine.TransferLinkListener
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ladder and the transfer engine together (WP4 with the WP5 carry-forward): two phones on the same network, each
 * running a [TransferEngine] and a [LadderRunner] joined by a [LadderTransferBridge], a Bluetooth primary paced at
 * 100 KB/s, and Wi-Fi links that are loopback TCP behind fake [LoopbackLinkProvider]s.
 *
 * Shown end to end: the head start (data over Bluetooth before any Wi-Fi link exists, §7.4), the `LinkReady`
 * exchange (§7.2), the first authenticated `StreamOpen` stream of each link, the receiver's selection travelling as
 * N13 `ControlMoved` and the sender following it when the data link changes, and a byte-exact transfer.
 *
 * The rung deadlines are stretched to 20 s so that the gates, not the machine's speed, decide when a link comes up. The
 * LAN's throughput check is kept far from its threshold for the same reason: a fast LAN passes a lowered mark by a wide
 * margin, and a slow one stays well under the production mark, which a busy machine can only make slower.
 */
class LadderTransferEndToEndTest {
    private val mib = 1024 * 1024

    @Test
    fun `head start over Bluetooth, then the LAN is measured, selected and carries the transfer`() =
        runBlocking<Unit> {
            // The LAN comes up only once the head start is seen; the Wi-Fi Direct group never forms and loses the race.
            // The LAN's pass mark is 1 MB/s here (10 MB/s in production): each stream is paced at 6 MB/s, so the LAN passes
            // by a wide margin however busy the machine is, and the check stays a gate, not a race against the CPU.
            Phones(lanBytesPerSecond = 6_000_000, p2pGate = LinkGate(), lanMinBytesPerSecond = 1_000_000).use { phones ->
                val files = listOf(MemorySource("movie.mp4", bytes(40 * mib + 3, 1)), MemorySource("notes.txt", bytes(50_000, 2)))
                withTimeout(90_000) {
                    val (sending, receiving) = phones.start(files)

                    val headStart = receiving.progress.first { it.bytesDone > 0 }
                    assertEquals(TransferPhase.STREAMING_BLUETOOTH, headStart.phase, "the first bytes come over Bluetooth")
                    assertTrue(phones.senderLan.links.isEmpty() && phones.receiverLan.links.isEmpty(), "no Wi-Fi link exists yet")
                    phones.lanGate.open()

                    val receiverRunner = phones.receiverBridge.runner.filterNotNull().first()
                    val senderRunner = phones.senderBridge.runner.filterNotNull().first()
                    val selected = receiverRunner.state.first { it.phase == LadderPhase.ACTIVE && it.attempts[1].status.isEnded }
                    assertEquals(LinkMode.LAN, selected.dataCandidate?.mode)
                    assertEquals(LinkEndReason.SUPERSEDED, selected.attempts[1].endReason, "the Wi-Fi Direct rung lost the race (N9)")
                    val followed = senderRunner.state.first { it.phase == LadderPhase.ACTIVE }
                    assertEquals(LinkMode.LAN, followed.dataCandidate?.mode, "the sender follows the receiver's selection")

                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    phones.awaitLaddersClosed()
                }

                // LinkReady both ways for generation 0: the sender hosts the LAN (S5) and names its port.
                val hostLink = phones.senderLan.links.single()
                val fromSender = phones.receiverEvents.linkReady.single { it.generation == 0 }
                assertEquals(LinkKind.LAN, fromSender.linkKind)
                assertEquals("127.0.0.1", fromSender.address)
                assertEquals(hostLink.localPort, fromSender.port)
                assertEquals(LinkKind.LAN, phones.senderEvents.linkReady.single { it.generation == 0 }.linkKind)

                // The joiner opened the first StreamOpen stream and then the rest; the host accepted them.
                assertTrue(phones.receiverLan.links.single().streams >= 1)
                assertTrue(hostLink.streams >= 1)

                // N13: the receiver's selection reached the sender as ControlMoved, and the sender moved its own.
                assertTrue(phones.senderEvents.moved.any { it.generation == 0 }, "the receiver moved control to the LAN")
                assertTrue(phones.receiverEvents.moved.any { it.generation == 0 }, "the sender followed onto the LAN")

                // The LAN was measured on the bytes that arrived over it (§4), after the Bluetooth head start.
                assertTrue(phones.receiverEvents.bytes(LinkKind.BLUETOOTH) > 0)
                assertTrue(
                    phones.receiverEvents.bytes(LinkKind.LAN) >= 10_000_000,
                    "LAN bytes ${phones.receiverEvents.bytes(LinkKind.LAN)}",
                )
                assertEquals(2, phones.p2pGate.cancelled.get(), "both devices cancelled the group formation")

                for (file in files) assertEquals(sha256(file.bytes), sha256(phones.received(file.name)), file.name)
                assertTrue((phones.senderLan.links + phones.receiverLan.links).all { it.tornDown }, "every link is torn down (F-E11)")
            }
        }

    @Test
    fun `a slow LAN goes on standby, the Wi-Fi Direct group takes the data and the control moves with it`() =
        runBlocking<Unit> {
            // 4 streams of 1 MB/s stay below the 10 MB/s LAN threshold; the group forms once the LAN is on standby.
            Phones(lanBytesPerSecond = 1_000_000, p2pGate = LinkGate(), p2pBytesPerSecond = 8_000_000).use { phones ->
                val files = listOf(MemorySource("album.zip", bytes(24 * mib + 17, 3)))
                withTimeout(90_000) {
                    val (sending, receiving) = phones.start(files)
                    val headStart = receiving.progress.first { it.bytesDone > 0 }
                    assertEquals(TransferPhase.STREAMING_BLUETOOTH, headStart.phase)
                    phones.lanGate.open()

                    val receiverRunner = phones.receiverBridge.runner.filterNotNull().first()
                    val senderRunner = phones.senderBridge.runner.filterNotNull().first()
                    val standby = receiverRunner.state.first { it.attempts[0].status == AttemptStatus.STANDBY }
                    assertTrue(standby.hints.any { it.code == HintCode.LAN_SLOW }, "lan_slow while a direct link is tried")
                    assertEquals(LinkMode.LAN, standby.dataCandidate?.mode, "the slow LAN keeps carrying data meanwhile")
                    phones.p2pGate.open()

                    val selected = receiverRunner.state.first { it.phase == LadderPhase.ACTIVE && it.attempts[0].status.isEnded }
                    assertEquals(LinkMode.P2P, selected.dataCandidate?.mode)
                    assertEquals(LinkEndReason.SUPERSEDED, selected.attempts[0].endReason)
                    assertEquals(5180, selected.freqMhz)
                    val followed = senderRunner.state.first { it.phase == LadderPhase.ACTIVE }
                    assertEquals(LinkMode.P2P, followed.dataCandidate?.mode)

                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    phones.awaitLaddersClosed()
                }

                // The receiver hosts the group (receiver tie-break) and announces it as generation 2 (P2P slot, attempt 0).
                val group = phones.senderEvents.linkReady.single { it.generation == 2 }
                assertEquals(LinkKind.P2P, group.linkKind)
                assertEquals(phones.receiverP2p.links.single().localPort, group.port)
                assertEquals(5180, group.freqMhz)
                assertEquals(LinkKind.P2P, phones.receiverEvents.linkReady.single { it.generation == 2 }.linkKind)
                assertTrue(phones.senderP2p.links.single().streams >= 1, "the joiner opened the group's StreamOpen streams")

                // The control stream went from Bluetooth straight to the group: the LAN was never selected.
                assertEquals(listOf(2), phones.senderEvents.moved.map { it.generation }.distinct())
                assertEquals(listOf(2), phones.receiverEvents.moved.map { it.generation }.distinct())

                assertTrue(phones.receiverEvents.bytes(LinkKind.LAN) > 0)
                assertTrue(phones.receiverEvents.bytes(LinkKind.P2P) > 0)
                assertEquals(sha256(files[0].bytes), sha256(phones.received("album.zip")))
                val all = phones.senderLan.links + phones.receiverLan.links + phones.senderP2p.links + phones.receiverP2p.links
                assertTrue(all.all { it.tornDown }, "every link is torn down (F-E11)")
            }
        }

    // ---- Harness ----

    /** Records what the engine reports and passes it on to the bridge. */
    private class Recorder(
        private val delegate: TransferLinkListener,
    ) : TransferLinkListener {
        val linkReady = CopyOnWriteArrayList<LinkReady>()
        val moved = CopyOnWriteArrayList<ControlMoved>()
        private val samples = ConcurrentHashMap<LinkKind, AtomicLong>()

        fun bytes(kind: LinkKind): Long = samples[kind]?.get() ?: 0

        override fun onPeerLinkReady(message: LinkReady) {
            linkReady += message
            delegate.onPeerLinkReady(message)
        }

        override fun onPeerControlMoved(message: ControlMoved) {
            moved += message
            delegate.onPeerControlMoved(message)
        }

        override fun onThroughputSample(
            kind: LinkKind,
            bytes: Long,
        ) {
            samples.getOrPut(kind) { AtomicLong() }.addAndGet(bytes)
            delegate.onThroughputSample(kind, bytes)
        }

        override fun onLinkLost(
            kind: LinkKind,
            generation: Int,
        ) = delegate.onLinkLost(kind, generation)

        override fun onSessionStarted(epoch: Int) = delegate.onSessionStarted(epoch)

        override fun onSessionLost(epoch: Int) = delegate.onSessionLost(epoch)

        override fun onTransferStarted() = delegate.onTransferStarted()

        override fun onTransferEnded(cancelled: Boolean) = delegate.onTransferEnded(cancelled)
    }

    /**
     * Two flagship phones on the same router (the LAN is reachable, both can host a 5 GHz group). The sender hosts the
     * LAN (S5); the receiver wins the group-owner tie-break and brings the Wi-Fi Direct credentials in its `Accept`.
     */
    private class Phones(
        lanBytesPerSecond: Long?,
        val p2pGate: LinkGate,
        p2pBytesPerSecond: Long? = null,
        lanMinBytesPerSecond: Long = ProtocolConstants.LAN_MIN_BPS,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lanGate = LinkGate()
        val senderLan = LoopbackLinkProvider(LinkKind.LAN, setOf(LinkMode.LAN), lanGate, bytesPerSecond = lanBytesPerSecond)
        val receiverLan = LoopbackLinkProvider(LinkKind.LAN, setOf(LinkMode.LAN), lanGate, bytesPerSecond = lanBytesPerSecond)
        val senderP2p = LoopbackLinkProvider(LinkKind.P2P, P2P_MODES, p2pGate, frequencyMhz = 5180, bytesPerSecond = p2pBytesPerSecond)
        val receiverP2p = LoopbackLinkProvider(LinkKind.P2P, P2P_MODES, p2pGate, frequencyMhz = 5180, bytesPerSecond = p2pBytesPerSecond)
        private val dir: Path = Files.createTempDirectory("drop-ladder-e2e-")
        private val receiverStore = DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"))
        private val senderStore = DirectoryFileStore(dir.resolve("sender-partials"), dir.resolve("sender-received"))
        private val lifecycle =
            LinkLifecycle(
                LadderTimeouts(
                    lanConnectMillis = STRETCHED_MILLIS,
                    lanMinBytesPerSecond = lanMinBytesPerSecond,
                    p2pFormationMillis = STRETCHED_MILLIS,
                    hotspotMillis = STRETCHED_MILLIS,
                ),
            )

        lateinit var senderBridge: LadderTransferBridge
        lateinit var receiverBridge: LadderTransferBridge
        lateinit var senderEvents: Recorder
        lateinit var receiverEvents: Recorder

        private fun plan(role: TransferRole): LadderPlan =
            LadderPlanner.plan(LadderInput(phone(), phone(), role, lanReachable = true, peerName = "Peer"))

        /** Handshake over the paced Bluetooth primary, `Offer`, `Accept`; the ladders start with the streaming. */
        suspend fun start(files: List<SourceFile>): Pair<Transfer, Transfer> {
            val senderPlan = plan(TransferRole.SENDER)
            val receiverPlan = plan(TransferRole.RECEIVER)
            assertEquals(listOf(LinkMode.LAN, LinkMode.P2P, LinkMode.HOTSPOT, LinkMode.BLUETOOTH), senderPlan.candidates.map { it.mode })
            assertTrue(senderPlan.parallelLanProbe, "the LAN probe races the group formation (N9)")

            val (senderSession, receiverSession) = bluetoothSessions()
            val sending =
                engine("Sender", senderStore).send(
                    senderSession,
                    files,
                    SendOptions(linkOptions = LadderNegotiation.offerOptions(senderPlan, p2pCredentials = null)),
                )
            senderBridge =
                LadderTransferBridge(sending, scope) { session, base ->
                    val agreement =
                        LadderNegotiation.adopt(senderPlan, null, sending.accept.value?.link) {
                            error("the receiver brings the group credentials")
                        }
                    LadderRunner(
                        agreement.plan,
                        listOf(senderLan, senderP2p),
                        session,
                        scope,
                        SystemMonotonicClock,
                        agreement.config(base),
                        lifecycle,
                    )
                }
            senderEvents = Recorder(senderBridge)
            sending.linkListener = senderEvents

            val incoming = engine("Receiver", receiverStore).receive(receiverSession)
            val decision = LadderNegotiation.accept(receiverPlan, incoming.offer.linkOptions) { TEST_CREDENTIALS }
            receiverBridge =
                LadderTransferBridge(incoming.transfer, scope) { session, base ->
                    val agreement = decision.agreement
                    LadderRunner(
                        agreement.plan,
                        listOf(receiverLan, receiverP2p),
                        session,
                        scope,
                        SystemMonotonicClock,
                        agreement.config(base),
                        lifecycle,
                    )
                }
            receiverEvents = Recorder(receiverBridge)
            incoming.transfer.linkListener = receiverEvents
            val receiving = incoming.accept(link = decision.intent)
            return sending to receiving
        }

        /** Both ladders tore their links down and restored the network after the transfer ended (F-E11). */
        suspend fun awaitLaddersClosed() {
            val states = ArrayList<LadderState>()
            for (bridge in listOf(senderBridge, receiverBridge)) states += bridge.runner.filterNotNull().first().awaitClosed()
            assertTrue(states.all { it.phase == LadderPhase.CLOSED })
        }

        fun received(name: String): Path = dir.resolve("received").resolve(name)

        private fun engine(
            name: String,
            store: DirectoryFileStore,
        ): TransferEngine = TransferEngine(EngineConfig(sessionConfig(name), store, InMemoryResumeStore(), lingerMillis = 300), scope)

        private suspend fun bluetoothSessions(): Pair<SecureSession, SecureSession> {
            val (a, b) = InMemoryDataChannel.pair(LinkKind.BLUETOOTH, capacitySegments = 16, maxSegment = 16 * 1024)
            val senderEnd: DataChannel = PacedChannel(a, BLUETOOTH_BYTES_PER_SECOND)
            val receiverEnd: DataChannel = PacedChannel(b, BLUETOOTH_BYTES_PER_SECOND)
            val senderConfig = sessionConfig("Sender")
            val receiverConfig = sessionConfig("Receiver")
            return coroutineScope {
                val initiator = async { SessionHandshake.initiate(senderEnd, senderConfig) }
                val responder = async { SessionHandshake.respond(receiverEnd, receiverConfig) }
                initiator.await() to responder.await()
            }
        }

        override fun close() {
            scope.cancel()
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }

        private companion object {
            val P2P_MODES = setOf(LinkMode.P2P, LinkMode.P2P_LEGACY)
            const val STRETCHED_MILLIS = 20_000L
            const val BLUETOOTH_BYTES_PER_SECOND = 100_000L
            val crypto: CryptoProvider = JcaCryptoProvider()

            fun sessionConfig(name: String): SessionConfig =
                SessionConfig(
                    crypto,
                    identity(),
                    LocalPeerInfo(0, name, 0, AeadAlgorithm.AES_256_GCM),
                    HandshakeGuard(),
                    TrustedPeerLookup.NONE,
                )

            fun identity(): IdentityKey {
                val pair = crypto.generateEd25519()
                return object : IdentityKey {
                    override val publicKey: ByteArray = pair.publicKey

                    override fun sign(message: ByteArray): ByteArray = crypto.ed25519Sign(pair.privateKey, message)
                }
            }
        }
    }

    private class MemorySource(
        override val name: String,
        val bytes: ByteArray,
    ) : SourceFile {
        override val size: Long get() = bytes.size.toLong()
        override val mimeType: String? get() = null

        override suspend fun read(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }

        override suspend fun close() = Unit
    }

    private fun bytes(
        size: Int,
        seed: Long,
    ): ByteArray = Random(seed).nextBytes(size)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))
}
