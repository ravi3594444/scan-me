package com.constrivo.drop.ui.shared.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.GlyphBadge
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.InstallerWarningUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

/**
 * "Open this app installer?" (F‑D5): shown before a received APK, executable or script is opened, with the file name
 * and who sent it. Cancel is the primary (safe) action; "Open anyway" hands the file to the system.
 */
@Composable
fun InstallerWarningSheet(
    state: InstallerWarningUi,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    SheetSurface(modifier.testTag(TestTags.INSTALLER_WARNING)) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphBadge(DropIcons.App, tint = colors.warningText, background = colors.accentSoft)
                SheetTitle(stringResource(Res.string.installer_title), Modifier.weight(1f))
            }
            Text(state.fileName, style = type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            Text(
                state.senderName?.let {
                    stringResource(Res.string.installer_body, it)
                } ?: stringResource(Res.string.installer_body_unknown),
                style = type.body,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(Res.string.common_cancel), onCancel)
        Spacer(Modifier.height(12.dp))
        QuietButton(stringResource(Res.string.installer_open), onOpen, color = colors.dangerText, modifier = Modifier.fillMaxWidth())
    }
}
