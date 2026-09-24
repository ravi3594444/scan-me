package com.constrivo.drop.ui.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.constrivo.drop.R
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.platform.android.notification.LaunchTarget
import com.constrivo.drop.platform.android.notification.NotificationTexts
import com.constrivo.drop.platform.android.service.ServiceLog
import com.constrivo.drop.platform.android.service.TransferServiceHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The application: holds the process's one [AppGraph] (the shared UI's controller and its Android ports), created
 * when the first activity asks for it, and hosts the [com.constrivo.drop.platform.android.service.TransferService]
 * ([TransferServiceHost]): the ring-buffer log, the notification texts and icon, the onboarding state, and the
 * device's name as the default nickname. The service may run without any activity (a transfer, background
 * visibility), so nothing here needs the graph.
 */
class DropApplication :
    Application(),
    TransferServiceHost {
    internal val graph: AppGraph by lazy { AppGraph(this) }

    private val prefs: ShellPreferences by lazy { ShellPreferences(ShellPreferences.store(this)) }
    private val onboardedState by lazy { MutableStateFlow(prefs.onboarded) }

    override val serviceLog: ServiceLog by lazy {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ServiceLog(AndroidClocks.wall) { entry -> if (debuggable) Log.d(TAG, "[${entry.tag}] ${entry.message}") }
    }

    override val notificationTexts: NotificationTexts by lazy { AndroidNotificationTexts(::localizedContext) }

    override val notificationIcon: Int get() = R.drawable.ic_notification

    override val onboarded: StateFlow<Boolean> get() = onboardedState.asStateFlow()

    override val defaultNickname: String get() = AndroidOnboarding.deviceName(this)

    override fun launchIntent(target: LaunchTarget): Intent = NotificationIntents.launch(this, target)

    /** Onboarding finished (the phone may now be visible in the background, S9). */
    internal fun markOnboarded() {
        prefs.onboarded = true
        onboardedState.value = true
    }

    /**
     * A context in the language of Settings → Language: the app itself on Android 13+ (the per-app locale), one
     * configured with the in-app choice on Android 12, where the shared UI applies the language itself ([AppLocales]).
     */
    internal fun localizedContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
        val tag = prefs.languageTag ?: return this
        val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return createConfigurationContext(config)
    }

    private companion object {
        const val TAG = "Drop"
    }
}
