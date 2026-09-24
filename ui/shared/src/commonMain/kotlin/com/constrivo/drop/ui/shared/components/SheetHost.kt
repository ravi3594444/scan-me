package com.constrivo.drop.ui.shared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalReducedMotion

/**
 * One sheet over a screen (design §1: one elevation level, sheets over the radar).
 *
 * @property key identifies the sheet (any value with equality, typically an enum); a new key replaces the content.
 * @property heightFraction a fixed share of the height (the picker's 80%, design §4.1); null wraps the content, up to
 *   90% of the height.
 */
@Immutable
class SheetSpec(
    val key: Any,
    val heightFraction: Float? = null,
    val content: @Composable () -> Unit,
)

/**
 * Shows [content] with at most one [sheet] above it: a scrim that dismisses on tap, and the sheet sliding up from the
 * bottom (a plain fade under reduced motion). The last sheet stays composed while it slides out.
 */
@Composable
fun SheetHost(
    sheet: SheetSpec?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalDropColors.current
    val reduced = LocalReducedMotion.current
    // A plain holder, not snapshot state: it only keeps the outgoing sheet composed during its exit animation.
    val last = remember { SheetHolder() }
    if (sheet != null) last.spec = sheet
    Box(modifier.fillMaxSize()) {
        // The sheet is modal for screen readers too (design §11): what lies under it is hidden from TalkBack while it
        // shows, so focus cannot wander to the radar or the dashboard and activate them behind the sheet.
        Box(if (sheet != null) Modifier.fillMaxSize().semantics { hideFromAccessibility() } else Modifier.fillMaxSize()) { content() }
        AnimatedVisibility(visible = sheet != null, enter = fadeIn(tween(SCRIM_MILLIS)), exit = fadeOut(tween(SCRIM_MILLIS))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .clearAndSetSemantics { },
            )
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val maxSheet = maxHeight * MAX_FRACTION
            AnimatedVisibility(
                visible = sheet != null,
                enter =
                    if (reduced) {
                        fadeIn(
                            tween(SCRIM_MILLIS),
                        )
                    } else {
                        slideInVertically(tween(SLIDE_MILLIS, easing = DropMotion.Decelerate)) {
                            it
                        }
                    },
                exit =
                    if (reduced) {
                        fadeOut(
                            tween(SCRIM_MILLIS),
                        )
                    } else {
                        slideOutVertically(tween(SLIDE_MILLIS, easing = DropMotion.Accelerate)) {
                            it
                        }
                    },
            ) {
                val spec = sheet ?: last.spec ?: return@AnimatedVisibility
                val base = Modifier.widthIn(max = MAX_SHEET_WIDTH).fillMaxWidth()
                val sized =
                    spec.heightFraction?.let { base.height(maxHeight * it.coerceIn(0.2f, MAX_FRACTION)) } ?: base.heightIn(max = maxSheet)
                Box(sized) { spec.content() }
            }
        }
    }
}

private class SheetHolder {
    var spec: SheetSpec? = null
}

private const val SCRIM_MILLIS = 200
private const val SLIDE_MILLIS = 250
private const val MAX_FRACTION = 0.9f
private val MAX_SHEET_WIDTH = 640.dp
