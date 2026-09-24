package com.constrivo.drop.ui.shared.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.SettingsUi
import com.constrivo.drop.ui.shared.radar.VisibilityOptions
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

@Immutable
class SettingsCallbacks(
    val onVisibility: (Visibility) -> Unit = {},
    val onPrefer5Ghz: (Boolean) -> Unit = {},
    val onKeepScreenAwake: (Boolean) -> Unit = {},
    val onBundleSmallFiles: (Boolean) -> Unit = {},
    val onPickSaveLocation: () -> Unit = {},
    val onClearPartials: () -> Unit = {},
    val onNickname: (String) -> Unit = {},
    val onPickAvatar: () -> Unit = {},
    val onRemoveAvatar: () -> Unit = {},
    val onLanguage: (AppLanguage) -> Unit = {},
    val onCrashReports: (Boolean) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
)

/**
 * Settings (F‑G5, design §6): Visibility · Speed · Storage · Profile · Privacy · Accessibility · About. Every control
 * applies at once through [callbacks] (no Save, no restart); the nickname is saved from its field's Done action or
 * Save button, so the beacon is not re-advertised on every keystroke.
 */
@Composable
internal fun SettingsTab(
    state: SettingsUi,
    callbacks: SettingsCallbacks,
) {
    val v = state.values
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DropDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(Res.string.settings_visibility)) {
            VisibilityOptions(v.visibility, callbacks.onVisibility)
        }
        Section(stringResource(Res.string.settings_speed)) {
            SwitchRow(
                stringResource(Res.string.settings_prefer_5ghz),
                stringResource(Res.string.settings_prefer_5ghz_desc),
                v.prefer5Ghz,
                callbacks.onPrefer5Ghz,
            )
            SwitchRow(
                stringResource(Res.string.settings_keep_awake),
                stringResource(Res.string.settings_keep_awake_desc),
                v.keepScreenAwake,
                callbacks.onKeepScreenAwake,
            )
            SwitchRow(
                stringResource(Res.string.settings_bundle),
                stringResource(Res.string.settings_bundle_desc),
                v.bundleSmallFiles,
                callbacks.onBundleSmallFiles,
            )
        }
        Section(stringResource(Res.string.settings_storage)) {
            Row(Modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_save_location), style = type.body, color = colors.text)
                    Text(
                        v.saveLocationLabel ?: stringResource(Res.string.settings_save_location_default),
                        style = type.caption,
                        color = colors.textMuted,
                    )
                }
                QuietButton(stringResource(Res.string.settings_change), callbacks.onPickSaveLocation)
            }
            Column {
                Text(stringResource(Res.string.settings_clear_partials_desc), style = type.caption, color = colors.textMuted)
                QuietButton(
                    stringResource(Res.string.settings_clear_partials),
                    callbacks.onClearPartials,
                    enabled = !state.clearingPartials,
                )
                state.lastClear?.let {
                    Text(
                        stringResource(Res.string.settings_clear_partials_done, sizeText(it.bytesFreed)),
                        style = type.caption,
                        color = colors.successText,
                    )
                }
            }
        }
        Section(stringResource(Res.string.settings_profile)) {
            NicknameField(v.nickname, callbacks.onNickname)
            Row(
                Modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(Avatars.initials(v.nickname), Avatars.hash(v.nickname), 40.dp, image = v.avatar)
                Text(stringResource(Res.string.settings_avatar), style = type.body, color = colors.text, modifier = Modifier.weight(1f))
                QuietButton(stringResource(Res.string.settings_change), callbacks.onPickAvatar)
                if (v.avatar != null) QuietButton(stringResource(Res.string.settings_avatar_initials), callbacks.onRemoveAvatar)
            }
            Text(stringResource(Res.string.settings_language), style = type.body, color = colors.text)
            Column(Modifier.selectableGroup()) {
                for (language in AppLanguage.entries) {
                    val label =
                        stringResource(
                            when (language) {
                                AppLanguage.SYSTEM -> Res.string.settings_language_system
                                AppLanguage.ENGLISH -> Res.string.language_en
                                AppLanguage.HINDI -> Res.string.language_hi
                            },
                        )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = DropDimens.minTouch)
                            .selectable(selected = v.language == language, role = Role.RadioButton) { callbacks.onLanguage(language) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = v.language == language,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.accentText),
                        )
                        Text(label, style = type.body, color = colors.text, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        Section(stringResource(Res.string.settings_privacy)) {
            Text(stringResource(Res.string.settings_privacy_statement), style = type.bodyStrong, color = colors.text)
            Text(stringResource(Res.string.privacy_statement), style = type.body, color = colors.text)
            Text(stringResource(Res.string.privacy_internet), style = type.caption, color = colors.textMuted)
            SwitchRow(
                stringResource(Res.string.settings_crash_reports),
                stringResource(Res.string.settings_crash_reports_desc),
                v.crashReports,
                callbacks.onCrashReports,
            )
        }
        Section(stringResource(Res.string.settings_accessibility)) {
            SwitchRow(stringResource(Res.string.settings_haptics), null, v.haptics, callbacks.onHaptics)
        }
        Section(stringResource(Res.string.settings_about)) {
            Text(stringResource(Res.string.app_name), style = type.body, color = colors.text)
            Text(stringResource(Res.string.settings_version, v.appVersion), style = type.caption, color = colors.textMuted)
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDropColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = LocalDropTypography.current.sectionHeader,
            color = colors.textMuted,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(DropShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, DropShapes.card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = type.body, color = colors.text)
            description?.let { Text(it, style = type.caption, color = colors.textMuted) }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primaryButton, checkedThumbColor = colors.onPrimaryButton),
        )
    }
}

@Composable
private fun NicknameField(
    nickname: String,
    onSave: (String) -> Unit,
) {
    val colors = LocalDropColors.current
    var text by rememberSaveable(nickname) { mutableStateOf(nickname) }
    val changed = text.trim() != nickname && text.isNotBlank()
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.settings_nickname)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (changed) onSave(text) }),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentText,
                    focusedLabelColor = colors.accentText,
                    cursorColor = colors.accentText,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (changed) QuietButton(stringResource(Res.string.common_save), { onSave(text) })
    }
}
