package com.constrivo.drop.ui.desktop

import com.constrivo.drop.platform.desktop.AutoStart
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.platform.desktop.OwnerOnlyFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Strings, single instance, window placement and the launch options (design §9, architecture §10.2). */
class DesktopSupportTest {
    private val root: Path = Files.createTempDirectory("drop-desktop-support")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    // ---- strings (decision 9) ----

    @Test
    fun englishAndHindiDefineTheSameKeysAndPlaceholders() {
        val en = DesktopStrings.of(Locale.ENGLISH)
        val hi = DesktopStrings.of(Locale.forLanguageTag("hi"))
        val placeholders = Regex("\\{(\\d)")
        for (key in en.keys) {
            val hiText = hi[key, 7, "X"]
            assertTrue(hiText != key, "Hindi defines $key")
            val enPattern = raw("strings.properties", key)
            val hiPattern = raw("strings_hi.properties", key)
            assertEquals(
                placeholders.findAll(enPattern).map { it.groupValues[1] }.toSet(),
                placeholders.findAll(hiPattern).map { it.groupValues[1] }.toSet(),
                "placeholders of $key",
            )
        }
        assertEquals(en.keys, rawKeys("strings_hi.properties"), "no Hindi-only keys")
    }

    @Test
    fun patternsFormatCountsAndFallBack() {
        val en = DesktopStrings.of(Locale.ENGLISH)
        assertEquals("1 file · 4 MB", en["sendTo.summary", 1, "4 MB"])
        assertEquals("1,000 files · 4 MB", en["sendTo.summary", 1000, "4 MB"])
        assertEquals("Drop to send to Rohan's Pixel", en["drop.onDevice", "Rohan's Pixel"])
        assertEquals("Only the first 1,000 files are sent.", en["sendTo.truncated", 1000])
        assertEquals("no.such.key", en["no.such.key"], "a missing key shows itself instead of crashing")
        assertEquals("Hindi", DesktopStrings.forLanguage("hi").locale.getDisplayLanguage(Locale.ENGLISH))
        assertEquals(Locale.ENGLISH, DesktopStrings.forLanguage("en").locale)
        assertEquals("1 फ़ाइल · 4 MB", DesktopStrings.forLanguage("hi")["sendTo.summary", 1, "4 MB"])
    }

    private fun raw(
        file: String,
        key: String,
    ): String {
        val props = java.util.Properties()
        DesktopStrings::class.java.getResourceAsStream(file)!!.reader(Charsets.UTF_8).use { props.load(it) }
        return props.getProperty(key) ?: error("$file has no $key")
    }

    private fun rawKeys(file: String): Set<String> {
        val props = java.util.Properties()
        DesktopStrings::class.java.getResourceAsStream(file)!!.reader(Charsets.UTF_8).use { props.load(it) }
        return props.stringPropertyNames()
    }

    // ---- single instance ----

    @Test
    fun aSecondStartActivatesTheFirstAndTheLockIsReleasedOnClose() {
        val lock = root.resolve("data").resolve("instance.lock")
        val activated = CountDownLatch(2)
        val first = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock) { activated.countDown() })
        try {
            val portFile = lock.resolveSibling("instance.lock.port")
            assertTrue(OwnerOnlyFiles.isRestricted(portFile), "the token is the owner's alone")
            assertEquals(first.instance.port, SingleInstance.readPortFile(portFile)?.first)
            assertEquals(SingleInstance.Outcome.Secondary(activated = true), SingleInstance.acquire(lock) {})
            assertEquals(SingleInstance.Outcome.Secondary(activated = true), SingleInstance.acquire(lock) {})
            assertTrue(activated.await(5, TimeUnit.SECONDS), "the first copy was asked to show itself twice")
        } finally {
            first.instance.close()
        }
        val again = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock) {})
        again.instance.close()
    }

    /**
     * A start while the first copy quits (its window gone, its node still stopping) finds no one to activate: it waits
     * for the lock and becomes the app once the first copy has exited, instead of exiting with nothing shown.
     */
    @Test
    fun aStartWhileTheFirstQuitsBecomesTheAppOnceTheFirstHasExited() {
        val lock = root.resolve("instance.lock")
        val first = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock) {})
        first.instance.stopAccepting()
        assertNull(SingleInstance.readPortFile(lock.resolveSibling("instance.lock.port")), "no one answers any more")
        val exiting =
            thread {
                Thread.sleep(700)
                first.instance.close()
            }
        try {
            val second = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock, waitForExitMillis = 10_000) {})
            second.instance.close()
        } finally {
            exiting.join()
        }
    }

    /** A copy that holds the lock and never answers (hung) is given up on after the wait. */
    @Test
    fun aCopyThatHoldsTheLockWithoutAnsweringIsGivenUpOn() {
        val lock = root.resolve("instance.lock")
        val first = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock) {})
        try {
            first.instance.stopAccepting()
            val started = System.nanoTime()
            assertEquals(SingleInstance.Outcome.Secondary(activated = false), SingleInstance.acquire(lock, waitForExitMillis = 300) {})
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "bounded")
        } finally {
            first.instance.close()
        }
    }

    @Test
    fun aWrongTokenDoesNotActivate() {
        val lock = root.resolve("instance.lock")
        val activated = CountDownLatch(1)
        val first = assertIs<SingleInstance.Outcome.Primary>(SingleInstance.acquire(lock) { activated.countDown() })
        try {
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), first.instance.port).use { s ->
                s.getOutputStream().write("activate 00000000000000000000000000000000\n".toByteArray())
            }
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), first.instance.port).use { s ->
                s.getOutputStream().write(ByteArray(4096) { 'a'.code.toByte() })
            }
            assertTrue(!activated.await(300, TimeUnit.MILLISECONDS))
        } finally {
            first.instance.close()
        }
    }

    @Test
    fun malformedPortFilesAreIgnored() {
        val file = root.resolve("p")
        assertNull(SingleInstance.readPortFile(file))
        for (text in listOf(
            "",
            "12",
            "abc 0123456789abcdef0123456789abcdef",
            "70000 0123456789abcdef0123456789abcdef",
            "80 short",
            "80 a b",
        )) {
            Files.writeString(file, text)
            assertNull(SingleInstance.readPortFile(file), text)
        }
        Files.writeString(file, "8080 0123456789abcdef0123456789abcdef\n")
        assertEquals(8080 to "0123456789abcdef0123456789abcdef", SingleInstance.readPortFile(file))
    }

    // ---- window placement (design §9 "remembers position") ----

    @Test
    fun placementIsRestoredWhenStillOnAScreen() {
        val screen = ScreenArea(0, 0, 1920, 1040)
        assertEquals(WindowPlacement.DEFAULT, WindowPlacement.restore(null, listOf(screen)))
        assertEquals(420, WindowPlacement.DEFAULT.width)
        assertEquals(640, WindowPlacement.DEFAULT.height)
        val saved = WindowPlacement(100, 200, 500, 700)
        assertEquals(saved, WindowPlacement.restore(saved, listOf(screen)))
        // Its monitor was unplugged: the size is kept, the OS places it.
        assertEquals(WindowPlacement(null, null, 500, 700), WindowPlacement.restore(WindowPlacement(2500, 200, 500, 700), listOf(screen)))
        // A second screen to the right still has it.
        val two = listOf(screen, ScreenArea(1920, 0, 1280, 1000))
        assertEquals(WindowPlacement(2500, 200, 500, 700), WindowPlacement.restore(WindowPlacement(2500, 200, 500, 700), two))
        // The title bar above the screen is not reachable.
        assertEquals(null, WindowPlacement.restore(WindowPlacement(100, -500, 500, 700), listOf(screen)).x)
        // Too small, or larger than any screen, is brought into range.
        assertEquals(WindowPlacement(10, 10, 360, 480), WindowPlacement.restore(WindowPlacement(10, 10, 100, 100), listOf(screen)))
        assertEquals(WindowPlacement(0, 0, 1920, 1040), WindowPlacement.restore(WindowPlacement(0, 0, 5000, 5000), listOf(screen)))
        // Headless (no screens): the size alone.
        assertEquals(WindowPlacement(null, null, 500, 700), WindowPlacement.restore(saved, emptyList()))
    }

    @Test
    fun placementRoundTripsThroughItsFileAndSurvivesGarbage() {
        val store = WindowPlacementStore(root.resolve("config").resolve("window.properties"))
        assertNull(store.load())
        assertTrue(store.save(WindowPlacement(-40, 25, 420, 640)))
        assertEquals(WindowPlacement(-40, 25, 420, 640), store.load())
        assertTrue(store.save(WindowPlacement(null, null, 500, 600)))
        assertEquals(WindowPlacement(null, null, 500, 600), store.load())
        Files.writeString(root.resolve("config").resolve("window.properties"), "width=abc\nheight=-3\n\\u00zz")
        assertNull(store.load())
    }

    // ---- launch ----

    @Test
    fun theLoginEntryStartsMinimised() {
        assertTrue(LaunchOptions.parse(arrayOf(AutoStart.MINIMIZED_FLAG)).minimized)
        assertTrue(!LaunchOptions.parse(arrayOf("--other", "file.txt")).minimized)
    }

    @Test
    fun theSuggestedNameIsTheComputersName() {
        val hostname = root.resolve("hostname")
        Files.writeString(hostname, "studio.example.org\n")
        assertEquals("studio", DeviceNames.suggested(DesktopOs.LINUX, emptyMap(), hostname))
        assertEquals("ASHA-PC", DeviceNames.suggested(DesktopOs.WINDOWS, mapOf("COMPUTERNAME" to "ASHA-PC"), null))
        assertEquals("box", DeviceNames.suggested(DesktopOs.MAC, mapOf("COMPUTERNAME" to "ignored", "HOSTNAME" to "box.local"), null))
        assertEquals("Computer", DeviceNames.suggested(DesktopOs.LINUX, emptyMap(), root.resolve("missing")))
        assertEquals("Computer", DeviceNames.suggested(DesktopOs.LINUX, mapOf("HOSTNAME" to "localhost"), null))
        assertEquals("a".repeat(DeviceNames.MAX_LENGTH), DeviceNames.suggested(DesktopOs.LINUX, mapOf("HOSTNAME" to "a".repeat(80)), null))
    }
}
