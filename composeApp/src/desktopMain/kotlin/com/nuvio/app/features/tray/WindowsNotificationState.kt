package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs

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

    /** True while a fullscreen app, game or presentation is up. False when unknown or not on Windows. */
    fun isFullscreenBusy(): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return false
        val state = WindowsForeign.userNotificationState() ?: return false
        return state == QunsBusy || state == QunsRunningD3dFullScreen || state == QunsPresentationMode
    }
}
