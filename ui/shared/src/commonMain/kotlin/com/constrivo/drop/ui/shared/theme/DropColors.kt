package com.constrivo.drop.ui.shared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens from design §1. `accent` is a placeholder brand colour until the name and brand are decided (decision 1).
 *
 * The design tokens are used unchanged for fills, rings, progress and glyphs. Four tokens are added by WP8 so that all
 * text meets WCAG AA (design §11): the design's accent, warning, success and danger colours measure below 4.5:1 as text
 * on white (warning about 2:1), so text in those roles uses the `*Text` variants, and the primary button uses
 * [primaryButton] / [onPrimaryButton] (white on #2F6BFF is 4.50:1, just under AA; white on the dark accent is 3.5:1).
 *
 * @property accentText accent-coloured text and links, AA on [surface], [bg] and [accentSoft].
 * @property warningText the hint line (design §8.2) and slow-mode text, AA on [bg] and [surface].
 * @property outline hairline borders of chips and cards (never the only signal: chips always carry text, design §11).
 * @property scrim behind sheets (design §1: one elevation level, sheets over the radar).
 * @property avatarPalette the eight muted initials backgrounds of design §12, chosen by a hash of the device ID; white
 *   initials are AA on each.
 */
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
    val accentText: Color = accent,
    val warningText: Color = warning,
    val successText: Color = success,
    val dangerText: Color = danger,
    val primaryButton: Color = accent,
    val onPrimaryButton: Color = Color.White,
    val outline: Color = textMuted.copy(alpha = 0.3f),
    val scrim: Color = Color.Black.copy(alpha = 0.32f),
    val avatarPalette: List<Color> = DEFAULT_AVATAR_PALETTE,
) {
    /** Radar rings: accent at 35% / 20% / 10% opacity, brightened slightly in dark mode (design §1). */
    fun ring(
        index: Int,
        dark: Boolean,
    ): Color {
        val alpha = floatArrayOf(0.35f, 0.20f, 0.10f)[index.coerceIn(0, 2)]
        return accent.copy(alpha = if (dark) (alpha * 1.25f).coerceAtMost(1f) else alpha)
    }

    /** The initials background for a device whose stable hash is [hash] (design §12). */
    fun avatar(hash: Int): Color = avatarPalette[hash.mod(avatarPalette.size)]

    companion object {
        /** Muted initials backgrounds (design §12), each AA with white initials. */
        val DEFAULT_AVATAR_PALETTE: List<Color> =
            listOf(
                Color(0xFF4F5FB3),
                Color(0xFF2F6E7D),
                Color(0xFF7D4F92),
                Color(0xFF9A4E40),
                Color(0xFF44714A),
                Color(0xFF7A6437),
                Color(0xFF55606F),
                Color(0xFF964468),
            )

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
                accentText = Color(0xFF285EE8),
                warningText = Color(0xFF8C6400),
                successText = Color(0xFF13784F),
                dangerText = Color(0xFFB8322A),
                primaryButton = Color(0xFF285EE8),
                onPrimaryButton = Color.White,
                outline = Color(0xFFD5D9E0),
                scrim = Color(0x5214171C),
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
                accentText = Color(0xFF6A96FF),
                warningText = Color(0xFFF2B824),
                successText = Color(0xFF2ECC8A),
                dangerText = Color(0xFFFF6B61),
                primaryButton = Color(0xFF4C82FF),
                onPrimaryButton = Color(0xFF0E1116),
                outline = Color(0xFF2C323C),
                scrim = Color(0x99000000),
            )
    }
}
