package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.seen
import com.constrivo.drop.platform.desktop.node.NodeHarness.Companion.transfer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pairing (F‑B3, F‑B4) and the advertising-secret exchange after it (S3), as the two nodes live them. */
class DesktopNodeTrustTest {
    private fun NodeHarness.note(
        name: String,
        text: String = "hello",
    ) = root.resolve("src").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray())
    }

    private suspend fun resolved(
        node: DesktopNode,
        peer: DesktopNode,
    ): NearbyDevice =
        node.devices.first { l -> l.any { it.trustedDeviceId == peer.selfDeviceId } }.first {
            it.trustedDeviceId ==
                peer.selfDeviceId
        }

    /**
     * A small first send ends milliseconds after the accept, long before the sender's user can compare the codes: the
     * code stays on the finished transfer, and confirming it then still pairs the devices both ways, so a receiver
     * that goes Trusted-only (the default) stays on its paired sender's radar.
     */
    @Test
    fun `F-B3 a sender who confirms the code after a small first send ended still pairs both ways (S3)`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("tiny.txt", "12345"))).files))
                    val offer = bob.offers.first { it.isNotEmpty() }.single()
                    assertFalse(offer.trusted)
                    bob.confirmCode(offer.id)
                    bob.accept(offer.id, alwaysAccept = false)
                    val done = transfer(alice, id) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, done.stage)
                    assertEquals(offer.sas, done.pairingCode, "the sender's code outlives the transfer until it is answered")
                    delay(1_000)
                    alice.confirmPairing(id)
                    assertNull(transfer(alice, id) { it.pairingCode == null }.pairingCode)
                    // Each radar resolves the other: the advertising secrets were exchanged after the end.
                    resolved(alice, bob)
                    resolved(bob, alice)
                    // Bob goes Trusted-only and stays on Alice's radar, resolved; a send reaches him without a code.
                    bob.setVisibility(Visibility.TRUSTED_ONLY)
                    val trustedBob =
                        alice.devices.first { l ->
                            l.any {
                                it.trustedDeviceId == bob.selfDeviceId &&
                                    it.visibility == Visibility.TRUSTED_ONLY
                            }
                        }
                    val key = trustedBob.first { it.trustedDeviceId == bob.selfDeviceId }.key
                    val second = assertNotNull(alice.send(key, SendItems.expand(listOf(h.note("second.txt"))).files))
                    val trustedOffer = bob.offers.first { l -> l.any { it.id == second } }.single { it.id == second }
                    assertTrue(trustedOffer.trusted, "the handshake proved the pairing both ways")
                    assertNull(trustedOffer.sas)
                    bob.accept(second, alwaysAccept = false)
                    val secondSent = transfer(alice, second) { it.stage.isFinal }
                    assertEquals(NodeStage.DONE, secondSent.stage, secondSent.failure)
                    assertNull(secondSent.pairingCode)
                    assertTrue(h.problems.isEmpty(), "no problems: ${h.problems}")
                }
            }
        }

    @Test
    fun `a finished send's code can be dismissed without trusting anyone`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(60_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val id = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("a.txt"))).files))
                    val offer = bob.offers.first { it.isNotEmpty() }.single()
                    bob.accept(offer.id, alwaysAccept = false)
                    assertNotNull(transfer(alice, id) { it.stage.isFinal }.pairingCode)
                    alice.dismissPairing(id)
                    assertNull(transfer(alice, id) { it.pairingCode == null }.pairingCode)
                    alice.confirmPairing(id) // too late: nothing to confirm any more
                    delay(300)
                    assertFalse(alice.data.devices.find(bob.selfDeviceId)!!.isTrusted)
                    assertFalse(bob.data.devices.find(alice.selfDeviceId)!!.isTrusted)
                }
            }
        }

    /**
     * After a one-sided Forget the handshake proves no pairing, so both screens show the same code again (a device
     * that still trusts locally must not hide it); once both confirm, both hold the new recognition secret and a
     * Trusted-only handshake with the proof succeeds.
     */
    @Test
    fun `F-B4 after a one-sided Forget both screens show the same code, and confirming it heals the pairing`() =
        runBlocking<Unit> {
            NodeHarness().use { h ->
                withTimeout(90_000) {
                    val alice = h.node("Alice")
                    val bob = h.node("Bob")
                    val first = assertNotNull(alice.send(seen(alice, "Bob").key, SendItems.expand(listOf(h.note("one.txt"))).files))
                    val firstOffer = bob.offers.first { it.isNotEmpty() }.single()
                    bob.confirmCode(firstOffer.id)
                    bob.accept(firstOffer.id, alwaysAccept = false)
                    alice.confirmPairing(first)
                    assertEquals(NodeStage.DONE, transfer(alice, first) { it.stage.isFinal }.stage)
                    resolved(alice, bob)
                    resolved(bob, alice)

                    assertTrue(bob.forget(alice.selfDeviceId))
                    // Bob announces under his rotated k_adv: Alice sees a stranger she still trusts locally.
                    val stranger = alice.devices.first { l -> l.any { it.nickname == "Bob" && it.trustedDeviceId == null } }
                    val again =
                        assertNotNull(
                            alice.send(
                                stranger.first {
                                    it.nickname == "Bob" && it.trustedDeviceId == null
                                }.key,
                                SendItems.expand(listOf(h.note("two.txt"))).files,
                            ),
                        )
                    val offer = bob.offers.first { l -> l.any { it.id == again } }.single { it.id == again }
                    assertFalse(offer.trusted)
                    val senderCode = transfer(alice, again) { it.pairingCode != null }.pairingCode
                    assertNotNull(offer.sas)
                    assertEquals(offer.sas, senderCode, "both screens show the same code")
                    bob.confirmCode(offer.id)
                    bob.accept(offer.id, alwaysAccept = false)
                    alice.confirmPairing(again)
                    assertEquals(NodeStage.DONE, transfer(alice, again) { it.stage.isFinal }.stage)
                    resolved(alice, bob)
                    resolved(bob, alice)

                    // Healed: both hold the same secret, so a Trusted-only Bob accepts Alice's proof.
                    bob.setVisibility(Visibility.TRUSTED_ONLY)
                    val trustedBob =
                        alice.devices.first { l ->
                            l.any {
                                it.trustedDeviceId == bob.selfDeviceId &&
                                    it.visibility == Visibility.TRUSTED_ONLY
                            }
                        }
                    val third =
                        assertNotNull(
                            alice.send(
                                trustedBob.first {
                                    it.trustedDeviceId == bob.selfDeviceId
                                }.key,
                                SendItems.expand(listOf(h.note("three.txt"))).files,
                            ),
                        )
                    val trustedOffer = bob.offers.first { l -> l.any { it.id == third } }.single { it.id == third }
                    assertTrue(trustedOffer.trusted)
                    assertNull(trustedOffer.sas)
                    assertNull(transfer(alice, third) { it.stage != NodeStage.CONNECTING }.pairingCode)
                    bob.accept(third, alwaysAccept = false)
                    assertEquals(NodeStage.DONE, transfer(alice, third) { it.stage.isFinal }.stage)
                    assertTrue(h.problems.none { "refused" in it }, "no refused handshake: ${h.problems}")
                }
            }
        }
}
