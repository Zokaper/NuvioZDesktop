package com.nuvio.app.features.tray

import androidx.compose.ui.awt.ComposeWindow
import coil3.PlatformContext
import java.awt.Frame
import javax.swing.SwingUtilities
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whether Nuvio's window exists, and what happens to the process while it does not (Social V2 tray mode).
 *
 * ## How "hidden in the tray" works
 *
 * Hiding **removes the window and its whole composition**; the process, the signed-in session, the
 * active profile and the Social layer carry on. `SocialRepository` runs on its own scope and the window's
 * composition never shuts it down, so its Realtime channel keeps refreshing the inbox and Watching Now,
 * which is what the pop-ups read. Opening Nuvio again re-creates the window, and [com.nuvio.app.AppGateResume]
 * puts it straight back on the app.
 *
 * This is the approach Stage T0 measured (`PLAN-social-v2.md`): ~1.1 GB open, ~437 MB hidden once the UI
 * is gone and the heap collected, ~135 MB after the working-set trim, plus ~80 MB of WebView2 helpers.
 */
internal object DesktopTrayController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _windowVisible = MutableStateFlow(true)
    val windowVisible: StateFlow<Boolean> = _windowVisible.asStateFlow()

    private val _closePromptVisible = MutableStateFlow(false)
    val closePromptVisible: StateFlow<Boolean> = _closePromptVisible.asStateFlow()

    private var releaseJob: Job? = null

    /**
     * The window's close button, after any Watch Together departure has been settled. [quit] is the
     * full shutdown; it is passed in because only the application scope can exit.
     */
    fun onCloseRequested(quit: () -> Unit) {
        when (DesktopTrayPreferences.current.closeAction) {
            DesktopCloseAction.Ask -> _closePromptVisible.value = true
            DesktopCloseAction.HideToTray -> hide()
            DesktopCloseAction.Quit -> quit()
        }
    }

    /** The first-close prompt's answer, which becomes the setting. Dismissing it answers nothing. */
    fun answerClosePrompt(hideToTray: Boolean, quit: () -> Unit) {
        _closePromptVisible.value = false
        DesktopTrayPreferences.setCloseAction(if (hideToTray) DesktopCloseAction.HideToTray else DesktopCloseAction.Quit)
        if (hideToTray) hide() else quit()
    }

    fun dismissClosePrompt() {
        _closePromptVisible.value = false
    }

    fun hide() {
        if (!_windowVisible.value) return
        _windowVisible.value = false
        releaseJob?.cancel()
        releaseJob = scope.launch { releaseMemoryWhileHidden() }
    }

    fun show() {
        releaseJob?.cancel()
        releaseJob = null
        _windowVisible.value = true
    }

    /** The live main window, while there is one; set and cleared by `Main.kt`. */
    @Volatile
    var mainWindow: ComposeWindow? = null

    /** Restores a minimised window and raises it; a window being re-created raises itself when it opens. */
    fun bringToFront() {
        val window = mainWindow ?: return
        SwingUtilities.invokeLater {
            if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            window.toFront()
            window.requestFocus()
        }
    }

    /**
     * Drops what only the window needed, then keeps the resident set small while hidden.
     *
     * The image cache holds decoded posters and backdrops for screens that no longer exist; two
     * collections let G1 give the freed heap back (with the shipped `MaxHeapFreeRatio`); the trim then
     * moves the untouched rest out of RAM. Re-trimmed every few minutes, since periodic GC and the
     * Social refreshes slowly touch pages back in.
     */
    private suspend fun releaseMemoryWhileHidden() {
        delay(WindowTeardownGraceMs)
        runCatching { SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear() }
        repeat(2) {
            System.gc()
            delay(1_000)
        }
        delay(TrimDelayMs)
        while (scope.isActive && !_windowVisible.value) {
            WindowsWorkingSet.trim()
            delay(RetrimIntervalMs)
        }
    }

    private const val WindowTeardownGraceMs = 2_000L
    private const val TrimDelayMs = 8_000L
    private const val RetrimIntervalMs = 10 * 60_000L
}
