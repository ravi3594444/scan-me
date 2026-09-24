package com.constrivo.drop.ui.desktop

import androidx.compose.ui.input.key.Key
import com.constrivo.drop.platform.desktop.DesktopOs
import com.constrivo.drop.ui.shared.presenter.Screen

/** What a shortcut does (design §9 "Keyboard"). */
enum class ShortcutAction {
    /** Ctrl+O (Cmd+O on macOS): pick files or folders to send. */
    PICK_FILES,

    /** Esc: cancel the current selection. */
    CANCEL,

    /** Enter: send. */
    SEND,

    /** Up / Down in "Send to…": move the highlighted device. */
    PREVIOUS,
    NEXT,
}

/** The state a shortcut depends on. */
data class ShortcutContext(
    val screen: Screen,
    val sendToOpen: Boolean,
    val pickerOpen: Boolean,
    val pickerHasSelection: Boolean,
)

/** One key press, as Compose reports it (only key-down events reach [KeyboardShortcuts.action]). */
data class KeyPress(
    val key: Key,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

/**
 * Design §9: "Ctrl/Cmd+O pick files, Esc cancels current selection, Enter sends." Pure, so the rules are tested
 * without a window:
 *
 * - Ctrl+O on Windows and Linux, Cmd+O on macOS (the other modifier does nothing), anywhere but onboarding.
 * - Esc anywhere but onboarding (where it would skip the nickname).
 * - Enter only when there is something to send — "Send to…" open or the picker holding a selection — so Enter in a
 *   text field (a nickname, a rename) still reaches the field. Up and Down only move the "Send to…" highlight.
 */
object KeyboardShortcuts {
    fun action(
        press: KeyPress,
        context: ShortcutContext,
        os: DesktopOs = DesktopOs.current,
    ): ShortcutAction? {
        if (context.screen == Screen.ONBOARDING) return null
        val command = if (os.usesCommandKey) press.meta && !press.ctrl else press.ctrl && !press.meta
        return when {
            press.key == Key.O && command && !press.alt && !press.shift -> {
                ShortcutAction.PICK_FILES
            }

            press.ctrl || press.meta || press.alt -> {
                null
            }

            press.key == Key.Escape -> {
                ShortcutAction.CANCEL
            }

            (press.key == Key.Enter || press.key == Key.NumPadEnter) && !press.shift -> {
                if (context.sendToOpen || (context.pickerOpen && context.pickerHasSelection)) ShortcutAction.SEND else null
            }

            press.key == Key.DirectionUp && context.sendToOpen -> {
                ShortcutAction.PREVIOUS
            }

            press.key == Key.DirectionDown && context.sendToOpen -> {
                ShortcutAction.NEXT
            }

            else -> {
                null
            }
        }
    }
}
