package com.constrivo.drop.core.data

/** A mutual-exclusion lock for the few non-suspending critical sections here; `commonMain` has no `synchronized`. */
internal expect class DataLock() {
    fun <T> withLock(block: () -> T): T
}
