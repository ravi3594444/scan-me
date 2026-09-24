package com.constrivo.drop.core.ladder.engine

import com.constrivo.drop.core.ladder.LadderGenerations
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The generation base of a reconnected session comes from the session's transcript hash, which both devices share,
 * never from a count of local session starts (a handshake that completed on one side only would shift one count).
 */
class LadderGenerationBaseTest {
    @Test
    fun `the base depends only on the transcript, is a multiple of PER_RUN, and is never the first run's`() {
        val random = Random(11)
        val bases = HashSet<Int>()
        repeat(2_000) {
            val transcript = random.nextBytes(32)
            val base = LadderTransferBridge.generationBaseFor(transcript)
            assertEquals(base, LadderTransferBridge.generationBaseFor(transcript.copyOf()), "the same on both devices")
            assertEquals(0, base % LadderGenerations.PER_RUN)
            assertNotEquals(0, base, "0 is the first run's base")
            assertTrue(base in LadderGenerations.PER_RUN..LadderGenerations.PER_RUN * (LadderTransferBridge.RUN_SLOTS - 1))
            // Every generation of the run stays representable (LadderGenerations.of checks the same bound).
            assertTrue(base <= Int.MAX_VALUE - LadderGenerations.PER_RUN)
            bases += base
        }
        assertTrue(bases.size > 1_990, "new sessions spread over the slots: ${bases.size} distinct of 2000")
    }
}
