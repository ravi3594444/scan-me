package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** SettingsRepository (F-G5, design §6; F-A5 visibility). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private val minute = 60_000L

    @Test
    fun everySettingHasItsDocumentedDefault() =
        runTest {
            val data = openTestData()
            val expected =
                SettingsSnapshot(
                    visibility = VisibilityPreference(Visibility.TRUSTED_ONLY, null, Visibility.TRUSTED_ONLY),
                    prefer5Ghz = true,
                    keepScreenAwake = false,
                    bundleSmallFiles = true,
                    saveLocation = null,
                    nickname = null,
                    avatar = null,
                    language = null,
                    crashReports = false,
                    haptics = true,
                )
            assertEquals(expected, data.settings.snapshot())
            assertEquals(true, data.settings.get(SettingKeys.PREFER_5_GHZ))
            assertEquals(false, data.settings.get(SettingKeys.KEEP_SCREEN_AWAKE))
            assertEquals(true, data.settings.get(SettingKeys.BUNDLE_SMALL_FILES))
            assertEquals(false, data.settings.get(SettingKeys.CRASH_REPORTS))
            assertEquals(true, data.settings.get(SettingKeys.HAPTICS))
            for (key in listOf(SettingKeys.SAVE_LOCATION, SettingKeys.NICKNAME, SettingKeys.AVATAR, SettingKeys.LANGUAGE)) {
                assertNull(data.settings.get(key), key.name)
            }
            assertEquals(Visibility.TRUSTED_ONLY, data.settings.visibility().effectiveAt(T0))
            assertEquals(9, SettingKeys.ALL.size)
            assertEquals(SettingKeys.ALL.size, SettingKeys.ALL.map { it.name }.toSet().size, "names are unique")
        }

    @Test
    fun valuesRoundTripAndResetToTheDefault() =
        runTest {
            val data = openTestData()
            data.settings.set(SettingKeys.PREFER_5_GHZ, false)
            data.settings.set(SettingKeys.KEEP_SCREEN_AWAKE, true)
            data.settings.set(SettingKeys.BUNDLE_SMALL_FILES, false)
            data.settings.set(SettingKeys.SAVE_LOCATION, "content://com.android.externalstorage.documents/tree/primary%3ADrop")
            data.settings.set(SettingKeys.NICKNAME, "  Asha​ ")
            data.settings.set(SettingKeys.AVATAR, "file:///data/avatar.jpg")
            data.settings.set(SettingKeys.LANGUAGE, "hi-IN")
            data.settings.set(SettingKeys.CRASH_REPORTS, true)
            data.settings.set(SettingKeys.HAPTICS, false)
            val snapshot = data.settings.snapshot()
            assertEquals(false, snapshot.prefer5Ghz)
            assertEquals(true, snapshot.keepScreenAwake)
            assertEquals(false, snapshot.bundleSmallFiles)
            assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADrop", snapshot.saveLocation)
            assertEquals("Asha", snapshot.nickname, "nicknames are sanitised like every nickname")
            assertEquals("file:///data/avatar.jpg", snapshot.avatar)
            assertEquals("hi-IN", snapshot.language)
            assertEquals(true, snapshot.crashReports)
            assertEquals(false, snapshot.haptics)
            assertEquals("Asha", data.settings.get(SettingKeys.NICKNAME))

            data.settings.set(SettingKeys.NICKNAME, null)
            assertNull(data.settings.get(SettingKeys.NICKNAME))
            data.settings.reset(SettingKeys.PREFER_5_GHZ)
            assertEquals(true, data.settings.get(SettingKeys.PREFER_5_GHZ))
        }

    @Test
    fun invalidValuesAreRejected() =
        runTest {
            val data = openTestData()
            assertFailsWith<IllegalArgumentException> { data.settings.set(SettingKeys.NICKNAME, " ​ ") }
            assertFailsWith<IllegalArgumentException> { data.settings.set(SettingKeys.LANGUAGE, "english please") }
            assertFailsWith<IllegalArgumentException> { data.settings.set(SettingKeys.LANGUAGE, "e") }
            assertFailsWith<IllegalArgumentException> { data.settings.set(SettingKeys.SAVE_LOCATION, "  ") }
            assertFailsWith<IllegalArgumentException> { data.settings.set(SettingKeys.SAVE_LOCATION, "a\nb") }
            assertFailsWith<IllegalArgumentException> {
                data.settings.set(SettingKeys.AVATAR, "x".repeat(SettingKeys.MAX_LOCATION_LENGTH + 1))
            }
            assertEquals(SettingsSnapshot::class, data.settings.snapshot()::class)
            assertNull(data.settings.get(SettingKeys.NICKNAME))
            val long = "é".repeat(40)
            data.settings.set(SettingKeys.NICKNAME, long)
            assertEquals(32, data.settings.get(SettingKeys.NICKNAME)!!.length, "cut to 64 UTF-8 bytes at a code point boundary")
        }

    @Test
    fun anUnreadableStoredValueReadsAsTheDefault() =
        runTest {
            val data = openTestData()
            data.driver.execute(null, "INSERT INTO settings (key, value) VALUES ('speed.prefer_5ghz', 'maybe')", 0)
            data.driver.execute(null, "INSERT INTO settings (key, value) VALUES ('profile.language', 'not a tag')", 0)
            data.driver.execute(null, "INSERT INTO settings (key, value) VALUES ('visibility.mode', 'sideways')", 0)
            assertEquals(true, data.settings.get(SettingKeys.PREFER_5_GHZ))
            assertNull(data.settings.get(SettingKeys.LANGUAGE))
            assertEquals(VisibilityPreference.DEFAULT, data.settings.visibility())
            data.settings.set(SettingKeys.PREFER_5_GHZ, false)
            assertEquals(false, data.settings.get(SettingKeys.PREFER_5_GHZ), "the next set replaces it")
        }

    @Test
    fun eachKeyHasAFlowThatFollowsChangesWithoutARestart() =
        runTest {
            val data = openTestData()
            val prefer = collectInto(data.settings.observe(SettingKeys.PREFER_5_GHZ))
            val nickname = collectInto(data.settings.observe(SettingKeys.NICKNAME))
            val all = collectInto(data.settings.observeAll())
            runCurrent()
            data.settings.set(SettingKeys.PREFER_5_GHZ, false)
            runCurrent()
            data.settings.set(SettingKeys.NICKNAME, "Asha")
            runCurrent()
            data.settings.set(SettingKeys.PREFER_5_GHZ, false) // unchanged: no emission
            runCurrent()
            data.settings.reset(SettingKeys.PREFER_5_GHZ)
            runCurrent()
            assertEquals(listOf(true, false, true), prefer)
            assertEquals(listOf(null, "Asha"), nickname)
            assertEquals(listOf(true, false, false, true), all.map { it.prefer5Ghz })
            assertEquals(listOf(null, null, "Asha", "Asha"), all.map { it.nickname })
        }

    @Test
    fun choosingAModeClearsAnyWindow() =
        runTest {
            val data = openTestData()
            for (mode in listOf(Visibility.EVERYONE, Visibility.HIDDEN, Visibility.TRUSTED_ONLY)) {
                val preference = data.settings.setVisibility(mode, T0)
                assertEquals(VisibilityPreference(mode, null, mode), preference)
                assertEquals(preference, data.settings.visibility())
                assertEquals(mode, preference.effectiveAt(T0 + 365 * DAY))
                assertNull(preference.remainingMillis(T0))
            }
        }

    @Test
    fun everyoneForTenMinutesRevertsToThePreviousMode() =
        runTest {
            val data = openTestData()
            data.settings.setVisibility(Visibility.HIDDEN, T0)
            val window = data.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES, T0 + minute)
            assertEquals(VisibilityPreference(Visibility.EVERYONE_TEN_MINUTES, T0 + 11 * minute, Visibility.HIDDEN), window)
            assertEquals(window, data.settings.visibility())
            assertEquals(Visibility.EVERYONE_TEN_MINUTES, window.effectiveAt(T0 + minute))
            assertEquals(10 * minute, window.remainingMillis(T0 + minute))
            assertEquals(1, window.remainingMillis(T0 + 11 * minute - 1))
            assertEquals(Visibility.HIDDEN, window.effectiveAt(T0 + 11 * minute))
            assertNull(window.remainingMillis(T0 + 11 * minute))

            // Choosing it again restarts the window and still falls back to Hidden, not to itself.
            val again = data.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES, T0 + 5 * minute)
            assertEquals(VisibilityPreference(Visibility.EVERYONE_TEN_MINUTES, T0 + 15 * minute, Visibility.HIDDEN), again)
            // After it expired, a new window falls back to what applied then.
            val later = data.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES, T0 + HOUR)
            assertEquals(Visibility.HIDDEN, later.revertTo)
        }

    @Test
    fun aClockSetBackEndsTheWindowInsteadOfStretchingIt() {
        val window = VisibilityPreference(Visibility.EVERYONE_TEN_MINUTES, T0 + 10 * minute, Visibility.TRUSTED_ONLY)
        assertEquals(Visibility.EVERYONE_TEN_MINUTES, window.effectiveAt(T0))
        assertEquals(Visibility.TRUSTED_ONLY, window.effectiveAt(T0 - 1))
        assertNull(window.remainingMillis(T0 - HOUR))
        val broken = VisibilityPreference(Visibility.EVERYONE_TEN_MINUTES, null, Visibility.EVERYONE)
        assertEquals(Visibility.EVERYONE, broken.effectiveAt(T0), "a window without an end is closed")
        assertFailsWith<IllegalArgumentException> { VisibilityPreference(Visibility.EVERYONE, T0, Visibility.EVERYONE) }
        assertFailsWith<IllegalArgumentException> {
            VisibilityPreference(Visibility.EVERYONE_TEN_MINUTES, T0, Visibility.EVERYONE_TEN_MINUTES)
        }
    }

    /** A wall clock that follows the test scheduler's virtual time, starting at [T0]. */
    private fun TestScope.virtualClock(): WallClock = WallClock { T0 + testScheduler.currentTime }

    @Test
    fun effectiveVisibilitySwitchesBackWhenTheWindowEnds() =
        runTest {
            val clock = virtualClock()
            val data = openTestData(clock)
            val effective = collectInto(data.settings.observeEffectiveVisibility())
            runCurrent()
            data.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES)
            runCurrent()
            advanceTimeBy(10 * minute - 1)
            runCurrent()
            assertEquals(listOf(Visibility.TRUSTED_ONLY, Visibility.EVERYONE_TEN_MINUTES), effective)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(Visibility.TRUSTED_ONLY, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY), effective)
            data.settings.setVisibility(Visibility.EVERYONE)
            runCurrent()
            advanceTimeBy(DAY)
            runCurrent()
            assertEquals(Visibility.EVERYONE, effective.last())
            assertEquals(4, effective.size)
        }

    @Test
    fun observeVisibilityEmitsStoredChanges() =
        runTest {
            val data = openTestData()
            val seen = collectInto(data.settings.observeVisibility())
            runCurrent()
            data.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES, T0)
            runCurrent()
            data.settings.set(SettingKeys.HAPTICS, false) // another key: no visibility emission
            runCurrent()
            assertEquals(listOf(VisibilityPreference.DEFAULT.mode, Visibility.EVERYONE_TEN_MINUTES), seen.map { it.mode })
        }
}
