package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode

internal enum class PlayerEpisodeModeRoute {
    SOURCE_LIST,
    QUALITY_SHEET,
    AUTO_PICK,
}

/**
 * Keeps an episode chosen inside the player on the same playback path as a details-page play.
 *
 * Desktop playback is hosted by a native WebKit/WebView surface. A Compose quality sheet cannot
 * be raised above that surface while the player is open, so Streamlined must use the native
 * source panel there instead of opening an invisible sheet behind the video.
 */
internal fun playerEpisodeModeRoute(
    mode: PlaybackMode,
    isDesktop: Boolean = false,
): PlayerEpisodeModeRoute = when (mode) {
    PlaybackMode.CLASSIC -> PlayerEpisodeModeRoute.SOURCE_LIST
    PlaybackMode.STREAMLINED -> if (isDesktop) {
        PlayerEpisodeModeRoute.SOURCE_LIST
    } else {
        PlayerEpisodeModeRoute.QUALITY_SHEET
    }
    PlaybackMode.INSTANT -> PlayerEpisodeModeRoute.AUTO_PICK
}
