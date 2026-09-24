package com.constrivo.drop.ui.shared.receive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.MoreTile
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.ThumbTile
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.Formats
import com.constrivo.drop.ui.shared.model.IncomingCardUi
import com.constrivo.drop.ui.shared.model.SenderPairingUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.summaryWithSizeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
class IncomingCallbacks(
    val onAccept: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onAlwaysAccept: (Boolean) -> Unit = {},
    val onSasConfirmed: () -> Unit = {},
)

/**
 * The incoming card (F‑D1, design §5.1): sender avatar, "**Dev** wants to send", "12 photos · 48 MB", the 30 s
 * countdown bar under the header, up to six previews and "+N", the trust caption ("Verified device" or "New device —
 * first time"), for a first-time sender the six-digit code with "Same code on both screens?" and "Yes, it matches",
 * then "Always accept from Dev", Accept and Decline. The content scrolls at large font sizes instead of clipping.
 */
@Composable
fun IncomingCard(
    state: IncomingCardUi,
    callbacks: IncomingCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    SheetSurface(modifier.testTag(TestTags.INCOMING_CARD)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(
                    state.senderInitials,
                    state.senderAvatarHash,
                    DropDimens.avatarSmall,
                    image = state.senderAvatar,
                    platform = state.senderPlatform,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        boldName(stringResource(Res.string.incoming_title, state.senderName), state.senderName),
                        style = type.title,
                        color = colors.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(summaryWithSizeText(state.summary, state.totalBytes), style = type.body, color = colors.textMuted)
                }
            }
            CountdownBar(state)
            TrustCaption(state.trusted)
            if (state.previews.isNotEmpty()) PreviewStrip(state)
            state.sas?.let { SasRow(it, state.sasConfirmed, callbacks.onSasConfirmed) }
            AlwaysAccept(state, callbacks.onAlwaysAccept)
            PrimaryButton(stringResource(Res.string.incoming_accept), callbacks.onAccept)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QuietButton(stringResource(Res.string.incoming_decline), callbacks.onDecline, color = colors.dangerText)
            }
        }
    }
}

/** "{name} wants to send" with the name in bold wherever the translation puts it. */
private fun boldName(
    text: String,
    name: String,
): AnnotatedString {
    val at = text.indexOf(name)
    if (at < 0 || name.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, at))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(name)
        pop()
        append(text.substring(at + name.length))
    }
}

/** The thin 30 s countdown bar (design §5.1), animating linearly between the presenter's one-second ticks. */
@Composable
private fun CountdownBar(state: IncomingCardUi) {
    val colors = LocalDropColors.current
    val fraction by animateFloatAsState(state.remainingFraction, tween(1_000, easing = LinearEasing))
    val seconds = ((state.remainingMillis + 999) / 1000).toInt()
    val description = pluralStringResource(Res.plurals.a11y_incoming_seconds, seconds, seconds)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.outline)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(colors.accent))
    }
}

@Composable
private fun TrustCaption(trusted: Boolean) {
    val colors = LocalDropColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (trusted) DropIcons.Shield else DropIcons.Info,
            contentDescription = null,
            tint = if (trusted) colors.successText else colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (trusted) Res.string.incoming_verified else Res.string.incoming_new_device),
            style = LocalDropTypography.current.caption,
            color = if (trusted) colors.successText else colors.textMuted,
        )
    }
}

/** Up to six previews, then "+N" (design §5.1). */
@Composable
private fun PreviewStrip(state: IncomingCardUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val tile = 44.dp
        for (thumb in state.previews) ThumbTile(thumb, tile)
        if (state.morePreviews > 0) MoreTile(stringResource(Res.string.incoming_more, state.morePreviews), tile)
    }
}

/** The six-digit code and "Same code on both screens?" (F‑B3), shown as soon as the handshake result exists. */
@Composable
private fun SasRow(
    code: String,
    confirmed: Boolean,
    onConfirm: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accentSoft)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.pair_code), style = type.body, color = colors.text, textAlign = TextAlign.Center)
        Text(Formats.sas(code), style = sasStyle(32.dp), color = colors.text, maxLines = 1, softWrap = false)
        if (confirmed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = DropDimens.minTouch)) {
                Icon(DropIcons.Check, contentDescription = null, tint = colors.successText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.pair_confirmed), style = type.body, color = colors.successText)
            }
        } else {
            QuietButton(stringResource(Res.string.pair_confirm), onConfirm)
        }
    }
}

@Composable
private fun AlwaysAccept(
    state: IncomingCardUi,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalDropColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = DropDimens.minTouch)
                .toggleable(value = state.alwaysAccept, enabled = state.canAlwaysAccept, role = Role.Checkbox) { onChange(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state.alwaysAccept,
            onCheckedChange = null,
            enabled = state.canAlwaysAccept,
            colors = CheckboxDefaults.colors(checkedColor = colors.primaryButton, checkmarkColor = colors.onPrimaryButton),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(Res.string.incoming_always, state.senderName),
            style = LocalDropTypography.current.body,
            color = if (state.canAlwaysAccept) colors.text else colors.textMuted,
        )
    }
}

/**
 * The six-digit code in a display size that grows with the font scale only up to [SAS_MAX_SCALE]: it is already large
 * text, and at 200% the six digits would no longer fit one line on a phone, which makes them hard to compare.
 */
@Composable
private fun sasStyle(base: Dp): TextStyle {
    val density = LocalDensity.current
    val size = with(density) { (base * minOf(density.fontScale, SAS_MAX_SCALE)).toSp() }
    return LocalDropTypography.current.stat.copy(fontSize = size, letterSpacing = 2.sp)
}

private const val SAS_MAX_SCALE = 1.3f

@Immutable
class SenderPairingCallbacks(
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/** The sender's side of first-time pairing (design §5.1: the code shows on both screens). */
@Composable
fun SenderPairingSheet(
    state: SenderPairingUi,
    callbacks: SenderPairingCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.pair_sender_title, state.peerName),
                style = type.title,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.pair_code), style = type.body, color = colors.textMuted, textAlign = TextAlign.Center)
            Text(Formats.sas(state.code), style = sasStyle(36.dp), color = colors.text, maxLines = 1, softWrap = false)
            PrimaryButton(stringResource(Res.string.pair_confirm), callbacks.onConfirm)
            QuietButton(stringResource(Res.string.common_cancel), callbacks.onCancel, color = colors.dangerText)
        }
    }
}
