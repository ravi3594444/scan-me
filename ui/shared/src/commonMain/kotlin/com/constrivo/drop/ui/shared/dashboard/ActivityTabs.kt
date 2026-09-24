package com.constrivo.drop.ui.shared.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.components.BadgeChip
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.components.fileKindIcon
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.Formats
import com.constrivo.drop.ui.shared.model.HistoryDetailUi
import com.constrivo.drop.ui.shared.model.HistoryRowUi
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.LiveRowUi
import com.constrivo.drop.ui.shared.presenter.HistoryUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.badgeText
import com.constrivo.drop.ui.shared.text.clockText
import com.constrivo.drop.ui.shared.text.dayLabelText
import com.constrivo.drop.ui.shared.text.durationText
import com.constrivo.drop.ui.shared.text.hintText
import com.constrivo.drop.ui.shared.text.historyStatusText
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.text.speedLineText
import com.constrivo.drop.ui.shared.text.stageText
import com.constrivo.drop.ui.shared.text.summaryText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.DropType
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

@Immutable
class LiveCallbacks(
    val onPause: (String) -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onAddFiles: (String) -> Unit = {},
)

/** Live tab (F‑G1, design §6): device, files, progress bar, speed and ETA, badge, hint; pause, cancel, add. */
@Composable
internal fun LiveTab(
    rows: List<LiveRowUi>,
    callbacks: LiveCallbacks,
) {
    if (rows.isEmpty()) {
        EmptyTab(stringResource(Res.string.live_empty))
        return
    }
    LazyColumn(contentPadding = PaddingValues(DropDimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.transferId }) { row -> LiveCard(row, callbacks) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(
    row: LiveRowUi,
    callbacks: LiveCallbacks,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DropShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, DropShapes.card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirectionIcon(row.direction)
            Text(
                stringResource(if (row.direction == Direction.SEND) Res.string.history_to else Res.string.history_from, row.peerName),
                style = type.bodyStrong,
                color = colors.text,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        Text(
            stringResource(
                Res.string.summary_with_size,
                summaryText(row.summary),
                stringResource(Res.string.live_progress, sizeText(row.bytesDone), sizeText(row.bytesTotal)),
            ),
            style = type.caption,
            color = colors.textMuted,
        )
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.outline)) {
            Box(Modifier.fillMaxWidth(row.fraction).height(6.dp).background(colors.accent))
        }
        val line = stageText(row.stage, row.direction, row.peerName, row.paused) ?: speedLineText(row.bytesPerSecond, row.etaMillis)
        line?.let { Text(it, style = type.readout, color = colors.text) }
        row.badge?.let { BadgeChip(it) }
        row.hint?.let { Text(hintText(it), style = type.caption, color = colors.warningText) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.paused) {
                QuietButton(stringResource(Res.string.live_resume), { callbacks.onResume(row.transferId) })
            } else {
                QuietButton(stringResource(Res.string.live_pause), { callbacks.onPause(row.transferId) })
            }
            QuietButton(stringResource(Res.string.live_cancel), { callbacks.onCancel(row.transferId) }, color = colors.dangerText)
            if (row.canAddFiles) QuietButton(stringResource(Res.string.live_add), { callbacks.onAddFiles(row.transferId) })
        }
    }
}

@Composable
private fun DirectionIcon(direction: Direction) {
    val colors = LocalDropColors.current
    Icon(
        if (direction == Direction.SEND) DropIcons.ArrowUp else DropIcons.ArrowDown,
        contentDescription = null,
        tint = colors.accentText,
        modifier = Modifier.size(20.dp),
    )
}

@Immutable
class HistoryCallbacks(
    val onOpen: (String) -> Unit = {},
    val onClear: () -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onOpenFile: (transferId: String, fileId: String) -> Unit = { _, _ -> },
    val onResend: (String) -> Unit = {},
)

/**
 * History tab (F‑G2, design §6): grouped by day, newest first; a row shows the direction arrow, device, files,
 * size, duration, average speed and the status chip; tap opens the detail sheet. Lazily laid out, so 10,000 rows
 * scroll smoothly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryTab(
    state: HistoryUi,
    callbacks: HistoryCallbacks,
) {
    if (state.isEmpty) {
        EmptyTab(stringResource(Res.string.history_empty))
        return
    }
    val colors = LocalDropColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        for (day in state.days) {
            stickyHeader(key = DayKey(day.epochDay)) {
                Text(
                    dayLabelText(day.label),
                    style = LocalDropTypography.current.sectionHeader,
                    color = colors.textMuted,
                    modifier =
                        Modifier.fillMaxWidth().background(
                            colors.bg,
                        ).padding(horizontal = DropDimens.gutter, vertical = 8.dp).semantics {
                            heading()
                        },
                )
            }
            items(day.rows, key = { it.entry.id }) { row -> HistoryRow(row, callbacks.onOpen) }
        }
    }
}

/** Lazy-list key of a day header (row keys are transfer ids, so headers need their own key type). */
private data class DayKey(
    val epochDay: Long,
)

@Composable
private fun HistoryRow(
    row: HistoryRowUi,
    onOpen: (String) -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val e = row.entry
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { onOpen(e.id) }
            .padding(horizontal = DropDimens.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DirectionIcon(e.direction)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (e.direction == Direction.SEND) Res.string.history_to else Res.string.history_from, e.peerName),
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(summaryText(e.summary), style = type.caption, color = colors.textMuted)
            Text(historyDetails(row), style = type.caption, color = colors.textMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                clockText(row.hour, row.minute),
                style = type.caption.copy(fontFeatureSettings = DropType.TABULAR_NUMERALS),
                color = colors.textMuted,
            )
            StatusChip(e.status)
        }
    }
}

/** "48 MB · 12 s · 4.0 MB/s" (size, duration, average speed); parts that are unknown are left out. */
@Composable
private fun historyDetails(row: HistoryRowUi): String {
    val e = row.entry
    val size = sizeText(if (e.status == HistoryStatus.DONE) e.bytesTotal else e.bytesDone)
    val duration = e.durationMillis?.let { durationText(it) }
    val speed = e.avgBytesPerSecond?.let { stringResource(Res.string.stats_speed, Formats.megabytesPerSecond(it)) }
    return when {
        duration != null && speed != null -> stringResource(Res.string.history_details, size, duration, speed)
        duration != null -> stringResource(Res.string.history_details_short, size, duration)
        else -> size
    }
}

/** The status chip: text on an outlined chip, colour as a second cue only (design §11). */
@Composable
private fun StatusChip(status: HistoryStatus) {
    val colors = LocalDropColors.current
    val color =
        when (status) {
            HistoryStatus.DONE -> colors.successText
            HistoryStatus.PARTIAL, HistoryStatus.INTERRUPTED -> colors.warningText
            HistoryStatus.FAILED -> colors.dangerText
            HistoryStatus.CANCELLED -> colors.textMuted
        }
    Text(
        historyStatusText(status),
        style = LocalDropTypography.current.caption,
        color = color,
        modifier =
            Modifier.padding(
                top = 4.dp,
            ).border(1.dp, color.copy(alpha = 0.5f), DropShapes.chip).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The History detail sheet (design §6): when, size, speed, link, the files (tap to open) and "Send again". */
@Composable
fun HistoryDetailSheet(
    state: HistoryDetailUi,
    callbacks: HistoryCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val e = state.row.entry
    SheetSurface(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetTitle(stringResource(if (e.direction == Direction.SEND) Res.string.history_to else Res.string.history_from, e.peerName))
            Text(
                stringResource(Res.string.history_detail_when, dayLabelText(state.label), clockText(state.row.hour, state.row.minute)),
                style = type.body,
                color = colors.textMuted,
            )
            Text(
                stringResource(Res.string.summary_with_size, summaryText(e.summary), historyDetails(state.row)),
                style = type.body,
                color = colors.text,
            )
            e.badge?.let { Text(stringResource(Res.string.history_via, badgeText(it)), style = type.caption, color = colors.textMuted) }
            StatusChip(e.status)
            val files = state.files
            if (files == null) {
                Text(stringResource(Res.string.history_files_loading), style = type.caption, color = colors.textMuted)
            } else {
                for (f in files) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = DropDimens.minTouch)
                            .let { if (f.openable) it.clickable(role = Role.Button) { callbacks.onOpenFile(e.id, f.id) } else it },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(fileKindIcon(f.kind), contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                style = type.body,
                                color = if (f.openable) colors.text else colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                            Text(
                                if (f.openable) sizeText(f.sizeBytes) else stringResource(Res.string.history_file_missing),
                                style = type.caption,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
            if (e.direction == Direction.SEND) QuietButton(stringResource(Res.string.history_resend), { callbacks.onResend(e.id) })
            QuietButton(
                stringResource(Res.string.common_close),
                callbacks.onCloseDetail,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
