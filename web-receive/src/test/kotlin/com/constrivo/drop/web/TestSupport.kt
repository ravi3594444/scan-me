package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.random.Random

/** An in-memory [SharedFile]; counts how often it was opened. */
class BytesFile(
    override val name: String,
    val bytes: ByteArray,
    override val mimeType: String? = "application/octet-stream",
    private val declaredSize: Long = bytes.size.toLong(),
) : SharedFile {
    val opens = AtomicInteger()

    override val size: Long get() = declaredSize

    override fun open(offset: Long): InputStream {
        opens.incrementAndGet()
        val start = offset.toInt().coerceAtMost(bytes.size)
        return ByteArrayInputStream(bytes, start, bytes.size - start)
    }
}

/** A monotonic clock the test moves by hand. */
class FakeMonotonicClock(
    start: Long = 1_000,
) : MonotonicClock {
    private val now = AtomicLong(start)

    override fun elapsedMillis(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}

/** 23 Sep 2026 12:00:00 UTC. */
val FIXED_WALL_CLOCK = WallClock { 1_790_164_800_000L }

/**
 * A [BrowserApprover] the test answers: each request waits for [answer] (which may come first) unless [autoAnswer]
 * is set.
 */
class ScriptedApprover(
    private val autoAnswer: ((BrowserApprovalRequest) -> Boolean)? = null,
) : BrowserApprover {
    val requests = CopyOnWriteArrayList<BrowserApprovalRequest>()
    private val answers = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    val asked = CompletableDeferred<Unit>()

    override suspend fun approve(request: BrowserApprovalRequest): Boolean {
        val answer = answers.computeIfAbsent(request.browserNumber) { CompletableDeferred() }
        requests += request
        asked.complete(Unit)
        autoAnswer?.let { return it(request) }
        return answer.await()
    }

    /** Answers the request of browser [number] (1-based), now or when it arrives. */
    fun answer(
        number: Int,
        allowed: Boolean,
    ) {
        answers.computeIfAbsent(number) { CompletableDeferred() }.complete(allowed)
    }
}

fun randomBytes(
    size: Int,
    seed: Int,
): ByteArray = Random(seed).nextBytes(size)

fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

/** A deterministic token for tests (fixed seed). */
val TEST_TOKEN: ReceiveToken = ReceiveToken.generate(Random(7))
