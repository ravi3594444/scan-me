package com.constrivo.drop.tools.fuzz

import com.constrivo.drop.core.protocol.ProtocolException
import kotlin.random.Random

/**
 * How long and how hard to fuzz. A run stops per target after [iterationsPerTarget] inputs or
 * [millisPerTarget] milliseconds, whichever comes first, or at [maxFailuresPerTarget] failures.
 */
data class FuzzConfig(
    val seed: Long,
    val iterationsPerTarget: Long = Long.MAX_VALUE,
    val millisPerTarget: Long = Long.MAX_VALUE,
    val maxInputSize: Int = 70_000,
    val maxFailuresPerTarget: Int = 5,
    /** An input that takes longer than this to decode is reported (a hang or quadratic blow-up). */
    val slowInputMillis: Long = 2_000,
    /** Accepted mutants are added to the corpus up to this size, a cheap stand-in for coverage feedback. */
    val corpusLimit: Int = 2_000,
) {
    init {
        require(iterationsPerTarget > 0 && millisPerTarget > 0) { "the run must have a budget" }
        require(maxInputSize > 0 && maxFailuresPerTarget > 0 && slowInputMillis > 0 && corpusLimit >= 0) { "limits must be positive" }
        require(iterationsPerTarget != Long.MAX_VALUE || millisPerTarget != Long.MAX_VALUE) { "set an iteration or a time budget" }
    }
}

/** One input that broke the property. [inputHex] reproduces it. */
class FuzzFailure(
    val target: String,
    val problem: String,
    val inputHex: String,
    val cause: Throwable?,
) {
    override fun toString(): String =
        "[$target] $problem\n  input: $inputHex" +
            (cause?.let { "\n  cause: ${it.stackTraceToString().lines().take(8).joinToString("\n    ")}" } ?: "")
}

/** What one target's run did. */
class TargetReport(
    val target: String,
    val executions: Long,
    val accepted: Long,
    val rejected: Long,
    val maxAllocatedBytes: Long,
    val elapsedMillis: Long,
    val corpusSize: Int,
    val failures: List<FuzzFailure>,
) {
    override fun toString(): String =
        "%-12s %9d inputs  %8d accepted  %9d rejected  max alloc %7d KiB  corpus %5d  %6d ms  %d failures"
            .format(target, executions, accepted, rejected, maxAllocatedBytes / 1024, corpusSize, elapsedMillis, failures.size)
}

class FuzzReport(
    val seed: Long,
    val targets: List<TargetReport>,
) {
    val failures: List<FuzzFailure> get() = targets.flatMap { it.failures }

    val ok: Boolean get() = failures.isEmpty()

    fun summary(): String =
        buildString {
            appendLine("drop fuzz, seed $seed")
            targets.forEach { appendLine("  $it") }
            if (ok) {
                appendLine("  OK: only ProtocolException, allocations within the declared limits")
            } else {
                failures.forEach {
                    appendLine(it)
                }
            }
        }
}

/**
 * A mutation fuzzer for the protocol decoders. For each target it starts from the target's seed corpus (the golden
 * encodings), mutates entries with [Mutator] or feeds fresh random bytes, and checks every input against the
 * property of [FuzzTarget]: only [ProtocolException] escapes, allocation stays within
 * [FuzzTarget.allocationLimit], accepted inputs satisfy the round-trip property, and no input is slow.
 *
 * Deterministic for a given seed and iteration budget (the time budget and the allocation readings depend on the
 * machine). Runs on the calling thread, because the allocation counter is per thread.
 */
class Fuzzer(
    private val targets: List<FuzzTarget>,
    private val meter: AllocationMeter = AllocationMeter.current(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(config: FuzzConfig): FuzzReport = FuzzReport(config.seed, targets.map { runTarget(it, config) })

    private fun runTarget(
        target: FuzzTarget,
        config: FuzzConfig,
    ): TargetReport {
        val random = Random(config.seed xor target.name.hashCode().toLong())
        val mutator = Mutator(random, config.maxInputSize)
        val corpus = ArrayList(target.seeds)
        val failures = ArrayList<FuzzFailure>()
        // Warm up on the seeds: class loading and lazy initialisation allocate once and must not count against an
        // input, but the seeds must satisfy the property like any other input.
        corpus.forEach { seed -> execute(target, seed, config, measure = false).failure?.let { failures += it } }
        var executions = 0L
        var accepted = 0L
        var rejected = 0L
        var maxAllocated = 0L
        val start = nanoTime()
        val deadline = if (config.millisPerTarget == Long.MAX_VALUE) Long.MAX_VALUE else start + config.millisPerTarget * 1_000_000
        while (executions < config.iterationsPerTarget && failures.size < config.maxFailuresPerTarget) {
            if (executions % 64 == 0L && nanoTime() >= deadline) break
            val input =
                if (corpus.isEmpty() || random.nextInt(10) == 0) {
                    mutator.randomInput()
                } else {
                    mutator.mutate(corpus[random.nextInt(corpus.size)], corpus[random.nextInt(corpus.size)])
                }
            val result = execute(target, input, config, measure = true)
            executions++
            maxAllocated = maxOf(maxAllocated, result.allocated)
            when {
                result.failure != null -> {
                    failures += result.failure
                }

                result.accepted -> {
                    accepted++
                    if (corpus.size < config.corpusLimit) corpus += input
                }

                else -> {
                    rejected++
                }
            }
        }
        val elapsed = (nanoTime() - start) / 1_000_000
        return TargetReport(target.name, executions, accepted, rejected, maxAllocated, elapsed, corpus.size, failures)
    }

    private class Result(
        val accepted: Boolean,
        val allocated: Long,
        val failure: FuzzFailure?,
    )

    private fun execute(
        target: FuzzTarget,
        input: ByteArray,
        config: FuzzConfig,
        measure: Boolean,
    ): Result {
        val before = meter.allocatedBytes()
        val started = nanoTime()
        var accepted = false
        var failure: FuzzFailure? = null
        try {
            accepted = target.run(input)
        } catch (e: ProtocolException) {
            accepted = false
        } catch (e: PropertyViolation) {
            failure = failure(target, "property violated: ${e.message}", input, e)
        } catch (t: Throwable) {
            // Anything else, including errors such as OutOfMemoryError or StackOverflowError, breaks the property.
            failure = failure(target, "threw ${t::class.qualifiedName}: ${t.message}", input, t)
        }
        val elapsedMillis = (nanoTime() - started) / 1_000_000
        val after = meter.allocatedBytes()
        val allocated = if (before < 0 || after < 0) 0 else after - before
        if (failure == null && measure) {
            val limit = target.allocationLimit(input)
            if (before >= 0 && allocated > limit) {
                failure = failure(target, "allocated $allocated bytes for a ${input.size}-byte input, limit $limit", input, null)
            } else if (elapsedMillis > config.slowInputMillis) {
                failure = failure(target, "took $elapsedMillis ms for a ${input.size}-byte input", input, null)
            }
        }
        return Result(accepted && failure == null, allocated, failure)
    }

    private fun failure(
        target: FuzzTarget,
        problem: String,
        input: ByteArray,
        cause: Throwable?,
    ) = FuzzFailure(target.name, problem, input.joinToString("") { "%02x".format(it) }, cause)
}
