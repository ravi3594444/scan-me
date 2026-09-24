package com.constrivo.drop.platform.desktop.files

import com.constrivo.drop.core.protocol.ProtocolConstants
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
 * - The order is deterministic: the items in the order given, and inside a folder each directory's entries by name,
 *   a subfolder's files at the subfolder's place (so `a/b` sorts before `a-b`); the same files dropped twice are listed
 *   once.
 * - At most [maxFiles] files (the protocol's `MAX_FILES_PER_TRANSFER`, 100,000); the walk stops as soon as one more is
 *   found, so a home folder dropped by mistake is neither walked to its end nor held in memory, and
 *   [ExpandedItems.truncated] says so.
 * - A name longer than the protocol's [ProtocolConstants.MAX_FILE_NAME_BYTES] loses its leading folders, never the
 *   file name: the sender cuts names at their end and the receiver keeps the last component, so a deep tree (or
 *   non-Latin folder names, three UTF-8 bytes a character) must not cost the file its name and extension.
 *
 * Blocking file-system I/O: call it off the UI thread, inside `runInterruptible` so a cancelled drop stops the walk (it
 * checks the thread's interrupt flag at every entry and then throws [InterruptedException]).
 */
object SendItems {
    val maxFiles: Int = ProtocolConstants.MAX_FILES_PER_TRANSFER

    /** @throws InterruptedException when the calling thread is interrupted during the walk. */
    fun expand(
        items: List<Path>,
        limit: Int = maxFiles,
    ): ExpandedItems {
        require(limit > 0) { "limit must be positive" }
        val walk = Walk(limit)
        for (item in items) {
            if (walk.truncated) break
            walk.item(item.toAbsolutePath().normalize())
        }
        return ExpandedItems(walk.files, walk.skipped, walk.truncated)
    }

    /**
     * [segments] joined with `/`, without as many leading segments as it takes to fit [maxBytes] UTF-8 bytes; the last
     * segment (the file name) always stays whole.
     */
    fun sendName(
        segments: List<String>,
        maxBytes: Int = ProtocolConstants.MAX_FILE_NAME_BYTES,
    ): String {
        require(segments.isNotEmpty()) { "a name needs at least one segment" }
        var from = 0
        var bytes = segments.sumOf { utf8Length(it) } + segments.size - 1
        while (bytes > maxBytes && from < segments.size - 1) {
            bytes -= utf8Length(segments[from]) + 1
            from++
        }
        return segments.subList(from, segments.size).joinToString("/")
    }

    private fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes +=
                when {
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        i++
                        4
                    }

                    c.code < 0x80 -> {
                        1
                    }

                    c.code < 0x800 -> {
                        2
                    }

                    else -> {
                        3
                    }
                }
            i++
        }
        return bytes
    }

    /** One expansion: the files so far, what was skipped, and whether the limit was passed. */
    private class Walk(
        private val limit: Int,
    ) {
        val files = ArrayList<SendFile>()
        val skipped = ArrayList<Path>()
        private val seen = HashSet<Path>()
        var truncated = false
            private set

        fun item(root: Path) {
            checkInterrupted()
            val attrs = attributes(root)
            when {
                attrs == null || attrs.isSymbolicLink -> {
                    skipped.add(root)
                }

                attrs.isDirectory -> {
                    folder(root)
                }

                attrs.isRegularFile && Files.isReadable(root) -> {
                    add(root, root.fileName.toString(), attrs.size())
                }

                else -> {
                    skipped.add(root)
                }
            }
        }

        /** Depth first, each directory's entries by name, with an explicit stack (a deep tree cannot overflow the call stack). */
        private fun folder(root: Path) {
            val rootEntries = entriesOf(root) ?: return
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(rootEntries.iterator(), listOf(root.fileName?.toString()?.takeIf { it.isNotEmpty() } ?: "folder")))
            while (stack.isNotEmpty() && !truncated) {
                checkInterrupted()
                val top = stack.last()
                if (!top.entries.hasNext()) {
                    stack.removeLast()
                    continue
                }
                val entry = top.entries.next()
                val attrs = attributes(entry)
                val name = entry.fileName.toString()
                when {
                    attrs == null || attrs.isSymbolicLink -> {
                        skipped.add(entry)
                    }

                    attrs.isDirectory -> {
                        entriesOf(entry)?.let { stack.addLast(Frame(it.iterator(), top.segments + name)) }
                    }

                    attrs.isRegularFile && Files.isReadable(entry) -> {
                        add(entry, sendName(top.segments + name), attrs.size())
                    }

                    else -> {
                        skipped.add(entry)
                    }
                }
            }
        }

        private fun add(
            path: Path,
            name: String,
            size: Long,
        ) {
            if (!seen.add(path)) return
            if (files.size >= limit) {
                truncated = true
                return
            }
            files += SendFile(path, name, size)
        }

        /** The entries of [dir] sorted by name, or null (reported as skipped) when it cannot be listed. */
        private fun entriesOf(dir: Path): List<Path>? =
            try {
                Files.newDirectoryStream(dir).use { stream -> stream.sortedBy { it.fileName.toString() } }
            } catch (_: IOException) {
                skipped.add(dir)
                null
            } catch (_: DirectoryIteratorException) {
                skipped.add(dir)
                null
            } catch (_: SecurityException) {
                skipped.add(dir)
                null
            }

        private fun attributes(path: Path): BasicFileAttributes? =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("the folder walk was cancelled")
        }
    }

    private class Frame(
        val entries: Iterator<Path>,
        val segments: List<String>,
    )
}
