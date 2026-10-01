package com.nuvio.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's activity follows the tab that is shown (Performance Phase 1): the always-mounted Home in
 * [AppTabHost] keeps its state behind another tab but stops working there.
 */
class AppTabHostHomeActivityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHeroPagesWhileHomeIsShown() {
        HomeActivityProbe.show(compose) { HomeActivityProbe.Host(AppScreenTab.Home) }
        compose.mainClock.autoAdvance = false
        assertTrue(HomeActivityProbe.heroChanges(compose, seconds = 30) > 0, "hero never paged on a visible Home")
    }

    @Test
    fun aHiddenHomeStaysComposedButStopsPagingAndResumesWhenShownAgain() {
        var tab by mutableStateOf(AppScreenTab.Home)
        HomeActivityProbe.show(compose) { HomeActivityProbe.Host(tab) }
        compose.mainClock.autoAdvance = false
        assertTrue(HomeActivityProbe.heroChanges(compose, seconds = 20) > 0, "hero never paged on a visible Home")

        tab = AppScreenTab.Library
        compose.mainClock.advanceTimeBy(2_000L)
        assertTrue(HomeActivityProbe.heroTitles(compose).isNotEmpty(), "Home should stay composed behind Library")
        assertEquals(0, HomeActivityProbe.heroChanges(compose, seconds = 30), "a hidden Home kept paging its hero")

        tab = AppScreenTab.Home
        compose.mainClock.advanceTimeBy(1_000L)
        assertTrue(HomeActivityProbe.heroChanges(compose, seconds = 30) > 0, "hero did not resume when Home came back")
    }

    /** An iOS 16+ native-tab host: choosing Home there switches hosts, so Home has no business here. */
    @Test
    fun aNativeTabHostDoesNotComposeHomeBehindItsOwnTab() {
        HomeActivityProbe.show(compose) { HomeActivityProbe.Host(AppScreenTab.Library, keepHomeBehindOtherTabs = false) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2_000L)
        assertTrue(HomeActivityProbe.heroTitles(compose).isEmpty(), "a native Library host composed a hidden Home")
    }

    /** The same host still shows a working Home when Home is its tab - e.g. Social coerced to Home when Social is off. */
    @Test
    fun aNativeTabHostWhoseTabIsHomeShowsAWorkingHome() {
        HomeActivityProbe.show(compose) { HomeActivityProbe.Host(AppScreenTab.Home, keepHomeBehindOtherTabs = false) }
        compose.mainClock.autoAdvance = false
        assertTrue(HomeActivityProbe.heroChanges(compose, seconds = 30) > 0, "hero never paged on a native Home host")
    }
}
