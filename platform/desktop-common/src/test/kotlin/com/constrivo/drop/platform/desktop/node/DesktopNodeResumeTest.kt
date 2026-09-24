package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.PRIMARY_ONLY
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.seen
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.sha256
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.transfer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S8 and T‑07 on the desktop: a transfer whose link drops is offered again with its id on a new session and resumes
 * from what arrived (no card for a transfer the receiver accepted in this process, the resume card after a restart),
 * and a new send from the same peer is never taken for a reconnect. The primary link runs through a throttled
 * [TcpProxy] with the LAN rung off, so "mid-transfer" is a matter of bytes, not luck.
 */
class DesktopNodeResumeTest {
    private fun NodeHarness.file(
        name: String,
        size: Int,
    ): Path =
        root.resolve("src").resolve(name).also {
            Files.createDirectories(it.parent)
            Files.write(it, Random(name.hashCode()).nextBytes(size))
        }

    @Test
    fun `S8 a dropped link resumes two transfers to the same peer on new sessions, each as itself and without a card`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(90_000) {
                    val alice = h.node("Alice", PRIMARY_ONLY)
                    val bob = h.node("Bob", PRIMARY_ONLY, proxied = true, proxyBytesPerSecond = 8L * MIB)
                    // Resume works in whole units (4 MiB chunks): each file has more than one, and the drop comes
                    // after the first is on disk.
                    val a = h.file("a.bin", 12 * MIB + 17)
                    val b = h.file("b.bin", 12 * MIB + 29)
                    val cards = Collections.synchronizedList(ArrayList<String>())
                    val watch = launch { bob.offers.collect { list -> list.forEach { cards += it.id } } }
                    val key = seen(alice, "Bob").key
                    val first = assertNotNull(alice.send(key, SendItems.expand(listOf(a)).files))
                    bob.offers.first { l -> l.any { it.id == first } }
                    bob.accept(first, alwaysAccept = false)
                    val second = assertNotNull(alice.send(key, SendItems.expand(listOf(b)).files))
                    bob.offers.first { l -> l.any { it.id == second } }
                    bob.accept(second, alwaysAccept = false)
                    transfer(bob, first) { it.bytesDone >= 5L * MIB }
                    transfer(bob, second) { it.bytesDone >= 5L * MIB }
                    delay(300) // the write-behind (at most 100 ms, N5) makes the first unit durable
                    val cardsBefore = cards.toSet()

                    h.proxy("Bob").cut() // the Wi-Fi drops for both transfers at once

                    val sentA = transfer(alice, first) { it.stage.isFinal }
                    val sentB = transfer(alice, second) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, sentA.stage, sentA.failure)
                    assertEquals(NodeStage.DONE, sentB.stage, sentB.failure)
                    assertEquals(NodeStage.DONE, transfer(bob, first) { it.stage.isFinal }.stage)
                    assertEquals(NodeStage.DONE, transfer(bob, second) { it.stage.isFinal }.stage)
                    watch.cancel()
                    assertEquals(cardsBefore, cards.toSet(), "a resumed transfer shows no second card")
                    val received = h.directories("Bob").received
                    assertEquals(sha256(a), sha256(received.resolve("a.bin")))
                    assertEquals(sha256(b), sha256(received.resolve("b.bin")))
                    // Each resumed as itself: the last attempt of each sent less than the whole file again.
                    val resentA = assertNotNull(alice.statsOf(first)).fileBytesSent
                    val resentB = assertNotNull(alice.statsOf(second)).fileBytesSent
                    assertTrue(resentA <= Files.size(a) - 4L * MIB, "only what was missing of a: $resentA")
                    assertTrue(resentB <= Files.size(b) - 4L * MIB, "only what was missing of b: $resentB")
                    assertTrue(h.proxy("Bob").accepted >= 4, "new sessions after the drop")
                    val names = Files.list(received).use { list -> list.map { it.fileName.toString() }.toList() }.toSet()
                    assertEquals(setOf("a.bin", "b.bin"), names, "no duplicates")
                }
            }
        }

    /**
     * While a receive from Alice waits for her to come back (her app went away mid-transfer), a new send from Alice is
     * a new session with its own Offer: it shows its card and runs, and the interrupted receive keeps waiting.
     */
    @Test
    fun `a new send from a peer whose earlier transfer is reconnecting shows its card`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(90_000) {
                    val alice = h.node("Alice", PRIMARY_ONLY)
                    val bob = h.node("Bob", PRIMARY_ONLY, proxied = true, proxyBytesPerSecond = 4L * MIB)
                    val big = h.file("big.bin", 16 * MIB)
                    val old = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(big)).files))
                    bob.offers.first { l -> l.any { it.id == old } }
                    bob.accept(old, alwaysAccept = false)
                    transfer(bob, old) { it.bytesDone >= MIB }
                    alice.stop() // Alice's app goes away mid-transfer
                    transfer(bob, old) { it.stage == NodeStage.RECONNECTING || it.stage == NodeStage.WAITING_FOR_PEER }

                    val alice2 = h.node("Alice", PRIMARY_ONLY) // the same device, started again; the pair is not trusted
                    val note = h.file("note.txt", 5)
                    val fresh = assertNotNull(alice2.send(seen(alice2, "Bob").key, SendItems.expand(listOf(note)).files))
                    val card = bob.offers.first { l -> l.any { it.id == fresh } }.single { it.id == fresh }
                    assertFalse(card.isResume)
                    bob.accept(fresh, alwaysAccept = false)
                    assertEquals(NodeStage.DONE, transfer(alice2, fresh) { it.stage.isFinal }.stage)
                    assertEquals(NodeStage.DONE, transfer(bob, fresh) { it.stage.isFinal }.stage)
                    val stillWaiting = bob.transfers.value.single { it.id == old }
                    assertTrue(
                        stillWaiting.stage == NodeStage.RECONNECTING || stillWaiting.stage == NodeStage.WAITING_FOR_PEER,
                        "$stillWaiting",
                    )
                }
            }
        }

    /**
     * T‑07: the receiver's app stops mid-transfer and starts again. Its partial files and resume record stay; the
     * sender offers the same transfer again once the receiver is back, the receiver shows the resume card, and after
     * the accept only what was missing crosses.
     */
    @Test
    fun `T-07 a receiver restarted mid-transfer gets the resume card and completes with fewer bytes sent again`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(120_000) {
                    val alice = h.node("Alice", PRIMARY_ONLY)
                    val bob = h.node("Bob", PRIMARY_ONLY, proxied = true, proxyBytesPerSecond = 8L * MIB)
                    val video = h.file("video.mp4", 24 * MIB + 5)
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(video)).files))
                    bob.offers.first { l -> l.any { it.id == id } }
                    bob.accept(id, alwaysAccept = false)
                    transfer(bob, id) { it.bytesDone >= 6L * MIB }
                    delay(400) // the write-behind (at most 100 ms, N5) makes what is on disk durable
                    bob.stop()
                    val partials = h.directories("Bob").partials.resolve(id)
                    assertTrue(Files.isDirectory(partials), "the partial files stay")
                    transfer(alice, id) { it.stage == NodeStage.RECONNECTING }

                    val bob2 = h.node("Bob", PRIMARY_ONLY, proxied = true, proxyBytesPerSecond = 8L * MIB)
                    val card = bob2.offers.first { l -> l.any { it.id == id } }.single { it.id == id }
                    assertTrue(card.isResume, "the resume prompt (T-07)")
                    bob2.accept(id, alwaysAccept = false)
                    val sent = transfer(alice, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, sent.stage, sent.failure)
                    val received = transfer(bob2, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, received.stage, received.failure)
                    assertEquals(sha256(video), sha256(h.directories("Bob").received.resolve("video.mp4")))
                    val resent = assertNotNull(alice.statsOf(id)).fileBytesSent
                    assertTrue(resent <= Files.size(video) - 4L * MIB, "the restart sent only what was missing: $resent")
                    assertEquals(TransferStatus.DONE, bob2.data.transfers.get(TransferId.fromHex(id))?.status)
                    assertFalse(Files.exists(partials), "partials go once done")
                }
            }
        }

    /** A send an app restart interrupted is offered again with its id once its trusted peer shows up (T‑07). */
    @Test
    fun `T-07 a sender restarted mid-transfer offers the transfer again to its trusted peer, which resumes it`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(120_000) {
                    val alice = h.node("Alice", PRIMARY_ONLY)
                    val bob = h.node("Bob", PRIMARY_ONLY, proxied = true, proxyBytesPerSecond = 8L * MIB)
                    // Pair first, so Alice's radar recognises Bob after her restart.
                    val pair = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.file("hi.txt", 3))).files))
                    val pairing = bob.offers.first { it.isNotEmpty() }.single()
                    bob.confirmCode(pairing.id)
                    bob.accept(pairing.id, alwaysAccept = false)
                    alice.confirmPairing(pair)
                    alice.devices.first { l -> l.any { it.trustedDeviceId == bob.selfDeviceId } }
                    val bobOnAlice = alice.devices.value.first { it.trustedDeviceId == bob.selfDeviceId }

                    val video = h.file("clip.mp4", 20 * MIB + 3)
                    val id = assertNotNull(alice.send(bobOnAlice.key, SendItems.expand(listOf(video)).files))
                    bob.offers.first { l -> l.any { it.id == id } }
                    bob.accept(id, alwaysAccept = false)
                    transfer(bob, id) { it.bytesDone >= 6L * MIB }
                    delay(400) // the write-behind (at most 100 ms, N5) makes the first unit durable
                    alice.stop()
                    transfer(bob, id) { it.stage == NodeStage.RECONNECTING || it.stage == NodeStage.WAITING_FOR_PEER }

                    val alice2 = h.node("Alice", PRIMARY_ONLY)
                    val resumed = async { transfer(alice2, id) { it.stage.isFinal } }
                    assertEquals(NodeStage.DONE, resumed.await().stage)
                    assertEquals(NodeStage.DONE, transfer(bob, id) { it.stage.isFinal }.stage)
                    assertEquals(sha256(video), sha256(h.directories("Bob").received.resolve("clip.mp4")))
                    assertTrue(assertNotNull(alice2.statsOf(id)).fileBytesSent < Files.size(video), "resumed, not sent again")
                }
            }
        }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
