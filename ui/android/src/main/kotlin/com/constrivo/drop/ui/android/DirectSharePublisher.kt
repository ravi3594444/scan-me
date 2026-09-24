package com.constrivo.drop.ui.android

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.theme.DropColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Publishes the radar's trusted devices as direct-share targets (F‑C3, architecture §10.1: `ShortcutManagerCompat`
 * dynamic shortcuts "refreshed from the radar"). The share sheet matches them to this app's `share-target`
 * (`res/xml/shortcuts.xml`) through [SHARE_CATEGORY]; choosing one starts [MainActivity] with `ACTION_SEND` and the
 * shortcut id, which [DirectShareIds] maps back to the device.
 *
 * Changes settle for [SETTLE_MILLIS] before publishing, so the list follows the radar within the 2 s of F‑C3 without
 * rewriting shortcuts on every RSSI tick. Devices that leave are removed from the share sheet's cache too
 * (`removeLongLivedShortcuts`). When the system rate-limits shortcut updates (an app in the background), the latest
 * list is published once the limit lifts.
 */
internal class DirectSharePublisher(
    private val context: Context,
    private val ids: DirectShareIds,
) {
    // Written by one publish at a time (collectLatest joins the previous one), on IO threads.
    @Volatile
    private var published: Set<String>? = null

    /** Follows [radar] until [scope] ends. */
    fun start(
        scope: CoroutineScope,
        radar: Flow<RadarUiState>,
    ): Job {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(0, MAX_TARGETS)
        return scope.launch {
            radar
                .map { DirectShareTargets.of(it, max) }
                .distinctUntilChanged()
                .collectLatest { targets ->
                    // collectLatest cancels this wait when the radar changes again: a debounce.
                    delay(SETTLE_MILLIS)
                    while (ShortcutManagerCompat.isRateLimitingActive(context)) delay(RATE_LIMIT_RETRY_MILLIS)
                    withContext(Dispatchers.IO) { publish(targets) }
                }
        }
    }

    private fun publish(targets: List<ShareTarget>) {
        try {
            val before = published ?: existingIds()
            val shortcuts = targets.map(::shortcut)
            val now = shortcuts.mapTo(HashSet()) { it.id }
            val gone = before - now
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone.toList())
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            published = now
            ids.trim(targets.map { it.key })
        } catch (_: IllegalStateException) {
            // The user is locked (direct boot); the next radar change publishes again.
        } catch (_: IllegalArgumentException) {
            // More shortcuts than the launcher allows; MAX_TARGETS keeps below the usual limit.
        }
    }

    /** Tells the system a target was used, so the share sheet ranks it higher next time. */
    fun reportUsed(id: String) {
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, id)
        } catch (_: IllegalStateException) {
            // The user is locked; ranking is best effort.
        }
    }

    /** Shortcuts left from an earlier run, including those only the share sheet still caches. */
    private fun existingIds(): Set<String> =
        ShortcutManagerCompat
            .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
            .mapTo(HashSet()) { it.id }

    private fun shortcut(target: ShareTarget): ShortcutInfoCompat {
        val id = ids.idFor(target.key)
        return ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(target.name)
            .setLongLabel(target.name)
            .setIcon(AvatarIcon.create(context, target))
            .setIntent(openIntent(context, id))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .setPerson(Person.Builder().setName(target.name).setKey(id).build())
            .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
            .build()
    }

    companion object {
        /** Must match the `<category>` of the share target in `res/xml/shortcuts.xml`. */
        const val SHARE_CATEGORY: String = "com.constrivo.drop.category.DIRECT_SHARE_TARGET"

        /** A launcher that still shows the shortcut (Android 12, where surfaces cannot be excluded) opens the device. */
        const val ACTION_OPEN_DEVICE: String = "com.constrivo.drop.action.OPEN_DEVICE"

        /** The shortcut id in an [ACTION_OPEN_DEVICE] intent. */
        const val EXTRA_TARGET_ID: String = "com.constrivo.drop.extra.TARGET_ID"

        /** The share sheet shows four or five targets per app; more would only be trimmed. */
        const val MAX_TARGETS: Int = 8
        const val SETTLE_MILLIS: Long = 500
        private const val RATE_LIMIT_RETRY_MILLIS = 30_000L

        fun openIntent(
            context: Context,
            id: String,
        ): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_DEVICE).putExtra(EXTRA_TARGET_ID, id)
    }
}

/** The adaptive icon of a direct-share target: the device's avatar colour (design §12) with white initials. */
internal object AvatarIcon {
    /** Adaptive icons are 108 dp with the visible part in the middle 72 dp. */
    private const val ADAPTIVE_DP = 108f
    private const val TEXT_SCALE = 0.26f

    fun create(
        context: Context,
        target: ShareTarget,
    ): IconCompat {
        val size = (ADAPTIVE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(DropColors.Light.avatar(target.avatarHash).toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val c = size / 2f
        val initials = target.initials
        if (initials != null) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = size * TEXT_SCALE
            canvas.drawText(initials, c, c - (paint.descent() + paint.ascent()) / 2f, paint)
        } else {
            // A person glyph: head and shoulders, inside the 72 dp safe zone.
            canvas.drawCircle(c, c - size * 0.07f, size * 0.09f, paint)
            canvas.drawArc(RectF(c - size * 0.17f, c + size * 0.06f, c + size * 0.17f, c + size * 0.36f), 180f, 180f, true, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
