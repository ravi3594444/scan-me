package com.constrivo.drop.ui.shared.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.GlyphBadge
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.BrowserApprovalUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.stringResource

@Immutable
class BrowserApprovalCallbacks(
    val onAllow: (requestId: Long) -> Unit = {},
    val onDeny: (requestId: Long) -> Unit = {},
)

/**
 * "Allow this computer?" (N15): shown when a browser opens the receive page, before `/files` is served. A later
 * browser gets "Another computer wants to connect". Shows the browser's address and, when recognised, its name.
 */
@Composable
fun BrowserApprovalSheet(
    state: BrowserApprovalUi,
    callbacks: BrowserApprovalCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphBadge(DropIcons.Desktop, tint = colors.accentText, background = colors.accentSoft)
                SheetTitle(
                    stringResource(if (state.browserNumber <= 1) Res.string.browser_title else Res.string.browser_title_again),
                    Modifier.weight(1f),
                )
            }
            Text(stringResource(Res.string.browser_body, state.remoteAddress), style = type.body, color = colors.text)
            state.browser?.let { b ->
                val line =
                    when {
                        b.browser != null && b.os != null -> stringResource(Res.string.browser_agent, b.browser, b.os)
                        else -> b.browser ?: b.os ?: b.raw
                    }
                line?.let { Text(it, style = type.caption, color = colors.textMuted) }
            }
            PrimaryButton(stringResource(Res.string.browser_allow), { callbacks.onAllow(state.requestId) })
            QuietButton(stringResource(Res.string.browser_deny), {
                callbacks.onDeny(state.requestId)
            }, color = colors.dangerText, modifier = Modifier.fillMaxWidth())
        }
    }
}
