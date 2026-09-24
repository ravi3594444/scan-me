package com.constrivo.drop.platform.linux

import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.platform.desktop.AutoStart
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import com.constrivo.drop.platform.desktop.DownloadMarker
import com.constrivo.drop.platform.desktop.KeepAwake
import com.constrivo.drop.platform.desktop.SecretWrap
import java.nio.file.Path
import java.nio.file.Paths

/** Platform facts the engine needs before the radios are wired up (implementation plan, spec change S11). */
object LinuxPlatform {
    /** BlueZ LEAdvertisement1 supports service data. */
    val beaconCarrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA

    const val CAN_ADVERTISE: Boolean = true

    /**
     * What Linux adds to the shared desktop layer (WP10a): the XDG autostart entry (F‑H5). The LAN path, directories,
     * secrets and database come from `:platform:desktop-common`; BlueZ radios, a libsecret [SecretWrap] and a logind
     * keep-awake inhibitor follow in WP10d, until then the portable defaults apply.
     *
     * @param launcher the command that starts the packaged app ([XdgAutoStart.packagedLauncher]); null when this run
     *   is not packaged (`./gradlew run`), in which case auto-start is not offered.
     */
    fun services(
        env: Map<String, String> = System.getenv(),
        home: Path = Paths.get(System.getProperty("user.home")),
        launcher: List<String>? = XdgAutoStart.packagedLauncher(),
    ): DesktopPlatformServices {
        val autoStart = XdgAutoStart.forUser(env, home, launcher)
        return object : DesktopPlatformServices {
            override val os: DesktopOs = DesktopOs.LINUX
            override val secretWrap: SecretWrap = SecretWrap.NONE
            override val keepAwake: KeepAwake = KeepAwake.NONE
            override val downloadMarker: DownloadMarker = DownloadMarker.NONE
            override val autoStart: AutoStart = autoStart
            override val beaconRadios: List<BeaconRadio> = emptyList()
            override val beaconCarrier: BeaconCarrier = LinuxPlatform.beaconCarrier
        }
    }
}
