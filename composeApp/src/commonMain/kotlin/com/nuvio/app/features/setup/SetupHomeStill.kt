package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioBlockPointerEvents
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.ThemeSettingsRepository
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_downloads
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.setup_still_catalog_row
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource

/**
 * A still of the home screen, drawn with the app's **real** composables.
 *
 * ## ⚠ This is the one file under `features/setup/` that is NOT byte-identical across the
 * ## repositories, and it must never be `cp`'d
 *
 * **This is the `NuvioZDesktop` copy.** Its `HomeContinueWatchingSection` takes a **required**
 * `dataSourceKey: WatchProgressSource` in third position that `nuvio-z`'s does not, and imports it from
 * `features.tracking` rather than `features.trakt`. Its `HomeHeroSection` also inserts an
 * optional `sectionPadding` mid-list, and `NuvioShelfSection` reorders `rowModifier`. **Named
 * arguments at every call below** are what stops a positional argument binding to the wrong slot
 * when this is ported. Port by hand; the two copies differ by three hunks.
 *
 * ## Why the real composables here, when every step specimen deliberately avoids them
 *
 * Revision 2 rendered a whole fake home and details screen from the shipped composables and was
 * pulled for three reasons - none of which apply to this screen:
 *
 * 1. Those composables read their settings repositories *internally*, so a choice the user made
 *    snapped instead of tweening. **The Welcome step has no controls.** Nothing changes while it
 *    is on screen, so there is nothing to tween.
 * 2. Most of a full screen had nothing to do with the one control being changed. **The Welcome
 *    step is not about a control** - it is answering "what is this?", and the whole screen is
 *    the answer.
 * 3. It could not be kept byte-identical. Still true, and accepted: fidelity is the entire point
 *    of this screen, and the divergence is quarantined to this one file.
 *
 * `SetupSpecimen.kt` keeps drawing from primitives for steps 2-8 and stays identical in both
 * repositories. That split is deliberate - do not merge them.
 *
 * ## It is a screenshot, and that is the whole design rule
 *
 * Everything is laid out at the app's real metrics inside the app's own scroll container, and the
 * wizard's sheet simply covers the bottom of it. **Nothing is ever resized, repositioned or
 * padded to fit the space the sheet leaves.** Revision 7 did all three - a hero shrunk to the
 * visible band, a nav bar lifted so it would clear the panel, a column padded to stop above it -
 * and the result read as messy precisely because none of it was where the app puts it.
 *
 * It is not interactive and it fetches nothing. ⚠ **With no network Coil draws nothing and the
 * hero is flat black**, because `HomeHeroSection`'s backdrop image carries no placeholder or
 * error painter. That is not a defect here - it is exactly what the real home screen looks like
 * with no network, and making the wizard nicer than the app is the failure this file exists to
 * avoid. It is still worth seeing in aeroplane mode.
 */
@Composable
fun SetupHomeStill(modifier: Modifier = Modifier) {
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    val catalogRowTitle = stringResource(Res.string.setup_still_catalog_row)

    // ⚠ **Which chrome the app actually wears on this platform.** This file's whole rule is that
    // it is a screenshot of the real home screen (see the header) - and on desktop it was not
    // one, because it drew a bottom tab bar. The desktop app has no bottom tab bar: `App.kt`
    // picks a left hover sidebar or a floating top bar, never that. A first-run screen showing
    // navigation the app does not have is the exact failure the "it is a screenshot" rule exists
    // to prevent, and it shipped because this file was mirrored from the mobile repository.
    val desktopNavigationLayout by remember {
        ThemeSettingsRepository.desktopNavigationLayout
    }.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // ⚠ A real `LazyColumn` is draggable. A screenshot that scrolls out from under your
            // thumb while you read the panel is a new kind of mess, so the whole still is inert.
            // `Initial` pass, so the list never sees the gesture at all.
            .nuvioBlockPointerEvents(),
    ) {
        val density = LocalDensity.current

        // ⚠ **All three conditions, and the width one is not optional.** This mirrors
        // `useDesktopSidebar` in `App.kt`, which is `isDesktop && isTabletLayout && ... && layout
        // == Sidebar` with `isTabletLayout = maxWidth >= 768.dp`. Dropping the width test drew the
        // rail on a 420 dp window - where the app itself falls back to the bottom bar - which the
        // render harness caught immediately, because `isDesktop` is true for every scene it draws.
        val wideEnoughForDesktopChrome = maxWidth >= DesktopChromeMinWidth
        val showSidebarRail = isDesktop &&
            wideEnoughForDesktopChrome &&
            desktopNavigationLayout == DesktopNavigationLayout.Sidebar
        // Under TopBar, or on a tablet-width window, the app wears a floating top bar. That bar
        // is a live, scroll-reactive overlay, so this draws no chrome at all rather than faking
        // it: an absent piece of chrome is a smaller lie than a still copy of a moving one.
        val showBottomBar = !wideEnoughForDesktopChrome
        val railWidth = if (showSidebarRail) SetupStillRailWidth else 0.dp

        // ⚠ **The full window, which is what `HomeScreen` passes.** Revision 7 passed the height
        // visible above the panel and capped it again with the Continue Watching reserve, which
        // made the hero a size the app never draws - the still stopped being a screenshot and
        // became a layout fitted to a hole. The sheet crops this; nothing reflows for it.
        val heroLayout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = maxHeight.value,
        )

        // How far down the screenshot is taken. Derived from the hero the section will actually
        // draw rather than guessed, so it holds on every window: scroll until only the hero's
        // content block - logo, metadata line, button, dots - is left above the fold, and
        // Continue Watching follows it.
        val scrollPx = with(density) {
            (heroLayout.heroHeight - HeroTailKept).coerceAtLeast(0.dp).roundToPx()
        }

        // ⚠ **Seeded state, not `Modifier.offset`.** `HomeHeroSection` reads
        // `listState.firstVisibleItemScrollOffset` for its parallax and background scale, so an
        // offset on a plain Column leaves the backdrop sitting where it would be *unscrolled* -
        // which was part of why revision 7 read as not-the-app. Giving the list the position and
        // handing the section the same state gets the app's own behaviour for free.
        val listState = remember(scrollPx) { LazyListState(0, scrollPx) }

        // Both measured off the window width, exactly as `HomeScreen` measures them, and both
        // passed down. ⚠ `HomeContinueWatchingSection` only honours `sectionPadding` when
        // `layout` comes with it - pass one without the other and it silently drops into its
        // own `BoxWithConstraints` and re-derives both, so the argument reads as load-bearing
        // while doing nothing.
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)

        NuvioScreen(
            // Exactly how `HomeScreen` calls it when the hero is on: full-bleed, and the hero
            // starts at y = 0 under the status bar.
            horizontalPadding = 0.dp,
            topPadding = 0.dp,
            listState = listState,
            // ⚠ The rail insets the content rather than overlapping it, which is what `App.kt`
            // does to the real screen (`padding(start = DesktopSidebarCollapsedWidth)`). This is
            // the one place the still is allowed to move something for the chrome, because the
            // app moves it too - it is not the "fitted to a hole" reflow revision 7 was pulled
            // for.
            modifier = Modifier.padding(start = railWidth),
        ) {
            item {
                // ⚠ **One item, not the whole row.** The hero is a pager that rotates, and a
                // still that rotates is not a still - it also meant the screenshot landed on
                // whichever title's backdrop happened to be missing from the artwork host, which
                // is what produced a hero that was simply black. The cost is the pager dots,
                // which `HomeHeroSection` only draws for more than one item.
                HomeHeroSection(
                    items = SetupSampleTitle.rowItems.take(1),
                    viewportHeight = maxHeight,
                    listState = listState,
                )
            }
            item {
                HomeContinueWatchingSection(
                    items = SetupSampleTitle.continueWatching,
                    style = continueWatching.style,
                    useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                    blurNextUp = continueWatching.blurNextUp,
                    // ⚠ Desktop-only, and required. It scopes the section's disintegration
                    // animation state, so any stable value works for a still; `NUVIO_SYNC` is
                    // chosen because it names no external tracking provider.
                    dataSourceKey = WatchProgressSource.NUVIO_SYNC,
                    sectionPadding = sectionPadding,
                    layout = continueWatchingLayout,
                    // ⚠ 12 dp per item on top of the container's 12 dp `listGap` - the real home
                    // screen's rows are 24 dp apart, and revision 7's 16 dp was neither.
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                    // No `title`: the section falls back to the same string resource the real
                    // home screen shows it under, so this cannot drift from it.
                )
            }
            item {
                HomeCatalogRowSection(
                    section = SetupSampleTitle.catalogSection(catalogRowTitle),
                    sectionPadding = sectionPadding,
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                )
            }
            item {
                // A second row, so what the panel crops is a screen that carries on rather than
                // the end of a short list. Its own key - `NuvioShelfSection` dedupes by key.
                HomeCatalogRowSection(
                    section = SetupSampleTitle.secondCatalogSection(catalogRowTitle),
                    sectionPadding = sectionPadding,
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                )
            }
        }

        if (showSidebarRail) {
            // ⚠ Pinned to the window's leading edge and full height, which is where `App.kt`
            // draws the real one. It is the collapsed state on purpose: the real sidebar only
            // expands on hover, and this still is inert.
            SetupStillSidebarRail(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(SetupStillRailWidth),
            )
        } else if (showBottomBar) {
            // ⚠ Pinned to the window's bottom edge, which is where `App.kt` draws it - an overlay
            // over the rows, not a thing laid out below them. Revision 7 padded it up so it would
            // clear the panel and it ended up floating across the middle of the Continue Watching
            // row. The sheet covers it now, and that is correct: a screenshot of a scrolled home
            // screen with a sheet over it does not show the tab bar either.
            SetupStillNavigationBar(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Width of the collapsed desktop sidebar.
 *
 * ⚠ Mirrors `DesktopSidebarCollapsedWidth` in `App.kt`, which is a private file-level val and
 * cannot be imported. Kept local with this note, the same way [HomeRowBottomPadding] mirrors the
 * real per-row padding. If the sidebar is ever re-measured, this has to follow it or the still
 * stops lining up with the app it is a picture of.
 *
 * `internal` because `SetupWelcomeSurface` pads its panel clear of the rail by this much.
 */
internal val SetupStillRailWidth = 84.dp

/**
 * ⚠ Mirrors `isTabletLayout` in `App.kt` - the width at which the app stops using a bottom tab
 * bar. Below it the desktop app wears the same bottom bar a phone does, so the still must too.
 */
private val DesktopChromeMinWidth = 768.dp

/** ⚠ Mirrors `DesktopSidebarIconSlotSize` in `App.kt`, for the same reason as the width. */
private val SetupStillRailIconSlotSize = 42.dp

/**
 * The collapsed sidebar, drawn for looks only.
 *
 * A local look-alike rather than the real thing, exactly as [SetupStillNavigationBar] is:
 * `DesktopHoverSidebar` is private to `App.kt` and needs `ProfileRepository`, an `AppScreenTab`
 * selection and four callbacks, none of which a still has or should have.
 *
 * ⚠ Home is drawn selected because that is the tab a first launch lands on. The real rail's
 * bottom item is a `ProfileSwitcherTab` reading `ProfileRepository`; it is omitted rather than
 * faked, and four items still read as the app's rail.
 */
@Composable
private fun SetupStillSidebarRail(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier.background(tokens.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SetupStillRailItem(icon = Icons.Filled.Home, selected = true)
            SetupStillRailItem(drawable = Res.drawable.sidebar_search)
            SetupStillRailItem(drawable = Res.drawable.sidebar_library)
            SetupStillRailItem(icon = Icons.Filled.Download)
        }
    }
}

/**
 * One rail slot.
 *
 * Selected uses `overlayHover` rather than the accent fill: the real rail marks the active tab
 * with a tinted slot, and an accent block here would pull the eye off the wizard's own panel -
 * which is the thing the user is meant to be reading.
 */
@Composable
private fun SetupStillRailItem(
    icon: ImageVector? = null,
    drawable: DrawableResource? = null,
    selected: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(SetupStillRailIconSlotSize)
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(if (selected) tokens.colors.overlayHover else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted
        val iconModifier = Modifier.size(NuvioTokens.Icon.lg)
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = iconModifier,
            )
            drawable != null -> Icon(
                painter = painterResource(drawable),
                contentDescription = null,
                tint = tint,
                modifier = iconModifier,
            )
        }
    }
}

/**
 * How much of the hero is left above the fold.
 *
 * The content block - logo, metadata line, action button, dots - measures about 240 dp on a phone
 * (`HomeHeroSection`'s `bottomFadeHeight` is 220 dp and the block slightly overruns it), so this
 * frames on the part of the hero that carries the title's identity and hands the rest of the band
 * to Continue Watching.
 */
private val HeroTailKept = 250.dp

/**
 * ⚠ Mirrors the per-row bottom padding `HomeScreen` applies to every section - literal `12.dp` on
 * the catalog rows and `HomeContinueWatchingSectionBottomPadding` (also `12.dp`) on Continue
 * Watching. Kept local rather than imported so the desktop port of this file needs no extra
 * import, and because the two real values are separate constants that happen to agree.
 */
private val HomeRowBottomPadding = 12.dp

/**
 * The nav bar, drawn for looks only.
 *
 * No `hazeState`: the bar would then blur the still behind it, which is correct in the app but
 * competes with the wizard's own frosted panel. `NuvioNavigationBar` already deepens its own tint
 * to `0.82f` when no haze state is supplied, so this is the shape the bar is designed to take
 * without one rather than a special case.
 *
 * ⚠ Home is drawn selected because that is the tab a first launch lands on. The Settings tab in
 * the real bar is a `ProfileSwitcherTab`, which reads `ProfileRepository`; it is left out rather
 * than faked, and four items still read as the app's nav bar.
 *
 * `NavItem` is a member of `NuvioNavigationBar`'s content receiver, so it needs no import.
 */
@Composable
private fun SetupStillNavigationBar(modifier: Modifier = Modifier) {
    NuvioNavigationBar(modifier = modifier.fillMaxWidth()) {
        NavItem(
            selected = true,
            onClick = {},
            icon = Icons.Filled.Home,
            contentDescription = stringResource(Res.string.compose_nav_home),
            label = stringResource(Res.string.compose_nav_home),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Res.drawable.sidebar_search,
            contentDescription = stringResource(Res.string.compose_nav_search),
            label = stringResource(Res.string.compose_nav_search),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Res.drawable.sidebar_library,
            contentDescription = stringResource(Res.string.compose_nav_library),
            label = stringResource(Res.string.compose_nav_library),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Icons.Filled.Download,
            contentDescription = stringResource(Res.string.compose_nav_downloads),
            label = stringResource(Res.string.compose_nav_downloads),
        )
    }
}
