package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the Social cards off-screen and writes PNGs.
 *
 * The sibling of `SetupWizardRenderHarness`, committed for the same reason and with the same
 * warning attached: **the defect this exists to catch is a hierarchy one, and only looking catches
 * it.**
 *
 * ## ⚠ This harness passed a build that physical QA then failed, and why
 *
 * The first version of this file drew the feed as its own `LazyColumn(Modifier.fillMaxWidth())`
 * with no friends rail beside it and no width cap on the list. The screen does neither: the rail
 * takes 360dp and the list was capped at 600dp while its column count was chosen from the full
 * remaining width. So the harness rendered three comfortable cards, the app rendered three 177dp
 * ones, and `PAUSED` came out of a real window one character per line. **A harness that composes
 * its subject under constraints production never applies is worse than no harness** - the same
 * lesson this file already carried about rebuilding Home's shelves by hand, learnt again one layer
 * up. Both scenes now go through the production entry points *and* the production constraint
 * stack, and [socialFeedMetrics] is the single number both the screen and this file read.
 *
 * The second physical failure was invisible here for a different reason: Home's card titles
 * rendered black on a dark card because `Surface` fell through to the host's `LocalContentColor`.
 * A PNG shows that, but only if somebody notices dark text on a dark card, and nobody did.
 * [homeCardsDoNotInheritTheHostContentColour] turns it into an assertion.
 *
 * ## What it proves and does not prove
 *
 * It **asserts every scene composes and renders without throwing**, and that the two colour
 * variants of Home are identical. What it still cannot do is judge the layout - that is what the
 * PNGs are for. Run it and look:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialRenderHarness"
 * # then read the PNGs in composeApp/build/social-render/
 * ```
 *
 * The questions to ask of the output:
 *
 * 1. Is a Recently Watched card **clearly lighter** than a Continue Watching card would be at the
 *    same width, while still being comfortably readable? It is secondary, not microscopic.
 * 2. Does the Social feed use the width it has - two Watching Now columns and three activity
 *    columns on a desktop window - with no wide band of dead space beside it?
 * 3. Does any label wrap? `PLAYING`, `PAUSED`, `Join` and `Ask to join` must each sit on one line.
 * 4. Does a long title, a long friend name and a missing episode number each degrade to an
 *    ellipsis rather than to a second line or a clipped box?
 *
 * ⚠ **Artwork will be missing.** There is no network here and Coil resolves nothing, so every
 * still is its placeholder fill. That is the aeroplane-mode state, which is a check in its own
 * right - the cards must still be readable with no art at all. Judge *layout* from these and judge
 * artwork on a device.
 */
class SocialRenderHarness {

    private val outputDir = File("build/social-render")

    /**
     * The same ladder the wizard harness uses, plus the phone path this work must not break, plus
     * **1920x1080** - the shape of the window the physical QA screenshots came from, which is the
     * one the collapse was actually seen on.
     */
    private val WindowSizes = listOf(
        1280 to 820,
        1920 to 1080,
        2560 to 1440,
        3840 to 2160,
        420 to 900,
    )

    private fun platformDensityFor(widthDp: Int): Float = if (widthDp >= 2000) 1f else 2f

    private fun profile(
        name: String = "Seraph",
        handle: String = "seraph",
        avatarUrl: String? = null,
    ) = SocialProfileSummary(
        profileId = "p-$handle",
        handle = handle,
        displayName = name,
        avatarUrl = avatarUrl,
        avatarColorHex = "#1E88E5",
        isFriend = true,
    )

    /**
     * Specimens chosen for the ways this card can go wrong, not for a happy path.
     *
     * A movie with no episode line at all, a normal episode, a multi-episode run (the count that
     * used to clip as a badge), and the overflow case - a long title under a long display name,
     * which is where an ellipsis has to do its job. `The Defenders` is here verbatim because it is
     * the title that came back from a real window as `The D…`.
     */
    private val activityRuns = listOf(
        RecentActivityRun(
            runId = "r1", profile = profile(), contentId = "tt1", contentType = "movie",
            videoId = "tt1", title = "A Perfectly Ordinary Movie",
            firstEventTime = "2026-09-11T10:00:00Z", lastEventTime = "2026-09-11T12:00:00Z",
        ),
        RecentActivityRun(
            runId = "r2", profile = profile("Big Z", "bigz"), contentId = "tt2", contentType = "series",
            videoId = "tt2:1:2", title = "The Defenders", season = 1, episode = 2, episodeTitle = "Worst Behavior",
            eventCount = 2,
            firstEventTime = "2026-09-11T10:00:00Z", lastEventTime = "2026-09-11T12:00:00Z",
        ),
        RecentActivityRun(
            runId = "r3", profile = profile("Zokaper", "zokaper"), contentId = "tt3", contentType = "series",
            videoId = "tt3:1:1", title = "A Show With An Unreasonably Long Title That Has To Ellipsize",
            season = 1, episode = 1, eventCount = 2,
            firstEventTime = "2026-09-11T10:00:00Z", lastEventTime = "2026-09-11T12:00:00Z",
        ),
        RecentActivityRun(
            runId = "r4", profile = profile("A Friend With A Very Long Display Name", "longname"),
            contentId = "tt4", contentType = "series", videoId = "tt4:3:12", title = "Something Else",
            season = 3, episode = 12,
            firstEventTime = "2026-09-11T10:00:00Z", lastEventTime = "2026-09-11T12:00:00Z",
        ),
    )

    /**
     * One specimen per join policy, because the action is what makes these cards different.
     *
     * The first is the physical reproduction: one paused friend, `Ask to join`, on a window with
     * room to spare. That is the card that came back 177dp wide with its label running down it.
     */
    private val watchingNow = listOf(
        WatchingNowItem(
            profile = profile("Big Z", "bigz"), contentId = "tt5", contentType = "series",
            videoId = "tt5:1:1", title = "Reacher", season = 1, episode = 1,
            episodeTitle = "Welcome to Margrave",
            positionMs = 900_000, durationMs = 2_700_000,
            effectiveJoinPolicy = WatchJoinPolicy.approval,
            state = SocialPlaybackState.paused, heartbeatAt = "2026-09-11T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile(), contentId = "tt1", contentType = "movie",
            videoId = "tt1", title = "A Perfectly Ordinary Movie",
            positionMs = 300_000, durationMs = 7_200_000,
            effectiveJoinPolicy = WatchJoinPolicy.direct,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-11T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile("Zokaper", "zokaper"), contentId = "tt3", contentType = "series",
            videoId = "tt3:1:1", title = "A Show With An Unreasonably Long Title That Has To Ellipsize",
            season = 1, episode = 1,
            positionMs = 10_000, durationMs = 2_400_000,
            // ⚠ Off must draw **no action at all**, not a disabled button. If a greyed control
            // appears in this PNG, the join-policy rule has regressed.
            effectiveJoinPolicy = WatchJoinPolicy.disabled,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-11T12:00:00Z",
        ),
    )

    @Test
    fun renderEverySocialSurface() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        for ((widthDp, heightDp) in WindowSizes) {
            render("social-feed-${widthDp}x$heightDp", widthDp, heightDp, failures) { SocialFeedScene() }

            // Home's shelves, which is where "subordinate to Continue Watching" has to read.
            //
            // ⚠ **Through `homeSocialSections`, the real production entry point.** This block first
            // rebuilt the two shelves by hand, and the hand-built copy left the height off the
            // cards - so it drew a layout the app never produces and reported a fault that was the
            // harness's own.
            render("home-social-rows-${widthDp}x$heightDp", widthDp, heightDp, failures) {
                HomeSocialScene()
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * A card must look the same whichever screen hosts it.
     *
     * Home gives its content no `LocalContentColor`, the Social screen provides `onBackground`, and
     * a `Surface` coloured `surface.copy(alpha = …)` matches no colour-scheme role - so
     * `contentColorFor` fell through to whatever the host had, and every Home card title rendered
     * black on a dark card. Rendering the same shelves under two opposite ambient content colours
     * and demanding identical bytes is the cheapest thing that could have caught it.
     */
    @Test
    fun homeCardsDoNotInheritTheHostContentColour() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val onBlack = renderBytes("home-contrast-ambient-black", 1280, 820, failures) {
            CompositionLocalProvider(LocalContentColor provides Color.Black) { HomeSocialScene() }
        }
        val onWhite = renderBytes("home-contrast-ambient-white", 1280, 820, failures) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { HomeSocialScene() }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        if (onBlack == null || onWhite == null) fail("a contrast scene did not render")
        if (!onBlack.contentEquals(onWhite)) {
            fail(
                "Home's Social cards changed with the host's LocalContentColor, so a screen that " +
                    "provides none renders their titles in the black default. Name the content " +
                    "colour on the card's own Surface.",
            )
        }
    }

    /**
     * The Social tab's feed **in the constraint stack the screen actually builds**: the dashboard's
     * own width cap, the friends rail beside it, and the feed capped to the width its column count
     * was derived from.
     */
    @Composable
    private fun SocialFeedScene() {
        // ⚠ **The window size is not the width the feed divides.** `NuvioTheme` scales the density,
        // so a 1920x1080 window is about 1458 *composition* dp - and the screen reads `maxWidth`
        // from its own `BoxWithConstraints`, not from the window. Deriving the metrics out in the
        // test from the platform size was the same two-numbers mistake in miniature; it happened to
        // agree at every size on the ladder, which is exactly how the first one survived too.
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            val railVisible = maxWidth >= 1040.dp
            val feed = socialFeedMetrics(maxWidth, railVisible)
            Column(Modifier.fillMaxHeight().widthIn(max = SocialDashboardMaxWidth)) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LazyColumn(
                            Modifier.widthIn(max = feed.feedWidth).fillMaxWidth().fillMaxHeight(),
                            contentPadding = PaddingValues(
                                start = SocialFeedHorizontalPadding,
                                end = SocialFeedHorizontalPadding,
                                top = 16.dp,
                                bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item { SectionLabel("Watching Now") }
                            socialGridItems(
                                watchingNow,
                                feed.watchingNowColumns,
                                { "w:${it.videoId}" },
                            ) { item, m ->
                                SocialWatchingNowCard(
                                    item,
                                    watchPartyEnabled = true,
                                    onOpen = {},
                                    onStartParty = {},
                                    modifier = m,
                                    artworkWidth = feed.watchingNowArtworkWidth,
                                )
                            }
                            item { SectionLabel("Friends Recently Watched") }
                            socialGridItems(
                                activityRuns,
                                feed.activityColumns,
                                RecentActivityRun::runId,
                            ) { run, m ->
                                SocialActivityCard(
                                    run,
                                    onOpen = {},
                                    modifier = m,
                                    artworkWidth = feed.activityArtworkWidth,
                                )
                            }
                        }
                    }
                    if (railVisible) {
                        // The panel itself is private to the screen; what the feed's layout cares
                        // about is that 360dp of the window is not its to divide.
                        Box(
                            Modifier.width(SocialFriendsRailWidth)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeSocialScene() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                homeSocialSections(
                    watchingNow = watchingNow,
                    activity = activityRuns,
                    sectionPadding = 16.dp,
                    watchPartyEnabled = true,
                    onStartParty = {},
                    onOpenContent = { _, _, _ -> },
                )
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        renderBytes(name, widthDp, heightDp, failures, content)
    }

    private fun renderBytes(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ): ByteArray? {
        val density = Density(platformDensityFor(widthDp))
        return runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density.density).toInt(),
                height = (heightDp * density.density).toInt(),
                density = density,
            ) {
                // Passing the real desktop scale is what makes the render honest - see the long
                // note in `SetupWizardRenderHarness`. `widthDp`/`heightDp` are **platform** dp.
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.WHITE,
                    amoled = false,
                    desktopUiScale = desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) { content() }
            }
            try {
                val image = scene.render(0L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                val bytes = data.bytes
                File(outputDir, "$name.png").writeBytes(bytes)
                bytes
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }.getOrNull()
    }
}
