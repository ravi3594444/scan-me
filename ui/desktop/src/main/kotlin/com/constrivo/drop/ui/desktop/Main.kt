package com.constrivo.drop.ui.desktop

import androidx.compose.ui.window.application
import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import com.constrivo.drop.platform.desktop.FileSecretStorage
import com.constrivo.drop.platform.desktop.lan.JmdnsLanDiscovery
import com.constrivo.drop.platform.desktop.lan.LanInterfaces
import com.constrivo.drop.platform.desktop.lan.LanWatcher
import com.constrivo.drop.platform.desktop.node.DesktopNode
import com.constrivo.drop.platform.desktop.node.DesktopNodeConfig
import com.constrivo.drop.platform.linux.LinuxPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.awt.GraphicsEnvironment
import java.net.InetAddress
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * The desktop app's entry point (architecture §10.2): one instance per user, the directories of the OS, the LAN
 * interface (followed while the app runs: Wi‑Fi joined after login, another network), the node, then the window and
 * the tray. `--minimized` (the login entry, F‑H5) starts in the tray.
 */
fun main(args: Array<String>) {
    // Before any JmDNS class loads: records live RFC 6762's 120 s, not JmDNS's hour.
    JmdnsLanDiscovery.useShortTtl()
    val options = LaunchOptions.parse(args)
    val os = DesktopOs.current
    val directories =
        fatalOnError("the app folders could not be created") {
            AppDirectories.forOs(os, Paths.get(System.getProperty("user.home")).toAbsolutePath(), System.getenv()).also {
                it.ensureCreated()
            }
        }
    // A second start before the window exists is remembered and shown once it does.
    val app = AtomicReference<DesktopApp?>(null)
    val pendingActivation = AtomicBoolean(false)
    val instance =
        when (
            val outcome =
                fatalOnError("the app could not check for another copy of itself") {
                    SingleInstance.acquire(directories.instanceLock) {
                        app.get()?.showWindow() ?: pendingActivation.set(true)
                    }
                }
        ) {
            is SingleInstance.Outcome.Primary -> {
                outcome.instance
            }

            is SingleInstance.Outcome.Secondary -> {
                // The running copy shows its window; this one has nothing to do.
                exitProcess(if (outcome.activated) 0 else 1)
            }
        }

    val services = if (os == DesktopOs.LINUX) LinuxPlatform.services() else DesktopPlatformServices.portable(os)
    val lan = LanWatcher({ LanInterfaces.selectCurrent() }, onError = ::logToStderr)
    // Without a network the node runs on loopback, so History, Settings and the code work; the banner says why nothing
    // appears, and the watcher moves the node once the computer joins one.
    val address = lan.selection.value?.address ?: InetAddress.getLoopbackAddress()
    val discovery = JmdnsLanDiscovery(address, onError = ::logToStderr)
    val node =
        DesktopNode(
            DesktopNodeConfig(
                directories = directories,
                secrets = FileSecretStorage(directories.secrets, services.secretWrap),
                lan = discovery,
                lanAddress = address,
                defaultNickname = DeviceNames.suggested(os),
                services = services,
            ),
        )
    fatalOnError("the app could not start") { runBlocking { node.start() } }

    val version = DesktopApp::class.java.`package`?.implementationVersion ?: DEVELOPMENT_VERSION
    val desktop =
        DesktopApp(node, services, AwtDesktopHost(os), lanAvailable = lan.available, appVersion = version, main = Dispatchers.Swing)
    val trayInstalled =
        runBlocking(Dispatchers.Swing) {
            desktop.start()
            desktop.installTray()
        }
    app.set(desktop)
    if (pendingActivation.get()) desktop.showWindow()
    followLan(lan, node, desktop.scope)

    val placements = WindowPlacementStore(directories.config.resolve(WINDOW_FILE))
    application(exitProcessOnExit = false) {
        DesktopWindow(desktop, placements, trayInstalled, startHidden = options.minimized)
    }

    // The window is gone: a start from now on waits for this copy to exit and becomes the app (not a silent exit).
    instance.stopAccepting()
    runBlocking(Dispatchers.Swing) { desktop.shutdown() }
    discovery.close()
    instance.close()
    exitProcess(0)
}

/**
 * Keeps the node on the machine's LAN interface (architecture §10.2 note, F‑H5): the watcher polls it, and each change
 * moves the node there (loopback while there is no network), trying again with back-off while the address cannot be
 * bound yet; the banner follows `lan.available` by itself.
 */
private fun followLan(
    lan: LanWatcher,
    node: DesktopNode,
    scope: CoroutineScope,
) {
    lan.launchIn(scope)
    scope.launch(Dispatchers.IO) {
        // The node ignores its current address, so the first value (the one it started on) changes nothing.
        lan.selection.collectLatest { selection ->
            val address = selection?.address ?: InetAddress.getLoopbackAddress()
            var wait = LAN_RETRY_MILLIS
            while (true) {
                try {
                    node.setLanAddress(address)
                    return@collectLatest
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    return@collectLatest // the node is stopping
                } catch (e: Exception) {
                    logToStderr("the app could not move to ${address.hostAddress}; trying again", e)
                }
                delay(wait)
                wait = minOf(wait * 2, LAN_RETRY_MAX_MILLIS)
            }
        }
    }
}

private const val LAN_RETRY_MILLIS = 1_000L
private const val LAN_RETRY_MAX_MILLIS = 30_000L

private const val WINDOW_FILE = "window.properties"
private const val DEVELOPMENT_VERSION = "dev"

/** Runs [block]; on failure tells the user (a dialog where there is a display) and exits. */
private inline fun <T> fatalOnError(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: Exception) {
        logToStderr(what, e)
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(
                null,
                "${AppIdentity.DISPLAY_NAME}: $what.\n${e.message ?: e}",
                AppIdentity.DISPLAY_NAME,
                JOptionPane.ERROR_MESSAGE,
            )
        }
        exitProcess(1)
    }
