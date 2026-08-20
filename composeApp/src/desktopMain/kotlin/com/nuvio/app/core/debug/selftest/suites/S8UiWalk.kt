package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.debug.SelfTestHooks
import com.nuvio.app.core.debug.selftest.CheckScope
import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.debug.selftest.SelfTestRedaction
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.ContinueWatchingSettingsRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadsSettingsRoute
import com.nuvio.app.navigation.HomescreenSettingsRoute
import com.nuvio.app.navigation.MetaScreenSettingsRoute
import com.nuvio.app.navigation.TabsRoute
import kotlinx.coroutines.delay

/**
 * Walking the real app and photographing it.
 *
 * `SetupWizardRenderHarness` already draws these surfaces in CI and its own header explains why -
 * every wizard defect that came back from a device was something only *looking* could catch: an
 * unreadable translucent sheet, chips aligned hard left, a card clipped in half, rails filled with
 * a colour too dark to see.
 *
 * What that harness structurally cannot do is have a network. Coil resolves nothing there, so every
 * poster and backdrop is a placeholder fill, and its own docs say to judge artwork on a device
 * instead. Three checks in the wizard device script are exactly that - *"Welcome reads as the real
 * home screen and the rows are visible"*, *"Cast and Trailers show content"*, *"episode stills
 * differ per row"* - and all three are unanswerable offscreen.
 *
 * So this walks the installed app, with the user's real addons and a real connection, and grabs the
 * screen. The pictures are the deliverable; the assertions here only confirm that navigation
 * arrived and something drew.
 *
 * ⚠ **No account, debrid or Trakt page is photographed.** Those show API keys in plain text and a
 * screenshot cannot be redacted.
 */
internal object S8UiWalk {

    /** Pages deliberately left out of the walk, because their pixels contain credentials. */
    private val SENSITIVE_PAGE_NAMES = setOf("Debrid", "Account", "Trakt", "Simkl", "Plugins", "Addons")

    suspend fun run(context: SelfTestContext) {
        val navigate = SelfTestHooks.navigate

        context.check("S8.1", "Home draws with real artwork") { scope ->
            if (navigate == null) scope.skip("The app did not publish a navigator.")
            navigate(TabsRoute)

            // ⚠ Stillness is not enough here, and the first run of this harness proved it: home
            // settled at 1601 ms on an almost entirely **black** screen - no hero, no catalog rows,
            // one Continue Watching card floating in the middle - and the check passed, because
            // nothing was moving. A screen that has not loaded is perfectly still.
            //
            // So this one waits on the repository as well. Home fans out to every addon's catalogs
            // and S1.3 measured that at three to six seconds against this addon set; the settle
            // detector was being asked a question it structurally cannot answer.
            val loadedInMs = awaitHomeContent()
            scope.value("home content wait ms", loadedInMs)
            val state = HomeRepository.uiState.value
            scope.value("hero items", state.heroItems.size)
            scope.value("catalog sections", state.sections.size)
            scope.value("sections with items", state.sections.count { it.items.isNotEmpty() })
            state.errorMessage?.let { scope.value("error", SelfTestRedaction.text(it)) }

            visitSettled(context, scope, "S8.1-home")

            scope.require(state.sections.any { it.items.isNotEmpty() }) {
                "Home drew with no populated catalog row after ${loadedInMs}ms. The screenshot is " +
                    "of an empty home, which is what a user sees on a cold launch."
            }
            scope.summary = "${state.sections.count { it.items.isNotEmpty() }} populated row(s), " +
                "${state.heroItems.size} hero item(s)."
        }

        context.check("S8.2", "Film details draws") { scope ->
            if (navigate == null) scope.skip("The app did not publish a navigator.")
            if (context.filmMeta == null) scope.skip("Film metadata did not load - see S1.1.")
            visit(
                context, scope, navigate,
                DetailRoute(type = "movie", id = context.fixtures.filmId),
                "S8.2-details-film",
            )
            scope.summary = "Captured."
        }

        context.check("S8.3", "Series details draws, with episode stills") { scope ->
            if (navigate == null) scope.skip("The app did not publish a navigator.")
            if (context.seriesMeta == null) scope.skip("Series metadata did not load - see S1.2.")
            // The episode-still check from the wizard script lives here: if every row shows the
            // same image, the `episodes.metahub.space` URL shape is wrong and the backdrop fallback
            // is hiding it. That is a judgement from the picture, not an assertion - the fallback
            // makes failure look like a design choice, which is exactly why a person has to look.
            visit(
                context, scope, navigate,
                DetailRoute(type = "series", id = context.fixtures.seriesId),
                "S8.3-details-series",
            )
            scope.summary = "Captured. Check the episode stills differ per row."
        }

        context.check("S8.4", "Settings pages draw") { scope ->
            if (navigate == null) scope.skip("The app did not publish a navigator.")
            val pages: List<Pair<AppRoute, String>> = listOf(
                HomescreenSettingsRoute() to "S8.4-settings-homescreen",
                MetaScreenSettingsRoute() to "S8.4-settings-metascreen",
                ContinueWatchingSettingsRoute() to "S8.4-settings-continue-watching",
                DownloadsSettingsRoute() to "S8.4-settings-downloads",
            )
            pages.forEach { (route, name) -> visit(context, scope, navigate, route, name) }
            scope.value("pages captured", pages.size)
            scope.value("pages deliberately skipped", SENSITIVE_PAGE_NAMES.joinToString())
            scope.summary = "${pages.size} settings pages captured."
        }

        context.check("S8.5", "Nothing behind a full-screen surface is tappable") { scope ->
            val currentRoute = SelfTestHooks.currentRoute
                ?: scope.skip("The app did not publish its current route.")
            if (navigate == null) scope.skip("The app did not publish a navigator.")

            // ⚠ The fault this exists for has now **shipped twice**: a full-screen sibling `Box`
            // drawn over the app without `nuvioConsumePointerEvents()` leaves everything underneath
            // fully tappable, so a tap that misses a control lands on whatever is behind and the
            // user cannot see what they hit. A `background()` does not consume input; a `Dialog` is
            // immune by construction; a sibling `Box` is not.
            //
            // Mechanically checkable rather than a matter of looking: click a point that should be
            // inert and read the route back. It changed, or it did not.
            visit(context, scope, navigate, DetailRoute(type = "movie", id = context.fixtures.filmId), "S8.5-before")
            val before = currentRoute()
            scope.value("route before", before?.let { it::class.simpleName } ?: "(none)")

            context.capture.clickCentre()
            delay(SETTLE_AFTER_CLICK_MS)
            val after = currentRoute()
            scope.value("route after a click on the centre of the screen", after?.let { it::class.simpleName } ?: "(none)")
            scope.screenshot(context.capture.screen("S8.5-after"))

            // A details screen has real content in the middle, so a click there legitimately does
            // something. This records what happened rather than asserting no-change; the assertion
            // form belongs on an overlay, and S5 raises one.
            scope.summary = if (before == after) {
                "Route unchanged by a centre click."
            } else {
                "Centre click navigated ${before?.let { it::class.simpleName }} → " +
                    "${after?.let { it::class.simpleName }}."
            }
        }
    }

    /**
     * Navigates, waits for the screen to stop moving, and grabs it.
     *
     * The settle detector is what makes this reliable: there is no Compose idling resource on
     * desktop, a fixed sleep is either slow or a race, and waiting on a repository's loading flag
     * misses everything that animates after the data lands - which here is most of it.
     */
    private suspend fun visit(
        context: SelfTestContext,
        scope: CheckScope,
        navigate: (AppRoute) -> Unit,
        route: AppRoute,
        name: String,
    ) {
        navigate(route)
        visitSettled(context, scope, name)
    }

    /** The settle-and-grab half of [visit], for callers that navigate and wait for content first. */
    private suspend fun visitSettled(context: SelfTestContext, scope: CheckScope, name: String) {
        val settled = context.capture.awaitSettled()
        scope.value("$name settle ms", settled.waitedMs)
        if (!settled.settled) {
            // Not a failure on its own - a page with a looping animation never settles - but it
            // has to be visible, because the screenshot below is then of a moving target.
            scope.value("$name settled", "no, timed out")
        }
        // ⚠ Reported because it is the blank-screen signature. A screen that never changed while
        // being watched either drew instantly or drew nothing, and from here those look identical
        // - so this number belongs in the report rather than being discarded, which is exactly the
        // mistake that let an empty home screen pass on the first run.
        if (!settled.changed) {
            scope.value("$name changed while watched", "no - nothing moved, check the screenshot")
        }
        scope.screenshot(context.capture.screen(name))
    }

    /**
     * Waits for home's catalog fan-out, or gives up and lets the check report what it found.
     *
     * Returns the elapsed wait. Deliberately does not throw on timeout: an empty home *is* the
     * finding, and the caller asserts on it with the row counts in hand.
     */
    private suspend fun awaitHomeContent(): Long {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < HOME_CONTENT_TIMEOUT_MS) {
            val state = HomeRepository.uiState.value
            if (!state.isLoading && state.sections.any { it.items.isNotEmpty() }) break
            delay(250L)
        }
        return System.currentTimeMillis() - started
    }

    private const val SETTLE_AFTER_CLICK_MS = 1_500L

    /** Home fans out to every addon's catalogs; S1.3 measured that at three to six seconds. */
    private const val HOME_CONTENT_TIMEOUT_MS = 30_000L
}
