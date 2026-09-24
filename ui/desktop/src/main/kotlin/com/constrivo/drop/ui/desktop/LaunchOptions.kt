package com.constrivo.drop.ui.desktop

import com.constrivo.drop.platform.desktop.AutoStart
import com.constrivo.drop.platform.desktop.DesktopOs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** The command line (F‑H5: the login entry starts the app with [AutoStart.MINIMIZED_FLAG], straight to the tray). */
data class LaunchOptions(
    val minimized: Boolean,
) {
    companion object {
        /** Unknown arguments are ignored (a desktop environment may pass its own, such as a file to open). */
        fun parse(args: Array<String>): LaunchOptions = LaunchOptions(minimized = AutoStart.MINIMIZED_FLAG in args)
    }
}

/** The name the onboarding suggests (design §7 "What should nearby devices call you?"): the computer's name. */
object DeviceNames {
    /** Longer names are cut: the beacon and the radar label have room for about this many characters. */
    const val MAX_LENGTH: Int = 24

    private const val FALLBACK = "Computer"

    /**
     * The computer's name from [env] (`COMPUTERNAME` on Windows, `HOSTNAME` where the shell exports it), else the
     * contents of [hostnameFile] (Linux `/etc/hostname`), without a domain part ("studio.local" → "studio"); a
     * generic name when none is set. Never blocks on DNS (`InetAddress.getLocalHost` can take seconds).
     */
    fun suggested(
        os: DesktopOs,
        env: Map<String, String> = System.getenv(),
        hostnameFile: Path? = defaultHostnameFile(os),
    ): String {
        val raw =
            env["COMPUTERNAME"]?.takeIf { os == DesktopOs.WINDOWS && it.isNotBlank() }
                ?: env["HOSTNAME"]?.takeIf { it.isNotBlank() }
                ?: hostnameFile?.let(::readFirstLine)
        return clean(raw) ?: FALLBACK
    }

    internal fun clean(raw: String?): String? {
        val name =
            raw
                ?.trim()
                ?.substringBefore('.')
                ?.filter { !it.isISOControl() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "localhost" }
                ?: return null
        return if (name.length > MAX_LENGTH) name.take(MAX_LENGTH).trimEnd() else name
    }

    private fun defaultHostnameFile(os: DesktopOs): Path? =
        if (os == DesktopOs.LINUX || os == DesktopOs.OTHER) {
            try {
                Paths.get("/etc/hostname")
            } catch (_: InvalidPathException) {
                null
            }
        } else {
            null
        }

    private fun readFirstLine(file: Path): String? =
        try {
            if (Files.isRegularFile(file) && Files.size(file) < MAX_FILE) Files.readAllLines(file).firstOrNull() else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private const val MAX_FILE = 4096L
}
