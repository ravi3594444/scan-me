package com.constrivo.drop.ui.shared.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.IconAction
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.LiveRowUi
import com.constrivo.drop.ui.shared.model.SettingsUi
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.presenter.DevicesUi
import com.constrivo.drop.ui.shared.presenter.HistoryUi
import com.constrivo.drop.ui.shared.radar.LARGE_FONT_SCALE
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

/** Everything the dashboard shows (design §6); each tab's part comes from its presenter. */
@Immutable
data class DashboardUiState(
    val tab: DashboardTab,
    val live: List<LiveRowUi>,
    val history: HistoryUi,
    val devices: DevicesUi,
    val stats: StatsSnapshot,
    val settings: SettingsUi,
)

@Immutable
class DashboardCallbacks(
    val onBack: () -> Unit = {},
    val onTab: (DashboardTab) -> Unit = {},
    val live: LiveCallbacks = LiveCallbacks(),
    val history: HistoryCallbacks = HistoryCallbacks(),
    val devices: DevicesCallbacks = DevicesCallbacks(),
    val onShareStats: () -> Unit = {},
    val settings: SettingsCallbacks = SettingsCallbacks(),
)

/**
 * The dashboard (design §6): full screen, a title bar with Back, and five tabs across the bottom (Live, History,
 * Devices, Stats, Settings). Sheets of the tabs (History detail, rename, forget, confirmations) are shown by the host.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Column(modifier.fillMaxSize().background(colors.bg).testTag(TestTags.DASHBOARD)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(DropIcons.Back, stringResource(Res.string.common_back), callbacks.onBack)
            Text(
                stringResource(Res.string.dashboard_title),
                style = LocalDropTypography.current.title,
                color = colors.text,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            if (state.tab == DashboardTab.HISTORY && !state.history.isEmpty) {
                QuietButton(stringResource(Res.string.history_clear), callbacks.history.onClear)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                DashboardTab.LIVE -> LiveTab(state.live, callbacks.live)
                DashboardTab.HISTORY -> HistoryTab(state.history, callbacks.history)
                DashboardTab.DEVICES -> DevicesTab(state.devices, callbacks.devices)
                DashboardTab.STATS -> StatsTab(state.stats, callbacks.onShareStats)
                DashboardTab.SETTINGS -> SettingsTab(state.settings, callbacks.settings)
            }
        }
        DashboardTabs(state.tab, callbacks.onTab)
    }
}

private fun tabParts(tab: DashboardTab) =
    when (tab) {
        DashboardTab.LIVE -> DropIcons.Live to Res.string.tab_live
        DashboardTab.HISTORY -> DropIcons.History to Res.string.tab_history
        DashboardTab.DEVICES -> DropIcons.Devices to Res.string.tab_devices
        DashboardTab.STATS -> DropIcons.Stats to Res.string.tab_stats
        DashboardTab.SETTINGS -> DropIcons.Settings to Res.string.tab_settings
    }

/**
 * The five tabs across the bottom (design §6). From 150% font size they become a horizontally scrolling row of
 * full-width labels, so no label is clipped or broken inside a word (design §11).
 */
@Composable
private fun DashboardTabs(
    selected: DashboardTab,
    onTab: (DashboardTab) -> Unit,
) {
    val colors = LocalDropColors.current
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        ScrollingTabs(selected, onTab)
        return
    }
    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
        for (tab in DashboardTab.entries) {
            val (icon, label) = tabParts(tab)
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onTab(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label), style = LocalDropTypography.current.caption, textAlign = TextAlign.Center) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accentText,
                        selectedTextColor = colors.accentText,
                        indicatorColor = colors.accentSoft,
                        unselectedIconColor = colors.textMuted,
                        unselectedTextColor = colors.textMuted,
                    ),
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun ScrollingTabs(
    selected: DashboardTab,
    onTab: (DashboardTab) -> Unit,
) {
    val colors = LocalDropColors.current
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .horizontalScroll(scroll)
            .selectableGroup()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tab in DashboardTab.entries) {
            val (icon, label) = tabParts(tab)
            val isSelected = tab == selected
            Row(
                Modifier
                    .heightIn(min = DropDimens.minTouch)
                    .clip(DropShapes.button)
                    .background(if (isSelected) colors.accentSoft else colors.surface)
                    .selectable(selected = isSelected, role = Role.Tab) { onTab(tab) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (isSelected) colors.accentText else colors.textMuted
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(label),
                    style = LocalDropTypography.current.caption,
                    color = tint,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** A centred empty-state line for the tabs. */
@Composable
internal fun EmptyTab(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = LocalDropTypography.current.body, color = LocalDropColors.current.textMuted, textAlign = TextAlign.Center)
    }
}
