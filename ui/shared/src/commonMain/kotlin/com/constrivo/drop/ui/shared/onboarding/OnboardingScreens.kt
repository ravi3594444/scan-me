package com.constrivo.drop.ui.shared.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.GlyphBadge
import com.constrivo.drop.ui.shared.components.PrimaryButton
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.OnboardingUi
import com.constrivo.drop.ui.shared.model.PermissionPromptUi
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
class WelcomeCallbacks(
    val onNicknameChange: (String) -> Unit = {},
    val onPickAvatar: () -> Unit = {},
    val onStart: () -> Unit = {},
)

/**
 * Onboarding step 1 (design §7, F‑I3, F‑J4): one sentence, the nickname prefilled from the device name, an optional
 * avatar, "Start", and the privacy statement.
 */
@Composable
fun WelcomeScreen(
    state: OnboardingUi,
    callbacks: WelcomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .testTag(TestTags.ONBOARDING),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(Res.string.app_name),
                style = type.title,
                color = colors.accentText,
                modifier =
                    Modifier.semantics {
                        heading()
                    },
            )
            Text(stringResource(Res.string.welcome_sentence), style = type.title, color = colors.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Avatar(Avatars.initials(state.nickname), Avatars.hash(state.nickname), 88.dp, image = state.avatar)
            QuietButton(
                stringResource(if (state.avatar == null) Res.string.welcome_avatar else Res.string.welcome_avatar_change),
                callbacks.onPickAvatar,
            )
            OutlinedTextField(
                value = state.nickname,
                onValueChange = callbacks.onNicknameChange,
                label = { Text(stringResource(Res.string.welcome_nickname)) },
                supportingText = {
                    Text(stringResource(if (state.nicknameValid) Res.string.welcome_nickname_hint else Res.string.welcome_nickname_error))
                },
                isError = !state.nicknameValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentText,
                        focusedLabelColor = colors.accentText,
                        cursorColor = colors.accentText,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(stringResource(Res.string.welcome_start), callbacks.onStart, enabled = state.nicknameValid)
            Text(stringResource(Res.string.privacy_statement), style = type.caption, color = colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}

@Immutable
class BrandStepCallbacks(
    val onOpenSettings: () -> Unit = {},
    val onSkip: () -> Unit = {},
)

/** Onboarding step 3 on Xiaomi, Vivo, Oppo and Samsung (design §7, F‑I2): one button to the exact screen, "Skip for now". */
@Composable
fun BrandStepScreen(
    brand: OemBrand,
    callbacks: BrandStepCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Box(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing).testTag(TestTags.ONBOARDING)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            GlyphBadge(DropIcons.Battery, tint = colors.accentText, background = colors.accentSoft, size = 72.dp)
            Text(
                stringResource(Res.string.brand_title),
                style = type.title,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.semantics {
                        heading()
                    },
            )
            Text(stringResource(Res.string.brand_body), style = type.body, color = colors.text, textAlign = TextAlign.Center)
            Text(stringResource(brandSteps(brand)), style = type.body, color = colors.textMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(stringResource(Res.string.brand_open), callbacks.onOpenSettings)
            QuietButton(stringResource(Res.string.brand_skip), callbacks.onSkip, color = colors.textMuted)
        }
    }
}

private fun brandSteps(brand: OemBrand): StringResource =
    when (brand) {
        OemBrand.XIAOMI -> Res.string.brand_steps_xiaomi
        OemBrand.VIVO -> Res.string.brand_steps_vivo
        OemBrand.OPPO -> Res.string.brand_steps_oppo
        OemBrand.SAMSUNG -> Res.string.brand_steps_samsung
    }

@Immutable
class PermissionSheetCallbacks(
    val onContinue: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The just-in-time explainer (F‑I1, design §7 step 2): a title and one line of why, then "Continue" (the system dialog)
 * or "Not now"; after a permanent denial the recovery wording and "Open settings".
 */
@Composable
fun PermissionSheet(
    state: PermissionPromptUi,
    callbacks: PermissionSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val (title, reason) = permissionCopy(state.permission)
    SheetSurface(modifier.testTag(TestTags.SHEET)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphBadge(permissionIcon(state.permission), tint = colors.accentText, background = colors.accentSoft)
                SheetTitle(stringResource(title), Modifier.weight(1f))
            }
            Text(stringResource(reason), style = type.body, color = colors.text)
            if (state.blocked) Text(stringResource(Res.string.perm_blocked), style = type.body, color = colors.textMuted)
            PrimaryButton(
                stringResource(if (state.blocked) Res.string.perm_open_settings else Res.string.perm_continue),
                callbacks.onContinue,
            )
            QuietButton(
                stringResource(Res.string.perm_not_now),
                callbacks.onDismiss,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
    }
}

private fun permissionCopy(permission: DropPermission): Pair<StringResource, StringResource> =
    when (permission) {
        DropPermission.NEARBY -> Res.string.perm_nearby_title to Res.string.perm_nearby_reason
        DropPermission.LOCATION_FOR_WIFI_DIRECT -> Res.string.perm_location_title to Res.string.perm_location_reason
        DropPermission.MEDIA -> Res.string.perm_media_title to Res.string.perm_media_reason
        DropPermission.NOTIFICATIONS -> Res.string.perm_notifications_title to Res.string.perm_notifications_reason
        DropPermission.CAMERA -> Res.string.perm_camera_title to Res.string.perm_camera_reason
        DropPermission.BATTERY -> Res.string.perm_battery_title to Res.string.perm_battery_reason
    }

private fun permissionIcon(permission: DropPermission) =
    when (permission) {
        DropPermission.NEARBY -> DropIcons.Radar
        DropPermission.LOCATION_FOR_WIFI_DIRECT -> DropIcons.Wifi
        DropPermission.MEDIA -> DropIcons.Image
        DropPermission.NOTIFICATIONS -> DropIcons.Bell
        DropPermission.CAMERA -> DropIcons.Camera
        DropPermission.BATTERY -> DropIcons.Battery
    }
