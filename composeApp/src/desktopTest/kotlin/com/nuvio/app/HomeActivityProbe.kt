package com.nuvio.app

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.home.MetaPreview
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared by [HiddenHomeActivityHarness] (measurement) and [AppTabHostHomeActivityTest] (assertions). */
internal object HomeActivityProbe {
    private val heroItems = List(8) { MetaPreview(id = "hero-$it", type = "movie", name = "Hero $it") }

    /** Eight hero titles in Home's repository, so a composed Home has a hero that pages. */
    fun seedHero() {
        val field = HomeRepository::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableStateFlow<HomeUiState>).value = HomeUiState(heroItems = heroItems)
    }

    /** Composes the real [AppTabHost] and waits, up to 15 s, for Home's hero if Home is composed at all. */
    fun show(compose: ComposeContentTestRule, content: @Composable () -> Unit) {
        seedHero()
        compose.setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) { content() }
        }
        compose.waitForIdle()
        // The first composition in a JVM initialises repositories that reset Home's state.
        seedHero()
        runCatching { compose.waitUntil(timeoutMillis = 15_000L) { heroTitles(compose).isNotEmpty() } }
    }

    @Composable
    fun Host(tab: AppScreenTab, keepHomeBehindOtherTabs: Boolean = true) {
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
        AppTabHost(
            selectedTab = tab,
            requests = requests,
            state = AppTabState(searchListState = searchListState, tabsRouteActiveState = tabsRouteActive),
            actions = AppTabActions(),
            keepHomeBehindOtherTabs = keepHomeBehindOtherTabs,
        )
    }

    /** Counts hero title changes over [seconds] of virtual time, sampled once a second. */
    fun heroChanges(compose: ComposeContentTestRule, seconds: Int): Int {
        var changes = 0
        var previous = heroTitles(compose)
        repeat(seconds) {
            compose.mainClock.advanceTimeBy(1_000L)
            val now = heroTitles(compose)
            if (now != previous) changes++
            previous = now
        }
        return changes
    }

    fun heroTitles(compose: ComposeContentTestRule): Set<String> {
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

    fun semanticsNodes(compose: ComposeContentTestRule): Int {
        fun count(node: SemanticsNode): Int = 1 + node.children.sumOf(::count)
        return count(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    }

    /** Live collectors on every repository `HomeScreen` collects with `collectAsStateWithLifecycle`. */
    fun homeRepositorySubscribers(): Int = HomeCollectedRepositories.sumOf { name ->
        Class.forName(name).declaredFields.filter { Modifier.isStatic(it.modifiers) }.sumOf { field ->
            field.isAccessible = true
            when (val value = runCatching { field.get(null) }.getOrNull()) {
                is MutableStateFlow<*> -> value.subscriptionCount.value
                is MutableSharedFlow<*> -> value.subscriptionCount.value
                else -> 0
            }
        }
    }

    private val HomeCollectedRepositories = listOf(
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
