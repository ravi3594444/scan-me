package com.constrivo.drop.platform.desktop.files

import com.constrivo.drop.core.protocol.ProtocolConstants
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * One file to send (F‑C6): where it is on disk and the name it is sent under. For a file inside a dropped folder the
 * name is its path from the folder, the folder's own name first, with `/` separators (`Holiday/day 1/IMG_0001.jpg`),
 * as `SourceFile.name` expects for folder sends; the receiver keeps only the last component (F‑D5).
 */
data class SendFile(
    val path: Path,
    val name: String,
    val size: Long,
) {
    init {
        require(name.isNotEmpty()) { "a file to send needs a name" }
        require(size >= 0) { "size must not be negative" }
    }
}

/** What [SendItems.expand] found: the files in send order, and what it skipped. */
data class ExpandedItems(
    val files: List<SendFile>,
    /** Symbolic links, special files and unreadable entries that were left out. */
    val skipped: List<Path>,
    /** More than [SendItems.maxFiles] files were found; [files] holds the first ones. */
    val truncated: Boolean,
)

/**
 * Turns what the user dropped or picked into the send list (F‑C6: "a folder of 1,000 files sends with bundling";
 * design §9 drop zone and Ctrl/Cmd+O):
 *
 * - A regular file is sent under its own name.
 * - A folder is walked recursively; its regular files are sent under their path from the folder, the folder's name
 *   first. Empty folders send nothing. Hidden files are included (the user dropped the folder as it is).
 * - Symbolic links are not followed (a link loop or a link out of the folder could otherwise send far more than was
 *   dropped) and, like sockets, devices and unreadable entries, are reported in [ExpandedItems.skipped].
 * - The order is deterministic: the items in the order given, each folder's files by path, compared by name segment
 *   so `a/b` sorts before `a-b`; the same files dropped twice are listed once.
 * - At most [maxFiles] files (the protocol's `MAX_FILES_PER_TRANSFER`); [ExpandedItems.truncated] says when more were
 *   found.
 *
 * Blocking file-system I/O: call it off the UI thread.
 */
object SendItems {
    val maxFiles: Int = ProtocolConstants.MAX_FILES_PER_TRANSFER

    fun expand(
        items: List<Path>,
        limit: Int = maxFiles,
    ): ExpandedItems {
        require(limit > 0) { "limit must be positive" }
        val files = ArrayList<SendFile>()
        val skipped = ArrayList<Path>()
        val seen = HashSet<Path>()
        var truncated = false
        for (item in items) {
            if (truncated) break
            val root = item.toAbsolutePath().normalize()
            when {
                Files.isSymbolicLink(root) -> {
                    skipped.add(root)
                }

                Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) -> {
                    val prefix = root.fileName?.toString()?.takeIf { it.isNotEmpty() } ?: "folder"
                    val found = ArrayList<Pair<List<String>, SendFile>>()
                    walk(root, prefix, found, skipped)
                    found.sortWith { a, b -> compareSegments(a.first, b.first) }
                    for ((_, file) in found) {
                        if (!seen.add(file.path)) continue
                        if (files.size >= limit) {
                            truncated = true
                            break
                        }
                        files += file
                    }
                }

                Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(root) -> {
                    if (!seen.add(root)) continue
                    if (files.size >= limit) {
                        truncated = true
                        break
                    }
                    val size =
                        try {
                            Files.size(root)
                        } catch (_: IOException) {
                            skipped.add(root)
                            continue
                        }
                    files += SendFile(root, root.fileName.toString(), size)
                }

                else -> {
                    skipped.add(root)
                }
            }
        }
        return ExpandedItems(files, skipped, truncated)
    }

    private fun walk(
        root: Path,
        prefix: String,
        out: MutableList<Pair<List<String>, SendFile>>,
        skipped: MutableList<Path>,
    ) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile || !Files.isReadable(file)) {
                        skipped.add(file)
                        return FileVisitResult.CONTINUE
                    }
                    val segments = listOf(prefix) + root.relativize(file).map { it.toString() }
                    out += segments to SendFile(file, segments.joinToString("/"), attrs.size())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    skipped.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root && attrs.isSymbolicLink) {
                        skipped.add(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun compareSegments(
        a: List<String>,
        b: List<String>,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}
