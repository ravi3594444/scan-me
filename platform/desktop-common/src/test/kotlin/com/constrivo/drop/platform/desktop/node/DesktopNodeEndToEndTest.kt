package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferFileStatus
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.MarkingFileStore
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.seen
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.sha256
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.transfer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two desktop nodes in one JVM on loopback, found through an in-memory mDNS network (WP10a session test; F‑C6, F‑B3,
 * F‑D2, F‑G2, F‑H4 LAN path, T‑19's desktop half):
 *
 * 1. Alice drops a folder of 1,000 small files and two larger files on Bob's bubble; both screens show the same SAS;
 *    Bob confirms it and accepts with "Always accept", Alice confirms it; the transfer runs with bundling over the LAN
 *    primary and the ladder's LAN rung.
 * 2. Every byte arrives, in one per-drop subfolder (more than 20 files, design §9), and both History rows and their
 *    files say done.
 * 3. The pair now trusts each other: the advertising secrets were exchanged (S3), so each radar shows the other as
 *    trusted, and Alice's second send to Bob is accepted without a card (auto-accept).
 */
class DesktopNodeEndToEndTest {
    @Test
    fun `F-C6 pairing with SAS, a folder of 1,000 files with bundling, History, then auto-accept for the trusted pair`() =
        runBlocking<Unit> {
            NodeHarness().use { harness ->
                withTimeout(180_000) {
                    val alice = harness.node("Alice")
                    val bob = harness.node("Bob")
                    val source = harness.root.resolve("to-send")
                    val folder = source.resolve("Holiday")
                    val random = Random(20260924)
                    for (i in 0 until SMALL_FILES) {
                        val dir = folder.resolve("day ${i / 250 + 1}")
                        Files.createDirectories(dir)
                        Files.write(dir.resolve("photo-%04d.jpg".format(i)), random.nextBytes(1 + random.nextInt(4096)))
                    }
                    val movie = source.resolve("movie.mp4").also { Files.write(it, random.nextBytes(5 * MIB + 3)) }
                    val archive = source.resolve("archive.zip").also { Files.write(it, random.nextBytes(3 * MIB + 11)) }
                    val items = SendItems.expand(listOf(folder, movie, archive))
                    assertEquals(SMALL_FILES + 2, items.files.size)
                    assertEquals("Holiday/day 1/photo-0000.jpg", items.files.first().name)

                    // Discovery over the (in-memory) LAN: each radar shows the other, as a stranger.
                    val bobOnAlice = seen(alice, "Bob")
                    assertNull(bobOnAlice.trustedDeviceId)
                    assertTrue(bobOnAlice.lanOnly)
                    seen(bob, "Alice")

                    // History is written by the time a node reports the end (the stage shows it a moment earlier).
                    val finishedOnBob = async { bob.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val finishedOnAlice = async { alice.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val id = assertNotNull(alice.send(bobOnAlice.key, items.files))

                    // The SAS on both screens (F-B3), before anything is accepted.
                    val offer = bob.offers.first { it.isNotEmpty() }.single()
                    assertEquals(id, offer.id)
                    assertFalse(offer.trusted)
                    assertEquals(SMALL_FILES + 2, offer.fileCount)
                    val senderCode = transfer(alice, id) { it.pairingCode != null }.pairingCode
                    assertNotNull(offer.sas)
                    assertEquals(offer.sas, senderCode, "both devices show the same code")

                    bob.confirmCode(offer.id)
                    bob.accept(offer.id, alwaysAccept = true)
                    alice.confirmPairing(id)

                    val sent = transfer(alice, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, sent.stage, "sender: ${sent.failure}")
                    val received = transfer(bob, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, received.stage, "receiver: ${received.failure}")
                    assertEquals(items.files.sumOf { it.size }, received.bytesDone)
                    val finished = finishedOnBob.await()
                    assertEquals(SMALL_FILES + 2, finished.receivedFiles)
                    assertEquals(NodeStage.DONE, finishedOnAlice.await().transfer.stage)

                    // Every byte, in one per-drop subfolder of Bob's Received folder (design §9: more than 20 files).
                    val receivedRoot = harness.directories("Bob").received
                    val drops = Files.list(receivedRoot).use { it.toList() }
                    assertEquals(1, drops.size, "one per-drop subfolder: $drops")
                    val drop = drops.single()
                    assertTrue(Files.isDirectory(drop))
                    assertTrue(drop.fileName.toString().startsWith("Alice "), "named after the sender: ${drop.fileName}")
                    assertEquals(drop, finished.folder)
                    assertEquals(SMALL_FILES + 2, Files.list(drop).use { it.count() }.toInt())
                    for (file in items.files) {
                        val name = file.name.substringAfterLast('/')
                        assertEquals(sha256(file.path), sha256(drop.resolve(name)), name)
                    }
                    val partials = harness.directories("Bob").partials
                    assertEquals(0L, Files.walk(partials).use { s -> s.filter { Files.isRegularFile(it) }.count() }, "no partials left")

                    // History on both sides (F-G2).
                    val transferId = TransferId.fromHex(id)
                    val aliceRow = assertNotNull(alice.data.transfers.get(transferId))
                    assertEquals(TransferStatus.DONE, aliceRow.status)
                    assertEquals(TransferDirection.SEND, aliceRow.direction)
                    assertEquals(SMALL_FILES + 2, aliceRow.fileCount)
                    assertEquals(items.files.sumOf { it.size }, aliceRow.bytesDone)
                    assertEquals(LinkKind.LAN, aliceRow.transport)
                    assertEquals("Bob", aliceRow.peerName)
                    val bobRow = assertNotNull(bob.data.transfers.get(transferId))
                    assertEquals(TransferStatus.DONE, bobRow.status)
                    assertEquals(TransferDirection.RECEIVE, bobRow.direction)
                    assertEquals(items.files.sumOf { it.size }, bobRow.bytesDone)
                    assertEquals(mapOf(TransferFileStatus.DONE to SMALL_FILES + 2), bob.data.transferFiles.statusCounts(transferId))
                    assertEquals(mapOf(TransferFileStatus.DONE to SMALL_FILES + 2), alice.data.transferFiles.statusCounts(transferId))
                    val bobFiles = bob.data.transferFiles.files(transferId)
                    assertTrue(bobFiles.all { f -> f.sha256 != null && MarkingFileStore.pathOf(f.savedUri!!)?.parent == drop })
                    assertTrue(bob.data.manifests.forTransfer(transferId).isEmpty(), "resume state is gone once done")

                    // Trust on both sides, auto-accept on Bob, and each other's advertising secret (S3): trusted bubbles.
                    val aliceOnBob = assertNotNull(bob.data.devices.find(alice.selfDeviceId))
                    assertTrue(aliceOnBob.isTrusted && aliceOnBob.autoAccept)
                    val bobKnown = assertNotNull(alice.data.devices.find(bob.selfDeviceId))
                    assertTrue(bobKnown.isTrusted)
                    val trustedBob =
                        alice.devices.first { l -> l.any { it.trustedDeviceId == bob.selfDeviceId } }.first {
                            it.trustedDeviceId ==
                                bob.selfDeviceId
                        }
                    bob.devices.first { l -> l.any { it.trustedDeviceId == alice.selfDeviceId } }

                    // Second send: no card on Bob (auto-accept, F-D2), no code on Alice.
                    val note = source.resolve("note.txt").also { Files.write(it, "see you".toByteArray()) }
                    val secondOnBob = async { bob.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val second = assertNotNull(alice.send(trustedBob.key, SendItems.expand(listOf(note)).files))
                    val secondSent = transfer(alice, second) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, secondSent.stage, secondSent.failure)
                    assertNull(secondSent.pairingCode)
                    assertEquals(NodeStage.DONE, transfer(bob, second) { it.stage.isFinal }.stage)
                    assertEquals(second, secondOnBob.await().transfer.id)
                    assertTrue(bob.offers.value.isEmpty())
                    // One file: straight into the Received folder, no subfolder.
                    assertEquals("see you", String(Files.readAllBytes(receivedRoot.resolve("note.txt"))))
                    val secondRow = assertNotNull(bob.data.transfers.get(TransferId.fromHex(second)))
                    assertEquals(TransferStatus.DONE, secondRow.status)
                    assertEquals(2, bob.data.transfers.historyPage().transfers.size)
                    assertTrue(harness.problems.isEmpty(), "no problems reported: ${harness.problems}")
                }
            }
        }

    private companion object {
        const val SMALL_FILES = 1000
        const val MIB = 1024 * 1024
    }
}
