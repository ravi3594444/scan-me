package com.constrivo.drop.ui.shared.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.GlyphBadge
import com.constrivo.drop.ui.shared.components.IconAction
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.AttachmentUi
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.VisibilityUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.text.summaryText
import com.constrivo.drop.ui.shared.text.visibilityText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Everything the radar can ask for; the host wires these to presenters and navigation. */
@Immutable
class RadarCallbacks(
    val onBubbleTap: (key: String) -> Unit = {},
    val onCancelTap: (transferId: String) -> Unit = {},
    val onOverflowTap: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onShowCode: () -> Unit = {},
    val onDashboard: () -> Unit = {},
    val onVisibilityTap: () -> Unit = {},
    val onNoticeAction: (RadarNotice) -> Unit = {},
    val onClearAttachment: () -> Unit = {},
    val onTrayOpen: (fileId: String) -> Unit = {},
    val onTrayShare: (fileId: String) -> Unit = {},
    val onOpenFolder: () -> Unit = {},
)

/**
 * The radar, home of the app (design §3): title and visibility chip at the top, the share banner (§4.3), the rings
 * and bubbles around the avatar at bottom-centre, the one notice of §8.1, the tray (§5.2) and the bottom bar. Stateless:
 * [state] comes from [com.constrivo.drop.ui.shared.presenter.RadarPresenter].
 */
@Composable
fun RadarScreen(
    state: RadarUiState,
    callbacks: RadarCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .testTag(TestTags.RADAR),
    ) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            RadarField(
                state = state,
                widthDp = maxWidth.value.toDouble(),
                heightDp = maxHeight.value.toDouble(),
                callbacks = callbacks,
            )
            Column(Modifier.fillMaxWidth()) {
                RadarTopBar(state.visibility, callbacks.onVisibilityTap)
                state.attachment?.let { ShareBanner(it, callbacks.onClearAttachment) }
                state.notice?.let { notice ->
                    NoticeCard(notice, onAction = { callbacks.onNoticeAction(notice) })
                }
            }
            if (state.tray.isNotEmpty()) {
                Tray(
                    items = state.tray,
                    onOpen = callbacks.onTrayOpen,
                    onShare = callbacks.onTrayShare,
                    onOpenFolder = callbacks.onOpenFolder,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                )
            }
        }
        BottomBar(callbacks)
    }
}

@Composable
private fun RadarTopBar(
    visibility: VisibilityUi,
    onVisibilityTap: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    // Wraps the chip under the title when both do not fit (large font sizes, design §11).
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DropDimens.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.radar_title),
            style = type.title,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        VisibilityChip(visibility, onVisibilityTap)
    }
}

/** The visibility chip (design §2, §3.1): "Everyone · 7 min", "Trusted only", "Hidden" with the eye-off glyph. */
@Composable
fun VisibilityChip(
    visibility: VisibilityUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val label =
        if (visibility.mode == Visibility.EVERYONE_TEN_MINUTES && visibility.minutesLeft != null) {
            stringResource(Res.string.visibility_chip_ten_min, visibility.minutesLeft)
        } else {
            visibilityText(visibility.mode)
        }
    val description = stringResource(Res.string.a11y_visibility, label)
    Box(
        modifier =
            modifier
                .heightIn(min = DropDimens.minTouch)
                .clip(DropShapes.chip)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .testTag(TestTags.RADAR_CHIP),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(DropShapes.chip)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, DropShapes.chip)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon =
                when (visibility.mode) {
                    Visibility.HIDDEN -> DropIcons.EyeOff
                    Visibility.TRUSTED_ONLY -> DropIcons.Shield
                    else -> DropIcons.Radar
                }
            Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = LocalDropTypography.current.caption, color = colors.text)
        }
    }
}

/**
 * "Sending 12 photos · 48 MB — tap a device" (design §4.3), with the first file names below it so the user sees what
 * will be sent, or "… to Rohan's Pixel as soon as it is nearby" while the files wait for a direct-share target.
 */
@Composable
private fun ShareBanner(
    attachment: AttachmentUi,
    onClear: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DropDimens.gutter, vertical = 4.dp)
                .clip(DropShapes.card)
                .background(colors.accentSoft)
                .padding(start = 16.dp)
                .testTag(TestTags.RADAR_BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            val summary = summaryText(attachment.summary)
            val size = sizeText(attachment.totalBytes)
            Text(
                attachment.waitingFor?.let { stringResource(Res.string.share_banner_waiting, summary, size, it) }
                    ?: stringResource(Res.string.share_banner, summary, size),
                style = type.body,
                color = colors.text,
            )
            if (attachment.names.isNotEmpty()) {
                val names = attachment.names.joinToString(NAME_SEPARATOR)
                Text(
                    if (attachment.moreCount > 0) {
                        pluralStringResource(Res.plurals.share_banner_names_more, attachment.moreCount, names, attachment.moreCount)
                    } else {
                        names
                    },
                    style = type.caption,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconAction(DropIcons.Close, stringResource(Res.string.a11y_clear_attachment), onClear, tint = colors.textMuted)
    }
}

/** Between file names in the banner (English and Hindi both use a comma). */
private const val NAME_SEPARATOR = ", "

/** The empty and error states of design §8.1, one at a time. */
@Composable
private fun NoticeCard(
    notice: RadarNotice,
    onAction: () -> Unit,
) {
    val colors = LocalDropColors.current
    val (icon, text) =
        when (notice) {
            RadarNotice.PERMISSION_MISSING -> DropIcons.Lock to stringResource(Res.string.empty_permission)
            RadarNotice.BLUETOOTH_OFF -> DropIcons.BluetoothOff to stringResource(Res.string.empty_bluetooth_off)
            RadarNotice.WIFI_OFF -> DropIcons.WifiOff to stringResource(Res.string.empty_wifi_off)
            RadarNotice.HIDDEN -> DropIcons.EyeOff to stringResource(Res.string.empty_hidden)
            RadarNotice.NO_DEVICES -> DropIcons.Radar to stringResource(Res.string.empty_no_devices)
        }
    val action: String? =
        when (notice) {
            RadarNotice.PERMISSION_MISSING -> stringResource(Res.string.action_allow)
            RadarNotice.BLUETOOTH_OFF, RadarNotice.WIFI_OFF -> stringResource(Res.string.action_turn_on)
            RadarNotice.HIDDEN -> stringResource(Res.string.action_change)
            RadarNotice.NO_DEVICES -> null
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DropDimens.gutter, vertical = 8.dp)
                .clip(DropShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, DropShapes.card)
                .padding(12.dp)
                .semantics(mergeDescendants = true) { }
                .testTag(TestTags.RADAR_NOTICE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphBadge(icon, tint = colors.accentText, background = colors.accentSoft, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(text, style = LocalDropTypography.current.body, color = colors.text)
            if (action != null) {
                QuietButton(action, onAction, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Scan to send · Show my code · Dashboard (design §2, §8.3). From 150% font size the three actions stack as full-width
 * rows, so labels keep their size and are never clipped or broken inside a word (design §11: 200% text).
 */
@Composable
private fun BottomBar(callbacks: RadarCallbacks) {
    val colors = LocalDropColors.current
    val stacked = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val actions =
        listOf(
            Triple(DropIcons.Scan, stringResource(Res.string.action_scan), callbacks.onScan),
            Triple(DropIcons.Qr, stringResource(Res.string.action_show_qr), callbacks.onShowCode),
            Triple(DropIcons.Dashboard, stringResource(Res.string.action_dashboard), callbacks.onDashboard),
        )
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().testTag(TestTags.RADAR_BOTTOM_BAR)) {
        HorizontalDivider(color = colors.outline)
        if (stacked) {
            for ((icon, label, onClick) in actions) StackedBarAction(icon, label, onClick)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                for ((icon, label, onClick) in actions) BarAction(icon, label, onClick, Modifier.weight(1f))
            }
        }
    }
}

/** Font scale from which bars switch to layouts that keep whole words (design §11). */
internal const val LARGE_FONT_SCALE = 1.5f

@Composable
private fun StackedBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalDropColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = DropDimens.minTouch)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = DropDimens.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(DropDimens.icon))
        Spacer(Modifier.width(12.dp))
        Text(label, style = LocalDropTypography.current.body, color = colors.text)
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Column(
        modifier =
            modifier
                .heightIn(min = 64.dp)
                .clip(DropShapes.button)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(DropDimens.icon))
        Spacer(Modifier.size(4.dp))
        Text(label, style = LocalDropTypography.current.caption, color = colors.text, textAlign = TextAlign.Center)
    }
}
