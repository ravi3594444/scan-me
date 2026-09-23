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
 * coroutine; returning true serves `/files` and the downloads to that browser. Throwing, or not answering within
 * [ReceiveSettings.approvalTimeoutMillis], counts as "no".
 */
fun interface BrowserApprover {
    suspend fun approve(request: BrowserApprovalRequest): Boolean
}

/** Where one browser stands with the phone. */
enum class BrowserState { PENDING, APPROVED, DENIED }

/**
 * One browser, identified by the session cookie and bound to the address it first came from, so a copied cookie is
 * useless from another computer on the network.
 */
internal class BrowserSession(
    val id: String,
    val number: Int,
    val remoteAddress: String,
) {
    /** Completes with the phone's answer; [state] already reflects it when it completes. */
    val decision = CompletableDeferred<Boolean>()

    @Volatile private var allowed: Boolean? = null

    val state: BrowserState
        get() =
            when (allowed) {
                null -> BrowserState.PENDING
                true -> BrowserState.APPROVED
                false -> BrowserState.DENIED
            }

    /** Records the first answer; later ones are ignored. */
    @Synchronized
    fun decide(allowed: Boolean) {
        if (this.allowed != null) return
        this.allowed = allowed
        decision.complete(allowed)
    }
}

/**
 * The browsers that presented the token (N15). The first request with the right token and no valid cookie creates a
 * session, sets its cookie and asks the phone through the [BrowserApprover]; later browsers each need their own
 * approval, and after [maxBrowsers] sessions (pending, approved or denied) new browsers are refused without asking,
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
        val id = newId()
        val session = BrowserSession(id, sessions.size + 1, remoteAddress)
        sessions[id] = session
        val request = BrowserApprovalRequest(session.number, remoteAddress, userAgent?.take(MAX_USER_AGENT))
        scope.launch {
            val allowed =
                try {
                    withTimeoutOrNull(approvalTimeoutMillis) { approver.approve(request) } ?: false
                } catch (e: CancellationException) {
                    session.decide(false)
                    throw e
                } catch (_: Exception) {
                    false
                }
            session.decide(allowed)
        }
        return session
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
