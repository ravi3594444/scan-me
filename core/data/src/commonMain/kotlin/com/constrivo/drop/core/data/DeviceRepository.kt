package com.constrivo.drop.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.crypto.trust.TrustedProof
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.discovery.BluetoothAddress
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import com.constrivo.drop.core.data.db.Device as DeviceRow

/**
 * Peer devices and their trust (architecture §12 `device`, §5.3, §6; F-B4, F-G3; spec change S3).
 *
 * Lifecycle: a handshake reports the peer with [recordPeer] (an untrusted row, needed before any transfer with it);
 * a confirmed SAS or a verified QR makes it trusted with [trust]; its `TrustShare` delivers the advertising secret
 * via [storeAdvertisingSecret]; beacons resolved to it bump [recordSighting]. [forget] makes it a stranger again.
 *
 * Every function is main-safe (runs on the database context). Updates that name a device return false when there is
 * no such device, or when it is not in the state the update needs (for example [setAutoAccept] on an untrusted
 * device); they never create rows. Secrets are stored through the [SecretFieldCipher] given to [DropData.open].
 */
class DeviceRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
    private val clock: WallClock,
    private val crypto: CryptoProvider,
    private val cipher: SecretFieldCipher,
) {
    private val queries get() = database.deviceQueries

    /**
     * Records a peer met through a handshake: creates the untrusted row on first contact, otherwise updates its
     * [nickname] and [platform] (a trusted device keeps its trust when its name changes, F-B4) and its last-seen time.
     * The device id is derived from [identityKey], so a caller cannot store a key under the wrong id.
     *
     * @param nickname the name from the peer's `Hello`; sanitised with `Nicknames.normalize` (empty if nothing
     *   visible is left).
     * @throws IdentityConflictException if the id is stored with a different key (a 128-bit collision).
     * @throws IllegalArgumentException if [identityKey] is not 32 bytes.
     */
    suspend fun recordPeer(
        identityKey: ByteArray,
        nickname: String,
        platform: DevicePlatform,
        seenAtMillis: Long = clock.nowMillis(),
    ): Device {
        val key = identityKey.copyOf()
        val id = DeviceIds.of(crypto, key)
        val name = Nicknames.normalize(nickname)?.text ?: ""
        val platformText = DevicePlatformColumn.encode(platform)
        return withContext(context) {
            database.transactionWithResult {
                val existing = queries.selectById(id).executeAsOneOrNull()
                if (existing == null) {
                    queries.insertPeer(id = id, identityKey = key, nickname = name, platform = platformText, seenAt = seenAtMillis)
                } else {
                    if (!existing.identity_pk.contentEquals(key)) throw IdentityConflictException(id)
                    queries.updatePeer(nickname = name, platform = platformText, seenAt = seenAtMillis, id = id)
                }
                toDevice(queries.selectById(id).executeAsOne())
            }
        }
    }

    /**
     * Records a browser receive session (F-D6, WP9), which has no identity key: [id] is a random device id the
     * server picked for the session, [label] what History shows (for example "Browser"). Such a device can never be
     * trusted.
     *
     * @throws IllegalArgumentException if [id] is malformed or already names a device with an identity key.
     */
    suspend fun recordBrowserPeer(
        id: String,
        label: String,
        seenAtMillis: Long = clock.nowMillis(),
    ): Device {
        DeviceIds.requireValid(id)
        val name = Nicknames.normalize(label)?.text ?: ""
        return withContext(context) {
            database.transactionWithResult {
                val existing = queries.selectById(id).executeAsOneOrNull()
                if (existing == null) {
                    queries.insertBrowser(id = id, nickname = name, seenAt = seenAtMillis)
                } else {
                    require(existing.identity_pk == null) { "device $id is not a browser session" }
                    queries.updateBrowser(nickname = name, seenAt = seenAtMillis, id = id)
                }
                toDevice(queries.selectById(id).executeAsOne())
            }
        }
    }

    /** Moves the device's last-seen time forward to [seenAtMillis] (a resolved beacon or LAN record); never back. */
    suspend fun recordSighting(
        deviceId: String,
        seenAtMillis: Long = clock.nowMillis(),
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        return withContext(context) { queries.touchLastSeen(seenAt = seenAtMillis, id = deviceId).value > 0 }
    }

    /**
     * Makes the device trusted after a confirmed SAS or a verified QR (F-B3, F-B5), storing the pairing's
     * [recognitionSecret] and, when the peer's `TrustShare` already arrived, its [advertisingSecret] with
     * [advertisingSecretGeneration] (S3). Pairing again replaces the secrets and keeps the first trust time.
     *
     * @return false when there is no such device, or it is a browser session.
     * @throws IllegalArgumentException for a recognition secret that is not 32 bytes, or an advertising secret without
     *   a generation (or the reverse), or a negative generation.
     */
    suspend fun trust(
        deviceId: String,
        recognitionSecret: ByteArray,
        advertisingSecret: AdvertisingSecret? = null,
        advertisingSecretGeneration: Int? = null,
        atMillis: Long = clock.nowMillis(),
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        require(recognitionSecret.size == TrustedProof.SECRET_SIZE) { "a recognition secret is ${TrustedProof.SECRET_SIZE} bytes" }
        require((advertisingSecret == null) == (advertisingSecretGeneration == null)) {
            "an advertising secret and its generation go together"
        }
        require(advertisingSecretGeneration == null || advertisingSecretGeneration >= 0) { "generation must be non-negative" }
        val sealedRecognition = cipher.seal(recognitionSecret, recognitionContext(deviceId))
        val sealedAdvertising = advertisingSecret?.let { sealAdvertising(deviceId, it) }
        return withContext(context) {
            queries
                .trust(
                    recognitionSecret = sealedRecognition,
                    advertisingSecret = sealedAdvertising,
                    advertisingGeneration = advertisingSecretGeneration?.toLong(),
                    trustedAt = atMillis,
                    id = deviceId,
                ).value > 0
        }
    }

    /**
     * Stores the advertising secret a trusted peer shared in `TrustShare` (S3). The previous one is kept as the
     * previous generation, so beacons sent just before the peer rotated still resolve.
     *
     * @return true when stored; false when the device is missing or untrusted, or [generation] is not newer than the
     *   stored one (a duplicate or replayed share).
     */
    suspend fun storeAdvertisingSecret(
        deviceId: String,
        secret: AdvertisingSecret,
        generation: Int,
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        require(generation >= 0) { "generation must be non-negative" }
        val sealed = sealAdvertising(deviceId, secret)
        return withContext(context) {
            queries
                .rotateAdvertisingSecret(
                    advertisingSecret = sealed,
                    advertisingGeneration = generation.toLong(),
                    id = deviceId,
                ).value > 0
        }
    }

    /**
     * Stores (or with null clears) the Bluetooth Classic address of a trusted desktop (S10), which it stops
     * advertising in Trusted-only mode (WP1 note). False when the device is missing or untrusted.
     *
     * @throws IllegalArgumentException for an address that cannot be connected to (`BluetoothAddress.isUsable`).
     */
    suspend fun setClassicAddress(
        deviceId: String,
        address: BluetoothAddress?,
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        require(address == null || address.isUsable) { "address $address cannot be connected to" }
        return withContext(context) { queries.setClassicAddress(classicAddress = address?.value, id = deviceId).value > 0 }
    }

    /**
     * Sets the user's name for the device (Devices tab, F-G3), or with null goes back to the announced nickname.
     *
     * @throws IllegalArgumentException if [name] has no visible character. Names are sanitised and cut to 64 UTF-8
     *   bytes like nicknames.
     */
    suspend fun rename(
        deviceId: String,
        name: String?,
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        val clean = name?.let { requireNotNull(Nicknames.normalize(it)) { "a device name needs a visible character" }.text }
        return withContext(context) { queries.rename(customName = clean, id = deviceId).value > 0 }
    }

    /** Turns auto-accept (F-D2) on or off; false when the device is missing or untrusted (only trusted devices auto-accept). */
    suspend fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        return withContext(context) { queries.setAutoAccept(autoAccept = enabled.toDb(), id = deviceId).value > 0 }
    }

    /**
     * "Forget" (F-G3): clears trust, auto-accept, the recognition secret, both advertising secrets, the Classic
     * address and the custom name, so the device shows as untrusted next time and can no longer prove itself in a
     * `Hello`. The row stays for History. Returns false when there is no such device.
     *
     * S3 also rotates this device's own `k_adv` (`AdvertisingSecretStore.rotate()` in `core/crypto`) so the forgotten
     * peer can no longer resolve our beacon; the caller does that, since the secret does not live in the database.
     */
    suspend fun forget(deviceId: String): Boolean {
        DeviceIds.requireValid(deviceId)
        return withContext(context) { queries.forget(deviceId).value > 0 }
    }

    suspend fun find(deviceId: String): Device? {
        DeviceIds.requireValid(deviceId)
        return withContext(context) { queries.selectById(deviceId).executeAsOneOrNull()?.let(::toDevice) }
    }

    suspend fun findByIdentityKey(identityKey: ByteArray): Device? {
        require(identityKey.size == DeviceIds.IDENTITY_KEY_SIZE) { "an identity key is ${DeviceIds.IDENTITY_KEY_SIZE} bytes" }
        val key = identityKey.copyOf()
        return withContext(context) { queries.selectByIdentityKey(key).executeAsOneOrNull()?.let(::toDevice) }
    }

    /** The device, re-emitted on every change of the device table (distinct values only); null while it does not exist. */
    fun observe(deviceId: String): Flow<Device?> {
        DeviceIds.requireValid(deviceId)
        return queries
            .selectById(deviceId)
            .asFlow()
            .mapToOneOrNull(context)
            .map { row -> row?.let(::toDevice) }
            .distinctUntilChanged()
    }

    /** Trusted devices, most recently seen first: the Devices tab (F-G3). */
    fun observeTrusted(): Flow<List<Device>> =
        queries
            .selectTrusted()
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::toDevice) }
            .distinctUntilChanged()

    suspend fun trusted(): List<Device> = withContext(context) { queries.selectTrusted().executeAsList().map(::toDevice) }

    /** The secrets of every trusted device, for the handshake and the radar (S3). */
    suspend fun trustedKeys(): List<TrustedDeviceKeys> = withContext(context) { queries.selectTrusted().executeAsList().map(::toKeys) }

    /** The secrets of one trusted device (the handshake initiator's expected peer); null if missing or untrusted. */
    suspend fun trustedKeys(deviceId: String): TrustedDeviceKeys? {
        DeviceIds.requireValid(deviceId)
        return withContext(context) {
            queries.selectById(deviceId).executeAsOneOrNull()?.takeIf { it.trusted == 1L }?.let(::toKeys)
        }
    }

    /**
     * [trustedKeys], re-emitted whenever the trusted set or a secret changes: feed it to the radar's `TrustState`
     * ([toTrustedPeers]) and the handshake ([toTrustedPeerLookup]) so trust changes apply without a restart.
     */
    fun observeTrustedKeys(): Flow<List<TrustedDeviceKeys>> =
        queries
            .selectTrusted()
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::toKeys) }
            .distinctUntilChanged()

    private fun sealAdvertising(
        deviceId: String,
        secret: AdvertisingSecret,
    ): ByteArray {
        val raw = secret.bytes()
        try {
            return cipher.seal(raw, advertisingContext(deviceId))
        } finally {
            raw.fill(0)
        }
    }

    private fun toKeys(row: DeviceRow): TrustedDeviceKeys {
        val device = toDevice(row)
        val where = "device ${row.id}"
        val sealedRecognition = row.recognition_secret ?: throw DataCorruptionException("$where: trusted without a recognition secret")
        val recognition = cipher.open(sealedRecognition, recognitionContext(row.id))
        try {
            requireStored(recognition.size == TrustedProof.SECRET_SIZE) { "$where: recognition secret has ${recognition.size} bytes" }
            return TrustedDeviceKeys(
                device = device,
                recognitionSecret = recognition,
                advertisingSecret = row.peer_adv_secret?.let { openAdvertising(it, row.id) },
                previousAdvertisingSecret = row.previous_peer_adv_secret?.let { openAdvertising(it, row.id) },
            )
        } finally {
            recognition.fill(0)
        }
    }

    private fun openAdvertising(
        sealed: ByteArray,
        deviceId: String,
    ): AdvertisingSecret {
        val raw = cipher.open(sealed, advertisingContext(deviceId))
        try {
            requireStored(raw.size == AdvertisingSecret.SIZE) { "device $deviceId: advertising secret has ${raw.size} bytes" }
            return AdvertisingSecret(raw)
        } finally {
            raw.fill(0)
        }
    }

    private companion object {
        fun recognitionContext(deviceId: String) = "device:$deviceId:recognition_secret"

        /** Shared by the current and the previous generation, because a rotation moves the value between columns. */
        fun advertisingContext(deviceId: String) = "device:$deviceId:peer_adv_secret"

        fun toDevice(row: DeviceRow): Device {
            val where = "device ${row.id}"
            val key = row.identity_pk
            requireStored(key == null || key.size == DeviceIds.IDENTITY_KEY_SIZE) { "$where: identity key has ${key?.size} bytes" }
            val classic =
                row.classic_address?.let {
                    requireStored(it in 0..BluetoothAddress.MAX_VALUE) { "$where: classic address out of range" }
                    BluetoothAddress(it)
                }
            return Device(
                id = deviceIdFromDb(row.id),
                identityKey = key,
                nickname = row.nickname,
                customName = row.custom_name,
                platform = DevicePlatformColumn.decodeColumn(row.platform, where),
                isTrusted = booleanFromDb(row.trusted, "$where trusted"),
                autoAccept = booleanFromDb(row.auto_accept, "$where auto_accept"),
                advertisingSecretGeneration = row.peer_adv_generation?.let { intFromDb(it, "$where peer_adv_generation") },
                classicAddress = classic,
                firstSeenMillis = row.first_seen,
                lastSeenMillis = row.last_seen,
                trustedAtMillis = row.trusted_at,
            )
        }
    }
}
