package com.constrivo.drop.ui.shared.radar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.CancelConfirmUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.deviceNameText
import com.constrivo.drop.ui.shared.text.ringText
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.text.visibilityDescription
import com.constrivo.drop.ui.shared.text.visibilityText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

/** The visibility choices (F‑A5, design §2 chip): each applies at once. */
@Composable
fun VisibilitySheet(
    current: Visibility,
    onSelect: (Visibility) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        SheetTitle(stringResource(Res.string.visibility_title), Modifier.padding(bottom = 8.dp))
        VisibilityOptions(current, onSelect)
        QuietButton(
            stringResource(Res.string.common_close),
            onClose,
            color = LocalDropColors.current.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The four visibility modes as a radio group (also used by Settings → Visibility). */
@Composable
fun VisibilityOptions(
    current: Visibility,
    onSelect: (Visibility) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Column(modifier.selectableGroup()) {
        for (mode in listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY, Visibility.HIDDEN)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(
                    selected = mode == current,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accentText),
                )
                Column(Modifier.weight(1f)) {
                    Text(visibilityText(mode), style = LocalDropTypography.current.body, color = colors.text)
                    Text(visibilityDescription(mode), style = LocalDropTypography.current.caption, color = colors.textMuted)
                }
            }
        }
    }
}

/** The list behind "+N more" (design §3.2): every device on the radar, tap to send. */
@Composable
fun OverflowSheet(
    devices: List<BubbleUi>,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        SheetTitle(stringResource(Res.string.more_devices_title), Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(devices, key = { it.key }) { d ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.a11y_action_send)) { onSelect(d.key) }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(d.initials, d.avatarHash, 40.dp, platform = d.platform)
                    Column(Modifier.weight(1f)) {
                        Text(deviceNameText(d.name), style = LocalDropTypography.current.body, color = colors.text)
                        Text(ringText(d.ring, d.lanOnly), style = LocalDropTypography.current.caption, color = colors.textMuted)
                    }
                    if (d.trusted) Icon(DropIcons.Shield, contentDescription = null, tint = colors.successText)
                }
            }
        }
        QuietButton(stringResource(Res.string.common_close), onClose, color = colors.textMuted, modifier = Modifier.fillMaxWidth())
    }
}

/** Cancel past 100 MB (design §4.2). */
@Composable
fun CancelConfirmSheet(
    state: CancelConfirmUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmSheet(
        title =
            stringResource(
                if (state.direction == Direction.SEND) Res.string.cancel_send_title else Res.string.cancel_receive_title,
                state.peerName,
            ),
        body = stringResource(Res.string.cancel_body, sizeText(state.bytesDone), sizeText(state.bytesTotal)),
        confirm = stringResource(Res.string.cancel_confirm),
        dismiss = stringResource(Res.string.cancel_keep),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        modifier = modifier,
    )
}

/** A two-button confirmation sheet (cancel, forget, clear history, clear partial files). */
@Composable
fun ConfirmSheet(
    title: String,
    body: String?,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = LocalDropColors.current
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetTitle(title)
            body?.let { Text(it, style = LocalDropTypography.current.body, color = colors.textMuted) }
            PrimaryButton(dismiss, onDismiss)
            QuietButton(
                confirm,
                onConfirm,
                color = if (destructive) colors.dangerText else colors.accentText,
                modifier = Modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch),
            )
        }
    }
}
