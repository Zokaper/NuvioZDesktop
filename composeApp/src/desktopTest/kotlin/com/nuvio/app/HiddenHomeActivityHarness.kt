package com.nuvio.app

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.home.MetaPreview
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import kotlin.test.Test

/**
 * Performance Phase 1 measurement, not a regression test: what a Home the viewer cannot see still does.
 *
 * Composes the real [AppTabHost] with eight hero titles in [HomeRepository] and records, over 60 s of
 * virtual time with the clock driven by hand:
 * - live collectors on the repositories `HomeScreen` collects (`subscriptionCount` of each one's
 *   `MutableStateFlow`/`MutableSharedFlow` fields, over the count before anything was composed;
 *   the selected tab's own screen collects some of them too),
 * - how many semantics nodes the host composes,
 * - how often the hero changed title (sampled once a second),
 * - global snapshot applies (state writes that reach composition).
 *
 * Three hosts: Home selected (control); Library selected in the single-host shell (desktop, Android,
 * iOS below 16); Library selected in an iOS 16+ native-tab host, which never shows Home in place.
 * Results go to `composeApp/build/perf-phase1/hidden-home.txt`.
 */
class HiddenHomeActivityHarness {
    @get:Rule
    val compose = createComposeRule()

    private val heroItems = List(8) { MetaPreview(id = "hero-$it", type = "movie", name = "Hero $it") }

    @Test
    fun homeSelected() = measure("home-selected", AppScreenTab.Home, nativeTabHost = false)

    @Test
    fun libraryInSingleHostShell() = measure("library-single-host", AppScreenTab.Library, nativeTabHost = false)

    @Test
    fun libraryInNativeTabHost() = measure("library-native-host", AppScreenTab.Library, nativeTabHost = true)

    private fun measure(label: String, tab: AppScreenTab, nativeTabHost: Boolean) {
        seedHero()
        val subscribersBefore = homeRepositorySubscribers()
        var applies = 0
        val observer = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            compose.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                    Host(tab, nativeTabHost)
                }
            }
            compose.waitForIdle()
            // The first composition in this JVM initialises repositories that reset Home's state.
            seedHero()
            // The repositories behind Home's hero slot load on background threads; whichever case runs
            // first in this JVM would otherwise measure a Home still showing its skeleton. A host that
            // does not compose Home at all simply times out here.
            runCatching { compose.waitUntil(timeoutMillis = 15_000L) { visibleHeroTitles().isNotEmpty() } }
            compose.mainClock.autoAdvance = false
            // Let first-composition work settle before measuring.
            compose.mainClock.advanceTimeBy(2_000L)
            val subscribers = homeRepositorySubscribers() - subscribersBefore
            val nodes = countNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            applies = 0
            var heroChanges = 0
            var previous = visibleHeroTitles()
            val heroSeen = previous.isNotEmpty()
            repeat(60) {
                compose.mainClock.advanceTimeBy(1_000L)
                val now = visibleHeroTitles()
                if (now != previous) heroChanges++
                previous = now
            }
            val line = "%-22s homeSubscribers=%3d semanticsNodes=%4d heroComposed=%-5s heroChanges/60s=%3d snapshotApplies/60s=%5d"
                .format(label, subscribers, nodes, heroSeen, heroChanges, applies)
            println("PERF-PHASE1 $line")
            File("build/perf-phase1").apply { mkdirs() }.resolve("hidden-home.txt").appendText(line + "\n")
        } finally {
            observer.dispose()
        }
    }

    @Composable
    private fun Host(tab: AppScreenTab, nativeTabHost: Boolean) {
        val searchListState = rememberLazyListState()
        val tabsRouteActive = remember { mutableStateOf(true) }
        val requests = remember {
            AppTabRequests(
                homeScrollToTopRequests = MutableSharedFlow(),
                searchScrollToTopRequests = MutableSharedFlow(),
                libraryScrollToTopRequests = MutableSharedFlow(),
                downloadsScrollToTopRequests = MutableSharedFlow(),
                socialScrollToTopRequests = MutableSharedFlow(),
                settingsRootActionRequests = MutableSharedFlow(),
            )
        }
        // Baseline: AppTabHost has no notion of a native-tab host, so both Library cases are the same call.
        @Suppress("UNUSED_VARIABLE") val native = nativeTabHost
        AppTabHost(
            selectedTab = tab,
            requests = requests,
            state = AppTabState(searchListState = searchListState, tabsRouteActiveState = tabsRouteActive),
            actions = AppTabActions(),
        )
    }

    private fun seedHero() {
        val field = HomeRepository::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableStateFlow<HomeUiState>).value = HomeUiState(heroItems = heroItems)
    }

    private fun visibleHeroTitles(): Set<String> {
        val texts = mutableSetOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                if (text.text.startsWith("Hero ")) texts += text.text
            }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return texts
    }

    private fun countNodes(node: SemanticsNode): Int = 1 + node.children.sumOf(::countNodes)

    private fun homeRepositorySubscribers(): Int = HomeCollectedRepositories.sumOf { name ->
        val cls = Class.forName(name)
        cls.declaredFields.filter { Modifier.isStatic(it.modifiers) }.sumOf { field ->
            field.isAccessible = true
            when (val value = runCatching { field.get(null) }.getOrNull()) {
                is MutableStateFlow<*> -> value.subscriptionCount.value
                is MutableSharedFlow<*> -> value.subscriptionCount.value
                else -> 0
            }
        }
    }

    private companion object {
        /** Every repository `HomeScreen` collects with `collectAsStateWithLifecycle`. */
        val HomeCollectedRepositories = listOf(
            "com.nuvio.app.features.home.HomeRepository",
            "com.nuvio.app.features.home.HomeCatalogSettingsRepository",
            "com.nuvio.app.features.addons.AddonRepository",
            "com.nuvio.app.features.cloud.CloudLibraryRepository",
            "com.nuvio.app.features.collection.CollectionRepository",
            "com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository",
            "com.nuvio.app.core.network.NetworkStatusRepository",
            "com.nuvio.app.features.profiles.ProfileRepository",
            "com.nuvio.app.features.social.SocialRepository",
            "com.nuvio.app.features.tracking.TrackingSettingsRepository",
            "com.nuvio.app.features.watchprogress.WatchProgressRepository",
            "com.nuvio.app.features.watched.WatchedRepository",
        )
    }
}
