package com.constrivo.drop.platform.desktop

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Files and directories only the current user can read (architecture §13 "Data at rest"; spec change N11): POSIX mode
 * `0700` / `0600` on Linux and macOS, an ACL granting the owner alone full control on Windows (NTFS). On a file system
 * with neither (FAT on a stick), the permissions stay as the file system has them; [isRestricted] tells.
 */
object OwnerOnlyFiles {
    private val posix: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    private val FILE_MODE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    private val DIR_MODE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

    /** The attribute to create a file with owner-only access, when the file system takes POSIX modes. */
    fun fileAttributes(): Array<FileAttribute<*>> = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(FILE_MODE)) else emptyArray()

    /**
     * Creates [dir] and its missing parents; [dir] itself (whether new or existing) is then restricted to the owner.
     *
     * @throws IOException when it cannot be created.
     */
    fun createDirectories(dir: Path) {
        Files.createDirectories(dir)
        restrict(dir)
    }

    /** Restricts [path] to its owner; best effort on file systems without POSIX modes or ACLs. */
    fun restrict(path: Path) {
        try {
            if (posix) {
                val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(path, if (directory) DIR_MODE else FILE_MODE)
                return
            }
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
            val owner = acl.owner
            val entry =
                AclEntry
                    .newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(AclEntryPermission.entries.toSet())
                    .build()
            acl.acl = listOf(entry)
        } catch (_: UnsupportedOperationException) {
            // A file system without either view: nothing more can be done here.
        } catch (_: IOException) {
            // Not ours to change (a shared folder chosen by the user); the file stays as the file system made it.
        }
    }

    /** Whether [path] is readable by its owner only (POSIX: no group or other bits). True where it cannot be checked. */
    fun isRestricted(path: Path): Boolean {
        if (!posix) return true
        val mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        return mode.none {
            it in
                setOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE,
                )
        }
    }
}
