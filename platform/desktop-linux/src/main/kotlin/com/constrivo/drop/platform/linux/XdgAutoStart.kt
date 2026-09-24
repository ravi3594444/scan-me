package com.constrivo.drop.platform.linux

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.platform.desktop.AutoStart
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Start on login for Linux desktops (F‑H5, P1, opt-in), as the XDG Autostart specification describes: a desktop entry
 * `com.constrivo.drop.desktop` in `$XDG_CONFIG_HOME/autostart` (`~/.config/autostart`) that runs [launcher] with
 * [AutoStart.MINIMIZED_FLAG], so the app starts in the tray and the phone finds the sleeping laptop. GNOME, KDE, Xfce
 * and the other XDG desktops read it; turning it off deletes the entry. Writes are atomic.
 *
 * Only a packaged app (jpackage sets `jpackage.app-path`) has a stable launcher; otherwise [isAvailable] is false and
 * nothing is written.
 */
class XdgAutoStart(
    val autostartDirectory: Path,
    private val launcher: List<String>?,
) : AutoStart {
    /** The desktop entry this app owns. */
    val entry: Path = autostartDirectory.resolve("${AppIdentity.PACKAGE}.desktop")

    override val isAvailable: Boolean = launcher != null && launcher.isNotEmpty()

    /** Whether the entry exists and is not disabled by `Hidden=true` (a user may hide it in their session settings). */
    override fun isEnabled(): Boolean {
        if (!Files.isRegularFile(entry)) return false
        val lines =
            try {
                Files.readAllLines(entry, StandardCharsets.UTF_8)
            } catch (_: IOException) {
                return false
            }
        return lines.none { it.trim().equals("Hidden=true", ignoreCase = true) }
    }

    /** @throws IOException when the entry cannot be written or removed; nothing happens when unavailable. */
    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            Files.deleteIfExists(entry)
            return
        }
        val command = launcher?.takeIf { it.isNotEmpty() } ?: return
        Files.createDirectories(autostartDirectory)
        val temp = autostartDirectory.resolve(".${entry.fileName}.tmp")
        Files.write(temp, desktopEntry(command).toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(temp, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, entry, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** The autostart directory of the user whose home is [home], honouring an absolute `$XDG_CONFIG_HOME`. */
        fun forUser(
            env: Map<String, String>,
            home: Path,
            launcher: List<String>?,
        ): XdgAutoStart {
            val config =
                env["XDG_CONFIG_HOME"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        try {
                            Paths.get(it)
                        } catch (_: InvalidPathException) {
                            null
                        }
                    }?.takeIf { it.isAbsolute }
                    ?: home.resolve(".config")
            return XdgAutoStart(config.resolve("autostart"), launcher)
        }

        /** The packaged launcher (jpackage's `jpackage.app-path`), or null when this run is not packaged. */
        fun packagedLauncher(): List<String>? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { listOf(it) }

        /**
         * The desktop entry for [command] (Desktop Entry Specification 1.5): `Exec` quotes every argument that needs it
         * and escapes `%` as `%%`, so a launcher path with spaces or `$` starts exactly that program.
         *
         * @throws IllegalArgumentException for an empty command or an argument with a line break or a NUL.
         */
        fun desktopEntry(command: List<String>): String {
            require(command.isNotEmpty()) { "the command must not be empty" }
            val exec = (command + AutoStart.MINIMIZED_FLAG).joinToString(" ") { execArgument(it) }
            return buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=").append(AppIdentity.DISPLAY_NAME).append('\n')
                append("Comment=Start ").append(AppIdentity.DISPLAY_NAME).append(" in the tray so nearby devices can find this computer\n")
                append("Exec=").append(exec).append('\n')
                append("Terminal=false\n")
                append("X-GNOME-Autostart-enabled=true\n")
            }
        }

        /** One `Exec` argument: quoted when it holds a reserved character, then string-escaped. */
        fun execArgument(argument: String): String {
            require(argument.none { it == '\n' || it == '\r' || it == '\u0000' }) { "an Exec argument cannot hold a line break" }
            val quoted =
                if (argument.isEmpty() || argument.any { it in RESERVED }) {
                    buildString {
                        append('"')
                        for (c in argument) {
                            if (c in ESCAPED_IN_QUOTES) append('\\')
                            append(c)
                        }
                        append('"')
                    }
                } else {
                    argument
                }
            // The string-value escape applies on top of the quoting rule; field codes start with '%'.
            return quoted.replace("\\", "\\\\").replace("%", "%%")
        }

        private const val RESERVED = " \t\"'\\><~|&;$*?#()`="
        private const val ESCAPED_IN_QUOTES = "\"`$\\"
    }
}
