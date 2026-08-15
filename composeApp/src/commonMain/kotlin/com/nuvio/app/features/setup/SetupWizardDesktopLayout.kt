package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/**
 * The wizard's steps 2-8 on a desktop window.
 *
 * ## Why this exists at all
 *
 * Every file under `features/setup/` except `SetupHomeStill.kt` was byte-identical to the mobile
 * repository, this screen included, and it showed. The mobile layout stacks a specimen band over
 * a panel and caps the panel's text at 620 dp - which on the 1280x820 default window
 * (`Main.kt`) is a band across the top and a narrow column with roughly 660 dp of empty surface
 * split between the gutters. Nothing was broken; it was simply a phone layout in a desktop
 * window.
 *
 * The app already knows how to do this and the wizard was the last screen not doing it.
 * `AuthScreen` - the *other* full-screen gate - splits into `AuthLargeLayout` at 900 dp,
 * `SettingsScreen` splits at 768 dp into a fixed rail plus a detail pane, `StreamsScreen` splits
 * 0.4/0.6, and `MetaDetailsScreen` swaps in `DesktopDetailHero` at `isDesktop && width >= 1000`.
 * This is that same move: the specimen takes the width, the controls keep a readable measure.
 *
 * ## Pure with respect to its parameters, and that is load-bearing
 *
 * This composable reads **no repository** - every appearance value arrives as an argument, exactly
 * as [SetupSpecimenBand] does and for the same reason. `SetupWizardScreen` holds the current step
 * in `rememberSaveable` state, so before this file existed there was no way to render a single
 * wizard step off-screen: the harness could only ever draw Welcome and the bands in isolation.
 * Taking [step] as a parameter is what lets `SetupWizardRenderHarness` draw all seven remaining
 * steps. ⚠ If a value is ever read internally here instead of passed in, that coverage is gone.
 *
 * ## The seam is vertical now, and the band needed no change for it
 *
 * [SetupSpecimenBand] paints itself with a `background -> surface` vertical gradient so that the
 * bottom of the band lands on the panel's colour instead of meeting it at a hard rule. That still
 * works here: the specimen pane is the full height of the window and the control pane beside it is
 * `colors.surface`, so the gradient reads as the left pane settling into the panel colour rather
 * than as a horizontal seam. **That is why `SetupSpecimen.kt` is untouched** and stays
 * byte-identical with the mobile repository - `diff -q` is still a real check on it.
 */
@Composable
fun SetupWizardDesktopLayout(
    step: SetupStep,
    plan: SetupWizardPlan,
    specimen: SetupSpecimen,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    playbackMode: PlaybackMode,
    posterWidthDp: Int,
    posterCornerRadiusDp: Int,
    landscapeCards: Boolean,
    showCardTitles: Boolean,
    heroEnabled: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    backgroundMode: MetaScreenBackgroundMode,
    episodeCardStyle: MetaEpisodeCardStyle,
    blurUnwatchedEpisodes: Boolean,
    tabLayout: Boolean,
    nextUpLabel: String,
    topInset: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        val paneHeight = maxHeight
        // ⚠ **Capped and centred rather than stretched edge to edge, and this is the fix for
        // "everything looks broken on 4K".** The specimens are hand-drawn at roughly a phone's
        // width: the home banner is 400x150 (2.67:1) and the details hero 400x96 (4.17:1), both
        // `fillMaxWidth`. Given a 2140 dp pane they become 14:1 and 22:1 - the hero stops being a
        // hero and becomes a letterbox strip with a logo lost in the middle of it, which is
        // exactly what the 4K screenshots showed. The theme step's accent button went to 52:1.
        //
        // Capping the *block* rather than the specimen's content is what lets `SetupSpecimen.kt`
        // stay byte-identical with the mobile repository: the band still fills its own pane and
        // still paints its own gradient, the pane is simply the width the drawings were made for.
        val blockWidth = maxWidth.coerceAtMost(DesktopWizardBlockMaxWidth)
        val controlPaneWidth = desktopControlPaneWidth(blockWidth)

        Row(
            modifier = Modifier
                .width(blockWidth)
                .fillMaxHeight(),
        ) {
            // The specimen pane.
            //
            // ⚠ **The band gets `preferredHeight`, not the pane's height, and that is the whole
            // trick.** `SpecimenDetails` and `SpecimenHome` are `fillMaxSize()` internally and
            // top-anchor their content, so handing them an 820 dp pane does not centre them - it
            // stretches the mock and pushes its hero off the top edge. `preferredHeight` is the
            // height each specimen was actually budgeted against (`SetupSpecimen.kt`), so drawing
            // at exactly that height is what makes it render the way it was designed to; the pane
            // then centres the finished block. Capped against the pane so a short window clips
            // nothing.
            //
            // ⚠ **The two fillers are what make the pane look like one surface rather than a
            // band floating on one.** `SetupSpecimenBand` paints itself with a vertical gradient
            // running `background` (top) to `surface` (bottom). Centring it inside a
            // single-colour pane therefore puts a visible step at whichever edge does not match -
            // a hard line across the pane, which is what the first render of the playback-mode
            // step showed. Painting the space above it `background` and the space below it
            // `surface` continues the gradient's own endpoints exactly, so both edges vanish and
            // the pane reads as one continuous fade - the same thing a full-height band would
            // have drawn, but with the specimen still at the height it was designed for.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(tokens.colors.background),
                )
                SetupSpecimenBand(
                    specimen = specimen,
                    step = step,
                    playbackMode = playbackMode,
                    height = specimen.desktopHeight
                        .coerceAtMost((paneHeight - topInset).coerceAtLeast(0.dp)),
                    contentPaddingTop = 0.dp,
                    posterWidthDp = posterWidthDp,
                    posterCornerRadiusDp = posterCornerRadiusDp,
                    landscapeCards = landscapeCards,
                    showCardTitles = showCardTitles,
                    heroEnabled = heroEnabled,
                    continueWatchingStyle = continueWatchingStyle,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    backgroundMode = backgroundMode,
                    episodeCardStyle = episodeCardStyle,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    tabLayout = tabLayout,
                    nextUpLabel = nextUpLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(tokens.colors.surface),
                )
            }

            // The same hairline the stacked layout draws between band and panel, stood on its end.
            Box(
                modifier = Modifier
                    .width(tokens.borders.hairline)
                    .fillMaxHeight()
                    .background(tokens.colors.borderSubtle.copy(alpha = 0.6f)),
            )

            SetupDesktopControlPane(
                step = step,
                plan = plan,
                dismissible = dismissible,
                onDismiss = onDismiss,
                topInset = topInset,
                bottomInset = bottomInset,
                onBack = onBack,
                onAdvance = onAdvance,
                modifier = Modifier
                    .width(controlPaneWidth)
                    .fillMaxHeight(),
                body = body,
            )
        }
    }
}

/**
 * How tall to draw a specimen when there is a whole pane to put it in.
 *
 * ⚠ **Only [SetupSpecimen.Details] differs, and a blanket bonus would be wrong.** `preferredHeight`
 * is not "how much room this would like" - it is the height each specimen was *fitted* against,
 * and `SpecimenHome`, `SpecimenTheme` and `SetupDiagram` all draw exactly that much and top-anchor
 * whatever is left over. Handing them more does not centre them better, it strands them against
 * the top of a taller empty box.
 *
 * Details is the exception because its budget was deliberately set *below* its content: 400 dp is
 * roughly `windowHeight * 0.5f` on a phone, and `SetupSpecimen.kt` says in as many words that the
 * trailers rail is meant to be the part that clips there, because a page continuing below the fold
 * is what the real details screen does. That reasoning is about a screen edge. In a pane with 820
 * dp to spend the same clip lands in the middle of the window with empty space under it, where it
 * reads as a broken row rather than as a fold - so here it gets the height it actually needs.
 *
 * Kept in this file rather than raising the enum's own value, so `SetupSpecimen.kt` stays
 * byte-identical with the mobile repository and the phone layout keeps the fold it was tuned for.
 */
private val SetupSpecimen.desktopHeight: Dp
    get() = when (this) {
        SetupSpecimen.Details -> DesktopDetailsSpecimenHeight
        else -> preferredHeight
    }

/** Enough for the trailers rail to finish. Measured against the render harness, not guessed. */
private val DesktopDetailsSpecimenHeight = 500.dp

/**
 * How wide the two panes together are allowed to get.
 *
 * A **500 dp specimen pane plus a 560 dp control pane**. The specimen half is the number that
 * matters and it comes from the drawings themselves, not from taste:
 *
 * - The home banner is 400x150 as designed; at 500 it is 3.3:1 against a designed 2.67:1.
 * - The details hero is 400x96; at 500 it is 5.2:1 against a designed 4.17:1. It is the shortest
 *   full-bleed element in `SetupSpecimen.kt` and therefore the first thing to look wrong, which is
 *   what sets the ceiling here.
 * - ⚠ **The horizontal rails have to keep overflowing.** The narrowest the cards row can ever be
 *   is 754 dp (six poster cards at the `Dense` 112 dp setting) and the widest 1457 dp; Continue
 *   Watching's widest style is 580 dp and the episode row 580 dp. A 500 dp stage keeps every one
 *   of them running off the edge, which is the behaviour `SetupSpecimen.kt` calls for in as many
 *   words - "content deliberately overflows the right edge the way a real catalog row does". Widen
 *   this much past 560 and those rows start to *fit*, which silently deletes that intent.
 */
private val DesktopWizardBlockMaxWidth = 1060.dp

/**
 * The controls, in a column of their own.
 *
 * Proportional with hard clamps, following `detailTabletContentMaxWidth` in `MetaDetailsScreen`,
 * so the split degrades sensibly on a window narrower than the block cap instead of the specimen
 * collapsing to nothing.
 */
internal fun desktopControlPaneWidth(blockWidth: Dp): Dp =
    (blockWidth * DesktopControlPaneFraction)
        .coerceIn(DesktopControlPaneMinWidth, DesktopControlPaneMaxWidth)

/** 560 / 1060 - the control pane's share of the block at full width. */
private const val DesktopControlPaneFraction = 0.528f
private val DesktopControlPaneMinWidth = 420.dp
private val DesktopControlPaneMaxWidth = 560.dp

/**
 * Horizontal padding inside the control pane.
 *
 * 32 dp is what `MetaDetailsScreen` uses for `contentHorizontalPadding` on a wide window, not the
 * 22 dp the stacked mobile panel uses. The pane is furniture at this size and needs the gutter.
 */
private val DesktopControlPanePadding = 32.dp

@Composable
private fun SetupDesktopControlPane(
    step: SetupStep,
    plan: SetupWizardPlan,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scrollState = rememberScrollState()

    Box(modifier = modifier.background(tokens.colors.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = DesktopControlPanePadding,
                    end = DesktopControlPanePadding,
                    top = 32.dp + topInset,
                    bottom = 28.dp + bottomInset,
                ),
        ) {
            SetupPanelHeader(
                step = step,
                plan = plan,
                dismissible = dismissible,
                onDismiss = onDismiss,
            )
            Spacer(modifier = Modifier.height(24.dp))
            // Scrolls for a long step (Cards and Details both carry four controls) or a large
            // font scale. The desktop scrollbar is drawn rather than left implicit, because a
            // pane that scrolls with no visible affordance is a desktop bug in its own right -
            // `NuvioScreen` wires it exactly this way.
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    body()
                }
                NuvioDesktopVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            SetupDesktopFooter(
                step = step,
                plan = plan,
                onBack = onBack,
                onAdvance = onAdvance,
            )
        }
    }
}

/**
 * Back and Next.
 *
 * ⚠ Deliberately **not** `fillMaxWidth()` buttons. The stacked mobile layout stretches its
 * primary actions because a thumb needs the target; a 500 dp wide "Next" under a mouse pointer
 * just looks like a mistake. Same two actions, same order, same strings.
 */
@Composable
private fun SetupDesktopFooter(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupBackButton(step = step, plan = plan, onBack = onBack)
        Spacer(modifier = Modifier.weight(1f))
        SetupAdvanceButton(step = step, plan = plan, onAdvance = onAdvance)
    }
}
