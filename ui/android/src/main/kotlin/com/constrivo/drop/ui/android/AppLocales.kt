package com.constrivo.drop.ui.android

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.constrivo.drop.ui.shared.model.AppLanguage

/**
 * Settings → Language on Android (decision 9, F‑G5: applied at once, without a restart).
 *
 * - Android 13+: the per-app language of `LocaleManager` (also offered in the system's app settings through
 *   `res/xml/locales_config.xml`). The system recreates the activities in the new language; the process-wide
 *   controller keeps its state.
 * - Android 12: the shared UI applies it in place ([inApp]: `DropApp`'s `applyLanguage`), and the choice is kept in
 *   [ShellPreferences] for the next launch.
 */
internal class AppLocales(
    private val context: Context,
    private val prefs: ShellPreferences,
) {
    /** Whether the shared UI applies the language (Android 12); on 13+ the system does. */
    val inApp: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** The language in force at start-up. */
    fun current(): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemChoice()
        } else {
            AppLanguage.ofTag(prefs.languageTag)
        }

    fun apply(language: AppLanguage) {
        prefs.languageTag = language.tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setSystemChoice(language)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun systemChoice(): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return AppLanguage.SYSTEM
        return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.ofTag(locales[0].language)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setSystemChoice(language: AppLanguage) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    }
}
