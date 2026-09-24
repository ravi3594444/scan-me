package com.constrivo.drop.ui.shared.qr

import androidx.compose.runtime.Immutable
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** Thrown when a string cannot be encoded as a QR code (too long for version 40 at the chosen level). */
class QrEncodeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The modules of a QR code (F‑B5, design §4.4), without the quiet zone; the renderer adds a four-module margin.
 * Encoded with ZXing core (pure Java, so it runs on every JVM target of this module).
 */
@Immutable
class QrMatrix private constructor(
    val size: Int,
    private val modules: BooleanArray,
    val text: String,
) {
    /** True for a dark module at column [x], row [y]. */
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean {
        require(x in 0 until size && y in 0 until size) { "module ($x, $y) outside $size × $size" }
        return modules[y * size + x]
    }

    override fun equals(other: Any?): Boolean = other is QrMatrix && other.text == text && other.modules.contentEquals(modules)

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "QrMatrix(${size}x$size)"

    companion object {
        /** Modules of light margin on each side, as the QR specification requires. */
        const val QUIET_ZONE: Int = 4

        /**
         * Encodes [text] (the base64url QR payload of architecture §6.3, or a URL) at error-correction level M.
         *
         * @throws QrEncodeException when [text] is empty or too long for a QR code.
         */
        fun encode(text: String): QrMatrix {
            if (text.isEmpty()) throw QrEncodeException("nothing to encode")
            val code =
                try {
                    Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
                } catch (e: WriterException) {
                    throw QrEncodeException("text does not fit a QR code", e)
                } catch (e: IllegalArgumentException) {
                    throw QrEncodeException("text cannot be encoded", e)
                }
            val matrix = code.matrix
            val n = matrix.width
            val modules = BooleanArray(n * n) { i -> matrix.get(i % n, i / n).toInt() == 1 }
            return QrMatrix(n, modules, text)
        }
    }
}
