package com.constrivo.drop.ui.shared.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.TimeSource

/**
 * Ignores taps on a security prompt's actions for [armMillis] after the prompt appears or its subject changes.
 *
 * "Allow this computer?", the incoming card and the code confirmations appear unasked, above whatever the user is
 * touching, at a moment another party chooses (a browser on the hotspot can ask again by reloading; a sender decides
 * when an Offer arrives). Without a guard, a tap meant for the bottom bar or a bubble could land on Allow or Accept
 * while the sheet slides in (250 ms) or just after. [ARM_MILLIS] covers the slide plus a human reaction time.
 */
class TapGuard(
    private val now: () -> Long,
    private val armMillis: Long = ARM_MILLIS,
) {
    private val shownAt = now()

    /** Whether a tap now counts. */
    fun allows(): Boolean = now() - shownAt >= armMillis

    /** [action], run only when a tap counts. */
    fun guard(action: () -> Unit): () -> Unit = { if (allows()) action() }

    companion object {
        const val ARM_MILLIS: Long = 600
    }
}

private val appStart = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds for [TapGuard]; tests provide a clock they control. */
val LocalTapClock = staticCompositionLocalOf<() -> Long> { { appStart.elapsedNow().inWholeMilliseconds } }

/** A [TapGuard] that re-arms whenever [keys] change (a new request, a new Offer, a code that just appeared). */
@Composable
fun rememberTapGuard(vararg keys: Any?): TapGuard {
    val clock = LocalTapClock.current
    return remember(*keys) { TapGuard(clock) }
}
