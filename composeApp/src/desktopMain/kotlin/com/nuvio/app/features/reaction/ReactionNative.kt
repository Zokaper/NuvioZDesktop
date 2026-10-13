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

    /** parentHwnd 0 = popup at virtual-screen coordinates; otherwise a child at parent coordinates. */
    external fun createStage(parentHwnd: Long, x: Int, y: Int, width: Int, height: Int, options: Array<String>): Long
    external fun destroyStage(handle: Long)
    external fun stageCommand(handle: Long, args: Array<String>): Int
    external fun stageSetProperty(handle: Long, name: String, value: String): Int
    external fun stageGetProperty(handle: Long, name: String): String?
    external fun stageTakeEvents(handle: Long): String
    external fun stageSetBounds(handle: Long, x: Int, y: Int, width: Int, height: Int)
    external fun stageSetVisible(handle: Long, visible: Boolean)
    external fun stageSetHole(handle: Long, x: Int, y: Int, width: Int, height: Int)
    external fun stageRestack(handle: Long)

    external fun mainContainerHwnd(playerHandle: Long): Long
    external fun mainSetProperty(playerHandle: Long, name: String, value: String)
    external fun mainGetProperty(playerHandle: Long, name: String): String?
    external fun mainRunControlsScript(playerHandle: Long, script: String)
}
