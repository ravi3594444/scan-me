package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.data.VisibilityPreference
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTransfer

/** The tray / menu-bar icon's three states (design §9). */
enum class TrayState {
    /** Nothing going on. */
    IDLE,

    /** A transfer is moving: the icon's arc animates. */
    TRANSFERRING,

    /** An incoming card, a code to compare or a browser to allow waits for the user. */
    ATTENTION,
}

/** One entry of the tray menu (design §9: Open, Visibility, Received folder, Quit). */
sealed interface TrayMenuItem {
    val label: String

    data class Action(
        override val label: String,
        val action: TrayAction,
    ) : TrayMenuItem

    /** A radio group: one [Choice] per visibility mode, the mode in force checked. */
    data class Choices(
        override val label: String,
        val choices: List<Choice>,
    ) : TrayMenuItem

    data class Toggle(
        override val label: String,
        val checked: Boolean,
        val action: TrayAction,
    ) : TrayMenuItem

    data object Separator : TrayMenuItem {
        override val label: String = ""
    }

    data class Choice(
        val label: String,
        val visibility: Visibility,
        val checked: Boolean,
    )
}

/** What a tray menu entry does. */
enum class TrayAction { OPEN, RECEIVED_FOLDER, TOGGLE_AUTOSTART, QUIT }

/** What the tray shows at one moment. */
data class TrayView(
    val state: TrayState,
    val tooltip: String,
    val menu: List<TrayMenuItem>,
)

/**
 * The tray's content as a pure function of the app's state (design §9), so it is tested without a display and the AWT
 * binding ([SystemTrayController]) only renders it.
 *
 * - State: [TrayState.ATTENTION] when something waits for the user (an incoming offer, a SAS to compare on the
 *   sender's side, a browser asking to connect), else [TrayState.TRANSFERRING] while any transfer runs, else
 *   [TrayState.IDLE]. Attention wins, since it is the one the user must act on.
 * - Menu: Open, Visibility (the four modes, the chosen one checked), Received folder, Start at login (only where
 *   auto-start is available, F‑H5), Quit.
 */
object TrayModel {
    /** The four modes in the order the Visibility sheet lists them (design §2). */
    val VISIBILITY_ORDER: List<Visibility> =
        listOf(Visibility.EVERYONE_TEN_MINUTES, Visibility.EVERYONE, Visibility.TRUSTED_ONLY, Visibility.HIDDEN)

    fun state(
        waitingOffers: Int,
        pairingCodes: Int,
        browserPrompts: Int,
        runningTransfers: Int,
    ): TrayState =
        when {
            waitingOffers > 0 || pairingCodes > 0 || browserPrompts > 0 -> TrayState.ATTENTION
            runningTransfers > 0 -> TrayState.TRANSFERRING
            else -> TrayState.IDLE
        }

    /** The stages after which a transfer no longer moves (kept briefly on the radar for its completion animation). */
    private val ENDED: Set<NodeStage> =
        setOf(NodeStage.DONE, NodeStage.FAILED, NodeStage.CANCELLED, NodeStage.DECLINED, NodeStage.NO_ANSWER)

    /**
     * The state for the node's [offers] and [transfers] and whether a browser asks to connect ([browserPrompt]): a
     * sender's pairing code counts as waiting for the user until the transfer ends.
     */
    fun state(
        offers: List<NodeOffer>,
        transfers: List<NodeTransfer>,
        browserPrompt: Boolean,
    ): TrayState {
        val running = transfers.filter { it.stage !in ENDED }
        return state(
            waitingOffers = offers.size,
            pairingCodes = running.count { it.direction == NodeDirection.SEND && it.pairingCode != null },
            browserPrompts = if (browserPrompt) 1 else 0,
            runningTransfers = running.size,
        )
    }

    /**
     * The mode to check in the menu: "Everyone for 10 min" while its window is open (not the "Everyone" it amounts
     * to), then the mode it reverts to ([VisibilityPreference.effectiveAt]).
     */
    fun shownVisibility(
        preference: VisibilityPreference,
        nowMillis: Long,
    ): Visibility = preference.effectiveAt(nowMillis)

    fun view(
        state: TrayState,
        visibility: Visibility,
        autoStartAvailable: Boolean,
        autoStartEnabled: Boolean,
        strings: DesktopStrings,
    ): TrayView {
        val menu = ArrayList<TrayMenuItem>()
        menu += TrayMenuItem.Action(strings["tray.open"], TrayAction.OPEN)
        menu +=
            TrayMenuItem.Choices(
                strings["tray.visibility"],
                VISIBILITY_ORDER.map { TrayMenuItem.Choice(visibilityLabel(it, strings), it, it == visibility) },
            )
        menu += TrayMenuItem.Action(strings["tray.receivedFolder"], TrayAction.RECEIVED_FOLDER)
        if (autoStartAvailable) {
            menu += TrayMenuItem.Separator
            menu += TrayMenuItem.Toggle(strings["tray.autostart"], autoStartEnabled, TrayAction.TOGGLE_AUTOSTART)
        }
        menu += TrayMenuItem.Separator
        menu += TrayMenuItem.Action(strings["tray.quit"], TrayAction.QUIT)
        val tooltip =
            when (state) {
                TrayState.IDLE -> strings["tray.tooltip.idle"]
                TrayState.TRANSFERRING -> strings["tray.tooltip.transferring"]
                TrayState.ATTENTION -> strings["tray.tooltip.attention"]
            }
        return TrayView(state, tooltip, menu)
    }

    fun visibilityLabel(
        visibility: Visibility,
        strings: DesktopStrings,
    ): String =
        when (visibility) {
            Visibility.EVERYONE -> strings["tray.visibility.everyone"]
            Visibility.EVERYONE_TEN_MINUTES -> strings["tray.visibility.everyone10"]
            Visibility.TRUSTED_ONLY -> strings["tray.visibility.trusted"]
            Visibility.HIDDEN -> strings["tray.visibility.hidden"]
        }

    /** Frames of the transferring arc (design §9 "animated arc"): one full turn in [ARC_FRAMES] steps. */
    const val ARC_FRAMES: Int = 12

    /** How long each arc frame shows. */
    const val ARC_FRAME_MILLIS: Long = 100

    /** The arc's start angle in degrees for [frame] (0 at the top, clockwise). */
    fun arcStartDegrees(frame: Int): Int = Math.floorMod(frame, ARC_FRAMES) * (360 / ARC_FRAMES)
}
