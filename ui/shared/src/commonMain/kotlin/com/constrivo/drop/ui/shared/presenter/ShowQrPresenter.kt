package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.ShowQrUi
import com.constrivo.drop.ui.shared.qr.QrEncodeException
import com.constrivo.drop.ui.shared.qr.QrMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * This device's code for "Show my code" (F‑B5, architecture §6.3): the base64url QR payload the app signs, the fallback
 * code to type, and its validity on the wall clock. A static code (F‑B6) has no [expiresAtMillis].
 */
data class MyCode(
    val payload: String,
    val fallbackCode: String?,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long?,
)

/** Where "Show my code" gets its content (the app layer signs a new QR payload on each call). */
interface MyCodeSource {
    /** A freshly signed code, valid for 5 minutes (architecture §6.3). */
    suspend fun current(): MyCode

    /** Where the browser path for a computer without the app stands (N15, architecture §10.3). */
    val browserShare: Flow<BrowserShareState>

    /**
     * Starts the hotspot or group and the receive server, serving [files] to a computer without the app. Progress and
     * failure come back through [browserShare].
     */
    fun startBrowserShare(files: AttachedFiles)

    /**
     * The user closed the sheet: offer the page to no new browser and take the hotspot or group down once no download
     * is running (the app layer decides how long a running download may finish; web-receive shuts its server down
     * 60 s after the last one).
     */
    fun stopBrowserShare()

    companion object {
        /** No code available (the engine is not wired yet): the sheet shows the nickname only. */
        val None: MyCodeSource =
            object : MyCodeSource {
                override suspend fun current(): MyCode = throw IllegalStateException("no code source")

                override val browserShare: Flow<BrowserShareState> = flowOf(BrowserShareState.Idle)

                override fun startBrowserShare(files: AttachedFiles) = Unit

                override fun stopBrowserShare() = Unit
            }
    }
}

/**
 * The "Show my code" sheet (design §4.4 with N15): a QR code of at least 240 dp encoded once per payload, the nickname,
 * the fallback code, and the "computer without the app" hint with the real SSID, password and
 * `http://drop.local:<port>/t/<token>/` address plus the IP address form (design §10). While open it re-signs the code
 * when it expires (every 5 minutes) and drives the subtle refresh arc, ticking once a second on [wallClock]; while the
 * app is in the background ([pause]) it does neither. Once the browser path runs, the sheet can show a QR code of the
 * page's address instead. Closing the sheet stops the browser path it started.
 */
class ShowQrPresenter(
    private val scope: CoroutineScope,
    private val source: MyCodeSource,
    profile: Flow<SelfProfile>,
    private val wallClock: WallClock,
) {
    private data class Shown(
        val code: MyCode?,
        val matrix: QrMatrix?,
        val fraction: Float,
        val browserCode: Boolean = false,
    )

    private val shown = MutableStateFlow<Shown?>(null)
    private var job: Job? = null
    private var paused = false
    private var browserStarted = false

    // The page's QR code, encoded once per address.
    private var browserQr: Pair<String, QrMatrix?>? = null

    val state: StateFlow<ShowQrUi?> =
        combine(shown, profile, source.browserShare) { s, me, share ->
            s?.let {
                val hint = (share as? BrowserShareState.Ready)?.hint
                val pageQr = hint?.let { h -> browserMatrix(h.ipUrl ?: h.url) }
                ShowQrUi(
                    nickname = me.nickname,
                    matrix = it.matrix,
                    fallbackCode = it.code?.fallbackCode,
                    refreshFraction = it.fraction,
                    browserHint = hint,
                    browserStarting = share == BrowserShareState.Starting,
                    browserFailed = share == BrowserShareState.Failed,
                    browserMatrix = pageQr,
                    showingBrowserCode = it.browserCode && pageQr != null,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val isOpen: Boolean get() = shown.value != null

    /** Whether the refresh loop is running (tests; the loop stops while the sheet is closed or the app is paused). */
    val isRefreshing: Boolean get() = job?.isActive == true

    fun open() {
        if (shown.value != null) return
        shown.value = Shown(null, null, 0f)
        startLoop()
    }

    fun close() {
        job?.cancel()
        job = null
        shown.value = null
        if (browserStarted) {
            browserStarted = false
            source.stopBrowserShare()
        }
    }

    /** The app went to the background: no ticks, no re-signing, until [resume]. */
    fun pause() {
        paused = true
        job?.cancel()
        job = null
    }

    /** The app is in front again: a sheet left open refreshes its code at once. */
    fun resume() {
        paused = false
        if (shown.value != null) startLoop()
    }

    /** Starts the browser path serving [files] (the controller chooses them). */
    fun startBrowserShare(files: AttachedFiles) {
        if (shown.value == null || files.items.isEmpty()) return
        browserStarted = true
        source.startBrowserShare(files)
    }

    /** Switches between the app's code and the page's code (only once the browser path runs). */
    fun toggleBrowserCode() {
        shown.update { it?.copy(browserCode = !it.browserCode) }
    }

    private fun startLoop() {
        if (paused || job?.isActive == true) return
        job = scope.launch { refreshLoop() }
    }

    private fun browserMatrix(url: String): QrMatrix? {
        browserQr?.let { (u, m) -> if (u == url) return m }
        val m =
            try {
                QrMatrix.encode(url)
            } catch (_: QrEncodeException) {
                null
            }
        browserQr = url to m
        return m
    }

    private suspend fun refreshLoop() {
        while (true) {
            val code =
                try {
                    source.current()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No code (engine not wired, key store locked): keep the sheet with the nickname and retry later.
                    delay(RETRY_MILLIS)
                    continue
                }
            val fetchedAt = wallClock.nowMillis()
            val matrix =
                try {
                    QrMatrix.encode(code.payload)
                } catch (_: QrEncodeException) {
                    null
                }
            val end = code.expiresAtMillis
            while (true) {
                val now = wallClock.nowMillis()
                val fraction =
                    if (end == null || end <= code.issuedAtMillis) {
                        0f
                    } else {
                        ((now - code.issuedAtMillis).toFloat() / (end - code.issuedAtMillis)).coerceIn(0f, 1f)
                    }
                shown.update { it?.copy(code = code, matrix = matrix, fraction = fraction) }
                if (shown.value == null || end == null) return
                if (now >= end) break
                delay(minOf(TICK_MILLIS, end - now))
            }
            // A code that was already over when it arrived (a source that caches, or whole-second expiry that is still
            // "valid" for up to a second past its end) would otherwise be fetched again at once, in a tight loop.
            if (end <= fetchedAt || end <= code.issuedAtMillis) delay(TICK_MILLIS)
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val RETRY_MILLIS = 5_000L
    }
}
