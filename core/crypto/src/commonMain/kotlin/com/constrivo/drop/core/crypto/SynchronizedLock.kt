package com.constrivo.drop.core.crypto

/**
 * A mutual-exclusion lock for the few stateful objects in this module (key stores, frame counters, the per-session
 * stream registry). `commonMain` has no `synchronized`, so each target supplies one.
 */
internal expect class SynchronizedLock() {
    fun <T> withLock(block: () -> T): T
}
