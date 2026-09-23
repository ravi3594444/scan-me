package com.constrivo.drop.tools.fuzz

import java.lang.management.ManagementFactory

/**
 * Bytes allocated by the current thread, from HotSpot's per-thread allocation counter
 * (`com.sun.management.ThreadMXBean`). The fuzzer reads it before and after each decode to check that no input makes
 * a decoder allocate beyond its declared limit.
 */
fun interface AllocationMeter {
    /** Monotonic count of bytes the calling thread has allocated, or -1 when unavailable. */
    fun allocatedBytes(): Long

    companion object {
        val UNAVAILABLE: AllocationMeter = AllocationMeter { -1 }

        /** The HotSpot counter when the running JVM supports it, otherwise [UNAVAILABLE]. */
        fun current(): AllocationMeter {
            val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return UNAVAILABLE
            if (!bean.isThreadAllocatedMemorySupported) return UNAVAILABLE
            if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
            return AllocationMeter { bean.currentThreadAllocatedBytes }
        }
    }
}
