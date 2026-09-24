package com.constrivo.drop.ui.android

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.presenter.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * The file picker's Photos grid and Apps tab on Android (design §4.1; architecture §10.1: read through `MediaStore`,
 * no broad storage permission).
 *
 * The gallery is read only while the picker is open ([pickerOpen]) and media access is granted, so nothing is read
 * before the user asks to send (F‑I1) and the thumbnails are released when the sheet closes. It lists the most
 * recently added photos and videos, [PAGE] at a time: when the grid scrolls near its end the picker asks for more
 * ([requestMore]) and the next page is appended, up to [MAX_ITEMS] (older ones are in the Files tab, the system
 * picker). Rows come first with type glyphs, then thumbnails fill in; a thumbnail is decoded once per open sheet. The
 * list reloads when the gallery changes. Selected items stay selected in the picker whatever this list shows. With
 * Android 14's partial access the query returns only the photos the user selected, which is what the grid shows.
 *
 * Item ids are `content:` URIs of the MediaStore rows; an app's id is the path of its APK (world-readable, one file:
 * apps split into several APKs are left out, since they cannot be sent as one).
 */
internal class MediaStoreLibrary(
    private val context: Context,
    private val permissions: AndroidPermissions,
    private val pickerOpen: Flow<Boolean>,
) : MediaLibrary {
    private val resolver: ContentResolver get() = context.contentResolver

    override val mediaAccess: Flow<Boolean> =
        permissions.changes.map { permissions.status(DropPermission.MEDIA).usable }.distinctUntilChanged()

    /** How many pages the open picker wants; back to one each time it opens. */
    private val pages = MutableStateFlow(1)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val media: Flow<List<PickedItem>> =
        combine(pickerOpen, mediaAccess) { open, access -> open && access }
            .distinctUntilChanged()
            .flatMapLatest { load ->
                pages.value = 1
                if (load) recentMedia() else flowOf(emptyList())
            }

    override fun requestMore() {
        pages.update { if (it * PAGE < MAX_ITEMS) it + 1 else it }
    }

    override val apps: Flow<List<PickedItem>> = flow { emit(installedApps()) }.flowOn(Dispatchers.IO)

    private class Row(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long?,
        val kind: FileKind,
    ) {
        fun item(thumb: FileThumb?) = PickedItem(id = uri.toString(), name = name, sizeBytes = sizeBytes, kind = kind, thumb = thumb)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun recentMedia(): Flow<List<PickedItem>> =
        flow {
            // Decoded thumbnails, kept while the sheet is open so a new page or a reload does not decode them again.
            val thumbs = HashMap<Uri, FileThumb>()
            emitAll(
                combine(galleryChanges(), pages) { first, n -> first to n }
                    .flatMapLatest { (first, n) ->
                        flow {
                            // A camera save fires several changes in a row; wait for them to settle before reloading.
                            if (!first) delay(RELOAD_SETTLE_MILLIS)
                            emitAll(loadRecent(n * PAGE, thumbs))
                        }
                    },
            )
        }.flowOn(Dispatchers.IO)

    /** Emits true once at start, then false on every change to external media. */
    private fun galleryChanges(): Flow<Boolean> =
        callbackFlow {
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(false)
                    }
                }
            resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
            trySend(true)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }

    private fun loadRecent(
        limit: Int,
        thumbs: MutableMap<Uri, FileThumb>,
    ): Flow<List<PickedItem>> =
        flow {
            val rows = queryRecent(limit)
            val items = rows.mapTo(ArrayList(rows.size)) { it.item(thumbs[it.uri]) }
            emit(items.toList())
            val missing = rows.indices.filter { rows[it].uri !in thumbs }
            for (page in missing.chunked(THUMB_PAGE)) {
                for (i in page) {
                    thumbnail(rows[i])?.let {
                        thumbs[rows[i].uri] = it
                        items[i] = rows[i].item(it)
                    }
                }
                emit(items.toList())
            }
        }

    private fun queryRecent(limit: Int): List<Row> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection =
            arrayOf(
                BaseColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
            )
        val args =
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    ),
                )
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
        val rows = ArrayList<Row>()
        try {
            resolver.query(collection, projection, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(BaseColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext()) {
                    val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) VIDEOS else IMAGES
                    val uri = ContentUris.withAppendedId(base, c.getLong(id))
                    val display = SharedFiles.cleanName(if (c.isNull(name)) null else c.getString(name)) ?: uri.lastPathSegment.orEmpty()
                    val bytes = if (c.isNull(size)) null else c.getLong(size).takeIf { it >= 0 }
                    val kind = FileKind.fromMime(if (c.isNull(mime)) null else c.getString(mime)).takeIf { it != FileKind.OTHER }
                    rows += Row(uri, display, bytes, kind ?: if (video) FileKind.VIDEO else FileKind.IMAGE)
                }
            }
        } catch (_: SecurityException) {
            // Access was revoked between the check and the query: an empty grid, and mediaAccess catches up.
        } catch (_: IllegalArgumentException) {
            // A provider without one of the columns (never on stock MediaStore): nothing to show.
        } catch (_: RuntimeException) {
            // The media provider failed (it runs in another process): an empty grid rather than a crash.
        }
        return rows
    }

    private fun thumbnail(row: Row): FileThumb? =
        try {
            FileThumb.Picture(resolver.loadThumbnail(row.uri, Size(THUMB_PX, THUMB_PX), null).asImageBitmap(), row.kind)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // SecurityException, or a decoder failing on a broken file: the tile keeps its glyph.
            null
        }

    private fun installedApps(): List<PickedItem> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, 0)
            }
        return activities
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName && it.splitSourceDirs.isNullOrEmpty() && it.sourceDir != null }
            .distinctBy { it.packageName }
            .map { app -> appItem(pm, app) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun appItem(
        pm: PackageManager,
        app: ApplicationInfo,
    ): PickedItem {
        val apk = java.io.File(app.sourceDir)
        val icon = pm.getApplicationIcon(app).toBitmap(THUMB_PX, THUMB_PX).asImageBitmap()
        return PickedItem(
            id = apk.path,
            name = pm.getApplicationLabel(app).toString(),
            sizeBytes = apk.length().takeIf { it > 0 },
            kind = FileKind.APP,
            thumb = FileThumb.Picture(icon, FileKind.APP),
        )
    }

    companion object {
        /** How many photos and videos the grid lists at first, and adds per [requestMore]. */
        const val PAGE: Int = 120

        /** The grid's limit; older media are in the Files tab. */
        const val MAX_ITEMS: Int = 5_000

        /** Thumbnail edge in pixels: sharp in a grid cell, about 100 KB each. */
        const val THUMB_PX: Int = 160
        private const val THUMB_PAGE = 12
        private const val RELOAD_SETTLE_MILLIS = 500L
        private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
}
