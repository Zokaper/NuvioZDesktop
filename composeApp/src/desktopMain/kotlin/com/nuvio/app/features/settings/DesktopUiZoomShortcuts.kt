package com.nuvio.app.features.settings

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * `Ctrl` `+` / `-` / `0` - interface zoom, from anywhere in the app.
 *
 * ## Why a global AWT dispatcher rather than a Compose key handler
 *
 * Zoom has to work *wherever you are*, including over the player, a settings page, or the setup
 * wizard while it is gating the app. A Compose `onPreviewKeyEvent` only sees events once its own
 * subtree has focus, so it would need attaching to every screen and would still miss the Swing
 * video surface, which is a real AWT component and swallows key events before Compose ever runs.
 *
 * `KeyboardFocusManager.addKeyEventDispatcher` sits above all of that: it sees every key event in
 * the process before it is dispatched to any component. This is the same mechanism
 * `installDesktopAppFullscreenShortcuts` already uses for `F11`, and this installer follows its
 * contract exactly - it returns its own uninstaller, and the caller must invoke it on dispose or
 * the dispatcher outlives the window.
 *
 * ⚠ **Returning `true` consumes the event.** That is wanted here - nothing else in the app binds
 * these combinations - but it means a future binding on `Ctrl`+`0` would silently never fire.
 * The only other shortcuts registered anywhere in the desktop app are `F11` and `Cmd`+`Ctrl`+`F`.
 *
 * Text fields are unaffected: these are all modifier-held combinations, which produce no typed
 * character, so nothing is stolen from the addon URL field or the search box.
 */
internal fun installDesktopUiZoomShortcuts(): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        when (event.zoomShortcut()) {
            ZoomShortcut.In -> {
                ThemeSettingsRepository.zoomDesktopUiIn()
                true
            }

            ZoomShortcut.Out -> {
                ThemeSettingsRepository.zoomDesktopUiOut()
                true
            }

            ZoomShortcut.Reset -> {
                ThemeSettingsRepository.resetDesktopUiZoom()
                true
            }

            null -> false
        }
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private enum class ZoomShortcut { In, Out, Reset }

private fun KeyEvent.zoomShortcut(): ZoomShortcut? {
    if (id != KeyEvent.KEY_PRESSED) return null

    // `Ctrl` on Windows and Linux, `Cmd` on macOS - the platform conventions for zoom differ, and
    // accepting either is cheaper than branching on the host OS for a shortcut this small.
    val accelerator = modifiersEx and (KeyEvent.CTRL_DOWN_MASK or KeyEvent.META_DOWN_MASK) != 0
    if (!accelerator) return null

    // ⚠ Alt must be clear. `Cmd`+`Ctrl`+`F` is the existing fullscreen binding and Alt-modified
    // combinations belong to the window manager on Linux; matching loosely here would consume
    // events that are not ours.
    if (modifiersEx and KeyEvent.ALT_DOWN_MASK != 0) return null

    return when (keyCode) {
        // `Ctrl` `+` is physically `Ctrl` `=` on most layouts, so both are accepted, plus the
        // numeric keypad.
        KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> ZoomShortcut.In
        KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> ZoomShortcut.Out
        KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> ZoomShortcut.Reset
        else -> null
    }
}
