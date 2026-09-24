package com.constrivo.drop.platform.android.service

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.platform.android.notification.LaunchTarget
import com.constrivo.drop.platform.android.notification.NotificationTexts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the app gives the [TransferService]: its `Application` implements this (the service fails fast otherwise), so
 * this library needs nothing from `ui/android` but the interface. Called on the main thread.
 */
interface TransferServiceHost {
    /** The ring-buffer log of the process (architecture §14); it outlives service restarts, the export reads it. */
    val serviceLog: ServiceLog

    /** The notifications' strings in the user's language. */
    val notificationTexts: NotificationTexts

    /** The notifications' small icon: a monochrome drawable of the app. */
    @get:DrawableRes
    val notificationIcon: Int

    /** Whether onboarding finished: before that the phone is never visible in the background (S9). */
    val onboarded: StateFlow<Boolean>

    /** The nickname before the user sets one (F-I3): the device's name. */
    val defaultNickname: String

    /** The activity intent a notification tap opens. */
    fun launchIntent(target: LaunchTarget): Intent

    /**
     * The ladder's Wi-Fi rungs on this phone (WP7c's Wi-Fi Direct provider, WP7d's hotspot and LAN providers), created
     * once per service. None: transfers stay on Bluetooth, and the browser page cannot be hosted.
     */
    fun wifiProviders(context: Context): List<WifiLinkProvider> = emptyList()

    /** WP7d's mDNS browse of desktops on the LAN, for the radar (F-H4). */
    fun lanEvents(context: Context): Flow<LanEvent> = emptyFlow()

    /** Dials a desktop's LAN endpoint (mDNS, a scanned code); null disables the LAN path. */
    fun lanDialer(context: Context): LanDialer? = TcpLanDialer()
}
