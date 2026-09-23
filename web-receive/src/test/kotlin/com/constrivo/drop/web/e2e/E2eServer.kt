package com.constrivo.drop.web.e2e

import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.DirectoryUploadSink
import com.constrivo.drop.web.PathSharedFile
import com.constrivo.drop.web.ReceiveOffer
import com.constrivo.drop.web.ReceiveServer
import com.constrivo.drop.web.ReceiveSession
import com.constrivo.drop.web.ReceiveSettings
import com.constrivo.drop.web.ReceiveToken
import com.constrivo.drop.web.SharedFile
import com.constrivo.drop.web.UploadSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Starts a receive server with sample files for the Playwright test in `web-receive/e2e/` (run with
 * `./gradlew :web-receive:e2eServer`). Prints one line `E2E_READY {json}` with the page URL, the upload folder and
 * every sample's served name, size and SHA-256, then serves until stdin says `stop` or closes, or 10 minutes pass.
 *
 * The approver lets the first browser in after 1.5 s (so the page shows the waiting state first) and denies every
 * later browser, so the script can check both sides of the N15 gate. The 12 MB sample is served at about 3 MB/s, so
 * the progress bar and MB/s readout are on screen long enough to check, whichever way the page downloads.
 */
fun main() {
    val dir = Files.createTempDirectory("drop-e2e")
    val random = Random(2026)
    val samples =
        listOf(
            Sample("hello.txt", "text/plain", "Hello from the phone.\n".toByteArray()),
            Sample("Fotó 日本 📷.jpg", "image/jpeg", random.nextBytes(300_000)),
            Sample("empty.bin", null, ByteArray(0)),
            Sample("clip.mp4", "video/mp4", random.nextBytes(12_000_000)),
            Sample("report:final?.pdf", "application/pdf", random.nextBytes(40_000)),
        )
    val files =
        samples.mapIndexed { i, sample ->
            val path = dir.resolve("sample-$i")
            Files.write(path, sample.bytes)
            val file = PathSharedFile(path, sample.name, sample.mime)
            if (sample.bytes.size >= THROTTLE_FROM) Throttled(file, THROTTLE_BYTES_PER_SECOND) else file
        }
    val uploads = dir.resolve("uploads")
    val approver =
        BrowserApprover { request ->
            if (request.browserNumber == 1) {
                delay(1_500)
                true
            } else {
                false
            }
        }
    val offer = ReceiveOffer("Rohan's Pixel", files)
    val session =
        ReceiveSession(
            ReceiveToken.generate(),
            offer,
            approver,
            UploadSettings(DirectoryUploadSink(uploads), 50_000_000),
            ReceiveSettings(idleTimeoutMillis = 10 * 60_000),
        )
    val server = ReceiveServer(session, InetAddress.getByName("127.0.0.1"))
    runBlocking { server.start() }
    val ready =
        buildJsonObject {
            put("url", server.ipUrl())
            put("mdnsUrl", server.url())
            put("uploadDir", uploads.toString())
            put("archiveName", offer.archiveName)
            put("archiveSize", session.archiveSize)
            put("sender", offer.senderName)
            put("summary", offer.summary)
            putJsonArray("files") {
                samples.forEachIndexed { i, sample ->
                    addJsonObject {
                        put("name", offer.displayNames[i])
                        put("size", sample.bytes.size)
                        put("sha256", sha256(sample.bytes))
                    }
                }
            }
        }
    // UTF-8 whatever the locale: the JVM encodes stdout for the platform charset, which is ASCII under LANG=C.
    System.out.write("E2E_READY $ready\n".toByteArray(Charsets.UTF_8))
    System.out.flush()
    thread(isDaemon = true, name = "e2e-stdin") {
        while (true) {
            val line = readlnOrNull()
            if (line == null || line.trim() == "stop") break
        }
        runBlocking { server.stop() }
    }
    val reason = runBlocking { withTimeoutOrNull(10 * 60_000L) { server.awaitStopped() } }
    if (reason == null) runBlocking { server.stop() }
    println("E2E_STOPPED ${reason ?: "timeout"}")
    deleteRecursively(dir)
}

private const val THROTTLE_FROM = 10_000_000
private const val THROTTLE_BYTES_PER_SECOND = 3_000_000L

/** [file] read at no more than [bytesPerSecond]. */
private class Throttled(
    private val file: SharedFile,
    private val bytesPerSecond: Long,
) : SharedFile by file {
    override fun open(offset: Long): InputStream =
        object : FilterInputStream(file.open(offset)) {
            private val start = System.nanoTime()
            private var read = 0L

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                val n = super.read(b, off, minOf(len, CHUNK))
                if (n > 0) {
                    read += n
                    val wait = start + read * 1_000_000_000L / bytesPerSecond - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
                return n
            }
        }

    private companion object {
        const val CHUNK = 32 * 1024
    }
}

private class Sample(
    val name: String,
    val mime: String?,
    val bytes: ByteArray,
)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun deleteRecursively(dir: Path) {
    dir.toFile().deleteRecursively()
}
