package com.constrivo.drop.ui.shared.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.ui.shared.icons.DropIcons
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.text.badgeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography

/** The platform glyph of design §3.1 / §12. */
fun platformIcon(platform: DevicePlatform): ImageVector =
    when (platform) {
        DevicePlatform.PHONE -> DropIcons.Phone
        DevicePlatform.LAPTOP -> DropIcons.Laptop
        DevicePlatform.DESKTOP -> DropIcons.Desktop
        DevicePlatform.BROWSER_PROXY -> DropIcons.Browser
        DevicePlatform.UNKNOWN -> DropIcons.Device
    }

/** The glyph of a file kind (design §5.1: documents show file-type glyphs). */
fun fileKindIcon(kind: FileKind): ImageVector =
    when (kind) {
        FileKind.IMAGE -> DropIcons.Image
        FileKind.VIDEO -> DropIcons.Video
        FileKind.AUDIO -> DropIcons.Audio
        FileKind.DOCUMENT -> DropIcons.File
        FileKind.ARCHIVE -> DropIcons.Archive
        FileKind.APP -> DropIcons.App
        FileKind.OTHER -> DropIcons.File
    }

/**
 * A round avatar: the photo when there is one, else initials on the palette colour of [hash] (design §12), else the
 * platform glyph. Decorative: callers put the name in the semantics of the surrounding element.
 */
@Composable
fun Avatar(
    initials: String?,
    hash: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    platform: DevicePlatform = DevicePlatform.PHONE,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(colors.avatar(hash)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            }

            initials != null -> {
                Text(
                    initials,
                    color = Color.White,
                    // Sized to the circle, not the font scale: the initials must fit their fixed-size avatar.
                    style = type.bodyStrong.copy(fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }),
                    maxLines = 1,
                )
            }

            else -> {
                Icon(platformIcon(platform), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
            }
        }
    }
}

/** The transport badge chip (design §4.2): text on a surface chip, never colour alone (design §11). */
@Composable
fun BadgeChip(
    badge: TransportBadge,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDropColors.current
    val icon =
        when (badge.kind) {
            LinkKind.LAN -> DropIcons.Network
            LinkKind.P2P -> DropIcons.Wifi
            LinkKind.HOTSPOT -> DropIcons.Hotspot
            LinkKind.BLUETOOTH -> DropIcons.Bluetooth
        }
    Row(
        modifier =
            modifier
                .clip(DropShapes.chip)
                .background(colors.surface)
                .border(1.dp, colors.outline, DropShapes.chip)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(badgeText(badge), style = LocalDropTypography.current.caption, color = colors.text)
    }
}

/** A square thumbnail or type glyph (tray, flyers, card strip, picker). */
@Composable
fun ThumbTile(
    thumb: FileThumb,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val colors = LocalDropColors.current
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier.size(size).clip(shape).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        when (thumb) {
            is FileThumb.Picture -> {
                Image(thumb.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            }

            is FileThumb.Glyph -> {
                Icon(
                    fileKindIcon(thumb.kind),
                    contentDescription = null,
                    tint = colors.accentText,
                    modifier =
                        Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

/** A "+N" tile (design §4.2 last flyer, §5.1 strip). */
@Composable
fun MoreTile(
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val colors = LocalDropColors.current
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(corner)).background(colors.primaryButton),
        contentAlignment = Alignment.Center,
    ) {
        // Sized to the tile, not the font scale, so "+N" always fits its fixed-size tile.
        val fontSize = with(LocalDensity.current) { (size * 0.34f).toSp() }
        Text(text, color = colors.onPrimaryButton, style = LocalDropTypography.current.bodyStrong.copy(fontSize = fontSize), maxLines = 1)
    }
}

/** A clockwise progress ring from 12 o'clock (design §4.2), [strokeWidth] 4 dp. */
@Composable
fun ProgressRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = DropDimens.progressRing,
    trackColor: Color = Color.Transparent,
) = ProgressRing({ fraction }, color, modifier, strokeWidth, trackColor)

/**
 * A clockwise progress ring from 12 o'clock whose [fraction] is read only while drawing: an animated value then
 * redraws the ring every frame without recomposing it or its parent (F‑C4: 60 fps during a transfer).
 */
@Composable
fun ProgressRing(
    fraction: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = DropDimens.progressRing,
    trackColor: Color = Color.Transparent,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        if (trackColor.alpha > 0f) {
            drawArc(trackColor, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
        }
        val sweep = 360f * fraction().coerceIn(0f, 1f)
        if (sweep > 0f) {
            drawArc(
                color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * The surface of every sheet (design §1: 16 dp corners, one elevation level). Sheets are laid out by the host
 * ([SheetHost]); this is their shape and padding.
 */
@Composable
fun SheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDropColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(DropShapes.sheet)
                .background(colors.surface)
                .navigationBarsPadding()
                .padding(start = DropDimens.gutter, end = DropDimens.gutter, bottom = DropDimens.gutter),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(colors.outline))
        }
        content()
    }
}

/** A sheet heading (title style, marked as a heading for screen readers). */
@Composable
fun SheetTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = LocalDropTypography.current.title,
        color = LocalDropColors.current.text,
        modifier = modifier.semantics { heading() },
    )
}

/** Full-width primary button (design §1: 12 dp corners; AA colours from [com.constrivo.drop.ui.shared.theme.DropColors]). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalDropColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = DropShapes.button,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.primaryButton,
                contentColor = colors.onPrimaryButton,
                disabledContainerColor = colors.textMuted.copy(alpha = 0.16f),
                disabledContentColor = colors.textMuted,
            ),
        modifier = modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch),
    ) {
        Text(text, style = LocalDropTypography.current.button, textAlign = TextAlign.Center)
    }
}

/** A text button in the accent text colour (design §5.1 "Decline", §5.2 "Open folder"). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalDropColors.current.accentText,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = DropShapes.button,
        modifier = modifier.heightIn(min = DropDimens.minTouch),
    ) {
        Text(
            text,
            style = LocalDropTypography.current.button,
            color = if (enabled) color else LocalDropColors.current.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** A 48 dp round icon button with a spoken [label] (design §11). */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalDropColors.current.text,
    background: Color = Color.Transparent,
    iconSize: Dp = DropDimens.icon,
) {
    Box(
        modifier =
            modifier
                .size(DropDimens.minTouch)
                .clip(CircleShape)
                .background(background)
                .clickable(role = Role.Button, onClickLabel = null, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** An icon in a tinted circle, for notices and explainers (design §8.1 illustrations, simplified). */
@Composable
fun GlyphBadge(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A labelled row of content with the gutter padding, used by settings and detail sheets. */
@Composable
fun LabelledRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = DropDimens.minTouch).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
