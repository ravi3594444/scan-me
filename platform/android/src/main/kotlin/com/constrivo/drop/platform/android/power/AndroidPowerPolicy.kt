package com.constrivo.drop.platform.android.power

import android.content.Context
import android.os.PowerManager
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * The engine's [PowerPolicy] on Android (architecture §9 "Stay awake and cool", §10.1 "Thermal"; F-F6, F-E12):
 *
 * - [thermal] follows `PowerManager.addThermalStatusListener`, mapped by [ThermalLevels]; from `SEVERE` up the engine
 *   runs 2 streams and sends the `thermal` hint (§7.4, §7.8) instead of stalling (F-F6, T-09).
 * - [keepAwake] holds a partial wake lock while any transfer asks ([KeepAwakeCounter]): acquired with a lease and
 *   renewed, never held open-ended.
 *
 * Call [start] when the transfer service starts and [stop] when it ends; both are idempotent. Needs `WAKE_LOCK`
 * (declared by this module).
 *
 * @param onEvent a line for the ring-buffer log on every thermal change and wake-lock change (architecture §14).
 */
class AndroidPowerPolicy(
    context: Context,
    scope: CoroutineScope,
    private val onEvent: (String) -> Unit = {},
) : PowerPolicy {
    private val power: PowerManager? = context.applicationContext.getSystemService(PowerManager::class.java)
    private val mutable = MutableStateFlow(readStatus())
    private val lock = Any()
    private var listening = false

    private val listener =
        PowerManager.OnThermalStatusChangedListener { status ->
            val level = ThermalLevels.fromStatus(status)
            if (mutable.value != level) {
                mutable.value = level
                onEvent("thermal $level (status $status)")
            }
        }

    private val counter =
        KeepAwakeCounter(
            lock = PartialWakeLock(power),
            scope = scope,
            onChange = { reasons ->
                onEvent(if (reasons.isEmpty()) "wake lock released" else "wake lock held for ${reasons.joinToString()}")
            },
        )

    override val thermal: StateFlow<ThermalLevel> = mutable.asStateFlow()

    override fun keepAwake(reason: String): Releasable = counter.hold(reason)

    /** Whether the wake lock is held now (diagnostics). */
    val awake: Boolean get() = counter.isHeld

    fun start() {
        synchronized(lock) {
            if (listening) return
            listening = true
            mutable.value = readStatus()
            // The listener only writes a StateFlow, so it runs on the binder thread that delivers it.
            runCatching { power?.addThermalStatusListener(DIRECT, listener) }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!listening) return
            listening = false
            runCatching { power?.removeThermalStatusListener(listener) }
        }
        counter.releaseAll()
    }

    private fun readStatus(): ThermalLevel =
        ThermalLevels.fromStatus(runCatching { power?.currentThermalStatus }.getOrNull() ?: PowerManager.THERMAL_STATUS_NONE)

    /** A partial, not reference-counted wake lock ([KeepAwakeCounter] does the counting). */
    private class PartialWakeLock(
        power: PowerManager?,
    ) : WakeLockHandle {
        private val wakeLock: PowerManager.WakeLock? =
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) }

        override fun acquire(timeoutMillis: Long) {
            wakeLock?.acquire(timeoutMillis)
        }

        override fun release() {
            val lock = wakeLock ?: return
            if (lock.isHeld) runCatching { lock.release() }
        }
    }

    private companion object {
        const val TAG = "drop:transfer"
        val DIRECT = Executor { it.run() }
    }
}
