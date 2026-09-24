package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.ThermalLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the machine from sleeping while a transfer runs (architecture §9 "Stay awake and cool"). Per OS in
 * WP10b–d: `SetThreadExecutionState` on Windows, an `IOPMAssertion` on macOS, a logind inhibitor on Linux.
 * [acquire] is called by [DesktopPowerPolicy] only when the first holder arrives, and its [Releasable] when the last
 * one leaves.
 */
fun interface KeepAwake {
    fun acquire(reason: String): Releasable

    companion object {
        /** Does nothing: the machine may sleep (the WP10a default). */
        val NONE: KeepAwake = KeepAwake { Releasable {} }
    }
}

/**
 * [PowerPolicy] for desktops (architecture §9): the thermal level is always [ThermalLevel.NONE] (desktop JVMs have no
 * portable thermal signal, and desktops rarely throttle a transfer), and keep-awake requests are counted so that
 * [keepAwake] reaches the OS hook once however many transfers run. Each [Releasable] releases at most once.
 * Thread-safe.
 */
class DesktopPowerPolicy(
    private val hook: KeepAwake = KeepAwake.NONE,
) : PowerPolicy {
    private val lock = Any()
    private var holders = 0
    private var osHold: Releasable? = null
    private val held = MutableStateFlow(0)

    override val thermal: StateFlow<ThermalLevel> = MutableStateFlow(ThermalLevel.NONE).asStateFlow()

    /** How many keep-awake requests are held now. */
    val holdCount: StateFlow<Int> = held.asStateFlow()

    override fun keepAwake(reason: String): Releasable {
        synchronized(lock) {
            if (holders++ == 0) osHold = runCatching { hook.acquire(reason) }.getOrNull()
            held.value = holders
        }
        val released = AtomicBoolean(false)
        return Releasable {
            if (!released.compareAndSet(false, true)) return@Releasable
            val toRelease =
                synchronized(lock) {
                    holders--
                    held.value = holders
                    if (holders == 0) osHold.also { osHold = null } else null
                }
            toRelease?.let { runCatching { it.release() } }
        }
    }
}

/**
 * Marks a received file as coming from another device (architecture §10.2): the Windows Mark-of-the-Web
 * (`Zone.Identifier` alternate data stream, zone 3) in WP10b and the macOS `com.apple.quarantine` attribute in WP10c,
 * so the OS warns before running it (F‑D5). Called once per published file; a failure is reported by throwing and
 * never undoes the publish.
 */
fun interface DownloadMarker {
    suspend fun mark(
        file: Path,
        senderName: String?,
    )

    companion object {
        /** No marking (Linux has no OS-wide equivalent; the app's own installer warning still applies). */
        val NONE: DownloadMarker = DownloadMarker { _, _ -> }
    }
}

/**
 * Start minimised on login (F‑H5, P1, opt-in). Linux uses an XDG autostart entry (`:platform:desktop-linux`);
 * Windows (the `Run` key) and macOS (a login item) follow in WP10b/c.
 */
interface AutoStart {
    /** Whether this build can register itself (a packaged app with a launcher path). */
    val isAvailable: Boolean

    fun isEnabled(): Boolean

    /** @throws java.io.IOException when the entry cannot be written or removed. */
    fun setEnabled(enabled: Boolean)

    companion object {
        /** Auto-start is not available on this OS yet. */
        val UNAVAILABLE: AutoStart =
            object : AutoStart {
                override val isAvailable: Boolean = false

                override fun isEnabled(): Boolean = false

                override fun setEnabled(enabled: Boolean) = Unit
            }

        /** The command-line flag the autostart entry passes: start with the window hidden in the tray. */
        const val MINIMIZED_FLAG: String = "--minimized"
    }
}

/**
 * What an OS module contributes to the desktop app: the keychain wrap for [FileSecretStorage], the keep-awake hook,
 * the download marker, auto-start, and the Bluetooth radios for [com.constrivo.drop.core.discovery.NearbyDevices]
 * (none in WP10a: the desktop is LAN-only and shows the no-Bluetooth banner, design §9). Each OS module returns its own
 * value (`LinuxPlatform.services()` in `:platform:desktop-linux`); [portable] is the fallback.
 */
interface DesktopPlatformServices {
    val os: DesktopOs
    val secretWrap: SecretWrap
    val keepAwake: KeepAwake
    val downloadMarker: DownloadMarker
    val autoStart: AutoStart

    /** BLE radios; empty when this machine has none the app can drive (then [bluetoothAvailable] is false). */
    val beaconRadios: List<BeaconRadio>

    /** How [beaconRadios] carry the beacon body (S11): service data on Linux, manufacturer data on Windows. */
    val beaconCarrier: BeaconCarrier get() = BeaconCarrier.SERVICE_DATA

    val bluetoothAvailable: Boolean get() = beaconRadios.isNotEmpty()

    companion object {
        /** No keychain, no keep-awake, no marking, no auto-start, no Bluetooth: the LAN path only. */
        fun portable(os: DesktopOs = DesktopOs.current): DesktopPlatformServices =
            object : DesktopPlatformServices {
                override val os: DesktopOs = os
                override val secretWrap: SecretWrap = SecretWrap.NONE
                override val keepAwake: KeepAwake = KeepAwake.NONE
                override val downloadMarker: DownloadMarker = DownloadMarker.NONE
                override val autoStart: AutoStart = AutoStart.UNAVAILABLE
                override val beaconRadios: List<BeaconRadio> = emptyList()
            }
    }
}
