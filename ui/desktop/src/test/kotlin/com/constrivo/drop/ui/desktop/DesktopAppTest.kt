package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import com.constrivo.drop.platform.desktop.FileSecretStorage
import com.constrivo.drop.platform.desktop.lan.InMemoryLanNetwork
import com.constrivo.drop.platform.desktop.node.DesktopNode
import com.constrivo.drop.platform.desktop.node.DesktopNodeConfig
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTuning
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.HistoryStatus
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop app over two real [DesktopNode]s on loopback (found through an in-memory mDNS network): a drop on a
 * bubble reaches the peer through the shell, the ports and the node; the peer's tray asks for attention while its card
 * waits; the finished drop is announced with "Open" on its folder, shows in History with openable files, and the
 * tray's Visibility menu reaches the node (design §9, F‑C1, F‑D3, F‑G2). The banner follows the network, and a first
 * send's code is asked for again once the send has ended (F‑B3).
 */
class DesktopAppTest {
    private val root: Path = Files.createTempDirectory("drop-desktop-app")
    private val network = InMemoryLanNetwork()
    private val nodes = ArrayList<DesktopNode>()
    private val apps = ArrayList<Pair<DesktopApp, ExecutorCoroutineDispatcher>>()

    @AfterTest
    fun cleanUp() =
        runBlocking<Unit> {
            for ((app, main) in apps) {
                withContext(main) { app.shutdown() }
                main.close()
            }
            for (node in nodes) runCatching { node.close() }
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }

    private class RecordingHost : DesktopHost {
        val opened: MutableList<Path> = Collections.synchronizedList(ArrayList())
        val revealed: MutableList<Path> = Collections.synchronizedList(ArrayList())

        override fun open(path: Path): Boolean = opened.add(path)

        override fun reveal(path: Path): Boolean = revealed.add(path)

        override suspend fun chooseFiles(
            title: String,
            button: String,
        ): List<Path> = emptyList()

        override suspend fun chooseFolder(
            title: String,
            button: String,
            start: Path?,
        ): Path? = null
    }

    private suspend fun app(
        name: String,
        host: RecordingHost,
        lan: StateFlow<Boolean> = MutableStateFlow(true),
    ): DesktopApp {
        val base = root.resolve(name)
        val dirs = AppDirectories(base.resolve("config"), base.resolve("data"), base.resolve("cache"), base.resolve("Received"))
        val node =
            DesktopNode(
                DesktopNodeConfig(
                    directories = dirs,
                    secrets = FileSecretStorage(dirs.secrets),
                    lan = network.participant(),
                    lanAddress = InetAddress.getLoopbackAddress(),
                    defaultNickname = name,
                    platform = DevicePlatform.LAPTOP,
                    tuning = NodeTuning(finishedRetentionMillis = 600_000, lingerMillis = 300),
                ),
            )
        node.start()
        nodes += node
        node.setVisibility(Visibility.EVERYONE)
        node.setNickname(name)
        // A chosen nickname means no onboarding (design §7); wait until the node's settings say so.
        node.settings.first { it?.nickname == name }
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "$name-main").apply { isDaemon = true } }.asCoroutineDispatcher()
        val app =
            withContext(main) {
                DesktopApp(
                    node,
                    DesktopPlatformServices.portable(DesktopOs.LINUX),
                    host,
                    lanAvailable = lan,
                    appVersion = "test",
                    main = main,
                ).also {
                    it.start()
                }
            }
        apps += app to main
        return app
    }

    @Test
    fun aDropOnABubbleReachesThePeerAndEndsInHistoryWithANotification() =
        runBlocking<Unit> {
            withTimeout(60_000) {
                val aliceHost = RecordingHost()
                val bobHost = RecordingHost()
                val alice = app("Alice", aliceHost)
                val bob = app("Bob", bobHost)
                val main = apps.first().second
                val source = root.resolve("send").also(Files::createDirectories)
                val photo = source.resolve("photo.jpg").also { Files.write(it, ByteArray(40_000) { i -> (i % 251).toByte() }) }
                val note = source.resolve("note.txt").also { Files.writeString(it, "see you at six") }

                // No Bluetooth on this desktop: the banner carries the static code.
                val banner = alice.banner.first { it is DesktopBanner.NoBluetooth && it.code != null }
                assertTrue(assertIs<DesktopBanner.NoBluetooth>(banner).code!!.isNotEmpty())

                // Bob's bubble on Alice's radar, then files dropped on it.
                val bobBubble =
                    alice.controller.radar.state.first { s -> s.bubbles.any { it.name == "Bob" } }.bubbles.first {
                        it.name ==
                            "Bob"
                    }
                val announced = async { bob.notifications.first() }
                assertTrue(withContext(main) { alice.shell.onDrop(listOf(photo, note), bobBubble.key) })

                // Bob's card waits: his tray asks for attention; both screens show the same code (F‑B3).
                val offer = bob.node.offers.first { it.isNotEmpty() }.single()
                assertEquals(TrayState.ATTENTION, bob.trayView.first { it.state == TrayState.ATTENTION }.state)
                val sending = alice.node.transfers.first { l -> l.any { it.pairingCode != null } }.single()
                assertEquals(offer.sas, sending.pairingCode)
                assertEquals(TrayState.ATTENTION, alice.trayView.first { it.state == TrayState.ATTENTION }.state)
                bob.ports.confirmCode(offer.id)
                bob.ports.accept(offer.id, alwaysAccept = false)
                alice.ports.confirmPairing(sending.id)

                val received = bob.node.transfers.first { l -> l.any { it.id == offer.id && it.stage.isFinal } }.single()
                assertEquals(NodeStage.DONE, received.stage, received.failure)
                alice.node.transfers.first { l -> l.any { it.id == sending.id && it.stage.isFinal } }

                // "Received from Alice" with "Open" on the Received folder, even with the window in front.
                val notification = announced.await()
                assertEquals("Received from Alice", notification.title)
                val folder = assertIs<NotificationAction.OpenFolder>(notification.action).folder
                assertTrue(Files.isRegularFile(folder.resolve("photo.jpg")))

                // History on both sides through the ports (F‑G2), with the received files openable.
                val aliceRow = alice.ports.history.first { rows -> rows.any { it.id == sending.id } }.first { it.id == sending.id }
                assertEquals(HistoryStatus.DONE, aliceRow.status)
                assertEquals(Direction.SEND, aliceRow.direction)
                val bobRow = bob.ports.history.first { rows -> rows.any { it.id == offer.id } }.first { it.id == offer.id }
                assertEquals(Direction.RECEIVE, bobRow.direction)
                val files = bob.ports.files(offer.id)
                assertEquals(setOf("photo.jpg", "note.txt"), files.map { it.name }.toSet())
                assertTrue(files.all { it.openable }, "$files")
                val noteFile = files.first { it.name == "note.txt" }
                bob.ports.openFile(offer.id, noteFile.id)
                val opened = waitFor { bobHost.opened.firstOrNull() }
                assertEquals("see you at six", Files.readString(opened))
                assertEquals(folder.resolve("note.txt"), opened)

                // The tray settles once nothing waits or moves; its Visibility menu reaches the node.
                assertEquals(TrayState.IDLE, bob.trayView.first { it.state == TrayState.IDLE }.state)
                withContext(bob.scope.coroutineContext) { bob.onTrayVisibility(Visibility.HIDDEN) }
                val hidden =
                    bob.trayView.first { v ->
                        (v.menu[1] as TrayMenuItem.Choices).choices.single { it.checked }.visibility ==
                            Visibility.HIDDEN
                    }
                assertNotNull(hidden)
                assertEquals(Visibility.HIDDEN, bob.node.visibility.first { it.mode == Visibility.HIDDEN }.mode)
            }
        }

    @Test
    fun trayOpenAndQuitReachTheWindowAndTheReceivedFolderOpens() =
        runBlocking<Unit> {
            withTimeout(30_000) {
                val host = RecordingHost()
                val app = app("Carol", host)
                val requests = async { app.windowRequests.first() }
                withContext(app.scope.coroutineContext) { app.onTrayAction(TrayAction.OPEN) }
                assertEquals(WindowRequest.SHOW, requests.await())
                withContext(app.scope.coroutineContext) { app.onTrayAction(TrayAction.QUIT) }
                assertEquals(WindowRequest.QUIT, app.windowRequests.first())
                withContext(app.scope.coroutineContext) { app.onTrayAction(TrayAction.RECEIVED_FOLDER) }
                assertEquals(app.node.receivedFolder, waitFor { host.revealed.firstOrNull() })
                // Portable services have no login entry: the menu does not offer it.
                assertTrue(app.trayView.value.menu.none { it is TrayMenuItem.Toggle })
            }
        }

    /** The no-network banner follows the LAN watcher both ways (F‑H5: Wi‑Fi joins after login, or goes). */
    @Test
    fun theNoNetworkBannerFollowsTheNetwork() =
        runBlocking<Unit> {
            withTimeout(30_000) {
                val lan = MutableStateFlow(false)
                val app = app("Dana", RecordingHost(), lan)
                assertEquals(DesktopBanner.NoNetwork, app.banner.first { it == DesktopBanner.NoNetwork })
                lan.value = true
                // No Bluetooth on this desktop: on a network the banner offers the static code instead.
                assertIs<DesktopBanner.NoBluetooth>(app.banner.first { it is DesktopBanner.NoBluetooth })
                lan.value = false
                assertEquals(DesktopBanner.NoNetwork, app.banner.first { it == DesktopBanner.NoNetwork })
            }
        }

    /**
     * F‑B3 through the shell: a small first send ends before its sender has compared the codes, and the shared sheet goes
     * with it; the shell asks once more, and "Yes, it matches" then pairs the two devices both ways.
     */
    @Test
    fun aFirstSendsCodeIsAskedForAgainAfterItEndsAndConfirmingPairsBothWays() =
        runBlocking<Unit> {
            withTimeout(60_000) {
                val alice = app("Alice", RecordingHost())
                val bob = app("Bob", RecordingHost())
                val main = apps.first().second
                val note = root.resolve("send").also(Files::createDirectories).resolve("note.txt").also { Files.writeString(it, "hi") }
                val bobBubble = bubble(alice, "Bob")
                assertTrue(withContext(main) { alice.shell.onDrop(listOf(note), bobBubble.key) })
                val offer = bob.node.offers.first { it.isNotEmpty() }.single()
                bob.ports.confirmCode(offer.id)
                bob.ports.accept(offer.id, alwaysAccept = false)
                val sent = alice.node.transfers.first { l -> l.any { it.stage.isFinal } }.single()
                assertEquals(NodeStage.DONE, sent.stage, sent.failure)

                val prompt = assertNotNull(alice.finishedPairing.first { it != null })
                assertEquals(sent.id, prompt.transferId)
                assertEquals(offer.sas, prompt.code, "the code both screens showed")
                withContext(main) { alice.finishedPairingActions.confirm(prompt.transferId) }
                alice.finishedPairing.first { it == null }
                // Both radars resolve the other: the pairing and the advertising secrets went both ways (S3).
                alice.node.devices.first { l -> l.any { it.trustedDeviceId == bob.node.selfDeviceId } }
                bob.node.devices.first { l -> l.any { it.trustedDeviceId == alice.node.selfDeviceId } }
            }
        }

    /** "Not now" on the shared sheet while the send ran was the answer: the shell does not ask again after the end. */
    @Test
    fun aCodePutAsideWhileItsSendRanIsNotAskedForAgain() =
        runBlocking<Unit> {
            withTimeout(60_000) {
                val alice = app("Alice", RecordingHost())
                val bob = app("Bob", RecordingHost())
                val main = apps.first().second
                val note = root.resolve("send").also(Files::createDirectories).resolve("note.txt").also { Files.writeString(it, "hi") }
                val bobBubble = bubble(alice, "Bob")
                assertTrue(withContext(main) { alice.shell.onDrop(listOf(note), bobBubble.key) })
                val sheet = assertNotNull(alice.controller.radar.state.first { it.senderPairing != null }.senderPairing)
                withContext(main) { alice.controller.radar.pairLater(sheet.transferId) }
                alice.controller.radar.state.first { it.senderPairing == null }
                delay(300) // the shell notes the answer while the transfer still waits for Bob
                val offer = bob.node.offers.first { it.isNotEmpty() }.single()
                bob.ports.accept(offer.id, alwaysAccept = false)
                assertEquals(NodeStage.DONE, alice.node.transfers.first { l -> l.any { it.stage.isFinal } }.single().stage)
                delay(500)
                assertNull(alice.finishedPairing.value)
                assertFalse(alice.node.data.devices.find(bob.node.selfDeviceId)!!.isTrusted)
            }
        }

    /** [name]'s bubble on [app]'s radar, once it shows. */
    private suspend fun bubble(
        app: DesktopApp,
        name: String,
    ) = app.controller.radar.state
        .first { s -> s.bubbles.any { it.name == name } }
        .bubbles
        .first { it.name == name }

    private suspend fun <T : Any> waitFor(read: () -> T?): T {
        while (true) {
            read()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
    }
}
