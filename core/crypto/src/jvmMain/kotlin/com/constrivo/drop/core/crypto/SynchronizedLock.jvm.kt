package com.constrivo.drop.core.crypto

internal actual class SynchronizedLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
