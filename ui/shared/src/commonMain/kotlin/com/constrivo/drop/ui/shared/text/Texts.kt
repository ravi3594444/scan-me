package com.constrivo.drop.ui.shared.text

import androidx.compose.runtime.Composable
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.BadgeKey
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.ui.shared.model.DayDate
import com.constrivo.drop.ui.shared.model.DayLabel
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.DurationParts
import com.constrivo.drop.ui.shared.model.Formats
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.LastSeen
import com.constrivo.drop.ui.shared.model.SizeUnit
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.resources.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * Model → string resource mapping (design §8). Every user-facing string goes through here or through a resource
 * directly; composables never hold literals (UserTextLiteralTest).
 */

/** "48 MB", "4.2 GB" (decimal units, S6). */
@Composable
fun sizeText(bytes: Long): String {
    val parts = Formats.size(bytes)
    val res =
        when (parts.unit) {
            SizeUnit.B -> Res.string.size_b
            SizeUnit.KB -> Res.string.size_kb
            SizeUnit.MB -> Res.string.size_mb
            SizeUnit.GB -> Res.string.size_gb
            SizeUnit.TB -> Res.string.size_tb
        }
    return stringResource(res, parts.value)
}

/** "45 s", "3 min", "1 h 20 min". */
@Composable
fun durationText(millis: Long): String =
    when (val parts = Formats.duration(millis)) {
        is DurationParts.Seconds -> stringResource(Res.string.duration_seconds, parts.seconds)
        is DurationParts.Minutes -> stringResource(Res.string.duration_minutes, parts.minutes)
        is DurationParts.HoursMinutes -> stringResource(Res.string.duration_hours_minutes, parts.hours, parts.minutes)
    }

/** "12 photos". */
@Composable
fun summaryText(summary: ItemSummary): String = pluralStringResource(summaryPlural(summary.kind), summary.count, summary.count)

/** "12 photos · 48 MB" (design §5.1 line 2). */
@Composable
fun summaryWithSizeText(
    summary: ItemSummary,
    bytes: Long,
): String = stringResource(Res.string.summary_with_size, summaryText(summary), sizeText(bytes))

fun summaryPlural(kind: SummaryKind): PluralStringResource =
    when (kind) {
        SummaryKind.PHOTOS -> Res.plurals.summary_photos
        SummaryKind.VIDEOS -> Res.plurals.summary_videos
        SummaryKind.MEDIA -> Res.plurals.summary_media
        SummaryKind.FILES -> Res.plurals.summary_files
    }

/** "44 MB/s · 45 s left", or "44 MB/s" without an ETA, or null before the first speed sample (design §4.2). */
@Composable
fun speedLineText(
    bytesPerSecond: Long?,
    etaMillis: Long?,
): String? {
    val speed = bytesPerSecond?.let { Formats.megabytesPerSecond(it) } ?: return null
    return if (etaMillis != null) {
        stringResource(Res.string.transfer_speed, speed, durationText(etaMillis))
    } else {
        stringResource(Res.string.transfer_speed_only, speed)
    }
}

/** The badge copy (design §8.3), mirroring `TransportBadge.englishText` key for key. */
@Composable
fun badgeText(badge: TransportBadge): String =
    when (badge.key) {
        BadgeKey.HOTSPOT -> stringResource(Res.string.badge_hotspot, bandText(badge.band))
        else -> stringResource(badgeResource(badge.key))
    }

/** Resource of a badge key without params (for [BadgeKey.HOTSPOT] the template with `{band}`). */
fun badgeResource(key: BadgeKey): StringResource =
    when (key) {
        BadgeKey.P2P_5 -> Res.string.badge_p2p_5
        BadgeKey.P2P_24 -> Res.string.badge_p2p_24
        BadgeKey.P2P_6 -> Res.string.badge_p2p_6
        BadgeKey.P2P_UNKNOWN_BAND -> Res.string.badge_p2p
        BadgeKey.LAN -> Res.string.badge_lan
        BadgeKey.HOTSPOT -> Res.string.badge_hotspot
        BadgeKey.HOTSPOT_UNKNOWN_BAND -> Res.string.badge_hotspot_plain
        BadgeKey.BLUETOOTH -> Res.string.badge_bt
    }

@Composable
private fun bandText(band: WifiBand?): String =
    when (band) {
        WifiBand.BAND_2_4_GHZ -> stringResource(Res.string.band_24)
        WifiBand.BAND_5_GHZ -> stringResource(Res.string.band_5)
        WifiBand.BAND_6_GHZ -> stringResource(Res.string.band_6)
        null -> ""
    }

/** The hint copy (design §8.2), mirroring `LadderHint.englishText` form for form. */
@Composable
fun hintText(hint: LadderHint): String {
    val name = hint.params[LadderHint.PARAM_NAME]
    return when (hint.code) {
        HintCode.BAND24 -> {
            stringResource(Res.string.hint_band24)
        }

        HintCode.PEER_BAND24_ONLY -> {
            if (name != null) {
                stringResource(Res.string.hint_peer_band24_only, name)
            } else {
                stringResource(Res.string.hint_peer_band24_only_unnamed)
            }
        }

        HintCode.SDCARD -> {
            stringResource(Res.string.hint_sdcard)
        }

        HintCode.THERMAL -> {
            stringResource(Res.string.hint_thermal)
        }

        HintCode.BUNDLING -> {
            val count = hint.params[LadderHint.PARAM_COUNT]?.toIntOrNull()
            if (count != null) {
                pluralStringResource(Res.plurals.hint_bundling, count, count)
            } else {
                stringResource(Res.string.hint_bundling_many)
            }
        }

        HintCode.BT_FALLBACK -> {
            stringResource(Res.string.hint_bt_fallback)
        }

        HintCode.LAN_SLOW -> {
            stringResource(Res.string.hint_lan_slow)
        }

        HintCode.STATION_BAND24 -> {
            when {
                hint.params[LadderHint.PARAM_SIDE] != LadderHint.SIDE_PEER -> stringResource(Res.string.hint_sta_band24)
                name != null -> stringResource(Res.string.hint_sta_band24_peer, name)
                else -> stringResource(Res.string.hint_sta_band24_peer_unnamed)
            }
        }
    }
}

/** A transfer stage's caption, or null when the speed line says it all (a running transfer). */
@Composable
fun stageText(
    stage: TransferStage,
    direction: Direction,
    peerName: String,
    paused: Boolean = false,
): String? {
    if (paused && !stage.isFinal) return stringResource(Res.string.transfer_paused)
    return when (stage) {
        TransferStage.CONNECTING -> stringResource(Res.string.transfer_connecting)
        TransferStage.AWAITING_ACCEPT -> stringResource(Res.string.transfer_waiting_accept)
        TransferStage.TRANSFERRING -> null
        TransferStage.RECONNECTING -> stringResource(Res.string.transfer_reconnecting)
        TransferStage.WAITING_FOR_PEER -> stringResource(Res.string.transfer_waiting_peer, peerName)
        TransferStage.VERIFYING -> stringResource(Res.string.transfer_verifying)
        TransferStage.DONE -> doneText(direction)
        TransferStage.FAILED -> stringResource(Res.string.transfer_failed)
        TransferStage.CANCELLED -> stringResource(Res.string.transfer_cancelled)
        TransferStage.DECLINED -> stringResource(Res.string.transfer_declined)
        TransferStage.NO_ANSWER -> stringResource(Res.string.transfer_no_answer)
    }
}

/** "Sent" / "Received" (design §8.3 `transfer.done`). */
@Composable
fun doneText(direction: Direction): String =
    stringResource(if (direction == Direction.SEND) Res.string.transfer_done_sent else Res.string.transfer_done_received)

/** Visibility names (design §8.3). */
@Composable
fun visibilityText(mode: Visibility): String = stringResource(visibilityResource(mode))

fun visibilityResource(mode: Visibility): StringResource =
    when (mode) {
        Visibility.EVERYONE -> Res.string.visibility_everyone
        Visibility.EVERYONE_TEN_MINUTES -> Res.string.visibility_ten_min
        Visibility.TRUSTED_ONLY -> Res.string.visibility_trusted
        Visibility.HIDDEN -> Res.string.visibility_hidden
    }

/** One-line explanation of a visibility mode. */
@Composable
fun visibilityDescription(mode: Visibility): String =
    stringResource(
        when (mode) {
            Visibility.EVERYONE -> Res.string.visibility_everyone_desc
            Visibility.EVERYONE_TEN_MINUTES -> Res.string.visibility_ten_min_desc
            Visibility.TRUSTED_ONLY -> Res.string.visibility_trusted_desc
            Visibility.HIDDEN -> Res.string.visibility_hidden_desc
        },
    )

@Composable
fun platformText(platform: DevicePlatform): String =
    stringResource(
        when (platform) {
            DevicePlatform.PHONE -> Res.string.platform_phone
            DevicePlatform.LAPTOP -> Res.string.platform_laptop
            DevicePlatform.DESKTOP -> Res.string.platform_desktop
            DevicePlatform.BROWSER_PROXY -> Res.string.platform_browser
            DevicePlatform.UNKNOWN -> Res.string.platform_unknown
        },
    )

/** "Right here" / "Nearby" / "Far" (design §3.2); "Same network" for mDNS-only devices. */
@Composable
fun ringText(
    ring: Ring,
    lanOnly: Boolean,
): String =
    stringResource(
        when {
            lanOnly -> Res.string.ring_network
            ring == Ring.INNER -> Res.string.ring_inner
            ring == Ring.MIDDLE -> Res.string.ring_middle
            else -> Res.string.ring_outer
        },
    )

/** The ring meaning inside an accessibility label ("…, nearby, trusted"). */
@Composable
fun ringA11yText(
    ring: Ring,
    lanOnly: Boolean,
): String =
    stringResource(
        when {
            lanOnly -> Res.string.a11y_ring_network
            ring == Ring.INNER -> Res.string.a11y_ring_inner
            ring == Ring.MIDDLE -> Res.string.a11y_ring_middle
            else -> Res.string.a11y_ring_outer
        },
    )

/** A device name, or "Nearby device" when the peer advertises none (Trusted-only strangers, N4). */
@Composable
fun deviceNameText(name: String?): String = name ?: stringResource(Res.string.unnamed_device)

@Composable
fun dayLabelText(label: DayLabel): String =
    when (label) {
        DayLabel.Today -> {
            stringResource(Res.string.history_today)
        }

        DayLabel.Yesterday -> {
            stringResource(Res.string.history_yesterday)
        }

        is DayLabel.Date -> {
            stringResource(
                Res.string.history_date,
                label.date.day,
                stringResource(monthResource(label.date.month)),
                label.date.year,
            )
        }
    }

fun monthResource(month: Int): StringResource =
    when (month) {
        1 -> Res.string.month_1
        2 -> Res.string.month_2
        3 -> Res.string.month_3
        4 -> Res.string.month_4
        5 -> Res.string.month_5
        6 -> Res.string.month_6
        7 -> Res.string.month_7
        8 -> Res.string.month_8
        9 -> Res.string.month_9
        10 -> Res.string.month_10
        11 -> Res.string.month_11
        12 -> Res.string.month_12
        else -> throw IllegalArgumentException(DayDate.MONTH_RANGE)
    }

/** "14:05". */
@Composable
fun clockText(
    hour: Int,
    minute: Int,
): String = stringResource(Res.string.history_time, Formats.twoDigits(hour), Formats.twoDigits(minute))

@Composable
fun lastSeenText(lastSeen: LastSeen): String =
    when (lastSeen) {
        LastSeen.JustNow -> stringResource(Res.string.devices_seen_now)
        is LastSeen.MinutesAgo -> pluralStringResource(Res.plurals.devices_seen_minutes, lastSeen.minutes, lastSeen.minutes)
        is LastSeen.HoursAgo -> pluralStringResource(Res.plurals.devices_seen_hours, lastSeen.hours, lastSeen.hours)
        is LastSeen.DaysAgo -> pluralStringResource(Res.plurals.devices_seen_days, lastSeen.days, lastSeen.days)
    }

@Composable
fun historyStatusText(status: HistoryStatus): String =
    stringResource(
        when (status) {
            HistoryStatus.DONE -> Res.string.status_done
            HistoryStatus.PARTIAL -> Res.string.status_partial
            HistoryStatus.FAILED -> Res.string.status_failed
            HistoryStatus.CANCELLED -> Res.string.status_cancelled
            HistoryStatus.INTERRUPTED -> Res.string.status_interrupted
        },
    )
