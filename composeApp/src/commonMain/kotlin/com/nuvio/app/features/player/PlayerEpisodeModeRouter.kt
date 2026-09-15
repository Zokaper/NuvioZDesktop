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
 * be raised above that surface while the player is open, so desktop Streamlined cannot ask its
 * quality question in the player at all.
 *
 * ⚠ **It used to answer with Classic's release list instead, and that was hardware Bug 3
 * (2026-09-15).** A Streamlined host pressed Next episode and got the ordinary source list - the one
 * interaction Streamlined exists to remove - and in a Watch Together party that list was the host
 * deciding by hand, mid-episode, what everyone would watch next. The quality sheet being unreachable
 * is not a reason to fall back to a *different mode's* question. It is a reason to answer the
 * question from what the user already told Streamlined: the in-player path picks within their
 * Streamlined preferences (quality ceiling, codec, dynamic range, audio language, the connection
 * estimate) through `PlaybackQualityOptions` and `PlaybackSourceSelector` - the exact selector the
 * Streamlined autoplay-next has always used - and only uncovers the source list when that selection
 * genuinely finds nothing, saying why. Manual Next episode and autoplay-next are the same flow now,
 * the countdown being the only difference.
 *
 * Mobile is unchanged: the sheet can be drawn over its player, so Streamlined still asks there.
 */
internal fun playerEpisodeModeRoute(
    mode: PlaybackMode,
    isDesktop: Boolean = false,
): PlayerEpisodeModeRoute = when (mode) {
    PlaybackMode.CLASSIC -> PlayerEpisodeModeRoute.SOURCE_LIST
    PlaybackMode.STREAMLINED -> if (isDesktop) {
        PlayerEpisodeModeRoute.AUTO_PICK
    } else {
        PlayerEpisodeModeRoute.QUALITY_SHEET
    }
    PlaybackMode.INSTANT -> PlayerEpisodeModeRoute.AUTO_PICK
}
