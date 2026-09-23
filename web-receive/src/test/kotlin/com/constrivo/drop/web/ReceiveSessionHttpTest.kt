package com.constrivo.drop.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiveSessionHttpTest {
    private val photo = BytesFile("Fotó 日本.jpg", randomBytes(200_000, 11), "image/jpeg")
    private val note = BytesFile("note.txt", "hello from the phone\n".toByteArray(), "text/plain")
    private val empty = BytesFile("empty.bin", ByteArray(0), null)
    private val page = BytesFile("../evil<name>.html", "<script>alert(1)</script>".toByteArray(), "text/html")
    private val files = listOf(photo, note, empty, page)
    private val prefix = ReceiveRoutes.prefix(TEST_TOKEN)

    private fun session(
        approver: BrowserApprover = ScriptedApprover { true },
        upload: UploadSettings? = null,
        settings: ReceiveSettings = ReceiveSettings(approvalWaitMillis = 5_000),
        offerFiles: List<SharedFile> = files,
    ) = ReceiveSession(
        TEST_TOKEN,
        ReceiveOffer("Dev", offerFiles),
        approver,
        upload,
        settings,
        FakeMonotonicClock(),
        FIXED_WALL_CLOCK,
        Random(42),
    )

    private fun receiveTest(
        session: ReceiveSession,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { receiveModule(session) }
        block()
    }

    private fun ApplicationTestBuilder.browser(): HttpClient =
        createClient {
            install(HttpCookies)
            followRedirects = false
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Opens the page, waits for the approval, and returns the client with its session cookie. */
    private suspend fun ApplicationTestBuilder.approvedBrowser(): HttpClient {
        val client = browser()
        assertEquals(HttpStatusCode.OK, client.get(prefix).status)
        assertEquals(HttpStatusCode.OK, client.get(prefix + "files").status)
        return client
    }

    @Test
    fun pathsWithoutTheRightTokenAre404() =
        receiveTest(session()) {
            val client = browser()
            for (path in listOf(
                "/",
                "/files",
                "/file/0",
                "/all.zip",
                "/t/",
                "/t/wrongtoken0/",
                "/t/wrongtoken0/files",
                "/t/${TEST_TOKEN.value}x/",
                "/x/t/${TEST_TOKEN.value}/",
                "/favicon.ico",
            )) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, path)
                assertNull(response.headers[HttpHeaders.SetCookie], "no session for $path")
            }
            assertEquals(HttpStatusCode.NotFound, client.post("/t/wrongtoken0/upload").status)
        }

    @Test
    fun unknownPathsUnderTheTokenAre404WithoutClaimingTheToken() {
        val approver = ScriptedApprover { true }
        receiveTest(session(approver)) {
            val client = browser()
            for (path in listOf(
                "nothing",
                "file/",
                "file/9",
                "file/01",
                "file/-1",
                "file/1/x",
                "files/",
                "upload",
                "all.zip/x",
                "file/99999999999",
            )) {
                assertEquals(HttpStatusCode.NotFound, client.get(prefix + path).status, path)
            }
            assertTrue(approver.requests.isEmpty())
        }
    }

    @Test
    fun tokenPathWithoutSlashRedirects() =
        receiveTest(session()) {
            val response = browser().get(prefix.removeSuffix("/"))
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(prefix, response.headers[HttpHeaders.Location])
        }

    @Test
    fun tokenIsCaseInsensitive() =
        receiveTest(session()) {
            assertEquals(HttpStatusCode.OK, browser().get("/t/${TEST_TOKEN.value.uppercase()}/").status)
        }

    @Test
    fun pageIsServedWithSecurityHeadersAndASessionCookie() =
        receiveTest(session()) {
            val response = browser().get(prefix)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("text/html; charset=UTF-8", response.headers[HttpHeaders.ContentType])
            assertContentEquals(ReceivePage.bytes, response.bodyAsBytes())
            assertEquals(ReceivePage.contentSecurityPolicy, response.headers["Content-Security-Policy"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
            assertTrue(cookie.startsWith("drop_session="), cookie)
            assertTrue(cookie.contains("Path=$prefix"), cookie)
            assertTrue(cookie.contains("HttpOnly"), cookie)
            assertTrue(cookie.contains("SameSite=Strict"), cookie)
        }

    @Test
    fun filesJsonListsTheOffer() =
        receiveTest(session()) {
            val client = browser()
            client.get(prefix)
            val response = client.get(prefix + "files")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters())
            val body = response.json()
            assertEquals("ok", body["status"]!!.jsonPrimitive.content)
            assertEquals("Dev", body["sender"]!!.jsonPrimitive.content)
            assertEquals("4 files · 200 KB", body["summary"]!!.jsonPrimitive.content)
            assertEquals(4, body["count"]!!.jsonPrimitive.int)
            val archive = body["archive"]!!.jsonObject
            assertEquals("all.zip", archive["path"]!!.jsonPrimitive.content)
            assertEquals("Files from Dev.zip", archive["name"]!!.jsonPrimitive.content)
            assertTrue(archive["size"]!!.jsonPrimitive.long > files.sumOf { it.size })
            assertEquals("null", body["upload"].toString())
            val list = body["files"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(0, 1, 2, 3), list.map { it["index"]!!.jsonPrimitive.int })
            assertEquals(listOf("Fotó 日本.jpg", "note.txt", "empty.bin", "evil_name_.html"), list.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(files.map { it.size }, list.map { it["size"]!!.jsonPrimitive.long })
            assertEquals(
                listOf("image/jpeg", "text/plain", "application/octet-stream", "text/html"),
                list.map {
                    it["mime"]!!.jsonPrimitive.content
                },
            )
            assertEquals(listOf("file/0", "file/1", "file/2", "file/3"), list.map { it["path"]!!.jsonPrimitive.content })
        }

    @Test
    fun fileDownloadStreamsWithLengthAndDisposition() =
        receiveTest(session()) {
            val client = approvedBrowser()
            val response = client.get(prefix + "file/0")
            assertEquals(HttpStatusCode.OK, response.status)
            assertContentEquals(photo.bytes, response.bodyAsBytes())
            assertEquals(photo.bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertEquals(
                "attachment; filename=\"Fot_ __.jpg\"; filename*=UTF-8''Fot%C3%B3%20%E6%97%A5%E6%9C%AC.jpg",
                response.headers[HttpHeaders.ContentDisposition],
            )
            assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
            assertNotNull(response.headers[HttpHeaders.ETag])
            assertEquals("sandbox; default-src 'none'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun emptyFileDownloads() =
        receiveTest(session()) {
            val response = approvedBrowser().get(prefix + "file/2")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("0", response.headers[HttpHeaders.ContentLength])
            assertEquals(0, response.bodyAsBytes().size)
            assertEquals("application/octet-stream", response.headers[HttpHeaders.ContentType])
        }

    @Test
    fun htmlFilesAreForcedToDownloadInASandbox() =
        receiveTest(session()) {
            val response = approvedBrowser().get(prefix + "file/3")
            assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment;"))
            assertEquals("sandbox; default-src 'none'", response.headers["Content-Security-Policy"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }

    @Test
    fun rangeRequests() =
        receiveTest(session()) {
            val client = approvedBrowser()
            val size = photo.bytes.size

            suspend fun range(
                header: String,
                ifRange: String? = null,
            ) = client.get(prefix + "file/0") {
                header(HttpHeaders.Range, header)
                if (ifRange != null) header(HttpHeaders.IfRange, ifRange)
            }

            val first = range("bytes=0-99")
            assertEquals(HttpStatusCode.PartialContent, first.status)
            assertEquals("bytes 0-99/$size", first.headers[HttpHeaders.ContentRange])
            assertEquals("100", first.headers[HttpHeaders.ContentLength])
            assertContentEquals(photo.bytes.copyOfRange(0, 100), first.bodyAsBytes())

            val open = range("bytes=150000-")
            assertEquals(HttpStatusCode.PartialContent, open.status)
            assertEquals("bytes 150000-${size - 1}/$size", open.headers[HttpHeaders.ContentRange])
            assertContentEquals(photo.bytes.copyOfRange(150_000, size), open.bodyAsBytes())

            val suffix = range("bytes=-10")
            assertContentEquals(photo.bytes.copyOfRange(size - 10, size), suffix.bodyAsBytes())

            val clamped = range("bytes=199990-999999")
            assertEquals("bytes 199990-${size - 1}/$size", clamped.headers[HttpHeaders.ContentRange])
            assertContentEquals(photo.bytes.copyOfRange(199_990, size), clamped.bodyAsBytes())

            val unsatisfiable = range("bytes=$size-")
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
            assertEquals("bytes */$size", unsatisfiable.headers[HttpHeaders.ContentRange])

            val multi = range("bytes=0-1,5-6")
            assertEquals(HttpStatusCode.OK, multi.status)
            assertContentEquals(photo.bytes, multi.bodyAsBytes())

            val etag = first.headers[HttpHeaders.ETag]!!
            val resumed = range("bytes=100-199", ifRange = etag)
            assertEquals(HttpStatusCode.PartialContent, resumed.status)
            assertContentEquals(photo.bytes.copyOfRange(100, 200), resumed.bodyAsBytes())

            val stale = range("bytes=100-199", ifRange = "\"other\"")
            assertEquals(HttpStatusCode.OK, stale.status, "a changed validator gets the whole file")
            assertContentEquals(photo.bytes, stale.bodyAsBytes())

            val byDate = range("bytes=100-199", ifRange = "Wed, 23 Sep 2026 12:00:00 GMT")
            assertEquals(HttpStatusCode.OK, byDate.status)
        }

    @Test
    fun zipStreamsEveryFileAndReadsBackWithJavaUtilZip() {
        val s = session()
        receiveTest(s) {
            val response = approvedBrowser().get(prefix + "all.zip")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/zip", response.headers[HttpHeaders.ContentType])
            assertEquals(
                "attachment; filename=\"Files from Dev.zip\"; filename*=UTF-8''Files%20from%20Dev.zip",
                response.headers[HttpHeaders.ContentDisposition],
            )
            assertEquals("none", response.headers[HttpHeaders.AcceptRanges])
            val bytes = response.bodyAsBytes()
            assertEquals(s.archiveSize.toString(), response.headers[HttpHeaders.ContentLength])
            assertEquals(s.archiveSize, bytes.size.toLong())
            val file = Files.createTempFile("drop-all", ".zip")
            try {
                Files.write(file, bytes)
                ZipFile(file.toFile()).use { zip ->
                    val entries = zip.entries().toList()
                    assertEquals(listOf("Fotó 日本.jpg", "note.txt", "empty.bin", "evil_name_.html"), entries.map { it.name })
                    for ((entry, source) in entries.zip(files)) {
                        val content = zip.getInputStream(entry).use { it.readBytes() }
                        assertContentEquals((source as BytesFile).bytes, content, entry.name)
                        assertEquals(crc32(content), entry.crc, entry.name)
                    }
                }
            } finally {
                Files.deleteIfExists(file)
            }
            assertEquals(0, empty.opens.get(), "empty files are never opened")
        }
    }

    @Test
    fun forcedZip64ArchiveReadsBackOverHttp() {
        val s = session(settings = ReceiveSettings(forceZip64 = true))
        receiveTest(s) {
            val bytes = approvedBrowser().get(prefix + "all.zip").bodyAsBytes()
            assertEquals(s.archiveSize, bytes.size.toLong())
            val file = Files.createTempFile("drop-zip64", ".zip")
            try {
                Files.write(file, bytes)
                ZipFile(file.toFile()).use { zip -> assertEquals(4, zip.size()) }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun wrongMethodsAre405() =
        receiveTest(session(upload = UploadSettings(RecordingSink(), 1000))) {
            val client = browser()
            val post = client.post(prefix + "files")
            assertEquals(HttpStatusCode.MethodNotAllowed, post.status)
            assertEquals("GET", post.headers[HttpHeaders.Allow])
            val get = client.get(prefix + "upload")
            assertEquals(HttpStatusCode.MethodNotAllowed, get.status)
            assertEquals("POST", get.headers[HttpHeaders.Allow])
            assertEquals(HttpStatusCode.MethodNotAllowed, client.request(prefix) { method = HttpMethod.Delete }.status)
        }

    // --- N15 approval gate ---------------------------------------------------------------------------------------

    @Test
    fun filesWaitForTheApprovalThenServe() {
        val approver = ScriptedApprover()
        receiveTest(session(approver, settings = ReceiveSettings(approvalWaitMillis = 50))) {
            val client = browser()
            assertEquals(HttpStatusCode.OK, client.get(prefix).status, "the page itself is served while pending")
            val pending = client.get(prefix + "files")
            assertEquals(HttpStatusCode.Accepted, pending.status)
            assertEquals("pending", pending.json()["status"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, client.get(prefix + "file/0").status)
            assertEquals(HttpStatusCode.Forbidden, client.get(prefix + "all.zip").status)
            val request = approver.requests.single()
            assertEquals(1, request.browserNumber)
            approver.answer(1, true)
            assertEquals(HttpStatusCode.OK, client.get(prefix + "files").status)
            assertEquals(HttpStatusCode.OK, client.get(prefix + "file/1").status)
            assertEquals(1, approver.requests.size, "one browser is asked for once")
        }
    }

    @Test
    fun aHeldFilesRequestAnswersAsSoonAsThePhoneSaysYes() {
        val approver = ScriptedApprover()
        receiveTest(session(approver, settings = ReceiveSettings(approvalWaitMillis = 30_000))) {
            val client = browser()
            client.get(prefix)
            approver.answer(1, true)
            val response = withTimeout(10_000) { client.get(prefix + "files") }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun deniedBrowserGetsNothing() {
        val approver = ScriptedApprover { false }
        receiveTest(session(approver)) {
            val client = browser()
            client.get(prefix)
            val files = client.get(prefix + "files")
            assertEquals(HttpStatusCode.Forbidden, files.status)
            assertEquals("denied", files.json()["status"]!!.jsonPrimitive.content)
            val download = client.get(prefix + "file/0")
            assertEquals(HttpStatusCode.Forbidden, download.status)
            assertEquals("denied", download.json()["status"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, client.get(prefix + "all.zip").status)
            assertEquals(0, photo.opens.get())
        }
    }

    @Test
    fun aSecondBrowserNeedsItsOwnApproval() {
        val approver = ScriptedApprover { it.browserNumber == 1 }
        val s = session(approver)
        receiveTest(s) {
            val first = approvedBrowser()
            val second = browser()
            val page = second.get(prefix)
            assertEquals(HttpStatusCode.OK, page.status)
            assertNotNull(page.headers[HttpHeaders.SetCookie], "the second browser gets its own session")
            assertEquals(HttpStatusCode.Forbidden, second.get(prefix + "files").status)
            assertEquals(HttpStatusCode.Forbidden, second.get(prefix + "file/0").status)
            assertEquals(listOf(1, 2), approver.requests.map { it.browserNumber }.sorted())
            // The first browser is unaffected.
            assertEquals(HttpStatusCode.OK, first.get(prefix + "file/0").status)
            assertEquals(listOf(BrowserState.APPROVED, BrowserState.DENIED), s.browserStates())
        }
    }

    @Test
    fun aSecondBrowserTheUserAllowsIsServed() {
        val approver = ScriptedApprover { true }
        receiveTest(session(approver)) {
            approvedBrowser()
            val second = approvedBrowser()
            assertEquals(HttpStatusCode.OK, second.get(prefix + "file/1").status)
            assertEquals(2, approver.requests.size)
        }
    }

    @Test
    fun withOneBrowserAllowedTheTokenIsSingleUse() {
        val approver = ScriptedApprover { true }
        receiveTest(session(approver, settings = ReceiveSettings(maxBrowsers = 1))) {
            approvedBrowser()
            val second = browser()
            val page = second.get(prefix)
            assertEquals(HttpStatusCode.Forbidden, page.status)
            assertTrue(page.bodyAsText().contains("already open on another computer"))
            assertNull(page.headers[HttpHeaders.SetCookie])
            val files = second.get(prefix + "files")
            assertEquals(HttpStatusCode.Forbidden, files.status)
            assertEquals("refused", files.json()["status"]!!.jsonPrimitive.content)
            assertEquals(1, approver.requests.size, "the phone is not asked again")
        }
    }

    @Test
    fun aForgedCookieCountsAsANewBrowser() {
        val approver = ScriptedApprover { it.browserNumber == 1 }
        receiveTest(session(approver)) {
            approvedBrowser()
            val intruder = createClient { followRedirects = false }
            val response = intruder.get(prefix + "file/0") { header(HttpHeaders.Cookie, "drop_session=AAAAAAAAAAAAAAAAAAAAAA") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(2, approver.requests.size)
        }
    }

    @Test
    fun closingTheSessionRevokesTheToken() {
        val s = session()
        receiveTest(s) {
            val client = approvedBrowser()
            s.close()
            assertTrue(s.isClosed)
            for (path in listOf(
                "",
                "files",
                "file/0",
                "all.zip",
            )) {
                assertEquals(HttpStatusCode.NotFound, client.get(prefix + path).status, path)
            }
        }
    }

    // --- Upload (P1) ---------------------------------------------------------------------------------------------

    @Test
    fun uploadIsAbsentWithoutASink() =
        receiveTest(session()) {
            assertEquals(HttpStatusCode.NotFound, approvedBrowser().post(prefix + "upload?name=a.txt") { setBody("x") }.status)
        }

    @Test
    fun uploadNeedsAnApprovedBrowser() {
        val sink = RecordingSink()
        receiveTest(session(ScriptedApprover { false }, upload = UploadSettings(sink, 1000))) {
            val client = browser()
            client.get(prefix)
            assertEquals(HttpStatusCode.Forbidden, client.post(prefix + "upload?name=a.txt") { setBody("hello") }.status)
            assertTrue(sink.opened.isEmpty())
        }
    }

    @Test
    fun uploadReachesTheSinkAndIsAdvertised() {
        val sink = RecordingSink()
        val upload = UploadSettings(sink, 1000)
        receiveTest(session(upload = upload)) {
            val client = approvedBrowser()
            val files = client.get(prefix + "files").json()
            assertEquals(1000L, files["upload"]!!.jsonObject["maxBytes"]!!.jsonPrimitive.long)
            val response =
                client.post(prefix + "upload?name=" + "..%2Fback%20home%3F.txt") {
                    header(HttpHeaders.ContentType, "text/plain; charset=utf-8")
                    setBody("hello back")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.json()
            assertEquals("back home_.txt", body["name"]!!.jsonPrimitive.content)
            assertEquals(10L, body["size"]!!.jsonPrimitive.long)
            assertEquals(990L, body["maxBytes"]!!.jsonPrimitive.long)
            val saved = sink.committed.single()
            assertEquals("back home_.txt", saved.name)
            assertEquals("text/plain", saved.mime)
            assertEquals("hello back", saved.bytes.toString(Charsets.UTF_8))
            assertEquals(990L, upload.remainingBytes)
        }
    }

    @Test
    fun uploadSizeCapIsEnforcedAcrossTheSession() {
        val sink = RecordingSink()
        val upload = UploadSettings(sink, 100)
        receiveTest(session(upload = upload)) {
            val client = approvedBrowser()
            val tooBig = client.post(prefix + "upload?name=big.bin") { setBody(ByteArray(101)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
            assertEquals("too_large", tooBig.json()["status"]!!.jsonPrimitive.content)
            assertTrue(sink.opened.isEmpty(), "refused before anything is stored")
            assertEquals(HttpStatusCode.OK, client.post(prefix + "upload?name=a.bin") { setBody(ByteArray(60)) }.status)
            val second = client.post(prefix + "upload?name=b.bin") { setBody(ByteArray(41)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, second.status)
            assertEquals(40L, second.json()["maxBytes"]!!.jsonPrimitive.long)
            assertEquals(HttpStatusCode.OK, client.post(prefix + "upload?name=c.bin") { setBody(ByteArray(40)) }.status)
            assertEquals(0L, upload.remainingBytes)
            assertEquals(listOf("a.bin", "c.bin"), sink.committed.map { it.name })
        }
    }

    @Test
    fun uploadWithoutContentLengthIs411() {
        val sink = RecordingSink()
        receiveTest(session(upload = UploadSettings(sink, 1000))) {
            val client = approvedBrowser()
            val response =
                client.post(prefix + "upload?name=a.bin") {
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeFully(ByteArray(10))
                            }
                        },
                    )
                }
            assertEquals(HttpStatusCode.LengthRequired, response.status)
            assertTrue(sink.opened.isEmpty())
        }
    }

    @Test
    fun aFailingSinkReleasesTheReservation() {
        val upload = UploadSettings({ _, _, _ -> throw java.io.IOException("disk full") }, 100)
        receiveTest(session(upload = upload)) {
            val response = approvedBrowser().post(prefix + "upload?name=a.bin") { setBody(ByteArray(50)) }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("failed", response.json()["status"]!!.jsonPrimitive.content)
            assertEquals(100L, upload.remainingBytes)
        }
    }

    @Test
    fun transfersAreCounted() {
        val s = session(upload = UploadSettings(RecordingSink(), 1000))
        receiveTest(s) {
            val client = approvedBrowser()
            client.get(prefix + "file/1").bodyAsBytes()
            client.get(prefix + "all.zip").bodyAsBytes()
            client.post(prefix + "upload?name=a") { setBody(ByteArray(5)) }
            val snapshot = s.activity.snapshot()
            assertEquals(0, snapshot.inProgress)
            assertEquals(3, snapshot.finished)
            assertEquals(note.size + files.sumOf { it.size }, snapshot.bytesSent)
            assertEquals(5L, snapshot.bytesReceived)
            assertFalse(s.activity.isIdle(), "the fake clock has not moved")
        }
    }

    /** An [UploadSink] that keeps everything in memory. */
    class RecordingSink : UploadSink {
        data class Saved(
            val name: String,
            val mime: String,
            val bytes: ByteArray,
        )

        val opened = mutableListOf<String>()
        val committed = mutableListOf<Saved>()

        override fun open(
            name: String,
            size: Long,
            mimeType: String,
        ): UploadWriter {
            opened += name
            val buffer = ByteArrayOutputStream()
            return object : UploadWriter {
                override fun write(
                    buffer2: ByteArray,
                    offset: Int,
                    length: Int,
                ) = buffer.write(buffer2, offset, length)

                override fun commit(): String {
                    committed += Saved(name, mimeType, buffer.toByteArray())
                    return name
                }

                override fun abort() = Unit
            }
        }
    }
}
