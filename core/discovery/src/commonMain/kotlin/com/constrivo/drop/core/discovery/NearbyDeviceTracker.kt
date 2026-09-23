package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider

/**
 * The nearby-device state machine behind [NearbyDevices]: synchronous, single-threaded and driven by explicit
 * timestamps, so it can be tested (and previewed) without coroutines.
 *
 * Rules:
 * - Beacons are decoded ([BeaconBody.decode]); malformed or future-layout packets are counted and dropped.
 * - Ephemeral IDs are resolved with [EphemeralIdResolver] against the trusted peers (S3). Our own beacon heard back
 *   is dropped. A Trusted-only beacon or TXT record that no trusted peer explains is dropped (F‑A5); a HIDDEN
 *   device never advertises in the first place.
 * - A trusted peer is keyed by its device ID, anyone else by its ephemeral ID, so Bluetooth and mDNS sightings of
 *   the same device merge whenever their IDs match (for trusted peers even across epochs).
 * - RSSI goes through [RssiSmoothing] and [RingThresholds]; readings outside −127…20 dBm (Android reports 127 for
 *   "unknown") count as presence but not as signal.
 * - Bluetooth presence ends [NearbyConfig.beaconTimeoutMillis] after the last beacon (design §3.3); mDNS presence
 *   ends with a "lost" event or after [NearbyConfig.lanRecordMaxAgeMillis]. A device disappears when both have ended.
 *
 * Every mutating call returns whether the visible list changed, ignoring [NearbyDevice.lastSeenMillis] alone, so
 * the caller republishes about four times a second per device at most, not on every packet.
 * Not thread-safe: confine an instance to one coroutine or thread.
 */
class NearbyDeviceTracker(
    private val crypto: CryptoProvider,
    ownAdvertisingSecret: ByteArray? = null,
    val config: NearbyConfig = NearbyConfig(),
) {
    private val own: ByteArray? = ownAdvertisingSecret?.copyOf()
    private var resolver = EphemeralIdResolver(crypto, emptyList(), own)
    private val entries = HashMap<String, Entry>()
    private val lanOwner = HashMap<String, String>()

    /** Drop counters since construction (or the last [clear]). */
    var counters: DiscoveryCounters = DiscoveryCounters()
        private set

    /** Number of devices currently tracked. */
    val size: Int get() = entries.size

    /**
     * Replaces the trusted peers (pairing, "Forget", a re-shared `k_adv`) and re-files every current sighting under
     * the new resolution, each resolved at the time it was seen. Should a device ID appear twice, its first entry
     * wins, so a faulty trust store cannot stop the radar.
     */
    fun setTrustedPeers(peers: Collection<TrustedPeer>): Boolean {
        val before = snapshot()
        resolver = EphemeralIdResolver(crypto, peers.distinctBy { it.deviceId }, own)
        val bleParts = entries.values.mapNotNull { it.ble }.sortedBy { it.body.ephemeralId.value }
        val lanParts = entries.values.flatMap { it.lan.values }.sortedBy { it.instanceName }
        entries.clear()
        lanOwner.clear()
        for (part in bleParts) {
            val entry = admit(part.body.ephemeralId, part.body.visibility, part.lastSeen, countDrops = false) ?: continue
            val existing = entry.ble
            if (existing == null || existing.lastSeen < part.lastSeen) entry.ble = part
        }
        for (part in lanParts) {
            val entry = admit(part.record.ephemeralId, part.record.visibility, part.seenAt, countDrops = false) ?: continue
            entry.lan[part.instanceName] = part
            lanOwner[part.instanceName] = entry.key
        }
        return snapshot() != before
    }

    /** Applies one Bluetooth sighting heard at [nowMillis]. */
    fun onSighting(
        sighting: BeaconSighting,
        nowMillis: Long,
    ): Boolean {
        val body =
            try {
                BeaconBody.decode(sighting.body)
            } catch (e: UnsupportedBeaconVersionException) {
                counters = counters.copy(unsupportedBeacons = counters.unsupportedBeacons + 1)
                return false
            } catch (e: DiscoveryFormatException) {
                counters = counters.copy(malformedBeacons = counters.malformedBeacons + 1)
                return false
            }
        val entry = admit(body.ephemeralId, body.visibility, nowMillis, countDrops = true) ?: return false
        val before = entry.view()
        val previous = entry.ble
        val rssi = sighting.rssiDbm.takeIf { it in MIN_RSSI..MAX_RSSI }
        val smoothed =
            when {
                rssi == null -> previous?.rssi
                previous?.rssi != null -> previous.rssi.add(rssi, nowMillis)
                else -> config.smoothing.start(rssi, nowMillis)
            }
        val ring =
            smoothed?.let { config.rings.classify(it.valueDbm, previous?.takeIf { p -> p.rssi != null }?.ring) } ?: Ring.MIDDLE
        val name = sighting.localName?.let { Nicknames.normalize(it) }
        val truncated = name?.let { it.truncated || sighting.localNameTruncated } ?: previous?.localNameTruncated ?: false
        entry.ble =
            BlePart(
                body = body,
                carrier = sighting.carrier,
                rssi = smoothed,
                ring = ring,
                localName = name?.text ?: previous?.localName,
                localNameTruncated = truncated,
                radioAddress = sighting.radioAddress ?: previous?.radioAddress,
                lastSeen = nowMillis,
            )
        return changed(before, entry.view())
    }

    /** Applies one mDNS event received at [nowMillis]. */
    fun onLanEvent(
        event: LanEvent,
        nowMillis: Long,
    ): Boolean =
        when (event) {
            is LanEvent.Lost -> removeLan(event.instanceName)
            is LanEvent.Found -> onLanFound(event.service, nowMillis)
        }

    /** Closes due RSSI windows and expires stale presence at [nowMillis]. */
    fun advanceTo(nowMillis: Long): Boolean {
        var changed = false
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val before = entry.view()
            entry.ble?.let { ble ->
                if (nowMillis - ble.lastSeen >= config.beaconTimeoutMillis) {
                    entry.ble = null
                } else if (ble.rssi != null) {
                    val advanced = ble.rssi.advanceTo(nowMillis)
                    if (advanced !== ble.rssi) {
                        entry.ble = ble.copy(rssi = advanced, ring = config.rings.classify(advanced.valueDbm, ble.ring))
                    }
                }
            }
            val lanIterator = entry.lan.values.iterator()
            while (lanIterator.hasNext()) {
                val part = lanIterator.next()
                if (nowMillis - part.seenAt >= config.lanRecordMaxAgeMillis) {
                    lanIterator.remove()
                    lanOwner.remove(part.instanceName)
                }
            }
            val after = entry.view()
            if (after == null) iterator.remove()
            if (changed(before, after)) changed = true
        }
        return changed
    }

    /** The earliest time at which [advanceTo] would change something, or null when nothing is pending. */
    fun nextDeadlineMillis(): Long? {
        var next = Long.MAX_VALUE
        for (entry in entries.values) {
            entry.ble?.let { ble ->
                next = minOf(next, ble.lastSeen + config.beaconTimeoutMillis)
                ble.rssi?.openWindowEndMillis?.let { next = minOf(next, it) }
            }
            for (part in entry.lan.values) next = minOf(next, part.seenAt + config.lanRecordMaxAgeMillis)
        }
        return if (next == Long.MAX_VALUE) null else next
    }

    /** The current devices: nearest ring first, then trusted before others, then by key. */
    fun snapshot(): List<NearbyDevice> =
        entries.values
            .mapNotNull { it.view() }
            .sortedWith(compareBy<NearbyDevice> { it.ring.ordinal }.thenBy { !it.trusted }.thenBy { it.key })

    /** Forgets every sighting and resets the counters; the trusted peers are kept. */
    fun clear() {
        entries.clear()
        lanOwner.clear()
        counters = DiscoveryCounters()
    }

    private fun onLanFound(
        service: LanService,
        nowMillis: Long,
    ): Boolean {
        val record =
            try {
                MdnsRecord.fromTxt(service.txt)
            } catch (e: DiscoveryFormatException) {
                counters = counters.copy(malformedLanRecords = counters.malformedLanRecords + 1)
                return removeLan(service.instanceName)
            }
        // A re-announcement may change the TXT record, so the instance is filed afresh.
        val removed = removeLan(service.instanceName)
        val entry = admit(record.ephemeralId, record.visibility, nowMillis, countDrops = true) ?: return removed
        val before = entry.view()
        entry.lan[service.instanceName] = LanPart(service.instanceName, record, service.host, nowMillis)
        lanOwner[service.instanceName] = entry.key
        return removed || changed(before, entry.view())
    }

    private fun removeLan(instanceName: String): Boolean {
        val key = lanOwner.remove(instanceName) ?: return false
        val entry = entries[key] ?: return false
        val before = entry.view()
        entry.lan.remove(instanceName)
        val after = entry.view()
        if (after == null) entries.remove(key)
        return changed(before, after)
    }

    /** Resolves [id] and returns the entry to file the sighting under, or null when the sighting must be dropped. */
    private fun admit(
        id: EphemeralId,
        visibility: Visibility,
        atMillis: Long,
        countDrops: Boolean,
    ): Entry? {
        val peer =
            when (val resolution = resolver.resolve(id, atMillis)) {
                EphemeralIdResolution.Own -> {
                    if (countDrops) counters = counters.copy(ownEchoes = counters.ownEchoes + 1)
                    return null
                }

                EphemeralIdResolution.Unknown -> {
                    null
                }

                is EphemeralIdResolution.Trusted -> {
                    resolution.peer
                }
            }
        if (peer == null && visibility == Visibility.TRUSTED_ONLY) {
            if (countDrops) counters = counters.copy(trustedOnlyStrangers = counters.trustedOnlyStrangers + 1)
            return null
        }
        val key = if (peer != null) "t:${peer.deviceId}" else "e:${id.toHex()}"
        entries[key]?.let { return it }
        if (peer == null && entries.size >= config.maxDevices) {
            if (countDrops) counters = counters.copy(flooded = counters.flooded + 1)
            return null
        }
        return Entry(key, peer).also { entries[key] = it }
    }

    private fun changed(
        before: NearbyDevice?,
        after: NearbyDevice?,
    ): Boolean {
        if (before == null || after == null) return before != after
        return before != after.copy(lastSeenMillis = before.lastSeenMillis)
    }

    private data class BlePart(
        val body: BeaconBody,
        val carrier: BeaconCarrier,
        val rssi: SmoothedRssi?,
        val ring: Ring,
        val localName: String?,
        val localNameTruncated: Boolean,
        val radioAddress: String?,
        val lastSeen: Long,
    )

    private class LanPart(
        val instanceName: String,
        val record: MdnsRecord,
        val host: String,
        val seenAt: Long,
    )

    private class Entry(
        val key: String,
        val peer: TrustedPeer?,
    ) {
        var ble: BlePart? = null
        val lan = LinkedHashMap<String, LanPart>()
        private val angle = RadarPlacement.stableAngleDegrees(key)
        private val storedNickname = peer?.nickname?.let { Nicknames.normalize(it)?.text }

        fun view(): NearbyDevice? {
            val ble = ble
            val lan = lan.values.maxWithOrNull(compareBy<LanPart> { it.seenAt }.thenBy { it.instanceName })
            if (ble == null && lan == null) return null
            val lanNick = lan?.record?.nickname
            val nickname = storedNickname ?: lanNick ?: ble?.localName
            val truncated = storedNickname == null && lanNick == null && ble?.localName != null && ble.localNameTruncated
            val lastSeen = maxOf(ble?.lastSeen ?: Long.MIN_VALUE, this.lan.values.maxOfOrNull { it.seenAt } ?: Long.MIN_VALUE)
            return NearbyDevice(
                key = key,
                ephemeralId = ble?.body?.ephemeralId ?: lan!!.record.ephemeralId,
                trustedDeviceId = peer?.deviceId,
                nickname = nickname,
                nicknameTruncated = truncated,
                platform = ble?.body?.platform ?: lan!!.record.platform,
                visibility = ble?.body?.visibility ?: lan!!.record.visibility,
                capabilities = ble?.body?.capabilities ?: lan!!.record.capabilities,
                networkHint = ble?.body?.networkHint ?: NetworkHint.NONE,
                ring = ble?.takeIf { it.rssi != null }?.ring ?: Ring.MIDDLE,
                stableAngleDegrees = angle,
                smoothedRssiDbm = ble?.rssi?.valueDbm,
                classicAddress = ble?.body?.classicAddress,
                radioAddress = ble?.radioAddress,
                carrier = ble?.carrier,
                lanEndpoint = lan?.let { LanEndpoint(it.instanceName, it.host, it.record.controlPort) },
                sources =
                    buildSet {
                        if (ble != null) add(DiscoverySource.BLUETOOTH)
                        if (lan != null) add(DiscoverySource.LAN)
                    },
                lastSeenMillis = lastSeen,
            )
        }
    }

    private companion object {
        const val MIN_RSSI = -127
        const val MAX_RSSI = 20
    }
}
