package com.constrivo.drop.ui.desktop

import androidx.compose.ui.window.application
import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import com.constrivo.drop.platform.desktop.FileSecretStorage
import com.constrivo.drop.platform.desktop.lan.JmdnsLanDiscovery
import com.constrivo.drop.platform.desktop.lan.LanInterfaces
import com.constrivo.drop.platform.desktop.node.DesktopNode
import com.constrivo.drop.platform.desktop.node.DesktopNodeConfig
import com.constrivo.drop.platform.linux.LinuxPlatform
import kotlinx.coroutines.Dispatchers
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
 * interface, the node, then the window and the tray. `--minimized` (the login entry, F‑H5) starts in the tray.
 */
fun main(args: Array<String>) {
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
    val lan = LanInterfaces.selectCurrent()
    // Without a network the node still runs on loopback, so History, Settings and the code work; the banner says why
    // nothing appears.
    val address = lan?.address ?: InetAddress.getLoopbackAddress()
    val discovery = JmdnsLanDiscovery(address)
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
    val desktop = DesktopApp(node, services, AwtDesktopHost(os), hasLan = lan != null, appVersion = version, main = Dispatchers.Swing)
    val trayInstalled =
        runBlocking(Dispatchers.Swing) {
            desktop.start()
            desktop.installTray()
        }
    app.set(desktop)
    if (pendingActivation.get()) desktop.showWindow()

    val placements = WindowPlacementStore(directories.config.resolve(WINDOW_FILE))
    application(exitProcessOnExit = false) {
        DesktopWindow(desktop, placements, trayInstalled, startHidden = options.minimized)
    }

    runBlocking(Dispatchers.Swing) { desktop.shutdown() }
    discovery.close()
    instance.close()
    exitProcess(0)
}

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
