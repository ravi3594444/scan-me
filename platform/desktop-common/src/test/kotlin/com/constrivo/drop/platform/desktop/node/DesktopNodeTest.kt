package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.qr.QrPayloadCodec
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.lan.RebindableLanDiscovery
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.seen
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.transfer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNodeTest {
    private fun NodeHarness.note(
        name: String,
        text: String = "hello",
    ) = root.resolve("src").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray())
    }

    @Test
    fun `a declined offer shows Declined on the sender and History says cancelled on both`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    val offer = bob.offers.first { it.isNotEmpty() }.single()
                    bob.decline(offer.id)
                    assertEquals(NodeStage.DECLINED, transfer(alice, id) { it.stage.isFinal }.stage)
                    assertTrue(bob.offers.first { it.isEmpty() }.isEmpty())
                    val row = bob.data.transfers.observe(TransferId.fromHex(id)).first { it?.status?.isTerminal == true }
                    assertEquals(TransferStatus.CANCELLED, row?.status)
                    assertFalse(bob.data.devices.find(alice.selfDeviceId)!!.isTrusted, "declining trusts nobody")
                }
            }
        }

    @Test
    fun `the sender can cancel while the card is up, and the card goes`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    bob.offers.first { it.isNotEmpty() }
                    transfer(alice, id) { it.stage == NodeStage.AWAITING_ACCEPT }
                    alice.cancel(id)
                    assertEquals(NodeStage.CANCELLED, transfer(alice, id) { it.stage.isFinal }.stage)
                    bob.offers.first { it.isEmpty() }
                    assertEquals(NodeStage.CANCELLED, transfer(bob, id) { it.stage.isFinal }.stage)
                }
            }
        }

    @Test
    fun `Trusted only keeps a stranger off the radar and Hidden withdraws the record (F-A5, N4)`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice", visibility = Visibility.EVERYONE)
                    val bob = h.node("Bob", visibility = Visibility.TRUSTED_ONLY)
                    seen(bob, "Alice")
                    val record =
                        h.network.services.values.map { MdnsRecord.fromTxt(it.txt) }.first {
                            it.visibility ==
                                Visibility.TRUSTED_ONLY
                        }
                    assertNull(record.nickname, "Trusted-only records carry no nickname")
                    delay(300)
                    assertTrue(alice.devices.value.isEmpty(), "a Trusted-only stranger never shows: ${alice.devices.value}")
                    bob.setVisibility(Visibility.HIDDEN)
                    withTimeout(5_000) { while (h.network.services.size != 1) delay(20) }
                    assertEquals(Visibility.HIDDEN, bob.effectiveVisibility.value)
                    bob.setVisibility(Visibility.EVERYONE_TEN_MINUTES)
                    assertEquals(Visibility.EVERYONE_TEN_MINUTES, bob.visibility.first { it.mode == Visibility.EVERYONE_TEN_MINUTES }.mode)
                    seen(alice, "Bob")
                }
            }
        }

    @Test
    fun `pairing then Forget untrusts the device and rotates the advertising secret (F-G3, S3)`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(90_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    val offer = bob.offers.first { it.isNotEmpty() }.single()
                    bob.confirmCode(offer.id)
                    bob.accept(offer.id, alwaysAccept = false)
                    alice.confirmPairing(id)
                    assertEquals(NodeStage.DONE, transfer(alice, id) { it.stage.isFinal }.stage)
                    // Each side resolves the other once the secrets were exchanged.
                    alice.devices.first { l -> l.any { it.trustedDeviceId == bob.selfDeviceId } }
                    bob.devices.first { l -> l.any { it.trustedDeviceId == alice.selfDeviceId } }
                    val before = h.network.services.keys
                    assertTrue(bob.forget(alice.selfDeviceId))
                    assertFalse(bob.data.devices.find(alice.selfDeviceId)!!.isTrusted)
                    // Bob announces under a new rotating ID, which Alice can no longer resolve.
                    withTimeout(5_000) { while (h.network.services.keys == before) delay(20) }
                    alice.devices.first { l -> l.any { it.nickname == "Bob" && it.trustedDeviceId == null } }
                    assertFalse(bob.forget("0".repeat(32)))
                }
            }
        }

    /** An unusable Received folder (an unplugged drive, a read-only share) declines the offer with `storage`. */
    @Test
    fun `an offer that cannot be stored is declined at once and nobody is left waiting`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val blocker = h.root.resolve("not-a-folder").also { Files.write(it, byteArrayOf(1)) }
                    bob.setSaveLocation(blocker.resolve("Received"))
                    bob.settings.first { it?.saveLocation != null }
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    val ended = transfer(alice, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DECLINED, ended.stage, "Decline{storage} ends the send at once, not after 30 s")
                    assertTrue(bob.offers.value.isEmpty(), "no card for an offer that cannot be stored")
                    withTimeout(5_000) { while (h.problems.none { "Received folder cannot be used" in it }) delay(20) }
                    // A source that vanished between the drop and the dial fails the send and closes its session.
                    val gone = h.note("gone.txt")
                    val files = SendItems.expand(listOf(gone)).files
                    Files.delete(gone)
                    bob.setSaveLocation(null)
                    val failed = assertNotNull(alice.send(seen(alice, "Bob").key, files))
                    assertEquals(NodeStage.FAILED, transfer(alice, failed) { it.stage.isFinal }.stage)
                    assertTrue(bob.offers.value.isEmpty())
                }
            }
        }

    /** "Clear partial files" also frees what an earlier run of the app left of receives it never finished (F‑G5). */
    @Test
    fun `clearing partial files frees the receives an earlier run left behind`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                val bob = h.node("Bob")
                val stranger = JcaCryptoProvider().generateEd25519().publicKey
                val peer = bob.data.devices.recordPeer(stranger, "Alice", com.constrivo.drop.core.discovery.DevicePlatform.LAPTOP)
                val id = TransferId(ByteArray(TransferId.SIZE) { 7 })
                bob.data.transfers.create(NewTransfer(id, peer.id, TransferDirection.RECEIVE, 4_096, 1))
                val partial = h.directories("Bob").partials.resolve(id.toHex()).resolve("0.part")
                Files.createDirectories(partial.parent)
                Files.write(partial, ByteArray(4_096))
                val cleared = bob.clearPartials()
                assertEquals(1, cleared.filesRemoved)
                assertEquals(4_096L, cleared.bytesFreed)
                assertFalse(Files.exists(partial))
                assertEquals(TransferStatus.CANCELLED, bob.data.transfers.get(id)?.status, "it can no longer resume")
            }
        }

    /** Anyone on the LAN can open connections: silent ones must neither starve the node nor lock other devices out. */
    @Test
    fun `a flood of silent connections is capped and does not hold up another device's offer`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val tuning = NodeHarness.TEST_TUNING.copy(helloWaitMillis = 500, maxUnauthenticatedPerHost = 16)
                    val alice = h.node("Alice", tuning)
                    val bob = h.node("Bob", tuning)
                    val silent =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            (1..80).map { java.net.Socket(java.net.InetAddress.getLoopbackAddress(), bob.controlPort) }
                        }
                    try {
                        delay(100)
                        assertTrue(
                            bob.unauthenticatedConnections <= 16,
                            "at most 16 wait for their Hello: ${bob.unauthenticatedConnections}",
                        )
                        val started = System.nanoTime()
                        val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                        bob.offers.first { l -> l.any { it.id == id } }
                        val millis = (System.nanoTime() - started) / 1_000_000
                        assertTrue(millis < 3_000, "the offer arrived in $millis ms")
                        // The silent ones are dropped after the Hello deadline.
                        withTimeout(5_000) { while (bob.unauthenticatedConnections > 0) delay(50) }
                    } finally {
                        silent.forEach { runCatching { it.close() } }
                    }
                }
            }
        }

    /** The machine joins another network (or its address changes): the node follows it (F‑H5, architecture §10.2). */
    @Test
    fun `moving to another LAN address rebinds the listener and announces the new endpoint`() =
        runBlocking<Unit> {
            val other = java.net.InetAddress.getByName("127.0.0.2")
            val usable = runCatching { java.net.ServerSocket(0, 1, other).close() }.isSuccess
            org.junit.jupiter.api.Assumptions.assumeTrue(usable, "127.0.0.2 is not usable here")
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val before = seen(alice, "Bob").lanEndpoints.single()
                    bob.setLanAddress(other)
                    assertEquals(other, bob.lanAddress.value)
                    val moved =
                        alice.devices.first { l ->
                            l.any { d -> d.nickname == "Bob" && d.lanEndpoints.any { it.host == "127.0.0.2" } }
                        }
                    val bobNow = moved.first { it.nickname == "Bob" }
                    assertTrue(bobNow.lanEndpoints.none { it == before }, "the old endpoint is gone")
                    val id = assertNotNull(alice.send(bobNow.key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    bob.offers.first { l -> l.any { it.id == id } }
                    bob.accept(id, alwaysAccept = false)
                    assertEquals(NodeStage.DONE, transfer(alice, id) { it.stage.isFinal }.stage)
                    bob.setLanAddress(other) // the same address: nothing to do
                }
            }
        }

    /**
     * Quit is not held up by a step that blocks its thread and ignores cancellation (here the mDNS goodbye, as a stuck
     * JmDNS can): [DesktopNode.stop] leaves it behind and returns within its budget.
     */
    @Test
    fun `stopping ends within its budget even when the mDNS goodbye hangs`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                val hang = AtomicBoolean(false)
                val release = CountDownLatch(1)
                val alice = h.node("Alice", wrapLan = { HangingGoodbye(it, hang, release) })
                try {
                    hang.set(true)
                    val started = System.nanoTime()
                    alice.stop()
                    val tookMillis = (System.nanoTime() - started) / 1_000_000
                    assertTrue(tookMillis < 8_000, "stop took $tookMillis ms")
                    assertTrue(release.count == 1L, "the goodbye is still stuck")
                } finally {
                    release.countDown()
                }
            }
        }

    /** A browse that fails (JmDNS could not start yet) is started again, so the radar does not stay empty for good. */
    @Test
    fun `a failed mDNS browse is tried again and the radar fills`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(30_000) {
                    val alice = h.node("Alice", wrapLan = { FailingFirstBrowse(it) })
                    h.node("Bob")
                    seen(alice, "Bob")
                    assertTrue(h.problems.any { "browsing stopped" in it }, "the failure was reported: ${h.problems}")
                }
            }
        }

    /** mDNS whose first browse fails at once. */
    private class FailingFirstBrowse(
        private val delegate: RebindableLanDiscovery,
    ) : RebindableLanDiscovery by delegate {
        private val failures = AtomicInteger(1)

        override fun browse(): Flow<LanEvent> =
            flow {
                if (failures.getAndDecrement() > 0) throw IOException("mDNS could not start on the interface")
                emitAll(delegate.browse())
            }
    }

    /** mDNS whose goodbye, once [hang] is set, blocks its thread until [release] (deaf to cancellation). */
    private class HangingGoodbye(
        private val delegate: RebindableLanDiscovery,
        private val hang: AtomicBoolean,
        private val release: CountDownLatch,
    ) : RebindableLanDiscovery by delegate {
        override suspend fun withdraw() {
            if (hang.get()) release.await(60, TimeUnit.SECONDS) else delegate.withdraw()
        }
    }

    @Test
    fun `the static QR code names this device and never expires (F-B6)`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                val alice = h.node("Alice")
                val crypto = JcaCryptoProvider()
                val payload = QrPayloadCodec(crypto).parse(alice.staticCode(), System.currentTimeMillis() / 1000)
                assertTrue(payload.isStatic)
                assertEquals(alice.selfDeviceId, payload.deviceId.toHex())
                assertEquals(alice.selfDeviceId, alice.selfDeviceId.lowercase())
                assertTrue(alice.capabilities.bits != 0, "announces the no-Bluetooth bit")
                assertFalse(alice.bluetoothAvailable)
            }
        }

    /** "Show my code" (F‑B5, F‑H4): five minutes, with the LAN address to dial where multicast is filtered. */
    @Test
    fun `the one-time code carries the LAN endpoint and expires in five minutes`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                val crypto = JcaCryptoProvider()
                val alice = h.node("Alice")
                val onLoopback = alice.oneTimeCode()
                val parsed = QrPayloadCodec(crypto).parse(onLoopback.payload, onLoopback.issuedAtMillis / 1000)
                assertFalse(parsed.isStatic)
                assertNull(parsed.link, "no network: nothing to dial")
                assertNull(onLoopback.fallback)
                assertEquals(5 * 60_000L, onLoopback.expiresAtMillis - onLoopback.issuedAtMillis / 1000 * 1000)
                // On a real interface the code names it; skipped where the container has none.
                val lan = com.constrivo.drop.platform.desktop.lan.LanInterfaces.selectCurrent()?.address ?: return@runBlocking
                val bob = h.node("Bob", address = lan)
                val code = bob.oneTimeCode()
                val payload = QrPayloadCodec(crypto).parse(code.payload, code.issuedAtMillis / 1000)
                assertEquals(bob.selfDeviceId, payload.deviceId.toHex())
                val link = assertNotNull(payload.link)
                assertEquals(com.constrivo.drop.core.crypto.qr.QrLinkKind.LAN, link.kind)
                assertEquals(lan.hostAddress, link.address)
                assertEquals(bob.controlPort, link.port)
                assertEquals("${lan.hostAddress}:${bob.controlPort}", code.fallback)
            }
        }

    @Test
    fun `the browser receive page is served on the LAN address until stopped (F-H4)`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(30_000) {
                    val alice = h.node("Alice")
                    alice.startBrowserShare(SendItems.expand(listOf(h.note("page.txt"))).files)
                    val ready = alice.browserShare.first { it is BrowserShareStatus.Ready || it is BrowserShareStatus.Failed }
                    val url = (ready as BrowserShareStatus.Ready).url
                    assertTrue(url.startsWith("http://127.0.0.1:") && url.contains("/t/"), url)
                    assertEquals(1, ready.fileCount)
                    val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { connection.responseCode }
                    assertEquals(200, status)
                    connection.disconnect()
                    alice.stopBrowserShare()
                    assertEquals(BrowserShareStatus.Idle, alice.browserShare.value)
                }
            }
        }
}
