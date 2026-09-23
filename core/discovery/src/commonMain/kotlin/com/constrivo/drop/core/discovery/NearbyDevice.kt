package com.constrivo.drop.core.discovery

import kotlin.time.TimeSource

/**
 * Wall-clock time in unix milliseconds. Discovery reads it only to compute epochs (§5.3: which rotating IDs are
 * current). It can step backwards or forwards (NTP, NITZ, the user), so nothing in discovery measures durations on it.
 */
fun interface WallClock {
    fun nowMillis(): Long
}

/**
 * Monotonic time in milliseconds from an arbitrary origin. It never steps when the wall clock is corrected, so every
 * duration in discovery is measured on it: the 5 s bubble expiry, the 250 ms smoothing windows, mDNS record ageing and
 * the wake-up alarms. On Android pass `SystemClock.elapsedRealtime()`; [SystemMonotonicClock] is the portable default.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/** [MonotonicClock] on the platform's monotonic time source ([TimeSource.Monotonic]). */
object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun elapsedMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * One moment read from both clocks: [elapsedMillis] ([MonotonicClock]) for every duration and deadline, [unixMillis]
 * ([WallClock]) only for epoch arithmetic.
 */
data class ClockReading(
    val elapsedMillis: Long,
    val unixMillis: Long,
)

/** How a nearby device was found. */
enum class DiscoverySource { BLUETOOTH, LAN }

/** Where to reach a device found over mDNS: the DNS-SD instance, its host and the control port from the TXT record. */
data class LanEndpoint(
    val instanceName: String,
    val host: String,
    val port: Int,
)

/** A Bluetooth address a device's beacon was heard from, and when it was last heard ([MonotonicClock]). */
data class RadioAddress(
    val address: String,
    val lastSeenElapsedMillis: Long,
)

/**
 * One device on the radar (F‑A2, F‑A3, design §3), immutable.
 *
 * Nothing heard over the air is authenticated: a beacon can be replayed or relayed and a TXT record forged. So the
 * model keeps every candidate way to reach the device instead of letting the latest packet win, and whatever
 * connects to it (WP5) tries each one behind the handshake's identity check (F‑B4).
 *
 * @property key stable for the device while it stays visible: `t:<deviceId>` for a trusted peer (stable across
 *   sessions and epochs); `e:<eph hex>` for anyone else, named after the first rotating ID it was seen with. A
 *   stranger's key survives its ID rotation when the tracker can tell it is the same device (see
 *   [NearbyDeviceTracker]) or when a session links the next ID ([NearbyDevices.link]); otherwise it changes at the
 *   rotation, by design (F‑J1).
 * @property trustedDeviceId set when the ephemeral ID resolved with a trusted peer's `k_adv`. This is recognition,
 *   not authentication: a beacon can be replayed within its epoch, so the trusted badge and any auto-accept rest on
 *   the handshake's identity check (F‑B4), never on this field alone.
 * @property nickname for a trusted peer the name stored at pairing. Otherwise the scan-response name (up to 24 bytes,
 *   [nicknameTruncated] when shortened), replaced by the mDNS `nick` (up to 64 bytes) only when that is the full
 *   form of the shortened Bluetooth name or no Bluetooth name is known: when the two disagree, the name heard over
 *   Bluetooth wins, because a LAN host cannot then relabel a bubble. Null when none is known (Trusted-only peers
 *   advertise none).
 * @property platform [DevicePlatform.UNKNOWN] for a platform added by a later app version (generic glyph).
 * @property ring from the smoothed RSSI with hysteresis; [Ring.MIDDLE] for devices known only over mDNS.
 * @property stableAngleDegrees hash-derived angle in `[0, 360)` ([RadarPlacement.stableAngleDegrees] of [key]).
 * @property smoothedRssiDbm null when no Bluetooth reading is current.
 * @property capabilities and [networkHint] from the beacon when one is current, otherwise from the TXT record (which
 *   carries no hint).
 * @property radioAddresses every address the beacon was heard from within the last
 *   [NearbyConfig.beaconTimeoutMillis], in the order they were first heard (at most
 *   [NearbyConfig.maxRadioAddresses]), for GATT (S10). A relay can add its own address, so try each in turn behind
 *   the identity check; the first is the one heard first.
 * @property lanEndpoints every live DNS-SD announcement filed under this device, newest first (at most
 *   [NearbyConfig.maxLanClaims]). For a trusted peer try each behind the identity check. For a stranger, claims
 *   from two different hosts contradict each other and cannot be told apart, so none is listed until only one host
 *   is left ([DiscoveryCounters.contestedLanClaims]).
 * @property lastSeenElapsedMillis the latest beacon or mDNS announcement on the [MonotonicClock], as of this snapshot.
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
    val radioAddresses: List<RadioAddress>,
    val carrier: BeaconCarrier?,
    val lanEndpoints: List<LanEndpoint>,
    val sources: Set<DiscoverySource>,
    val lastSeenElapsedMillis: Long,
) {
    val trusted: Boolean get() = trustedDeviceId != null

    /** Seen only over mDNS (no Bluetooth), shown with the "network" glyph (design §3.2). */
    val lanOnly: Boolean get() = DiscoverySource.BLUETOOTH !in sources

    /** The input [RadarPlacement.layout] needs for this device. */
    fun toRadarItem(): RadarItem = RadarItem(key, ring, trusted, smoothedRssiDbm)
}

/**
 * Tunables of [NearbyDeviceTracker] and [NearbyDevices]. Durations are on the [MonotonicClock].
 *
 * @property beaconTimeoutMillis a device's Bluetooth presence ends this long after its last beacon (design §3.3); so
 *   does each of its [NearbyDevice.radioAddresses].
 * @property lanRecordMaxAgeMillis an mDNS record not re-announced for this long is dropped even without a "lost"
 *   event; records rotate every epoch (N4), so two epochs is generous.
 * @property maxDevices new strangers are ignored while this many devices are tracked (flooding guard); trusted peers
 *   are always admitted.
 * @property maxRadioAddresses candidate Bluetooth addresses kept per device; the least recently heard one makes room.
 * @property maxLanClaims mDNS instances kept per device; further ones are refused while these are live, so a flood of
 *   forged announcements cannot push out the first ones.
 * @property epochLinkWindowMillis how close to an epoch boundary (wall clock) a stranger's new rotating ID must first
 *   appear, and how recently its old one must have been heard, for the tracker to keep its bubble (see
 *   [NearbyDeviceTracker]).
 * @property ownSecretRetentionMillis how long an own `k_adv` that left [TrustState] is still recognised.
 * @property minPublishIntervalMillis [NearbyDevices] republishes at most this often (10 Hz by default).
 */
data class NearbyConfig(
    val beaconTimeoutMillis: Long = 5_000,
    val lanRecordMaxAgeMillis: Long = 2 * EphemeralIds.EPOCH_MILLIS,
    val maxDevices: Int = 256,
    val maxRadioAddresses: Int = 4,
    val maxLanClaims: Int = 4,
    val epochLinkWindowMillis: Long = 10_000,
    val ownSecretRetentionMillis: Long = 2 * EphemeralIds.EPOCH_MILLIS,
    val minPublishIntervalMillis: Long = 100,
    val smoothing: RssiSmoothing = RssiSmoothing(),
    val rings: RingThresholds = RingThresholds(),
) {
    init {
        require(beaconTimeoutMillis > 0 && lanRecordMaxAgeMillis > 0) { "timeouts must be positive" }
        require(maxDevices > 0 && maxRadioAddresses > 0 && maxLanClaims > 0) { "limits must be positive" }
        require(epochLinkWindowMillis >= 0 && ownSecretRetentionMillis >= 0 && minPublishIntervalMillis >= 0) {
            "durations must not be negative"
        }
    }
}

/** Why packets were dropped or withheld, for the local diagnostics log (architecture §14). */
data class DiscoveryCounters(
    val malformedBeacons: Long = 0,
    val unsupportedBeacons: Long = 0,
    val malformedLanRecords: Long = 0,
    val ownEchoes: Long = 0,
    val trustedOnlyStrangers: Long = 0,
    val flooded: Long = 0,
    /** A stranger's mDNS claim that contradicts another host's claim for the same rotating ID. */
    val contestedLanClaims: Long = 0,
    /** mDNS claims refused because a device already had [NearbyConfig.maxLanClaims] live ones. */
    val refusedLanClaims: Long = 0,
    /** Beacons of a stranger's old rotating ID after its bubble moved on to the next one. */
    val retiredIdBeacons: Long = 0,
    /** Automatic stranger links that were undone because the evidence turned out ambiguous. */
    val undoneLinks: Long = 0,
)
