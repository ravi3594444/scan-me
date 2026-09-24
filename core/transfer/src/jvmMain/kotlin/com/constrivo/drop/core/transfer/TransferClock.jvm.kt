package com.constrivo.drop.core.transfer

import java.util.concurrent.locks.ReentrantLock

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual class TransferLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
