package com.constrivo.drop.tools.fuzz

import kotlin.system.exitProcess

private const val USAGE = """usage: fuzz [--seconds N] [--iterations N] [--seed N] [--target NAME]... [--max-size BYTES]

Fuzzes the core/protocol decoders (frame, control, chunk, bundle, stream-open, layout) and checks that every input
either decodes or raises ProtocolException, within the allocation limits the decoders declare.

  --seconds N      total wall-clock budget, split evenly across targets (default 60)
  --iterations N   inputs per target (overrides --seconds when given alone)
  --seed N         random seed (default: current time; printed for reproduction)
  --target NAME    fuzz only this target; repeatable
  --max-size N     largest generated input in bytes (default 70000)

Exit status 0 when the property held, 1 when an input broke it, 2 on a usage error."""

/** Parsed command line for [main]. */
internal data class FuzzArguments(
    val targets: List<FuzzTarget>,
    val config: FuzzConfig,
)

internal fun parseArguments(
    args: Array<String>,
    defaultSeed: () -> Long,
): FuzzArguments {
    var seconds: Long? = null
    var iterations: Long? = null
    var seed: Long? = null
    var maxSize = 70_000
    val names = ArrayList<String>()
    var i = 0

    fun value(flag: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$flag needs a value")
    while (i < args.size) {
        when (val flag = args[i]) {
            "--seconds" -> seconds = value(flag).toLong()
            "--iterations" -> iterations = value(flag).toLong()
            "--seed" -> seed = value(flag).toLong()
            "--target" -> names += value(flag)
            "--max-size" -> maxSize = value(flag).toInt()
            "--help", "-h" -> throw IllegalArgumentException("help")
            else -> throw IllegalArgumentException("unknown argument $flag")
        }
        i++
    }
    val targets = if (names.isEmpty()) ProtocolTargets.all() else names.map { ProtocolTargets.byName(it) }
    val totalSeconds = seconds ?: if (iterations == null) 60 else null
    require(totalSeconds == null || totalSeconds > 0) { "--seconds must be positive" }
    val config =
        FuzzConfig(
            seed = seed ?: defaultSeed(),
            iterationsPerTarget = iterations ?: Long.MAX_VALUE,
            millisPerTarget = totalSeconds?.let { maxOf(1, it * 1000 / targets.size) } ?: Long.MAX_VALUE,
            maxInputSize = maxSize,
        )
    return FuzzArguments(targets, config)
}

fun main(args: Array<String>) {
    val parsed =
        try {
            parseArguments(args) { System.currentTimeMillis() }
        } catch (e: IllegalArgumentException) {
            if (e.message != "help") System.err.println("fuzz: ${e.message}")
            System.err.println(USAGE)
            exitProcess(2)
        }
    val report = Fuzzer(parsed.targets).run(parsed.config)
    print(report.summary())
    exitProcess(if (report.ok) 0 else 1)
}
