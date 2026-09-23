package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.AppIdentity
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Why a [ReceiveServer] stopped. */
enum class StopReason {
    /** [ReceiveSettings.idleTimeoutMillis] passed after the last transfer, with nothing in progress. */
    IDLE,

    /** The app called [ReceiveServer.stop]. */
    STOPPED,
}

/**
 * The embedded HTTP server of the browser receive page (architecture §10.3): Ktor CIO serving one [ReceiveSession],
 * bound to [bindAddress] only, the phone's address on its hotspot or Wi‑Fi Direct group (or a desktop's LAN address
 * for the no-Bluetooth PC path, F‑H4). The wildcard address is refused, so the page is never reachable from another
 * network the device is on.
 *
 * The server stops by itself when the session goes idle ([StopReason.IDLE], 60 s after the last transfer) and revokes
 * the token as it stops. Android cannot bind ports below 1024 without root, so the page URL carries the port:
 * `http://drop.local:<port>/t/<token>/` ([url]).
 *
 * Typical use: create the session and the server, [start] it, show [url] in the QR code, start an
 * [com.constrivo.drop.web.mdns.MdnsResponder] on the same interface, and tear both down when [awaitStopped] returns.
 */
class ReceiveServer(
    val session: ReceiveSession,
    private val bindAddress: InetAddress,
    private val port: Int = 0,
) {
    init {
        require(!bindAddress.isAnyLocalAddress) { "bind to the link interface address, never the wildcard address" }
        require(port in 0..MAX_PORT) { "port out of range" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val stopped = CompletableDeferred<StopReason>()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var watchdog: Job? = null

    /** The bound address and port, once [start] returned. */
    @Volatile
    var boundAddress: InetSocketAddress? = null
        private set

    /**
     * Binds and starts serving; returns the bound address (the actual port when [port] was 0).
     *
     * @throws IllegalStateException when started twice or after stopping.
     * @throws java.io.IOException when the address cannot be bound.
     */
    suspend fun start(): InetSocketAddress =
        lifecycle.withLock {
            check(server == null && !stopped.isCompleted) { "a receive server starts once" }
            val engine = embeddedServer(CIO, port = port, host = bindAddress.hostAddress) { receiveModule(session) }
            engine.startSuspend(wait = false)
            server = engine
            val actualPort = engine.engine.resolvedConnectors().first().port
            val bound = InetSocketAddress(bindAddress, actualPort)
            boundAddress = bound
            watchdog =
                scope.launch {
                    session.awaitIdle()
                    // awaitIdle also returns when the app closed the session itself.
                    stop(if (session.isClosed) StopReason.STOPPED else StopReason.IDLE)
                }
            bound
        }

    /** The page URL for [host] (the mDNS name by default; pass the IP address for the fallback line). */
    fun url(host: String = AppIdentity.MDNS_HOST): String {
        val bound = checkNotNull(boundAddress) { "not started" }
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$h:${bound.port}${session.pathPrefix}"
    }

    /** The page URL with the bound IP address, the fallback shown under the QR code. */
    fun ipUrl(): String {
        val address = checkNotNull(boundAddress) { "not started" }.address
        val host = address.hostAddress.let { if (address is Inet6Address) it.substringBefore('%') else it }
        return url(host)
    }

    /** Stops serving and revokes the token. Idempotent. */
    suspend fun stop() = stop(StopReason.STOPPED)

    /** Suspends until the server stopped, and returns why. */
    suspend fun awaitStopped(): StopReason = stopped.await()

    private suspend fun stop(reason: StopReason) {
        lifecycle.withLock {
            if (stopped.isCompleted) return
            session.close()
            server?.stopSuspend(GRACE_MILLIS, TIMEOUT_MILLIS)
            server = null
            stopped.complete(reason)
        }
        if (reason != StopReason.IDLE) watchdog?.cancel()
        scope.cancel()
    }

    private companion object {
        const val MAX_PORT = 65535
        const val GRACE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 2000L
    }
}
