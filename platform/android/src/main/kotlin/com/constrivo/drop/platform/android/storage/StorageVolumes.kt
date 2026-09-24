package com.constrivo.drop.platform.android.storage

import android.content.ContentResolver
import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * The volume behind a [ReceiveDestination] (F-D4, T-23, T-27): its free space, whether it is removable (the `sdcard`
 * hint and capability bit 10), and its MediaStore volume name for `AndroidCapabilityDetector.setSaveVolume`.
 *
 * A picked folder of the system's external storage provider names its volume in its document id (`primary:…` or
 * `<UUID>:…`); for another provider the free space comes from its root's `COLUMN_AVAILABLE_BYTES`, and a volume it
 * does not name is taken as not removable. Every call is guarded: an unknown answer is "not removable" and
 * [Long.MAX_VALUE] free bytes (a full volume mid-transfer still cancels cleanly with `storage`).
 */
class StorageVolumes(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storage: StorageManager? = appContext.getSystemService(StorageManager::class.java)
    private val resolver: ContentResolver = appContext.contentResolver

    /** The volume of [destination], or null when the platform does not say. */
    fun volumeOf(destination: ReceiveDestination): StorageVolume? {
        val manager = storage ?: return null
        return try {
            when (destination) {
                is ReceiveDestination.MediaStoreVolume -> {
                    manager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == destination.volumeName }
                        ?: if (destination.volumeName == ReceiveDestination.PRIMARY_VOLUME) manager.primaryStorageVolume else null
                }

                is ReceiveDestination.DocumentTree -> {
                    val volumeId = externalStorageVolumeId(destination.treeUri) ?: return null
                    if (volumeId == PRIMARY_ID) {
                        manager.primaryStorageVolume
                    } else {
                        manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    }
                }
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    fun isRemovable(destination: ReceiveDestination): Boolean = volumeOf(destination)?.isRemovable ?: false

    /** The MediaStore volume name of [destination]'s volume, or null for the primary one (and when unknown). */
    fun mediaStoreVolumeName(destination: ReceiveDestination): String? {
        val volume = volumeOf(destination) ?: return null
        return volume.mediaStoreVolumeName?.takeIf { it != ReceiveDestination.PRIMARY_VOLUME && !volume.isPrimary }
    }

    fun freeBytes(destination: ReceiveDestination): Long {
        val directory = volumeOf(destination)?.directory
        if (directory != null) {
            try {
                return StatFs(directory.path).availableBytes
            } catch (e: IllegalArgumentException) {
                // The volume went away between the lookup and the stat (a card pulled out).
                return 0
            }
        }
        if (destination is ReceiveDestination.DocumentTree) rootAvailableBytes(destination.treeUri)?.let { return it }
        return Long.MAX_VALUE
    }

    /** `COLUMN_AVAILABLE_BYTES` of the provider root that holds [treeUri], when the provider reports it. */
    private fun rootAvailableBytes(treeUri: String): Long? =
        try {
            val tree = treeUri.toUri()
            val authority = tree.authority ?: return null
            val rootId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            val columns = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            resolver.query(DocumentsContract.buildRootsUri(authority), columns, null, null, null)?.use { c ->
                val id = c.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                val bytes = c.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                if (id < 0 || bytes < 0) return null
                while (c.moveToNext()) {
                    if (c.getString(id) == rootId && !c.isNull(bytes)) return c.getLong(bytes).takeIf { it >= 0 }
                }
                null
            }
        } catch (e: RuntimeException) {
            null
        }

    companion object {
        /** The external storage provider's authority, whose document ids start with the volume id. */
        const val EXTERNAL_STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"
        private const val PRIMARY_ID = "primary"

        /** The volume id (`primary` or a UUID) in an external-storage tree URI, or null for any other provider. */
        fun externalStorageVolumeId(treeUri: String): String? =
            try {
                val tree = treeUri.toUri()
                if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) {
                    null
                } else {
                    DocumentsContract.getTreeDocumentId(tree).substringBefore(':').takeIf { it.isNotEmpty() }
                }
            } catch (e: RuntimeException) {
                null
            }
    }
}
