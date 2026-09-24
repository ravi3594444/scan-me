package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.discovery.EphemeralIds
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import com.constrivo.drop.platform.desktop.node.DesktopNode
import com.constrivo.drop.platform.desktop.node.NodeEvent
import com.constrivo.drop.ui.shared.presenter.DropAppController
import com.constrivo.drop.ui.shared.presenter.OnboardingConfig
import com.constrivo.drop.web.BrowserApprover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** What the app asks of its window. */
enum class WindowRequest {
    /** Show the window and bring it to the front (tray "Open", a second start, a notification click). */
    SHOW,

    /** Close the window and end the app (tray "Quit", or closing the window where there is no tray). */
    QUIT,
}

/**
 * The desktop app around a started [DesktopNode] (architecture §10.2): the shared UI's controller over [DesktopPorts],
 * the shell's drop zone and shortcuts, the tray and the notifications (design §9). Main-thread confined like the
 * controller: [main] is the Swing event thread in the app (Compose's main thread on the desktop), a test dispatcher in
 * tests.
 *
 * Closing the window hides it when a tray icon is there to bring it back; the app ends with tray "Quit" ([shutdown]
 * stops the node, so the ladders tear their links down first, F‑E11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopApp(
    val node: DesktopNode,
    private val services: DesktopPlatformServices,
    private val host: DesktopHost,
    val hasLan: Boolean,
    appVersion: String,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = ::logToStderr,
) {
    val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, e -> log("unexpected failure in the desktop app", e) })

    private val stringsState = MutableStateFlow(DesktopStrings.forLanguage(node.settings.value?.language))

    /** The shell's own copy in the language of Settings → Language (decision 9). */
    val strings: StateFlow<DesktopStrings> = stringsState.asStateFlow()

    val ports = DesktopPorts(node, scope, host, { stringsState.value }, appVersion, io)

    /** The first start asks for a nickname (design §7); the computer's name is the suggestion. */
    val controller: DropAppController =
        DropAppController(
            scope,
            ports.dependencies(
                if (node.settings.value?.nickname == null) OnboardingConfig(node.config.defaultNickname, null, ports) else null,
            ),
        )

    val shell = ShellState(controller, scope, io, onProblem = log)

    /** Whether the window has the focus (notifications that only matter in the background are held back meanwhile). */
    val windowFocused = MutableStateFlow(true)

    // A channel, not a shared flow: a request made before the window collects (a second start during launch) waits.
    private val windowRequestChannel = Channel<WindowRequest>(REQUEST_BUFFER, BufferOverflow.DROP_OLDEST)

    /** Requests for the window ([WindowRequest]); collected by the one window. */
    val windowRequests: Flow<WindowRequest> = windowRequestChannel.receiveAsFlow()

    private val autoStartEnabled = MutableStateFlow(false)
    private val clicks = NotificationClicks(node.config.monotonicClock)
    private var tray: SystemTrayController? = null

    /** The banner above the radar (design §9): no network, or no Bluetooth with the static code (refreshed each epoch). */
    val banner: StateFlow<DesktopBanner?> =
        when {
            !hasLan -> flowOf(DesktopBanner.NoNetwork)
            node.bluetoothAvailable -> flowOf(null)
            else -> staticCodes().map { DesktopBanner.NoBluetooth(it) }
        }.stateIn(scope, SharingStarted.Eagerly, if (!hasLan) DesktopBanner.NoNetwork else null)

    /** What the tray shows ([TrayModel]). */
    val trayView: StateFlow<TrayView> =
        combine(
            combine(node.offers, node.transfers, controller.browserApproval.state) { offers, transfers, prompt ->
                TrayModel.state(offers, transfers, prompt != null)
            },
            combine(node.visibility, node.effectiveVisibility) { preference, _ ->
                TrayModel.shownVisibility(preference, node.config.wallClock.nowMillis())
            },
            stringsState,
            autoStartEnabled,
        ) { state, visibility, strings, autoStart ->
            TrayModel.view(state, visibility, services.autoStart.isAvailable, autoStart, strings)
        }.distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                TrayModel.view(TrayState.IDLE, node.visibility.value.mode, services.autoStart.isAvailable, false, stringsState.value),
            )

    /**
     * The notifications to show now (design §9), for the tray icon's messages: finished transfers, and each incoming
     * card once while it waits.
     */
    val notifications: Flow<DesktopNotification> =
        channelFlow {
            launch {
                node.events.filterIsInstance<NodeEvent.TransferFinished>().collect { event ->
                    Notifications.finished(event, stringsState.value)?.let { send(it) }
                }
            }
            launch {
                val announced = HashSet<String>()
                node.offers.collect { offers ->
                    for (offer in offers) {
                        if (announced.add(offer.id)) send(Notifications.incoming(offer, stringsState.value))
                    }
                    announced.retainAll(offers.mapTo(HashSet()) { it.id })
                }
            }
        }.filter { Notifications.shouldShow(it, windowFocused.value) }

    /** Wires what the controller cannot do itself; call once, on [main]. */
    fun start() {
        ports.onFilesChosen = controller::onFilesPicked
        node.browserApprover =
            BrowserApprover { request ->
                // On the main thread like every controller call; the server's timeout still cancels the question.
                withContext(main) {
                    controller.browserApproval.ask(request.browserNumber, request.remoteAddress, request.userAgent)
                }
            }
        scope.launch { controller.language.collect { stringsState.value = DesktopStrings.forLanguage(it.tag) } }
        scope.launch { node.events.filterIsInstance<NodeEvent.Problem>().collect { log(it.message, it.error) } }
        scope.launch { ports.problems.collect { log(it.message, it.error) } }
        refreshAutoStart()
    }

    /**
     * Adds the tray icon and keeps it current (design §9: idle, the animated arc while transferring, attention); false
     * when this desktop has no tray. Call on [main], which must be the AWT event thread.
     */
    fun installTray(): Boolean {
        val controller = SystemTrayController(onClick = ::onTrayClick, onAction = ::onTrayAction, onVisibility = ::onTrayVisibility)
        if (!controller.install(trayView.value)) return false
        tray = controller
        scope.launch {
            trayView.collectLatest { view ->
                controller.update(view, 0)
                if (view.state == TrayState.TRANSFERRING) {
                    var frame = 0
                    while (true) {
                        delay(TrayModel.ARC_FRAME_MILLIS)
                        controller.update(view, ++frame)
                    }
                }
            }
        }
        scope.launch {
            notifications.collect { n ->
                if (controller.notify(n)) clicks.shown(n)
            }
        }
        return true
    }

    /** A key press in the window (design §9); true when a shortcut took it. */
    fun onKey(press: KeyPress): Boolean {
        val action = KeyboardShortcuts.action(press, shell.shortcutContext(), services.os) ?: return false
        shell.perform(action, ::pickFiles)
        return true
    }

    /** Ctrl/Cmd+O: the file dialog, then "Send to…" with what was chosen. */
    fun pickFiles() {
        scope.launch {
            val s = stringsState.value
            val chosen = host.chooseFiles(s["chooser.files"], s["chooser.send"])
            shell.onPicked(chosen)
        }
    }

    fun showWindow() {
        windowRequestChannel.trySend(WindowRequest.SHOW)
    }

    fun requestQuit() {
        windowRequestChannel.trySend(WindowRequest.QUIT)
    }

    fun onTrayClick() {
        when (val action = clicks.clicked()) {
            is NotificationAction.OpenFolder -> scope.launch(io) { if (!host.reveal(action.folder)) showWindow() }
            NotificationAction.ShowWindow -> showWindow()
        }
    }

    fun onTrayAction(action: TrayAction) {
        when (action) {
            TrayAction.OPEN -> {
                showWindow()
            }

            TrayAction.RECEIVED_FOLDER -> {
                ports.openReceivedFolder()
            }

            TrayAction.TOGGLE_AUTOSTART -> {
                scope.launch {
                    try {
                        withContext(io) { services.autoStart.setEnabled(!services.autoStart.isEnabled()) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("start at login could not be changed", e)
                    }
                    refreshAutoStart()
                }
            }

            TrayAction.QUIT -> {
                requestQuit()
            }
        }
    }

    fun onTrayVisibility(mode: Visibility) = ports.setVisibility(mode)

    private fun refreshAutoStart() {
        scope.launch {
            autoStartEnabled.value =
                try {
                    withContext(io) { services.autoStart.isAvailable && services.autoStart.isEnabled() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("start at login could not be read", e)
                    false
                }
        }
    }

    /** This computer's static code (F‑B6), again at every beacon epoch since it carries the current beacon ID. */
    private fun staticCodes(): Flow<String?> =
        flow {
            while (true) {
                emit(
                    try {
                        node.staticCode()
                    } catch (e: IllegalStateException) {
                        log("the static code could not be made", e)
                        null
                    },
                )
                delay(EphemeralIds.millisUntilNextEpoch(node.config.wallClock.nowMillis()) + EPOCH_SLACK_MILLIS)
            }
        }

    /** Stops the node (bounded), removes the tray icon and ends the app's coroutines. Call once, on [main]. */
    suspend fun shutdown() {
        tray?.remove()
        tray = null
        withContext(io) { withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { node.stop() } }
        scope.cancel()
    }

    private companion object {
        const val REQUEST_BUFFER = 8
        const val STOP_TIMEOUT_MILLIS = 10_000L

        /** A little after the epoch boundary, so the new beacon ID is the current one. */
        const val EPOCH_SLACK_MILLIS = 1_000L
    }
}

/** The app's log until the ring-buffer log of architecture §14 exists: one line per problem on standard error. */
fun logToStderr(
    message: String,
    error: Throwable?,
) {
    System.err.println("${Instant.now()} drop: $message${error?.let { ": $it" } ?: ""}")
}
