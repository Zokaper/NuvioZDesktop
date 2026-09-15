package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerEpisodeModeRouterTest {
    @Test
    fun classicOpensTheSourceList() {
        assertEquals(PlayerEpisodeModeRoute.SOURCE_LIST, playerEpisodeModeRoute(PlaybackMode.CLASSIC))
    }

    @Test
    fun streamlinedOpensTheQualitySheet() {
        assertEquals(PlayerEpisodeModeRoute.QUALITY_SHEET, playerEpisodeModeRoute(PlaybackMode.STREAMLINED))
    }

    @Test
    fun streamlinedDesktopPicksWithinItsPreferencesInsteadOfShowingClassicsList() {
        // ⚠ Regression, hardware Bug 3 (2026-09-15). This asserted SOURCE_LIST: the quality sheet
        // cannot be drawn over the native player, so desktop Streamlined fell back to Classic's
        // release list - and a Streamlined Watch Together host pressing Next episode was handed the
        // ordinary chooser. The unreachable sheet is answered from the Streamlined preferences
        // instead, through the same selector autoplay-next uses.
        assertEquals(
            PlayerEpisodeModeRoute.AUTO_PICK,
            playerEpisodeModeRoute(PlaybackMode.STREAMLINED, isDesktop = true),
        )
    }

    @Test
    fun onlyClassicEverOpensTheSourceListOnDesktop() {
        PlaybackMode.entries.forEach { mode ->
            val route = playerEpisodeModeRoute(mode, isDesktop = true)
            assertEquals(
                mode == PlaybackMode.CLASSIC,
                route == PlayerEpisodeModeRoute.SOURCE_LIST,
                "mode=$mode route=$route",
            )
            // Nothing on desktop may ask for a sheet the native surface would hide.
            assertEquals(false, route == PlayerEpisodeModeRoute.QUALITY_SHEET, "mode=$mode")
        }
    }

    @Test
    fun instantAutoPicks() {
        assertEquals(PlayerEpisodeModeRoute.AUTO_PICK, playerEpisodeModeRoute(PlaybackMode.INSTANT))
        assertEquals(PlayerEpisodeModeRoute.AUTO_PICK, playerEpisodeModeRoute(PlaybackMode.INSTANT, isDesktop = true))
    }
}
