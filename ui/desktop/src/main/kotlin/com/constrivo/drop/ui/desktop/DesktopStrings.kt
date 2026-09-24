package com.constrivo.drop.ui.desktop

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * The desktop shell's own copy (design §9: the banner, "Send to…", the tray menu, notifications, file dialogs) in
 * English and Hindi (decision 9), from `strings.properties` / `strings_hi.properties` next to this class. The shared
 * screens take theirs from `ui/shared`. The tray menu and notifications are AWT and need plain strings, which is why
 * this is a resource bundle and not Compose resources.
 *
 * Patterns are [MessageFormat]s; counts use choice formats. A key missing from a translation falls back to English; a
 * key missing everywhere shows the key itself, so a typo is visible but never crashes the app.
 */
class DesktopStrings private constructor(
    val locale: Locale,
    private val bundle: ResourceBundle,
    private val fallback: ResourceBundle,
) {
    operator fun get(
        key: String,
        vararg args: Any,
    ): String {
        val pattern =
            try {
                bundle.getString(key)
            } catch (_: MissingResourceException) {
                try {
                    fallback.getString(key)
                } catch (_: MissingResourceException) {
                    return key
                }
            }
        return if (args.isEmpty()) MessageFormat(pattern, locale).format(emptyArray<Any>()) else MessageFormat(pattern, locale).format(args)
    }

    /** Every key of the English file (tests: both languages define the same keys). */
    val keys: Set<String> get() = fallback.keySet()

    companion object {
        private const val BASE = "com.constrivo.drop.ui.desktop.strings"
        private val NO_FALLBACK: ResourceBundle.Control =
            ResourceBundle.Control.getNoFallbackControl(
                ResourceBundle.Control.FORMAT_PROPERTIES,
            )

        /** The strings for [locale]: Hindi for `hi`, English otherwise. */
        fun of(locale: Locale): DesktopStrings {
            val english = ResourceBundle.getBundle(BASE, Locale.ROOT, DesktopStrings::class.java.classLoader, NO_FALLBACK)
            val bundle =
                if (locale.language == "hi") {
                    ResourceBundle.getBundle(BASE, Locale.forLanguageTag("hi"), DesktopStrings::class.java.classLoader, NO_FALLBACK)
                } else {
                    english
                }
            return DesktopStrings(if (locale.language == "hi") Locale.forLanguageTag("hi") else Locale.ENGLISH, bundle, english)
        }

        /** The strings for Settings → Language ([tag], null for the system language). */
        fun forLanguage(tag: String?): DesktopStrings = of(tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
    }
}
