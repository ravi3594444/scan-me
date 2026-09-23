package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlin.concurrent.Volatile

/**
 * A trusted peer as beacon resolution sees it: its device ID (hex of `device_id`, as stored in the `device` table,
 * architecture §12) and the advertising secret `k_adv` it shared with us in the encrypted handshake (spec change S3).
 *
 * [previousAdvertisingSecrets] are older generations of the peer's `k_adv` that it may still advertise with: after
 * the peer rotates `k_adv` ("Forget", §5.3) it keeps advertising IDs of the old one for a grace period, so keeping
 * the previous generation here leaves no gap in recognition while the new one is re-shared.
 *
 * [nickname] is the name stored when the peer was trusted. The radar prefers it over any name heard over the air,
 * because scan responses and TXT records are unauthenticated and a replayed beacon could carry a false name.
 * [toString] never prints the secrets.
 */
class TrustedPeer(
    val deviceId: String,
    advertisingSecret: ByteArray,
    val nickname: String? = null,
    previousAdvertisingSecrets: Collection<ByteArray> = emptyList(),
) {
    /** The current `k_adv` first, then the previous generations. */
    internal val secrets: List<ByteArray>

    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        val all = listOf(advertisingSecret) + previousAdvertisingSecrets
        for (secret in all) {
            require(secret.size == EphemeralIds.ADVERTISING_SECRET_SIZE) {
                "k_adv must be ${EphemeralIds.ADVERTISING_SECRET_SIZE} bytes, was ${secret.size}"
            }
        }
        secrets = all.map { it.copyOf() }
    }

    /** Same device, name and secrets. */
    internal fun sameAs(other: TrustedPeer): Boolean =
        deviceId == other.deviceId && nickname == other.nickname && sameSecrets(secrets, other.secrets)

    override fun toString(): String = "TrustedPeer($deviceId)"
}

/**
 * Everything the radar needs to know about trust (S3), delivered as one reactive value so that a change of either
 * part reaches [NearbyDevices] at once.
 *
 * @param ownAdvertisingSecrets this device's own `k_adv`, current first, plus any previous generation it may still be
 *   advertising with, so its own beacon and mDNS record never appear on its radar. When a secret disappears from this
 *   list (rotation on "Forget" or "Reset identity"), [NearbyDeviceTracker] keeps recognising it for
 *   [NearbyConfig.ownSecretRetentionMillis] (two epochs), because echoes of the last advertisement can still arrive.
 * @param peers the trusted peers; should a device ID appear twice, its first entry wins.
 */
class TrustState(
    ownAdvertisingSecrets: Collection<ByteArray> = emptyList(),
    peers: Collection<TrustedPeer> = emptyList(),
) {
    internal val ownSecrets: List<ByteArray>
    val peers: List<TrustedPeer> = peers.toList()

    init {
        for (secret in ownAdvertisingSecrets) {
            require(secret.size == EphemeralIds.ADVERTISING_SECRET_SIZE) { "own k_adv must be 32 bytes, was ${secret.size}" }
        }
        ownSecrets = ownAdvertisingSecrets.map { it.copyOf() }
    }

    /** Same own secrets and same peers, in the same order. */
    internal fun sameAs(other: TrustState): Boolean =
        sameSecrets(ownSecrets, other.ownSecrets) &&
            peers.size == other.peers.size &&
            peers.indices.all { peers[it].sameAs(other.peers[it]) }

    override fun toString(): String = "TrustState(own=${ownSecrets.size}, peers=${peers.size})"
}

private fun sameSecrets(
    a: List<ByteArray>,
    b: List<ByteArray>,
): Boolean = a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

/** What an [EphemeralId] heard over the air belongs to. */
sealed interface EphemeralIdResolution {
    /** A stranger, or an ID that matches more than one trusted peer in the window (never guessed). */
    data object Unknown : EphemeralIdResolution

    /** This device's own beacon or mDNS record, heard back. */
    data object Own : EphemeralIdResolution

    /** The ID belongs to [peer]; [epoch] is the epoch whose ID matched (the current one or a neighbour). */
    data class Trusted(
        val peer: TrustedPeer,
        val epoch: Long,
    ) : EphemeralIdResolution
}

/**
 * Resolves sighted [EphemeralId]s to trusted peers (architecture §5.3 with spec change S3, F‑J1, F‑A5).
 *
 * For the epoch `e` of the sighting it accepts the IDs of epochs `e − 1`, `e` and `e + 1`, so two clocks up to one
 * epoch apart still recognise each other and a beacon sent just before a rotation still resolves just after it.
 * Each epoch's IDs are computed once into a table (one HMAC per peer) and cached; resolving is then at most three
 * hash-map lookups and no cryptography, well inside the 5 ms per packet budget of architecture §15 with 100 peers.
 * At a rotation only the new neighbour epoch is computed; tables more than two epochs from the latest sighting are
 * evicted. A backwards clock step simply computes the older tables again.
 *
 * Every generation of a peer's secret (its current and previous `k_adv`) resolves to that peer, and every
 * own secret to [EphemeralIdResolution.Own].
 *
 * An instance is immutable with respect to its peer set: build a new one when trust changes (pairing, "Forget",
 * a re-shared `k_adv`). It is safe to call from several threads; concurrent callers may at worst compute the same
 * table twice.
 *
 * @param ownAdvertisingSecrets this device's `k_adv` generations, so its own beacon heard back resolves to
 *   [EphemeralIdResolution.Own] instead of showing up as a stranger.
 */
class EphemeralIdResolver(
    private val crypto: CryptoProvider,
    peers: Collection<TrustedPeer>,
    ownAdvertisingSecrets: Collection<ByteArray> = emptyList(),
) {
    private val peers: List<TrustedPeer> = peers.toList()
    private val own: List<ByteArray> = ownAdvertisingSecrets.map { it.copyOf() }

    @Volatile
    private var tables: Map<Long, Map<Long, Int>> = emptyMap()

    init {
        val ids = HashSet<String>()
        for (p in this.peers) require(ids.add(p.deviceId)) { "duplicate trusted peer ${p.deviceId}" }
        for (secret in own) require(secret.size == EphemeralIds.ADVERTISING_SECRET_SIZE) { "own k_adv must be 32 bytes" }
    }

    /** Number of trusted peers this resolver checks. */
    val peerCount: Int get() = peers.size

    /** Resolves [id] heard at [unixMillis]. */
    fun resolve(
        id: EphemeralId,
        unixMillis: Long,
    ): EphemeralIdResolution {
        val epoch = EphemeralIds.epochAt(unixMillis)
        val window = window(epoch)
        var match = NONE
        var matchEpoch = -1L
        // Current epoch first, so the reported epoch is the most plausible one.
        for (k in ORDER.indices) {
            val e = epoch + ORDER[k]
            val hit = window[k][id.value] ?: continue
            when {
                hit == OWN -> {
                    return EphemeralIdResolution.Own
                }

                hit == AMBIGUOUS -> {
                    return EphemeralIdResolution.Unknown
                }

                match == NONE -> {
                    match = hit
                    matchEpoch = e
                }

                match != hit -> {
                    return EphemeralIdResolution.Unknown
                }
            }
        }
        return if (match >= 0) EphemeralIdResolution.Trusted(peers[match], matchEpoch) else EphemeralIdResolution.Unknown
    }

    /** Computes the tables around [unixMillis] ahead of time, for example just before an epoch boundary. */
    fun precompute(unixMillis: Long) {
        window(EphemeralIds.epochAt(unixMillis))
    }

    private fun window(epoch: Long): Array<Map<Long, Int>> {
        val current = tables
        val cur = current[epoch]
        val prev = current[epoch - 1]
        val next = current[epoch + 1]
        if (cur != null && prev != null && next != null) return arrayOf(cur, prev, next)
        val updated = HashMap<Long, Map<Long, Int>>()
        for ((e, t) in current) if (e in epoch - 2..epoch + 2) updated[e] = t
        val built = arrayOf(epoch, epoch - 1, epoch + 1).map { e -> updated.getOrPut(e) { buildTable(e) } }
        tables = updated
        return built.toTypedArray()
    }

    private fun buildTable(epoch: Long): Map<Long, Int> {
        if (epoch < 0) return emptyMap()
        val table = HashMap<Long, Int>(peers.size * 2 + own.size * 2 + 2)
        for (secret in own) table[EphemeralIds.derive(crypto, secret, epoch).value] = OWN
        for (i in peers.indices) {
            for (secret in peers[i].secrets) {
                val key = EphemeralIds.derive(crypto, secret, epoch).value
                val existing = table[key]
                table[key] =
                    when {
                        existing == null || existing == i -> i
                        existing == OWN -> OWN
                        else -> AMBIGUOUS
                    }
            }
        }
        return table
    }

    private companion object {
        const val NONE = -3
        const val AMBIGUOUS = -2
        const val OWN = -1

        /** Offsets of the three window slots: current, previous, next. */
        val ORDER = longArrayOf(0, -1, 1)
    }
}
