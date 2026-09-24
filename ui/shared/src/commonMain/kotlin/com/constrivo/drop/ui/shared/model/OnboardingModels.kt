package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The permissions of architecture §11 that are asked just in time (F‑I1, design §7 step 2), with the moment each is
 * first needed. The platform maps them to its own permission names (Android: `AndroidPermissionMatrix`).
 */
enum class DropPermission(
    val askedAt: PermissionMoment,
) {
    /** `BLUETOOTH_SCAN/CONNECT/ADVERTISE` (12+) and `NEARBY_WIFI_DEVICES` (13+). */
    NEARBY(PermissionMoment.RADAR_OPEN),

    /** `ACCESS_FINE_LOCATION`, Android 12 only, for Wi‑Fi Direct discovery. */
    LOCATION_FOR_WIFI_DIRECT(PermissionMoment.FIRST_SEND),

    /** `READ_MEDIA_*` (13+) or `READ_EXTERNAL_STORAGE` (12), for the picker and thumbnails. */
    MEDIA(PermissionMoment.FIRST_SEND),

    /** `POST_NOTIFICATIONS` (13+), for progress and incoming cards. */
    NOTIFICATIONS(PermissionMoment.FIRST_TRANSFER),

    /** `CAMERA`, to scan codes. */
    CAMERA(PermissionMoment.FIRST_SCAN),

    /** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, once in onboarding, skippable. */
    BATTERY(PermissionMoment.ONBOARDING),
}

enum class PermissionMoment { RADAR_OPEN, FIRST_SEND, FIRST_TRANSFER, FIRST_SCAN, ONBOARDING }

/** What the platform says about a permission. */
enum class PermissionStatus {
    GRANTED,

    /** Not granted; asking shows the system dialog. */
    DENIED,

    /** Denied with "don't ask again" (or by policy): only the app's system settings page can grant it. */
    BLOCKED,

    /** This OS version does not need it (for example notifications before Android 13). */
    NOT_NEEDED,
    ;

    val usable: Boolean get() = this == GRANTED || this == NOT_NEEDED
}

/** The just-in-time explainer sheet (design §7 step 2): one line of why, then the system dialog or the recovery. */
@Immutable
data class PermissionPromptUi(
    val permission: DropPermission,
    /** True after a denial that only Settings can undo: the sheet offers "Open settings" (F‑I1 recovery). */
    val blocked: Boolean,
)

/** OEMs with the brand step of design §7 step 3 (F‑I2). */
enum class OemBrand {
    /** Xiaomi, Redmi, POCO (MIUI / HyperOS Autostart). */
    XIAOMI,

    /** Vivo, iQOO. */
    VIVO,

    /** Oppo, Realme, OnePlus (ColorOS). */
    OPPO,
    SAMSUNG,
    ;

    companion object {
        /** The brand of a device manufacturer or brand string (Android `Build.MANUFACTURER` / `Build.BRAND`), or null. */
        fun of(vararg names: String?): OemBrand? {
            for (raw in names) {
                val name = raw?.trim()?.lowercase() ?: continue
                when (name) {
                    "xiaomi", "redmi", "poco" -> return XIAOMI
                    "vivo", "iqoo" -> return VIVO
                    "oppo", "realme", "oneplus" -> return OPPO
                    "samsung" -> return SAMSUNG
                }
            }
            return null
        }
    }
}

enum class OnboardingStep { WELCOME, BRAND }

/** Onboarding (design §7, F‑I3, F‑J4). */
@Immutable
data class OnboardingUi(
    val step: OnboardingStep,
    val nickname: String,
    /** The nickname has a visible character (core/discovery `Nicknames`); "Start" is enabled only then. */
    val nicknameValid: Boolean,
    val avatar: ImageBitmap?,
    val brand: OemBrand?,
)
