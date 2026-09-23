package com.constrivo.drop.core.discovery

/** Wall-clock time in unix milliseconds. Injected everywhere discovery needs time, so tests are deterministic. */
fun interface WallClock {
    fun nowMillis(): Long
}

/** How a nearby device was found. */
enum class DiscoverySource { BLUETOOTH, LAN }

/** Where to reach a device found over mDNS: the DNS-SD instance, its host and the control port from the TXT record. */
data class LanEndpoint(
    val instanceName: String,
    val host: String,
    val port: Int,
)

/**
 * One device on the radar (F‑A2, F‑A3, design §3), immutable.
 *
 * @property key stable for the device while it stays visible: `t:<deviceId>` for a trusted peer (stable across
 *   sessions and epochs), `e:<eph hex>` for anyone else (changes when the stranger's ID rotates, by design, F‑J1).
 * @property trustedDeviceId set when the ephemeral ID resolved with a trusted peer's `k_adv`. This is recognition,
 *   not authentication: a beacon can be replayed within its epoch, so the trusted badge and any auto-accept rest on
 *   the handshake's identity check (F‑B4), never on this field alone.
 * @property nickname for a trusted peer the name stored at pairing; otherwise the mDNS `nick` (up to 64 bytes) or
 *   the scan-response name (up to 26 bytes, [nicknameTruncated] when shortened); null when none is known
 *   (Trusted-only peers advertise none).
 * @property ring from the smoothed RSSI with hysteresis; [Ring.MIDDLE] for devices known only over mDNS.
 * @property stableAngleDegrees hash-derived angle in `[0, 360)` ([RadarPlacement.stableAngleDegrees] of [key]).
 * @property smoothedRssiDbm null when no Bluetooth reading is current.
 * @property capabilities and [networkHint] from the beacon when one is current, otherwise from the TXT record (which
 *   carries no hint).
 * @property radioAddress the platform's Bluetooth address for GATT (S10), from the latest beacon.
 * @property lastSeenMillis the latest beacon or mDNS announcement, as of this snapshot.
 */
data class NearbyDevice(
    val key: String,
    val ephemeralId: EphemeralId,
    val trustedDeviceId: String?,
    val nickname: String?,
    val nicknameTruncated: Boolean,
    val platform: DevicePlatform,
    val visibility: Visibility,
    val capabilities: Capabilities,
    val networkHint: NetworkHint,
    val ring: Ring,
    val stableAngleDegrees: Double,
    val smoothedRssiDbm: Double?,
    val classicAddress: BluetoothAddress?,
    val radioAddress: String?,
    val carrier: BeaconCarrier?,
    val lanEndpoint: LanEndpoint?,
    val sources: Set<DiscoverySource>,
    val lastSeenMillis: Long,
) {
    val trusted: Boolean get() = trustedDeviceId != null

    /** Seen only over mDNS (no Bluetooth), shown with the "network" glyph (design §3.2). */
    val lanOnly: Boolean get() = DiscoverySource.BLUETOOTH !in sources

    /** The input [RadarPlacement.layout] needs for this device. */
    fun toRadarItem(): RadarItem = RadarItem(key, ring, trusted, smoothedRssiDbm)
}

/**
 * Tunables of [NearbyDeviceTracker].
 *
 * @property beaconTimeoutMillis a device's Bluetooth presence ends this long after its last beacon (design §3.3).
 * @property lanRecordMaxAgeMillis an mDNS record not re-announced for this long is dropped even without a "lost"
 *   event; records rotate every epoch (N4), so two epochs is generous.
 * @property maxDevices new strangers are ignored while this many devices are tracked (flooding guard); trusted peers
 *   are always admitted.
 */
data class NearbyConfig(
    val beaconTimeoutMillis: Long = 5_000,
    val lanRecordMaxAgeMillis: Long = 2 * EphemeralIds.EPOCH_MILLIS,
    val maxDevices: Int = 256,
    val smoothing: RssiSmoothing = RssiSmoothing(),
    val rings: RingThresholds = RingThresholds(),
) {
    init {
        require(beaconTimeoutMillis > 0 && lanRecordMaxAgeMillis > 0) { "timeouts must be positive" }
        require(maxDevices > 0) { "maxDevices must be positive" }
    }
}

/** Why packets were dropped, for the local diagnostics log (architecture §14). */
data class DiscoveryCounters(
    val malformedBeacons: Long = 0,
    val unsupportedBeacons: Long = 0,
    val malformedLanRecords: Long = 0,
    val ownEchoes: Long = 0,
    val trustedOnlyStrangers: Long = 0,
    val flooded: Long = 0,
)
