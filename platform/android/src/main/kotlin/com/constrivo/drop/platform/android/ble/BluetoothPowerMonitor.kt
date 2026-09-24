package com.constrivo.drop.platform.android.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Bluetooth adapter's power state as the radios see it. */
enum class BluetoothPower {
    /** No Bluetooth LE hardware (or no adapter): the Bluetooth paths are unavailable for good. */
    UNAVAILABLE,

    /** Off or turning on or off. */
    OFF,

    /** On: advertising, scanning and connections can run. */
    ON,
}

/**
 * Follows `BluetoothAdapter.ACTION_STATE_CHANGED` into [state]. Reading the adapter state needs no runtime permission.
 * Ref-counted: [start] and [stop] may be called by several owners (the beacon radio, the channel listener and capability
 * detection share one monitor); the receiver is registered while at least one owner has started it.
 */
class BluetoothPowerMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val hasLe = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    private val mutable = MutableStateFlow(read())
    private val lock = Any()
    private var owners = 0

    /** The current power state; accurate while started, a snapshot from construction or the last [refresh] otherwise. */
    val state: StateFlow<BluetoothPower> = mutable.asStateFlow()

    /** The adapter, or null without Bluetooth hardware. */
    val adapter: BluetoothAdapter? get() = manager?.adapter

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                mutable.value = fromAdapterState(value, hasLe && adapter != null)
            }
        }

    fun start() {
        synchronized(lock) {
            if (owners++ > 0) return
            // System broadcasts reach a not-exported receiver; nothing else can.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        refresh()
    }

    fun stop() {
        synchronized(lock) {
            if (owners == 0 || --owners > 0) return
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /** Re-reads the adapter state (after a permission grant, or when the process returns to the foreground). */
    fun refresh() {
        mutable.value = read()
    }

    private fun read(): BluetoothPower {
        val adapter = manager?.adapter
        if (!hasLe || adapter == null) return BluetoothPower.UNAVAILABLE
        return fromAdapterState(runCatching { adapter.state }.getOrDefault(BluetoothAdapter.STATE_OFF), true)
    }

    companion object {
        /** Maps a `BluetoothAdapter.STATE_*` value; [available] false means no LE hardware. */
        fun fromAdapterState(
            state: Int,
            available: Boolean,
        ): BluetoothPower =
            when {
                !available -> BluetoothPower.UNAVAILABLE
                state == BluetoothAdapter.STATE_ON -> BluetoothPower.ON
                else -> BluetoothPower.OFF
            }
    }
}
