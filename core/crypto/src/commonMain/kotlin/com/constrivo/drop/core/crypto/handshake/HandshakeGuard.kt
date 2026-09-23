package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.SynchronizedLock
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.crypto.trust.TrustedProof

/**
 * The device-wide state that every [HandshakeResponder] on this device shares (architecture §6 note). Create one
 * at start-up and pass it to every responder, on every transport; a guard per responder protects nothing.
 * Thread-safe.
 *
 * - [attempts] caps the untrusted handshakes that end unverified, so a man in the middle cannot probe for a
 *   matching SAS ([PairingAttemptLimiter]).
 * - A replay cache remembers every `(identity_pk_A, commitment)` accepted with a trusted proof for
 *   [TrustedProof.REPLAY_WINDOW_SECONDS], so a captured `Hello` cannot be replayed to get past Trusted-only mode or
 *   to fetch a signed `HelloAck` (which would link this device across beacon rotations, F-J1).
 * - [clock] supplies the proof epoch and the monotonic time both use.
 *
 * @param maxRememberedProofs replay-cache capacity. When it is full, further proofs are refused with
 *   [HandshakeFailure.RATE_LIMITED] until old entries expire (only trusted peers can add entries).
 */
class HandshakeGuard(
    /** Time for the proof epoch, the replay window and the limiter. */
    val clock: HandshakeClock = HandshakeClock.SYSTEM,
    /** The limiter for untrusted handshakes. */
    val attempts: PairingAttemptLimiter = PairingAttemptLimiter(clock),
    maxRememberedProofs: Int = DEFAULT_MAX_REMEMBERED_PROOFS,
) {
    init {
        require(maxRememberedProofs > 0) { "maxRememberedProofs must be positive" }
    }

    private val replays = ProofReplayCache(maxRememberedProofs, clock)

    /** What [rememberProof] found. */
    internal enum class ProofUse { FIRST, REPLAYED, CACHE_FULL }

    /** Records that a `Hello` with a valid proof was accepted, unless the same one was accepted before. */
    internal fun rememberProof(
        identityKey: ByteArray,
        commitment: ByteArray,
    ): ProofUse = replays.remember(identityKey, commitment)

    companion object {
        /** Default replay-cache capacity: far more trusted handshakes than a device makes in 45 minutes. */
        const val DEFAULT_MAX_REMEMBERED_PROOFS: Int = 1024
    }
}

/** The `(identity_pk_A, commitment)` pairs accepted with a trusted proof in the last [TrustedProof.REPLAY_WINDOW_SECONDS]. */
private class ProofReplayCache(
    private val capacity: Int,
    private val clock: HandshakeClock,
) {
    private val lock = SynchronizedLock()

    /** hex(identity ‖ commitment) → monotonic time of acceptance, oldest first. */
    private val seen = LinkedHashMap<String, Long>()

    fun remember(
        identityKey: ByteArray,
        commitment: ByteArray,
    ): HandshakeGuard.ProofUse =
        lock.withLock {
            val now = clock.monotonicMillis()
            val entries = seen.entries.iterator()
            while (entries.hasNext()) {
                if (now - entries.next().value < WINDOW_MILLIS) break
                entries.remove()
            }
            val key = (identityKey + commitment).toHex()
            when {
                key in seen -> HandshakeGuard.ProofUse.REPLAYED
                seen.size >= capacity -> HandshakeGuard.ProofUse.CACHE_FULL
                else -> HandshakeGuard.ProofUse.FIRST.also { seen[key] = now }
            }
        }

    private companion object {
        const val WINDOW_MILLIS: Long = TrustedProof.REPLAY_WINDOW_SECONDS * 1000
    }
}
