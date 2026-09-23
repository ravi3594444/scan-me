package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.web.zip.StoredZipLayout
import com.constrivo.drop.web.zip.StoredZipWriter
import com.constrivo.drop.web.zip.ZipEntrySpec
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * One browser-receive session: the files on offer, the token that guards them, the browsers the phone approved, and
 * the transfers in flight (architecture §10.3 as changed by spec change N15; design §10; F‑D6, F‑H4).
 *
 * Install it into a Ktor application with [receiveModule]; [ReceiveServer] does that on a CIO engine bound to the link
 * interface. The HTTP contract:
 *
 * - Every path outside `/t/<token>/` ([ReceiveRoutes]) is 404, as is everything once the session is [close]d.
 * - The first request with the token and no valid session cookie creates a browser session: an `HttpOnly`,
 *   `SameSite=Strict` cookie scoped to the prefix, bound to the browser's IP address, and an approval request to the
 *   phone ([BrowserApprover]). Up to [ReceiveSettings.maxBrowsers] browsers are asked for; later ones get 403
 *   `{"status":"refused"}`.
 * - `GET /` serves the page to any browser with a session. `GET files` answers 200 with the list once the phone
 *   approved, holds a pending browser for up to [ReceiveSettings.approvalWaitMillis] and then answers 202
 *   `{"status":"pending"}`, and answers 403 `{"status":"denied"}` after a "no". Downloads and uploads need an approved
 *   browser (403 `pending` or `denied` otherwise).
 * - `GET file/{i}` streams one file with `Content-Length`, an RFC 6266 `Content-Disposition`, a strong `ETag` and
 *   single-range support (206, 416, `If-Range`).
 * - `GET all.zip` streams every file as a STORED zip ([StoredZipLayout]) with an exact `Content-Length`.
 * - `POST upload?name=…` (P1, only with [upload]) takes one file as the raw body with `Content-Length` (411 without,
 *   413 past the cap, 400 when the body ends early) into the [UploadSink].
 * - Every response carries `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and
 *   `Referrer-Policy: no-referrer`; file responses also a `sandbox` CSP so an HTML file can never run in the page's
 *   origin.
 *
 * [awaitIdle] returns once the idle timeout passed after the last transfer ([TransferActivity]).
 */
class ReceiveSession(
    val token: ReceiveToken,
    val offer: ReceiveOffer,
    approver: BrowserApprover,
    val upload: UploadSettings? = null,
    val settings: ReceiveSettings = ReceiveSettings(),
    monotonicClock: MonotonicClock = SystemMonotonicClock,
    wallClock: WallClock = SystemWallClock,
    random: Random = SecureRandom().asKotlinRandom(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Transfers in flight and the idle timer. */
    val activity = TransferActivity(monotonicClock, settings.idleTimeoutMillis)

    private val browsers = BrowserSessions(approver, settings.maxBrowsers, settings.approvalTimeoutMillis, random, scope)
    private val zipLayout =
        StoredZipLayout(offer.displayNames.mapIndexed { i, name -> ZipEntrySpec(name, offer.files[i].size) }, settings.forceZip64)
    private val dosDateTime = StoredZipWriter.dosDateTime(wallClock.nowMillis())
    private val etagNonce = random.nextBytes(ETAG_NONCE_BYTES).joinToString("") { "%02x".format(it) }

    /** `/t/<token>/`, the path the QR code points at. */
    val pathPrefix: String = ReceiveRoutes.prefix(token)

    /** Exact size of `all.zip` in bytes. */
    val archiveSize: Long get() = zipLayout.totalSize

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Each browser's approval state, in arrival order. */
    fun browserStates(): List<BrowserState> = browsers.states()

    /** Suspends until the session is idle ([TransferActivity.isIdle]) or closed. */
    suspend fun awaitIdle() {
        while (!isClosed && !activity.isIdle()) delay(settings.idleCheckIntervalMillis)
    }

    /** Revokes the token (every request is 404 from now on) and denies browsers still waiting for approval. */
    override fun close() {
        isClosed = true
        browsers.denyPending()
        scope.cancel()
    }

    /** Handles one request; [receiveModule] routes every request here. */
    suspend fun handle(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        val route = if (isClosed) null else Route.match(call.request.path(), token, offer.files.size)
        if (route == null || (route == Route.Upload && upload == null)) return notFound(call)
        if (route == Route.Redirect) return call.respondRedirect(pathPrefix, permanent = false)
        val method = if (route == Route.Upload) HttpMethod.Post else HttpMethod.Get
        if (call.request.httpMethod != method) {
            call.response.header(HttpHeaders.Allow, method.value)
            return call.respondText("Method not allowed", ContentType.Text.Plain, HttpStatusCode.MethodNotAllowed)
        }

        val remote = call.request.local.remoteAddress
        var browser = browsers.find(call.request.cookies[COOKIE], remote)
        if (browser == null) {
            browser = browsers.claim(remote, call.request.headers[HttpHeaders.UserAgent]) ?: return refused(call, route)
            call.response.cookies.append(
                Cookie(
                    COOKIE,
                    browser.id,
                    encoding = CookieEncoding.RAW,
                    path = pathPrefix,
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
        }

        when (route) {
            Route.Page -> {
                servePage(call)
            }

            Route.Files -> {
                serveFiles(call, browser)
            }

            else -> {
                if (!approved(call, browser)) return
                when (route) {
                    is Route.File -> serveFile(call, route.index)
                    Route.Zip -> serveZip(call)
                    Route.Upload -> receiveUpload(call, upload ?: return notFound(call))
                }
            }
        }
    }

    private suspend fun servePage(call: ApplicationCall) {
        call.response.header("Content-Security-Policy", ReceivePage.contentSecurityPolicy)
        call.response.header("X-Frame-Options", "DENY")
        call.respondBytes(ReceivePage.bytes, ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.OK)
    }

    private suspend fun serveFiles(
        call: ApplicationCall,
        browser: BrowserSession,
    ) {
        if (browser.state == BrowserState.PENDING) {
            withTimeoutOrNull(settings.approvalWaitMillis) { browser.decision.await() }
        }
        if (isClosed) return notFound(call)
        when (browser.state) {
            BrowserState.APPROVED -> json(call, HttpStatusCode.OK, filesJson())
            BrowserState.PENDING -> json(call, HttpStatusCode.Accepted, status("pending"))
            BrowserState.DENIED -> json(call, HttpStatusCode.Forbidden, status("denied"))
        }
    }

    private suspend fun approved(
        call: ApplicationCall,
        browser: BrowserSession,
    ): Boolean {
        when (browser.state) {
            BrowserState.APPROVED -> return true
            BrowserState.PENDING -> json(call, HttpStatusCode.Forbidden, status("pending"))
            BrowserState.DENIED -> json(call, HttpStatusCode.Forbidden, status("denied"))
        }
        return false
    }

    private fun filesJson(): JsonObject =
        buildJsonObject {
            put("status", "ok")
            put("sender", offer.senderName)
            put("summary", offer.summary)
            put("count", offer.files.size)
            put("totalBytes", offer.totalBytes)
            putJsonObject("archive") {
                put("name", offer.archiveName)
                put("path", ReceiveRoutes.ALL_ZIP)
                put("size", zipLayout.totalSize)
            }
            val up = upload
            if (up != null) {
                putJsonObject("upload") {
                    put("path", ReceiveRoutes.UPLOAD)
                    put("maxBytes", up.remainingBytes)
                }
            } else {
                put("upload", JsonNull)
            }
            putJsonArray("files") {
                offer.files.forEachIndexed { i, file ->
                    addJsonObject {
                        put("index", i)
                        put("name", offer.displayNames[i])
                        put("size", file.size)
                        put("mime", offer.mimeTypes[i])
                        put("path", ReceiveRoutes.file(i))
                    }
                }
            }
        }

    private suspend fun serveFile(
        call: ApplicationCall,
        index: Int,
    ) {
        val file = offer.files[index]
        val size = file.size
        val etag = "\"$etagNonce-$index-$size\""
        val ifRange = call.request.headers[HttpHeaders.IfRange]?.trim()
        val range =
            if (ifRange != null && ifRange != etag) {
                RangeRequest.Whole
            } else {
                RangeRequest.parse(call.request.headers[HttpHeaders.Range], size)
            }
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ContentDisposition, FileNames.contentDisposition(offer.displayNames[index]))
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        val (start, length, status) =
            when (range) {
                RangeRequest.Whole -> {
                    Triple(0L, size, HttpStatusCode.OK)
                }

                is RangeRequest.Part -> {
                    call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
                    Triple(range.first, range.length, HttpStatusCode.PartialContent)
                }

                RangeRequest.Unsatisfiable -> {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                    return call.respondText("", ContentType.Text.Plain, HttpStatusCode.RequestedRangeNotSatisfiable)
                }
            }
        val input =
            try {
                withContext(Dispatchers.IO) { file.open(start) }
            } catch (_: IOException) {
                return json(call, HttpStatusCode.Gone, status("unavailable"))
            }
        input.use {
            activity.track {
                call.respondOutputStream(contentType(offer.mimeTypes[index]), status, length) {
                    copyExactly(input, this, length)
                }
            }
        }
    }

    private suspend fun serveZip(call: ApplicationCall) {
        call.response.header(HttpHeaders.ContentDisposition, FileNames.contentDisposition(offer.archiveName))
        call.response.header(HttpHeaders.AcceptRanges, "none")
        val writer = StoredZipWriter(zipLayout, dosDateTime, settings.bufferSize)
        activity.track {
            call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK, zipLayout.totalSize) {
                writer.write(this, { i -> offer.files[i].open(0) }, activity::addSent)
            }
        }
    }

    private suspend fun receiveUpload(
        call: ApplicationCall,
        upload: UploadSettings,
    ) {
        val length =
            call.request.contentLength() ?: return json(call, HttpStatusCode.LengthRequired, status("length_required"), close = true)
        if (length < 0 || !upload.reserve(length)) {
            val body =
                buildJsonObject {
                    put("status", "too_large")
                    put("maxBytes", upload.remainingBytes)
                }
            return json(call, HttpStatusCode.PayloadTooLarge, body, close = true)
        }
        val name = FileNames.sanitize(call.request.queryParameters[ReceiveRoutes.UPLOAD_NAME_PARAMETER].orEmpty(), fallback = "upload")
        val mime = MimeTypes.sanitize(call.request.headers[HttpHeaders.ContentType])
        val saved =
            try {
                activity.track { receiveInto(call, upload.sink, name, length, mime) }
            } catch (e: IOException) {
                upload.release(length)
                val code = if (e is EOFException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
                return json(call, code, status(if (e is EOFException) "incomplete" else "failed"), close = true)
            } catch (e: Throwable) {
                upload.release(length)
                throw e
            }
        json(
            call,
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", "ok")
                put("name", saved)
                put("size", length)
                put("maxBytes", upload.remainingBytes)
            },
        )
    }

    /** Streams exactly [length] body bytes into a new [UploadWriter]; returns the saved name. */
    private suspend fun receiveInto(
        call: ApplicationCall,
        sink: UploadSink,
        name: String,
        length: Long,
        mime: String,
    ): String {
        val writer = withContext(Dispatchers.IO) { sink.open(name, length, mime) }
        var committed = false
        try {
            val channel = call.receiveChannel()
            val buffer = ByteArray(settings.bufferSize)
            var remaining = length
            while (remaining > 0) {
                val n = channel.readAvailable(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) throw EOFException("upload ended $remaining bytes early")
                if (n == 0) continue
                withContext(Dispatchers.IO) { writer.write(buffer, 0, n) }
                remaining -= n
                activity.addReceived(n)
            }
            val saved = withContext(Dispatchers.IO) { writer.commit() }
            committed = true
            return saved
        } finally {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { writer.abort() }
        }
    }

    private fun copyExactly(
        input: InputStream,
        out: OutputStream,
        length: Long,
    ) {
        val buffer = ByteArray(settings.bufferSize)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw SharedFileChangedException("file ended $remaining bytes before its promised size")
            out.write(buffer, 0, n)
            remaining -= n
            activity.addSent(n)
        }
    }

    private suspend fun refused(
        call: ApplicationCall,
        route: Route,
    ) {
        if (route == Route.Page) {
            call.respondText(REFUSED_PAGE, ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.Forbidden)
        } else {
            json(call, HttpStatusCode.Forbidden, status("refused"))
        }
    }

    private suspend fun notFound(call: ApplicationCall) = call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)

    private suspend fun json(
        call: ApplicationCall,
        code: HttpStatusCode,
        body: JsonObject,
        close: Boolean = false,
    ) {
        if (close) call.response.header(HttpHeaders.Connection, "close")
        call.respondText(body.toString(), ContentType.Application.Json, code)
    }

    private fun status(value: String): JsonObject = buildJsonObject { put("status", value) }

    private fun contentType(mime: String): ContentType =
        try {
            ContentType.parse(mime)
        } catch (_: Exception) {
            ContentType.Application.OctetStream
        }

    /** The endpoints of [ReceiveRoutes], matched against a raw request path. */
    internal sealed interface Route {
        data object Redirect : Route

        data object Page : Route

        data object Files : Route

        data object Zip : Route

        data object Upload : Route

        data class File(
            val index: Int,
        ) : Route

        companion object {
            private val PREFIX = "/${ReceiveRoutes.TOKEN_SEGMENT}/"
            private const val FILE_PREFIX = "file/"
            private const val MAX_INDEX_DIGITS = 9

            /** The route for [path], or null when it is not an endpoint of the session guarded by [token]. */
            fun match(
                path: String,
                token: ReceiveToken,
                fileCount: Int,
            ): Route? {
                if (!path.startsWith(PREFIX)) return null
                val afterPrefix = path.substring(PREFIX.length)
                val slash = afterPrefix.indexOf('/')
                val candidate = if (slash < 0) afterPrefix else afterPrefix.substring(0, slash)
                if (!token.matches(candidate)) return null
                if (slash < 0) return Redirect
                val rest = afterPrefix.substring(slash + 1)
                return when (rest) {
                    ReceiveRoutes.PAGE -> Page
                    ReceiveRoutes.FILES -> Files
                    ReceiveRoutes.ALL_ZIP -> Zip
                    ReceiveRoutes.UPLOAD -> Upload
                    else -> fileRoute(rest, fileCount)
                }
            }

            private fun fileRoute(
                rest: String,
                fileCount: Int,
            ): Route? {
                if (!rest.startsWith(FILE_PREFIX)) return null
                val digits = rest.substring(FILE_PREFIX.length)
                // Canonical decimal only: no sign, no leading zeros, at most 9 digits.
                val canonical =
                    digits.length in 1..MAX_INDEX_DIGITS && digits.all { it in '0'..'9' } && (digits.length == 1 || digits[0] != '0')
                if (!canonical) return null
                val index = digits.toInt()
                return if (index < fileCount) File(index) else null
            }
        }
    }

    private companion object {
        const val COOKIE = "drop_session"
        const val ETAG_NONCE_BYTES = 6
        const val REFUSED_PAGE =
            "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>Link already used</title>" +
                "<p style=\"font:16px system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 16px\">" +
                "This link is already open on another computer. Ask for a new code on the phone.</p>"
    }
}

/** Routes every request of this application to [session] ([ReceiveSession.handle]). */
fun Application.receiveModule(session: ReceiveSession) {
    routing {
        route("{...}") {
            handle { session.handle(call) }
        }
    }
}
