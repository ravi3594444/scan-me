package com.constrivo.drop.ui.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.DropApp
import com.constrivo.drop.ui.shared.components.Avatar
import com.constrivo.drop.ui.shared.components.QuietButton
import com.constrivo.drop.ui.shared.components.SheetSurface
import com.constrivo.drop.ui.shared.components.SheetTitle
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.SenderPairingUi
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.RadarSheet
import com.constrivo.drop.ui.shared.presenter.Screen
import com.constrivo.drop.ui.shared.qr.QrMatrix
import com.constrivo.drop.ui.shared.receive.SenderPairingCallbacks
import com.constrivo.drop.ui.shared.receive.SenderPairingSheet
import com.constrivo.drop.ui.shared.send.QrCode
import com.constrivo.drop.ui.shared.text.deviceNameText
import com.constrivo.drop.ui.shared.text.sizeText
import com.constrivo.drop.ui.shared.theme.DropDimens
import com.constrivo.drop.ui.shared.theme.DropShapes
import com.constrivo.drop.ui.shared.theme.DropTheme
import com.constrivo.drop.ui.shared.theme.LocalDropColors
import com.constrivo.drop.ui.shared.theme.LocalDropTypography

/** Test tags of the desktop shell's own elements. */
object DesktopTags {
    const val BANNER = "desktop.banner"
    const val BANNER_QR = "desktop.banner.qr"
    const val DROP_AREA = "desktop.dropArea"
    const val DROP_HINT = "desktop.dropHint"
    const val SEND_TO = "desktop.sendTo"
    const val SEND_TO_CANCEL = "desktop.sendTo.cancel"
    const val PAIRING = "desktop.pairing"

    fun sendToDevice(key: String): String = "desktop.sendTo.$key"

    fun dropTarget(key: String): String = "desktop.dropTarget.$key"
}

/** The desktop banner above the radar (design §9), or none. */
sealed interface DesktopBanner {
    /** No Bluetooth on this computer: phones on the LAN still appear, or scan [code] (the static QR, F‑B6). */
    data class NoBluetooth(
        val code: String?,
    ) : DesktopBanner

    /** Not on any network: nothing can be found until the computer joins one. */
    data object NoNetwork : DesktopBanner
}

/**
 * A send's pairing code after its transfer ended (F‑B3): a small first send ends before anyone can compare the codes,
 * and the shared sheet goes with the transfer, so the desktop keeps asking until the user answers.
 */
data class FinishedPairing(
    val transferId: String,
    val peerName: String,
    val code: String,
)

/** What the answers to a [FinishedPairing] do: "Yes, it matches", or "Not now" / Cancel. */
class FinishedPairingActions(
    val confirm: (transferId: String) -> Unit = {},
    val dismiss: (transferId: String) -> Unit = {},
)

/**
 * The desktop window's content (design §9): the shared app ([DropApp]) with the desktop's banner above the radar, the
 * whole area as a drop zone ([ShellState]), the "Send to…" list over it, and a finished send's unanswered code.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(
    controller: DropAppController,
    shell: ShellState,
    strings: DesktopStrings,
    banner: DesktopBanner?,
    modifier: Modifier = Modifier,
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    pairing: FinishedPairing? = null,
    pairingActions: FinishedPairingActions = FinishedPairingActions(),
) {
    val screen by controller.screen.collectAsState()
    val coveredBySheet = sheetShowing(controller)
    val density = LocalDensity.current
    DropTheme(dark = dark) {
        val colors = LocalDropColors.current
        Column(modifier.fillMaxSize().background(colors.bg)) {
            if (banner != null && screen == Screen.RADAR) {
                BannerRow(banner, strings, onShowCode = { controller.openSheet(RadarSheet.SHOW_QR) })
            }
            // The whole area takes files; the bubbles' own targets inside it take a drop that lands on a bubble.
            val area = remember(shell) { FileDropTarget(shell, bubbleKey = null) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        shell.setArea(size.width / density.density.toDouble(), size.height / density.density.toDouble())
                    }.dragAndDropTarget(shouldStartDragAndDrop = ::carriesFiles, target = area)
                    .testTag(DesktopTags.DROP_AREA),
            ) {
                DropApp(controller, dark = dark)
                // Under a sheet or card the bubbles are hidden: a drop there goes to the area ("Send to…", or the open
                // picker), never to a bubble behind the sheet.
                val finished = pairing?.takeIf { !coveredBySheet && screen != Screen.ONBOARDING }
                val bubbleTargets = screen == Screen.RADAR && shell.sendTo == null && !coveredBySheet && finished == null
                if (bubbleTargets) BubbleDropTargets(controller, shell)
                if (shell.dragging) DropHint(shell.hover, strings)
                shell.sendTo?.let { sendTo -> SendToSheet(controller, shell, sendTo, strings) }
                if (finished != null && shell.sendTo == null) FinishedPairingSheet(finished, pairingActions)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun carriesFiles(event: DragAndDropEvent): Boolean = event.dragData() is DragData.FilesList

/** Whether one of the shared app's sheets or cards covers the screen (the picker, a code, a prompt, a card). */
@Composable
private fun sheetShowing(controller: DropAppController): Boolean {
    val radar by controller.radar.state.collectAsState()
    val radarSheet by controller.radarSheet.collectAsState()
    val incoming by controller.incoming.state.collectAsState()
    val installer by controller.installerWarning.collectAsState()
    val browser by controller.browserApproval.state.collectAsState()
    val permission by controller.permissions.state.collectAsState()
    return radarSheet != null || incoming != null || installer != null || browser != null || permission != null ||
        radar.senderPairing != null || radar.cancelConfirm != null
}

/** The shared sender's code sheet for a transfer that already ended, over a scrim that does not dismiss it. */
@Composable
private fun FinishedPairingSheet(
    pairing: FinishedPairing,
    actions: FinishedPairingActions,
) {
    val colors = LocalDropColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .testTag(DesktopTags.PAIRING),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SenderPairingSheet(
            SenderPairingUi(pairing.transferId, pairing.peerName, pairing.code),
            SenderPairingCallbacks(
                onConfirm = { actions.confirm(pairing.transferId) },
                onCancel = { actions.dismiss(pairing.transferId) },
                onLater = { actions.dismiss(pairing.transferId) },
            ),
        )
    }
}

/**
 * A drop target for files: the whole area ([bubbleKey] null) or one bubble. Compose hands a drag to the innermost
 * target under the pointer, so the area's target sees only drags over empty space.
 */
private class FileDropTarget(
    private val shell: ShellState,
    private val bubbleKey: String?,
) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = shell.onDragOver(bubbleKey)

    override fun onMoved(event: DragAndDropEvent) {
        if (bubbleKey == null && shell.hover != null) shell.onDragOver(null)
    }

    override fun onExited(event: DragAndDropEvent) {
        // Leaving a bubble returns to the area; leaving the area leaves the window.
        if (bubbleKey != null) shell.onDragOver(null) else shell.onDragEnded()
    }

    override fun onEnded(event: DragAndDropEvent) = shell.onDragEnded()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onDrop(event: DragAndDropEvent): Boolean {
        val files = event.dragData() as? DragData.FilesList ?: return false
        return shell.onDrop(DropTargets.paths(files.readFiles()), bubbleKey)
    }
}

/** An invisible drop target over each bubble ([RadarHitTest.targets]); pointer input passes through to the bubbles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleDropTargets(
    controller: DropAppController,
    shell: ShellState,
) {
    val radar by controller.radar.state.collectAsState()
    val targets = RadarHitTest.targets(radar, shell.areaWidthDp, shell.areaHeightDp)
    Box(Modifier.fillMaxSize()) {
        for (t in targets) {
            key(t.key) {
                val target = remember(shell, t.key) { FileDropTarget(shell, t.key) }
                Box(
                    Modifier
                        .absoluteOffset(x = (t.xDp - t.radiusDp).dp, y = (t.yDp - t.radiusDp).dp)
                        .size((2 * t.radiusDp).dp)
                        .dragAndDropTarget(shouldStartDragAndDrop = ::carriesFiles, target = target)
                        .testTag(DesktopTags.dropTarget(t.key)),
                )
            }
        }
    }
}

@Composable
private fun BannerRow(
    banner: DesktopBanner,
    strings: DesktopStrings,
    onShowCode: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DropDimens.gutter, vertical = 8.dp)
            .background(colors.surface, DropShapes.card)
            .border(1.dp, colors.outline, DropShapes.card)
            .padding(12.dp)
            .testTag(DesktopTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (banner) {
            is DesktopBanner.NoBluetooth -> {
                Text(strings["banner.noBluetooth"], style = type.body, color = colors.text, modifier = Modifier.weight(1f))
                val matrix = remember(banner.code) { banner.code?.let { runCatching { QrMatrix.encode(it) }.getOrNull() } }
                if (matrix != null) {
                    Spacer(Modifier.width(12.dp))
                    val label = strings["banner.code"]
                    Box(
                        Modifier
                            .size(BANNER_QR_SIZE)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = label, onClick = onShowCode)
                            .semantics { contentDescription = label }
                            .padding(4.dp)
                            .testTag(DesktopTags.BANNER_QR),
                    ) {
                        QrCode(matrix, Modifier.fillMaxSize())
                    }
                }
            }

            DesktopBanner.NoNetwork -> {
                Text(strings["banner.noNetwork"], style = type.body, color = colors.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** While files are dragged over the window: an accent frame and what a drop would do. */
@Composable
private fun DropHint(
    hover: BubbleUi?,
    strings: DesktopStrings,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val name = hover?.let { deviceNameText(it.name) }
    Box(Modifier.fillMaxSize().border(3.dp, colors.accent).testTag(DesktopTags.DROP_HINT)) {
        Text(
            if (name != null) strings["drop.onDevice", name] else strings["drop.here"],
            style = type.bodyStrong,
            color = colors.onPrimaryButton,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = DropDimens.gutter, end = DropDimens.gutter)
                    .background(colors.primaryButton, DropShapes.chip)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** "Send to…" with the bubble list (design §9), over a scrim that cancels it. */
@Composable
private fun SendToSheet(
    controller: DropAppController,
    shell: ShellState,
    sendTo: SendToUi,
    strings: DesktopStrings,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val radar by controller.radar.state.collectAsState()
    val devices = shell.sendToDevices(radar.bubbles)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = shell::cancelSendTo),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SheetSurface(
            Modifier
                // Clicks inside the sheet do not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .testTag(DesktopTags.SEND_TO),
        ) {
            SheetTitle(strings["sendTo.title"])
            Spacer(Modifier.size(4.dp))
            Text(strings["sendTo.summary", sendTo.fileCount, sizeText(sendTo.totalBytes)], style = type.caption, color = colors.textMuted)
            if (sendTo.truncated) {
                Text(strings["sendTo.truncated", sendTo.limit], style = type.caption, color = colors.warningText)
            }
            Spacer(Modifier.size(8.dp))
            if (devices.isEmpty()) {
                Text(strings["sendTo.empty"], style = type.body, color = colors.text, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                val list = rememberLazyListState()
                LaunchedEffect(sendTo.highlighted) { list.animateScrollToItem(sendTo.highlighted.coerceIn(0, devices.size - 1)) }
                LazyColumn(Modifier.heightIn(max = SEND_TO_LIST_MAX), state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(devices, key = { _, b -> b.key }) { index, bubble ->
                        DeviceRow(bubble, highlighted = index == sendTo.highlighted, onClick = { shell.choose(bubble.key) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuietButton(
                    strings["sendTo.cancel"],
                    onClick = shell::cancelSendTo,
                    modifier = Modifier.testTag(DesktopTags.SEND_TO_CANCEL),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    bubble: BubbleUi,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDropColors.current
    val type = LocalDropTypography.current
    val name = deviceNameText(bubble.name)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DropDimens.minTouch)
            .background(if (highlighted) colors.accentSoft else colors.surface, DropShapes.button)
            .clickable(enabled = !bubble.busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(DesktopTags.sendToDevice(bubble.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(bubble.initials, bubble.avatarHash, SEND_TO_AVATAR, platform = bubble.platform)
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = type.body,
            color = if (bubble.busy) colors.textMuted else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private val BANNER_QR_SIZE = 96.dp
private val SEND_TO_LIST_MAX = 280.dp
private val SEND_TO_AVATAR = 36.dp
