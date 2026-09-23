package com.constrivo.drop.platform.android

import com.constrivo.drop.core.discovery.BeaconCarrier

/** Platform facts the engine needs before the radios are wired up (implementation plan, spec change S11). */
object AndroidPlatform {
    /** BluetoothLeAdvertiser supports service data. */
    val beaconCarrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA

    const val CAN_ADVERTISE: Boolean = true
}
