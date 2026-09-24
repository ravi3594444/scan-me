package com.constrivo.drop.platform.android.crypto

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `hasAesHardware` (architecture §7.1): the CPU feature list decides, a benchmark only when it is inconclusive. */
class AesHardwareProbeTest {
    private val arm64WithCrypto =
        """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x51
        """.trimIndent()

    private val arm64WithoutCrypto =
        """
        processor	: 0
        Features	: fp asimd evtstrm crc32 cpuid
        processor	: 1
        Features	: fp asimd evtstrm crc32 cpuid
        """.trimIndent()

    private val x86 = "processor : 0\nflags : fpu vme de pse tsc msr pae mce cx8 apic sse sse2 ssse3 aes avx\n"

    @Test
    fun readsTheFeatureLine() {
        assertEquals(true, AesHardwareProbe.fromCpuInfo(arm64WithCrypto))
        assertEquals(false, AesHardwareProbe.fromCpuInfo(arm64WithoutCrypto))
        assertEquals(true, AesHardwareProbe.fromCpuInfo(x86))
        // "aes" must be a whole token: "vaes" or "aesni" alone does not count.
        assertEquals(false, AesHardwareProbe.fromCpuInfo("Features : fp vaes aesni\n"))
        assertNull(AesHardwareProbe.fromCpuInfo("processor : 0\nHardware : Qualcomm\n"))
        assertNull(AesHardwareProbe.fromCpuInfo(""))
    }

    @Test
    fun theBenchmarkRunsOnlyWhenCpuInfoIsInconclusive() {
        var benchmarks = 0
        assertTrue(AesHardwareProbe.detect(readCpuInfo = { arm64WithCrypto }, benchmark = { benchmarks++ == 0 && false }))
        assertFalse(AesHardwareProbe.detect(readCpuInfo = { arm64WithoutCrypto }, benchmark = { benchmarks++ == 0 }))
        assertEquals(0, benchmarks)
        assertTrue(AesHardwareProbe.detect(readCpuInfo = { null }, benchmark = { true }))
        assertFalse(AesHardwareProbe.detect(readCpuInfo = { "no features here" }, benchmark = { false }))
        assertTrue(AesHardwareProbe.detect(readCpuInfo = { throw IOException("hidden") }, benchmark = { true }))
        assertTrue(AesHardwareProbe.detect(readCpuInfo = { throw SecurityException("denied") }, benchmark = { true }))
        // A benchmark that fails counts as "no hardware AES", the safe choice (ChaCha20-Poly1305 is fast everywhere).
        assertFalse(AesHardwareProbe.detect(readCpuInfo = { null }, benchmark = { throw IllegalStateException("broken") }))
    }

    @Test
    fun theRealBenchmarkRunsOnTheJvm() {
        // Only checks that it completes; the answer depends on the build machine.
        AesHardwareProbe.aesFasterThanChaCha()
    }
}
