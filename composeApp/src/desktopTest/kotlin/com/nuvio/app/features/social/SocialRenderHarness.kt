package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
 * it.** Recently Watched was reusing `TitlePresentationCard` with Continue Watching's own card
 * metrics, so a friend's history was drawn at literally the same size and weight as the viewer's
 * own half-finished episode. Nothing in the pure suites can see that, it is not a compile error,
 * and the parser check cannot see it either. The previous pass found a real defect - four playback
 * chips rendering as "Prefer", "Prefer", "Require", "Require" - by reading harness PNGs, and its
 * status note says to keep running it. This is that, for Social.
 *
 * ## What it proves and does not prove
 *
 * It **asserts every scene composes and renders without throwing**, which is worth having on its
 * own. What it cannot do is judge the layout - that is what the PNGs are for. Run it and look:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialRenderHarness"
 * # then read the PNGs in composeApp/build/social-render/
 * ```
 *
 * The questions to ask of the output, which are the ones the redesign is answering:
 *
 * 1. Is a Recently Watched card **clearly lighter** than a Continue Watching card would be at the
 *    same width? It should read as an activity entry, not as another thing in the catalogue.
 * 2. Does the Social grid use the width - two or three columns at 2560 and 3840 - rather than
 *    running as one long vertical file?
 * 3. Is the identity line legible and *outside* the artwork, and does the episode count sit in the
 *    metadata sentence rather than as a badge clipping over the art?
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

    /** The same ladder the wizard harness uses, plus the phone path this work must not break. */
    private val WindowSizes = listOf(
        1280 to 820,
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
     * which is where an ellipsis has to do its job.
     */
    private val activityRuns = listOf(
        RecentActivityRun(
            runId = "r1", profile = profile(), contentId = "tt1", contentType = "movie",
            videoId = "tt1", title = "A Perfectly Ordinary Movie",
            firstEventTime = "2026-09-11T10:00:00Z", lastEventTime = "2026-09-11T12:00:00Z",
        ),
        RecentActivityRun(
            runId = "r2", profile = profile("Big Z", "bigz"), contentId = "tt2", contentType = "series",
            videoId = "tt2:2:9", title = "Daredevil", season = 2, episode = 9, episodeTitle = "Nine",
            eventCount = 5,
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

    /** One specimen per join policy, because the action is what makes these cards different. */
    private val watchingNow = listOf(
        WatchingNowItem(
            profile = profile(), contentId = "tt2", contentType = "series", videoId = "tt2:2:9",
            title = "Daredevil", season = 2, episode = 9, episodeTitle = "Nine",
            positionMs = 900_000, durationMs = 2_700_000,
            effectiveJoinPolicy = WatchJoinPolicy.direct,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-11T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile("Big Z", "bigz"), contentId = "tt1", contentType = "movie",
            videoId = "tt1", title = "A Perfectly Ordinary Movie",
            positionMs = 300_000, durationMs = 7_200_000,
            effectiveJoinPolicy = WatchJoinPolicy.approval,
            state = SocialPlaybackState.paused, heartbeatAt = "2026-09-11T12:00:00Z",
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
            // The Social tab's feed, at the column count that width actually produces. The feed
            // excludes the friends rail above the dashboard breakpoint, exactly as the screen does.
            val wideDashboard = widthDp >= 1040
            val feedWidth = if (wideDashboard) (widthDp - SocialFriendsRailWidth.value.toInt()) else widthDp
            val columns = socialGridColumns(feedWidth.dp)
            render("social-feed-${widthDp}x$heightDp-cols$columns", widthDp, heightDp, failures) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                ) {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { SectionLabel("Watching now") }
                        socialGridItems(watchingNow, columns, { "w:${it.videoId}" }) { item, m ->
                            SocialWatchingNowCard(item, watchPartyEnabled = true, onOpen = {}, onStartParty = {}, modifier = m)
                        }
                        item { SectionLabel("Friends recently watched") }
                        socialGridItems(activityRuns, columns, RecentActivityRun::runId) { run, m ->
                            SocialActivityCard(run, onOpen = {}, modifier = m)
                        }
                    }
                }
            }

            // Home's shelves, which is where "subordinate to Continue Watching" has to read.
            //
            // ⚠ **Through `homeSocialSections`, the real production entry point.** This block first
            // rebuilt the two shelves by hand, and the hand-built copy left the height off the
            // cards - so it drew a layout the app never produces and reported a fault that was the
            // harness's own. A harness that composes its subject differently from production is
            // worse than no harness.
            render("home-social-rows-${widthDp}x$heightDp", widthDp, heightDp, failures) {
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
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Composable
    private fun SectionLabel(text: String, horizontal: androidx.compose.ui.unit.Dp = 0.dp) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = horizontal),
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
        val density = Density(platformDensityFor(widthDp))
        runCatching {
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
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }
    }
}
