package com.nuvio.app.features.reaction

import com.nuvio.app.features.player.desktop.NativePlayerBridge

/**
 * JNI surface of `native/windows/reaction_stage.inc.cpp`, which is compiled into player_bridge.dll.
 * Touching [NativePlayerBridge] first makes sure that DLL is loaded before any call resolves.
 */
internal object ReactionNative {
    val available: Boolean by lazy {
        runCatching {
            NativePlayerBridge.hashCode()
            // Any cheap call proves the exports are present (an older bridge DLL would not have them).
            takeEventsOrThrow()
            true
        }.getOrDefault(false)
    }

    private fun takeEventsOrThrow() {
        stageTakeEvents(0L)
    }

    /** Dual-screen: a borderless popup at virtual-screen pixel coordinates. */
    external fun createPopupStage(x: Int, y: Int, width: Int, height: Int, options: Array<String>): Long

    /** Single-screen: mpv's own window as a direct child of the main player's container. */
    external fun createInPlayerStage(playerHandle: Long, options: Array<String>): Long
    /** Analysis: an mpv with no window (encode mode writes the sampled frames to a file). */
    external fun createHeadlessStage(options: Array<String>): Long
    external fun destroyStage(handle: Long)
    external fun stageCommand(handle: Long, args: Array<String>): Int
    external fun stageSetProperty(handle: Long, name: String, value: String): Int
    external fun stageGetProperty(handle: Long, name: String): String?
    external fun stageTakeEvents(handle: Long): String
    external fun stageSetPopupBounds(handle: Long, x: Int, y: Int, width: Int, height: Int)
    external fun stageSetVisible(handle: Long, visible: Boolean)

    /** Single-screen: episode drawn in this rect (container pixels). False until the stage has a window. */
    external fun stageLayoutInPlayer(handle: Long, x: Int, y: Int, width: Int, height: Int): Boolean

    /** Client size of any window in physical pixels, packed as (width shl 32) or height; 0 if gone. */
    external fun windowClientSize(hwnd: Long): Long

    external fun mainContainerHwnd(playerHandle: Long): Long
    external fun mainSetProperty(playerHandle: Long, name: String, value: String)
    external fun mainGetProperty(playerHandle: Long, name: String): String?
    external fun mainRunControlsScript(playerHandle: Long, script: String)
}
