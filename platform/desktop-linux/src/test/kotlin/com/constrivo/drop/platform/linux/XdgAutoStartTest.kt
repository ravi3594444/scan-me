package com.constrivo.drop.platform.linux

import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.platform.desktop.AutoStart
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.SecretWrap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XdgAutoStartTest {
    private val home: Path = Files.createTempDirectory("drop-home-")

    @AfterTest
    fun cleanUp() {
        Files.walk(home).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `F-H5 turning auto-start on writes an XDG autostart entry that starts minimised, off removes it`() {
        val autoStart = XdgAutoStart.forUser(emptyMap(), home, listOf("/opt/drop/bin/Drop"))
        assertEquals(home.resolve(".config/autostart/com.constrivo.drop.desktop"), autoStart.entry)
        assertTrue(autoStart.isAvailable)
        assertFalse(autoStart.isEnabled())
        autoStart.setEnabled(true)
        assertTrue(autoStart.isEnabled())
        val text = String(Files.readAllBytes(autoStart.entry))
        assertTrue(text.startsWith("[Desktop Entry]\n"))
        assertTrue("Type=Application\n" in text)
        assertTrue("Exec=/opt/drop/bin/Drop ${AutoStart.MINIMIZED_FLAG}\n" in text, text)
        autoStart.setEnabled(true) // idempotent
        autoStart.setEnabled(false)
        autoStart.setEnabled(false)
        assertFalse(autoStart.isEnabled())
        assertFalse(Files.exists(autoStart.entry))
    }

    @Test
    fun `an entry hidden by the session counts as off, and XDG_CONFIG_HOME moves it`() {
        val config = home.resolve("cfg")
        val autoStart = XdgAutoStart.forUser(mapOf("XDG_CONFIG_HOME" to config.toString()), home, listOf("/opt/drop/bin/Drop"))
        assertEquals(config.resolve("autostart"), autoStart.autostartDirectory)
        autoStart.setEnabled(true)
        Files.write(autoStart.entry, (String(Files.readAllBytes(autoStart.entry)) + "Hidden=true\n").toByteArray())
        assertFalse(autoStart.isEnabled())
        val relative = XdgAutoStart.forUser(mapOf("XDG_CONFIG_HOME" to "relative"), home, null)
        assertEquals(home.resolve(".config/autostart"), relative.autostartDirectory)
    }

    @Test
    fun `without a packaged launcher nothing is offered or written`() {
        val autoStart = XdgAutoStart.forUser(emptyMap(), home, null)
        assertFalse(autoStart.isAvailable)
        autoStart.setEnabled(true)
        assertFalse(Files.exists(autoStart.entry))
    }

    @Test
    fun `Exec arguments are quoted and escaped as the Desktop Entry Specification requires`() {
        assertEquals("/usr/bin/drop", XdgAutoStart.execArgument("/usr/bin/drop"))
        assertEquals("\"/home/a b/Drop\"", XdgAutoStart.execArgument("/home/a b/Drop"))
        assertEquals("\"a\\\\\$b\"", XdgAutoStart.execArgument("a\$b"))
        assertEquals("100%%", XdgAutoStart.execArgument("100%"))
        assertEquals("\"\"", XdgAutoStart.execArgument(""))
        assertEquals("\"it's\"", XdgAutoStart.execArgument("it's"))
        assertFailsWith<IllegalArgumentException> { XdgAutoStart.execArgument("a\nb") }
        assertFailsWith<IllegalArgumentException> { XdgAutoStart.desktopEntry(emptyList()) }
        val entry = XdgAutoStart.desktopEntry(listOf("/opt/My Apps/Drop"))
        assertTrue("Exec=\"/opt/My Apps/Drop\" --minimized\n" in entry, entry)
    }

    @Test
    fun `Linux services use the shared desktop layer with the XDG auto-start`() {
        val services = LinuxPlatform.services(emptyMap(), home, listOf("/opt/drop/bin/Drop"))
        assertEquals(DesktopOs.LINUX, services.os)
        assertEquals(SecretWrap.NONE, services.secretWrap)
        assertTrue(services.autoStart.isAvailable)
        assertFalse(services.bluetoothAvailable)
        assertEquals(BeaconCarrier.SERVICE_DATA, services.beaconCarrier)
        assertEquals(false, LinuxPlatform.services(emptyMap(), Paths.get("/tmp"), null).autoStart.isAvailable)
    }
}
