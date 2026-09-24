package com.constrivo.drop.ui.android

import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.PickedItem

/**
 * Turns what the content resolver says about shared or picked URIs into [PickedItem]s (design §4.1 Files tab, §4.3
 * share sheet), and decides which streams of a share this app may read at all ([grantedStreams]). Pure, so the rules
 * are unit-tested off-device:
 *
 * - Only `content:` URIs are accepted. A `file:` URI from another app would make this app read a path with its own
 *   rights (possibly its private files), and Android 7+ forbids sending them anyway.
 * - Duplicates (the same URI in `EXTRA_STREAM` and in the `ClipData`) count once, in first-seen order.
 * - The name is the provider's display name reduced to its last path segment, with control characters removed; a
 *   missing or blank one falls back to the URI's last segment, then to [untitled].
 * - A negative or missing size is "unknown" (null); the running total then counts it as 0.
 * - The kind comes from the provider's MIME type, then the sender's, then the file extension's.
 */
internal object SharedFiles {
    /** One URI as the resolver described it; any field can be missing. */
    data class Facts(
        val uri: String,
        val scheme: String?,
        val displayName: String?,
        val sizeBytes: Long?,
        val mime: String?,
        val lastPathSegment: String?,
        /** The MIME type the sender declared for the whole share (`Intent.getType`), or the extension's type. */
        val fallbackMime: String? = null,
    )

    const val CONTENT_SCHEME: String = "content"

    /** One stream of a share intent: its URI, its provider's authority, and whether the intent's `ClipData` has it. */
    data class Stream(
        val uri: String,
        val authority: String?,
        val inClipData: Boolean,
    )

    /**
     * The streams this app reads on the sender's behalf (the confused-deputy guard of a share, design §4.3).
     *
     * `MainActivity` is exported, so any app can hand it any `content:` URI, including ones only this app can read:
     * MediaStore rows once the picker's media permission is granted, documents under a folder this app holds a
     * persisted grant for, this app's own providers. Reading those with this app's rights and sending them would leak
     * the user's files on behalf of an app that has no access to them.
     *
     * A stream is therefore used only when the system checked that the sender may grant it: it is in the intent's
     * `ClipData` and the intent carries [readGranted] (`FLAG_GRANT_READ_URI_PERMISSION`), which makes the system verify
     * the grant when the sender starts the activity (the sheet's `EXTRA_STREAM` is copied into `ClipData` with that
     * flag automatically, so every ordinary share qualifies). A URI only in `EXTRA_STREAM`, next to a `ClipData` the
     * sender chose to leave without it, is not. Neither is a URI of one of this app's own authorities
     * ([ownAuthorities]): the system does not check a grant the receiver would not need. On Android 15+ the manifest's
     * `requireContentUriPermissionFromCaller` makes the system refuse such a launch outright.
     */
    fun grantedStreams(
        streams: List<Stream>,
        readGranted: Boolean,
        ownAuthorities: Set<String>,
    ): List<String> {
        if (!readGranted) return emptyList()
        return streams
            .filter { it.inClipData && it.authority != null && it.authority !in ownAuthorities }
            .map { it.uri }
            .distinct()
    }

    fun toItems(
        facts: List<Facts>,
        untitled: String,
    ): List<PickedItem> {
        val seen = HashSet<String>()
        val out = ArrayList<PickedItem>(facts.size)
        for (f in facts) {
            if (!f.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) continue
            if (!seen.add(f.uri)) continue
            val name = cleanName(f.displayName) ?: cleanName(f.lastPathSegment) ?: untitled
            val mime = f.mime?.takeIf { it.isSpecific() } ?: f.fallbackMime?.takeIf { it.isSpecific() }
            out += PickedItem(id = f.uri, name = name, sizeBytes = f.sizeBytes?.takeIf { it >= 0 }, kind = FileKind.fromMime(mime))
        }
        return out
    }

    /** The last path segment of [raw] without control or bidi-override characters, or null when nothing is left. */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = last.filterNot { it.isISOControl() || it in BIDI_CONTROLS }.trim()
        return clean.ifEmpty { null }
    }

    // A wildcard type (any image, anything at all) describes a whole share, not one file.
    private fun String.isSpecific(): Boolean = isNotBlank() && '*' !in this

    private val BIDI_CONTROLS = setOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069')
}
