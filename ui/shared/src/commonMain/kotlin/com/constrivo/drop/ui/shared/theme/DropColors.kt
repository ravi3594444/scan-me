package com.constrivo.drop.ui.shared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Colour tokens from design §1. `accent` is a placeholder brand colour until the name and brand are decided. */
@Immutable
data class DropColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    /** Radar rings: accent at 35% / 20% / 10% opacity, brightened slightly in dark mode (design §1). */
    fun ring(
        index: Int,
        dark: Boolean,
    ): Color {
        val alpha = floatArrayOf(0.35f, 0.20f, 0.10f)[index.coerceIn(0, 2)]
        return accent.copy(alpha = if (dark) (alpha * 1.25f).coerceAtMost(1f) else alpha)
    }

    companion object {
        val Light =
            DropColors(
                bg = Color(0xFFF7F8FA),
                surface = Color(0xFFFFFFFF),
                text = Color(0xFF14171C),
                textMuted = Color(0xFF5F6773),
                accent = Color(0xFF2F6BFF),
                accentSoft = Color(0xFFE6EDFF),
                success = Color(0xFF1FA971),
                warning = Color(0xFFE0A400),
                danger = Color(0xFFD9433B),
            )

        val Dark =
            DropColors(
                bg = Color(0xFF0E1116),
                surface = Color(0xFF171B22),
                text = Color(0xFFECEFF3),
                textMuted = Color(0xFF9AA3AF),
                accent = Color(0xFF4C82FF),
                accentSoft = Color(0xFF1B2540),
                success = Color(0xFF2ECC8A),
                warning = Color(0xFFF2B824),
                danger = Color(0xFFFF6B61),
            )
    }
}
