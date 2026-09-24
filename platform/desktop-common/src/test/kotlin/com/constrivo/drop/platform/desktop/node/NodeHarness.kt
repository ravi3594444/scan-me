package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.FileSecretStorage
import com.constrivo.drop.platform.desktop.lan.InMemoryLanNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Two or more [DesktopNode]s in one JVM on loopback, found through an [InMemoryLanNetwork] (no multicast needed). */
internal class NodeHarness(
    val root: Path = Files.createTempDirectory("drop-node-"),
) : AutoCloseable {
    val network = InMemoryLanNetwork()
    private val nodes = ArrayList<DesktopNode>()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val problemWatchers = ArrayList<Job>()

    /** Every problem the nodes reported, for assertions and failure messages. */
    val problems: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    fun directories(name: String): AppDirectories {
        val base = root.resolve(name)
        return AppDirectories(base.resolve("config"), base.resolve("data"), base.resolve("cache"), base.resolve("Received"))
    }

    /** A node called [name] on 127.0.0.1 with tuning suited to tests (short linger, finished transfers kept). */
    suspend fun node(
        name: String,
        tuning: NodeTuning = TEST_TUNING,
        visibility: Visibility = Visibility.EVERYONE,
    ): DesktopNode {
        val dirs = directories(name)
        val node =
            DesktopNode(
                DesktopNodeConfig(
                    directories = dirs,
                    secrets = FileSecretStorage(dirs.secrets),
                    lan = network.participant(),
                    lanAddress = InetAddress.getLoopbackAddress(),
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
        watcherScope.cancel()
        runCatching { Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        val TEST_TUNING = NodeTuning(finishedRetentionMillis = 600_000, lingerMillis = 300)

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
