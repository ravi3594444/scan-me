package com.constrivo.drop.ui.shared.presenter

import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.OnboardingStep
import com.constrivo.drop.ui.shared.model.OnboardingUi
import com.constrivo.drop.ui.shared.model.PermissionPromptUi
import com.constrivo.drop.ui.shared.model.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What onboarding writes (the app layer stores the profile with core/data's `SettingsRepository`). */
interface OnboardingActions {
    /** Saves the nickname (already sanitised, F‑I3) and marks onboarding as done. */
    fun finish(nickname: String)

    /** Opens the platform avatar picker; the result comes back through [OnboardingPresenter.setAvatar]. */
    fun pickAvatar()

    /** Opens the brand's background-running screen (design §7 step 3, F‑I2). */
    fun openBrandSettings(brand: OemBrand)
}

/**
 * Onboarding (design §7): the welcome screen with the nickname prefilled from the device name and an optional avatar,
 * then, only on the OEMs of design §7 step 3, the brand step with "Skip for now". "Start" needs a nickname with a
 * visible character (core/discovery `Nicknames`), which is what goes into the beacon and cards.
 */
class OnboardingPresenter(
    deviceName: String,
    private val brand: OemBrand?,
    private val actions: OnboardingActions,
) {
    private val ui =
        MutableStateFlow(
            OnboardingUi(
                step = OnboardingStep.WELCOME,
                nickname = deviceName,
                nicknameValid = Nicknames.normalize(deviceName) != null,
                avatar = null,
                brand = brand,
            ),
        )
    private val done = MutableStateFlow(false)

    val state: StateFlow<OnboardingUi> = ui.asStateFlow()

    /** True once onboarding finished (the host shows the radar). */
    val finished: StateFlow<Boolean> = done.asStateFlow()

    fun onNicknameChanged(text: String) {
        ui.update { it.copy(nickname = text, nicknameValid = Nicknames.normalize(text) != null) }
    }

    fun pickAvatar() = actions.pickAvatar()

    fun setAvatar(image: ImageBitmap?) {
        ui.update { it.copy(avatar = image) }
    }

    /** "Start": saves the profile, then shows the brand step where one applies. */
    fun onStart() {
        val clean = Nicknames.normalize(ui.value.nickname) ?: return
        if (brand != null && ui.value.step == OnboardingStep.WELCOME) {
            ui.update { it.copy(nickname = clean.text, step = OnboardingStep.BRAND) }
            return
        }
        complete(clean.text)
    }

    fun onOpenBrandSettings() {
        val b = brand ?: return
        actions.openBrandSettings(b)
        complete(ui.value.nickname)
    }

    fun onSkipBrand() = complete(ui.value.nickname)

    private fun complete(nickname: String) {
        if (done.value) return
        val clean = Nicknames.normalize(nickname)?.text ?: return
        done.value = true
        actions.finish(clean)
    }
}

/** The platform's permission API (Android: runtime permissions and the battery-optimisation intent). */
interface PermissionController {
    fun status(permission: DropPermission): PermissionStatus

    /** Shows the system dialog and returns the result (Android: `RequestMultiplePermissions`). */
    suspend fun request(permission: DropPermission): PermissionStatus

    /** Opens the app's system settings page, the only way back from [PermissionStatus.BLOCKED]. */
    fun openAppSettings()

    companion object {
        /** Everything granted (desktops, tests). */
        val AllGranted: PermissionController =
            object : PermissionController {
                override fun status(permission: DropPermission) = PermissionStatus.GRANTED

                override suspend fun request(permission: DropPermission) = PermissionStatus.GRANTED

                override fun openAppSettings() = Unit
            }
    }
}

/**
 * Just-in-time permissions (F‑I1, design §7 step 2, architecture §11): [ensure] is called when a feature first needs a
 * permission. If it is missing, the explainer sheet shows one line of why; "Continue" opens the system dialog, "Not
 * now" gives up for now. A permission that only Settings can grant shows the recovery sheet with "Open settings"
 * (F‑I1: denial paths show recovery). Nothing is asked before it is needed, and one request runs at a time.
 */
class PermissionGate(
    private val controller: PermissionController,
) {
    private val prompt = MutableStateFlow<PermissionPromptUi?>(null)
    private var answer: CompletableDeferred<Boolean>? = null
    private val serial = Mutex()

    /** The explainer on screen, or null. */
    val state: StateFlow<PermissionPromptUi?> = prompt.asStateFlow()

    /** True when [permission] is usable, asking the user first if needed. */
    suspend fun ensure(permission: DropPermission): Boolean =
        serial.withLock {
            var status = controller.status(permission)
            if (status.usable) return@withLock true
            if (!explain(permission, blocked = status == PermissionStatus.BLOCKED)) return@withLock false
            if (status == PermissionStatus.BLOCKED) {
                controller.openAppSettings()
                return@withLock false
            }
            status = controller.request(permission)
            if (status.usable) return@withLock true
            if (status == PermissionStatus.BLOCKED && explain(permission, blocked = true)) controller.openAppSettings()
            false
        }

    /** "Continue" or "Open settings". */
    fun onContinue() = reply(true)

    /** "Not now", or the sheet was dismissed. */
    fun onDismiss() = reply(false)

    private suspend fun explain(
        permission: DropPermission,
        blocked: Boolean,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        prompt.value = PermissionPromptUi(permission, blocked)
        return try {
            deferred.await()
        } finally {
            prompt.value = null
            answer = null
        }
    }

    private fun reply(value: Boolean) {
        answer?.complete(value)
    }
}
