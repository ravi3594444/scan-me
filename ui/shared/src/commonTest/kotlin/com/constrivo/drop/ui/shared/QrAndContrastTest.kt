package com.constrivo.drop.ui.shared

import androidx.compose.ui.graphics.Color
import com.constrivo.drop.ui.shared.qr.QrEncodeException
import com.constrivo.drop.ui.shared.qr.QrMatrix
import com.constrivo.drop.ui.shared.theme.DropColors
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrMatrixTest {
    /** Renders [matrix] at [scale] pixels per module with the quiet zone and decodes it with ZXing's reader. */
    private fun decode(
        matrix: QrMatrix,
        scale: Int = 4,
    ): String {
        val side = (matrix.size + 2 * QrMatrix.QUIET_ZONE) * scale
        val pixels = IntArray(side * side) { -1 }
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = (x + QrMatrix.QUIET_ZONE) * scale + dx
                        val py = (y + QrMatrix.QUIET_ZONE) * scale + dy
                        pixels[py * side + px] = 0xFF000000.toInt()
                    }
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun fB5_theSignedPayloadRoundTripsThroughAReader() {
        val payload = "drop1." + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5".repeat(3)
        val matrix = QrMatrix.encode(payload)
        assertEquals(payload, decode(matrix))
        assertTrue(matrix.size >= 21 && (matrix.size - 17) % 4 == 0, "a valid QR version size")
        assertEquals(matrix, QrMatrix.encode(payload))
    }

    @Test
    fun n15_theBrowserAddressRoundTripsToo() {
        val url = "http://drop.local:8765/t/7h2kq9x3m4pz/"
        assertEquals(url, decode(QrMatrix.encode(url)))
    }

    @Test
    fun malformedInputRaisesQrEncodeException() {
        assertFailsWith<QrEncodeException> { QrMatrix.encode("") }
        assertFailsWith<QrEncodeException> { QrMatrix.encode("x".repeat(8_000)) }
        val m = QrMatrix.encode("a")
        assertFailsWith<IllegalArgumentException> { m[m.size, 0] }
        assertFailsWith<IllegalArgumentException> { m[0, -1] }
    }
}

/** WCAG 2.x contrast of the colour tokens (design §11: AA for all text, 4.5:1; graphics 3:1). */
class ContrastTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAa(
        text: Color,
        background: Color,
        what: String,
        minimum: Double = 4.5,
    ) {
        val ratio = contrast(text, background)
        assertTrue(ratio >= minimum, "$what: ${"%.2f".format(ratio)}:1 < $minimum:1")
    }

    @Test
    fun designSection11_textTokensAreAaOnEverySurfaceTheyUse() {
        for ((name, c) in listOf("light" to DropColors.Light, "dark" to DropColors.Dark)) {
            for ((surfaceName, surface) in listOf("bg" to c.bg, "surface" to c.surface)) {
                assertAa(c.text, surface, "$name text on $surfaceName")
                assertAa(c.textMuted, surface, "$name textMuted on $surfaceName")
                assertAa(c.accentText, surface, "$name accentText on $surfaceName")
                assertAa(c.warningText, surface, "$name warningText (hints) on $surfaceName")
                assertAa(c.successText, surface, "$name successText on $surfaceName")
                assertAa(c.dangerText, surface, "$name dangerText on $surfaceName")
            }
            assertAa(c.text, c.accentSoft, "$name text on accentSoft (banner, code row)")
            assertAa(c.accentText, c.accentSoft, "$name accentText on accentSoft")
            assertAa(c.textMuted, c.accentSoft, "$name textMuted on accentSoft")
            assertAa(c.onPrimaryButton, c.primaryButton, "$name primary button label")
            for (avatar in c.avatarPalette) assertAa(Color.White, avatar, "$name initials on $avatar")
            // Graphics (rings, progress, bars) need 3:1 against the surface they sit on.
            assertAa(c.accent, c.surface, "$name accent graphics", minimum = 3.0)
            assertAa(c.success, c.surface, "$name success tick", minimum = 3.0)
        }
    }

    @Test
    fun designTokensAreUnchanged() {
        assertEquals(Color(0xFF2F6BFF), DropColors.Light.accent)
        assertEquals(Color(0xFF4C82FF), DropColors.Dark.accent)
        assertEquals(Color(0xFFE0A400), DropColors.Light.warning)
        assertEquals(Color(0xFF0E1116), DropColors.Dark.bg)
        assertEquals(8, DropColors.Light.avatarPalette.size)
        assertEquals(DropColors.Light.avatar(3), DropColors.Light.avatar(11))
        assertEquals(DropColors.Light.avatar(-1), DropColors.Light.avatar(7))
    }
}
