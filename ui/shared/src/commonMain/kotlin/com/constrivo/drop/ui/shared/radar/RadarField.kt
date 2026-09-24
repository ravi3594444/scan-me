package com.constrivo.drop.ui.shared.radar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.ui.shared.TestTags
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.BadgeChip
import com.constrivo.drop.ui.shared.components.MoreTile
import com.constrivo.drop.ui.shared.components.ProgressRing
import com.constrivo.drop.ui.shared.components.ThumbTile
import com.constrivo.drop.ui.shared.components.platformIcon
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.platform.LocalDropHaptics
import com.constrivo.drop.ui.shared.resources.*
import com.constrivo.drop.ui.shared.text.deviceNameText
import com.constrivo.drop.ui.shared.text.doneText
import com.constrivo.drop.ui.shared.text.hintText
import com.constrivo.drop.ui.shared.text.ringA11yText
import com.constrivo.drop.ui.shared.text.speedLineText
import com.constrivo.drop.ui.shared.text.stageText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropMotion
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropDark
import com.constrivo.drop.ui.shared.theme.LocalDropTypography
import com.constrivo.drop.ui.shared.theme.LocalReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

/** Rings, bubbles, the avatar and the drop flyers, laid out by [RadarFrame] for this area. */
@Composable
internal fun RadarField(
    state: RadarUiState,
    widthDp: Double,
    heightDp: Double,
    callbacks: RadarCallbacks,
) {
    val topReserve =
        RadarFrame.DEFAULT_TOP_RESERVE_DP +
            (if (state.attachment != null) BANNER_RESERVE_DP else 0.0) +
            (if (state.notice != null) NOTICE_RESERVE_DP else 0.0)
    // Placement depends only on who is where and how they rank, not on progress or every RSSI sample: keyed on that
    // projection, a progress snapshot or a small signal wobble does not re-run the placement solver.
    val placement = remember(state.bubbles) { state.bubbles.map(::placementKey) }
    val frame =
        remember(placement, widthDp, heightDp, topReserve) {
            RadarFrame.compute(state.bubbles, widthDp, heightDp, topReserveDp = topReserve)
        }
    // Departed bubbles stay composed until their leave animation ends (design §3.3), at their last position and as they
    // last were. Keys only: present bubbles are always drawn from the current state.
    val retained = remember { mutableStateListOf<String>().apply { addAll(state.bubbles.map { it.key }) } }
    // Plain caches (not snapshot state): written while composing, read by departed and replacing bubbles.
    val lastPositions = remember { HashMap<String, DpPoint>() }
    val lastBubbles = remember { HashMap<String, BubbleUi>() }
    val presentKeys = remember(placement) { state.bubbles.mapTo(LinkedHashSet()) { it.key } }
    LaunchedEffect(presentKeys) {
        val fresh = presentKeys.filter { it !in retained }
        if (fresh.isNotEmpty()) retained.addAll(fresh)
    }
    val current = state.bubbles.associateBy { it.key }
    for (b in state.bubbles) lastBubbles[b.key] = b
    // Where a replacing bubble starts (the same stranger under a new rotating ID): its predecessor's last position.
    val startPositions = HashMap<String, DpPoint>()
    for (b in state.bubbles) b.replacesKey?.let { old -> lastPositions[old]?.let { startPositions[b.key] = it } }
    for ((key, point) in frame.bubbles) lastPositions[key] = point
    val visibleKeys = frame.bubbles.keys
    val order = retained.toList() + presentKeys.filter { it !in retained }

    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Rings(frame, dimmed = state.ringsDimmed)
        for (key in order) {
            val bubble = current[key] ?: lastBubbles[key] ?: continue
            val present = key in current
            if (present && key !in visibleKeys) continue // folded into "+N more"
            val point = frame.bubbles[key] ?: lastPositions[key] ?: continue
            androidx.compose.runtime.key(key) {
                BubbleNode(
                    bubble = bubble,
                    present = present,
                    center = point,
                    startAt = startPositions[key],
                    avatar = frame.avatarCenter,
                    selected = key == state.selectedKey,
                    callbacks = callbacks,
                    onGone = {
                        if (key !in presentKeys) {
                            retained.remove(key)
                            lastPositions.remove(key)
                            lastBubbles.remove(key)
                        }
                    },
                )
            }
        }
        frame.overflowCenter?.let { center ->
            OverflowBubble(count = frame.overflowKeys.size, center = center, onClick = callbacks.onOverflowTap)
        }
        SelfAvatar(state, frame.avatarCenter)
        DropFlights(state.bubbles, frame)
    }
}

/** What placement depends on (RSSI only in 5 dB steps: it only ranks bubbles when a ring overflows). */
private data class PlacementKey(
    val key: String,
    val ring: Ring,
    val trusted: Boolean,
    val busy: Boolean,
    val rssiStep: Int?,
)

private fun placementKey(b: BubbleUi) = PlacementKey(b.key, b.ring, b.trusted, b.busy, b.rssiDbm?.let { floor(it / RSSI_STEP_DB).toInt() })

private const val RSSI_STEP_DB = 5.0

private const val BANNER_RESERVE_DP = 64.0
private const val NOTICE_RESERVE_DP = 104.0

/** The three rings with the pulse of design §3.3; static at 20% under reduced motion; dimmed for §8.1 errors. */
@Composable
private fun Rings(
    frame: RadarFrame,
    dimmed: Boolean,
) {
    val colors = LocalDropColors.current
    val dark = LocalDropDark.current
    val reduced = LocalReducedMotion.current
    val dim = if (dimmed) DropMotion.RING_DIMMED_FACTOR else 1f
    val pulses =
        if (reduced) {
            null
        } else {
            val transition = rememberInfiniteTransition()
            List(3) { i ->
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            tween(DropMotion.RING_PULSE_MILLIS, easing = DropMotion.EaseOut),
                            RepeatMode.Restart,
                            StartOffset(i * DropMotion.RING_STAGGER_MILLIS),
                        ),
                )
            }
        }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(frame.avatarCenter.x.toFloat() * density, frame.avatarCenter.y.toFloat() * density)
        val stroke = 2.dp.toPx()
        frame.ringRadii.forEachIndexed { i, rDp ->
            val r = rDp.toFloat() * density
            val base =
                if (reduced) {
                    colors.accent.copy(alpha = DropMotion.RING_STATIC_OPACITY * dim)
                } else {
                    val ring = colors.ring(i, dark)
                    ring.copy(alpha = ring.alpha * dim)
                }
            drawCircle(base, radius = r, center = c, style = Stroke(stroke))
            val p = pulses?.get(i)?.value ?: return@forEachIndexed
            val scale = DropMotion.RING_SCALE_START + (DropMotion.RING_SCALE_END - DropMotion.RING_SCALE_START) * p
            val alpha = DropMotion.RING_OPACITY_START * (1f - p) * dim
            drawCircle(colors.accent.copy(alpha = alpha), radius = r * scale, center = c, style = Stroke(stroke * 1.5f))
        }
    }
}

/** A dp offset that centres a [size] box on [center]. */
private fun Modifier.centeredAt(
    center: DpPoint,
    size: Dp,
): Modifier = absoluteOffset(x = (center.x.toFloat() - size.value / 2).dp, y = (center.y.toFloat() - size.value / 2).dp)

@Composable
private fun SelfAvatar(
    state: RadarUiState,
    center: DpPoint,
) {
    val description = stringResource(Res.string.a11y_self, state.self.nickname)
    Avatar(
        initials = state.selfInitials,
        hash = state.selfAvatarHash,
        size = DropDimens.avatar,
        image = state.self.avatar,
        modifier =
            Modifier
                .centeredAt(center, DropDimens.avatar)
                .semantics { contentDescription = description }
                .testTag(TestTags.RADAR_SELF),
    )
}

/**
 * One bubble with the motion of design §3.3 and the sending state of §4.2: appear and leave fades, 1.3× when selected
 * or busy with an overshoot, 400 ms reposition, the progress ring from 12 o'clock, the completion pop and tick.
 */
@Composable
private fun BubbleNode(
    bubble: BubbleUi,
    present: Boolean,
    center: DpPoint,
    startAt: DpPoint?,
    avatar: DpPoint,
    selected: Boolean,
    callbacks: RadarCallbacks,
    onGone: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val haptics = LocalDropHaptics.current
    val size = if (bubble.trusted) DropDimens.bubbleTrusted else DropDimens.bubble
    val activity = bubble.activity

    // Animated values are read only in the layout and draw phases (the Layout's measure block and graphicsLayer
    // lambdas), so appearing, moving, scaling and popping redraw the bubble without recomposing it (F‑C4: 60 fps).
    // A bubble that replaces another (a stranger's new rotating ID) starts where that one was, already visible.
    val visibility = remember { Animatable(if (startAt != null) 1f else 0f) }
    LaunchedEffect(present) {
        if (present) {
            visibility.animateTo(1f, tween(DropMotion.APPEAR_MILLIS, easing = DropMotion.Decelerate))
        } else {
            visibility.animateTo(0f, tween(DropMotion.LEAVE_MILLIS, easing = DropMotion.Accelerate))
            onGone()
        }
    }
    val origin = remember { startAt ?: center }
    val x = remember { Animatable(origin.x.toFloat()) }
    val y = remember { Animatable(origin.y.toFloat()) }
    LaunchedEffect(center) {
        val move = tween<Float>(DropMotion.REPOSITION_MILLIS, easing = DropMotion.Standard)
        launch { x.animateTo(center.x.toFloat(), move) }
        launch { y.animateTo(center.y.toFloat(), move) }
    }
    val raised = activity is BubbleActivity.Active || (selected && activity == null)
    val selectScale =
        animateFloatAsState(
            if (raised) DropMotion.SELECTED_SCALE else 1f,
            tween(DropMotion.SELECT_MILLIS, easing = DropMotion.Overshoot),
        )
    val pop = remember { Animatable(1f) }
    if (activity is BubbleActivity.Completed) {
        LaunchedEffect(activity.token) {
            haptics.confirm()
            pop.snapTo(DropMotion.COMPLETION_POP_SCALE)
            pop.animateTo(1f, tween(DropMotion.COMPLETION_POP_MILLIS, easing = DropMotion.Decelerate))
        }
    }
    val completed = activity is BubbleActivity.Completed
    val entry = if (present) DropMotion.APPEAR_SCALE_START else DropMotion.LEAVE_SCALE_END

    val name = deviceNameText(bubble.name)
    val ring = ringA11yText(bubble.ring, bubble.lanOnly)
    val label =
        if (bubble.trusted) {
            stringResource(Res.string.a11y_bubble_trusted, name, ring)
        } else {
            stringResource(Res.string.a11y_bubble, name, ring)
        }
    val sendLabel = stringResource(Res.string.a11y_action_send)

    // The bubble is pinned to its centre; the texts go below it, or above it when below would run into the avatar,
    // and are kept on screen horizontally. Every bubble reserves the footprint of its raised (1.3×) state with the
    // ring, so nothing moves when it is selected.
    val footprint = (size + RING_GAP) * DropMotion.SELECTED_SCALE
    val labelWidth = if (activity is BubbleActivity.Active) ACTIVE_LABEL_WIDTH else IDLE_LABEL_WIDTH
    Layout(
        modifier = Modifier.graphicsLayer { alpha = visibility.value },
        content = {
            Box(Modifier.size(footprint)) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(size + RING_GAP)
                            .graphicsLayer {
                                val v = visibility.value
                                val scale = (if (completed) pop.value else selectScale.value) * (entry + (1f - entry) * v)
                                scaleX = scale
                                scaleY = scale
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(size)
                                .clip(CircleShape)
                                .background(colors.accentSoft)
                                .clickable(role = Role.Button, onClickLabel = sendLabel, enabled = present) {
                                    callbacks.onBubbleTap(bubble.key)
                                }
                                .semantics { contentDescription = label }
                                .testTag(TestTags.bubble(bubble.key)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(bubble.initials, bubble.avatarHash, size - 8.dp, platform = bubble.platform)
                    }
                    BubbleRing(activity, selected, size)
                    // Platform glyph bottom-left, trust shield top-right, network glyph top-left (design §3.1, §3.2).
                    MiniGlyph(platformIcon(bubble.platform), Modifier.align(AbsoluteAlignment.BottomLeft))
                    if (bubble.trusted) {
                        Box(
                            Modifier.align(
                                AbsoluteAlignment.TopRight,
                            ).size(DropDimens.shield + 4.dp).clip(CircleShape).background(colors.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                DropIcons.Shield,
                                contentDescription = null,
                                tint = colors.successText,
                                modifier = Modifier.size(DropDimens.shield),
                            )
                        }
                    }
                    if (bubble.lanOnly) MiniGlyph(DropIcons.Network, Modifier.align(AbsoluteAlignment.TopLeft))
                    if (activity is BubbleActivity.Completed) {
                        Box(Modifier.size(size).clip(CircleShape).background(colors.success), contentAlignment = Alignment.Center) {
                            Icon(DropIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
                        }
                    }
                }
                if (activity is BubbleActivity.Active) {
                    CancelButton(
                        transferId = activity.transferId,
                        key = bubble.key,
                        onCancel = callbacks.onCancelTap,
                        modifier = Modifier.align(AbsoluteAlignment.BottomRight).absoluteOffset(x = 10.dp, y = 10.dp),
                    )
                }
            }
            Column(Modifier.width(labelWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    name,
                    style = type.label,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                ActivityLines(activity, name)
            }
        },
    ) { measurables, constraints ->
        val loose = Constraints()
        val bubblePlaceable = measurables[0].measure(loose)
        val texts = measurables[1].measure(loose)
        val cx = x.value.dp.roundToPx()
        val cy = y.value.dp.roundToPx()
        val half = bubblePlaceable.height / 2
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else cx * 2
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else cy * 2
        val textX = (cx - texts.width / 2).coerceIn(0, maxOf(0, width - texts.width))
        val avatarTop = (avatar.y.toFloat().dp - DropDimens.avatar / 2 - AVATAR_MARGIN).roundToPx()
        val avatarHalfWidth = (DropDimens.avatar / 2 + AVATAR_MARGIN).roundToPx()
        val ax = avatar.x.toFloat().dp.roundToPx()
        val belowTop = cy + half
        val hitsAvatar =
            belowTop + texts.height > avatarTop &&
                belowTop < avatarTop + (avatarHalfWidth * 2) &&
                textX < ax + avatarHalfWidth &&
                textX + texts.width > ax - avatarHalfWidth
        val tuck = if (raised) 0 else LABEL_TUCK.roundToPx()
        val textY = if (hitsAvatar) cy - half - texts.height + tuck else belowTop - tuck
        layout(width, height) {
            bubblePlaceable.place(cx - bubblePlaceable.width / 2, cy - half)
            texts.place(textX, textY)
        }
    }
}

private val RING_GAP = 10.dp
private val IDLE_LABEL_WIDTH = 104.dp
private val ACTIVE_LABEL_WIDTH = 200.dp

/** Room kept around the avatar that bubble texts must not cover. */
private val AVATAR_MARGIN = 4.dp

/** The footprint reserves the 1.3× state; idle labels tuck up into that spare room. */
private val LABEL_TUCK = 6.dp

@Composable
private fun MiniGlyph(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    Box(
        modifier =
            modifier.size(
                DropDimens.platformGlyph + 2.dp,
            ).clip(CircleShape).background(colors.surface).border(1.dp, colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(DropDimens.platformGlyph - 6.dp))
    }
}

/**
 * The 4 dp progress ring around a busy bubble: appears at 0% when the bubble is selected (design §3.3), fills
 * clockwise while it sends or receives, snaps full on completion. The fraction is read only while drawing.
 */
@Composable
private fun BubbleRing(
    activity: BubbleActivity?,
    selected: Boolean,
    size: Dp,
) {
    val colors = LocalDropColors.current
    val target =
        when (activity) {
            is BubbleActivity.Active -> activity.fraction
            is BubbleActivity.Completed -> 1f
            else -> 0f
        }
    val fraction =
        animateFloatAsState(
            target,
            if (activity is BubbleActivity.Active) tween(250, easing = LinearEasing) else snap(),
        )
    if (activity is BubbleActivity.Active || activity is BubbleActivity.Completed || (selected && activity == null)) {
        ProgressRing(
            fraction = { fraction.value },
            color = if (activity is BubbleActivity.Completed) colors.success else colors.accent,
            trackColor = colors.accent.copy(alpha = 0.15f),
            modifier = Modifier.size(size + 10.dp),
        )
    }
}

/** The × at the bubble's bottom-right (design §4.2): a 48 dp target around a small visual. */
@Composable
private fun CancelButton(
    transferId: String,
    key: String,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val label = stringResource(Res.string.a11y_cancel_transfer)
    Box(
        modifier =
            modifier
                .size(DropDimens.minTouch)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onCancel(transferId) }
                .semantics { contentDescription = label }
                .testTag(TestTags.cancel(key)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(colors.surface).border(1.dp, colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(DropIcons.Close, contentDescription = null, tint = colors.text, modifier = Modifier.size(14.dp))
        }
    }
}

/** "44 MB/s · 45 s left", the badge chip and the hint line (design §4.2), or the end caption. */
@Composable
private fun ActivityLines(
    activity: BubbleActivity?,
    peerName: String,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    when (activity) {
        is BubbleActivity.Active -> {
            val line =
                stageText(activity.stage, activity.direction, peerName, activity.paused)
                    ?: speedLineText(activity.bytesPerSecond, activity.etaMillis)
            if (line != null) Text(line, style = type.readout, color = colors.text, textAlign = TextAlign.Center)
            if (activity.resumed && activity.stage == TransferStage.TRANSFERRING) {
                Text(stringResource(Res.string.transfer_resumed), style = type.label, color = colors.textMuted)
            }
            activity.badge?.let { BadgeChip(it, Modifier.padding(top = 4.dp)) }
            activity.hint?.let {
                Text(
                    hintText(it),
                    style = type.label,
                    color = colors.warningText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val announcement =
                stringResource(
                    if (activity.direction == Direction.SEND) Res.string.a11y_sending else Res.string.a11y_receiving,
                    peerName,
                    activity.announcedPercent,
                )
            // Changes only at 25% steps, so screen readers announce progress every 25% (design §11).
            Box(
                Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    }.testTag(TestTags.PROGRESS_ANNOUNCER),
            )
        }

        is BubbleActivity.Completed -> {
            Text(doneText(activity.direction), style = type.readout, color = colors.successText)
        }

        is BubbleActivity.Ended -> {
            stageText(activity.stage, activity.direction, peerName)?.let {
                val color = if (activity.stage == TransferStage.FAILED) colors.dangerText else colors.textMuted
                Text(it, style = type.readout, color = color)
            }
        }

        null -> {}
    }
}

/** The "+N more" bubble on the outer ring (design §3.2). */
@Composable
private fun OverflowBubble(
    count: Int,
    center: DpPoint,
    onClick: () -> Unit,
) {
    val colors = LocalDropColors.current
    val label = stringResource(Res.string.more_devices, count)
    val action = stringResource(Res.string.a11y_action_show_more)
    Box(
        modifier =
            Modifier
                .centeredAt(center, DropDimens.bubble)
                .size(DropDimens.bubble)
                .clip(CircleShape)
                .background(colors.accentSoft)
                .border(1.dp, colors.accent.copy(alpha = 0.4f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(TestTags.RADAR_OVERFLOW),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Res.string.incoming_more, count), style = LocalDropTypography.current.bodyStrong, color = colors.accentText)
    }
}

/**
 * The drop animation (design §4.2): up to 8 thumbnails fly from the avatar to the bubble, 60 ms apart, 400 ms each on
 * cubic-bezier(0.2, 0.8, 0.2, 1), shrinking to 0.3; more than 8 items end with a "+N" tile. Skipped under reduced motion.
 */
@Composable
private fun DropFlights(
    bubbles: List<BubbleUi>,
    frame: RadarFrame,
) {
    if (LocalReducedMotion.current) return
    val played = remember { HashSet<String>() }
    for (bubble in bubbles) {
        val active = bubble.activity as? BubbleActivity.Active ?: continue
        val token = active.dropToken ?: continue
        val target = frame.bubbles[bubble.key] ?: continue
        if (token in played) continue
        androidx.compose.runtime.key(token) {
            Flight(active.flyers, active.fileCount, frame.avatarCenter, target) { played += token }
        }
    }
}

@Composable
private fun Flight(
    thumbs: List<FileThumb>,
    fileCount: Int,
    from: DpPoint,
    to: DpPoint,
    onDone: () -> Unit,
) {
    val plan = remember(thumbs, fileCount) { FlightPlan.of(thumbs, fileCount) }
    val progress = remember(plan) { List(plan.tiles.size) { Animatable(0f) } }
    LaunchedEffect(plan) {
        coroutineScope {
            progress.forEachIndexed { i, anim ->
                launch {
                    anim.animateTo(
                        1f,
                        tween(DropMotion.FLYER_MILLIS, delayMillis = i * DropMotion.FLYER_STAGGER_MILLIS, easing = DropMotion.Flyer),
                    )
                }
            }
        }
        onDone()
    }
    // Each tile's progress is read only in the layout (offset) and layer lambdas: the flight moves eight tiles every
    // frame without recomposing them.
    val half = DropDimens.flyer / 2
    plan.tiles.forEachIndexed { i, tile ->
        val anim = progress[i]
        Box(
            Modifier
                .absoluteOffset {
                    val p = anim.value
                    val cx = from.x + (to.x - from.x) * p
                    val cy = from.y + (to.y - from.y) * p
                    IntOffset((cx.toFloat().dp - half).roundToPx(), (cy.toFloat().dp - half).roundToPx())
                }.wrapContentSize()
                .graphicsLayer {
                    val p = anim.value
                    val s = 1f - (1f - DropMotion.FLYER_END_SCALE) * p
                    scaleX = s
                    scaleY = s
                    alpha =
                        when {
                            p <= 0f || p >= 1f -> 0f
                            p > 0.9f -> (1f - p) * 10f
                            else -> 1f
                        }
                },
        ) {
            when (tile) {
                is FlightPlan.Tile.Thumb -> ThumbTile(tile.thumb, DropDimens.flyer)
                is FlightPlan.Tile.More -> MoreTile(stringResource(Res.string.incoming_more, tile.count), DropDimens.flyer)
            }
        }
    }
}

/** Which tiles fly (design §4.2): up to 8; with more than 8 items, 7 thumbnails and a "+N" tile for the rest. */
internal data class FlightPlan(
    val tiles: List<Tile>,
) {
    sealed interface Tile {
        data class Thumb(
            val thumb: FileThumb,
        ) : Tile

        data class More(
            val count: Int,
        ) : Tile
    }

    companion object {
        fun of(
            thumbs: List<FileThumb>,
            fileCount: Int,
        ): FlightPlan {
            val max = DropMotion.MAX_FLYERS
            val total = maxOf(fileCount, thumbs.size)
            if (total <= max) {
                val shown = thumbs.take(total).map { Tile.Thumb(it) }
                val filler = List(total - shown.size) { Tile.Thumb(FileThumb.Glyph(FileKind.OTHER)) }
                return FlightPlan(shown + filler)
            }
            val shown = thumbs.take(max - 1).map { Tile.Thumb(it) }
            val filler = List(max - 1 - shown.size) { Tile.Thumb(FileThumb.Glyph(FileKind.OTHER)) }
            return FlightPlan(shown + filler + Tile.More(total - (max - 1)))
        }
    }
}
