package com.constrivo.drop.ui.shared.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.components.ThumbTile
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.FilePickerUi
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.PickableUi
import com.constrivo.drop.ui.shared.model.PickerTab
import com.constrivo.drop.ui.shared.model.PickerTarget
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
class FilePickerCallbacks(
    val onTab: (PickerTab) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onBrowseFiles: () -> Unit = {},
    val onAllowPhotos: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onClose: () -> Unit = {},
    /** The Photos grid scrolled near its end: list older media (paging). */
    val onLoadMore: () -> Unit = {},
)

/**
 * The file picker (F‑C1, design §4.1), shown in a sheet of 80% height: Photos (3-column grid, recent first, count
 * badges), Files (system picker), Apps (feature flag, decision 8), and "Send {count} items · {size}", disabled at zero.
 */
@Composable
fun FilePickerSheet(
    state: FilePickerUi,
    callbacks: FilePickerCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    SheetSurface(modifier.testTag(TestTags.PICKER)) {
        val title =
            when (val target = state.target) {
                is PickerTarget.Device -> target.name?.let { stringResource(Res.string.picker_title, it) }
                is PickerTarget.Transfer -> stringResource(Res.string.picker_title_add, target.peerName)
                PickerTarget.Browser -> stringResource(Res.string.picker_title_browser)
            } ?: stringResource(Res.string.picker_title_unnamed)
        SheetTitle(title, Modifier.padding(bottom = 8.dp))
        PickerTabs(state.tabs, state.tab, callbacks.onTab)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            when (state.tab) {
                PickerTab.PHOTOS -> PhotosTab(state, callbacks)
                PickerTab.FILES -> FilesTab(state, callbacks)
                PickerTab.APPS -> AppsTab(state, callbacks)
            }
        }
        val label =
            if (state.canSend) {
                val button = if (state.target is PickerTarget.Transfer) Res.plurals.add_button else Res.plurals.send_button
                pluralStringResource(button, state.selectedCount, state.selectedCount, sizeText(state.selectedBytes))
            } else {
                stringResource(Res.string.send_button_empty)
            }
        PrimaryButton(label, callbacks.onSend, enabled = state.canSend)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QuietButton(stringResource(Res.string.common_cancel), callbacks.onClose, color = colors.textMuted)
        }
    }
}

@Composable
private fun PickerTabs(
    tabs: List<PickerTab>,
    selected: PickerTab,
    onTab: (PickerTab) -> Unit,
) {
    val colors = LocalDropColors.current
    Row(Modifier.fillMaxWidth()) {
        for (tab in tabs) {
            val isSelected = tab == selected
            val label =
                stringResource(
                    when (tab) {
                        PickerTab.PHOTOS -> Res.string.picker_tab_photos
                        PickerTab.FILES -> Res.string.picker_tab_files
                        PickerTab.APPS -> Res.string.picker_tab_apps
                    },
                )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = DropDimens.minTouch)
                        .selectable(selected = isSelected, role = Role.Tab) { onTab(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = if (isSelected) LocalDropTypography.current.bodyStrong else LocalDropTypography.current.body,
                    color = if (isSelected) colors.accentText else colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(if (isSelected) colors.accent else colors.outline.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun PhotosTab(
    state: FilePickerUi,
    callbacks: FilePickerCallbacks,
) {
    when {
        !state.photosAccess -> {
            Message(stringResource(Res.string.picker_photos_access), stringResource(Res.string.action_allow), callbacks.onAllowPhotos)
        }

        state.photos.isEmpty() -> {
            Message(stringResource(Res.string.picker_photos_empty), null, {})
        }

        else -> {
            val grid = rememberLazyGridState()
            // Paging: ask for older media when the last rows come into view (F‑C1: hundreds of photos).
            val onLoadMore by rememberUpdatedState(callbacks.onLoadMore)
            LaunchedEffect(grid) {
                snapshotFlow {
                    val info = grid.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_AHEAD
                }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.photos, key = { it.item.id }) { p -> PhotoTile(p, callbacks.onToggle) }
            }
        }
    }
}

/** How many items before the end of the grid the next page is asked for (four rows). */
private const val LOAD_MORE_AHEAD = 12

@Composable
private fun PhotoTile(
    p: PickableUi,
    onToggle: (String) -> Unit,
) {
    val colors = LocalDropColors.current
    val description =
        if (p.selected) stringResource(Res.string.a11y_joined, p.item.name, stringResource(Res.string.a11y_selected)) else p.item.name
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .toggleable(value = p.selected, role = Role.Checkbox) { onToggle(p.item.id) }
                .semantics { contentDescription = description },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            ThumbTile(p.item.thumb ?: FileThumb.Glyph(p.item.kind), maxWidth, corner = 6.dp)
        }
        if (p.selected) {
            Box(Modifier.fillMaxSize().border(3.dp, colors.accent, RoundedCornerShape(6.dp)))
        }
        CountBadge(p.selectionIndex, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** The selection count badge of design §4.1 (empty circle when not selected). */
@Composable
private fun CountBadge(
    index: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Box(
        modifier =
            modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (index > 0) colors.primaryButton else Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (index > 0) {
            Text(index.toString(), style = LocalDropTypography.current.label, color = colors.onPrimaryButton, maxLines = 1)
        }
    }
}

@Composable
private fun FilesTab(
    state: FilePickerUi,
    callbacks: FilePickerCallbacks,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(Res.string.picker_files_hint),
            style = LocalDropTypography.current.caption,
            color = LocalDropColors.current.textMuted,
        )
        QuietButton(stringResource(Res.string.picker_files_browse), callbacks.onBrowseFiles)
        LazyColumn(Modifier.weight(1f)) {
            items(state.files, key = { it.item.id }) { f -> ItemRow(f, callbacks.onToggle) }
        }
    }
}

@Composable
private fun AppsTab(
    state: FilePickerUi,
    callbacks: FilePickerCallbacks,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(Res.string.picker_apps_hint),
            style = LocalDropTypography.current.caption,
            color = LocalDropColors.current.textMuted,
        )
        if (state.apps.isEmpty()) {
            Message(stringResource(Res.string.picker_apps_empty), null, {})
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.apps, key = { it.item.id }) { a -> ItemRow(a, callbacks.onToggle) }
            }
        }
    }
}

@Composable
private fun ItemRow(
    p: PickableUi,
    onToggle: (String) -> Unit,
) {
    val colors = LocalDropColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = p.selected, role = Role.Checkbox) { onToggle(p.item.id) }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThumbTile(p.item.thumb ?: FileThumb.Glyph(p.item.kind), 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                p.item.name,
                style = LocalDropTypography.current.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            p.item.sizeBytes?.let { Text(sizeText(it), style = LocalDropTypography.current.caption, color = colors.textMuted) }
        }
        if (p.selected) Icon(DropIcons.Check, contentDescription = null, tint = colors.accentText)
    }
}

@Composable
private fun Message(
    text: String,
    action: String?,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = LocalDropTypography.current.body, color = LocalDropColors.current.textMuted, textAlign = TextAlign.Center)
        if (action != null) QuietButton(action, onAction)
    }
}
