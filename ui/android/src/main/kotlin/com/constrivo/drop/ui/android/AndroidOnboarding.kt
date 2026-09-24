package com.constrivo.drop.ui.android

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.presenter.OnboardingActions

/**
 * The OEM screens that let an app keep running in the background (design §7 step 3, F‑I2, architecture §10.1:
 * "Xiaomi Autostart, Vivo/Oppo background, Samsung sleeping apps"), most specific first. Their component names are
 * not public API and move between OS versions, so every entry is only a candidate; the lab confirms them per device
 * (implementation plan 7f, T‑22). Pure data, unit-tested.
 */
internal object OemScreens {
    data class Screen(
        val packageName: String,
        val className: String,
    )

    fun candidates(brand: OemBrand): List<Screen> =
        when (brand) {
            OemBrand.XIAOMI -> {
                listOf(
                    Screen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    Screen("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                )
            }

            OemBrand.VIVO -> {
                listOf(
                    Screen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                )
            }

            OemBrand.OPPO -> {
                listOf(
                    Screen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    Screen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    Screen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                )
            }

            OemBrand.SAMSUNG -> {
                listOf(
                    Screen("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    Screen("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
            }
        }
}

/**
 * Onboarding on Android (design §7): the nickname is kept in [ShellPreferences] (and handed to [onFinished], which
 * updates the running profile), the avatar comes from the system photo picker ([onPickAvatar]), and the brand step
 * opens the brand's background screen, falling back to the battery-optimisation request and then to the app's
 * settings page when the OEM screen is missing on this OS build.
 */
internal class AndroidOnboarding(
    private val context: Context,
    private val bridge: ActivityBridge,
    private val prefs: ShellPreferences,
    private val onFinished: (nickname: String) -> Unit,
    private val onPickAvatar: () -> Unit,
) : OnboardingActions {
    override fun finish(nickname: String) {
        prefs.nickname = nickname
        prefs.onboarded = true
        onFinished(nickname)
    }

    override fun pickAvatar() = onPickAvatar()

    override fun openBrandSettings(brand: OemBrand) {
        for (screen in OemScreens.candidates(brand)) {
            val intent = Intent().setComponent(ComponentName(screen.packageName, screen.className))
            if (bridge.start(intent)) return
        }
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
            if (bridge.start(ignoreBatteryOptimisations(context))) return
        }
        bridge.start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    companion object {
        /** The device's own name for the nickname prefill ("Asha's Pixel"), else its model. */
        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: Build.MODEL.orEmpty()

        /** The OEM brand step applies to (design §7 step 3). */
        fun brand(): OemBrand? = OemBrand.of(Build.MANUFACTURER, Build.BRAND)

        /**
         * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this app. Architecture §10.1/§11 make the direct request
         * the path for a transfer app that must survive OEM background killing; it is asked once, in onboarding, and
         * can be skipped.
         */
        @SuppressLint("BatteryLife")
        fun ignoreBatteryOptimisations(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
    }
}
