package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.SynchronizedLock

/**
 * Caps the untrusted handshakes that end without a verified `HelloReveal` (architecture §6 note, SAS).
 *
 * Why: a man in the middle `M` that initiates towards `B` knows every SAS input as soon as `B`'s `HelloAck` arrives,
 * before `M` reveals anything. It can compare `B`'s code with the one the other victim already shows, drop the
 * connection when they differ, and start again with a fresh `Hello`. `B` draws a new ephemeral key each time and
 * shows nothing. Commit-then-reveal makes each attempt a single 10⁻⁶ guess; this limiter caps how many of those
 * silent guesses `M` gets.
 *
 * Rules (defaults in [Policy]):
 * - One untrusted handshake at a time. While one is in flight, [tryAcquire] returns null. An in-flight handshake
 *   not settled within [Policy.inFlightTimeoutMillis] counts as failed.
 * - Every untrusted handshake that ends without a verified `HelloReveal` (bad reveal, connection dropped,
 *   [HandshakeResponder.abort], time-out), and every [recordFailure] from the UI, is a failure. The first
 *   [Policy.freeFailures] cost nothing; each further one blocks new untrusted handshakes for
 *   [Policy.firstLockoutMillis], doubling every time, up to [Policy.maxLockoutMillis].
 * - One failure is forgiven for every [Policy.forgiveAfterMillis] without a new one. A verified handshake neither
 *   adds nor removes failures.
 *
 * With the defaults an attacker gets 11 guesses in the first hour and then about one per hour, whatever its timing:
 * under 10,000 a year, so a year of continuous probing succeeds with probability about 1 %. A legitimate user who
 * fails a few pairings is not slowed down.
 *
 * Handshakes with a valid trusted proof do not go through the limiter, so a lockout never blocks trusted peers. Use
 * one instance per device, shared by every responder on every transport ([HandshakeGuard]). Thread-safe.
 */
class PairingAttemptLimiter(
    private val clock: HandshakeClock = HandshakeClock.SYSTEM,
    /** The limits in force. */
    val policy: Policy = Policy(),
) {
    /**
     * The limits. All times are milliseconds on [HandshakeClock.monotonicMillis].
     *
     * @property freeFailures failures tolerated before the first lockout.
     * @property firstLockoutMillis the first lockout; each further failure doubles it.
     * @property maxLockoutMillis the longest lockout.
     * @property forgiveAfterMillis one failure is forgiven per this much time without a new failure.
     * @property inFlightTimeoutMillis an untrusted handshake still unsettled after this long counts as failed.
     */
    class Policy(
        val freeFailures: Int = 5,
        val firstLockoutMillis: Long = 60_000,
        val maxLockoutMillis: Long = 3_600_000,
        val forgiveAfterMillis: Long = 3_600_000,
        val inFlightTimeoutMillis: Long = 30_000,
    ) {
        init {
            require(freeFailures >= 0) { "freeFailures must be non-negative" }
            require(firstLockoutMillis > 0 && maxLockoutMillis >= firstLockoutMillis) { "lockouts must be positive and ordered" }
            require(forgiveAfterMillis > 0) { "forgiveAfterMillis must be positive" }
            require(inFlightTimeoutMillis > 0) { "inFlightTimeoutMillis must be positive" }
        }
    }

    private val lock = SynchronizedLock()
    private var strikes = 0
    private var lastStrikeAt = 0L
    private var lockedUntil = 0L
    private var locked = false
    private var inFlight: Permit? = null

    /**
     * The slot of one untrusted handshake. Settle it exactly once: [succeeded] after the `HelloReveal` verified,
     * [failed] if the handshake ended in any other way. Later calls have no effect.
     */
    inner class Permit internal constructor(
        internal val startedAt: Long,
    ) {
        internal var settled = false

        /** The handshake verified. Frees the slot without a failure. */
        fun succeeded() = settle(Outcome.SUCCEEDED)

        /** The handshake ended without a verified `HelloReveal`. Frees the slot and counts a failure. */
        fun failed() = settle(Outcome.FAILED)

        /** Nothing about this device reached the peer (a local error before the `HelloAck` left). No failure. */
        internal fun cancelled() = settle(Outcome.CANCELLED)

        private fun settle(outcome: Outcome) {
            lock.withLock {
                if (settled) return@withLock
                settled = true
                if (inFlight === this) inFlight = null
                if (outcome == Outcome.FAILED) strikeLocked(clock.monotonicMillis())
            }
        }
    }

    private enum class Outcome { SUCCEEDED, FAILED, CANCELLED }

    /**
     * Reserves the slot for a new untrusted handshake, or returns null if new ones are locked out or another is in
     * flight. A refusal is not a failure.
     */
    fun tryAcquire(): Permit? =
        lock.withLock {
            val now = clock.monotonicMillis()
            expireLocked(now)
            if (locked && now < lockedUntil) return@withLock null
            if (inFlight != null) return@withLock null
            Permit(now).also { inFlight = it }
        }

    /**
     * Counts a failure the protocol cannot see, for example the user rejecting a code that did not match or
     * cancelling the comparison. Every attempt then costs the attacker the same, whether or not it completed.
     */
    fun recordFailure() {
        lock.withLock { strikeLocked(clock.monotonicMillis()) }
    }

    /** Milliseconds until [tryAcquire] can succeed again because of a lockout; 0 if none is in force. */
    fun lockoutRemainingMillis(): Long =
        lock.withLock {
            val now = clock.monotonicMillis()
            expireLocked(now)
            if (locked && now < lockedUntil) lockedUntil - now else 0L
        }

    /** Failures counted now, after forgiveness (for diagnostics and a "failed pairing attempts" notice). */
    val failures: Int
        get() =
            lock.withLock {
                val now = clock.monotonicMillis()
                expireLocked(now)
                effectiveStrikes(now)
            }

    /** Settles an in-flight permit that has timed out, as a failure at its deadline. */
    private fun expireLocked(now: Long) {
        val permit = inFlight ?: return
        val deadline = permit.startedAt + policy.inFlightTimeoutMillis
        if (now < deadline) return
        permit.settled = true
        inFlight = null
        strikeLocked(deadline)
    }

    private fun effectiveStrikes(now: Long): Int {
        if (strikes == 0) return 0
        val forgiven = (now - lastStrikeAt).coerceAtLeast(0) / policy.forgiveAfterMillis
        return if (forgiven >= strikes) 0 else strikes - forgiven.toInt()
    }

    private fun strikeLocked(at: Long) {
        val time = maxOf(at, lastStrikeAt)
        strikes = effectiveStrikes(time) + 1
        lastStrikeAt = time
        val excess = strikes - policy.freeFailures
        if (excess <= 0) return
        var lockout = policy.firstLockoutMillis
        for (i in 1 until excess) {
            if (lockout >= policy.maxLockoutMillis) break
            lockout = if (lockout > policy.maxLockoutMillis / 2) policy.maxLockoutMillis else lockout * 2
        }
        val until = time + minOf(lockout, policy.maxLockoutMillis)
        if (!locked || until > lockedUntil) lockedUntil = until
        locked = true
    }
}
