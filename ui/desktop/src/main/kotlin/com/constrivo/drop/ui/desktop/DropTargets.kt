package com.constrivo.drop.ui.desktop

import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.presenter.Screen
import com.constrivo.drop.ui.shared.radar.RadarFrame
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.hypot

/**
 * Where a drop lands on the radar (design §9 "dropping onto a bubble sends to that device"). The shared radar places
 * its bubbles with [RadarFrame.compute] in the field above its bottom bar; this repeats that computation for the
 * window's content area, and the shell lays an invisible drop target over each bubble ([targets]) — Compose hands a
 * drag to the innermost target under the pointer, so no pointer position is needed and `ui/shared`'s composables stay
 * untouched. `MainWindowUiTest` checks the targets against the bubbles' on-screen bounds, so a change to the radar's
 * layout that this does not follow fails a test instead of dropping onto the wrong device.
 */
object RadarHitTest {
    /** The radar's bottom bar at font scale 1: its divider, 4 dp padding above and below and the 64 dp actions. */
    const val BOTTOM_BAR_DP: Double = 1.0 + 4.0 + 64.0 + 4.0

    /** The share banner's reserve at the top of the field (`RadarField`'s `BANNER_RESERVE_DP`). */
    const val BANNER_RESERVE_DP: Double = 64.0

    /** A notice card's reserve at the top of the field (`RadarField`'s `NOTICE_RESERVE_DP`). */
    const val NOTICE_RESERVE_DP: Double = 104.0

    /** Bubble diameters (`DropDimens.bubble`, `bubbleTrusted`) and the ring drawn around them. */
    private const val BUBBLE_DP = 56.0
    private const val TRUSTED_BUBBLE_DP = 64.0
    private const val RING_GAP_DP = 10.0

    /**
     * Slack around a bubble's ring, so a drop that just misses the circle still counts: the pointer is a cursor, not a
     * finger, but users aim at the middle of the name as often as at the circle.
     */
    private const val SLACK_DP = 8.0

    /** The top reserve `RadarField` uses for [state]. */
    fun topReserve(state: RadarUiState): Double =
        RadarFrame.DEFAULT_TOP_RESERVE_DP +
            (if (state.attachment != null) BANNER_RESERVE_DP else 0.0) +
            (if (state.notice != null) NOTICE_RESERVE_DP else 0.0)

    /** Where a drop counts as "on" a bubble: a circle around its centre, in dp from the radar screen's top-left. */
    data class Target(
        val key: String,
        val xDp: Double,
        val yDp: Double,
        val radiusDp: Double,
    )

    /**
     * The drop target of every bubble shown on an [areaWidthDp] × [areaHeightDp] radar screen: the bubble with its
     * ring and a little slack. Bubbles folded into "+N more" have none (they are in the "Send to…" list).
     */
    fun targets(
        state: RadarUiState,
        areaWidthDp: Double,
        areaHeightDp: Double,
    ): List<Target> {
        if (areaWidthDp <= 0 || areaHeightDp <= BOTTOM_BAR_DP) return emptyList()
        val fieldHeight = areaHeightDp - BOTTOM_BAR_DP
        val frame = RadarFrame.compute(state.bubbles, areaWidthDp, fieldHeight, topReserveDp = topReserve(state))
        val byKey = state.bubbles.associateBy { it.key }
        return frame.bubbles.mapNotNull { (key, p) ->
            val bubble = byKey[key] ?: return@mapNotNull null
            val radius = ((if (bubble.trusted) TRUSTED_BUBBLE_DP else BUBBLE_DP) + RING_GAP_DP) / 2 + SLACK_DP
            Target(key, p.x, p.y, radius)
        }
    }

    /**
     * The bubble under ([xDp], [yDp]) on a radar screen of [areaWidthDp] × [areaHeightDp] (both from its top-left), or
     * null for empty space, the "+N more" bubble and the bottom bar. Where two targets overlap the nearer centre wins.
     */
    fun bubbleAt(
        state: RadarUiState,
        areaWidthDp: Double,
        areaHeightDp: Double,
        xDp: Double,
        yDp: Double,
    ): BubbleUi? {
        if (xDp < 0 || yDp < 0 || xDp > areaWidthDp || yDp > areaHeightDp - BOTTOM_BAR_DP) return null
        val hit =
            targets(state, areaWidthDp, areaHeightDp)
                .map { it to hypot(xDp - it.xDp, yDp - it.yDp) }
                .filter { (t, d) -> d <= t.radiusDp }
                .minByOrNull { it.second }
                ?.first ?: return null
        return state.bubbles.firstOrNull { it.key == hit.key }
    }
}

/** What a drop (or Ctrl/Cmd+O) does (design §9). */
sealed interface DropDecision {
    /** Send the files to the bubble they were dropped on. */
    data class SendTo(
        val deviceKey: String,
    ) : DropDecision

    /** Dropped on empty space, a busy bubble or another screen: ask "Send to…" with the bubble list. */
    data object ChooseDevice : DropDecision

    /** Dropped while the file picker is open: the files join its selection (as its "Browse files" would add them). */
    data object AddToPicker : DropDecision

    /** Nothing to do: no files, or the app is still being set up. */
    data object Ignore : DropDecision
}

/** The drop rules of design §9, pure so they are tested without a window. */
object DropTargets {
    /**
     * What dropping files does: onto an idle bubble of the radar it sends to that device; while the file picker is open
     * the files join its selection; anywhere else (empty space, the "+N more" bubble, a bubble that is already sending
     * or receiving, a sheet over the radar, the dashboard) it asks "Send to…". During onboarding and with nothing
     * droppable it does nothing. The shell lays no bubble targets under a sheet or card, so a drop there never lands on
     * a bubble hidden behind it.
     */
    fun decide(
        screen: Screen,
        hasFiles: Boolean,
        bubbleUnderPointer: BubbleUi?,
        pickerOpen: Boolean = false,
    ): DropDecision =
        when {
            !hasFiles || screen == Screen.ONBOARDING -> DropDecision.Ignore
            pickerOpen -> DropDecision.AddToPicker
            screen == Screen.RADAR && bubbleUnderPointer != null && !bubbleUnderPointer.busy -> DropDecision.SendTo(bubbleUnderPointer.key)
            else -> DropDecision.ChooseDevice
        }

    /**
     * The local paths of a drag's file list: AWT hands Compose `file:` URIs; anything else (a browser dragging a web
     * link, a URI of a virtual file system) is skipped.
     */
    fun paths(uris: List<String>): List<Path> =
        uris.mapNotNull { text ->
            try {
                val uri = URI(text)
                if (uri.scheme.equals("file", ignoreCase = true)) Paths.get(uri) else null
            } catch (_: URISyntaxException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: FileSystemNotFoundException) {
                null
            }
        }.distinct()
}
