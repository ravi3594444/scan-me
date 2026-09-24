package com.constrivo.drop.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import com.constrivo.drop.ui.shared.theme.DropTheme
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM screenshot tests on the desktop target (testing §5 "UI" row): each case renders a composable with
 * [ImageComposeScene] at a fixed size and density, in the bundled DejaVu Sans (so images do not depend on the
 * machine's fonts), and compares it with `src/desktopTest/resources/screenshots/<name>.png`.
 *
 * A pixel differs when any channel differs by more than [CHANNEL_TOLERANCE]; a case passes while at most
 * [PIXEL_TOLERANCE] of the pixels differ (anti-aliasing noise between Skia builds). On failure the actual image and a
 * diff (differing pixels in magenta) are written to `build/reports/screenshots/`.
 *
 * Re-record after an intended visual change with
 * `./gradlew :ui:shared:desktopTest -Pdrop.recordScreenshots=true`, look at the new PNGs, and commit them.
 */
object Screenshots {
    const val WIDTH_DP: Int = 360
    const val HEIGHT_DP: Int = 760
    private const val DENSITY = 1f
    private const val CHANNEL_TOLERANCE = 24
    private const val PIXEL_TOLERANCE = 0.003
    private const val FRAME_NANOS = 16_000_000L

    /** Frames rendered before the capture: long enough for every entry animation (the longest is the tray spring). */
    private const val FRAMES = 60

    private val record = System.getProperty("drop.recordScreenshots") == "true"

    /** Tests run with the module directory as the working directory. */
    private val references = File("src/desktopTest/resources/screenshots")
    private val reports = File("build/reports/screenshots")

    val testFont: FontFamily by lazy {
        fun load(name: String): ByteArray =
            requireNotNull(Screenshots::class.java.getResourceAsStream("/fonts/$name")) { "missing test font $name" }.readBytes()
        FontFamily(
            Font("DejaVuSans", load("DejaVuSans.ttf"), FontWeight.Normal),
            Font("DejaVuSans-Bold", load("DejaVuSans-Bold.ttf"), FontWeight.Bold),
        )
    }

    /** Renders [content] in the app theme and compares it with the reference [name]. */
    fun check(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        reducedMotion: Boolean = false,
        widthDp: Int = WIDTH_DP,
        heightDp: Int = HEIGHT_DP,
        content: @Composable () -> Unit,
    ) {
        Locale.setDefault(Locale.US)
        val png =
            render(widthDp, heightDp, fontScale) {
                DropTheme(dark = dark, fontFamily = testFont, reducedMotion = reducedMotion, content = content)
            }
        val reference = File(references, "$name.png")
        if (record) {
            references.mkdirs()
            reference.writeBytes(png)
            return
        }
        if (!reference.exists()) {
            write("$name.actual.png", png)
            fail("no reference screenshot $name.png; record it with -Pdrop.recordScreenshots=true (actual in $reports)")
        }
        val expected = ImageIO.read(reference)
        val actual = ImageIO.read(ByteArrayInputStream(png))
        if (expected.width != actual.width || expected.height != actual.height) {
            write("$name.actual.png", png)
            fail("$name: size ${actual.width}x${actual.height}, reference ${expected.width}x${expected.height}")
        }
        val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        var differing = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = actual.getRGB(x, y)
                val e = expected.getRGB(x, y)
                if (differs(a, e)) {
                    differing++
                    diff.setRGB(x, y, 0xFFFF00FF.toInt())
                } else {
                    diff.setRGB(x, y, (e and 0x00FFFFFF) or 0x40000000)
                }
            }
        }
        val share = differing.toDouble() / (actual.width * actual.height)
        if (share > PIXEL_TOLERANCE) {
            write("$name.actual.png", png)
            reports.mkdirs()
            ImageIO.write(diff, "png", File(reports, "$name.diff.png"))
        }
        assertTrue(share <= PIXEL_TOLERANCE, "$name: ${"%.3f".format(share * 100)}% of pixels differ (see $reports)")
    }

    /** Renders [content] to PNG bytes after [FRAMES] frames. */
    fun render(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene =
            ImageComposeScene(
                width = (widthDp * DENSITY).toInt(),
                height = (heightDp * DENSITY).toInt(),
                density = Density(DENSITY, fontScale),
                content = content,
            )
        try {
            var image = scene.render(0)
            for (frame in 1..FRAMES) image = scene.render(frame * FRAME_NANOS)
            return requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes
        } finally {
            scene.close()
        }
    }

    private fun differs(
        a: Int,
        b: Int,
    ): Boolean {
        for (shift in intArrayOf(0, 8, 16, 24)) {
            if (abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) > CHANNEL_TOLERANCE) return true
        }
        return false
    }

    private fun write(
        file: String,
        bytes: ByteArray,
    ) {
        reports.mkdirs()
        File(reports, file).writeBytes(bytes)
    }
}
