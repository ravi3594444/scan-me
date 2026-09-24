package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.model.BrowserShareHint
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

    /** The browser path's network and address once it runs (N15), else null. */
    val browserHint: Flow<BrowserShareHint?>

    /** Starts the hotspot or group and the receive server for a computer without the app. */
    fun startBrowserShare()

    companion object {
        /** No code available (the engine is not wired yet): the sheet shows the nickname only. */
        val None: MyCodeSource =
            object : MyCodeSource {
                override suspend fun current(): MyCode = throw IllegalStateException("no code source")

                override val browserHint: Flow<BrowserShareHint?> = flowOf(null)

                override fun startBrowserShare() = Unit
            }
    }
}

/**
 * The "Show my code" sheet (design §4.4 with N15): a QR code of at least 240 dp encoded once per payload, the nickname,
 * the fallback code, and the "computer without the app" hint with the real SSID, password and
 * `http://drop.local:<port>/t/<token>/` address. While open it re-signs the code when it expires (every 5 minutes) and
 * drives the subtle refresh arc, ticking once a second on [wallClock].
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
        val starting: Boolean,
    )

    private val shown = MutableStateFlow<Shown?>(null)
    private var job: Job? = null

    val state: StateFlow<ShowQrUi?> =
        combine(shown, profile, source.browserHint) { s, me, hint ->
            s?.let {
                ShowQrUi(
                    nickname = me.nickname,
                    matrix = it.matrix,
                    fallbackCode = it.code?.fallbackCode,
                    refreshFraction = it.fraction,
                    browserHint = hint,
                    browserStarting = it.starting && hint == null,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val isOpen: Boolean get() = shown.value != null

    fun open() {
        if (job?.isActive == true) return
        shown.value = Shown(null, null, 0f, starting = false)
        job = scope.launch { refreshLoop() }
    }

    fun close() {
        job?.cancel()
        job = null
        shown.value = null
    }

    fun startBrowserShare() {
        val s = shown.value ?: return
        shown.value = s.copy(starting = true)
        source.startBrowserShare()
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
                shown.value = shown.value?.copy(code = code, matrix = matrix, fraction = fraction) ?: return
                if (end == null) return
                if (now >= end) break
                delay(minOf(TICK_MILLIS, end - now))
            }
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val RETRY_MILLIS = 5_000L
    }
}
