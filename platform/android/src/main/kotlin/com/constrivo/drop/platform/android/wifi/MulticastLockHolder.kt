package com.constrivo.drop.platform.android.wifi

import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions

/** The platform lock behind [MulticastLockHolder] (`WifiManager.MulticastLock`), a seam for the JVM tests. */
interface MulticastLockApi {
    val isHeld: Boolean

    /** @throws SecurityException without `CHANGE_WIFI_MULTICAST_STATE`. */
    fun acquire()

    fun release()
}

/** [MulticastLockApi] over a non-reference-counted `WifiManager.MulticastLock` named [tag]. */
class AndroidMulticastLock(
    wifiManager: WifiManager,
    tag: String,
) : MulticastLockApi {
    private val lock: WifiManager.MulticastLock = wifiManager.createMulticastLock(tag).apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = lock.isHeld

    override fun acquire() = lock.acquire()

    override fun release() = lock.release()
}

/**
 * Keeps Wi-Fi multicast reception on while something needs it: the browser receive page's mDNS responder
 * (`web-receive`'s `MdnsResponder`, the WP9 carry-forward) and, on builds before the Tiramisu SDK extension 7,
 * `NsdManager` browsing ([needsLockForNsd]). Without the lock Android's Wi-Fi driver filters multicast frames, so
 * `drop.local` queries and mDNS answers never arrive.
 *
 * Each user holds a [Hold] from [hold]; the platform lock is acquired with the first and released with the last, so
 * users never release each other's reception. The platform lock is not reference-counted. A failure to acquire (no
 * `CHANGE_WIFI_MULTICAST_STATE`) is reported to [onError] and the hold is still returned, so a caller never fails for
 * it: multicast then works only as far as the platform allows. Thread-safe.
 */
class MulticastLockHolder(
    private val lock: MulticastLockApi,
    private val onError: (Throwable) -> Unit = {},
) {
    private val guard = Any()
    private val holders = LinkedHashMap<Hold, String>()

    /** The purposes holding the lock now, in the order they took it. */
    val purposes: List<String> get() = synchronized(guard) { holders.values.toList() }

    /** True while the platform lock is held. */
    val isHeld: Boolean get() = synchronized(guard) { runCatching { lock.isHeld }.getOrDefault(false) }

    /** Takes a hold for [purpose] (a label for the log); the lock stays on until every hold is closed. */
    fun hold(purpose: String): Hold {
        val hold = Hold()
        synchronized(guard) {
            if (holders.isEmpty()) {
                try {
                    lock.acquire()
                } catch (e: RuntimeException) {
                    onError(e)
                }
            }
            holders[hold] = purpose
        }
        return hold
    }

    private fun done(hold: Hold) {
        synchronized(guard) {
            if (holders.remove(hold) == null || holders.isNotEmpty()) return
            try {
                if (lock.isHeld) lock.release()
            } catch (e: RuntimeException) {
                onError(e)
            }
        }
    }

    /** One user's hold; [close] is idempotent. */
    inner class Hold internal constructor() : AutoCloseable {
        override fun close() = done(this)
    }

    companion object {
        /** The Tiramisu SDK extension from which `NsdManager` manages multicast reception by itself (see `NsdManager`). */
        const val NSD_SELF_MANAGED_EXTENSION: Int = 7

        /** True when `NsdManager` needs the app to hold the lock: before the Tiramisu SDK extension 7 (Android 12, early 13). */
        fun needsLockForNsd(): Boolean =
            try {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < NSD_SELF_MANAGED_EXTENSION
            } catch (_: RuntimeException) {
                true
            }
    }
}
