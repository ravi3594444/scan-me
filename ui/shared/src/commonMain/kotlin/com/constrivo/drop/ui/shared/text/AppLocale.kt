package com.constrivo.drop.ui.shared.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.constrivo.drop.ui.shared.model.AppLanguage
import java.util.Locale

/**
 * The in-app language (Settings → Language, decision 9), applied without a restart (F‑G5).
 *
 * Compose resources pick their strings from the default locale, so choosing English or Hindi makes that the default
 * locale of the process, and "System default" restores the one the process started with. Both apps run on the JVM, so
 * `java.util.Locale` is available here. Hosts that apply the language through the platform instead (Android 13+:
 * `LocaleManager`, which recreates the activities) skip this ([com.constrivo.drop.ui.shared.DropApp]'s
 * `applyLanguage`).
 */
object AppLocale {
    /** The default locale before the app first changed it. */
    private val system: Locale by lazy { Locale.getDefault() }

    /** Makes [language] the default locale (the system's for [AppLanguage.SYSTEM]). */
    fun apply(language: AppLanguage) {
        val base = system
        val target = language.tag?.let(Locale::forLanguageTag) ?: base
        if (Locale.getDefault() != target) Locale.setDefault(target)
    }
}

/**
 * Shows [content] in [language]: applies it ([AppLocale]) and rebuilds the content when it changes, so every string
 * resource is looked up again at once. Null leaves the locale alone.
 */
@Composable
internal fun LanguageScope(
    language: AppLanguage?,
    content: @Composable () -> Unit,
) {
    if (language == null) {
        content()
        return
    }
    // Applied while composing, before any string below is read; runs again only when the choice changes (or after
    // the host recreated the composition, when the platform may have reset the default locale).
    remember(language) { AppLocale.apply(language) }
    key(language) { content() }
}
