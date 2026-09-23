package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider

/**
 * The nearby-device state machine behind [NearbyDevices]: synchronous, single-threaded and driven by explicit
 * [ClockReading]s, so it can be tested (and previewed) without coroutines.
 *
 * Rules:
 * - Beacons are decoded ([BeaconBody.decode]); malformed or future-layout packets are counted and dropped.
 * - Ephemeral IDs are resolved with [EphemeralIdResolver] against the [TrustState] (S3), at the wall-clock time of the
 *   sighting. Our own beacon and record heard back are dropped, also for [NearbyConfig.ownSecretRetentionMillis] after
 *   our own `k_adv` rotated. A Trusted-only beacon or TXT record that no trusted peer explains is dropped (F‑A5), and
 *   a stranger that switches to Trusted-only leaves the radar at once; a HIDDEN device never advertises at all.
 * - A trusted peer is keyed by its device ID, anyone else by the first ephemeral ID it was seen with, so Bluetooth and
 *   mDNS sightings of the same device merge whenever their IDs match (for trusted peers even across epochs).
 * - Nothing heard is authenticated, so no packet overwrites what an earlier one said about how to reach a device:
 *   every Bluetooth address and every mDNS claim is kept as a candidate ([NearbyDevice.radioAddresses],
 *   [NearbyDevice.lanEndpoints]) for the handshake's identity check to settle. A stranger's claims from two different
 *   hosts contradict each other and are all withheld while they last. A LAN nickname never replaces a different
 *   Bluetooth name.
 * - Strangers across a rotation (every 15 minutes, N4): when a stranger's new ID first appears within
 *   [NearbyConfig.epochLinkWindowMillis] of an epoch boundary (wall clock), and exactly one stranger bubble of the
 *   previous epoch shares its Bluetooth address, Classic address or LAN host, the new ID joins that bubble: same key,
 *   angle, ring and smoother state, and the old ID is retired. Advertisers restart their advertising set at the
 *   boundary, which usually changes the Bluetooth address too; failing a shared address, a unique match on everything
 *   the beacon says in clear (nickname, platform, capabilities, visibility, network hint) links it. A Bluetooth link
 *   is undone if, within the window, a second new ID with the old bubble's facts appears (a look-alike, or the real
 *   device after a relay got in first) or the old ID is heard again: the bubbles split, and neither keeps the old
 *   key's state by mistake. Links live in memory only and use nothing the device does not broadcast anyway. A session
 *   that learns the next ID over an authenticated channel keeps the bubble with [link], which is final.
 * - RSSI goes through [RssiSmoothing] and [RingThresholds]; readings outside −127…20 dBm (Android reports 127 for
 *   "unknown") count as presence but not as signal.
 * - Bluetooth presence ends [NearbyConfig.beaconTimeoutMillis] after the last beacon (design §3.3); mDNS presence
 *   ends with a "lost" event or after [NearbyConfig.lanRecordMaxAgeMillis]. A device disappears when both have ended.
 *   Every duration is measured on the monotonic clock, so wall-clock steps only move the epoch arithmetic.
 *
 * Every mutating call returns whether the visible list changed, ignoring last-seen times alone, so the caller
 * republishes for visible changes only, not on every packet. [advanceTo] does work only when a deadline is due, and
 * then only for the entries it concerns. Not thread-safe: confine an instance to one coroutine or thread.
 */
class NearbyDeviceTracker(
    private val crypto: CryptoProvider,
    val config: NearbyConfig = NearbyConfig(),
) {
    private var trust = TrustState()
    private val retiredOwn = ArrayList<RetiredSecret>()
    private var resolver = EphemeralIdResolver(crypto, emptyList())
    private val entries = HashMap<String, Entry>()

    /** Stranger ephemeral IDs (values) → the key of the entry they are filed under; several IDs after a link. */
    private val aliases = HashMap<Long, String>()

    /** mDNS instance name → the key of the entry holding it. */
    private val lanOwner = HashMap<String, String>()

    /** A lower bound of every entry's next deadline; [Long.MAX_VALUE] when nothing is pending. */
    private var deadlineBound = Long.MAX_VALUE

    /** Until when (monotonic) an automatic link may still be undone; lets new IDs skip the contest check. */
    private var pendingLinksUntil = Long.MIN_VALUE

    /** Views of the entries touched by the current call, before it changed them. */
    private val touched = LinkedHashMap<String, NearbyDevice?>()

    /** Drop counters since construction (or the last [clear]). */
    var counters: DiscoveryCounters = DiscoveryCounters()
        private set

    /** Number of devices currently on the radar. */
    val size: Int get() = entries.values.count { it.isVisible() }

    /**
     * Replaces the trust inputs (pairing, "Forget", a re-shared or rotated `k_adv`) and re-files every current
     * sighting under the new resolution, each resolved at the time it was seen. An update equal to the current one
     * changes nothing and costs no cryptography. Own secrets that left the list stay recognised for
     * [NearbyConfig.ownSecretRetentionMillis]. Should a device ID appear twice, its first entry wins, so a faulty trust
     * store cannot stop the radar.
     */
    fun setTrust(
        trust: TrustState,
        at: ClockReading,
    ): Boolean {
        if (trust.sameAs(this.trust)) return false
        for (secret in this.trust.ownSecrets) {
            val stillOwn = trust.ownSecrets.any { it.contentEquals(secret) }
            if (!stillOwn && retiredOwn.none { it.secret.contentEquals(secret) }) {
                retiredOwn += RetiredSecret(secret, at.elapsedMillis + config.ownSecretRetentionMillis)
            }
        }
        retiredOwn.removeAll { r -> r.untilElapsed <= at.elapsedMillis || trust.ownSecrets.any { it.contentEquals(r.secret) } }
        this.trust = trust
        rebuildResolver()
        return refile()
    }

    /** Applies one Bluetooth sighting heard at [at]. */
    fun onSighting(
        sighting: BeaconSighting,
        at: ClockReading,
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
        val id = body.ephemeralId
        val entry: Entry =
            when (val resolution = resolve(id, at.unixMillis)) {
                EphemeralIdResolution.Own -> {
                    counters = counters.copy(ownEchoes = counters.ownEchoes + 1)
                    return false
                }

                is EphemeralIdResolution.Trusted -> {
                    trustedEntry(resolution.peer)
                }

                EphemeralIdResolution.Unknown -> {
                    val known = aliases[id.value]?.let { entries[it] }
                    if (body.visibility == Visibility.TRUSTED_ONLY) {
                        counters = counters.copy(trustedOnlyStrangers = counters.trustedOnlyStrangers + 1)
                        known?.let { hideBluetooth(it, id, at.elapsedMillis) }
                        return commit()
                    }
                    when {
                        known == null -> {
                            val name = sighting.localName?.let { Nicknames.normalize(it)?.text }
                            undoContestedLinks(Fingerprint(name, body), id, at.elapsedMillis)
                            val filed =
                                linkByBeacon(body, sighting.radioAddress, name, at)
                                    ?: newStranger(id)
                                    ?: run {
                                        counters = counters.copy(flooded = counters.flooded + 1)
                                        return commit()
                                    }
                            addAlias(filed, id)
                            filed
                        }

                        id.value in known.retired && !undoIfOldIdReturns(known, id, sighting.radioAddress, at.elapsedMillis) -> {
                            counters = counters.copy(retiredIdBeacons = counters.retiredIdBeacons + 1)
                            return commit()
                        }

                        else -> {
                            known
                        }
                    }
                }
            }
        applyBluetooth(entry, body, sighting, at)
        return commit()
    }

    /** Applies one mDNS event received at [at]. */
    fun onLanEvent(
        event: LanEvent,
        at: ClockReading,
    ): Boolean {
        when (event) {
            is LanEvent.Lost -> removeLan(event.instanceName, at.elapsedMillis)
            is LanEvent.Found -> onLanFound(event.service, at)
        }
        return commit()
    }

    /**
     * Keeps the bubble [key] for the device's next rotating ID [nextId], learned over an authenticated session
     * (WP4): when [nextId] is heard, over Bluetooth or mDNS, it updates this bubble instead of creating a new one, and
     * the ID it had before is retired. If [nextId] already has a bubble of its own, the two are merged into [key].
     * A trusted peer's key never changes, so linking one is a no-op.
     *
     * @return false when [key] is not on the radar (nothing is recorded then).
     */
    fun link(
        key: String,
        nextId: EphemeralId,
    ): Boolean {
        val entry = entries[key] ?: return false
        if (entry.peer != null) return true
        entry.provisional = null
        val otherKey = aliases[nextId.value]
        if (otherKey != null && otherKey != key) entries[otherKey]?.let { absorb(entry, it) }
        entry.retired.remove(nextId.value)
        addAlias(entry, nextId)
        commit()
        return true
    }

    /** Closes due RSSI windows and expires stale presence at [elapsedMillis] (monotonic). Cheap when nothing is due. */
    fun advanceTo(elapsedMillis: Long): Boolean {
        if (retiredOwn.removeAll { it.untilElapsed <= elapsedMillis }) rebuildResolver()
        if (elapsedMillis < deadlineBound) return false
        var next = Long.MAX_VALUE
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val due = entry.deadline(config)
            if (due > elapsedMillis) {
                next = minOf(next, due)
                continue
            }
            touch(entry)
            entry.ble?.let { ble ->
                if (elapsedMillis - ble.lastSeen >= config.beaconTimeoutMillis) {
                    entry.ble = null
                } else if (ble.rssi != null) {
                    val advanced = ble.rssi.advanceTo(elapsedMillis)
                    if (advanced !== ble.rssi) {
                        entry.ble = ble.copy(rssi = advanced, ring = config.rings.classify(advanced.valueDbm, ble.ring))
                    }
                }
            }
            entry.radios.removeAll { elapsedMillis - it.lastSeen >= config.beaconTimeoutMillis }
            val lanIterator = entry.lan.values.iterator()
            while (lanIterator.hasNext()) {
                val part = lanIterator.next()
                if (elapsedMillis - part.seenAt >= config.lanRecordMaxAgeMillis) {
                    lanIterator.remove()
                    lanOwner.remove(part.instanceName)
                }
            }
            entry.ghostUntil?.let { if (elapsedMillis >= it) entry.ghostUntil = null }
            if (!entry.hasParts() && entry.ghostUntil == null) {
                iterator.remove()
                forgetAliases(entry)
            } else {
                next = minOf(next, entry.deadline(config))
            }
        }
        deadlineBound = next
        return commit()
    }

    /**
     * The earliest time (monotonic) at which [advanceTo] may change something, or null when nothing is pending. It can
     * be early, never late: an early call does nothing but recompute it.
     */
    fun nextDeadlineMillis(): Long? = deadlineBound.takeIf { it != Long.MAX_VALUE }

    /** The current devices: nearest ring first, then trusted before others, then by key. */
    fun snapshot(): List<NearbyDevice> =
        entries.values
            .mapNotNull { it.view() }
            .sortedWith(compareBy<NearbyDevice> { it.ring.ordinal }.thenBy { !it.trusted }.thenBy { it.key })

    /** Forgets every sighting and resets the counters; the trust inputs are kept. */
    fun clear() {
        entries.clear()
        aliases.clear()
        lanOwner.clear()
        touched.clear()
        deadlineBound = Long.MAX_VALUE
        pendingLinksUntil = Long.MIN_VALUE
        counters = DiscoveryCounters()
    }

    private fun rebuildResolver() {
        resolver =
            EphemeralIdResolver(
                crypto,
                trust.peers.distinctBy { it.deviceId },
                trust.ownSecrets + retiredOwn.map { it.secret },
            )
    }

    private fun resolve(
        id: EphemeralId,
        unixMillis: Long,
    ): EphemeralIdResolution = resolver.resolve(id, unixMillis.coerceAtLeast(0))

    /** Files every part again under the current resolver. */
    private fun refile(): Boolean {
        val before = snapshot()
        val old = entries.values.sortedBy { it.key }
        entries.clear()
        aliases.clear()
        lanOwner.clear()
        touched.clear()
        for (entry in old) {
            entry.ble?.let { part ->
                refileTarget(entry, part.body.ephemeralId, part.body.visibility, part.lastSeenUnix)
                    ?.mergeBluetooth(part, entry.radios, config.maxRadioAddresses)
            }
            for (part in entry.lan.values.sortedBy { it.instanceName }) {
                val target = refileTarget(entry, part.record.ephemeralId, part.record.visibility, part.seenUnix) ?: continue
                target.lan[part.instanceName] = part
                lanOwner[part.instanceName] = target.key
            }
        }
        deadlineBound = entries.values.minOfOrNull { it.deadline(config) } ?: Long.MAX_VALUE
        return snapshot() != before
    }

    /** Where a part of [from] belongs under the current resolver, or null when it must be dropped. */
    private fun refileTarget(
        from: Entry,
        id: EphemeralId,
        visibility: Visibility,
        seenUnix: Long,
    ): Entry? =
        when (val resolution = resolve(id, seenUnix)) {
            EphemeralIdResolution.Own -> {
                null
            }

            is EphemeralIdResolution.Trusted -> {
                trustedEntry(resolution.peer)
            }

            EphemeralIdResolution.Unknown -> {
                if (visibility == Visibility.TRUSTED_ONLY) {
                    null
                } else if (from.peer == null) {
                    // A stranger stays a stranger: same key, same links (including IDs linked but not heard yet).
                    val target =
                        entries[from.key] ?: Entry(from.key, null).also { created ->
                            entries[from.key] = created
                            created.inheritLinks(from)
                            for (other in from.ephs) addAlias(created, EphemeralId(other))
                        }
                    target.also { addAlias(it, id) }
                } else {
                    // A forgotten peer: its parts become strangers again, one per rotating ID.
                    (aliases[id.value]?.let { entries[it] } ?: newStranger(id, force = true)!!).also { addAlias(it, id) }
                }
            }
        }

    private fun onLanFound(
        service: LanService,
        at: ClockReading,
    ) {
        val record =
            try {
                MdnsRecord.fromTxt(service.txt)
            } catch (e: DiscoveryFormatException) {
                counters = counters.copy(malformedLanRecords = counters.malformedLanRecords + 1)
                removeLan(service.instanceName, at.elapsedMillis)
                return
            }
        val previous = lanOwner[service.instanceName]?.let { entries[it]?.lan?.get(service.instanceName) }
        // A re-announcement may change the TXT record, so the instance is filed afresh.
        removeLan(service.instanceName, at.elapsedMillis)
        val id = record.ephemeralId
        val entry: Entry =
            when (val resolution = resolve(id, at.unixMillis)) {
                EphemeralIdResolution.Own -> {
                    counters = counters.copy(ownEchoes = counters.ownEchoes + 1)
                    return
                }

                is EphemeralIdResolution.Trusted -> {
                    trustedEntry(resolution.peer)
                }

                EphemeralIdResolution.Unknown -> {
                    if (record.visibility == Visibility.TRUSTED_ONLY) {
                        counters = counters.copy(trustedOnlyStrangers = counters.trustedOnlyStrangers + 1)
                        return
                    }
                    val filed =
                        aliases[id.value]?.let { entries[it] }
                            ?: linkByHost(id, service.host, at)
                            ?: newStranger(id)
                            ?: run {
                                counters = counters.copy(flooded = counters.flooded + 1)
                                return
                            }
                    addAlias(filed, id)
                    filed
                }
            }
        if (entry.lan.size >= config.maxLanClaims) {
            counters = counters.copy(refusedLanClaims = counters.refusedLanClaims + 1)
            dropIfEmpty(entry, at.elapsedMillis)
            return
        }
        if (entry.peer == null && entry.lan.values.any { it.host != service.host }) {
            counters = counters.copy(contestedLanClaims = counters.contestedLanClaims + 1)
        }
        touch(entry)
        val firstSeenUnix = previous?.takeIf { it.record.ephemeralId == id }?.firstSeenUnix ?: at.unixMillis
        entry.lan[service.instanceName] =
            LanPart(service.instanceName, record, service.host, at.elapsedMillis, at.unixMillis, firstSeenUnix)
        lanOwner[service.instanceName] = entry.key
        entry.ghostUntil = null
        lowerDeadline(entry)
    }

    private fun removeLan(
        instanceName: String,
        nowElapsed: Long,
    ) {
        val key = lanOwner.remove(instanceName) ?: return
        val entry = entries[key] ?: return
        touch(entry)
        val part = entry.lan.remove(instanceName) ?: return
        if (entry.peer == null) entry.rememberHost(part, nowElapsed, config.epochLinkWindowMillis)
        dropIfEmpty(entry, nowElapsed)
    }

    /**
     * Removes [entry] once nothing is left in it. A stranger whose last mDNS record was just withdrawn is kept
     * invisibly for [NearbyConfig.epochLinkWindowMillis], so the record it registers for its next ID can rejoin it.
     */
    private fun dropIfEmpty(
        entry: Entry,
        nowElapsed: Long,
    ) {
        if (entry.hasParts()) return
        touch(entry)
        if (entry.peer == null && config.epochLinkWindowMillis > 0 && entry.removedHosts.isNotEmpty()) {
            entry.ghostUntil = nowElapsed + config.epochLinkWindowMillis
            lowerDeadline(entry)
        } else {
            entries.remove(entry.key)
            forgetAliases(entry)
        }
    }

    private fun applyBluetooth(
        entry: Entry,
        body: BeaconBody,
        sighting: BeaconSighting,
        at: ClockReading,
    ) {
        touch(entry)
        val previous = entry.ble
        val sameId = previous != null && previous.body.ephemeralId == body.ephemeralId
        if (previous != null && !sameId && entry.peer == null) {
            // A linked stranger moved on to its next ID: the old one is retired.
            entry.retired += previous.body.ephemeralId.value
        }
        val rssi = sighting.rssiDbm.takeIf { it in MIN_RSSI..MAX_RSSI }
        val smoothed =
            when {
                rssi == null -> previous?.rssi
                previous?.rssi != null -> previous.rssi.add(rssi, at.elapsedMillis)
                else -> config.smoothing.start(rssi, at.elapsedMillis)
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
                lastSeen = at.elapsedMillis,
                lastSeenUnix = at.unixMillis,
                idFirstSeenUnix = if (sameId) previous.idFirstSeenUnix else at.unixMillis,
            )
        sighting.radioAddress?.let { entry.noteRadio(it, at.elapsedMillis, config.maxRadioAddresses) }
        entry.ghostUntil = null
        lowerDeadline(entry)
    }

    /** A stranger switched to Trusted-only (F‑A5): its Bluetooth presence ends now. */
    private fun hideBluetooth(
        entry: Entry,
        id: EphemeralId,
        nowElapsed: Long,
    ) {
        if (entry.ble?.body?.ephemeralId != id) return
        touch(entry)
        entry.ble = null
        entry.radios.clear()
        dropIfEmpty(entry, nowElapsed)
    }

    /**
     * Finds the stranger bubble a new rotating ID continues (see the class rules), or null. A shared address wins
     * over the clear-text facts; either way the link stays open to being undone for the window.
     */
    private fun linkByBeacon(
        body: BeaconBody,
        radioAddress: String?,
        name: String?,
        at: ClockReading,
    ): Entry? {
        val boundary = nearBoundary(at.unixMillis) ?: return null
        val fingerprint = Fingerprint(name, body)
        val strong = ArrayList<Entry>(1)
        val weak = ArrayList<Entry>(1)
        for (entry in entries.values) {
            if (entry.peer != null) continue
            val ble = entry.ble ?: continue
            if (ble.body.ephemeralId == body.ephemeralId || ble.idFirstSeenUnix >= boundary - config.epochLinkWindowMillis) continue
            if (at.elapsedMillis - ble.lastSeen > config.epochLinkWindowMillis) continue
            val sharedAddress =
                (radioAddress != null && entry.radios.any { it.address == radioAddress }) ||
                    (body.classicAddress != null && body.classicAddress == ble.body.classicAddress)
            when {
                sharedAddress -> strong += entry
                fingerprint.isUsable && fingerprint == Fingerprint(ble.localName, ble.body) -> weak += entry
            }
        }
        val match = (if (strong.isNotEmpty()) strong.singleOrNull() else weak.singleOrNull()) ?: return null
        val old = match.ble!!
        match.provisional =
            Provisional(body.ephemeralId.value, old.body.ephemeralId.value, Fingerprint(old.localName, old.body), at.elapsedMillis)
        pendingLinksUntil = maxOf(pendingLinksUntil, at.elapsedMillis + config.epochLinkWindowMillis)
        return match
    }

    /** Finds the stranger bubble whose previous-epoch mDNS record came from [host], for a record of a new ID. */
    private fun linkByHost(
        id: EphemeralId,
        host: String,
        at: ClockReading,
    ): Entry? {
        val boundary = nearBoundary(at.unixMillis) ?: return null
        val oldBefore = boundary - config.epochLinkWindowMillis
        val window = config.epochLinkWindowMillis

        fun liveClaim(entry: Entry) =
            entry.lan.values.any {
                it.host == host && it.record.ephemeralId != id && it.firstSeenUnix < oldBefore
            }

        fun withdrawnClaim(entry: Entry): Boolean {
            val removed = entry.removedHosts[host] ?: return false
            return at.elapsedMillis - removed.atElapsed <= window && removed.firstSeenUnix < oldBefore
        }
        return entries.values.filter { it.peer == null && (liveClaim(it) || withdrawnClaim(it)) }.singleOrNull()
    }

    /**
     * A second new ID with the facts the old bubble had means the link was a guess between look-alikes, or a relay
     * got its own ID in first: undo it.
     */
    private fun undoContestedLinks(
        fingerprint: Fingerprint,
        id: EphemeralId,
        nowElapsed: Long,
    ) {
        if (!fingerprint.isUsable || nowElapsed > pendingLinksUntil) return
        val contested =
            entries.values.filter { entry ->
                val p = entry.provisional
                p != null && nowElapsed - p.linkedAt <= config.epochLinkWindowMillis && p.fingerprint == fingerprint && p.newId != id.value
            }
        for (entry in contested) {
            undoLink(entry)
            dropIfEmpty(entry, nowElapsed)
        }
    }

    /**
     * The old ID of a pending link is heard again, after a short grace for late packets of the old advertising set, from
     * an address the bubble already had before the link: the old device is still there, so the two IDs belong to
     * different devices; the link is undone and the caller files the beacon normally. The old ID from any other address
     * is a replay and is ignored, so a relay cannot win the old bubble back.
     */
    private fun undoIfOldIdReturns(
        entry: Entry,
        id: EphemeralId,
        radioAddress: String?,
        nowElapsed: Long,
    ): Boolean {
        val p = entry.provisional ?: return false
        if (p.oldId != id.value) return false
        if (entry.radios.none { it.address == radioAddress && it.firstSeen < p.linkedAt }) return false
        val sinceLink = nowElapsed - p.linkedAt
        if (sinceLink <= PROVISIONAL_GRACE_MILLIS || sinceLink > config.epochLinkWindowMillis) return false
        undoLink(entry)
        return true
    }

    /** Splits the new ID of [entry]'s pending link off into a bubble of its own. */
    private fun undoLink(entry: Entry) {
        val p = entry.provisional ?: return
        entry.provisional = null
        touch(entry)
        counters = counters.copy(undoneLinks = counters.undoneLinks + 1)
        entry.retired.remove(p.oldId)
        val ble = entry.ble
        if (ble == null || ble.body.ephemeralId.value != p.newId) return
        val split = newStranger(EphemeralId(p.newId), force = true)!!
        touch(split)
        split.ble = ble
        val moved = entry.radios.filter { it.firstSeen >= p.linkedAt }
        entry.radios.removeAll(moved)
        split.radios += moved
        entry.ble = null
        entry.ephs.remove(p.newId)
        addAlias(split, EphemeralId(p.newId))
        lowerDeadline(split)
    }

    /** Merges [from] into [into] (an explicit [link] to an ID that already had its own bubble). */
    private fun absorb(
        into: Entry,
        from: Entry,
    ) {
        touch(into)
        touch(from)
        from.ble?.let { theirs ->
            val ours = into.ble
            if (ours == null || ours.lastSeen <= theirs.lastSeen) {
                if (ours != null && ours.body.ephemeralId != theirs.body.ephemeralId) into.retired += ours.body.ephemeralId.value
                into.ble = theirs
            } else {
                into.retired += theirs.body.ephemeralId.value
            }
        }
        for (radio in from.radios) into.mergeRadio(radio, config.maxRadioAddresses)
        for ((instance, part) in from.lan) {
            if (into.lan.size < config.maxLanClaims) {
                into.lan[instance] = part
                lanOwner[instance] = into.key
            } else {
                lanOwner.remove(instance)
            }
        }
        into.retired += from.retired
        for (id in from.ephs) {
            aliases[id] = into.key
            into.ephs += id
        }
        entries.remove(from.key)
        into.ghostUntil = null
        lowerDeadline(into)
    }

    private fun trustedEntry(peer: TrustedPeer): Entry {
        val key = "t:${peer.deviceId}"
        return entries.getOrPut(key) { Entry(key, peer) }
    }

    /** A new stranger entry for [id], or null when the flooding guard is full (unless [force]d). */
    private fun newStranger(
        id: EphemeralId,
        force: Boolean = false,
    ): Entry? {
        if (!force && entries.size >= config.maxDevices) return null
        var key = "e:${id.toHex()}"
        var n = 2
        while (key in entries) key = "e:${id.toHex()}#${n++}"
        return Entry(key, null).also { entries[key] = it }
    }

    private fun addAlias(
        entry: Entry,
        id: EphemeralId,
    ) {
        if (entry.peer != null) return
        aliases[id.value] = entry.key
        entry.ephs += id.value
    }

    private fun forgetAliases(entry: Entry) {
        for (id in entry.ephs) if (aliases[id] == entry.key) aliases.remove(id)
    }

    private fun lowerDeadline(entry: Entry) {
        deadlineBound = minOf(deadlineBound, entry.deadline(config))
    }

    /** The epoch boundary within [NearbyConfig.epochLinkWindowMillis] of [unixMillis], or null. */
    private fun nearBoundary(unixMillis: Long): Long? {
        val offset = unixMillis.mod(EphemeralIds.EPOCH_MILLIS)
        return when {
            offset <= config.epochLinkWindowMillis -> unixMillis - offset
            EphemeralIds.EPOCH_MILLIS - offset <= config.epochLinkWindowMillis -> unixMillis - offset + EphemeralIds.EPOCH_MILLIS
            else -> null
        }
    }

    private fun touch(entry: Entry) {
        if (entry.key !in touched) touched[entry.key] = entry.view()
    }

    /** Whether any touched entry changed visibly; resets the record. */
    private fun commit(): Boolean {
        var changed = false
        for ((key, before) in touched) {
            if (changed(before, entries[key]?.view())) {
                changed = true
                break
            }
        }
        touched.clear()
        return changed
    }

    /** A visible difference: anything but last-seen times. */
    private fun changed(
        before: NearbyDevice?,
        after: NearbyDevice?,
    ): Boolean {
        if (before == null || after == null) return before != after
        if (before.radioAddresses.size != after.radioAddresses.size) return true
        for (i in before.radioAddresses.indices) {
            if (before.radioAddresses[i].address != after.radioAddresses[i].address) return true
        }
        return before.copy(lastSeenElapsedMillis = after.lastSeenElapsedMillis, radioAddresses = after.radioAddresses) != after
    }

    private class RetiredSecret(
        val secret: ByteArray,
        val untilElapsed: Long,
    )

    private data class BlePart(
        val body: BeaconBody,
        val carrier: BeaconCarrier,
        val rssi: SmoothedRssi?,
        val ring: Ring,
        val localName: String?,
        val localNameTruncated: Boolean,
        val lastSeen: Long,
        val lastSeenUnix: Long,
        /** When this entry first heard [body]'s rotating ID (wall clock), to tell previous-epoch IDs from new ones. */
        val idFirstSeenUnix: Long,
    )

    private class LanPart(
        val instanceName: String,
        val record: MdnsRecord,
        val host: String,
        val seenAt: Long,
        val seenUnix: Long,
        val firstSeenUnix: Long,
    )

    private class RadioCandidate(
        val address: String,
        val firstSeen: Long,
        var lastSeen: Long,
    )

    private class RemovedHost(
        val atElapsed: Long,
        val firstSeenUnix: Long,
    )

    /** What a beacon says in clear about a device, for linking a stranger's rotation when no address is shared. */
    private data class Fingerprint(
        val nickname: String?,
        val platform: DevicePlatform,
        val capabilities: Capabilities,
        val visibility: Visibility,
        val networkHint: NetworkHint,
    ) {
        constructor(nickname: String?, body: BeaconBody) :
            this(nickname, body.platform, body.capabilities, body.visibility, body.networkHint)

        /** Without a nickname the facts are too generic to tell two phones apart. */
        val isUsable: Boolean get() = nickname != null
    }

    /**
     * An automatic stranger link made at [linkedAt], still open to being undone; [fingerprint] is what the bubble said
     * in clear before the link.
     */
    private class Provisional(
        val newId: Long,
        val oldId: Long,
        val fingerprint: Fingerprint,
        val linkedAt: Long,
    )

    private class Entry(
        val key: String,
        val peer: TrustedPeer?,
    ) {
        private val angle = RadarPlacement.stableAngleDegrees(key)
        private val storedNickname = peer?.nickname?.let { Nicknames.normalize(it)?.text }
        var ble: BlePart? = null

        /** Candidate Bluetooth addresses in the order they were first heard. */
        val radios = ArrayList<RadioCandidate>(1)
        val lan = LinkedHashMap<String, LanPart>()

        /** Stranger IDs filed under this entry (its aliases). */
        val ephs = HashSet<Long>()

        /** Stranger IDs this entry moved on from: their beacons are ignored. */
        val retired = HashSet<Long>()
        var provisional: Provisional? = null

        /** Hosts of recently withdrawn mDNS records, for linking the record of the next ID. */
        val removedHosts = HashMap<String, RemovedHost>()

        /** Set while an emptied stranger entry is kept invisibly for linking. */
        var ghostUntil: Long? = null

        fun hasParts(): Boolean = ble != null || lan.isNotEmpty()

        fun isVisible(): Boolean = ble != null || (lan.isNotEmpty() && (peer != null || !lanContested()))

        /** A stranger's claims from more than one host contradict each other. */
        private fun lanContested(): Boolean {
            val first = lan.values.firstOrNull()?.host ?: return false
            return lan.values.any { it.host != first }
        }

        private fun visibleLan(): List<LanPart> =
            if (lan.isEmpty() || (peer == null && lanContested())) {
                emptyList()
            } else {
                lan.values.sortedWith(compareByDescending<LanPart> { it.seenAt }.thenBy { it.instanceName })
            }

        fun deadline(config: NearbyConfig): Long {
            var d = Long.MAX_VALUE
            ble?.let { ble ->
                d = minOf(d, ble.lastSeen + config.beaconTimeoutMillis)
                ble.rssi?.openWindowEndMillis?.let { d = minOf(d, it) }
            }
            for (radio in radios) d = minOf(d, radio.lastSeen + config.beaconTimeoutMillis)
            for (part in lan.values) d = minOf(d, part.seenAt + config.lanRecordMaxAgeMillis)
            ghostUntil?.let { d = minOf(d, it) }
            return d
        }

        fun noteRadio(
            address: String,
            nowElapsed: Long,
            max: Int,
        ) {
            val known = radios.firstOrNull { it.address == address }
            if (known != null) {
                known.lastSeen = nowElapsed
                return
            }
            if (radios.size >= max) radios.remove(radios.minBy { it.lastSeen })
            radios += RadioCandidate(address, nowElapsed, nowElapsed)
        }

        fun mergeRadio(
            radio: RadioCandidate,
            max: Int,
        ) {
            val known = radios.firstOrNull { it.address == radio.address }
            if (known != null) {
                known.lastSeen = maxOf(known.lastSeen, radio.lastSeen)
                return
            }
            if (radios.size >= max) {
                val oldest = radios.minBy { it.lastSeen }
                if (oldest.lastSeen >= radio.lastSeen) return
                radios.remove(oldest)
            }
            val at = radios.indexOfFirst { it.firstSeen > radio.firstSeen }.let { if (it < 0) radios.size else it }
            radios.add(at, RadioCandidate(radio.address, radio.firstSeen, radio.lastSeen))
        }

        /** Keeps the newest Bluetooth part and every address (re-filing). */
        fun mergeBluetooth(
            part: BlePart,
            addresses: List<RadioCandidate>,
            max: Int,
        ) {
            val ours = ble
            if (ours == null || ours.lastSeen < part.lastSeen) ble = part
            for (radio in addresses) mergeRadio(radio, max)
        }

        fun rememberHost(
            part: LanPart,
            nowElapsed: Long,
            window: Long,
        ) {
            removedHosts.entries.removeAll { nowElapsed - it.value.atElapsed > window }
            if (removedHosts.size >= MAX_REMOVED_HOSTS) removedHosts.remove(removedHosts.entries.minBy { it.value.atElapsed }.key)
            removedHosts[part.host] = RemovedHost(nowElapsed, part.firstSeenUnix)
        }

        fun inheritLinks(from: Entry) {
            retired += from.retired
            provisional = from.provisional
            removedHosts.putAll(from.removedHosts)
        }

        /**
         * The name to show, and whether it is shortened: the stored name of a trusted peer; otherwise the Bluetooth
         * name, unless the LAN name is the same name in full (or there is no Bluetooth name). A LAN name that
         * disagrees never replaces the Bluetooth one.
         */
        private fun nickname(
            ble: BlePart?,
            lanName: String?,
        ): Pair<String?, Boolean> {
            if (storedNickname != null) return storedNickname to false
            if (ble == null || ble.localName == null) return lanName to false
            val bleName: String = ble.localName
            return when {
                lanName == null -> bleName to ble.localNameTruncated
                lanName == bleName || (ble.localNameTruncated && lanName.startsWith(bleName)) -> lanName to false
                else -> bleName to ble.localNameTruncated
            }
        }

        fun view(): NearbyDevice? {
            val ble = ble
            val claims = visibleLan()
            if (ble == null && claims.isEmpty()) return null
            val newest = claims.firstOrNull()
            val (nickname, truncated) = nickname(ble, newest?.record?.nickname)
            return NearbyDevice(
                key = key,
                ephemeralId = ble?.body?.ephemeralId ?: newest!!.record.ephemeralId,
                trustedDeviceId = peer?.deviceId,
                nickname = nickname,
                nicknameTruncated = truncated,
                platform = ble?.body?.platform ?: newest!!.record.platform,
                visibility = ble?.body?.visibility ?: newest!!.record.visibility,
                capabilities = ble?.body?.capabilities ?: newest!!.record.capabilities,
                networkHint = ble?.body?.networkHint ?: NetworkHint.NONE,
                ring = ble?.takeIf { it.rssi != null }?.ring ?: Ring.MIDDLE,
                stableAngleDegrees = angle,
                smoothedRssiDbm = ble?.rssi?.valueDbm,
                classicAddress = ble?.body?.classicAddress,
                radioAddresses = if (ble == null) emptyList() else radios.map { RadioAddress(it.address, it.lastSeen) },
                carrier = ble?.carrier,
                lanEndpoints = claims.map { LanEndpoint(it.instanceName, it.host, it.record.controlPort) },
                sources =
                    buildSet {
                        if (ble != null) add(DiscoverySource.BLUETOOTH)
                        if (claims.isNotEmpty()) add(DiscoverySource.LAN)
                    },
                lastSeenElapsedMillis = maxOf(ble?.lastSeen ?: Long.MIN_VALUE, claims.maxOfOrNull { it.seenAt } ?: Long.MIN_VALUE),
            )
        }
    }

    private companion object {
        const val MIN_RSSI = -127
        const val MAX_RSSI = 20

        /** Late packets of a stranger's old advertising set may still arrive this long after its next ID is linked. */
        const val PROVISIONAL_GRACE_MILLIS = 1_000L
        const val MAX_REMOVED_HOSTS = 4
    }
}
