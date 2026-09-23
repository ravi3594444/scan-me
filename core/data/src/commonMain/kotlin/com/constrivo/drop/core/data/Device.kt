package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.discovery.BluetoothAddress
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.TrustedPeer

/**
 * A peer device as the Devices tab, the incoming card and History show it (F-B4, F-G3; architecture §12). It holds
 * no secret; the engine reads those through [TrustedDeviceKeys].
 *
 * @property id `hex(SHA-256(identity_pk)[0..16])` ([DeviceIds]).
 * @property nickname the name the peer announced at its last handshake, sanitised (may be empty if it had no
 *   visible character). A trusted device keeps being recognised by its key when this changes (F-B4).
 * @property customName the user's own name for the device (Devices tab rename), or null.
 * @property advertisingSecretGeneration the generation of the peer's `k_adv` last received (S3), or null.
 * @property classicAddress Bluetooth Classic address of a trusted desktop (S10), for RFCOMM in Trusted-only mode.
 * @property trustedAtMillis when the device was first confirmed, null when untrusted.
 */
class Device internal constructor(
    val id: String,
    identityKey: ByteArray?,
    val nickname: String,
    val customName: String?,
    val platform: DevicePlatform,
    val isTrusted: Boolean,
    val autoAccept: Boolean,
    val advertisingSecretGeneration: Int?,
    val classicAddress: BluetoothAddress?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val trustedAtMillis: Long?,
) {
    private val identityKeyBytes: ByteArray? = identityKey?.copyOf()

    /** The name to show: [customName] when the user set one, else [nickname]. */
    val displayName: String get() = customName ?: nickname

    /** True for a browser receive session (WP9), which has no identity key and can never be trusted. */
    val isBrowserSession: Boolean get() = identityKeyBytes == null

    /** A copy of the 32-byte Ed25519 identity public key; null for a browser session. */
    fun identityKey(): ByteArray? = identityKeyBytes?.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Device &&
            id == other.id &&
            identityKeyBytes.contentEquals(other.identityKeyBytes) &&
            nickname == other.nickname &&
            customName == other.customName &&
            platform == other.platform &&
            isTrusted == other.isTrusted &&
            autoAccept == other.autoAccept &&
            advertisingSecretGeneration == other.advertisingSecretGeneration &&
            classicAddress == other.classicAddress &&
            firstSeenMillis == other.firstSeenMillis &&
            lastSeenMillis == other.lastSeenMillis &&
            trustedAtMillis == other.trustedAtMillis

    override fun hashCode(): Int = id.hashCode() * 31 + lastSeenMillis.hashCode()

    override fun toString(): String =
        "Device($id, '$displayName', $platform, trusted=$isTrusted, autoAccept=$autoAccept, lastSeen=$lastSeenMillis)"
}

/**
 * A trusted device with the secrets the engine needs (architecture §5.3, §6 with S3):
 * - [recognitionSecret] for the `Hello` trusted proof, in both directions ([toTrustedPeerLookup]);
 * - [advertisingSecret] (the peer's `k_adv`) and [previousAdvertisingSecret] to resolve its rotating beacon ID
 *   ([toTrustedPeer]); both are null until the peer's `TrustShare` arrives.
 *
 * [toString] never prints a secret.
 */
class TrustedDeviceKeys internal constructor(
    val device: Device,
    recognitionSecret: ByteArray,
    val advertisingSecret: AdvertisingSecret?,
    val previousAdvertisingSecret: AdvertisingSecret?,
) {
    private val recognition: ByteArray = recognitionSecret.copyOf()

    /** A copy of the 32-byte per-pair recognition secret. */
    fun recognitionSecret(): ByteArray = recognition.copyOf()

    /**
     * The discovery view of this device (S3): its current `k_adv` plus the previous generation, so a beacon sent just
     * before a rotation still resolves. Null while no advertising secret is known: the device's beacons cannot be
     * resolved yet, which the radar shows as a stranger until the next session shares the secret.
     */
    fun toTrustedPeer(): TrustedPeer? {
        val current = advertisingSecret ?: return null
        return TrustedPeer(
            deviceId = device.id,
            advertisingSecret = current.bytes(),
            nickname = device.displayName.ifEmpty { null },
            previousAdvertisingSecrets = listOfNotNull(previousAdvertisingSecret?.bytes()),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is TrustedDeviceKeys &&
            device == other.device &&
            recognition.contentEquals(other.recognition) &&
            advertisingSecret == other.advertisingSecret &&
            previousAdvertisingSecret == other.previousAdvertisingSecret

    override fun hashCode(): Int = device.hashCode()

    override fun toString(): String = "TrustedDeviceKeys(${device.id}, <secrets redacted>)"
}

/** The discovery trust input (`TrustState.peers`) for these devices; devices without a known `k_adv` are left out. */
fun Collection<TrustedDeviceKeys>.toTrustedPeers(): List<TrustedPeer> = mapNotNull { it.toTrustedPeer() }

/**
 * A handshake [TrustedPeerLookup] over a snapshot of these devices: the recognition secret for an identity key, or
 * null when that key is not trusted. Take a new snapshot when the trusted set changes
 * ([DeviceRepository.observeTrustedKeys]).
 */
fun Collection<TrustedDeviceKeys>.toTrustedPeerLookup(): TrustedPeerLookup {
    val byKey = HashMap<String, ByteArray>(size)
    for (keys in this) {
        val identity = keys.device.identityKey() ?: continue
        byKey[identity.toHex()] = keys.recognitionSecret()
    }
    return TrustedPeerLookup { identityKey -> byKey[identityKey.toHex()]?.copyOf() }
}
