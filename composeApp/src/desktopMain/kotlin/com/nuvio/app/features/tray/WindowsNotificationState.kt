package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Whether something fullscreen owns the screen right now: `SHQueryUserNotificationState`, the call
 * Windows' own notifications consult. A game (Direct3D exclusive), a fullscreen app such as a browser
 * video, or presentation mode all answer "busy". Pop-ups respect it unless the user turned on
 * "Over fullscreen apps and games".
 */
internal object WindowsNotificationState {
    private const val QunsBusy = 2
    private const val QunsRunningD3dFullScreen = 3
    private const val QunsPresentationMode = 4

    private val query by lazy {
        runCatching {
            val linker = Linker.nativeLinker()
            val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
            val handle = linker.downcallHandle(
                shell32.find("SHQueryUserNotificationState").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
            return@runCatching {
                Arena.ofConfined().use { arena ->
                    val out = arena.allocate(ValueLayout.JAVA_INT)
                    val hr = handle.invokeWithArguments(out) as Int
                    if (hr == 0) out.get(ValueLayout.JAVA_INT, 0) else null
                }
            }
        }.getOrNull()
    }

    /** True while a fullscreen app, game or presentation is up. False when unknown or not on Windows. */
    fun isFullscreenBusy(): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return false
        val state = runCatching { query?.invoke() }.getOrNull() ?: return false
        return state == QunsBusy || state == QunsRunningD3dFullScreen || state == QunsPresentationMode
    }
}
