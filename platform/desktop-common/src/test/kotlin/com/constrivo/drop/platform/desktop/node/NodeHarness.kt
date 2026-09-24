package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.FileSecretStorage
import com.constrivo.drop.platform.desktop.lan.InMemoryLanNetwork
import com.constrivo.drop.platform.desktop.lan.RebindableLanDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Two or more [DesktopNode]s in one JVM on loopback, found through an [InMemoryLanNetwork] (no multicast needed). A
 * node started again under the same name uses the same directories, so it is the same device after an app restart.
 * A [proxied] node announces a [TcpProxy] in front of its control port, which a test can throttle and cut.
 */
internal class NodeHarness(
    val root: Path = Files.createTempDirectory("drop-node-"),
) : AutoCloseable {
    val network = InMemoryLanNetwork()
    private val nodes = ArrayList<DesktopNode>()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val problemWatchers = ArrayList<Job>()
    private val proxies = ConcurrentHashMap<String, TcpProxy>()

    /** Every problem the nodes reported, for assertions and failure messages. */
    val problems: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    fun directories(name: String): AppDirectories {
        val base = root.resolve(name)
        return AppDirectories(base.resolve("config"), base.resolve("data"), base.resolve("cache"), base.resolve("Received"))
    }

    /** The proxy in front of [name]'s control port (a [node] started with `proxied`). */
    fun proxy(name: String): TcpProxy = checkNotNull(proxies[name]) { "$name is not proxied" }

    /**
     * A node called [name] on [address] (loopback) with tuning suited to tests (short linger, finished transfers kept).
     * With [proxied], the address others dial is a [TcpProxy] limited to [proxyBytesPerSecond] in each direction;
     * [wrapLan] can put a test double around its mDNS.
     */
    suspend fun node(
        name: String,
        tuning: NodeTuning = TEST_TUNING,
        visibility: Visibility = Visibility.EVERYONE,
        proxied: Boolean = false,
        proxyBytesPerSecond: Long = Long.MAX_VALUE,
        address: InetAddress = InetAddress.getLoopbackAddress(),
        wrapLan: (RebindableLanDiscovery) -> RebindableLanDiscovery = { it },
    ): DesktopNode {
        val dirs = directories(name)
        val participant = network.participant()
        val lan =
            wrapLan(
                if (!proxied) {
                    participant
                } else {
                    ProxiedLan(participant) { port ->
                        proxies.compute(name) { _, existing ->
                            (existing ?: TcpProxy(port, proxyBytesPerSecond)).also { it.target = port }
                        }!!.port
                    }
                },
            )
        val node =
            DesktopNode(
                DesktopNodeConfig(
                    directories = dirs,
                    secrets = FileSecretStorage(dirs.secrets),
                    lan = lan,
                    lanAddress = address,
                    defaultNickname = name,
                    platform = DevicePlatform.LAPTOP,
                    tuning = tuning,
                ),
            )
        problemWatchers +=
            watcherScope.launch {
                node.events.collect { e ->
                    if (e is NodeEvent.Problem) {
                        problems += "$name: ${e.message}: ${e.error}"
                        println("[$name] ${e.message}: ${e.error?.stackTraceToString()}")
                    }
                }
            }
        node.start()
        node.setVisibility(visibility)
        nodes += node
        return node
    }

    override fun close() {
        for (node in nodes) runCatching { node.close() }
        for (proxy in proxies.values) runCatching { proxy.close() }
        watcherScope.cancel()
        runCatching { Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Announces the proxy's port in place of the node's own, in the SRV record and the TXT `port` key alike. */
    private class ProxiedLan(
        private val delegate: RebindableLanDiscovery,
        private val proxyPortFor: (Int) -> Int,
    ) : RebindableLanDiscovery {
        override suspend fun announce(service: LanService) {
            val port = proxyPortFor(service.port)
            delegate.announce(service.copy(port = port, txt = service.txt + (MdnsRecord.KEY_PORT to port.toString())))
        }

        override suspend fun withdraw() = delegate.withdraw()

        override fun browse(): Flow<LanEvent> = delegate.browse()

        override suspend fun rebind(address: InetAddress) = delegate.rebind(address)
    }

    companion object {
        val TEST_TUNING = NodeTuning(finishedRetentionMillis = 600_000, lingerMillis = 300)

        /** For tests that shape the primary link: every byte goes over it (no LAN rung), reconnects are quick. */
        val PRIMARY_ONLY = TEST_TUNING.copy(lanRung = false)

        /** Waits until [node]'s radar shows a device called [nickname]. */
        suspend fun seen(
            node: DesktopNode,
            nickname: String,
        ): NearbyDevice = node.devices.first { list -> list.any { it.nickname == nickname } }.first { it.nickname == nickname }

        /** Waits until transfer [id] on [node] satisfies [condition]. */
        suspend fun transfer(
            node: DesktopNode,
            id: String,
            condition: (NodeTransfer) -> Boolean,
        ): NodeTransfer = node.transfers.first { list -> list.any { it.id == id && condition(it) } }.first { it.id == id }

        fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * A loopback TCP proxy to [target] for the node tests: every connection is forwarded both ways at most
 * [bytesPerSecond] per direction, [cut] resets the connections open now (a Wi‑Fi drop), and [target] can move (the
 * node restarted on a new port; the proxy's own port stays, like a peer that keeps its address).
 */
internal class TcpProxy(
    @Volatile var target: Int,
    private val bytesPerSecond: Long = Long.MAX_VALUE,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val open = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    /** Connections forwarded so far. */
    @Volatile
    var accepted: Int = 0
        private set

    init {
        thread(isDaemon = true, name = "proxy-$port") { acceptLoop() }
    }

    /** Resets every connection open now; new ones are forwarded again. */
    fun cut() {
        for (socket in open) runCatching { socket.close() }
        open.clear()
    }

    override fun close() {
        runCatching { server.close() }
        cut()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            val upstream =
                try {
                    Socket(InetAddress.getLoopbackAddress(), target)
                } catch (_: IOException) {
                    runCatching { client.close() }
                    continue
                }
            accepted++
            open += client
            open += upstream
            pump(client, upstream)
            pump(upstream, client)
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
    ) {
        thread(isDaemon = true) {
            val buffer = ByteArray(if (bytesPerSecond == Long.MAX_VALUE) 64 * 1024 else 16 * 1024)
            val started = System.nanoTime()
            var moved = 0L
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    moved += n
                    if (bytesPerSecond != Long.MAX_VALUE) {
                        val due = moved * 1_000_000_000L / bytesPerSecond
                        val ahead = due - (System.nanoTime() - started)
                        if (ahead > 0) Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
                    }
                }
            } catch (_: IOException) {
                // cut or closed
            } catch (_: InterruptedException) {
                // closed
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
                open.remove(from)
                open.remove(to)
            }
        }
    }

    private companion object {
        const val BACKLOG = 50
    }
}
