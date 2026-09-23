package com.constrivo.drop.core.transfer

import kotlinx.coroutines.flow.StateFlow

/** Mirrors Android's PowerManager thermal status levels; desktops report [NONE]. */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** Something to release when a transfer ends (a wake lock, a keep-awake assertion). */
fun interface Releasable {
    fun release()
}

/** Stay awake and cool (architecture §9): wake locks and the thermal signal that lowers the stream count. */
interface PowerPolicy {
    val thermal: StateFlow<ThermalLevel>

    fun keepAwake(reason: String): Releasable
}
