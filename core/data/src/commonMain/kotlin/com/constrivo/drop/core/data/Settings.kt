package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.Visibility

/**
 * A typed setting (F-G5, design §6) stored as text in the `settings` table under [name].
 *
 * A key that was never set reads as [defaultValue]. Setting a nullable key to null removes the row, so it reads as
 * its default (null) again. Values pass [prepare] before they are stored, which normalises them or throws
 * `IllegalArgumentException`.
 */
sealed class SettingKey<T>(
    val name: String,
    val defaultValue: T,
) {
    /** Validates and normalises [value] for storage. */
    internal abstract fun prepare(value: T): T

    /** The stored text of an already prepared value; null removes the row. */
    internal abstract fun encode(value: T): String?

    /** @throws DataCorruptionException if [text] is not something [encode] writes. */
    internal abstract fun decode(text: String): T

    override fun toString(): String = "SettingKey($name)"
}

/** An on/off setting, stored as `true` / `false`. */
class BooleanSetting internal constructor(
    name: String,
    defaultValue: Boolean,
) : SettingKey<Boolean>(name, defaultValue) {
    override fun prepare(value: Boolean): Boolean = value

    override fun encode(value: Boolean): String = value.toString()

    override fun decode(text: String): Boolean =
        when (text) {
            "true" -> true
            "false" -> false
            else -> throw DataCorruptionException("setting $name holds '$text', not a boolean")
        }
}

/** An optional text setting (default null) with a validating normaliser. */
class TextSetting internal constructor(
    name: String,
    private val normalizer: (String) -> String,
) : SettingKey<String?>(name, null) {
    override fun prepare(value: String?): String? = value?.let(normalizer)

    override fun encode(value: String?): String? = value

    override fun decode(text: String): String =
        try {
            normalizer(text)
        } catch (e: IllegalArgumentException) {
            throw DataCorruptionException("setting $name holds an invalid value", e)
        }
}

/** A visibility mode, stored with the design's copy-key names (design §8.3). */
internal class VisibilitySetting(
    name: String,
    defaultValue: Visibility,
) : SettingKey<Visibility>(name, defaultValue) {
    override fun prepare(value: Visibility): Visibility = value

    override fun encode(value: Visibility): String = NAMES.getValue(value)

    override fun decode(text: String): Visibility =
        NAMES.entries.firstOrNull { it.value == text }?.key
            ?: throw DataCorruptionException("setting $name holds '$text', not a visibility")

    private companion object {
        val NAMES =
            mapOf(
                Visibility.EVERYONE to "everyone",
                Visibility.EVERYONE_TEN_MINUTES to "ten_min",
                Visibility.TRUSTED_ONLY to "trusted",
                Visibility.HIDDEN to "hidden",
            )
    }
}

/** An optional instant in unix milliseconds, stored in decimal. */
internal class InstantSetting(
    name: String,
) : SettingKey<Long?>(name, null) {
    override fun prepare(value: Long?): Long? = value

    override fun encode(value: Long?): String? = value?.toString()

    override fun decode(text: String): Long =
        text.toLongOrNull()?.takeIf { text.all { it in '0'..'9' || it == '-' } }
            ?: throw DataCorruptionException("setting $name holds '$text', not an instant")
}

/**
 * Every user setting of design §6 and F-G5, with its default. Visibility is read and written through
 * [SettingsRepository.visibility] and [SettingsRepository.setVisibility], which keep its three stored parts
 * consistent. "Clear partial files" is an action, not a setting: [ResumeDataCleaner.clearPartials], which never
 * touches a transfer an engine may still be writing (a short retention on [ResumeDataCleaner] would cancel live ones).
 */
object SettingKeys {
    /** Longest save location or avatar URI accepted. */
    const val MAX_LOCATION_LENGTH: Int = 4096

    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

    /** Speed: ask for a 5 GHz Wi-Fi Direct group (F-E2). */
    val PREFER_5_GHZ: BooleanSetting = BooleanSetting("speed.prefer_5ghz", true)

    /** Speed: keep the screen on while a transfer runs. Off by default to save battery (§15). */
    val KEEP_SCREEN_AWAKE: BooleanSetting = BooleanSetting("speed.keep_screen_awake", false)

    /** Speed: bundle files under 1 MiB (F-E7, `Offer.bundle_small`). */
    val BUNDLE_SMALL_FILES: BooleanSetting = BooleanSetting("speed.bundle_small_files", true)

    /**
     * Storage: the folder received files go to (a SAF tree URI on Android, a path on desktop); null means the platform
     * default (gallery for media and Downloads for documents on Android, F-D4; `~/Received/Drop/` on desktop).
     */
    val SAVE_LOCATION: TextSetting = TextSetting("storage.save_location", ::requireLocation)

    /**
     * Profile: this device's nickname (F-I3), sanitised and cut to 64 UTF-8 bytes like every nickname; null until
     * onboarding sets it (onboarding prefills the device name).
     */
    val NICKNAME: TextSetting = TextSetting("profile.nickname", ::requireNickname)

    /** Profile: the avatar picture's URI; null shows initials (F-I3). */
    val AVATAR: TextSetting = TextSetting("profile.avatar", ::requireLocation)

    /** Profile: a BCP 47 language tag such as `hi` or `en-IN`; null follows the system language (F-I4). */
    val LANGUAGE: TextSetting = TextSetting("profile.language", ::requireLanguageTag)

    /** Privacy: opt-in crash reports (F-J3); off by default. */
    val CRASH_REPORTS: BooleanSetting = BooleanSetting("privacy.crash_reports", false)

    /** Accessibility: haptic feedback (design §11: haptics can be disabled in Settings). */
    val HAPTICS: BooleanSetting = BooleanSetting("accessibility.haptics", true)

    /** The typed keys above (not the internal visibility parts). */
    val ALL: List<SettingKey<*>> =
        listOf(PREFER_5_GHZ, KEEP_SCREEN_AWAKE, BUNDLE_SMALL_FILES, SAVE_LOCATION, NICKNAME, AVATAR, LANGUAGE, CRASH_REPORTS, HAPTICS)

    internal val VISIBILITY_MODE = VisibilitySetting("visibility.mode", VisibilityPreference.DEFAULT.mode)
    internal val VISIBILITY_EXPIRES_AT = InstantSetting("visibility.expires_at")
    internal val VISIBILITY_REVERT_TO = VisibilitySetting("visibility.revert_to", VisibilityPreference.DEFAULT.revertTo)

    private fun requireLocation(value: String): String {
        require(value.isNotBlank()) { "a location must not be blank" }
        require(value.length <= MAX_LOCATION_LENGTH) { "a location is at most $MAX_LOCATION_LENGTH characters" }
        require(value.none { it.code < 0x20 || it.code == 0x7F }) { "a location must not contain control characters" }
        return value
    }

    private fun requireNickname(value: String): String =
        requireNotNull(Nicknames.normalize(value)) { "a nickname needs a visible character" }.text

    private fun requireLanguageTag(value: String): String {
        require(value.length <= 35 && LANGUAGE_TAG.matches(value)) { "'$value' is not a BCP 47 language tag" }
        return value
    }
}

/**
 * The visibility setting (F-A5, design §2 chip): the chosen [mode] and, for "Everyone for 10 min", when the window
 * ends and which mode follows it.
 *
 * @property expiresAtMillis end of the 10-minute window (unix ms); set only with [Visibility.EVERYONE_TEN_MINUTES].
 * @property revertTo the mode in force before the window opened, which applies again when it ends; never
 *   [Visibility.EVERYONE_TEN_MINUTES].
 */
data class VisibilityPreference(
    val mode: Visibility,
    val expiresAtMillis: Long?,
    val revertTo: Visibility,
) {
    init {
        require(revertTo != Visibility.EVERYONE_TEN_MINUTES) { "the window cannot revert to another window" }
        require(expiresAtMillis == null || mode == Visibility.EVERYONE_TEN_MINUTES) { "only the 10-minute mode expires" }
    }

    /**
     * The visibility in force at [nowMillis]: [mode], except that a 10-minute window applies only from its start
     * (`expiresAtMillis − 10 min`) until its end, and [revertTo] otherwise. A wall clock set back before the window
     * therefore ends it instead of stretching it.
     */
    fun effectiveAt(nowMillis: Long): Visibility {
        val windowClosed = mode == Visibility.EVERYONE_TEN_MINUTES && !windowOpenAt(nowMillis)
        return if (windowClosed) revertTo else mode
    }

    /** Time left in the 10-minute window at [nowMillis] (for the chip's minutes), or null when no window is open. */
    fun remainingMillis(nowMillis: Long): Long? {
        val end = expiresAtMillis ?: return null
        return if (windowOpenAt(nowMillis)) end - nowMillis else null
    }

    private fun windowOpenAt(nowMillis: Long): Boolean {
        val end = expiresAtMillis ?: return false
        return nowMillis >= end - EVERYONE_WINDOW_MILLIS && nowMillis < end
    }

    companion object {
        /** "Everyone for 10 min" (F-A5). */
        const val EVERYONE_WINDOW_MILLIS: Long = 10L * 60 * 1000

        /** Trusted only, the assumed default (PRD §10 open question 2, plan decision 2). */
        val DEFAULT: VisibilityPreference = VisibilityPreference(Visibility.TRUSTED_ONLY, null, Visibility.TRUSTED_ONLY)
    }
}

/** Every setting at one moment, for the Settings tab (design §6). */
data class SettingsSnapshot(
    val visibility: VisibilityPreference,
    val prefer5Ghz: Boolean,
    val keepScreenAwake: Boolean,
    val bundleSmallFiles: Boolean,
    val saveLocation: String?,
    val nickname: String?,
    val avatar: String?,
    val language: String?,
    val crashReports: Boolean,
    val haptics: Boolean,
)
