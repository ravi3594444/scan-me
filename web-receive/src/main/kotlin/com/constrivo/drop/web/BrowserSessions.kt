package com.constrivo.drop.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import kotlin.random.Random

/**
 * What the phone shows when a browser opens the page: "Allow this computer?" (spec change N15).
 *
 * @property browserNumber 1 for the first browser that presented the token, 2 for the next, and so on; the phone can
 *   word a later request differently ("Another computer wants to connect").
 * @property remoteAddress the browser's IP address on the phone's network.
 * @property userAgent the browser's `User-Agent`, if sent; untrusted text for display only.
 */
data class BrowserApprovalRequest(
    val browserNumber: Int,
    val remoteAddress: String,
    val userAgent: String?,
)

/**
 * Asks the phone user whether a browser may see the files (N15). Called once per new browser, on a background
 * coroutine; returning true serves `/files` and the downloads to that browser, false or throwing counts as "no". A
 * call that does not return within [ReceiveSettings.approvalTimeoutMillis] is cancelled (dismiss the prompt then)
 * and leaves the browser [BrowserState.EXPIRED]: reloading the page asks again, for the same browser.
 */
fun interface BrowserApprover {
    suspend fun approve(request: BrowserApprovalRequest): Boolean
}

/** Where one browser stands with the phone. */
enum class BrowserState {
    /** The phone is being asked. */
    PENDING,

    /** The phone said yes: files, downloads and uploads are served. */
    APPROVED,

    /** The phone said no, or the session closed; final. */
    DENIED,

    /** Nobody answered within the approval timeout; the next page load asks again ([BrowserSessions.retry]). */
    EXPIRED,
}

/**
 * One browser, identified by the session cookie and bound to the address it first came from, so a copied cookie is
 * useless from another computer on the network.
 */
internal class BrowserSession(
    val id: String,
    val number: Int,
    val remoteAddress: String,
    val userAgent: String?,
) {
    /** Download progress this browser asked the server to report ([ReceiveRoutes.PROGRESS]). */
    val downloads = DownloadProgressTable()

    @Volatile
    var state: BrowserState = BrowserState.PENDING
        private set

    /** Completes with the state that ends the current [BrowserState.PENDING] period; replaced by [reopen]. */
    @Volatile
    var decision: CompletableDeferred<BrowserState> = CompletableDeferred()
        private set

    /** Records the phone's answer to the pending request; ignored when nothing is pending. */
    @Synchronized
    fun decide(allowed: Boolean) = settle(if (allowed) BrowserState.APPROVED else BrowserState.DENIED)

    /** The pending request ran out of time without an answer. */
    @Synchronized
    fun expire() = settle(BrowserState.EXPIRED)

    /** Moves an [BrowserState.EXPIRED] browser back to pending; false in any other state. */
    @Synchronized
    fun reopen(): Boolean {
        if (state != BrowserState.EXPIRED) return false
        decision = CompletableDeferred()
        state = BrowserState.PENDING
        return true
    }

    private fun settle(outcome: BrowserState) {
        if (state != BrowserState.PENDING) return
        state = outcome
        decision.complete(outcome)
    }
}

/**
 * The browsers that presented the token (N15). The first request with the right token and no valid cookie creates a
 * session, sets its cookie and asks the phone through the [BrowserApprover]; later browsers each need their own
 * approval, and after [maxBrowsers] sessions (in any state) new browsers are refused without asking,
 * so a leaked QR code cannot flood the phone with prompts.
 */
internal class BrowserSessions(
    private val approver: BrowserApprover,
    private val maxBrowsers: Int,
    private val approvalTimeoutMillis: Long,
    private val random: Random,
    private val scope: CoroutineScope,
) {
    private val sessions = LinkedHashMap<String, BrowserSession>()

    /** The session for [cookie] if it exists and was created from [remoteAddress]. */
    @Synchronized
    fun find(
        cookie: String?,
        remoteAddress: String,
    ): BrowserSession? {
        val session = cookie?.let { sessions[it] } ?: return null
        return if (session.remoteAddress == remoteAddress) session else null
    }

    /** Creates a session for a new browser and starts its approval, or returns null when [maxBrowsers] is reached. */
    @Synchronized
    fun claim(
        remoteAddress: String,
        userAgent: String?,
    ): BrowserSession? {
        if (sessions.size >= maxBrowsers) return null
        val session = BrowserSession(newId(), sessions.size + 1, remoteAddress, userAgent?.take(MAX_USER_AGENT))
        sessions[session.id] = session
        ask(session)
        return session
    }

    /**
     * Asks the phone again for a browser whose request expired unanswered, keeping its session (and its slot among
     * [maxBrowsers]). Returns false, asking nothing, when [session] is not [BrowserState.EXPIRED].
     */
    fun retry(session: BrowserSession): Boolean {
        if (!session.reopen()) return false
        ask(session)
        return true
    }

    private fun ask(session: BrowserSession) {
        val request = BrowserApprovalRequest(session.number, session.remoteAddress, session.userAgent)
        scope.launch {
            val allowed =
                try {
                    withTimeoutOrNull(approvalTimeoutMillis) { approver.approve(request) }
                } catch (e: CancellationException) {
                    session.decide(false)
                    throw e
                } catch (_: Exception) {
                    false
                }
            if (allowed == null) session.expire() else session.decide(allowed)
        }
    }

    /** Snapshot of every session's state, in the order browsers arrived. */
    @Synchronized
    fun states(): List<BrowserState> = sessions.values.map { it.state }

    /** Denies every pending session (the server is shutting down). */
    @Synchronized
    fun denyPending() {
        sessions.values.forEach { it.decide(false) }
    }

    private fun newId(): String {
        while (true) {
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(SESSION_ID_BYTES))
            if (id !in sessions) return id
        }
    }

    private companion object {
        const val SESSION_ID_BYTES = 16
        const val MAX_USER_AGENT = 256
    }
}
