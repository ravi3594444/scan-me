package com.constrivo.drop.platform.windows

import com.constrivo.drop.core.discovery.BeaconCarrier

/** Platform facts the engine needs before the radios are wired up (implementation plan, spec change S11). */
object WindowsPlatform {
    /** Windows apps may only publish manufacturer-specific data (BluetoothLEAdvertisementPublisher). */
    val beaconCarrier: BeaconCarrier = BeaconCarrier.MANUFACTURER_DATA

    const val CAN_ADVERTISE: Boolean = true
}
