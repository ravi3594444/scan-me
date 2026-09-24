package com.constrivo.drop.ui.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.constrivo.drop.platform.desktop.files.ExpandedItems
import com.constrivo.drop.platform.desktop.files.SendItems
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.RadarSheet
import com.constrivo.drop.ui.shared.presenter.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path

/** The "Send to…" list on screen (design §9): what was dropped, and the row Enter sends to. */
data class SendToUi(
    val fileCount: Int,
    val totalBytes: Long,
    /** More than the protocol's per-transfer maximum was dropped; the first [limit] files are sent (F‑C6). */
    val truncated: Boolean,
    val limit: Int,
    val highlighted: Int = 0,
)

/**
 * The desktop shell's own state around the shared app (design §9): the whole-window drop zone, the "Send to…" list
 * and the keyboard shortcuts. Main-thread confined like [DropAppController], which it drives through its public
 * entry points only: dropped files are attached as a share ([DropAppController.onShareIntent], the §4.3 banner), then
 * sent with a bubble tap ([DropAppController.onBubbleTap]) — so a drop behaves exactly like tapping a bubble with
 * shared files, including the pairing code of a first send.
 *
 * Folders are expanded on [io] (F‑C6: a dropped folder of 1,000 files is walked off the UI thread).
 */
@Stable
class ShellState(
    private val controller: DropAppController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val expand: (List<Path>) -> ExpandedItems = { SendItems.expand(it) },
    private val onProblem: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /** The drop area's size in dp (the window below the desktop banner), from its layout. */
    var areaWidthDp: Double by mutableStateOf(0.0)
        private set
    var areaHeightDp: Double by mutableStateOf(0.0)
        private set

    /** Files are being dragged over the window. */
    var dragging: Boolean by mutableStateOf(false)
        private set

    /** The idle bubble under the pointer while dragging, which a drop would send to. */
    var hover: BubbleUi? by mutableStateOf(null)
        private set

    /** The "Send to…" list, or null. */
    var sendTo: SendToUi? by mutableStateOf(null)
        private set

    /** Folders are being walked for a drop or a pick. */
    var preparing: Boolean by mutableStateOf(false)
        private set

    private var preparation: Job? = null

    fun setArea(
        widthDp: Double,
        heightDp: Double,
    ) {
        areaWidthDp = widthDp
        areaHeightDp = heightDp
    }

    /** The radar's bubble under [key], when the radar is showing. */
    private fun bubble(key: String?): BubbleUi? {
        if (key == null || controller.screen.value != Screen.RADAR) return null
        return controller.radar.state.value.bubbles.firstOrNull { it.key == key }
    }

    /** Files are dragged over the window, over the drop target of bubble [bubbleKey] or over empty space (null). */
    fun onDragOver(bubbleKey: String?) {
        dragging = true
        hover = bubble(bubbleKey)?.takeUnless { it.busy }
    }

    fun onDragEnded() {
        dragging = false
        hover = null
    }

    /**
     * Files dropped on bubble [bubbleKey], or on empty space (null): sent to that bubble when it is idle, otherwise
     * offered in "Send to…" (design §9). Returns whether the drop was taken.
     */
    fun onDrop(
        paths: List<Path>,
        bubbleKey: String?,
    ): Boolean {
        val decision = DropTargets.decide(controller.screen.value, paths.isNotEmpty(), bubble(bubbleKey))
        onDragEnded()
        if (decision == DropDecision.Ignore) return false
        prepare(paths, decision)
        return true
    }

    /** Files and folders picked with Ctrl/Cmd+O: offered in "Send to…". */
    fun onPicked(paths: List<Path>) {
        if (paths.isEmpty() || controller.screen.value == Screen.ONBOARDING) return
        prepare(paths, DropDecision.ChooseDevice)
    }

    private fun prepare(
        paths: List<Path>,
        decision: DropDecision,
    ) {
        preparation?.cancel()
        preparing = true
        preparation =
            scope.launch {
                try {
                    val expanded = withContext(io) { expand(paths) }
                    if (expanded.files.isEmpty()) return@launch
                    val files = AttachedFiles(PortMappers.pickedItems(expanded.files))
                    controller.onShareIntent(files)
                    if (decision is DropDecision.SendTo) {
                        sendTo = null
                        controller.onBubbleTap(decision.deviceKey)
                    } else {
                        sendTo = SendToUi(files.count, files.totalBytes, expanded.truncated, SendItems.maxFiles)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    onProblem("the dropped files could not be read", e)
                } catch (e: UncheckedIOException) {
                    onProblem("the dropped files could not be read", e)
                } catch (e: SecurityException) {
                    onProblem("the dropped files could not be read", e)
                } finally {
                    preparing = false
                }
            }
    }

    /** "Send to…" → a device: the attached files go to it, as a tap on its bubble would send them. */
    fun choose(deviceKey: String) {
        val radar = controller.radar.state.value
        val bubble = radar.bubbles.firstOrNull { it.key == deviceKey } ?: return
        if (bubble.busy) return
        sendTo = null
        // The files were cleared elsewhere (the banner's ×): nothing to send, and a tap would open the picker.
        if (radar.attachment == null) return
        controller.onBubbleTap(deviceKey)
    }

    /** "Send to…" dismissed (Cancel, Esc, the scrim): the dropped files are let go. */
    fun cancelSendTo() {
        if (sendTo == null) return
        sendTo = null
        controller.radar.clearAttachment()
    }

    /** Moves the "Send to…" highlight by [delta] rows among [count], wrapping around. */
    fun moveHighlight(
        delta: Int,
        count: Int,
    ) {
        val current = sendTo ?: return
        if (count <= 0) return
        sendTo = current.copy(highlighted = Math.floorMod(current.highlighted + delta, count))
    }

    /** The devices "Send to…" lists: every bubble, idle ones first, in the radar's order. */
    fun sendToDevices(bubbles: List<BubbleUi>): List<BubbleUi> = bubbles.sortedBy { it.busy }

    /** Enter in "Send to…": the highlighted device. */
    fun sendHighlighted() {
        val current = sendTo ?: return
        val devices = sendToDevices(controller.radar.state.value.bubbles)
        val device = devices.getOrNull(current.highlighted.coerceIn(0, maxOf(devices.size - 1, 0))) ?: return
        choose(device.key)
    }

    /** What the keyboard shortcuts need to know (design §9). */
    fun shortcutContext(): ShortcutContext =
        ShortcutContext(
            screen = controller.screen.value,
            sendToOpen = sendTo != null,
            pickerOpen = controller.radarSheet.value == RadarSheet.PICKER,
            pickerHasSelection = controller.picker.selection().items.isNotEmpty(),
        )

    /**
     * Runs [action] (design §9): Esc closes "Send to…", else whatever Back closes, else lets go of the attached files;
     * Enter sends to the highlighted device or the picker's selection. [pickFiles] opens the file dialog.
     */
    fun perform(
        action: ShortcutAction,
        pickFiles: () -> Unit,
    ) {
        when (action) {
            ShortcutAction.PICK_FILES -> {
                pickFiles()
            }

            ShortcutAction.CANCEL -> {
                when {
                    sendTo != null -> cancelSendTo()
                    controller.back() -> Unit
                    else -> controller.radar.clearAttachment()
                }
            }

            ShortcutAction.SEND -> {
                if (sendTo != null) sendHighlighted() else controller.sendPicked()
            }

            ShortcutAction.PREVIOUS -> {
                moveHighlight(-1, controller.radar.state.value.bubbles.size)
            }

            ShortcutAction.NEXT -> {
                moveHighlight(1, controller.radar.state.value.bubbles.size)
            }
        }
    }
}
