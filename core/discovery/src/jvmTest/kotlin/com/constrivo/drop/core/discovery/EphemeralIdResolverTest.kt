package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Trusted resolution of rotating IDs with a shared `k_adv` (architecture §5.3, S3, F‑A5, F‑J1). */
class EphemeralIdResolverTest {
    private val crypto = JcaCryptoProvider()
    private val epochMillis = EphemeralIds.EPOCH_MILLIS
    private val peers = (1..100).map { TrustedPeer("device-$it", Secrets.secret(it), "Peer $it") }

    private fun idOf(
        peer: Int,
        epoch: Long,
    ) = EphemeralIds.derive(crypto, Secrets.secret(peer), epoch)

    @Test
    fun s3_everyTrustedPeerResolvesTheSameRotatingId() {
        // Two different observers holding the same shared k_adv resolve the same beacon.
        val alice = EphemeralIdResolver(crypto, listOf(TrustedPeer("bob", Secrets.secret(7))))
        val carol = EphemeralIdResolver(crypto, listOf(TrustedPeer("bob", Secrets.secret(7)), TrustedPeer("dave", Secrets.secret(8))))
        val beacon = idOf(7, EPOCH)
        for (resolver in listOf(alice, carol)) {
            val r = assertIs<EphemeralIdResolution.Trusted>(resolver.resolve(beacon, EPOCH_START + 1))
            assertEquals("bob", r.peer.deviceId)
            assertEquals(EPOCH, r.epoch)
        }
    }

    @Test
    fun fJ1_adjacentEpochsResolveAndOthersDoNot() {
        val resolver = EphemeralIdResolver(crypto, peers)
        val now = EPOCH_START + 300_000
        for (offset in -1L..1L) {
            val r = assertIs<EphemeralIdResolution.Trusted>(resolver.resolve(idOf(42, EPOCH + offset), now), "offset $offset")
            assertEquals("device-42", r.peer.deviceId)
            assertEquals(EPOCH + offset, r.epoch)
        }
        for (offset in listOf(-3L, -2L, 2L, 3L)) {
            assertEquals(EphemeralIdResolution.Unknown, resolver.resolve(idOf(42, EPOCH + offset), now), "offset $offset")
        }
        assertEquals(EphemeralIdResolution.Unknown, resolver.resolve(idOf(101, EPOCH), now), "a stranger")
    }

    @Test
    fun fA1_rolloverExactlyAtTheBoundary() {
        val resolver = EphemeralIdResolver(crypto, peers)
        val lastMilli = EPOCH_START + epochMillis - 1
        val firstMilli = EPOCH_START + epochMillis
        // The old ID heard just after the rotation still resolves (as the previous epoch), the new one too.
        assertEquals(EPOCH, (resolver.resolve(idOf(5, EPOCH), firstMilli) as EphemeralIdResolution.Trusted).epoch)
        assertEquals(EPOCH + 1, (resolver.resolve(idOf(5, EPOCH + 1), firstMilli) as EphemeralIdResolution.Trusted).epoch)
        assertEquals(EPOCH + 1, (resolver.resolve(idOf(5, EPOCH + 1), lastMilli) as EphemeralIdResolution.Trusted).epoch)
        // Going backwards in time (clock correction) still works.
        assertEquals(
            EPOCH - 5,
            (resolver.resolve(idOf(5, EPOCH - 5), EPOCH_START - 5 * epochMillis) as EphemeralIdResolution.Trusted).epoch,
        )
        // Epoch 0 has no previous epoch.
        assertEquals(0L, (resolver.resolve(idOf(5, 0), 0) as EphemeralIdResolution.Trusted).epoch)
    }

    @Test
    fun ownBeaconIsRecognised() {
        val own = Secrets.secret(500)
        val resolver = EphemeralIdResolver(crypto, peers, listOf(own))
        assertEquals(EphemeralIdResolution.Own, resolver.resolve(EphemeralIds.derive(crypto, own, EPOCH), EPOCH_START))
        assertEquals(EphemeralIdResolution.Own, resolver.resolve(EphemeralIds.derive(crypto, own, EPOCH + 1), EPOCH_START))
    }

    @Test
    fun s3_everyGenerationOfAPeersSecretResolvesToThatPeer() {
        // After a rotation the peer may still advertise with its previous k_adv until the new one is re-shared.
        val current = Secrets.secret(40)
        val previous = Secrets.secret(41)
        val peer = TrustedPeer("bob", current, "Bob", previousAdvertisingSecrets = listOf(previous))
        val resolver = EphemeralIdResolver(crypto, listOf(peer, TrustedPeer("carol", Secrets.secret(42))))
        for (secret in listOf(current, previous)) {
            val r = assertIs<EphemeralIdResolution.Trusted>(resolver.resolve(EphemeralIds.derive(crypto, secret, EPOCH), EPOCH_START))
            assertEquals("bob", r.peer.deviceId)
        }
        // Several own generations are all recognised as our own echo.
        val own = listOf(Secrets.secret(50), Secrets.secret(51))
        val withOwn = EphemeralIdResolver(crypto, listOf(peer), own)
        for (secret in own) {
            assertEquals(EphemeralIdResolution.Own, withOwn.resolve(EphemeralIds.derive(crypto, secret, EPOCH), EPOCH_START))
        }
    }

    @Test
    fun trustStatesCompareByContent() {
        val a = TrustState(listOf(Secrets.secret(1)), listOf(TrustedPeer("p", Secrets.secret(2), "P")))
        val b = TrustState(listOf(Secrets.secret(1)), listOf(TrustedPeer("p", Secrets.secret(2), "P")))
        assertTrue(a.sameAs(b))
        assertFalse(a.sameAs(TrustState(listOf(Secrets.secret(3)), a.peers)))
        assertFalse(a.sameAs(TrustState(listOf(Secrets.secret(1)), listOf(TrustedPeer("p", Secrets.secret(4), "P")))))
        assertFalse(a.sameAs(TrustState(listOf(Secrets.secret(1)), listOf(TrustedPeer("p", Secrets.secret(2), "Renamed")))))
        assertEquals("TrustState(own=1, peers=1)", a.toString(), "no secret is printed")
    }

    @Test
    fun collidingIdsAreNeverGuessed() {
        // A provider that makes every HMAC equal forces all peers onto one ID.
        val colliding = FixedDigestCrypto(ByteArray(32) { 0x11 })
        val twoPeers = EphemeralIdResolver(colliding, listOf(TrustedPeer("a", Secrets.secret(1)), TrustedPeer("b", Secrets.secret(2))))
        val id = EphemeralId.fromBytes(ByteArray(6) { 0x11 })
        assertEquals(EphemeralIdResolution.Unknown, twoPeers.resolve(id, EPOCH_START))
        // With our own secret in the mix, the conservative answer is "own" (never show ourselves).
        val withOwn = EphemeralIdResolver(colliding, listOf(TrustedPeer("a", Secrets.secret(1))), listOf(Secrets.secret(9)))
        assertEquals(EphemeralIdResolution.Own, withOwn.resolve(id, EPOCH_START))
        // A single peer whose IDs coincide across epochs is still that peer.
        val single = EphemeralIdResolver(colliding, listOf(TrustedPeer("a", Secrets.secret(1))))
        assertEquals("a", (single.resolve(id, EPOCH_START) as EphemeralIdResolution.Trusted).peer.deviceId)
    }

    @Test
    fun peerValidation() {
        assertFailsWith<IllegalArgumentException> { TrustedPeer("", Secrets.secret(1)) }
        assertFailsWith<IllegalArgumentException> { TrustedPeer("x", ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> {
            EphemeralIdResolver(crypto, listOf(TrustedPeer("x", Secrets.secret(1)), TrustedPeer("x", Secrets.secret(2))))
        }
        assertFailsWith<IllegalArgumentException> { EphemeralIdResolver(crypto, emptyList(), listOf(ByteArray(5))) }
        assertFailsWith<IllegalArgumentException> { TrustedPeer("x", Secrets.secret(1), previousAdvertisingSecrets = listOf(ByteArray(3))) }
        assertFailsWith<IllegalArgumentException> { TrustState(ownAdvertisingSecrets = listOf(ByteArray(31))) }
        assertEquals("TrustedPeer(x)", TrustedPeer("x", Secrets.secret(1)).toString(), "the secret is never printed")
        // The peer keeps its own copy of the secret.
        val secret = Secrets.secret(3)
        val peer = TrustedPeer("p", secret)
        secret.fill(0)
        val resolver = EphemeralIdResolver(crypto, listOf(peer))
        assertIs<EphemeralIdResolution.Trusted>(resolver.resolve(EphemeralIds.derive(crypto, Secrets.secret(3), EPOCH), EPOCH_START))
    }

    @Test
    fun resolutionIsATableLookupWithinBudgetFor100Peers() {
        val counting = CountingCrypto()
        val resolver = EphemeralIdResolver(counting, peers, listOf(Secrets.secret(999)))
        resolver.precompute(EPOCH_START)
        assertEquals(3 * 101, counting.hmacCalls, "three epochs × (100 peers + own)")
        val sightings = (0 until 10_000).map { i -> idOf(1 + i % 120, EPOCH + (i % 3) - 1) }
        val calls = counting.hmacCalls
        val mark = TimeSource.Monotonic.markNow()
        var trusted = 0
        for (id in sightings) if (resolver.resolve(id, EPOCH_START + 1_000) is EphemeralIdResolution.Trusted) trusted++
        val perPacketMicros = mark.elapsedNow().inWholeMicroseconds.toDouble() / sightings.size
        assertEquals(calls, counting.hmacCalls, "no cryptography while resolving inside the window")
        assertEquals(sightings.indices.count { it % 120 < 100 }, trusted)
        // Architecture §15 budget: ≤ 5 ms per packet. A lookup is microseconds; the bound only catches a regression.
        assertTrue(perPacketMicros < 5_000, "resolution took $perPacketMicros µs per packet")

        // A rotation computes exactly one new epoch table.
        resolver.resolve(idOf(1, EPOCH + 1), EPOCH_START + epochMillis)
        assertEquals(calls + 101, counting.hmacCalls)
        // Flapping around the boundary reuses the cached tables.
        resolver.resolve(idOf(1, EPOCH), EPOCH_START + epochMillis - 1)
        resolver.resolve(idOf(1, EPOCH + 1), EPOCH_START + epochMillis)
        assertEquals(calls + 101, counting.hmacCalls)
    }
}
