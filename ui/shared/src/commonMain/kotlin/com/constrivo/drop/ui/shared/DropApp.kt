package com.constrivo.drop.ui.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.ui.shared.components.SheetHost
import com.constrivo.drop.ui.shared.components.SheetSpec
import com.constrivo.drop.ui.shared.dashboard.DashboardCallbacks
import com.constrivo.drop.ui.shared.dashboard.DashboardScreen
import com.constrivo.drop.ui.shared.dashboard.DashboardUiState
import com.constrivo.drop.ui.shared.dashboard.DevicesCallbacks
import com.constrivo.drop.ui.shared.dashboard.HistoryCallbacks
import com.constrivo.drop.ui.shared.dashboard.HistoryDetailSheet
import com.constrivo.drop.ui.shared.dashboard.LiveCallbacks
import com.constrivo.drop.ui.shared.dashboard.RenameCallbacks
import com.constrivo.drop.ui.shared.dashboard.RenameDeviceSheet
import com.constrivo.drop.ui.shared.dashboard.SettingsCallbacks
import com.constrivo.drop.ui.shared.model.OnboardingStep
import com.constrivo.drop.ui.shared.onboarding.BrandStepCallbacks
import com.constrivo.drop.ui.shared.onboarding.BrandStepScreen
import com.constrivo.drop.ui.shared.onboarding.PermissionSheet
import com.constrivo.drop.ui.shared.onboarding.PermissionSheetCallbacks
import com.constrivo.drop.ui.shared.onboarding.WelcomeCallbacks
import com.constrivo.drop.ui.shared.onboarding.WelcomeScreen
import com.constrivo.drop.ui.shared.platform.DropHaptics
import com.constrivo.drop.ui.shared.platform.LocalDropHaptics
import com.constrivo.drop.ui.shared.presenter.DayCalendar
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.DropDependencies
import com.constrivo.drop.ui.shared.presenter.RadarSheet
import com.constrivo.drop.ui.shared.presenter.Screen
import com.constrivo.drop.ui.shared.radar.CancelConfirmSheet
import com.constrivo.drop.ui.shared.radar.ConfirmSheet
import com.constrivo.drop.ui.shared.radar.OverflowSheet
import com.constrivo.drop.ui.shared.radar.RadarCallbacks
import com.constrivo.drop.ui.shared.radar.RadarScreen
import com.constrivo.drop.ui.shared.radar.VisibilitySheet
import com.constrivo.drop.ui.shared.receive.BrowserApprovalCallbacks
import com.constrivo.drop.ui.shared.receive.BrowserApprovalSheet
import com.constrivo.drop.ui.shared.receive.IncomingCallbacks
import com.constrivo.drop.ui.shared.receive.IncomingCard
import com.constrivo.drop.ui.shared.receive.InstallerWarningSheet
import com.constrivo.drop.ui.shared.receive.SenderPairingCallbacks
import com.constrivo.drop.ui.shared.receive.SenderPairingSheet
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.send.FilePickerCallbacks
import com.constrivo.drop.ui.shared.send.FilePickerSheet
import com.constrivo.drop.ui.shared.send.ScanQrCallbacks
import com.constrivo.drop.ui.shared.send.ScanQrScreen
import com.constrivo.drop.ui.shared.send.ShowQrCallbacks
import com.constrivo.drop.ui.shared.send.ShowQrSheet
import com.constrivo.drop.ui.shared.text.LanguageScope
import com.constrivo.drop.ui.shared.theme.DropTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import org.jetbrains.compose.resources.stringResource

/**
 * Root composable both apps show before their engines are wired: an in-memory backend ([DropDependencies.inMemory])
 * with an empty radar. App layers create a [DropAppController] over their own [DropDependencies] and call the
 * overload that takes it.
 */
@Composable
fun DropApp() {
    // A failure in one port must not end the app (the app layers give their own scopes a handler too).
    val scope = rememberCoroutineScope { CoroutineExceptionHandler { _, e -> e.printStackTrace() } }
    val controller =
        remember {
            DropAppController(scope, DropDependencies.inMemory(wallClock = SystemWallClock, calendar = DayCalendar.System))
        }
    DropApp(controller)
}

/**
 * The app (design §2): onboarding, the radar with its sheets, the dashboard and the scanner, with the global sheets
 * (permission explainer, "Allow this computer?", the incoming card) above whatever screen is showing. One sheet at a
 * time, most urgent first (design §1: one elevation level).
 *
 * @param camera the scanner's camera preview (platform; CameraX + ZXing on Android).
 * @param reducedMotion the system's reduced-motion setting (design §3.3, §11).
 * @param haptics the platform's haptic hooks (design §4.2, §4.4).
 * @param applyLanguage apply Settings → Language here ([com.constrivo.drop.ui.shared.text.AppLocale]); false when the
 *   host applies it through the platform (Android 13+ `LocaleManager`).
 */
@Composable
fun DropApp(
    controller: DropAppController,
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    haptics: DropHaptics = DropHaptics.None,
    fontFamily: FontFamily = FontFamily.Default,
    applyLanguage: Boolean = true,
    camera: @Composable () -> Unit = {},
) {
    val language by controller.language.collectAsState()
    LanguageScope(if (applyLanguage) language else null) {
        DropTheme(dark = dark, fontFamily = fontFamily, reducedMotion = reducedMotion) {
            CompositionLocalProvider(LocalDropHaptics provides haptics) {
                val screen by controller.screen.collectAsState()
                val globalSheet = globalSheet(controller)
                val localSheet = if (globalSheet == null) screenSheet(controller, screen) else null
                val sheet = globalSheet ?: localSheet
                SheetHost(sheet = sheet, onDismiss = { dismiss(controller, sheet?.key) }, modifier = modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.ONBOARDING -> OnboardingHost(controller)
                        Screen.RADAR -> RadarHost(controller)
                        Screen.DASHBOARD -> DashboardHost(controller)
                        Screen.SCAN -> ScanHost(controller, camera)
                    }
                }
            }
        }
    }
}

/** The sheets [DropApp] can show, most urgent first in [globalSheet]. */
private enum class Sheet {
    PERMISSION,
    BROWSER,
    INCOMING,
    INSTALLER,
    PAIRING,
    CANCEL,
    PICKER,
    QR,
    VISIBILITY,
    OVERFLOW,
    HISTORY_DETAIL,
    HISTORY_CLEAR,
    RENAME,
    FORGET,
    CLEAR_PARTIALS,
}

/** The picker's share of the height (design §4.1). */
private const val PICKER_HEIGHT = 0.8f

private fun dismiss(
    c: DropAppController,
    key: Any?,
) {
    when (key) {
        Sheet.PERMISSION -> c.permissions.onDismiss()

        // an explicit answer is required; the server withdraws the prompt on its own timeout
        Sheet.BROWSER -> Unit

        // the countdown decides; Decline is one tap away
        Sheet.INCOMING -> Unit

        Sheet.INSTALLER -> c.dismissInstallerWarning()

        // put aside without trusting the device ("Not now"); the bubble brings it back
        Sheet.PAIRING -> c.radar.state.value.senderPairing?.let { c.pairLater(it.transferId) }

        Sheet.CANCEL -> c.radar.dismissCancel()

        Sheet.HISTORY_DETAIL -> c.dashboard.history.closeDetail()

        Sheet.HISTORY_CLEAR -> c.dashboard.history.dismissClear()

        Sheet.RENAME -> c.dashboard.devices.cancelRename()

        Sheet.FORGET -> c.dashboard.devices.cancelForget()

        Sheet.CLEAR_PARTIALS -> c.dashboard.settings.dismissClearPartials()

        else -> c.closeSheet()
    }
}

/** Sheets that can appear over any screen, most urgent first. */
@Composable
private fun globalSheet(c: DropAppController): SheetSpec? {
    val permission by c.permissions.state.collectAsState()
    val browser by c.browserApproval.state.collectAsState()
    val incoming by c.incoming.state.collectAsState()
    val radar by c.radar.state.collectAsState()
    val installer by c.installerWarning.collectAsState()
    permission?.let { p ->
        return SheetSpec(Sheet.PERMISSION) {
            PermissionSheet(p, PermissionSheetCallbacks(onContinue = c.permissions::onContinue, onDismiss = c.permissions::onDismiss))
        }
    }
    browser?.let { b ->
        return SheetSpec(Sheet.BROWSER) {
            BrowserApprovalSheet(b, BrowserApprovalCallbacks(onAllow = c.browserApproval::allow, onDeny = c.browserApproval::deny))
        }
    }
    incoming?.let { card ->
        return SheetSpec(Sheet.INCOMING) {
            IncomingCard(
                card,
                IncomingCallbacks(
                    onAccept = c.incoming::onAccept,
                    onDecline = c.incoming::onDecline,
                    onAlwaysAccept = c.incoming::onAlwaysAcceptChanged,
                    onSasConfirmed = c.incoming::onSasConfirmed,
                ),
            )
        }
    }
    installer?.let { warning ->
        return SheetSpec(Sheet.INSTALLER) {
            InstallerWarningSheet(warning, onOpen = c::confirmOpenInstaller, onCancel = c::dismissInstallerWarning)
        }
    }
    radar.senderPairing?.let { pairing ->
        return SheetSpec(Sheet.PAIRING) {
            SenderPairingSheet(
                pairing,
                SenderPairingCallbacks(
                    onConfirm = { c.radar.confirmPairing(pairing.transferId) },
                    onCancel = { c.radar.onCancelTapped(pairing.transferId) },
                    onLater = { c.pairLater(pairing.transferId) },
                ),
            )
        }
    }
    radar.cancelConfirm?.let { confirm ->
        return SheetSpec(Sheet.CANCEL) { CancelConfirmSheet(confirm, c.radar::confirmCancel, c.radar::dismissCancel) }
    }
    return null
}

/** The sheet of the current screen, if any. */
@Composable
private fun screenSheet(
    c: DropAppController,
    screen: Screen,
): SheetSpec? =
    when (screen) {
        Screen.RADAR -> radarSheet(c)
        Screen.DASHBOARD -> dashboardSheet(c)
        else -> null
    }

/** The file picker, over the radar (a bubble, the browser page) or the dashboard (the Live tab's "Add files"). */
@Composable
private fun pickerSheet(c: DropAppController): SheetSpec? {
    val picker by c.picker.state.collectAsState()
    return picker?.let { p ->
        SheetSpec(Sheet.PICKER, heightFraction = PICKER_HEIGHT) {
            FilePickerSheet(
                p,
                FilePickerCallbacks(
                    onTab = c.picker::selectTab,
                    onToggle = c.picker::toggle,
                    onBrowseFiles = { c.platformBrowseFiles() },
                    onAllowPhotos = { c.askMediaAgain() },
                    onSend = c::sendPicked,
                    onClose = c::closeSheet,
                    onLoadMore = c.picker::loadMore,
                ),
                Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun radarSheet(c: DropAppController): SheetSpec? {
    val sheet by c.radarSheet.collectAsState()
    val qr by c.showQr.state.collectAsState()
    val radar by c.radar.state.collectAsState()
    return when (sheet) {
        RadarSheet.PICKER -> {
            pickerSheet(c)
        }

        RadarSheet.SHOW_QR -> {
            qr?.let { q ->
                SheetSpec(Sheet.QR) {
                    ShowQrSheet(
                        q,
                        ShowQrCallbacks(
                            onStartBrowserShare = c::startBrowserShare,
                            onToggleBrowserCode = c.showQr::toggleBrowserCode,
                            onClose = c::closeSheet,
                        ),
                    )
                }
            }
        }

        RadarSheet.VISIBILITY -> {
            SheetSpec(Sheet.VISIBILITY) {
                VisibilitySheet(radar.visibility.mode, onSelect = { c.dashboard.settings.setVisibility(it) }, onClose = c::closeSheet)
            }
        }

        RadarSheet.OVERFLOW -> {
            SheetSpec(Sheet.OVERFLOW) {
                OverflowSheet(
                    radar.bubbles,
                    onSelect = { key ->
                        c.closeSheet()
                        c.onBubbleTap(key)
                    },
                    onClose = c::closeSheet,
                )
            }
        }

        null -> {
            null
        }
    }
}

@Composable
private fun dashboardSheet(c: DropAppController): SheetSpec? {
    val sheet by c.radarSheet.collectAsState()
    if (sheet == RadarSheet.PICKER) return pickerSheet(c)
    val history by c.dashboard.history.state.collectAsState()
    val devices by c.dashboard.devices.state.collectAsState()
    val confirmPartials by c.dashboard.settings.confirmClear.collectAsState()
    history.detail?.let { d ->
        return SheetSpec(Sheet.HISTORY_DETAIL) { HistoryDetailSheet(d, historyCallbacks(c)) }
    }
    if (history.confirmClear) {
        return SheetSpec(Sheet.HISTORY_CLEAR) {
            ConfirmSheet(
                title = stringResource(Res.string.history_clear_title),
                body = stringResource(Res.string.history_clear_body),
                confirm = stringResource(Res.string.history_clear_confirm),
                dismiss = stringResource(Res.string.common_cancel),
                onConfirm = c.dashboard.history::confirmClear,
                onDismiss = c.dashboard.history::dismissClear,
                destructive = true,
            )
        }
    }
    devices.renaming?.let { d ->
        return SheetSpec(Sheet.RENAME) {
            RenameDeviceSheet(
                d,
                RenameCallbacks(onSave = {
                    c.dashboard.devices.saveRename(it)
                }, onCancel = c.dashboard.devices::cancelRename),
            )
        }
    }
    devices.forgetting?.let { d ->
        return SheetSpec(Sheet.FORGET) {
            ConfirmSheet(
                title = stringResource(Res.string.devices_forget_title, d.name),
                body = stringResource(Res.string.devices_forget_body),
                confirm = stringResource(Res.string.devices_forget),
                dismiss = stringResource(Res.string.common_cancel),
                onConfirm = c.dashboard.devices::confirmForget,
                onDismiss = c.dashboard.devices::cancelForget,
                destructive = true,
            )
        }
    }
    if (confirmPartials) {
        return SheetSpec(Sheet.CLEAR_PARTIALS) {
            ConfirmSheet(
                title = stringResource(Res.string.settings_clear_partials_title),
                body = stringResource(Res.string.settings_clear_partials_body),
                confirm = stringResource(Res.string.settings_clear_partials_confirm),
                dismiss = stringResource(Res.string.common_cancel),
                onConfirm = c.dashboard.settings::confirmClearPartials,
                onDismiss = c.dashboard.settings::dismissClearPartials,
                destructive = true,
            )
        }
    }
    return null
}

private fun historyCallbacks(c: DropAppController) =
    HistoryCallbacks(
        onOpen = c.dashboard.history::openDetail,
        onClear = c.dashboard.history::askClear,
        onCloseDetail = c.dashboard.history::closeDetail,
        onOpenFile = c::openHistoryFile,
        onResend = c.dashboard.history::resend,
    )

@Composable
private fun OnboardingHost(c: DropAppController) {
    val presenter = c.onboarding ?: return
    val state by presenter.state.collectAsState()
    if (state.step == OnboardingStep.BRAND && state.brand != null) {
        BrandStepScreen(state.brand!!, BrandStepCallbacks(onOpenSettings = presenter::onOpenBrandSettings, onSkip = presenter::onSkipBrand))
    } else {
        WelcomeScreen(
            state,
            WelcomeCallbacks(
                onNicknameChange = presenter::onNicknameChanged,
                onPickAvatar = presenter::pickAvatar,
                onStart = presenter::onStart,
            ),
        )
    }
}

@Composable
private fun RadarHost(c: DropAppController) {
    val state by c.radar.state.collectAsState()
    RadarScreen(
        state,
        RadarCallbacks(
            onBubbleTap = c::onBubbleTap,
            onCancelTap = c.radar::onCancelTapped,
            onOverflowTap = { c.openSheet(RadarSheet.OVERFLOW) },
            onScan = c::openScanner,
            onShowCode = { c.openSheet(RadarSheet.SHOW_QR) },
            onDashboard = { c.openDashboard() },
            onVisibilityTap = { c.openSheet(RadarSheet.VISIBILITY) },
            onNoticeAction = c::onNoticeAction,
            onClearAttachment = c.radar::clearAttachment,
            onTrayOpen = { c.platformOpenReceived(it) },
            onTrayShare = { c.platformShareReceived(it) },
            onOpenFolder = { c.platformOpenFolder() },
        ),
    )
}

@Composable
private fun DashboardHost(c: DropAppController) {
    val d = c.dashboard
    val tab by d.tab.collectAsState()
    val live by d.live.state.collectAsState()
    val history by d.history.state.collectAsState()
    val devices by d.devices.state.collectAsState()
    val stats by d.stats.state.collectAsState()
    val settings by d.settings.state.collectAsState()
    DashboardScreen(
        DashboardUiState(tab, live, history, devices, stats, settings),
        DashboardCallbacks(
            onBack = { c.back() },
            onTab = d::selectTab,
            live =
                LiveCallbacks(
                    onPause = d.live::pause,
                    onResume = d.live::resume,
                    onCancel = c::cancelTransfer,
                    onAddFiles = c::openAddFiles,
                ),
            history = historyCallbacks(c),
            devices =
                DevicesCallbacks(
                    onShowCode = {
                        c.back()
                        c.openSheet(RadarSheet.SHOW_QR)
                    },
                    onRename = d.devices::startRename,
                    onAutoAccept = d.devices::setAutoAccept,
                    onForget = d.devices::askForget,
                ),
            onShareStats = d.stats::share,
            settings =
                SettingsCallbacks(
                    onVisibility = d.settings::setVisibility,
                    onPrefer5Ghz = d.settings::setPrefer5Ghz,
                    onKeepScreenAwake = d.settings::setKeepScreenAwake,
                    onBundleSmallFiles = d.settings::setBundleSmallFiles,
                    onPickSaveLocation = d.settings::pickSaveLocation,
                    onClearPartials = d.settings::askClearPartials,
                    onNickname = { d.settings.setNickname(it) },
                    onPickAvatar = d.settings::pickAvatar,
                    onRemoveAvatar = d.settings::removeAvatar,
                    onLanguage = d.settings::setLanguage,
                    onCrashReports = d.settings::setCrashReports,
                    onHaptics = d.settings::setHaptics,
                ),
        ),
    )
}

@Composable
private fun ScanHost(
    c: DropAppController,
    camera: @Composable () -> Unit,
) {
    val status by c.scanStatus.collectAsState()
    ScanQrScreen(status, ScanQrCallbacks(onClose = { c.back() }, onAllowCamera = c::allowCamera), camera = camera)
}
