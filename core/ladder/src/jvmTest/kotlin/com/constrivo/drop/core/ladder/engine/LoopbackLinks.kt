package com.constrivo.drop.core.ladder.engine

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Holds a provider's `host` / `join` until [open]: a group that forms, or a network that is joined, only then. */
internal class LinkGate(
    opened: Boolean = false,
) {
    private val signal = CompletableDeferred<Unit>().also { if (opened) it.complete(Unit) }

    /** How many provider calls were cancelled while they waited (a rung the ladder stopped before it came up). */
    val cancelled = AtomicInteger()

    fun open() {
        signal.complete(Unit)
    }

    suspend fun await() {
        try {
            signal.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }
}

/**
 * A [WifiLinkProvider] for [modes] whose links are loopback TCP: the host listens on 127.0.0.1, the joiner connects to
 * the address and port of the host's `LinkReady`. [frequencyMhz] is what the platform would report for the channel;
 * [bytesPerSecond] paces every stream's writes (a slow network). Every link it brings up is kept in [links].
 */
internal class LoopbackLinkProvider(
    override val kind: LinkKind,
    private val modes: Set<LinkMode>,
    private val gate: LinkGate = LinkGate(opened = true),
    private val frequencyMhz: Int? = null,
    private val bytesPerSecond: Long? = null,
) : WifiLinkProvider {
    val links = CopyOnWriteArrayList<LoopbackLink>()

    override fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean = mode in modes

    override suspend fun host(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        gate.await()
        val link =
            LoopbackLink(kind, request.mode, LinkRole.HOST, frequencyMhz, request.credentials, TcpListener(kind = kind), bytesPerSecond)
        links += link
        onUp(link)
        return link
    }

    override suspend fun join(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        gate.await()
        val link = LoopbackLink(kind, request.mode, LinkRole.JOIN, frequencyMhz, null, null, bytesPerSecond)
        links += link
        onUp(link)
        return link
    }
}

/** One loopback link; [teardown] closes the listener and every stream, as leaving a group would. */
internal class LoopbackLink(
    override val kind: LinkKind,
    override val mode: LinkMode,
    override val role: LinkRole,
    override val frequencyMhz: Int?,
    override val credentials: WifiCredentials?,
    private val listener: TcpListener?,
    private val bytesPerSecond: Long?,
) : ActiveLink {
    private val channels = CopyOnWriteArrayList<DataChannel>()

    @Volatile
    var tornDown = false
        private set

    /** Streams this device opened (joiner) or accepted (host) over the link. */
    val streams: Int get() = channels.size

    override val localAddress: String? = listener?.let { "127.0.0.1" }
    override val localPort: Int? = listener?.port

    override suspend fun connect(
        address: String,
        port: Int,
    ): DataChannel = track(TcpDataChannel.connect(address, port, kind))

    override suspend fun accept(port: Int): DataChannel {
        val server = checkNotNull(listener) { "a joined link accepts nothing" }
        require(port == localPort) { "port $port is not this link's $localPort" }
        return track(server.accept())
    }

    override suspend fun teardown() {
        tornDown = true
        listener?.close()
        for (channel in channels) channel.close()
    }

    private suspend fun track(channel: DataChannel): DataChannel {
        val tracked = bytesPerSecond?.let { PacedChannel(channel, it) } ?: channel
        channels += tracked
        if (tornDown) {
            tracked.close()
            throw java.io.IOException("the link is gone")
        }
        return tracked
    }

    override fun toString(): String = "LoopbackLink($mode, $role, port ${listener?.port})"
}

/**
 * Paces writes at [bytesPerSecond] in 16 KiB slices: a Bluetooth RFCOMM socket, or a slow network. Reads pass through;
 * the peer's writes are paced at its end.
 */
internal class PacedChannel(
    private val delegate: DataChannel,
    private val bytesPerSecond: Long,
) : DataChannel by delegate {
    private val lock = Mutex()
    private var credit = 0.0

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) = lock.withLock {
        var from = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, SLICE)
            delegate.write(buffer, from, n)
            from += n
            left -= n
            credit += n * 1000.0 / bytesPerSecond
            val wait = credit.toLong()
            if (wait > 0) {
                delay(wait)
                credit -= wait
            }
        }
    }

    private companion object {
        const val SLICE = 16 * 1024
    }
}
