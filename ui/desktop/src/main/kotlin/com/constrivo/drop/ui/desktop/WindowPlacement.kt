package com.constrivo.drop.ui.desktop

import com.constrivo.drop.platform.desktop.OwnerOnlyFiles
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** A screen's usable area, in the window system's logical pixels (Compose's dp on the desktop). */
data class ScreenArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun intersectionWith(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val w = minOf(this.x + this.width, x + width) - maxOf(this.x, x)
        val h = minOf(this.y + this.height, y + height) - maxOf(this.y, y)
        return maxOf(w, 0) to maxOf(h, 0)
    }
}

/** Where the window was and how large (design §9: "remembers position"); a null position lets the OS centre it. */
data class WindowPlacement(
    val x: Int?,
    val y: Int?,
    val width: Int,
    val height: Int,
) {
    companion object {
        /** Design §9: 420 × 640, resizable. */
        const val DEFAULT_WIDTH: Int = 420
        const val DEFAULT_HEIGHT: Int = 640

        /** Below this the radar's bottom bar and sheets no longer fit. */
        const val MIN_WIDTH: Int = 360
        const val MIN_HEIGHT: Int = 480

        /** How much of the title bar must stay on a screen for a remembered position to be used. */
        private const val VISIBLE_WIDTH = 96
        private const val VISIBLE_HEIGHT = 32

        val DEFAULT: WindowPlacement = WindowPlacement(null, null, DEFAULT_WIDTH, DEFAULT_HEIGHT)

        /**
         * The placement to open with: [saved] when its title bar is still reachable on one of [screens] (a monitor may
         * have been unplugged since), else the OS's default position; the size is kept within the minimum and the
         * largest screen. Without a saved placement, the design's default size.
         */
        fun restore(
            saved: WindowPlacement?,
            screens: List<ScreenArea>,
        ): WindowPlacement {
            saved ?: return DEFAULT
            val maxWidth = screens.maxOfOrNull { it.width }?.coerceAtLeast(MIN_WIDTH) ?: Int.MAX_VALUE
            val maxHeight = screens.maxOfOrNull { it.height }?.coerceAtLeast(MIN_HEIGHT) ?: Int.MAX_VALUE
            val width = saved.width.coerceIn(MIN_WIDTH, maxWidth)
            val height = saved.height.coerceIn(MIN_HEIGHT, maxHeight)
            val x = saved.x
            val y = saved.y
            val onScreen =
                x != null && y != null &&
                    screens.any { screen ->
                        val (w, h) = screen.intersectionWith(x, y, width, VISIBLE_HEIGHT)
                        w >= minOf(VISIBLE_WIDTH, width) && h >= VISIBLE_HEIGHT
                    }
            return if (onScreen) WindowPlacement(x, y, width, height) else WindowPlacement(null, null, width, height)
        }
    }
}

/**
 * The window placement in `window.properties` in the config directory. Reads never throw (a missing, unreadable or
 * malformed file is "nothing saved"); writes are atomic, so a crash mid-write keeps the previous placement.
 */
class WindowPlacementStore(
    private val file: Path,
) {
    fun load(): WindowPlacement? {
        val text =
            try {
                Files.readString(file, StandardCharsets.UTF_8)
            } catch (_: IOException) {
                return null
            }
        val props = Properties()
        try {
            props.load(StringReader(text))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val width = props.getProperty(WIDTH)?.toIntOrNull() ?: return null
        val height = props.getProperty(HEIGHT)?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val x = props.getProperty(X)?.toIntOrNull()
        val y = props.getProperty(Y)?.toIntOrNull()
        return if (x != null && y != null) WindowPlacement(x, y, width, height) else WindowPlacement(null, null, width, height)
    }

    /** Saves [placement]; false when it could not be written (the next start opens at the default place). */
    fun save(placement: WindowPlacement): Boolean {
        val props = Properties()
        placement.x?.let { props.setProperty(X, it.toString()) }
        placement.y?.let { props.setProperty(Y, it.toString()) }
        props.setProperty(WIDTH, placement.width.toString())
        props.setProperty(HEIGHT, placement.height.toString())
        val text = StringWriter().also { props.store(it, null) }.toString()
        return try {
            file.parent?.let(Files::createDirectories)
            val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp", *OwnerOnlyFiles.fileAttributes())
            try {
                Files.writeString(temp, text, StandardCharsets.UTF_8)
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val X = "x"
        const val Y = "y"
        const val WIDTH = "width"
        const val HEIGHT = "height"
    }
}
