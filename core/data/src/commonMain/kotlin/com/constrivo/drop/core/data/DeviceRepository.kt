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
import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
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
 *
 * **Own `k_adv` generation (S3).** A peer keeps a shared advertising secret only when its generation is strictly newer
 * than the one it holds ([storeAdvertisingSecret]), and `core/crypto`'s `AdvertisingSecretStore` keeps only the secret,
 * so this device's own generation is kept here: send [ownAdvertisingGeneration] as `TrustShare.generation` with the
 * current secret, and on "Forget" call [advanceOwnAdvertisingGeneration] *before* `AdvertisingSecretStore.rotate()`.
 * A crash between the two then leaves a generation ahead of the secret (peers accept the same secret again), never a
 * new secret under an old generation, which every remaining peer would refuse.
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

    /**
     * Moves the device's last-seen time forward to [seenAtMillis] (a resolved beacon or LAN record); never back. The
     * radar reports every resolved beacon, so the time is stored only when it moves by at least
     * [SIGHTING_RESOLUTION_MILLIS]: a write re-runs every device flow, and the stored time only feeds "last seen"
     * labels. Returns false when there is no such device.
     */
    suspend fun recordSighting(
        deviceId: String,
        seenAtMillis: Long = clock.nowMillis(),
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        return withContext(context) {
            // Read first: an UPDATE notifies the flows even when it changes no row.
            val lastSeen = queries.selectLastSeen(deviceId).executeAsOneOrNull() ?: return@withContext false
            if (seenAtMillis - lastSeen >= SIGHTING_RESOLUTION_MILLIS) queries.touchLastSeen(seenAt = seenAtMillis, id = deviceId)
            true
        }
    }

    /**
     * Makes the device trusted after a confirmed SAS or a verified QR (F-B3, F-B5), storing the pairing's
     * [recognitionSecret] and, when the peer's `TrustShare` already arrived, its [advertisingSecret] with
     * [advertisingSecretGeneration] (S3). Pairing again replaces the recognition secret and keeps the first trust
     * time. Like [storeAdvertisingSecret], an advertising secret replaces the stored one only when its generation is
     * strictly newer (the stored one then becomes the previous generation); without one, or with a stale one, the
     * stored secret stays, so pairing again never makes a trusted device unresolvable or rolls its `k_adv` back.
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
        val generation = advertisingSecretGeneration?.toLong()
        return withContext(context) {
            database.transactionWithResult {
                val row = queries.selectById(deviceId).executeAsOneOrNull()
                if (row?.identity_pk == null) return@transactionWithResult false
                val stored = row.peer_adv_generation
                val newer = sealedAdvertising != null && generation != null && (stored == null || stored < generation)
                queries
                    .trust(
                        recognitionSecret = sealedRecognition,
                        advertisingSecret = if (newer) sealedAdvertising else row.peer_adv_secret,
                        advertisingGeneration = if (newer) generation else stored,
                        previousAdvertisingSecret = if (newer) row.peer_adv_secret else row.previous_peer_adv_secret,
                        previousUntil =
                            if (newer) {
                                row.peer_adv_secret?.let {
                                    atMillis + PREVIOUS_SECRET_GRACE_MILLIS
                                }
                            } else {
                                row.previous_peer_adv_until
                            },
                        trustedAt = atMillis,
                        id = deviceId,
                    ).value > 0
            }
        }
    }

    /**
     * Stores the advertising secret a trusted peer shared in `TrustShare` (S3). The stored one becomes the previous
     * generation, which keeps resolving for [PREVIOUS_SECRET_GRACE_MILLIS] after [atMillis], so beacons the peer sent
     * just before it rotated still resolve. After that it no longer resolves: the peer rotated because it forgot some
     * device, which still knows the old secret and must not be able to pass for the peer on this radar.
     *
     * Generations must increase: see the class comment for how this device numbers its own.
     *
     * @return true when stored; false when the device is missing or untrusted, or [generation] is not newer than the
     *   stored one (a duplicate or replayed share).
     */
    suspend fun storeAdvertisingSecret(
        deviceId: String,
        secret: AdvertisingSecret,
        generation: Int,
        atMillis: Long = clock.nowMillis(),
    ): Boolean {
        DeviceIds.requireValid(deviceId)
        require(generation >= 0) { "generation must be non-negative" }
        val sealed = sealAdvertising(deviceId, secret)
        return withContext(context) {
            database.transactionWithResult {
                val row = queries.selectById(deviceId).executeAsOneOrNull() ?: return@transactionWithResult false
                queries
                    .rotateAdvertisingSecret(
                        previousUntil = row.peer_adv_secret?.let { atMillis + PREVIOUS_SECRET_GRACE_MILLIS },
                        advertisingSecret = sealed,
                        advertisingGeneration = generation.toLong(),
                        id = deviceId,
                    ).value > 0
            }
        }
    }

    /** The generation of this device's own `k_adv` to send in `TrustShare` (S3): 0 until the first rotation. */
    suspend fun ownAdvertisingGeneration(): Int = withContext(context) { readOwnGeneration() }

    /**
     * Advances this device's own `k_adv` generation for a rotation and returns the new one. Call it before
     * `AdvertisingSecretStore.rotate()` (class comment), then share the new secret under the returned generation.
     */
    suspend fun advanceOwnAdvertisingGeneration(): Int =
        withContext(context) {
            database.transactionWithResult {
                val current = readOwnGeneration()
                check(current < Int.MAX_VALUE) { "the advertising-secret generation is exhausted" }
                (current + 1).also { database.settingsQueries.put(OWN_GENERATION_KEY, it.toString()) }
            }
        }

    private fun readOwnGeneration(): Int {
        val text = database.settingsQueries.selectValue(OWN_GENERATION_KEY).executeAsOneOrNull() ?: return 0
        val value = text.toIntOrNull()
        requireStored(value != null && value >= 0 && value.toString() == text) { "own advertising-secret generation holds '$text'" }
        return value!!
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

    /**
     * The secrets of every trusted device, in device id order, for the handshake and the radar (S3). A device whose
     * stored secrets do not open (a lost field key, a damaged row) is left out and passed to [onUnreadable], so one
     * bad row never takes every trusted device off the radar; it can be paired again.
     */
    suspend fun trustedKeys(
        onUnreadable: (deviceId: String, error: DataCorruptionException) -> Unit = { _, _ -> },
    ): List<TrustedDeviceKeys> {
        val rows = withContext(context) { queries.selectTrustedById().executeAsList() }
        return decodeKeys(rows, clock.nowMillis(), onUnreadable)
    }

    /**
     * The secrets of one trusted device (the handshake initiator's expected peer); null if missing or untrusted.
     *
     * @throws DataCorruptionException if its stored secrets do not open.
     */
    suspend fun trustedKeys(deviceId: String): TrustedDeviceKeys? {
        DeviceIds.requireValid(deviceId)
        val row = withContext(context) { queries.selectById(deviceId).executeAsOneOrNull()?.takeIf { it.trusted == 1L } }
        return row?.let { toKeys(it, clock.nowMillis()) }
    }

    /**
     * [trustedKeys], re-emitted whenever the trusted set, a name or a secret changes, and when a previous advertising
     * secret stops resolving ([storeAdvertisingSecret]): feed it to the radar's `TrustState` ([toTrustedPeers]) and
     * the handshake ([toTrustedPeerLookup]) so trust changes apply without a restart.
     *
     * Sightings do not re-emit it: the list is in device id order, and a change of `last_seen` alone neither re-opens
     * the secrets nor emits, so the radar never rebuilds its resolver for a beacon ([recordSighting]). The
     * [TrustedDeviceKeys.device] of an emitted value may therefore show an older last-seen time; the Devices tab
     * reads [observeTrusted].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTrustedKeys(
        onUnreadable: (deviceId: String, error: DataCorruptionException) -> Unit = { _, _ -> },
    ): Flow<List<TrustedDeviceKeys>> =
        queries
            .selectTrustedById()
            .asFlow()
            .mapToList(context)
            .map { rows -> rows.map(::KeyRow) }
            .distinctUntilChanged()
            .transformLatest { rows ->
                while (true) {
                    val now = clock.nowMillis()
                    emit(decodeKeys(rows.map { it.row }, now, onUnreadable))
                    val next = rows.mapNotNull { it.row.previous_peer_adv_until }.filter { it > now }.minOrNull() ?: break
                    delay(next - now)
                }
            }.distinctUntilChanged()

    /**
     * Re-seals, with the configured cipher, every secret still stored in [SecretFieldCipher.PLAINTEXT] framing: a
     * platform that supplies an [AeadSecretFieldCipher] in a later release finds its existing trusted devices intact.
     * [DropData.open] calls it, blocking; it runs once per database, in one transaction, the first time a cipher other
     * than PLAINTEXT opens it, and never again, so a plaintext value written into the file later does not open.
     * Returns how many devices were re-sealed.
     */
    internal fun resealPlaintextSecrets(): Int {
        if (cipher === SecretFieldCipher.PLAINTEXT) return 0
        return database.transactionWithResult {
            if (database.settingsQueries.selectValue(RESEALED_KEY).executeAsOneOrNull() != null) return@transactionWithResult 0
            database.settingsQueries.put(RESEALED_KEY, "true")
            var resealed = 0
            // Only trusted rows hold secrets (0.sqm CHECK).
            for (row in queries.selectTrustedById().executeAsList()) {
                val recognition = reseal(row.recognition_secret, recognitionContext(row.id))
                val current = reseal(row.peer_adv_secret, advertisingContext(row.id))
                val previous = reseal(row.previous_peer_adv_secret, advertisingContext(row.id))
                if (recognition === row.recognition_secret && current === row.peer_adv_secret &&
                    previous === row.previous_peer_adv_secret
                ) {
                    continue
                }
                queries.updateSecrets(recognition, current, previous, row.id)
                resealed++
            }
            resealed
        }
    }

    /** [stored] sealed with [cipher] if it is a PLAINTEXT value, else [stored] itself. */
    private fun reseal(
        stored: ByteArray?,
        context: String,
    ): ByteArray? {
        if (stored == null || !SecretFieldCipher.isPlaintextValue(stored)) return stored
        val plaintext = SecretFieldCipher.PLAINTEXT.open(stored, context)
        try {
            return cipher.seal(plaintext, context)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decodeKeys(
        rows: List<DeviceRow>,
        nowMillis: Long,
        onUnreadable: (deviceId: String, error: DataCorruptionException) -> Unit,
    ): List<TrustedDeviceKeys> =
        rows.mapNotNull { row ->
            try {
                toKeys(row, nowMillis)
            } catch (e: DataCorruptionException) {
                onUnreadable(row.id, e)
                null
            }
        }

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

    /** The keys of a trusted [row] at [nowMillis]: the previous advertising secret only while its grace period runs. */
    private fun toKeys(
        row: DeviceRow,
        nowMillis: Long,
    ): TrustedDeviceKeys {
        val device = toDevice(row)
        val where = "device ${row.id}"
        val sealedRecognition = row.recognition_secret ?: throw DataCorruptionException("$where: trusted without a recognition secret")
        val recognition = cipher.open(sealedRecognition, recognitionContext(row.id))
        try {
            requireStored(recognition.size == TrustedProof.SECRET_SIZE) { "$where: recognition secret has ${recognition.size} bytes" }
            val previousResolves = row.previous_peer_adv_until?.let { nowMillis < it } ?: false
            return TrustedDeviceKeys(
                device = device,
                recognitionSecret = recognition,
                advertisingSecret = row.peer_adv_secret?.let { openAdvertising(it, row.id) },
                previousAdvertisingSecret = row.previous_peer_adv_secret?.takeIf { previousResolves }?.let { openAdvertising(it, row.id) },
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

    /** A trusted row as the keys flows compare it: every column but `last_seen`, blobs by content. */
    private class KeyRow(
        val row: DeviceRow,
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is KeyRow) return false
            val a = row
            val b = other.row
            return a.id == b.id &&
                a.identity_pk.contentEquals(b.identity_pk) &&
                a.nickname == b.nickname &&
                a.custom_name == b.custom_name &&
                a.platform == b.platform &&
                a.trusted == b.trusted &&
                a.auto_accept == b.auto_accept &&
                a.recognition_secret.contentEquals(b.recognition_secret) &&
                a.peer_adv_secret.contentEquals(b.peer_adv_secret) &&
                a.peer_adv_generation == b.peer_adv_generation &&
                a.previous_peer_adv_secret.contentEquals(b.previous_peer_adv_secret) &&
                a.previous_peer_adv_until == b.previous_peer_adv_until &&
                a.classic_address == b.classic_address &&
                a.first_seen == b.first_seen &&
                a.trusted_at == b.trusted_at
        }

        override fun hashCode(): Int = row.id.hashCode()
    }

    companion object {
        /** [recordSighting] stores a sighting only when it moves the last-seen time by at least this much (30 s). */
        const val SIGHTING_RESOLUTION_MILLIS: Long = 30_000

        /**
         * How long a peer's previous advertising secret keeps resolving after the new one was stored: two beacon epochs
         * (30 min), the time the peer may still advertise an ID of the old one.
         */
        const val PREVIOUS_SECRET_GRACE_MILLIS: Long = 2 * EphemeralIds.EPOCH_MILLIS

        /** The `settings` row holding this device's own `k_adv` generation (not a user setting). */
        internal const val OWN_GENERATION_KEY = "trust.own_adv_generation"

        /** The `settings` row marking that [resealPlaintextSecrets] ran (not a user setting). */
        internal const val RESEALED_KEY = "trust.plaintext_resealed"

        private fun recognitionContext(deviceId: String) = "device:$deviceId:recognition_secret"

        /** Shared by the current and the previous generation, because a rotation moves the value between columns. */
        private fun advertisingContext(deviceId: String) = "device:$deviceId:peer_adv_secret"

        private fun toDevice(row: DeviceRow): Device {
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
