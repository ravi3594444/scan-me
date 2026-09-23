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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalDropColors = staticCompositionLocalOf { DropColors.Light }

/** Type scale from design §1: title 22 sp, body 16 sp, caption 13 sp, speed readout 28 sp with tabular numerals. */
object DropType {
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp)
    val caption = TextStyle(fontSize = 13.sp)
    val speed = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", textAlign = TextAlign.Center)
}

/** Shapes from design §1: 16 dp cards and sheets, 12 dp buttons. */
object DropShapes {
    val card = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(12.dp)
}

/** App theme. Dark mode follows the system (design §1). */
@Composable
fun DropTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DropColors.Dark else DropColors.Light
    val scheme =
        if (dark) {
            darkColorScheme(
                primary = colors.accent,
                background = colors.bg,
                surface = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                error = colors.danger,
            )
        } else {
            lightColorScheme(
                primary = colors.accent,
                background = colors.bg,
                surface = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                error = colors.danger,
            )
        }
    CompositionLocalProvider(LocalDropColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(titleLarge = DropType.title, bodyLarge = DropType.body, labelSmall = DropType.caption),
            shapes = Shapes(medium = DropShapes.card, small = DropShapes.button),
            content = content,
        )
    }
}
