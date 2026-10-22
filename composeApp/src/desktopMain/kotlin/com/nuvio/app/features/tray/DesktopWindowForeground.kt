package com.nuvio.app.features.tray

import androidx.compose.ui.awt.ComposeWindow
import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Hands Windows' foreground to the main window (2026-10-23).
 *
 * `toFront()` alone did nothing when a Social pop-up was clicked over another app: Windows only lets
 * the foreground process raise its windows, and a pop-up that never takes focus does not make Nuvio
 * that process - it only flashes the taskbar button. The click is the last input event, though, and
 * that is the other thing `SetForegroundWindow` accepts, so asking explicitly is allowed. Quiet off
 * Windows and on any failure: the window still gets AWT's own raise.
 */
internal object DesktopWindowForeground {
    private val log = Logger.withTag("DesktopWindowForeground")
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private interface User32 : Library {
        fun IsIconic(hWnd: Pointer): Boolean
        fun ShowWindow(hWnd: Pointer, nCmdShow: Int): Boolean
        fun SetForegroundWindow(hWnd: Pointer): Boolean
    }

    private val user32: User32? by lazy {
        if (!isWindows) null else runCatching { Native.load("user32", User32::class.java) }
            .onFailure { log.w(it) { "user32 unavailable" } }
            .getOrNull()
    }

    /** Call on the AWT thread, after AWT's own raise. */
    fun request(window: ComposeWindow) {
        val api = user32 ?: return
        runCatching {
            val hwnd = Pointer(window.windowHandle)
            if (api.IsIconic(hwnd)) api.ShowWindow(hwnd, SwRestore)
            val granted = api.SetForegroundWindow(hwnd)
            log.i { "foreground requested granted=$granted" }
        }.onFailure { log.w(it) { "foreground request failed" } }
    }

    private const val SwRestore = 9
}
