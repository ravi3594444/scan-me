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
            }
        }
        val type =
            try {
                resolver.getType(uri)
            } catch (_: SecurityException) {
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

/** The parts of a share intent (design §4.3): the streams and, from a direct-share target, its shortcut id (F‑C3). */
internal class ShareIntent(
    val streams: List<Uri>,
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
                val streams = LinkedHashSet<Uri>()
                if (intent.action == Intent.ACTION_SEND) {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::add)
                } else {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { list ->
                        list.filterNotNull().forEach(streams::add)
                    }
                }
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(streams::add) }
                ShareIntent(streams.toList(), intent.type, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            } catch (_: RuntimeException) {
                // BadParcelableException, ClassCastException and friends from a malformed Intent.
                null
            }
        }
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
