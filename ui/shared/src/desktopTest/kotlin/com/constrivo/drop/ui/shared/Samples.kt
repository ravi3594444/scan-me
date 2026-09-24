package com.constrivo.drop.ui.shared

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.ui.shared.dashboard.DashboardUiState
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachmentUi
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.BrowserApprovalUi
import com.constrivo.drop.ui.shared.model.BrowserNames
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.DayDate
import com.constrivo.drop.ui.shared.model.DayLabel
import com.constrivo.drop.ui.shared.model.DeviceCardUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.HistoryDayUi
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryRowUi
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.IncomingCardUi
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.LastSeen
import com.constrivo.drop.ui.shared.model.LiveRowUi
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.OnboardingStep
import com.constrivo.drop.ui.shared.model.OnboardingUi
import com.constrivo.drop.ui.shared.model.ProgressAnnouncements
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SettingsUi
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.TrayItemUi
import com.constrivo.drop.ui.shared.model.VisibilityUi
import com.constrivo.drop.ui.shared.presenter.DevicesUi
import com.constrivo.drop.ui.shared.presenter.HistoryUi

/** Hand-built UI states for the screenshot and accessibility tests (the presenters are tested separately). */
object Samples {
    private val NAMES =
        listOf(
            "Rohan",
            "Meera",
            "Kabir",
            "Office laptop",
            "Priya",
            "Dev",
            "Anil",
            "Sara",
            "Tara",
            "Neel",
            "Isha",
            "Om",
            "Zoya",
            "Ravi",
            "Maya",
        )

    val self = SelfProfile(nickname = "Asha Verma", deviceKey = "self")

    fun radar(
        bubbles: List<BubbleUi> = emptyList(),
        notice: RadarNotice? = if (bubbles.isEmpty()) RadarNotice.NO_DEVICES else null,
        visibility: VisibilityUi = VisibilityUi(Visibility.TRUSTED_ONLY),
        tray: List<TrayItemUi> = emptyList(),
    ) = RadarUiState.initial(self).copy(bubbles = bubbles, notice = notice, visibility = visibility, tray = tray)

    fun bubble(
        key: String,
        name: String?,
        ring: Ring,
        trusted: Boolean = false,
        platform: DevicePlatform = DevicePlatform.PHONE,
        lanOnly: Boolean = false,
        activity: BubbleActivity? = null,
    ) = BubbleUi(
        key = key,
        name = name,
        initials = Avatars.initials(name),
        avatarHash = Avatars.hash(key),
        platform = platform,
        trusted = trusted,
        ring = ring,
        lanOnly = lanOnly,
        rssiDbm = null,
        activity = activity,
    )

    val threeDevices =
        listOf(
            bubble("t:rohan", "Rohan's Pixel", Ring.INNER, trusted = true),
            bubble("e:0a1b2c3d4e5f", "Meera", Ring.MIDDLE),
            bubble("e:112233445566", "Office laptop", Ring.OUTER, platform = DevicePlatform.LAPTOP),
        )

    val fifteenDevices: List<BubbleUi> =
        List(15) { i ->
            val ring = Ring.entries[i % 3]
            bubble(
                "e:dev$i",
                NAMES[i % NAMES.size],
                ring,
                trusted = i % 5 == 0,
                platform =
                    if (i % 4 ==
                        3
                    ) {
                        DevicePlatform.LAPTOP
                    } else {
                        DevicePlatform.PHONE
                    },
            )
        }

    fun sending(
        fraction: Float = 0.42f,
        hint: LadderHint? = LadderHint.band24(),
    ) = BubbleActivity.Active(
        transferId = "tx1",
        direction = Direction.SEND,
        stage = TransferStage.TRANSFERRING,
        fraction = fraction,
        bytesDone = (100_000_000 * fraction).toLong(),
        bytesTotal = 100_000_000,
        bytesPerSecond = 44_000_000,
        etaMillis = 45_000,
        badge = TransportBadge.of(LinkKind.P2P, 2437),
        hint = hint,
        paused = false,
        resumed = false,
        dropToken = null,
        flyers = emptyList(),
        fileCount = 12,
        announcedPercent = ProgressAnnouncements.step(fraction),
        confirmCancel = false,
    )

    val sendingRadar =
        radar(
            bubbles =
                listOf(
                    bubble("t:rohan", "Rohan's Pixel", Ring.INNER, trusted = true, activity = sending()),
                    bubble("e:0a1b2c3d4e5f", "Meera", Ring.OUTER),
                ),
            visibility = VisibilityUi(Visibility.EVERYONE_TEN_MINUTES, minutesLeft = 7),
        )

    val bluetoothOff = radar(notice = RadarNotice.BLUETOOTH_OFF)

    val shareBanner =
        radar(Samples.threeDevices).copy(
            attachment =
                AttachmentUi(
                    ItemSummary(12, SummaryKind.PHOTOS),
                    48_000_000,
                    names = listOf("IMG_2034.jpg", "IMG_2035.jpg"),
                    moreCount = 10,
                ),
        )

    val tray =
        radar(
            bubbles = listOf(bubble("t:rohan", "Rohan's Pixel", Ring.INNER, trusted = true)),
            tray =
                listOf(
                    TrayItemUi("f1", "IMG_0042.jpg", FileThumb.Glyph(FileKind.IMAGE)),
                    TrayItemUi("f2", "Trip.mp4", FileThumb.Glyph(FileKind.VIDEO)),
                    TrayItemUi("f3", "Tickets.pdf", FileThumb.Glyph(FileKind.DOCUMENT)),
                ),
        )

    fun incoming(
        trusted: Boolean,
        sasConfirmed: Boolean = false,
    ) = IncomingCardUi(
        offerId = "o1",
        senderName = "Dev",
        senderInitials = "D",
        senderAvatarHash = Avatars.hash("e:dev"),
        senderAvatar = null,
        senderPlatform = DevicePlatform.PHONE,
        trusted = trusted,
        summary = ItemSummary(12, SummaryKind.PHOTOS),
        totalBytes = 48_000_000,
        previews = List(6) { FileThumb.Glyph(FileKind.IMAGE) },
        morePreviews = 6,
        sas = if (trusted) null else "042917",
        sasConfirmed = sasConfirmed,
        alwaysAccept = trusted,
        canAlwaysAccept = trusted || sasConfirmed,
        remainingMillis = 21_000,
        remainingFraction = 0.7f,
    )

    private val badge5 = TransportBadge.of(LinkKind.P2P, 5180)

    val live =
        listOf(
            LiveRowUi(
                "tx1",
                "Rohan's Pixel",
                DevicePlatform.PHONE,
                Direction.SEND,
                TransferStage.TRANSFERRING,
                ItemSummary(12, SummaryKind.PHOTOS),
                20_000_000,
                48_000_000,
                0.42f,
                44_000_000,
                1_000,
                badge5,
                null,
                false,
                true,
            ),
            LiveRowUi(
                "tx2",
                "Office laptop",
                DevicePlatform.LAPTOP,
                Direction.RECEIVE,
                TransferStage.TRANSFERRING,
                ItemSummary(240, SummaryKind.FILES),
                300_000_000,
                1_200_000_000,
                0.25f,
                2_400_000,
                375_000,
                TransportBadge.of(LinkKind.HOTSPOT, 2412),
                LadderHint.bundling(240),
                false,
                false,
            ),
        )

    private fun entry(
        id: String,
        name: String,
        direction: Direction,
        status: HistoryStatus,
        kind: SummaryKind,
        count: Int,
        bytes: Long,
    ) = HistoryEntry(
        id,
        name,
        DevicePlatform.PHONE,
        direction,
        status,
        0,
        12_000,
        bytes,
        bytes,
        bytes / 12,
        ItemSummary(count, kind),
        badge5,
    )

    val history =
        HistoryUi(
            days =
                listOf(
                    HistoryDayUi(
                        20_719,
                        DayLabel.Today,
                        listOf(
                            HistoryRowUi(
                                entry("h1", "Rohan's Pixel", Direction.SEND, HistoryStatus.DONE, SummaryKind.PHOTOS, 12, 48_000_000),
                                14,
                                5,
                            ),
                            HistoryRowUi(
                                entry("h2", "Meera", Direction.RECEIVE, HistoryStatus.PARTIAL, SummaryKind.MEDIA, 30, 820_000_000),
                                9,
                                41,
                            ),
                        ),
                    ),
                    HistoryDayUi(
                        20_718,
                        DayLabel.Yesterday,
                        listOf(
                            HistoryRowUi(
                                entry("h3", "Office laptop", Direction.SEND, HistoryStatus.FAILED, SummaryKind.FILES, 3, 2_100_000),
                                18,
                                30,
                            ),
                        ),
                    ),
                    HistoryDayUi(
                        20_710,
                        DayLabel.Date(DayDate(2026, 9, 14)),
                        listOf(
                            HistoryRowUi(
                                entry("h4", "Dev", Direction.RECEIVE, HistoryStatus.CANCELLED, SummaryKind.VIDEOS, 2, 600_000_000),
                                21,
                                2,
                            ),
                        ),
                    ),
                ),
        )

    val devices =
        DevicesUi(
            devices =
                listOf(
                    DeviceCardUi(
                        "d1",
                        "Rohan's Pixel",
                        "RP",
                        Avatars.hash("d1"),
                        DevicePlatform.PHONE,
                        LastSeen.MinutesAgo(5),
                        autoAccept = true,
                    ),
                    DeviceCardUi(
                        "d2",
                        "Office laptop",
                        "OL",
                        Avatars.hash("d2"),
                        DevicePlatform.LAPTOP,
                        LastSeen.DaysAgo(2),
                        autoAccept = false,
                    ),
                ),
        )

    val stats = StatsSnapshot(12_400_000_000, 38_000_000, 14, 31.5, listOf(2, 5, 3, 0, 8, 6, 9, 4, 7, 12, 10, 14))

    val settings =
        SettingsUi(
            SettingsValues(
                visibility = Visibility.TRUSTED_ONLY,
                prefer5Ghz = true,
                keepScreenAwake = false,
                bundleSmallFiles = true,
                saveLocationLabel = null,
                nickname = "Asha Verma",
                avatar = null,
                language = AppLanguage.SYSTEM,
                crashReports = false,
                haptics = true,
                appVersion = "0.1.0",
            ),
        )

    val browserApproval =
        BrowserApprovalUi(
            requestId = 1,
            browserNumber = 1,
            remoteAddress = "192.168.49.23",
            browser = BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36"),
        )

    fun dashboard(tab: DashboardTab) = DashboardUiState(tab, live, history, devices, stats, settings)

    val welcome = OnboardingUi(OnboardingStep.WELCOME, "Asha's Galaxy", nicknameValid = true, avatar = null, brand = OemBrand.XIAOMI)
}
