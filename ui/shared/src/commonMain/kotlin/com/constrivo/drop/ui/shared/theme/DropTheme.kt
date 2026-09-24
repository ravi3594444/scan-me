package com.constrivo.drop.ui.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalDropColors = staticCompositionLocalOf { DropColors.Light }

/** True while the dark palette is active (rings brighten, design §1). */
val LocalDropDark = staticCompositionLocalOf { false }

val LocalDropTypography = staticCompositionLocalOf { DropTypography() }

/**
 * Reduced motion (design §3.3, §11): the platform reads the system setting (Android: animator duration scale 0 or
 * "Remove animations"; desktop: off). When true the rings are static at 20% opacity and files do not fly; the progress
 * ring still animates.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Type scale from design §1 on the system font: title 22 sp, body 16 sp, caption 13 sp, speed readout 28 sp with
 * tabular numerals. These are the defaults of [DropTypography] with [FontFamily.Default].
 */
object DropType {
    /** OpenType feature for tabular (fixed-width) numerals, used by readouts and stat tiles (design §1, §6). */
    const val TABULAR_NUMERALS: String = "tnum"

    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp)
    val caption = TextStyle(fontSize = 13.sp)
    val speed =
        TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_NUMERALS, textAlign = TextAlign.Center)
}

/**
 * The design §1 type scale in one font family. [label] (12 sp) is the bubble name label of design §3.1; [readout] is
 * the 13 sp tabular style of the "44 MB/s · 45 s left" line; [stat] is the stat tile value (tabular, design §6).
 */
@Immutable
data class DropTypography(
    val fontFamily: FontFamily = FontFamily.Default,
) {
    val title: TextStyle = DropType.title.copy(fontFamily = fontFamily)
    val body: TextStyle = DropType.body.copy(fontFamily = fontFamily)
    val bodyStrong: TextStyle = DropType.body.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold)
    val caption: TextStyle = DropType.caption.copy(fontFamily = fontFamily)
    val label: TextStyle = TextStyle(fontFamily = fontFamily, fontSize = 12.sp, textAlign = TextAlign.Center)
    val button: TextStyle = TextStyle(fontFamily = fontFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val speed: TextStyle = DropType.speed.copy(fontFamily = fontFamily)
    val readout: TextStyle =
        TextStyle(
            fontFamily = fontFamily,
            fontSize = 13.sp,
            fontFeatureSettings = DropType.TABULAR_NUMERALS,
            textAlign = TextAlign.Center,
        )
    val stat: TextStyle =
        TextStyle(
            fontFamily = fontFamily,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = DropType.TABULAR_NUMERALS,
        )
    val sectionHeader: TextStyle = TextStyle(fontFamily = fontFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** Shapes from design §1: 16 dp cards and sheets, 12 dp buttons. */
object DropShapes {
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val button = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(12.dp)
}

/**
 * App theme. Dark mode follows the system (design §1).
 *
 * @param fontFamily the system font by default; screenshot tests pass a bundled font so images match on every machine.
 * @param reducedMotion the system's reduced-motion setting ([LocalReducedMotion]).
 */
@Composable
fun DropTheme(
    dark: Boolean = isSystemInDarkTheme(),
    fontFamily: FontFamily = FontFamily.Default,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DropColors.Dark else DropColors.Light
    val typography = remember(fontFamily) { DropTypography(fontFamily) }
    val scheme =
        if (dark) {
            darkColorScheme(
                primary = colors.primaryButton,
                onPrimary = colors.onPrimaryButton,
                secondary = colors.accentText,
                background = colors.bg,
                surface = colors.surface,
                surfaceContainerLow = colors.surface,
                surfaceContainer = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                onSurfaceVariant = colors.textMuted,
                outline = colors.outline,
                error = colors.dangerText,
            )
        } else {
            lightColorScheme(
                primary = colors.primaryButton,
                onPrimary = colors.onPrimaryButton,
                secondary = colors.accentText,
                background = colors.bg,
                surface = colors.surface,
                surfaceContainerLow = colors.surface,
                surfaceContainer = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                onSurfaceVariant = colors.textMuted,
                outline = colors.outline,
                error = colors.dangerText,
            )
        }
    CompositionLocalProvider(
        LocalDropColors provides colors,
        LocalDropDark provides dark,
        LocalDropTypography provides typography,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography =
                Typography(
                    titleLarge = typography.title,
                    bodyLarge = typography.body,
                    bodyMedium = typography.body,
                    labelLarge = typography.button,
                    labelMedium = typography.caption,
                    labelSmall = typography.caption,
                ),
            shapes = Shapes(small = DropShapes.button, medium = DropShapes.card, large = DropShapes.card),
            content = content,
        )
    }
}
