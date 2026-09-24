package com.constrivo.drop.ui.shared.model

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.radar.FlightPlan
import com.constrivo.drop.ui.shared.radar.RadarFrame
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.OvershootEasing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatsTest {
    @Test
    fun s6_sizesAreDecimalWithOneDecimalBelowTen() {
        assertEquals(SizeParts("0", SizeUnit.B), Formats.size(0))
        assertEquals(SizeParts("999", SizeUnit.B), Formats.size(999))
        assertEquals(SizeParts("1.0", SizeUnit.KB), Formats.size(1_000))
        assertEquals(SizeParts("48", SizeUnit.MB), Formats.size(48_000_000))
        assertEquals(SizeParts("4.2", SizeUnit.GB), Formats.size(4_200_000_000))
        assertEquals(SizeParts("1.0", SizeUnit.MB), Formats.size(999_999), "rounds up into the next unit")
        assertEquals(SizeParts("1.0", SizeUnit.TB), Formats.size(999_999_999_999))
        assertEquals(SizeParts("9223372", SizeUnit.TB), Formats.size(Long.MAX_VALUE))
        assertEquals(SizeParts("0", SizeUnit.B), Formats.size(-5), "negative counts are clamped")
    }

    @Test
    fun fF1_speedAndTimeLeft() {
        assertEquals("44", Formats.megabytesPerSecond(44_000_000))
        assertEquals("4.5", Formats.megabytesPerSecond(4_450_000))
        assertEquals("0.2", Formats.megabytesPerSecond(150_000))
        assertEquals("0.1", Formats.megabytesPerSecond(1), "a positive speed never reads 0")
        assertEquals("0", Formats.megabytesPerSecond(0))
        assertEquals(DurationParts.Seconds(45), Formats.duration(45_000))
        assertEquals(DurationParts.Seconds(1), Formats.duration(0))
        assertEquals(DurationParts.Seconds(59), Formats.duration(58_001))
        assertEquals(DurationParts.Minutes(3), Formats.duration(150_000))
        assertEquals(DurationParts.HoursMinutes(1, 20), Formats.duration(80 * 60_000L))
        assertEquals("31", Formats.hours(31.46))
        assertEquals("9.5", Formats.hours(9.46))
        assertEquals("32", Formats.hours(31.5))
        assertEquals("0.0", Formats.hours(Double.NaN))
        assertEquals("-1.5", Formats.decimal(-1.5, 1))
        assertEquals("0", Formats.decimal(-0.4, 0))
        assertFailsWith<IllegalArgumentException> { Formats.decimal(1.0, 4) }
    }

    @Test
    fun fB3_sasIsGroupedWithoutBreakingAndOtherTextIsKept() {
        assertEquals("042\u202F917", Formats.sas("042917"))
        assertEquals("12345", Formats.sas("12345"))
        assertEquals(50, Formats.percent(0.5f))
        assertEquals(100, Formats.percent(3f))
        assertEquals("09", Formats.twoDigits(9))
    }

    @Test
    fun fI3_initialsKeepWholeCharacters() {
        assertEquals("RP", Avatars.initials("Rohan's Pixel"))
        assertEquals("A", Avatars.initials("  asha  "))
        assertEquals("AV", Avatars.initials("Asha Verma Rao"))
        assertEquals("दे", Avatars.initials("देव"), "a Devanagari letter keeps its vowel sign")
        assertEquals("M", Avatars.initials("🙂 Meera"), "emoji are skipped")
        assertNull(Avatars.initials("🙂 ..."))
        assertNull(Avatars.initials(null))
        assertEquals(Avatars.hash("t:abc"), Avatars.hash("t:abc"))
        assertTrue(Avatars.hash("t:abc") >= 0)
        assertTrue((0 until 64).map { Avatars.hash("dev$it") % 8 }.toSet().size >= 6, "keys spread over the palette")
    }

    @Test
    fun n15_userAgentsAreSummarisedAndSanitised() {
        val chrome =
            BrowserNames.describe(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36",
            )
        assertEquals(BrowserDescription("Chrome", "Linux", null), chrome)
        assertEquals("Edge", BrowserNames.describe("Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36 Edg/140.0")?.browser)
        assertEquals("Safari", BrowserNames.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) Version/17.0 Safari/605.1.15")?.browser)
        val odd = BrowserNames.describe("curl/8.0\u0007\n" + "x".repeat(200))
        assertEquals(60, odd?.raw?.length)
        assertTrue(odd?.raw?.none { it.code < 0x20 } == true)
        assertNull(BrowserNames.describe("   "))
        assertNull(BrowserNames.describe(null))
    }
}

class FileModelsTest {
    @Test
    fun mimeTypesMapToKinds() {
        assertEquals(FileKind.IMAGE, FileKind.fromMime("image/jpeg"))
        assertEquals(FileKind.VIDEO, FileKind.fromMime("VIDEO/mp4; codecs=avc1"))
        assertEquals(FileKind.APP, FileKind.fromMime("application/vnd.android.package-archive"))
        assertEquals(FileKind.ARCHIVE, FileKind.fromMime("application/zip"))
        assertEquals(FileKind.DOCUMENT, FileKind.fromMime("application/pdf"))
        assertEquals(FileKind.DOCUMENT, FileKind.fromMime("text/plain"))
        assertEquals(FileKind.OTHER, FileKind.fromMime(null))
        assertEquals(FileKind.OTHER, FileKind.fromMime("application/octet-stream"))
    }

    @Test
    fun n12_summariesFromKindsAndTheOfferHistogram() {
        assertEquals(ItemSummary(2, SummaryKind.PHOTOS), ItemSummary.of(listOf(FileKind.IMAGE, FileKind.IMAGE)))
        assertEquals(SummaryKind.MEDIA, ItemSummary.of(listOf(FileKind.IMAGE, FileKind.VIDEO)).kind)
        assertEquals(SummaryKind.FILES, ItemSummary.of(listOf(FileKind.IMAGE, FileKind.DOCUMENT)).kind)
        assertEquals(ItemSummary(12, SummaryKind.PHOTOS), ItemSummary.ofHistogram(12, mapOf("image/jpeg" to 10, "image/heic" to 2)))
        assertEquals(SummaryKind.FILES, ItemSummary.ofHistogram(12, mapOf("image/jpeg" to 10)).kind, "uncounted files are 'other'")
        assertEquals(SummaryKind.VIDEOS, ItemSummary.ofHistogram(1, mapOf("video/mp4" to 1, "bogus" to 0)).kind)
        assertFailsWith<IllegalArgumentException> { ItemSummary(-1, SummaryKind.FILES) }
        assertFailsWith<IllegalArgumentException> { PickedItem("a", "a", -1, FileKind.OTHER) }
        val attached = AttachedFiles(listOf(PickedItem("a", "a", 5, FileKind.IMAGE), PickedItem("b", "b", null, FileKind.IMAGE)))
        assertEquals(5, attached.totalBytes)
        assertEquals(ItemSummary(2, SummaryKind.PHOTOS), attached.summary)
    }

    @Test
    fun s8_enginePhasesMapToStages() {
        assertEquals(TransferStage.AWAITING_ACCEPT, TransferStage.of(TransferPhase.OFFERED))
        assertEquals(TransferStage.CONNECTING, TransferStage.of(TransferPhase.OFFERED, awaitingAccept = false))
        assertEquals(TransferStage.TRANSFERRING, TransferStage.of(TransferPhase.STREAMING_BLUETOOTH))
        assertEquals(TransferStage.RECONNECTING, TransferStage.of(TransferPhase.RECONNECTING))
        assertEquals(TransferStage.WAITING_FOR_PEER, TransferStage.of(TransferPhase.PARKED))
        assertTrue(TransferPhase.entries.all { TransferStage.of(it).isFinal == it.isTerminal })
    }

    @Test
    fun designSection11_progressIsAnnouncedInQuarterSteps() {
        assertEquals(
            listOf(0, 0, 25, 25, 50, 75, 100, 100, 0),
            listOf(0f, 0.2499f, 0.25f, 0.49f, 0.5f, 0.99f, 1f, 7f, Float.NaN).map {
                ProgressAnnouncements.step(it)
            },
        )
        val snapshot = Fixtures.transfer("t", null, bytesDone = 0, bytesTotal = 0)
        assertEquals(0f, snapshot.fraction)
        assertEquals(1f, snapshot.copy(stage = TransferStage.DONE).fraction)
    }

    @Test
    fun fA5_visibilityWindowAndMinutes() {
        val end = 1_000_000L
        val v = VisibilityState(Visibility.EVERYONE_TEN_MINUTES, expiresAtMillis = end, revertTo = Visibility.HIDDEN)
        assertEquals(Visibility.EVERYONE_TEN_MINUTES, v.effectiveAt(end - 1))
        assertEquals(1, v.minutesLeftAt(end - 1))
        assertEquals(10, v.minutesLeftAt(end - 600_000))
        assertEquals(Visibility.HIDDEN, v.effectiveAt(end))
        assertNull(v.minutesLeftAt(end))
        assertNull(VisibilityState(Visibility.EVERYONE).minutesLeftAt(0))
    }

    @Test
    fun lastSeenBuckets() {
        assertEquals(LastSeen.JustNow, LastSeen.of(1_000, 50_000))
        assertEquals(LastSeen.MinutesAgo(59), LastSeen.of(0, 59 * 60_000L + 59_000))
        assertEquals(LastSeen.HoursAgo(3), LastSeen.of(0, 3 * 3_600_000L))
        assertEquals(LastSeen.DaysAgo(2), LastSeen.of(0, 49 * 3_600_000L))
        assertEquals(LastSeen.JustNow, LastSeen.of(10_000, 0), "a clock set back reads as just now")
        assertEquals(AppLanguage.HINDI, AppLanguage.ofTag("HI"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.ofTag("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.ofTag(null))
    }
}

class MotionAndLayoutTest {
    @Test
    fun designSection33_overshootPeaksAtFivePercentAndSettles() {
        val e = OvershootEasing(DropMotion.SELECT_OVERSHOOT)
        assertEquals(0f, e.transform(0f))
        assertEquals(1f, e.transform(1f), 1e-6f)
        val peak = (0..100).maxOf { e.transform(it / 100f) }
        assertEquals(1.05f, peak, 1e-3f)
        assertFailsWith<IllegalArgumentException> { OvershootEasing(0.9f) }
    }

    @Test
    fun designSection42_flyersAreCappedAtEightWithAPlusTile() {
        val thumbs = List(20) { FileThumb.Glyph(FileKind.IMAGE) }
        assertEquals(3, FlightPlan.of(thumbs.take(3), 3).tiles.size)
        val many = FlightPlan.of(thumbs, 20)
        assertEquals(DropMotion.MAX_FLYERS, many.tiles.size)
        assertEquals(FlightPlan.Tile.More(13), many.tiles.last())
        assertEquals(8, FlightPlan.of(emptyList(), 8).tiles.size, "missing previews fly as glyphs")
    }

    private fun bubble(
        key: String,
        ring: Ring,
        busy: Boolean = false,
    ) = BubbleUi(
        key,
        key,
        null,
        0,
        DevicePlatform.PHONE,
        trusted = false,
        ring = ring,
        lanOnly = false,
        activity =
            if (busy) {
                BubbleActivity.Completed(key, Direction.SEND, key)
            } else {
                null
            },
    )

    @Test
    fun designSection31_ringsAt30_55_80PercentAroundTheAvatarAt96dpAboveTheBar() {
        val frame = RadarFrame.compute(emptyList(), widthDp = 360.0, heightDp = 690.0)
        assertEquals(listOf(108.0, 198.0, 288.0), frame.ringRadii.map { round(it * 10) / 10.0 })
        assertEquals(180.0, frame.avatarCenter.x)
        assertEquals(690.0 - 124.0, frame.avatarCenter.y)
        assertNull(frame.overflowCenter)
    }

    @Test
    fun fA2_moreThanTwelveDevicesCollapseIntoPlusNButBusyOnesStay() {
        val bubbles = List(15) { bubble("d$it", Ring.entries[it % 3]) } + bubble("busy", Ring.OUTER, busy = true)
        val frame = RadarFrame.compute(bubbles, 360.0, 690.0)
        assertTrue(frame.overflowKeys.isNotEmpty())
        assertTrue("busy" in frame.bubbles, "a transfer in progress is never folded into +N")
        assertEquals(bubbles.size, frame.bubbles.size + frame.overflowKeys.size)
        val points = frame.bubbles.values.toList() + listOfNotNull(frame.overflowCenter)
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val d = hypot(points[i].x - points[j].x, points[i].y - points[j].y)
                assertTrue(d >= 72.0 - 1e-6, "bubbles keep 72 dp apart (design §3.2), got $d")
            }
        }
        assertTrue(frame.bubbles.values.all { it.x in 0.0..360.0 && it.y >= 0.0 }, "every bubble is on screen")
    }

    @Test
    fun shortLandscapeAreasShrinkTheRingsToStayOnScreen() {
        val frame = RadarFrame.compute(listOf(bubble("a", Ring.OUTER)), widthDp = 800.0, heightDp = 360.0)
        val outer = frame.ringRadii[2]
        assertTrue(frame.avatarCenter.y - outer >= RadarFrame.DEFAULT_TOP_RESERVE_DP, "the outer ring clears the title bar")
        val a = frame.bubbles.getValue("a")
        assertTrue(abs(hypot(a.x - frame.avatarCenter.x, a.y - frame.avatarCenter.y) - outer) < 1e-6)
    }
}
