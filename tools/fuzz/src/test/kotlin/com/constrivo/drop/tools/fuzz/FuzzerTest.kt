package com.constrivo.drop.tools.fuzz

import com.constrivo.drop.core.protocol.ProtocolException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The harness itself: it must catch every way a decoder can break the property. */
class FuzzerTest {
    private open class ScriptedTarget(
        override val name: String,
        private val behaviour: (ByteArray) -> Boolean,
        private val limit: Long = Long.MAX_VALUE,
    ) : FuzzTarget {
        override val seeds: List<ByteArray> = listOf(byteArrayOf(1, 2, 3, 4))

        override fun allocationLimit(input: ByteArray): Long = limit

        override fun run(input: ByteArray): Boolean = behaviour(input)
    }

    private val config = FuzzConfig(seed = 1, iterationsPerTarget = 2_000, maxFailuresPerTarget = 3)

    @Test
    fun aWellBehavedTargetPasses() {
        val target = ScriptedTarget("ok", { if (it.size % 2 == 0) true else throw ProtocolException("odd") })
        val report = Fuzzer(listOf(target)).run(config)
        assertTrue(report.ok, report.summary())
        val t = report.targets.single()
        assertEquals(2_000, t.executions)
        assertEquals(2_000, t.accepted + t.rejected)
        assertTrue(t.accepted > 0 && t.rejected > 0)
    }

    @Test
    fun otherExceptionsAreFailures() {
        val target = ScriptedTarget("leaky", { if (it.size > 3 && it[3].toInt() == 0) throw IndexOutOfBoundsException("boom") else false })
        val report = Fuzzer(listOf(target)).run(config)
        assertFalse(report.ok)
        assertEquals(3, report.failures.size, "stops at maxFailuresPerTarget")
        assertTrue(report.failures.all { "IndexOutOfBoundsException" in it.problem })
        assertTrue(report.summary().contains("input: "))
    }

    @Test
    fun errorsAreFailures() {
        val target = ScriptedTarget("deep", { throw StackOverflowError() })
        val report = Fuzzer(listOf(target)).run(config)
        assertTrue(report.failures.first().problem.contains("StackOverflowError"))
    }

    @Test
    fun seedsAreCheckedToo() {
        val target = ScriptedTarget("bad-seed", { if (it.contentEquals(byteArrayOf(1, 2, 3, 4))) error("seed") else false })
        val report = Fuzzer(listOf(target)).run(config.copy(iterationsPerTarget = 1))
        assertEquals("01020304", report.failures.first().inputHex)
    }

    @Test
    fun propertyViolationsAreFailures() {
        val target = ScriptedTarget("asymmetric", { throw PropertyViolation("does not round-trip") })
        val failure = Fuzzer(listOf(target)).run(config).failures.first()
        assertTrue(failure.problem.contains("does not round-trip"))
    }

    @Test
    fun overAllocationIsAFailure() {
        var sink: ByteArray? = null
        val target =
            ScriptedTarget("greedy", {
                sink = ByteArray(8 * 1024 * 1024)
                true
            }, limit = 1024 * 1024)
        val report = Fuzzer(listOf(target), AllocationMeter.current()).run(config)
        assertTrue(sink != null)
        assertTrue(report.failures.first().problem.startsWith("allocated "), report.summary())
    }

    @Test
    fun slowInputsAreFailures() {
        var clock = 0L
        val target =
            ScriptedTarget("slow", {
                clock += 5_000_000_000
                false
            })
        val report = Fuzzer(listOf(target), AllocationMeter.UNAVAILABLE) { clock }.run(config)
        assertTrue(report.failures.first().problem.startsWith("took "), report.summary())
    }

    @Test
    fun timeBudgetStopsTheRun() {
        var clock = 0L
        val target =
            ScriptedTarget("timed", {
                clock += 1_000_000
                false
            })
        val report = Fuzzer(listOf(target), AllocationMeter.UNAVAILABLE) { clock }.run(FuzzConfig(seed = 1, millisPerTarget = 100))
        val executions = report.targets.single().executions
        assertTrue(executions in 64..200, "$executions")
    }

    @Test
    fun runsAreReproducibleFromTheSeed() {
        fun inputs(seed: Long): List<String> {
            val seen = ArrayList<String>()
            val target =
                ScriptedTarget("record", {
                    seen += it.joinToString(",") { b -> b.toString() }
                    false
                })
            Fuzzer(listOf(target), AllocationMeter.UNAVAILABLE).run(FuzzConfig(seed = seed, iterationsPerTarget = 500))
            return seen
        }
        assertEquals(inputs(42), inputs(42))
        assertNotEquals(inputs(42), inputs(43))
    }

    @Test
    fun acceptedMutantsGrowTheCorpus() {
        val target = ScriptedTarget("grow", { true })
        val report = Fuzzer(listOf(target), AllocationMeter.UNAVAILABLE).run(config.copy(corpusLimit = 50))
        assertEquals(50, report.targets.single().corpusSize)
    }

    @Test
    fun allocationMeterCountsThisThread() {
        val meter = AllocationMeter.current()
        val before = meter.allocatedBytes()
        val block = ByteArray(2 * 1024 * 1024)
        val after = meter.allocatedBytes()
        assertTrue(block.isNotEmpty())
        if (before >= 0) assertTrue(after - before >= 2L * 1024 * 1024, "measured ${after - before}")
    }

    @Test
    fun mutatorIsDeterministicAndBounded() {
        val a = Mutator(Random(5), maxSize = 100)
        val b = Mutator(Random(5), maxSize = 100)
        val seed = ByteArray(90) { it.toByte() }
        repeat(1_000) {
            val x = a.mutate(seed, seed)
            assertContentEquals(x, b.mutate(seed, seed))
            assertTrue(x.size <= 100)
        }
        repeat(100) { assertTrue(a.randomInput().size <= 100) }
        assertTrue(a.mutate(ByteArray(0), ByteArray(0)).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { Mutator(Random(1), 0) }
    }

    @Test
    fun configNeedsABudget() {
        assertFailsWith<IllegalArgumentException> { FuzzConfig(seed = 1) }
        assertFailsWith<IllegalArgumentException> { FuzzConfig(seed = 1, iterationsPerTarget = 0) }
    }

    @Test
    fun parsesTheCommandLine() {
        val defaults = parseArguments(emptyArray()) { 99 }
        assertEquals(ProtocolTargets.all().size, defaults.targets.size)
        assertEquals(99, defaults.config.seed)
        assertEquals(60_000L / ProtocolTargets.all().size, defaults.config.millisPerTarget)
        val custom =
            parseArguments(arrayOf("--iterations", "10", "--seed", "7", "--target", "control", "--target", "frame", "--max-size", "512")) {
                0
            }
        assertEquals(listOf("control", "frame"), custom.targets.map { it.name })
        assertEquals(FuzzConfig(seed = 7, iterationsPerTarget = 10, maxInputSize = 512), custom.config)
        assertEquals(
            5_000,
            parseArguments(arrayOf("--seconds", "10", "--target", "chunk", "--target", "bundle")) {
                0
            }.config.millisPerTarget,
        )
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("--target", "nope")) { 0 } }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("--seconds")) { 0 } }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("--seconds", "x")) { 0 } }
        assertFailsWith<IllegalArgumentException> { parseArguments(arrayOf("--bogus")) { 0 } }
    }
}
