package com.constrivo.drop.ui.android

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.IntentCompat
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.platform.DropHaptics
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Haptics on Android (design §4.2: light haptic on completion, `CONFIRM`; §4.4: haptic on a scanned code). Both use
 * `CONFIRM`, the system's "something succeeded" effect. [enabled] is the app's "Haptic feedback" setting (design
 * §11); the system's own touch-feedback setting applies on top.
 */
internal class AndroidHaptics(
    private val view: View,
    private val enabled: () -> Boolean,
) : DropHaptics {
    override fun confirm() = perform()

    override fun scanned() = perform()

    private fun perform() {
        if (enabled()) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
}

/** The system's "Remove animations" (animator duration scale 0) is the reduced-motion setting of design §3.3. */
internal object SystemMotion {
    fun isReduced(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
}

/**
 * Reads the URIs of a share (design §4.3) or of the document picker into [PickedItem]s through [SharedFiles]: display
 * name and size from `OpenableColumns`, type from the provider. Runs on an IO thread: providers answer over binder.
 * A URI this app may not read is left out.
 */
internal class UriReader(
    private val resolver: ContentResolver,
    private val untitled: String,
) {
    fun items(
        uris: List<Uri>,
        declaredMime: String? = null,
    ): List<PickedItem> = SharedFiles.toItems(uris.mapNotNull { facts(it, declaredMime) }, untitled)

    /** [items] for URIs given as strings (the share's [SharedFiles.grantedStreams]). */
    fun itemsOf(
        uris: List<String>,
        declaredMime: String? = null,
    ): List<PickedItem> = items(uris.map(Uri::parse), declaredMime)

    /**
     * What the provider says about [uri], or null to leave the URI out. The provider belongs to another app, which may
     * be hostile or buggy: anything it throws across binder, or a cursor with the wrong column types (a BLOB where the
     * size should be, which makes `getLong` throw `SQLiteException`), leaves this one URI out instead of crashing.
     */
    private fun facts(
        uri: Uri,
        declaredMime: String?,
    ): SharedFiles.Facts? {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme.equals(SharedFiles.CONTENT_SCHEME, ignoreCase = true)) {
            try {
                resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            } catch (_: SecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                // A provider that does not know OpenableColumns: keep the URI with what the rest says.
            } catch (_: UnsupportedOperationException) {
                // Same, for providers that do not support queries at all.
            } catch (_: RuntimeException) {
                // SQLiteException from a mistyped column, or whatever the provider threw: leave this URI out.
                return null
            }
        }
        val type =
            try {
                resolver.getType(uri)
            } catch (_: RuntimeException) {
                // SecurityException, or a provider failing: the type falls back to the sender's and the extension's.
                null
            }
        val extension = SharedFiles.cleanName(name ?: uri.lastPathSegment)?.substringAfterLast('.', "")?.lowercase()
        val byExtension = extension?.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return SharedFiles.Facts(
            uri = uri.toString(),
            scheme = uri.scheme,
            displayName = name,
            sizeBytes = size,
            mime = type,
            lastPathSegment = uri.lastPathSegment,
            fallbackMime = declaredMime?.takeIf { '*' !in it } ?: byExtension,
        )
    }

    private companion object {
        val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}

/**
 * The parts of a share intent (design §4.3): its streams (with where each came from, for [SharedFiles.grantedStreams]),
 * whether it grants read access, and, from a direct-share target, its shortcut id (F‑C3).
 */
internal class ShareIntent(
    val streams: List<SharedFiles.Stream>,
    val readGranted: Boolean,
    val declaredMime: String?,
    val shortcutId: String?,
) {
    companion object {
        fun isShare(intent: Intent): Boolean = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

        /**
         * Reads [intent], or null when it is not a share or its extras are malformed: another app built it, so a
         * wrong type or a broken parcel must not crash this one.
         */
        fun read(intent: Intent): ShareIntent? {
            if (!isShare(intent)) return null
            return try {
                val extra = LinkedHashSet<Uri>()
                if (intent.action == Intent.ACTION_SEND) {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(extra::add)
                } else {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { list ->
                        list.filterNotNull().forEach(extra::add)
                    }
                }
                val clip = LinkedHashSet<Uri>()
                intent.clipData?.let { data -> for (i in 0 until data.itemCount) data.getItemAt(i)?.uri?.let(clip::add) }
                val streams = (clip + extra).map { SharedFiles.Stream(it.toString(), it.authority, it in clip) }
                val granted = (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                ShareIntent(streams, granted, intent.type, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            } catch (_: RuntimeException) {
                // BadParcelableException, ClassCastException and friends from a malformed Intent.
                null
            }
        }
    }
}

/**
 * What a recreated [MainActivity] does with the share it handled before (design §4.3): the files must be attached
 * once, survive a rotation, and come back after the process died while they were still waiting (Android keeps the
 * activity's URI grants across process death, not across its destruction).
 */
internal object ShareRestore {
    enum class Action {
        /** Read the launch intent's share (a fresh start, or the process died with the share still waiting). */
        HANDLE,

        /** The controller still holds the share (a configuration change): the new activity owns it now. */
        ADOPT,

        /** Nothing to do: no share, or its files were already sent or cleared. */
        SKIP,
    }

    /**
     * @param restored the activity is being recreated (it has saved state).
     * @param savedShareId the share the previous instance handled, if any.
     * @param savedPending the share was still waiting (attached or being read) when the state was saved.
     * @param live the process still holds that share (attached or being read).
     */
    fun decide(
        restored: Boolean,
        savedShareId: String?,
        savedPending: Boolean,
        live: Boolean,
    ): Action =
        when {
            !restored -> Action.HANDLE
            savedShareId == null -> Action.SKIP
            live -> Action.ADOPT
            savedPending -> Action.HANDLE
            else -> Action.SKIP
        }
}

/** The avatar from the photo picker: decoded at most [EDGE_PX] on its short side and cropped to a centred square. */
internal object AvatarImages {
    const val EDGE_PX: Int = 256

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
    ): ImageBitmap? =
        try {
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val shortSide = minOf(info.size.width, info.size.height).coerceAtLeast(1)
                    val scale = EDGE_PX.toFloat() / shortSide
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            val edge = minOf(bitmap.width, bitmap.height)
            Bitmap.createBitmap(bitmap, (bitmap.width - edge) / 2, (bitmap.height - edge) / 2, edge, edge).asImageBitmap()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
