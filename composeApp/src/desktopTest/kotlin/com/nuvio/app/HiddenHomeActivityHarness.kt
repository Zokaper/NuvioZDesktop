package com.nuvio.app

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import org.junit.Rule
import kotlin.test.Test

/**
 * Performance Phase 1 measurement, not a regression test: what a Home the viewer cannot see still does.
 * The assertions live in [AppTabHostHomeActivityTest].
 *
 * Composes the real [AppTabHost] with eight hero titles in Home's repository and records, over 60 s of
 * virtual time with the clock driven by hand:
 * - live collectors on the repositories `HomeScreen` collects, over the count before anything was
 *   composed (the selected tab's own screen collects some of them too),
 * - how many semantics nodes the host composes,
 * - how often the hero changed title (sampled once a second),
 * - global snapshot applies (state writes that reach composition).
 *
 * Three hosts: Home selected (control); Library selected in the single-host shell (desktop, Android,
 * iOS below 16); Library selected in an iOS 16+ native-tab host, which never shows Home in place.
 * Results are appended to `composeApp/build/perf-phase1/hidden-home.txt`.
 */
class HiddenHomeActivityHarness {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeSelected() = measure("home-selected", AppScreenTab.Home, keepHomeBehindOtherTabs = true)

    @Test
    fun libraryInSingleHostShell() = measure("library-single-host", AppScreenTab.Library, keepHomeBehindOtherTabs = true)

    @Test
    fun libraryInNativeTabHost() = measure("library-native-host", AppScreenTab.Library, keepHomeBehindOtherTabs = false)

    private fun measure(label: String, tab: AppScreenTab, keepHomeBehindOtherTabs: Boolean) {
        HomeActivityProbe.seedHero()
        val subscribersBefore = HomeActivityProbe.homeRepositorySubscribers()
        var applies = 0
        val observer = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            HomeActivityProbe.show(compose) { HomeActivityProbe.Host(tab, keepHomeBehindOtherTabs) }
            compose.mainClock.autoAdvance = false
            // Let first-composition work settle before measuring.
            compose.mainClock.advanceTimeBy(2_000L)
            val subscribers = HomeActivityProbe.homeRepositorySubscribers() - subscribersBefore
            val nodes = HomeActivityProbe.semanticsNodes(compose)
            val heroComposed = HomeActivityProbe.heroTitles(compose).isNotEmpty()
            applies = 0
            val heroChanges = HomeActivityProbe.heroChanges(compose, seconds = 60)
            val line = "%-22s homeSubscribers=%3d semanticsNodes=%4d heroComposed=%-5s heroChanges/60s=%3d snapshotApplies/60s=%5d"
                .format(label, subscribers, nodes, heroComposed, heroChanges, applies)
            println("PERF-PHASE1 $line")
            File("build/perf-phase1").apply { mkdirs() }.resolve("hidden-home.txt").appendText(line + "\n")
        } finally {
            observer.dispose()
        }
    }
}
