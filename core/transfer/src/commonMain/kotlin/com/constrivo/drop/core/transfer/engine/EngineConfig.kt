package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.protocol.TransferTimeouts
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.hash.ChunkHasher
import com.constrivo.drop.core.transfer.hash.xxh3ChunkHasher
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.SessionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Everything a [TransferEngine] needs, all injectable for tests (virtual time: pass the test dispatcher as both
 * dispatchers and its `currentTime` as [clock]).
 *
 * @property session how to run the handshake again on a reconnect (N3) and what to announce.
 * @property fileStore sources (sender) and partials plus the destination (receiver).
 * @property resumeStore the receiver's durable resume state (N5); the app adapts `core/data` to it.
 * @property io dispatcher for file and socket I/O.
 * @property compute dispatcher for hashing and AEAD work.
 * @property power thermal signal (F-F6); null means always cool.
 * @property reverifyOnResume re-hash every unit a resumed receiver has on disk against its stored XXH3 before accepting
 *   (N5), so a `.part` damaged by a crash is requested again instead of failing the whole-file check.
 * @property lingerMillis after its last message, the side that sent it waits this long for the peer to close first, so
 *   the final `Complete` or `Cancel` is not lost to a reset connection.
 */
class EngineConfig(
    val session: SessionConfig,
    val fileStore: FileStore,
    val resumeStore: ResumeStore = InMemoryResumeStore(),
    val clock: TransferClock = TransferClock.SYSTEM,
    val io: CoroutineDispatcher = defaultIoDispatcher(),
    val compute: CoroutineDispatcher = Dispatchers.Default,
    val power: PowerPolicy? = null,
    val timeouts: TransferTimeouts = TransferTimeouts(),
    val chunkHasher: ChunkHasher = xxh3ChunkHasher(),
    val reverifyOnResume: Boolean = true,
    val lingerMillis: Long = DEFAULT_LINGER_MILLIS,
    /** Reducer states to persist (`transfer.status` and friends); called in the engine's actor, keep it quick. */
    val onPersist: (TransferState) -> Unit = {},
    /** A `TrustShare` from the peer (S3): store the peer's advertising secret. */
    val onTrustShare: (peerIdentityKey: ByteArray, TrustShare) -> Unit = { _, _ -> },
    /** Test hooks; never set in production. */
    val debug: DebugHooks = DebugHooks.NONE,
) {
    init {
        require(lingerMillis >= 0) { "linger must be non-negative" }
    }

    companion object {
        const val DEFAULT_LINGER_MILLIS: Long = 2_000
    }
}

/**
 * Fault injection for the integration tests (T-28). [corruptPlaintext] runs on the sender after a frame's XXH3 was
 * computed and before it is sealed, so a corruption it makes passes the AEAD and must be caught by the receiver's
 * per-frame hash; return true after changing the payload. [beforeChunkSealed] can throw (for example a
 * `FrameLimitException`) to simulate a key reaching its limit.
 */
class DebugHooks(
    val corruptPlaintext: (
        unit: TransferUnit,
        blockOffset: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) -> Boolean = { _, _, _, _, _ -> false },
    val beforeChunkSealed: (streamId: Int) -> Unit = {},
) {
    companion object {
        val NONE: DebugHooks = DebugHooks()
    }
}

/**
 * Where a lost primary connection comes back from (N3): the handshake initiator dials the peer again (Bluetooth,
 * the LAN endpoints behind `EndpointDialer`), the responder waits for the next inbound connection. The engine runs the
 * handshake itself, with the peer's identity as the expected identity, on whatever [next] returns.
 */
fun interface PrimaryLinkSource {
    /** A new raw channel to the peer; throws when this attempt failed (the engine retries with back-off). */
    suspend fun next(): DataChannel
}

/** How a [DataLink] gets its connections. */
enum class DataLinkRole {
    /** This device joined the link: it connects to the host and opens the streams (`StreamOpen`). */
    CONNECT,

    /** This device hosts the link: it accepts the streams the joiner opens. */
    ACCEPT,
}

/**
 * A Wi-Fi (or LAN) link that is up, as the transfer engine uses it (architecture §7.4, §7.5): the ladder, or a test,
 * hands it over and the engine opens its data streams. [open] connects a new TCP connection to the host (on
 * [DataLinkRole.CONNECT]) or accepts the next one (on [DataLinkRole.ACCEPT]), with sockets bound to the link's network
 * and the socket options of §7.4 (4 MiB buffers). [generation] is the `LinkReady` generation both devices use.
 */
class DataLink(
    val kind: LinkKind,
    val generation: Int,
    val role: DataLinkRole,
    val freqMhz: Int = 0,
    val open: suspend () -> DataChannel,
) {
    init {
        require(kind != LinkKind.BLUETOOTH) { "Bluetooth is the primary connection, not a data link" }
        require(generation >= 0) { "generation must be non-negative" }
        require(freqMhz >= 0) { "frequency must be non-negative" }
    }

    override fun toString(): String = "DataLink($kind, gen $generation, $role)"
}

/**
 * Link events for the transport ladder (architecture §4; WP5 carry-forward). The ladder adapter in `core/ladder`
 * implements this and forwards to its `LadderRunner`. Called from the engine's coroutines; keep them quick.
 */
interface TransferLinkListener {
    /** The peer announced a link (§7.2). */
    fun onPeerLinkReady(message: LinkReady) {}

    /** The peer moved its control stream to a link (N13); on the sender this is the receiver's link selection. */
    fun onPeerControlMoved(message: ControlMoved) {}

    /** [bytes] arrived (receiver) or were acked (sender) over [kind] in the last 250 ms sample (§7.4). */
    fun onThroughputSample(
        kind: LinkKind,
        bytes: Long,
    ) {}

    /** Every data stream of link [generation] ([kind]) is gone. */
    fun onLinkLost(
        kind: LinkKind,
        generation: Int,
    ) {}

    /** A new session (after the first handshake or a reconnect, N3) carries the transfer; old links are gone. */
    fun onSessionStarted(epoch: Int) {}

    /** The session was lost; its links are closed. */
    fun onSessionLost(epoch: Int) {}

    /** The transfer was accepted and streams now. */
    fun onTransferStarted() {}

    /** The transfer ended ([cancelled] for every end but `Done`). */
    fun onTransferEnded(cancelled: Boolean) {}
}

/** Engine-wide limits that are not protocol constants. */
object EngineLimits {
    /** Plaintext send buffers: enough for every stream to have one unit sealing or waiting (§15 memory budget). */
    const val SEND_BUFFERS: Int = 4

    /** Receive buffers: the 16 MiB write queue of §7.4 in whole frames, plus one being read. */
    const val RECEIVE_BUFFERS: Int = ProtocolConstants.WRITE_QUEUE_BYTES / ProtocolConstants.CHUNK_SIZE + 1

    /** Files verified (SHA-256 of the `.part`) at the same time. */
    const val VERIFY_CONCURRENCY: Int = 2

    /** Write-behind: the resume manifest is flushed at most this long after a unit's write (N5). */
    const val FLUSH_MILLIS: Long = 100

    /** Heartbeats and the watchdog re-arm at most this often. */
    const val WATCHDOG_FEED_MILLIS: Long = 1_000

    /** Control messages buffered from a stream that is not (yet) the peer's control route. */
    const val MAX_BUFFERED_CONTROL: Int = 4096

    /** A thermal hint from the peer counts for this long (the hot device repeats it every [THERMAL_HINT_REPEAT_MILLIS]). */
    const val PEER_THERMAL_MILLIS: Long = 45_000
    const val THERMAL_HINT_REPEAT_MILLIS: Long = 20_000

    /** Reconnect back-off (N3): first retry after this, doubling up to [RECONNECT_BACKOFF_MAX_MILLIS]. */
    const val RECONNECT_BACKOFF_MILLIS: Long = 500
    const val RECONNECT_BACKOFF_MAX_MILLIS: Long = 8_000

    /** The sender waits this long for the receiver's `Resume` after a reconnect before giving the session up. */
    const val RESUME_WAIT_MILLIS: Long = 15_000
}
