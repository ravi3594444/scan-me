package com.constrivo.drop.platform.android.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs [work] on one coroutine of [scope] after [request], so the callers (network callbacks on the connectivity
 * thread, broadcasts on the main thread) only signal and never block, and runs never overlap: a detection that started
 * earlier can never publish after a later one. Requests made while [work] runs are conflated into one more run, which
 * starts after them, so the last run always sees the latest state.
 */
internal class ConflatedRefresh(
    scope: CoroutineScope,
    private val work: () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // A platform call failed in a way the guards did not expect: keep the last result and keep serving.
                }
            }
        }
    }

    /** Asks for a run; returns at once. */
    fun request() {
        requests.trySend(Unit)
    }
}
