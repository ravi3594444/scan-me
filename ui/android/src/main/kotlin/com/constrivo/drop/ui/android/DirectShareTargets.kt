package com.constrivo.drop.ui.android

import com.constrivo.drop.ui.shared.model.RadarUiState
import java.security.SecureRandom

/** A trusted nearby device offered as a direct-share target in the Android share sheet (F‑C3). */
internal data class ShareTarget(
    /** The radar key (what `DropAppController.onShareIntent` takes as the direct target). */
    val key: String,
    val name: String,
    val initials: String?,
    val avatarHash: Int,
)

/**
 * Which radar devices become direct-share targets (F‑C3, design §4.3: "Nearby trusted devices also appear as direct
 * share targets"): trusted bubbles with a name, closest ring first, keeping the radar's order within a ring, at most
 * [max]. Busy devices stay, so the share sheet's list does not flicker with every transfer; a send to a busy device is
 * the engine's to queue (F‑C5).
 */
internal object DirectShareTargets {
    fun of(
        state: RadarUiState,
        max: Int,
    ): List<ShareTarget> =
        state.bubbles
            .asSequence()
            .filter { it.trusted && !it.name.isNullOrBlank() }
            .sortedBy { it.ring.ordinal }
            .take(max.coerceAtLeast(0))
            .map { ShareTarget(it.key, it.name!!.trim(), it.initials, it.avatarHash) }
            .toList()
}

/**
 * Shortcut ids of direct-share targets. The share sheet hands the id back in the share intent (`EXTRA_SHORTCUT_ID`),
 * and a direct share sends without another tap once the device is on the radar (F‑C3). This activity is exported, so
 * any app could start it with an id of its choosing: ids are therefore random (128 bits from [SecureRandom]) rather
 * than derived from the device key, and an unknown id is only a plain share. Ids are kept in [store], so targets the
 * share sheet has cached still resolve after a restart; at most [MAX_ENTRIES] devices are remembered.
 */
internal class DirectShareIds(
    private val store: KeyValueStore,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The id of the device with radar key [key], created on first use. */
    fun idFor(key: String): String {
        store.getString(BY_KEY + key)?.let { return it }
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        val id = ID_PREFIX + bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        store.putString(BY_KEY + key, id)
        store.putString(BY_ID + id, key)
        return id
    }

    /** The radar key behind [id], or null for an id this app did not hand out (or no longer remembers). */
    fun keyFor(id: String?): String? {
        if (id == null || !ID_FORMAT.matches(id)) return null
        return store.getString(BY_ID + id)
    }

    /**
     * Forgets remembered devices beyond [MAX_ENTRIES], keeping [current] (the targets being published). Called after
     * each publish.
     */
    fun trim(current: Collection<String>) {
        val keys = store.keys(BY_KEY).map { it.removePrefix(BY_KEY) }
        if (keys.size <= MAX_ENTRIES) return
        val keep = current.toSet()
        for (key in keys.filterNot { it in keep }.take(keys.size - MAX_ENTRIES)) {
            store.getString(BY_KEY + key)?.let { store.putString(BY_ID + it, null) }
            store.putString(BY_KEY + key, null)
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 64
        private const val ID_BYTES = 16
        private const val ID_PREFIX = "ds_"
        private val ID_FORMAT = Regex("ds_[0-9a-f]{32}")
        private const val BY_KEY = "share.key."
        private const val BY_ID = "share.id."
    }
}
