package com.constrivo.drop.ui.shared

import androidx.compose.runtime.Composable
import com.constrivo.drop.ui.shared.components.SheetHost
import com.constrivo.drop.ui.shared.components.SheetSpec
import com.constrivo.drop.ui.shared.dashboard.DashboardCallbacks
import com.constrivo.drop.ui.shared.dashboard.DashboardScreen
import com.constrivo.drop.ui.shared.model.BrowserApprovalUi
import com.constrivo.drop.ui.shared.model.BrowserNames
import com.constrivo.drop.ui.shared.model.BrowserShareHint
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FilePickerUi
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.InstallerWarningUi
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.PickableUi
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTab
import com.constrivo.drop.ui.shared.model.PickerTarget
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.SenderPairingUi
import com.constrivo.drop.ui.shared.model.ShowQrUi
import com.constrivo.drop.ui.shared.onboarding.BrandStepCallbacks
import com.constrivo.drop.ui.shared.onboarding.BrandStepScreen
import com.constrivo.drop.ui.shared.onboarding.WelcomeCallbacks
import com.constrivo.drop.ui.shared.onboarding.WelcomeScreen
import com.constrivo.drop.ui.shared.qr.QrMatrix
import com.constrivo.drop.ui.shared.radar.RadarCallbacks
import com.constrivo.drop.ui.shared.radar.RadarScreen
import com.constrivo.drop.ui.shared.receive.BrowserApprovalCallbacks
import com.constrivo.drop.ui.shared.receive.BrowserApprovalSheet
import com.constrivo.drop.ui.shared.receive.IncomingCallbacks
import com.constrivo.drop.ui.shared.receive.IncomingCard
import com.constrivo.drop.ui.shared.receive.InstallerWarningSheet
import com.constrivo.drop.ui.shared.receive.SenderPairingCallbacks
import com.constrivo.drop.ui.shared.receive.SenderPairingSheet
import com.constrivo.drop.ui.shared.send.FilePickerCallbacks
import com.constrivo.drop.ui.shared.send.FilePickerSheet
import com.constrivo.drop.ui.shared.send.ShowQrCallbacks
import com.constrivo.drop.ui.shared.send.ShowQrSheet
import kotlin.test.Test

/**
 * Screenshot tests of the screens and states named by WP8 and testing §5: radar (empty, 3 devices, 15 with "+N",
 * sending with badge and hint, Bluetooth off, reduced motion), incoming card (first time with SAS, trusted), tray,
 * each dashboard tab, onboarding, light and dark, and 200% font. See [Screenshots] for tolerance and re-recording.
 */
class ScreenshotTest {
    @Composable
    private fun Radar(state: RadarUiState) = RadarScreen(state, RadarCallbacks())

    @Composable
    private fun OverRadar(
        key: String,
        heightFraction: Float? = null,
        sheet: @Composable () -> Unit,
    ) = SheetHost(SheetSpec(key, heightFraction, sheet), onDismiss = {}) { Radar(Samples.radar(Samples.threeDevices)) }

    @Test
    fun radarEmpty() = Screenshots.check("radar_empty") { Radar(Samples.radar()) }

    @Test
    fun radarEmptyDark() = Screenshots.check("radar_empty_dark", dark = true) { Radar(Samples.radar()) }

    @Test
    fun radarThreeDevices() = Screenshots.check("radar_three_devices") { Radar(Samples.radar(Samples.threeDevices)) }

    @Test
    fun radarThreeDevicesDark() = Screenshots.check("radar_three_devices_dark", dark = true) { Radar(Samples.radar(Samples.threeDevices)) }

    @Test
    fun radarThreeDevicesFont200() =
        Screenshots.check("radar_three_devices_font200", fontScale = 2f) {
            Radar(Samples.radar(Samples.threeDevices))
        }

    @Test
    fun radarFifteenDevicesWithOverflow() = Screenshots.check("radar_fifteen_devices") { Radar(Samples.radar(Samples.fifteenDevices)) }

    @Test
    fun radarSendingWithBadgeAndHint() = Screenshots.check("radar_sending") { Radar(Samples.sendingRadar) }

    @Test
    fun radarSendingDark() = Screenshots.check("radar_sending_dark", dark = true) { Radar(Samples.sendingRadar) }

    @Test
    fun radarReceiving() = Screenshots.check("radar_receiving") { Radar(Samples.receivingRadar) }

    @Test
    fun radarWaitingForAnAnswer() = Screenshots.check("radar_waiting") { Radar(Samples.waitingRadar) }

    @Test
    fun radarBluetoothOff() = Screenshots.check("radar_bluetooth_off") { Radar(Samples.bluetoothOff) }

    @Test
    fun radarReducedMotion() =
        Screenshots.check("radar_reduced_motion", reducedMotion = true) {
            Radar(Samples.radar(Samples.threeDevices))
        }

    @Test
    fun trayWithReceivedFiles() = Screenshots.check("radar_tray") { Radar(Samples.tray) }

    @Test
    fun incomingFirstTimeWithSas() =
        Screenshots.check("incoming_first_time") {
            OverRadar("incoming") { IncomingCard(Samples.incoming(trusted = false), IncomingCallbacks()) }
        }

    @Test
    fun incomingFirstTimeDark() =
        Screenshots.check("incoming_first_time_dark", dark = true) {
            OverRadar("incoming") { IncomingCard(Samples.incoming(trusted = false), IncomingCallbacks()) }
        }

    @Test
    fun incomingFirstTimeFont200() =
        Screenshots.check("incoming_first_time_font200", fontScale = 2f) {
            OverRadar("incoming") { IncomingCard(Samples.incoming(trusted = false), IncomingCallbacks()) }
        }

    @Test
    fun incomingTrusted() =
        Screenshots.check("incoming_trusted") {
            OverRadar("incoming") { IncomingCard(Samples.incoming(trusted = true), IncomingCallbacks()) }
        }

    @Test
    fun dashboardLive() = Screenshots.check("dashboard_live") { Dashboard(DashboardTab.LIVE) }

    @Test
    fun dashboardHistory() = Screenshots.check("dashboard_history") { Dashboard(DashboardTab.HISTORY) }

    @Test
    fun dashboardHistoryDark() = Screenshots.check("dashboard_history_dark", dark = true) { Dashboard(DashboardTab.HISTORY) }

    @Test
    fun dashboardDevices() = Screenshots.check("dashboard_devices") { Dashboard(DashboardTab.DEVICES) }

    @Test
    fun dashboardStats() = Screenshots.check("dashboard_stats") { Dashboard(DashboardTab.STATS) }

    @Test
    fun dashboardStatsDark() = Screenshots.check("dashboard_stats_dark", dark = true) { Dashboard(DashboardTab.STATS) }

    @Test
    fun dashboardSettings() = Screenshots.check("dashboard_settings") { Dashboard(DashboardTab.SETTINGS) }

    @Test
    fun dashboardSettingsFont200() = Screenshots.check("dashboard_settings_font200", fontScale = 2f) { Dashboard(DashboardTab.SETTINGS) }

    @Test
    fun onboardingWelcome() = Screenshots.check("onboarding_welcome") { WelcomeScreen(Samples.welcome, WelcomeCallbacks()) }

    @Test
    fun onboardingWelcomeDark() =
        Screenshots.check("onboarding_welcome_dark", dark = true) {
            WelcomeScreen(Samples.welcome, WelcomeCallbacks())
        }

    @Test
    fun onboardingBrandStep() = Screenshots.check("onboarding_brand") { BrandStepScreen(OemBrand.XIAOMI, BrandStepCallbacks()) }

    @Test
    fun showMyCode() =
        Screenshots.check("show_qr") {
            OverRadar("qr") {
                ShowQrSheet(
                    ShowQrUi(
                        nickname = "Asha Verma",
                        matrix = QrMatrix.encode("drop1.AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5"),
                        fallbackCode = "318204",
                        refreshFraction = 0.3f,
                        browserHint =
                            BrowserShareHint(
                                "DIRECT-xy-Drop",
                                "k7Qm2pX9",
                                "http://drop.local:8765/t/7h2kq9x3m4pz/",
                                "http://192.168.49.1:8765/t/7h2kq9x3m4pz/",
                            ),
                        browserStarting = false,
                        browserMatrix = QrMatrix.encode("http://192.168.49.1:8765/t/7h2kq9x3m4pz/"),
                    ),
                    ShowQrCallbacks(),
                )
            }
        }

    @Test
    fun filePicker() =
        Screenshots.check("file_picker") {
            OverRadar("picker", heightFraction = 0.8f) {
                FilePickerSheet(
                    FilePickerUi(
                        target = PickerTarget.Device("t:rohan", "Rohan's Pixel"),
                        tabs = listOf(PickerTab.PHOTOS, PickerTab.FILES),
                        tab = PickerTab.PHOTOS,
                        photos =
                            List(12) { i ->
                                PickableUi(
                                    PickedItem("p$i", "IMG_$i.jpg", 4_000_000, FileKind.IMAGE, FileThumb.Glyph(FileKind.IMAGE)),
                                    if (i <
                                        3
                                    ) {
                                        i + 1
                                    } else {
                                        0
                                    },
                                )
                            },
                        photosAccess = true,
                        files = emptyList(),
                        apps = emptyList(),
                        selectedCount = 3,
                        selectedBytes = 12_000_000,
                    ),
                    FilePickerCallbacks(),
                )
            }
        }

    @Test
    fun allowThisComputer() =
        Screenshots.check("browser_approval") {
            OverRadar("browser") {
                BrowserApprovalSheet(
                    BrowserApprovalUi(
                        1,
                        1,
                        "192.168.49.23",
                        BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36"),
                    ),
                    BrowserApprovalCallbacks(),
                )
            }
        }

    @Test
    fun installerWarning() =
        Screenshots.check("installer_warning") {
            OverRadar("installer") { InstallerWarningSheet(InstallerWarningUi("WhatsApp.apk", "Dev"), onOpen = {}, onCancel = {}) }
        }

    @Test
    fun shareBannerWithNames() = Screenshots.check("radar_share_banner") { Radar(Samples.shareBanner) }

    @Test
    fun senderPairing() =
        Screenshots.check("sender_pairing") {
            OverRadar("pairing") { SenderPairingSheet(SenderPairingUi("tx", "Rohan's Pixel", "042917"), SenderPairingCallbacks()) }
        }

    @Composable
    private fun Dashboard(tab: DashboardTab) = DashboardScreen(Samples.dashboard(tab), DashboardCallbacks())
}
