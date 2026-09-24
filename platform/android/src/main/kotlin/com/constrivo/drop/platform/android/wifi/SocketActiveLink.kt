package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.LowLatencyChannel
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import com.constrivo.drop.core.transfer.net.TcpSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** What a link is on this device, for logs, the UI and the lab's per-device table. */
sealed interface LinkDetails {
    /**
     * A Wi-Fi Direct group: its [interfaceName] (`p2p-wlan0-0`), the group owner's address, the measured band, whether
     * it was persistent, and which formation of the rung it was ([attempt], 1 for the §9 re-form).
     */
    data class WifiDirectGroup(
        val interfaceName: String?,
        val groupOwnerAddress: String?,
        val band: WifiBand?,
        val persistent: Boolean,
        val attempt: Int,
    ) : LinkDetails

    /**
     * A local-only hotspot this device hosts, with the system-chosen settings (N8, N15). [displacesStation]: this phone
     * was on Wi-Fi without STA/AP concurrency, so its station is down while the hotspot runs and returns on teardown.
     */
    data class Hotspot(
        val hotspot: HotspotDetails,
        val interfaceName: String?,
        val displacesStation: Boolean,
    ) : LinkDetails

    /**
     * A network joined with a `WifiNetworkSpecifier` (a hotspot, or a phone's group as a legacy client): its SSID, the
     * `Network` handle and interface, and the security the request used.
     */
    data class JoinedNetwork(
        val ssid: String,
        val networkHandle: Long,
        val interfaceName: String?,
        val security: SpecifierSecurity,
    ) : LinkDetails

    /** The LAN: the station (or Ethernet) network's handle and interface. */
    data class Lan(
        val networkHandle: Long,
        val interfaceName: String?,
    ) : LinkDetails
}

/**
 * Binds the listener of a hosted link (a seam: JVM tests bind loopback where a phone binds its interface address).
 */
fun interface LinkListenerFactory {
    /** A listener on [address] (port 0: ephemeral) for streams of [kind], doing its I/O on [io]. */
    fun bind(
        address: InetSocketAddress,
        kind: LinkKind,
        io: CoroutineDispatcher,
    ): TcpListener

    companion object {
        /** [TcpListener] on the given address. */
        val DEFAULT: LinkListenerFactory = LinkListenerFactory { address, kind, io -> TcpListener(address, kind, io) }
    }
}

/**
 * The [ActiveLink] of every Android Wi-Fi rung (architecture §7.4, §8): TCP streams over one link, every socket from
 * the link's [NetworkBoundSocketFactory] and every dialled address checked by the link's [LinkDialPolicy].
 *
 * - **Host** ([listenAddress] given): a [TcpListener] bound to the link interface's address with an ephemeral port (never
 *   the wildcard, so nothing arrives over another network); [localAddress] and [localPort] are what the ladder's
 *   `LinkReady` announces, and [accept] takes the peer's streams.
 * - **Join**: [connect] dials the host's `LinkReady` address.
 *
 * Streams are [TcpDataChannel]s with the §7.4 options (4 MiB buffers, `TCP_NODELAY` only where the engine asks, through
 * [LowLatencyChannel]). [teardown] closes the listener and every stream this link opened or accepted, then runs
 * [release], the provider's radio teardown (remove the group, close the hotspot reservation, release the network
 * request, wait for the previous Wi-Fi, F-E11). It runs once; concurrent and later calls wait for that one and never
 * throw. Watchers the provider starts with [watch] (group or network loss, channel changes) end when the teardown starts,
 * so a link's own teardown is never reported as a loss.
 *
 * @param listenerFactory binds the host's listener ([LinkListenerFactory.DEFAULT]; JVM tests bind loopback).
 * @throws IOException from the constructor when the listener cannot be bound (the interface address is gone).
 */
class SocketActiveLink(
    override val kind: LinkKind,
    override val mode: LinkMode,
    override val role: LinkRole,
    override val frequencyMhz: Int?,
    override val credentials: WifiCredentials?,
    val details: LinkDetails,
    listenAddress: InetAddress?,
    private val socketFactory: TcpSocketFactory,
    private val dialPolicy: LinkDialPolicy,
    private val release: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    listenerFactory: LinkListenerFactory = LinkListenerFactory.DEFAULT,
) : ActiveLink {
    init {
        require(mode.kind == kind) { "mode $mode is not a $kind link" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
        require(listenAddress == null || !listenAddress.isAnyLocalAddress) { "listen on the link's address, never the wildcard" }
    }

    private val listener: TcpListener? = listenAddress?.let { listenerFactory.bind(InetSocketAddress(it, 0), kind, io) }
    private val channels = CopyOnWriteArrayList<TrackedChannel>()
    private val tornDown = AtomicBoolean(false)
    private val lostFlag = AtomicBoolean(false)
    private val done = CompletableDeferred<Unit>()
    private val watchers = CoroutineScope(SupervisorJob() + io)

    override val localAddress: String? = listener?.let { listenAddress?.hostAddress }
    override val localPort: Int? = listener?.port

    /** True once [teardown] started. */
    val isTornDown: Boolean get() = tornDown.get()

    /** True once the provider saw the link go away ([markLost]). */
    val isLost: Boolean get() = lostFlag.get()

    /** Streams opened or accepted on this link and not closed yet. */
    val openStreams: Int get() = channels.size

    override suspend fun connect(
        address: String,
        port: Int,
    ): DataChannel {
        ensureUsable()
        if (port !in 1..MAX_PORT) throw LinkAddressRefusedException("port $port out of range")
        val target = dialPolicy.check(address)
        val channel =
            TcpDataChannel.connect(
                host = target.hostAddress ?: throw LinkAddressRefusedException("the address $address has no text form"),
                port = port,
                kind = kind,
                timeoutMillis = connectTimeoutMillis,
                lowLatency = false,
                factory = socketFactory,
                io = io,
            )
        return track(channel)
    }

    override suspend fun accept(port: Int): DataChannel {
        val server = listener ?: throw IOException("a joined ${kind.wireName} link accepts no streams")
        if (port != server.port) throw IOException("port $port is not this link's ${server.port}")
        ensureUsable()
        return track(server.accept())
    }

    override suspend fun teardown() {
        if (!tornDown.compareAndSet(false, true)) {
            done.await()
            return
        }
        withContext(NonCancellable) {
            try {
                watchers.cancel()
                listener?.close()
                for (channel in channels) {
                    try {
                        channel.close()
                    } catch (_: Exception) {
                        // already gone
                    }
                }
                channels.clear()
                try {
                    release()
                } catch (_: Exception) {
                    // The provider reports its own release failures, and a timeout inside it is no cancellation of
                    // this (non-cancellable) teardown: a teardown never throws (ActiveLink contract).
                }
            } finally {
                done.complete(Unit)
            }
        }
    }

    /** The provider saw the link go away (group removed, hotspot stopped, network lost): new streams fail at once. */
    fun markLost(): Boolean = lostFlag.compareAndSet(false, true)

    /** Runs [block] until the teardown starts; the provider's watchers for loss and channel changes. */
    fun watch(block: suspend CoroutineScope.() -> Unit) {
        if (tornDown.get()) return
        watchers.launch(block = block)
    }

    private fun ensureUsable() {
        if (tornDown.get()) throw IOException("the ${kind.wireName} link is gone")
        if (lostFlag.get()) throw IOException("the ${kind.wireName} link was lost")
    }

    private suspend fun track(channel: TcpDataChannel): DataChannel {
        val tracked = TrackedChannel(channel)
        channels += tracked
        if (tornDown.get()) {
            channels.remove(tracked)
            channel.close()
            throw IOException("the ${kind.wireName} link is gone")
        }
        return tracked
    }

    override fun toString(): String = "SocketActiveLink($kind, $mode, $role, ${localAddress ?: "join"}:${localPort ?: "-"}, $details)"

    /** A stream of this link; forgets itself when closed, so a long transfer's list stays short. */
    private inner class TrackedChannel(
        private val inner: TcpDataChannel,
    ) : DataChannel by inner,
        LowLatencyChannel by inner {
        override suspend fun close() {
            channels.remove(this)
            inner.close()
        }

        override fun toString(): String = inner.toString()
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}
