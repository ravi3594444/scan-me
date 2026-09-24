package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.discovery.DevicePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppDirectoriesTest {
    private val home: Path = Paths.get("/home/asha")

    @Test
    fun `Linux follows XDG with the usual defaults`() {
        val dirs = AppDirectories.forOs(DesktopOs.LINUX, home, emptyMap())
        assertEquals(Paths.get("/home/asha/.config/drop"), dirs.config)
        assertEquals(Paths.get("/home/asha/.local/share/drop"), dirs.data)
        assertEquals(Paths.get("/home/asha/.cache/drop"), dirs.cache)
        assertEquals(Paths.get("/home/asha/Received/Drop"), dirs.received)
        assertEquals(Paths.get("/home/asha/.local/share/drop/drop.db"), dirs.database)
        assertEquals(Paths.get("/home/asha/.local/share/drop/partials"), dirs.partials)
        assertEquals(Paths.get("/home/asha/.local/share/drop/secrets"), dirs.secrets, "device-bound: never under the synced config folder")
    }

    @Test
    fun `Linux honours absolute XDG variables and ignores empty or relative ones`() {
        val env = mapOf("XDG_CONFIG_HOME" to "/cfg", "XDG_DATA_HOME" to "relative/data", "XDG_CACHE_HOME" to "")
        val dirs = AppDirectories.forOs(DesktopOs.LINUX, home, env)
        assertEquals(Paths.get("/cfg/drop"), dirs.config)
        assertEquals(Paths.get("/home/asha/.local/share/drop"), dirs.data)
        assertEquals(Paths.get("/home/asha/.cache/drop"), dirs.cache)
    }

    @Test
    fun `Windows uses APPDATA and LOCALAPPDATA, falling back under the profile`() {
        // Windows paths only parse on Windows; the rule is the same with POSIX-style absolute paths.
        val env = mapOf("APPDATA" to "/profile/AppData/Roaming", "LOCALAPPDATA" to "/profile/AppData/Local")
        val dirs = AppDirectories.forOs(DesktopOs.WINDOWS, Paths.get("/profile"), env)
        assertEquals(Paths.get("/profile/AppData/Roaming/Drop"), dirs.config)
        assertEquals(Paths.get("/profile/AppData/Local/Drop"), dirs.data)
        assertEquals(Paths.get("/profile/AppData/Local/Drop/Cache"), dirs.cache)
        assertEquals(Paths.get("/profile/Received/Drop"), dirs.received)
        // A roaming profile copies %APPDATA% to every PC: the identity, k_adv and the database key stay in %LOCALAPPDATA%.
        assertEquals(Paths.get("/profile/AppData/Local/Drop/secrets"), dirs.secrets)
        assertEquals(Paths.get("/profile/AppData/Local/Drop/instance.lock"), dirs.instanceLock)
        assertTrue(!dirs.secrets.startsWith(dirs.config), "secrets never roam")
        val fallback = AppDirectories.forOs(DesktopOs.WINDOWS, Paths.get("/profile"), emptyMap())
        assertEquals(Paths.get("/profile/AppData/Roaming/Drop"), fallback.config)
        assertEquals(Paths.get("/profile/AppData/Local/Drop"), fallback.data)
    }

    @Test
    fun `macOS keeps everything under Application Support`() {
        val dirs = AppDirectories.forOs(DesktopOs.MAC, Paths.get("/Users/asha"), mapOf("XDG_CONFIG_HOME" to "/ignored"))
        assertEquals(Paths.get("/Users/asha/Library/Application Support/Drop"), dirs.config)
        assertEquals(dirs.config, dirs.data)
        assertEquals(Paths.get("/Users/asha/Library/Caches/com.constrivo.drop"), dirs.cache)
        assertEquals(Paths.get("/Users/asha/Received/Drop"), dirs.received)
    }

    @Test
    fun `the save location setting overrides the Received folder only when it is an absolute path`() {
        val dirs = AppDirectories.forOs(DesktopOs.LINUX, home, emptyMap())
        assertEquals(Paths.get("/media/usb/in"), dirs.receivedFolder("/media/usb/in"))
        assertEquals(dirs.received, dirs.receivedFolder(null))
        assertEquals(dirs.received, dirs.receivedFolder(" "))
        assertEquals(dirs.received, dirs.receivedFolder("relative/folder"))
        assertEquals(dirs.received, dirs.receivedFolder("bad\u0000path"))
    }

    @Test
    fun `a relative home is refused`() {
        assertFailsWith<IllegalArgumentException> { AppDirectories.forOs(DesktopOs.LINUX, Paths.get("home"), emptyMap()) }
    }

    @Test
    fun `ensureCreated makes private directories owner-only and the Received folder normal`() {
        val root = Files.createTempDirectory("drop-dirs-")
        try {
            val dirs = AppDirectories.forOs(DesktopOs.LINUX, root, emptyMap())
            dirs.ensureCreated()
            for (dir in listOf(dirs.config, dirs.data, dirs.cache, dirs.secrets, dirs.partials, dirs.received)) {
                assertTrue(Files.isDirectory(dir), "$dir exists")
            }
            for (dir in listOf(
                dirs.config,
                dirs.data,
                dirs.secrets,
                dirs.partials,
            )) {
                assertTrue(OwnerOnlyFiles.isRestricted(dir), "$dir private")
            }
            dirs.ensureCreated() // idempotent
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `OS names map to the four families`() {
        assertEquals(DesktopOs.LINUX, DesktopOs.fromName("Linux"))
        assertEquals(DesktopOs.WINDOWS, DesktopOs.fromName("Windows 11"))
        assertEquals(DesktopOs.MAC, DesktopOs.fromName("Mac OS X"))
        assertEquals(DesktopOs.MAC, DesktopOs.fromName("Darwin"))
        assertEquals(DesktopOs.OTHER, DesktopOs.fromName("FreeBSD"))
        assertEquals(DesktopOs.OTHER, DesktopOs.fromName(null))
        assertTrue(DesktopOs.MAC.usesCommandKey)
        assertEquals(false, DesktopOs.LINUX.usesCommandKey)
        assertEquals(DevicePlatform.DESKTOP, desktopPlatform(hasBattery = false))
        assertEquals(DevicePlatform.LAPTOP, desktopPlatform(hasBattery = true))
        assertEquals(DevicePlatform.LAPTOP, desktopPlatform(hasBattery = null))
    }
}
