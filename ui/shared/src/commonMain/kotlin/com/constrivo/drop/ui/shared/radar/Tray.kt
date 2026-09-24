package com.constrivo.drop.ui.shared.radar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.ThumbTile
import com.constrivo.drop.ui.shared.model.TrayItemUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * The tray (design §5.2, F‑D3): finished files above the bar, newest on the right, each sliding in with a spring bounce
 * (≈ 300 ms, damping 0.7). Tap opens, long-press shares onward, "Open folder" opens the receive folder. Files are never
 * opened automatically (F‑D5).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tray(
    items: List<TrayItemUi>,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val scroll = rememberScrollState()
    LaunchedEffect(items.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier =
            modifier
                .padding(horizontal = DropDimens.gutter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(DropShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, DropShapes.card)
                .padding(8.dp)
                .testTag(TestTags.RADAR_TRAY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (item in items) {
                androidx.compose.runtime.key(item.id) { TrayThumb(item, onOpen, onShare) }
            }
        }
        QuietButton(stringResource(Res.string.tray_open_folder), onOpenFolder)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrayThumb(
    item: TrayItemUi,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val slide = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(0f, spring(dampingRatio = DropMotion.TRAY_DAMPING, stiffness = DropMotion.TRAY_STIFFNESS))
    }
    // Items enter from the end side (the right in left-to-right layouts, where the newest sits).
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val openLabel = stringResource(Res.string.a11y_action_open)
    val shareLabel = stringResource(Res.string.a11y_action_share)
    Box(
        modifier =
            Modifier
                .size(DropDimens.trayThumb)
                .graphicsLayer { translationX = direction * slide.value * size.width }
                .clip(DropShapes.button)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = openLabel,
                    onLongClickLabel = shareLabel,
                    onLongClick = { onShare(item.id) },
                    onClick = { onOpen(item.id) },
                ).semantics { contentDescription = item.name },
    ) {
        ThumbTile(item.thumb, DropDimens.trayThumb, corner = 12.dp)
    }
}
