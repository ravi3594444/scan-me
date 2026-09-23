package com.constrivo.drop.tools.fuzz

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The CI fuzz job (testing §5 "Fuzz" row): every protocol decoder under mutated golden encodings and random bytes.
 *
 * - Default: a fixed seed and a fixed number of inputs per target, about ten seconds in total, deterministic.
 * - `-Pdrop.nightly=true`: one minute per target (five minutes in all) with a seed derived from the date, printed so
 *   a failure can be replayed with `-Pdrop.fuzz.seed=<seed>` or `fuzz --seed <seed>`.
 */
class FuzzSmokeTest {
    @Test
    fun protocolDecodersOnlyRaiseProtocolExceptionWithinTheirAllocationLimits() {
        val nightly = System.getProperty("drop.nightly") == "true"
        val seed =
            System.getProperty("drop.fuzz.seed")?.toLong()
                ?: if (nightly) LocalDate.now(ZoneOffset.UTC).toEpochDay() * 7919 else SMOKE_SEED
        val config =
            if (nightly) {
                FuzzConfig(seed = seed, millisPerTarget = 60_000)
            } else {
                FuzzConfig(seed = seed, iterationsPerTarget = SMOKE_ITERATIONS)
            }
        val report = Fuzzer(ProtocolTargets.all()).run(config)
        println(report.summary())
        if (!report.ok) fail(report.summary())
        assertEquals(ProtocolTargets.all().map { it.name }, report.targets.map { it.target })
        for (target in report.targets) {
            if (!nightly) assertEquals(SMOKE_ITERATIONS, target.executions, target.target)
            assertTrue(target.accepted > 0, "${target.target}: the fuzzer never produced a valid input")
            assertTrue(target.rejected > 0, "${target.target}: the fuzzer never produced an invalid input")
        }
    }

    private companion object {
        const val SMOKE_SEED = 20_260_923L
        const val SMOKE_ITERATIONS = 100_000L
    }
}
