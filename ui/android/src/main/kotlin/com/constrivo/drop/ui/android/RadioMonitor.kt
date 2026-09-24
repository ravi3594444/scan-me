package com.constrivo.drop.ui.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.RadioState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The radio facts behind the radar's notices (design §8.1: "Bluetooth off", "Wi‑Fi off", "Permission missing"):
 * Bluetooth and Wi‑Fi on or off, from their state broadcasts, and whether the Nearby permissions are granted, re-read
 * whenever [AndroidPermissions.changes] ticks. Reading adapter state needs no runtime permission on Android 12+.
 */
internal class RadioMonitor(
    private val context: Context,
    private val permissions: AndroidPermissions,
) {
    private val bluetooth: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi: WifiManager? get() = context.getSystemService(WifiManager::class.java)

    private val hasBluetoothLe: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private data class Radios(
        val bluetoothOn: Boolean,
        val wifiOn: Boolean,
    )

    /** The current [RadioState], updated as the radios and grants change. */
    val state: Flow<RadioState> =
        combine(radios(), permissions.changes) { radios, _ ->
            RadioState(
                bluetoothAvailable = hasBluetoothLe && bluetooth != null,
                bluetoothOn = radios.bluetoothOn,
                wifiOn = radios.wifiOn,
                nearbyPermission = permissions.status(DropPermission.NEARBY).usable,
            )
        }.distinctUntilChanged()

    private fun snapshot() = Radios(bluetoothOn = bluetooth?.isEnabled == true, wifiOn = wifi?.isWifiEnabled == true)

    private fun radios(): Flow<Radios> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        trySend(snapshot())
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                }
            // System broadcasts reach a not-exported receiver; nothing else should.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(snapshot())
            awaitClose { context.unregisterReceiver(receiver) }
        }.distinctUntilChanged()
}
