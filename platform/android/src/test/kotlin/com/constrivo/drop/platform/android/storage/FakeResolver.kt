package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.transfer.StorageFullException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Extension → type, as Android's `MimeTypeMap` answers for the files the tests use. */
internal val TEST_MIME: (String) -> String? = { extension ->
    when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "apk" -> "application/vnd.android.package-archive"
        "exe" -> "application/x-msdownload"
        else -> null
    }
}

/**
 * MediaStore and SAF in memory, with the rules the store relies on: pending items are invisible until published, a
 * published name is never replaced (` (1)`, ` (2)`, …), only pending items are deleted, and a volume of [capacity]
 * bytes throws [StorageFullException] when a write would pass it.
 */
internal class FakeResolver : PendingItemResolver {
    class Item(
        val uri: String,
        var displayName: String,
        var relativePath: String,
        val collection: MediaCollection?,
        val mimeType: String,
        val parentUri: String?,
    ) {
        var pending = true
        var data = ByteArray(0)
        var syncs = 0
        var openHandles = 0
    }

    val items = LinkedHashMap<String, Item>()
    val created = ArrayList<PendingItemSpec>()
    var capacity: Long = Long.MAX_VALUE
    var free: Long = Long.MAX_VALUE
    var removable = false
    var failNextCreate: IOException? = null
    private var next = 1

    val published: List<Item> get() = items.values.filter { !it.pending }

    fun item(uri: String): Item = checkNotNull(items[uri]) { "no item $uri" }

    private fun usedBytes(): Long = items.values.sumOf { it.data.size.toLong() }

    override fun create(spec: PendingItemSpec): PendingItemRef {
        failNextCreate?.let {
            failNextCreate = null
            throw it
        }
        created += spec
        val id = next++
        return when (val destination = spec.destination) {
            is ReceiveDestination.MediaStoreVolume -> {
                val uri = "content://media/${destination.volumeName}/${spec.target.collection.name.lowercase()}/$id"
                items[uri] =
                    Item(uri, spec.target.displayName, spec.target.relativePath, spec.target.collection, spec.target.mimeType, null)
                PendingItemRef(uri, PendingItemKind.MEDIA_STORE, spec.target.collection, spec.target.mimeType)
            }

            is ReceiveDestination.DocumentTree -> {
                val parent = destination.treeUri + (spec.subfolder?.let { "/$it" } ?: "")
                val uri = "$parent/document/$id"
                val temp = ".drop-${spec.transferId.take(12)}-${spec.fileIndex}.part"
                items[uri] = Item(uri, temp, "", null, spec.target.mimeType, parent)
                PendingItemRef(uri, PendingItemKind.DOCUMENT, null, spec.target.mimeType, parent)
            }
        }
    }

    override fun exists(ref: PendingItemRef): Boolean = items[ref.uri]?.pending == true

    override fun open(ref: PendingItemRef): PositionalFile {
        val item = items[ref.uri] ?: throw IOException("no such item ${ref.uri}")
        item.openHandles++
        return object : PositionalFile {
            private var closed = false

            override fun read(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (closed) throw IOException("closed")
                if (position >= item.data.size) return -1
                val n = minOf(length.toLong(), item.data.size - position).toInt()
                System.arraycopy(item.data, position.toInt(), buffer, offset, n)
                return n
            }

            override fun write(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) {
                if (closed) throw IOException("closed")
                val end = position + length
                val growth = (end - item.data.size).coerceAtLeast(0)
                if (usedBytes() + growth > capacity) throw StorageFullException("volume full")
                if (end > item.data.size) item.data = item.data.copyOf(end.toInt())
                System.arraycopy(buffer, offset, item.data, position.toInt(), length)
            }

            override fun length(): Long = item.data.size.toLong()

            override fun sync() {
                if (closed) throw IOException("closed")
                item.syncs++
            }

            override fun close() {
                if (closed) return
                closed = true
                item.openHandles--
            }
        }
    }

    override fun publish(
        ref: PendingItemRef,
        target: MediaTarget,
    ): PublishedItem {
        val item = items[ref.uri] ?: throw IOException("no such item ${ref.uri}")
        val folder = if (ref.kind == PendingItemKind.MEDIA_STORE) target.relativePath else ref.parentUri.orEmpty()
        val taken =
            items.values
                .filter {
                    it !== item && !it.pending &&
                        (if (ref.kind == PendingItemKind.MEDIA_STORE) it.relativePath else it.parentUri) == folder
                }
                .map { it.displayName }
                .toSet()
        var n = 0
        var name = target.displayName
        while (name in taken) {
            n++
            name =
                com.constrivo.drop.core.transfer.receive.FileNameSanitizer
                    .withCollisionSuffix(target.displayName, n)
        }
        item.displayName = name
        if (ref.kind == PendingItemKind.MEDIA_STORE) item.relativePath = target.relativePath
        item.pending = false
        return PublishedItem(item.uri, name)
    }

    override fun delete(ref: PendingItemRef) {
        val item = items[ref.uri] ?: return
        if (item.pending) items.remove(ref.uri)
    }

    override fun freeBytes(destination: ReceiveDestination): Long = free

    override fun isRemovable(destination: ReceiveDestination): Boolean = removable
}

/** A content provider in memory for [ContentSources]: described files, seekable or stream-only. */
internal class FakeSources : ContentSourceResolver {
    class Source(
        val facts: SourceFacts?,
        val bytes: ByteArray,
        val seekable: Boolean,
    ) {
        var streamsOpened = 0
        var descriptorsOpened = 0
    }

    val sources = HashMap<String, Source>()

    override fun describe(uri: String): SourceFacts? = sources[uri]?.facts

    override fun openRead(uri: String): PositionalFile? {
        val source = sources[uri] ?: throw IOException("no such file $uri")
        if (!source.seekable) return null
        source.descriptorsOpened++
        return object : PositionalFile {
            override fun read(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (position >= source.bytes.size) return -1
                val n = minOf(length.toLong(), source.bytes.size - position).toInt()
                System.arraycopy(source.bytes, position.toInt(), buffer, offset, n)
                return n
            }

            override fun write(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) = throw IOException("read-only")

            override fun length(): Long = source.bytes.size.toLong()

            override fun sync() = Unit

            override fun close() = Unit
        }
    }

    override fun openStream(uri: String): InputStream {
        val source = sources[uri] ?: throw IOException("no such file $uri")
        source.streamsOpened++
        return ByteArrayInputStream(source.bytes)
    }
}
