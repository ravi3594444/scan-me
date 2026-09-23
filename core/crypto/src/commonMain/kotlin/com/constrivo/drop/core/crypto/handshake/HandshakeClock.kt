package com.constrivo.drop.core.crypto.handshake

import kotlin.time.TimeSource

/**
 * The time sources of the handshake: wall-clock time for the trusted-proof epoch (architecture §5.3), and a monotonic
 * clock for [PairingAttemptLimiter] and the proof replay window. Tests inject a fake one.
 */
interface HandshakeClock {
    /** Wall-clock unix time in seconds. */
    fun unixSeconds(): Long

    /** Milliseconds from an arbitrary fixed origin that never go backwards; only differences are meaningful. */
    fun monotonicMillis(): Long

    companion object {
        /** The system clocks. */
        val SYSTEM: HandshakeClock =
            object : HandshakeClock {
                private val origin = TimeSource.Monotonic.markNow()

                override fun unixSeconds(): Long = currentUnixMillis().floorDiv(1000L)

                override fun monotonicMillis(): Long = origin.elapsedNow().inWholeMilliseconds
            }
    }
}

/** The platform's wall clock in unix milliseconds. */
internal expect fun currentUnixMillis(): Long
