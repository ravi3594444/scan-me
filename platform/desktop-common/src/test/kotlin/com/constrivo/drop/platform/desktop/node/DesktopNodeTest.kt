package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.qr.QrPayloadCodec
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.discovery.MdnsRecord
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.seen
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.transfer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
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
