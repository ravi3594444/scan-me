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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The file picker's Photos grid and Apps tab on Android (design §4.1; architecture §10.1: read through `MediaStore`,
 * no broad storage permission).
 *
 * The gallery is read only while the picker is open ([pickerOpen]) and media access is granted, so nothing is read
 * before the user asks to send (F‑I1) and the thumbnails are released when the sheet closes. It lists the [LIMIT]
 * most recently added photos and videos; older ones are one tap away in the Files tab (the system picker). Rows come
 * first with type glyphs, then thumbnails fill in page by page; the list reloads when the gallery changes. With
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override val media: Flow<List<PickedItem>> =
        combine(pickerOpen, mediaAccess) { open, access -> open && access }
            .distinctUntilChanged()
            .flatMapLatest { load -> if (load) recentMedia() else flowOf(emptyList()) }

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
        galleryChanges()
            .flatMapLatest { first ->
                flow {
                    // A camera save fires several changes in a row; wait for them to settle before reloading.
                    if (!first) delay(RELOAD_SETTLE_MILLIS)
                    emitAll(loadRecent())
                }
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

    private fun loadRecent(): Flow<List<PickedItem>> =
        flow {
            val rows = queryRecent()
            val items = rows.mapTo(ArrayList(rows.size)) { it.item(null) }
            emit(items.toList())
            for (page in rows.indices.chunked(THUMB_PAGE)) {
                for (i in page) thumbnail(rows[i])?.let { items[i] = rows[i].item(it) }
                emit(items.toList())
            }
        }

    private fun queryRecent(): List<Row> {
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
                putInt(ContentResolver.QUERY_ARG_LIMIT, LIMIT)
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
        }
        return rows
    }

    private fun thumbnail(row: Row): FileThumb? =
        try {
            FileThumb.Picture(resolver.loadThumbnail(row.uri, Size(THUMB_PX, THUMB_PX), null).asImageBitmap(), row.kind)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
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
        /** How many recent photos and videos the grid lists. */
        const val LIMIT: Int = 120

        /** Thumbnail edge in pixels: sharp in a grid cell, about 100 KB each. */
        const val THUMB_PX: Int = 160
        private const val THUMB_PAGE = 12
        private const val RELOAD_SETTLE_MILLIS = 500L
        private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
}
