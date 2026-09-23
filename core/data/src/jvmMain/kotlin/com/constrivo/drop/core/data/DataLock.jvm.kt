package com.constrivo.drop.core.data

internal actual class DataLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
