package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.ui.shared.model.BrowserApprovalUi
import com.constrivo.drop.ui.shared.model.BrowserNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * "Allow this computer?" (spec change N15): the phone-side prompt for web-receive's `BrowserApprover`. The app wires it
 * as `BrowserApprover { r -> presenter.ask(r.browserNumber, r.remoteAddress, r.userAgent) }`.
 *
 * Requests queue in arrival order and are shown one at a time. [ask] suspends until the user answers; when the server
 * cancels it (its approval timeout, or shutdown) the prompt is withdrawn. [ask] may be called from any thread (the
 * server asks on its own coroutines); the queue is updated atomically. The user agent is untrusted text: only a
 * browser and OS name recognised from it, or a short sanitised excerpt, is shown ([BrowserNames]).
 */
class BrowserApprovalPresenter(
    scope: CoroutineScope,
) {
    private class Pending(
        val ui: BrowserApprovalUi,
        val answer: CompletableDeferred<Boolean>,
    )

    private val queue = MutableStateFlow<List<Pending>>(emptyList())
    private val ids = MutableStateFlow(0L)

    /** The prompt on screen (the oldest unanswered request), or null. */
    val state: StateFlow<BrowserApprovalUi?> =
        queue.map { it.firstOrNull()?.ui }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Asks the user; true allows this browser. Cancelling the caller withdraws the prompt.
     *
     * @throws IllegalArgumentException when [browserNumber] is not positive.
     */
    suspend fun ask(
        browserNumber: Int,
        remoteAddress: String,
        userAgent: String?,
    ): Boolean {
        require(browserNumber >= 1) { "browser numbers start at 1" }
        val ui =
            BrowserApprovalUi(
                requestId = ids.updateAndGet { it + 1 },
                browserNumber = browserNumber,
                remoteAddress = remoteAddress.filter { it.isLetterOrDigit() || it in ADDRESS_PUNCTUATION }.take(MAX_ADDRESS),
                browser = BrowserNames.describe(userAgent),
            )
        val pending = Pending(ui, CompletableDeferred())
        queue.update { it + pending }
        try {
            return pending.answer.await()
        } finally {
            queue.update { list -> list.filterNot { it === pending } }
        }
    }

    /** Allows the request the user saw ([BrowserApprovalUi.requestId]); a stale or repeated tap does nothing. */
    fun allow(requestId: Long) = answer(requestId, true)

    fun deny(requestId: Long) = answer(requestId, false)

    private fun answer(
        requestId: Long,
        allowed: Boolean,
    ) {
        val before = queue.getAndUpdate { list -> list.filterNot { it.ui.requestId == requestId } }
        before.firstOrNull { it.ui.requestId == requestId }?.answer?.complete(allowed)
    }

    private companion object {
        const val MAX_ADDRESS = 45
        const val ADDRESS_PUNCTUATION = ".:%[]"
    }
}
