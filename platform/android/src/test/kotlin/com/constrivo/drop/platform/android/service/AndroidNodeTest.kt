package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.android.service.PhoneHarness.Companion.seen
import com.constrivo.drop.platform.android.service.PhoneHarness.Companion.transfer
import com.constrivo.drop.platform.android.service.PhoneHarness.Companion.trusted
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two phones in one JVM through [PhoneHarness] (WP7e's node over WP7a/b's radios, faked): beacons heard over a shared
 * air, the handshake and the whole transfer over the Bluetooth channel (no Wi-Fi rung here), History in the database,
 * and trust: F-B3's code on both screens, F-D2's auto-accept for the trusted pair after S3's exchange of advertising
 * secrets, and F-B5's scanned code, including T-12's expired one.
 */
class AndroidNodeTest {
    @Test
    fun `phones pair with the code, the file lands, and the trusted pair then sends without a card`() =
        runBlocking<Unit> {
            PhoneHarness().use { h ->
                withTimeout(60_000) {
                    val asha = h.phone("Asha")
                    val ravi = h.phone("Ravi")
                    val raviOnAsha = seen(asha, "Ravi")
                    assertNull(raviOnAsha.trustedDeviceId)
                    seen(ravi, "Asha")

                    val photo = h.file("photo.jpg", 300_000)
                    // History is written by the time a node reports the end (the stage shows it a moment earlier).
                    val finishedOnRavi = async { ravi.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val finishedOnAsha = async { asha.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val id = assertNotNull(asha.send(raviOnAsha.key, listOf(SendItem(photo.toString(), "photo.jpg"))))

                    // F-B3: the same code on both screens before anything is accepted.
                    val offer = ravi.offers.first { it.isNotEmpty() }.single()
                    assertEquals(id, offer.id)
                    assertFalse(offer.trusted)
                    assertEquals("Asha", offer.senderName)
                    assertEquals(1, offer.fileCount)
                    val senderCode = transfer(asha, id) { it.pairingCode != null }.pairingCode
                    assertNotNull(offer.sas)
                    assertEquals(offer.sas, senderCode)
                    assertEquals(1, ravi.activity.value.pendingOffers)
                    assertEquals(0, ravi.activity.value.active, "an offer on the card moves no bytes yet (S9)")

                    ravi.confirmCode(offer.id)
                    ravi.accept(offer.id, alwaysAccept = true)
                    asha.confirmPairing(id)

                    val sent = transfer(asha, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, sent.stage, "sender: ${sent.failure} ${h.problems}")
                    val received = transfer(ravi, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, received.stage, "receiver: ${received.failure} ${h.problems}")
                    assertEquals(300_000, received.bytesDone)
                    val finished = finishedOnRavi.await()
                    assertEquals("photo.jpg", finished.received.single().name)
                    assertContentEquals(Files.readAllBytes(photo), Files.readAllBytes(h.received("Ravi").resolve("photo.jpg")))

                    // History on both sides.
                    val raviRow = assertNotNull(ravi.data.transfers.get(TransferId.fromHex(id)))
                    assertEquals(TransferStatus.DONE, raviRow.status)
                    assertEquals(TransferDirection.RECEIVE, raviRow.direction)
                    assertEquals(LinkKind.BLUETOOTH, raviRow.transport)
                    assertEquals(NodeStage.DONE, finishedOnAsha.await().transfer.stage)
                    assertEquals(TransferStatus.DONE, asha.data.transfers.get(TransferId.fromHex(id))?.status)
                    assertEquals(photo.toString(), asha.data.transferFiles.files(TransferId.fromHex(id)).single().savedUri)

                    // S3: the advertising secrets crossed, so each radar resolves the other as trusted.
                    val raviTrusted = trusted(asha, ravi.selfDeviceId)
                    trusted(ravi, asha.selfDeviceId)
                    assertTrue(assertNotNull(ravi.data.devices.find(asha.selfDeviceId)).autoAccept)

                    // F-D2: the next send is accepted without a card.
                    val notes = h.file("notes.txt", 5_000)
                    val second = assertNotNull(asha.send(raviTrusted.key, listOf(SendItem(notes.toString(), "notes.txt"))))
                    val autoReceived = transfer(ravi, second) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, autoReceived.stage, "receiver: ${autoReceived.failure} ${h.problems}")
                    assertTrue(autoReceived.autoAccepted)
                    assertNull(transfer(asha, second) { it.stage.isFinal }.pairingCode, "a trusted session shows no code")
                    assertContentEquals(Files.readAllBytes(notes), Files.readAllBytes(h.received("Ravi").resolve("notes.txt")))
                }
            }
        }

    @Test
    fun `a declined offer ends on both sides and nothing is kept`() =
        runBlocking<Unit> {
            PhoneHarness().use { h ->
                withTimeout(30_000) {
                    val asha = h.phone("Asha")
                    val ravi = h.phone("Ravi")
                    val raviOnAsha = seen(asha, "Ravi")
                    val file = h.file("video.mp4", 20_000)
                    val finishedOnRavi = async { ravi.events.filterIsInstance<NodeEvent.TransferFinished>().first() }
                    val id = assertNotNull(asha.send(raviOnAsha.key, listOf(SendItem(file.toString(), "video.mp4"))))
                    val offer = ravi.offers.first { it.isNotEmpty() }.single()
                    ravi.decline(offer.id)

                    assertEquals(NodeStage.DECLINED, transfer(asha, id) { it.stage.isFinal }.stage)
                    assertEquals(NodeStage.DECLINED, transfer(ravi, id) { it.stage.isFinal }.stage)
                    // History is written by the time the node reports the end.
                    assertEquals(NodeStage.DECLINED, finishedOnRavi.await().transfer.stage)
                    assertTrue(ravi.offers.value.isEmpty())
                    assertFalse(Files.exists(h.received("Ravi").resolve("video.mp4")))
                    assertEquals(TransferStatus.CANCELLED, ravi.data.transfers.get(TransferId.fromHex(id))?.status)
                    // Nobody was trusted by a declined first contact.
                    assertFalse(ravi.data.devices.find(asha.selfDeviceId)?.isTrusted ?: false)
                }
            }
        }

    @Test
    fun `a scanned code verifies the receiver, an expired one says so, and a phone cannot scan itself`() =
        runBlocking<Unit> {
            PhoneHarness().use { h ->
                withTimeout(30_000) {
                    val asha = h.phone("Asha")
                    val ravi = h.phone("Ravi")
                    seen(asha, "Ravi")

                    val code = ravi.oneTimeCode()
                    assertNull(code.fallback, "a phone's code has no address to type")
                    assertEquals(AndroidNode.CODE_TARGET_MILLIS, code.expiresAtMillis - code.issuedAtMillis, 1_000)

                    val scan = assertIs<CodeScan.Verified>(asha.resolveCode(code.payload))
                    assertEquals(ravi.selfDeviceId, scan.deviceId)
                    val file = h.file("scan.pdf", 1_000)
                    val id = assertNotNull(asha.send(scan.deviceKey, listOf(SendItem(file.toString(), "scan.pdf"))))
                    val offer = ravi.offers.first { it.isNotEmpty() }.single()
                    ravi.accept(offer.id, alwaysAccept = false)
                    assertEquals(NodeStage.DONE, transfer(asha, id) { it.stage.isFinal }.stage, h.problems.toString())

                    assertIs<CodeScan.Invalid>(ravi.resolveCode(code.payload), "its own code")
                    assertIs<CodeScan.Invalid>(asha.resolveCode("not a drop code"))

                    // T-12: five minutes later the same code is refused as expired.
                    h.wallOffset.set(AndroidNode.CODE_TARGET_MILLIS + 60_000)
                    assertEquals(CodeScan.Expired, asha.resolveCode(code.payload))
                }
            }
        }

    private fun assertEquals(
        expected: Long,
        actual: Long,
        tolerance: Long,
    ) = assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")
}
