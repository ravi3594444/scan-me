package com.constrivo.drop.platform.android.bluetooth

/**
 * A resource shared by users that may outlive its owner's [close], such as the GATT callback thread of
 * [BluetoothChannelConnector], which the GATT-stream channels it returned need until they close: [release] runs once,
 * when the owner has closed and the last user is gone. Thread-safe.
 */
internal class SharedResource<U : Any>(
    private val release: () -> Unit,
) {
    private val lock = Any()
    private val users = HashSet<U>()
    private var closed = false
    private var released = false

    /** Registers [user]; false once [close] was called (the owner makes no new users then). */
    fun acquire(user: U): Boolean =
        synchronized(lock) {
            if (closed) return false
            users += user
            true
        }

    /** [user] no longer needs the resource. Idempotent. */
    fun done(user: U) {
        val now = synchronized(lock) { users.remove(user) && closed && users.isEmpty() && markReleased() }
        if (now) release()
    }

    /** The owner is done: the resource goes when its last user does, or at once when there is none. Idempotent. */
    fun close() {
        val now =
            synchronized(lock) {
                if (closed) return
                closed = true
                users.isEmpty() && markReleased()
            }
        if (now) release()
    }

    private fun markReleased(): Boolean {
        if (released) return false
        released = true
        return true
    }
}
