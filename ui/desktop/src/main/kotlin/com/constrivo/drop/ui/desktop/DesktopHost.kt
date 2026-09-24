package com.constrivo.drop.ui.desktop

import com.constrivo.drop.platform.desktop.DesktopOs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * What the shell asks of the operating system's desktop: opening a received file with its default application,
 * showing a folder, and the file dialogs (design §9, F‑C1 "Browse files", Settings → Save location). An interface so
 * the ports and the shell are tested without a display.
 */
interface DesktopHost {
    /** Opens [path] with the application the OS associates with it; false when nothing could open it. */
    fun open(path: Path): Boolean

    /** Shows [path] in the file manager: the folder itself, or the folder of a file with the file selected where the OS can. */
    fun reveal(path: Path): Boolean

    /** The files and folders the user chose to send, or an empty list when they cancelled. */
    suspend fun chooseFiles(
        title: String,
        button: String,
    ): List<Path>

    /** The folder the user chose, starting at [start], or null when they cancelled. */
    suspend fun chooseFolder(
        title: String,
        button: String,
        start: Path?,
    ): Path?
}

/**
 * [DesktopHost] over `java.awt.Desktop` and Swing's file chooser. Where AWT has no desktop integration (some Linux
 * window managers), opening falls back to `xdg-open`. Every call is defensive: a failure returns false or "cancelled"
 * and never throws into the UI.
 *
 * Dialogs run on the Swing event thread ([Dispatchers.Swing], which is also Compose's main thread on the desktop), so
 * they are modal to the window as users expect.
 */
class AwtDesktopHost(
    private val os: DesktopOs = DesktopOs.current,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val launch: (List<String>) -> Boolean = ::startProcess,
) : DesktopHost {
    private val desktop: Desktop? =
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) Desktop.getDesktop() else null

    override fun open(path: Path): Boolean {
        if (!Files.exists(path)) return false
        val file = path.toFile()
        return tryDesktop(Desktop.Action.OPEN) { it.open(file) } || fallbackOpen(path)
    }

    override fun reveal(path: Path): Boolean {
        if (!Files.exists(path)) return false
        if (!Files.isDirectory(path)) {
            // macOS and Windows select the file in its folder; elsewhere the folder opens.
            if (tryDesktop(Desktop.Action.BROWSE_FILE_DIR) { it.browseFileDirectory(path.toFile()) }) return true
            if (os == DesktopOs.WINDOWS && launch(listOf("explorer.exe", "/select,", path.toString()))) return true
            return path.parent?.let(::openFolder) ?: false
        }
        return openFolder(path)
    }

    private fun openFolder(folder: Path): Boolean = tryDesktop(Desktop.Action.OPEN) { it.open(folder.toFile()) } || fallbackOpen(folder)

    private fun fallbackOpen(path: Path): Boolean =
        when (os) {
            DesktopOs.LINUX, DesktopOs.OTHER -> launch(listOf("xdg-open", path.toString()))
            DesktopOs.MAC -> launch(listOf("open", path.toString()))
            DesktopOs.WINDOWS -> launch(listOf("explorer.exe", path.toString()))
        }

    private inline fun tryDesktop(
        action: Desktop.Action,
        block: (Desktop) -> Unit,
    ): Boolean {
        val d = desktop ?: return false
        if (!d.isSupported(action)) return false
        return try {
            block(d)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override suspend fun chooseFiles(
        title: String,
        button: String,
    ): List<Path> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return withContext(Dispatchers.Swing) {
            val chooser = JFileChooser()
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            chooser.isMultiSelectionEnabled = true
            chooser.approveButtonText = button
            if (chooser.showDialog(null, button) != JFileChooser.APPROVE_OPTION) return@withContext emptyList()
            chooser.selectedFiles
                .toList()
                .ifEmpty { listOfNotNull(chooser.selectedFile) }
                .map(File::toPath)
        }
    }

    override suspend fun chooseFolder(
        title: String,
        button: String,
        start: Path?,
    ): Path? {
        if (GraphicsEnvironment.isHeadless()) return null
        val startDir = start?.let { withContext(io) { it.takeIf(Files::isDirectory) } }
        return withContext(Dispatchers.Swing) {
            val chooser = JFileChooser(startDir?.toFile())
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isMultiSelectionEnabled = false
            chooser.approveButtonText = button
            if (chooser.showDialog(null, button) != JFileChooser.APPROVE_OPTION) return@withContext null
            chooser.selectedFile?.toPath()
        }
    }

    private companion object {
        /** Starts [command] detached (its output discarded); false when it could not be started. */
        fun startProcess(command: List<String>): Boolean =
            try {
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}
