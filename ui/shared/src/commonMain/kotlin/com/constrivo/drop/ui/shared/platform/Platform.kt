package com.constrivo.drop.ui.shared.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Haptic feedback hooks (design §4.2: light haptic on completion, Android `CONFIRM`; §4.4: haptic on a scanned code).
 * The platform implements them and honours the "Haptic feedback" setting (design §11); the default does nothing.
 */
interface DropHaptics {
    /** A transfer completed (Android: `HapticFeedbackConstants.CONFIRM`). */
    fun confirm()

    /** A code was recognised by the scanner. */
    fun scanned()

    companion object {
        val None: DropHaptics =
            object : DropHaptics {
                override fun confirm() = Unit

                override fun scanned() = Unit
            }
    }
}

val LocalDropHaptics = staticCompositionLocalOf { DropHaptics.None }
