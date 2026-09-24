package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeFailure
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.Offer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A TCP endpoint: an IP literal or host name and a port. */
data class Endpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port $port out of range" }
    }

    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** One way to reach the peer: [connect] opens a raw channel (a TCP connection, an RFCOMM socket, a GATT pipe). */
class DialCandidate(
    /** For logs: the endpoint or address tried. */
    val label: String,
    val connect: suspend () -> DataChannel,
)

/** Every candidate failed; [failures] holds each one's error in order. */
class DialException(
    message: String,
    val failures: List<Pair<String, Throwable>>,
) : Exception(message, failures.firstOrNull()?.second)

/**
 * Endpoint selection behind the handshake identity check (carried forward from WP1–WP3 and WP5).
 *
 * Unauthenticated discovery data (mDNS records, `NearbyDevice.lanEndpoints` and `radioAddresses`, the address in an
 * `Offer`'s LAN option) only suggests where the peer might be. [dial] tries the candidates in order, runs the handshake
 * on each connection with the expected identity, and returns the first session whose peer proved that identity; an
 * impostor answering on a claimed endpoint fails with
 * [com.constrivo.drop.core.crypto.handshake.HandshakeFailure.PEER_IDENTITY_MISMATCH] and the next candidate is tried.
 */
object EndpointDialer {
    /**
     * Tries [candidates] in order. Each gets [perCandidateMillis] to connect; the [handshake] (for example
     * `SessionHandshake.initiate(channel, config, config.expectedPeer(key))`) has its own timeout. A refusal that another
     * candidate cannot fix ([HandshakeFailure.RATE_LIMITED], [HandshakeFailure.TRUST_PROOF_REQUIRED]) still moves on,
     * since another endpoint may be the real device.
     *
     * @throws DialException when no candidate yields a session.
     */
    suspend fun dial(
        candidates: List<DialCandidate>,
        perCandidateMillis: Long = DEFAULT_CONNECT_MILLIS,
        handshake: suspend (DataChannel) -> SecureSession,
    ): SecureSession {
        require(perCandidateMillis > 0) { "per-candidate timeout must be positive" }
        val failures = ArrayList<Pair<String, Throwable>>()
        for (candidate in candidates) {
            var channel: DataChannel? = null
            try {
                channel =
                    withTimeoutOrNull(perCandidateMillis) { candidate.connect() }
                        ?: throw SessionException("connecting to ${candidate.label} timed out after $perCandidateMillis ms")
                return handshake(channel)
            } catch (e: CancellationException) {
                channel?.let { withContext(NonCancellable) { runCatching { it.close() } } }
                throw e
            } catch (e: Exception) {
                channel?.let { withContext(NonCancellable) { runCatching { it.close() } } }
                failures += candidate.label to e
            }
        }
        val summary = failures.joinToString { (label, e) -> "$label: ${describe(e)}" }
        throw DialException(if (candidates.isEmpty()) "no candidate endpoints" else "no candidate reached the peer ($summary)", failures)
    }

    /**
     * The LAN endpoints to try for [offer]'s sender and the caller's discovery claims: the `Offer`'s LAN options with an
     * address and port first (the sender announced them inside the authenticated session), then [discovered] (for
     * example `NearbyDevice.lanEndpoints`), without duplicates.
     */
    fun lanEndpoints(
        offer: Offer?,
        discovered: List<Endpoint>,
    ): List<Endpoint> {
        val out = LinkedHashSet<Endpoint>()
        offer?.linkOptions?.forEach { option ->
            val address = option.address
            val port = option.port
            if (option.linkKind == LinkKind.LAN && address != null && port != null) {
                runCatching { Endpoint(address, port) }.getOrNull()?.let(out::add)
            }
        }
        out += discovered
        return out.toList()
    }

    private fun describe(e: Throwable): String = if (e is HandshakeException) e.reason.name else e.message ?: e::class.simpleName.orEmpty()

    const val DEFAULT_CONNECT_MILLIS: Long = 1_000
}
