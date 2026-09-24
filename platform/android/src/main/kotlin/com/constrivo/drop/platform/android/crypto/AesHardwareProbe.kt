package com.constrivo.drop.platform.android.crypto

import java.io.File
import java.io.IOException

/**
 * Decides [com.constrivo.drop.core.crypto.CryptoProvider.hasAesHardware] on Android (architecture §7.1: frames use
 * ChaCha20-Poly1305 when a device has no AES instructions, because software AES-GCM is several times slower and not
 * constant-time).
 *
 * The CPU feature list is the primary source: `/proc/cpuinfo` lists the ARMv8 Cryptography Extension as `aes` in its
 * `Features` line (arm64 and 32-bit ARM kernels alike) and AES-NI as `aes` in the `flags` line (x86 emulators and
 * Chromebooks). Every Android kernel exposes it and apps may read it. When no feature line is found (an unusual
 * kernel, or the file is unreadable) a short benchmark decides instead: AES-GCM faster than ChaCha20-Poly1305 means
 * hardware AES.
 */
object AesHardwareProbe {
    /**
     * What `/proc/cpuinfo` says: true when a `Features` or `flags` line lists `aes`, false when such lines exist and none
     * does, null when the text has no feature line at all.
     */
    fun fromCpuInfo(cpuInfo: String): Boolean? {
        var sawFeatureLine = false
        for (line in cpuInfo.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            if (key != "features" && key != "flags") continue
            sawFeatureLine = true
            if (line.substring(colon + 1).split(' ', '\t').any { it == "aes" }) return true
        }
        return if (sawFeatureLine) false else null
    }

    /**
     * Probes this device: [readCpuInfo] first, then [benchmark] when the CPU information is inconclusive.
     *
     * @param benchmark true when AES-GCM outran ChaCha20-Poly1305; any exception counts as "no hardware AES".
     */
    fun detect(
        readCpuInfo: () -> String? = ::readProcCpuInfo,
        benchmark: () -> Boolean = ::aesFasterThanChaCha,
    ): Boolean {
        val text =
            try {
                readCpuInfo()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        text?.let(::fromCpuInfo)?.let { return it }
        return try {
            benchmark()
        } catch (e: Exception) {
            false
        }
    }

    private fun readProcCpuInfo(): String? = File("/proc/cpuinfo").takeIf { it.canRead() }?.readText()

    /**
     * Seals 64 KiB blocks with each AEAD for a few milliseconds and compares the best times. Runs once per process, and
     * only when `/proc/cpuinfo` is inconclusive.
     */
    fun aesFasterThanChaCha(): Boolean {
        val block = ByteArray(BENCH_BLOCK)
        val key = ByteArray(32) { it.toByte() }
        val aes = AeadProbe.aesGcm().create(key)
        val chacha = AeadProbe.chaCha().create(key)
        var counter = 0L

        fun best(aead: com.constrivo.drop.core.crypto.Aead): Long {
            var bestNanos = Long.MAX_VALUE
            repeat(BENCH_ROUNDS) {
                val nonce = ByteArray(12)
                val c = ++counter
                for (i in 0 until 8) nonce[4 + i] = (c ushr (8 * (7 - i))).toByte()
                val start = System.nanoTime()
                aead.seal(nonce, block)
                bestNanos = minOf(bestNanos, System.nanoTime() - start)
            }
            return bestNanos
        }
        // Warm both up first so JIT compilation does not decide the race.
        best(aes)
        best(chacha)
        return best(aes) < best(chacha)
    }

    private const val BENCH_BLOCK = 64 * 1024
    private const val BENCH_ROUNDS = 8
}
