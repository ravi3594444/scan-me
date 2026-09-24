package com.constrivo.drop.ui.shared.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.model.DeviceCardUi
import com.constrivo.drop.ui.shared.model.Formats
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.presenter.DevicesUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.lastSeenText
import com.constrivo.drop.ui.shared.text.platformText
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.DropType
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
class DevicesCallbacks(
    val onShowCode: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onAutoAccept: (String, Boolean) -> Unit = { _, _ -> },
    val onForget: (String) -> Unit = {},
)

/** Devices tab (F‑G3, design §6): "Show my code" on top, then a card per trusted device. */
@Composable
internal fun DevicesTab(
    state: DevicesUi,
    callbacks: DevicesCallbacks,
) {
    LazyColumn(contentPadding = PaddingValues(DropDimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PrimaryButton(stringResource(Res.string.action_show_qr), callbacks.onShowCode) }
        if (state.devices.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.devices_empty),
                    style = LocalDropTypography.current.body,
                    color = LocalDropColors.current.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
        }
        items(state.devices, key = { it.id }) { device -> DeviceCard(device, callbacks) }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceCardUi,
    callbacks: DevicesCallbacks,
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
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(device.initials, device.avatarHash, DropDimens.avatarSmall, platform = device.platform)
            Column(Modifier.weight(1f)) {
                Text(device.name, style = type.bodyStrong, color = colors.text)
                Text(
                    stringResource(Res.string.summary_with_size, platformText(device.platform), lastSeenText(device.lastSeen)),
                    style = type.caption,
                    color = colors.textMuted,
                )
            }
        }
        val autoLabel = stringResource(Res.string.devices_auto_accept)
        Row(
            Modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(autoLabel, style = type.body, color = colors.text, modifier = Modifier.weight(1f))
            Switch(
                checked = device.autoAccept,
                onCheckedChange = { callbacks.onAutoAccept(device.id, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.primaryButton, checkedThumbColor = colors.onPrimaryButton),
                modifier = Modifier.semantics { contentDescription = autoLabel },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(stringResource(Res.string.devices_rename), { callbacks.onRename(device.id) })
            QuietButton(stringResource(Res.string.devices_forget), { callbacks.onForget(device.id) }, color = colors.dangerText)
        }
    }
}

@Immutable
class RenameCallbacks(
    val onSave: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
)

/** Rename a trusted device (F‑G3); the name must have a visible character. */
@Composable
fun RenameDeviceSheet(
    device: DeviceCardUi,
    callbacks: RenameCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    var text by rememberSaveable(device.id) { mutableStateOf(device.name) }
    SheetSurface(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetTitle(stringResource(Res.string.devices_rename_title))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accentText, cursorColor = colors.accentText),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(stringResource(Res.string.common_save), { callbacks.onSave(text) }, enabled = text.isNotBlank())
            QuietButton(
                stringResource(Res.string.common_cancel),
                callbacks.onCancel,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Stats tab (F‑G4, design §6): four tiles with tabular numerals (total moved, average speed, transfers this week,
 * hours saved vs Bluetooth), the transfers-per-week bar chart for the last 12 weeks in the accent colour, and
 * "Share stats card".
 */
@Composable
internal fun StatsTab(
    stats: StatsSnapshot,
    onShare: () -> Unit,
) {
    val none = stringResource(Res.string.stats_none)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DropDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(Res.string.stats_total), sizeText(stats.totalBytes), Modifier.weight(1f))
            StatTile(
                stringResource(Res.string.stats_average),
                stats.averageBytesPerSecond?.let { stringResource(Res.string.stats_speed, Formats.megabytesPerSecond(it)) } ?: none,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(Res.string.stats_week), stats.transfersThisWeek.toString(), Modifier.weight(1f))
            StatTile(
                stringResource(Res.string.stats_saved_label),
                stringResource(Res.string.stats_hours, Formats.hours(stats.hoursSaved)),
                Modifier.weight(1f),
            )
        }
        WeeklyChart(stats.weeks)
        QuietButton(stringResource(Res.string.stats_share), onShare, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Column(
        modifier
            .clip(DropShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, DropShapes.card)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(value, style = type.stat, color = colors.text, maxLines = 1)
        Text(label, style = type.caption, color = colors.textMuted)
    }
}

/**
 * Transfers per week, last 12 weeks, oldest on the left (design §6). One series in the accent colour, bars with
 * 4 dp rounded tops on a recessive baseline, gaps between bars; the axis labels and values wear text tokens. The
 * current week and the largest week are labelled; tapping a bar shows its count. Screen readers get every value.
 */
@Composable
private fun WeeklyChart(weeks: List<Int>) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val values = if (weeks.isEmpty()) List(StatsSnapshot.WEEKS) { 0 } else weeks
    val max = values.max().coerceAtLeast(1)
    var selected by remember(values) { mutableStateOf(values.lastIndex) }
    val description = stringResource(Res.string.a11y_chart, values.joinToString(", "))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DropShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, DropShapes.card)
            .padding(12.dp),
    ) {
        Text(
            stringResource(Res.string.stats_chart_title),
            style = type.bodyStrong,
            color = colors.text,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
        val measurer = rememberTextMeasurer()
        val valueStyle = type.caption.copy(fontFeatureSettings = DropType.TABULAR_NUMERALS, color = colors.text)
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(136.dp)
                .testTag(TestTags.STATS_CHART)
                .semantics { contentDescription = description }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val slot = size.width / values.size.toFloat()
                        selected = (offset.x / slot).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val gap = 4.dp.toPx()
            val slot = size.width / values.size
            val barWidth = (slot - gap).coerceAtLeast(1f)
            val radius = 4.dp.toPx()
            val labelRoom = 18.dp.toPx()
            val base = size.height - 1.dp.toPx()
            val plot = base - labelRoom
            drawLine(colors.outline, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (plot * v / max).coerceAtLeast(radius)
                val left = i * slot + gap / 2
                val path =
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left,
                                top = base - h,
                                right = left + barWidth,
                                bottom = base,
                                topLeftCornerRadius = CornerRadius(radius),
                                topRightCornerRadius = CornerRadius(radius),
                            ),
                        )
                    }
                drawPath(path, if (i == selected) colors.accent else colors.accent.copy(alpha = 0.75f))
            }
            if (values[selected] == 0) {
                val left = selected * slot + gap / 2
                drawRect(colors.accent, Offset(left, base - 2.dp.toPx()), Size(barWidth, 2.dp.toPx()))
            }
            // Direct label on the selected bar (the current week by default, or the one tapped): its count, in a text
            // token above the bar, kept inside the plot horizontally.
            val label = measurer.measure(values[selected].toString(), valueStyle)
            val barTop = base - if (values[selected] > 0) (plot * values[selected] / max).coerceAtLeast(radius) else 2.dp.toPx()
            val centre = selected * slot + slot / 2
            val lx = (centre - label.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - label.size.width))
            drawText(label, topLeft = Offset(lx, barTop - label.size.height - 2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                pluralStringResource(Res.plurals.stats_axis_weeks_ago, values.size - 1, values.size - 1),
                style = type.caption,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(Res.string.stats_axis_this_week), style = type.caption, color = colors.textMuted)
        }
    }
}
