package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.discovery.DevicePlatform

/**
 * The desktop operating system the app runs on (architecture §8, §10.2). It picks the app directories
 * ([AppDirectories]), the platform services ([DesktopPlatformServices]) and the modifier key of the keyboard shortcuts
 * (design §9: Ctrl on Windows and Linux, Cmd on macOS).
 */
enum class DesktopOs {
    LINUX,
    WINDOWS,
    MAC,

    /** Another Unix (BSD, Solaris): treated like Linux for directories, with no OS-specific services. */
    OTHER,
    ;

    /** Whether the primary shortcut modifier is Cmd (macOS) rather than Ctrl. */
    val usesCommandKey: Boolean get() = this == MAC

    companion object {
        /**
         * The OS named by [osName] (the `os.name` system property): "Linux", "Windows 11", "Mac OS X", "FreeBSD", …
         * Unknown or null names are [OTHER].
         */
        fun fromName(osName: String?): DesktopOs {
            val name = osName?.trim()?.lowercase() ?: return OTHER
            return when {
                name.startsWith("windows") -> WINDOWS
                name.startsWith("mac") || name.startsWith("darwin") -> MAC
                name.startsWith("linux") -> LINUX
                else -> OTHER
            }
        }

        /** The OS of this JVM. */
        val current: DesktopOs get() = fromName(System.getProperty("os.name"))
    }
}

/**
 * The radar platform a desktop announces (architecture §5.1 platform code, §5.4 `plat`): a laptop when the machine has
 * a battery, else a desktop. Detection is per OS work (WP10b–d); this is the portable guess from [hasBattery].
 */
fun desktopPlatform(hasBattery: Boolean?): DevicePlatform = if (hasBattery == false) DevicePlatform.DESKTOP else DevicePlatform.LAPTOP
