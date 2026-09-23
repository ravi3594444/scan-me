package com.constrivo.drop.platform.mac

import com.constrivo.drop.core.discovery.BeaconCarrier

/** Platform facts the engine needs before the radios are wired up (implementation plan, spec change S11). */
object MacPlatform {
    /** CBPeripheralManager can only advertise service UUIDs and a local name, so macOS scans but never advertises; phones find it over mDNS or its static QR. */
    val beaconCarrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA

    const val CAN_ADVERTISE: Boolean = false
}
