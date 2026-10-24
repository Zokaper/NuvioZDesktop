package com.nuvio.app.features.social

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
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
     * Friends' activity, chosen for the ways a grouped row can go wrong (§9 stage 3):
     *
     * - one friend, one movie, with a broken poster URL (the monogram fallback, once Coil gives up)
     * - one friend's binge split across two runs, which must be **one** row reading `S2 E4–E9`
     * - three friends on the same title, which must be one row with an avatar stack
     * - a long title under a long display name, where the ellipsis has to do its job
     * - a broken avatar URL, which must still show an initial
     */
    private val activityRuns = listOf(
        RecentActivityRun(
            runId = "movie", profile = profile(), contentId = "tt-movie", contentType = "movie",
            videoId = "tt-movie", title = "A Perfectly Ordinary Movie", poster = "https://invalid.example/broken.jpg",
            firstEventTime = "2026-09-15T09:00:00Z", lastEventTime = "2026-09-15T09:30:00Z",
        ),
        RecentActivityRun(
            runId = "dd-late", profile = profile(), contentId = "tt-dd", contentType = "series",
            videoId = "tt-dd:2:9", title = "Daredevil", season = 2, episode = 9, eventCount = 3,
            firstEventTime = "2026-09-15T08:00:00Z", lastEventTime = "2026-09-15T08:45:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-a", profile = profile("Big Z", "bigz"), contentId = "tt-def", contentType = "series",
            videoId = "tt-def:1:2", title = "The Defenders", season = 1, episode = 2, eventCount = 1,
            firstEventTime = "2026-09-14T20:00:00Z", lastEventTime = "2026-09-14T21:00:00Z",
        ),
        RecentActivityRun(
            runId = "dd-early", profile = profile(), contentId = "tt-dd", contentType = "series",
            videoId = "tt-dd:2:4", title = "Daredevil", season = 2, episode = 4, eventCount = 3,
            firstEventTime = "2026-09-14T19:00:00Z", lastEventTime = "2026-09-14T19:50:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-b", profile = profile("Zokaper", "zokaper", avatarUrl = "https://invalid.example/avatar.png"),
            contentId = "tt-def", contentType = "series", videoId = "tt-def:1:3", title = "The Defenders",
            season = 1, episode = 3,
            firstEventTime = "2026-09-14T18:00:00Z", lastEventTime = "2026-09-14T18:40:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-c", profile = profile("debug", "debug"), contentId = "tt-def", contentType = "series",
            videoId = "tt-def:1:1", title = "The Defenders", season = 1, episode = 1,
            firstEventTime = "2026-09-12T18:00:00Z", lastEventTime = "2026-09-12T18:40:00Z",
        ),
        RecentActivityRun(
            runId = "long", profile = profile("A Friend With A Very Long Display Name", "longname"),
            contentId = "tt-long", contentType = "series", videoId = "tt-long:3:12",
            title = "A Show With An Unreasonably Long Title That Has To Ellipsize", season = 3, episode = 12,
            firstEventTime = "2026-09-01T10:00:00Z", lastEventTime = "2026-09-01T12:00:00Z",
        ),
    )

    /**
     * One specimen per join affordance, plus the case that must **not** group: two friends on the same
     * episode are two cards, because each card is one person's session and carries its own action.
     */
    private val watchingNow = listOf(
        WatchingNowItem(
            profile = profile("Big Z", "bigz"), contentId = "tt5", contentType = "series",
            videoId = "tt5:1:2", title = "The Punisher", season = 1, episode = 2, episodeTitle = "Two Dead Men",
            sessionId = "s-bigz", positionMs = 900_000, durationMs = 2_700_000,
            effectiveJoinPolicy = WatchJoinPolicy.approval,
            state = SocialPlaybackState.paused, heartbeatAt = "2026-09-15T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile(), contentId = "tt1", contentType = "movie",
            videoId = "tt1", title = "A Perfectly Ordinary Movie", sessionId = "s-seraph",
            positionMs = 300_000, durationMs = 7_200_000,
            effectiveJoinPolicy = WatchJoinPolicy.direct,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-15T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile("Zokaper", "zokaper", avatarUrl = "https://invalid.example/avatar.png"),
            contentId = "tt5", contentType = "series", videoId = "tt5:1:2", title = "The Punisher",
            season = 1, episode = 2, sessionId = "s-zokaper",
            positionMs = 910_000, durationMs = 2_700_000,
            // Off must draw **no action at all**, not a disabled button.
            effectiveJoinPolicy = WatchJoinPolicy.disabled,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-15T12:00:00Z",
        ),
        WatchingNowItem(
            profile = profile("A Friend With A Very Long Display Name", "longname"), contentId = "tt3",
            contentType = "series", videoId = "tt3:1:1",
            title = "A Show With An Unreasonably Long Title That Has To Ellipsize", season = 1, episode = 1,
            sessionId = "s-long", positionMs = 10_000, durationMs = 2_400_000,
            effectiveJoinPolicy = WatchJoinPolicy.approval,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-15T12:00:00Z",
        ),
    ) + listOf("Ana" to "ana", "Rayo" to "rayo", "Ben" to "ben").map { (name, handle) ->
        // One Watch Together party of three, hosted by Rayo: must draw as a single card (2026-09-17).
        WatchingNowItem(
            profile = profile(name, handle), contentId = "tt7282468", contentType = "movie",
            videoId = "tt7282468", title = "Burning", sessionId = "s-$handle",
            positionMs = 1_079_915, durationMs = 8_890_326,
            effectiveJoinPolicy = WatchJoinPolicy.approval,
            state = SocialPlaybackState.playing, heartbeatAt = "2026-09-17T13:53:00Z",
            partyId = "party-burning", partyHostProfileId = "p-rayo", partyMemberCount = 3,
        )
    }

    /** Every non-idle affordance, drawn once each, so each label is checked for wrapping. */
    private fun affordanceFor(item: WatchingNowItem): WatchingNowJoinAffordance = when (item.sessionId) {
        "s-long" -> WatchingNowJoinAffordance.Requested(expiresAtMs = Long.MAX_VALUE / 2, cancelling = false)
        else -> watchingNowJoinAffordance(item, OutgoingJoinRequestState.Idle, null)
    }

    private val friends = listOf(
        profile("Big Z", "bigz"),
        profile(),
        profile("Zokaper", "zokaper"),
        profile("A Friend With A Very Long Display Name", "longname"),
    )

    private val renderNowMs = 1_789_473_600_000L // 2026-09-15T12:00:00Z

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
            CompositionLocalProvider(LocalContentColor provides Color.Black) { HomeSocialScene(recommendedToYou(recommendationEvents)) }
        }
        val onWhite = renderBytes("home-contrast-ambient-white", 1280, 820, failures) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { HomeSocialScene(recommendedToYou(recommendationEvents)) }
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
     * The outgoing request dock in each state that reads differently, stacked in one frame, with a
     * deadline 100s out so the ring and clock read as they would mid-request.
     */
    @Test
    fun renderWatchTogetherDockStates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val binding = JoinRequestBinding("me", 1)
        val target = JoinRequestTarget("seraph", "Seraph", sessionId = "s1")
        val content = JoinRequestContent("tt5", "tt5:1:2", "The Punisher", season = 1, episode = 2)
        val far = com.nuvio.app.features.watchparty.currentEpochMs() + 100_000L
        val states = listOf(
            OutgoingJoinRequestState.Pending(binding, target, content, "r", far),
            OutgoingJoinRequestState.Outcome(binding, target, content, OutgoingJoinOutcome.Declined, null),
            OutgoingJoinRequestState.Outcome(binding, target, content, OutgoingJoinOutcome.Failed, null, "This party is full"),
            OutgoingJoinRequestState.Joining(binding, target, content, partyFixture()),
        )
        listOf(1280 to 820, 420 to 900).forEach { (w, h) ->
            render("dock-${w}x$h", w, h, failures) {
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val width = maxWidth
                    Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        states.forEach { WatchTogetherDock(it, windowWidth = width) }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun partyFixture() = com.nuvio.app.features.watchparty.WatchPartyState(
        id = "p", hostProfileId = "seraph", status = com.nuvio.app.features.watchparty.WatchPartyStatus.playing,
        controlMode = com.nuvio.app.features.watchparty.WatchPartyControlMode.host_only, contentGeneration = 1,
        content = com.nuvio.app.features.watchparty.PartyContent("tt5", "series", "tt5:1:2", "The Punisher"),
        positionMs = 0, durationMs = 0, playbackSpeed = 1f, sequence = 1, stateUpdatedAt = "",
    )

    /**
     * Diagnostic for "Home's Friends Recently Watched row starts with a card cut off at the left edge"
     * (§11.13). Hypothesis: `LazyRow` anchors its scroll to the first visible item's *key*, so when a
     * newer item arrives at the front of an unscrolled shelf the old first item stays put and the new
     * one lands off-screen to the left, peeking into the content padding.
     */
    @Test
    fun aShelfAtTheStartStaysAtTheStartWhenSomethingNewArrivesInFront() {
        val items = mutableStateOf(listOf("b", "c", "d", "e"))
        val state = LazyListState()
        val scene = ImageComposeScene(width = 1280, height = 300, density = Density(1f)) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false) {
                SocialHomeShelf(
                    title = "Friends' activity",
                    sectionPadding = 16.dp,
                    items = items.value,
                    key = { it },
                    itemSpacing = 16.dp,
                    state = state,
                ) { Box(Modifier.width(280.dp).height(66.dp).background(MaterialTheme.colorScheme.surface)) }
            }
        }
        try {
            scene.render(0L)
            items.value = listOf("a") + items.value
            scene.render(16_000_000L)
            scene.render(32_000_000L)
            if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) {
                fail(
                    "an unscrolled shelf moved when an item arrived in front: first visible index " +
                        "${state.firstVisibleItemIndex}, offset ${state.firstVisibleItemScrollOffset}",
                )
            }
        } finally {
            scene.close()
        }
    }

    /**
     * The Social tab **as `SocialScreen` composes it**: [SocialFeed], the stateless layout the screen
     * hands its gathered state to, with a fixture model in place of the repositories.
     *
     * ⚠ This used to be `SocialFeedSceneBody`, a hand-rebuilt replica of the screen's constraint
     * stack - the `LazyColumn`, its padding, the rail and the metrics call, copied out. It was honest
     * only for as long as it happened to match, and the phone redesign restructures exactly that
     * stack. Now there is one description of the layout and this file composes it.
     */
    @Composable
    private fun SocialFeedScene(
        state: SocialUiState = feedState,
        tab: SocialTab = SocialTab.Activity,
        overlay: SocialOverlay? = null,
    ) {
        val groups = groupFriendActivity(state.activity)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SocialFeed(
                model = SocialFeedModel(
                    state = state,
                    activityGroups = groups,
                    activityTimeline = friendActivityTimeline(state.activity, renderNowMs),
                    activityNowMs = renderNowMs,
                    joinAffordance = ::affordanceFor,
                    initialTab = tab,
                    initialOverlay = overlay,
                ),
                actions = SocialFeedActions(),
                listState = rememberLazyListState(),
            )
        }
    }

    private val notifications = listOf(
        SocialNotification(
            id = "join_request:1", kind = SocialNotificationKind.WatchingNowJoinRequest, actor = profile("Ben", "ben"),
            createdAt = "2026-09-15T11:59:00Z", state = "pending",
            availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
            contentSummary = com.nuvio.app.features.watchparty.PartyContent("tt-sev", "series", "tt-sev:1:3", "Severance", season = 1, episode = 3),
        ),
        SocialNotification(
            id = "party_invite:1", kind = SocialNotificationKind.PartyInvitation, actor = profile("Rayo", "rayo"),
            createdAt = "2026-09-15T11:48:00Z", state = "pending", partyId = "party-burning",
            availableActions = setOf(SocialNotificationAction.Join),
            contentSummary = com.nuvio.app.features.watchparty.PartyContent("tt7282468", "movie", "tt7282468", "Burning"),
        ),
        SocialNotification(
            id = "friend_request:1", kind = SocialNotificationKind.FriendRequest, actor = profile("Ana", "ana"),
            createdAt = "2026-09-15T11:00:00Z", state = "pending",
            availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
        ),
        SocialNotification(
            id = "friend_request:2", kind = SocialNotificationKind.FriendRequest, actor = profile("Zokaper", "zokaper"),
            createdAt = "2026-09-14T10:00:00Z", readAt = "2026-09-14T10:05:00Z", state = "accepted",
        ),
        SocialNotification(
            id = "party_invite:2", kind = SocialNotificationKind.PartyInvitation, actor = profile("debug", "debug"),
            createdAt = "2026-09-12T20:00:00Z", readAt = "2026-09-12T20:01:00Z", state = "expired",
            contentSummary = com.nuvio.app.features.watchparty.PartyContent("tt-andor", "series", "tt-andor:1:1", "Andor"),
        ),
    )

    private val inboxEvents = listOf(
        SocialInboxEvent(
            id = "e-rec", kind = SocialInboxEvent.Recommendation, actor = profile(),
            createdAt = "2026-09-15T10:00:00Z",
            payload = SocialInboxPayload(contentId = "tt-andor", contentType = "series", title = "Andor", note = "the prison arc is unreal"),
        ),
        SocialInboxEvent(
            id = "e-acc", kind = SocialInboxEvent.FriendAccepted, actor = profile("Zokaper", "zokaper"),
            createdAt = "2026-09-14T12:00:00Z", readAt = "2026-09-14T12:01:00Z",
        ),
        SocialInboxEvent(
            id = "e-watch", kind = SocialInboxEvent.FriendWatching, actor = profile("Big Z", "bigz"),
            createdAt = "2026-09-14T09:00:00Z", readAt = "2026-09-14T09:05:00Z",
            payload = SocialInboxPayload(contentId = "tt5", contentType = "series", title = "The Punisher"),
        ),
        // A kind from a newer backend: must be skipped, not crash or draw blank.
        SocialInboxEvent(id = "e-future", kind = "something_new", actor = profile(), createdAt = "2026-09-15T11:59:30Z"),
    )

    private val feedState = SocialUiState(
        capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true, partyContractVersion = 2),
        notifications = notifications,
        inbox = inboxEvents,
        sentRequests = listOf(SentFriendRequest("sent-1", profile("Ben", "ben"), "2026-09-15T09:00:00Z")),
        socialV2Backend = true,
        activeProfileId = "p-zokaper",
        me = profile("big z", "zokaper"),
        friends = friends,
        watchingNow = watchingNow,
        activity = activityRuns,
    )

    /** The ordinary phone case: one friend watching, a few rows of history, three friends. */
    private val typicalState = feedState.copy(
        watchingNow = watchingNow.take(1),
        friends = friends.take(3),
    )

    /** Recommendations waiting for the viewer: Burning from two friends (one note), Carrie from one. */
    private val recommendationEvents = listOf(
        Triple("tt-carrie", "Carrie", listOf(profile("debug", "debug") to "ts peak. Said the Liar Man.")),
        Triple("tt7282468", "Burning", listOf(profile("Ana", "ana") to "slow but trust me", profile("Ben", "ben") to null)),
    ).flatMapIndexed { i, (id, title, from) ->
        from.mapIndexed { j, (who, note) ->
            SocialInboxEvent(
                id = "e-rec-$i-$j", kind = SocialInboxEvent.Recommendation, actor = who,
                createdAt = "2026-09-15T1${i}:0$j:00Z",
                payload = SocialInboxPayload(contentId = id, contentType = if (id == "tt-carrie") "series" else "movie", title = title, note = note),
            )
        }
    }

    /**
     * Where "Recommended to you" lives (picked 2026-10-19): the shelf at the top of the Inbox - with no
     * recommendation card repeated under it - and, when Social settings -> Home asks for it, a shelf of its
     * own on Home under Friends. PNGs: `build/social-render/recommended-*`.
     */
    @Test
    fun renderRecommendedToYou() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val state = feedState.copy(inbox = recommendationEvents + inboxEvents, watchingNow = watchingNow.take(1))
        val recommended = recommendedToYou(state.inbox)
        render("recommended-inbox-desktop", 1280, 820, failures) { SocialFeedScene(state, overlay = SocialOverlay.Inbox) }
        renderPhone("recommended-inbox-phone", 411, 914, failures) { SocialFeedScene(state, overlay = SocialOverlay.Inbox) }
        render("recommended-home-desktop", 1280, 820, failures) { HomeSocialScene(recommended) }
        renderPhone("recommended-home-phone", 411, 914, failures) { HomeSocialScene(recommended) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * "Recommended to you" as its own page (2026-10-22): the header button with its count and new-dot,
     * and the page it opens. Three titles, one from two friends, one long note.
     * PNGs: `build/social-render/recommended-page-*` and `recommended-button-*`.
     */
    @Test
    fun renderRecommendedPage() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val art = mapOf(
            "tt7282468" to com.nuvio.app.features.setup.SetupSampleTitle.backgroundUrl("tt7282468"),
            "tt1475582" to com.nuvio.app.features.setup.SetupSampleTitle.backgroundUrl("tt1475582"),
            "tt0903747" to com.nuvio.app.features.setup.SetupSampleTitle.backgroundUrl("tt0903747"),
        )
        val recs = listOf(
            Triple("tt0903747", "Breaking Bad", listOf(profile("debug", "debug") to "it's peak. Said the Liar Man. Start from season one and don't skip anything, it pays off.")),
            Triple("tt7282468", "Burning", listOf(profile("Ana", "ana") to "slow but trust me", profile("Ben", "ben") to null)),
            Triple("tt1475582", "Sherlock", listOf(profile("Seraph", "seraph") to null)),
        ).flatMapIndexed { i, (id, title, from) ->
            from.mapIndexed { j, (who, note) ->
                SocialInboxEvent(
                    id = "e-rec-$i-$j", kind = SocialInboxEvent.Recommendation, actor = who,
                    createdAt = "2026-09-15T1${i}:0$j:00Z",
                    payload = SocialInboxPayload(contentId = id, contentType = if (id == "tt7282468") "movie" else "series", title = title, note = note, background = art[id]),
                )
            }
        }
        val state = feedState.copy(inbox = recs + inboxEvents, watchingNow = watchingNow.take(1))
        render("recommended-button-1280x820", 1280, 820, failures) { SocialFeedScene(state) }
        renderPhone("recommended-button-411x914", 411, 914, failures) { SocialFeedScene(state) }
        render("recommended-page-1280x820", 1280, 820, failures) { SocialFeedScene(state, overlay = SocialOverlay.Recommended) }
        renderPhone("recommended-page-411x914", 411, 914, failures) { SocialFeedScene(state, overlay = SocialOverlay.Recommended) }
        render("recommended-inbox-without-shelf-1280x820", 1280, 820, failures) { SocialFeedScene(state, overlay = SocialOverlay.Inbox) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * The two friend pickers with fourteen friends (2026-10-22): the list scrolls inside a cap, the
     * button stays on screen, and the dialog keeps clear of a short window's edges.
     */
    @Test
    fun renderFriendPickers() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val many = listOf("debug", "faisal", "finalguy", "jeremy renner", "main debug", "new guy", "Seraph", "tvamet", "VeyronV1",
            "Ana", "Ben", "Big Z", "Rayo", "Zokaper").map { name -> profile(name, name.lowercase().replace(" ", "")) }
        val content = SocialRecommendContent(contentType = "series", contentId = "tt1475582", title = "Sherlock", poster = null,
            background = com.nuvio.app.features.setup.SetupSampleTitle.backgroundUrl("tt1475582"))
        val rows = many.mapIndexed { i, p ->
            com.nuvio.app.features.watchparty.PartyInviteRow(
                key = p.profileId, name = p.displayName, avatarUrl = null, colorHex = "#1E88E5",
                watching = if (i < 2) "The Punisher · S1 E2" else null,
                button = com.nuvio.app.features.player.PartyInviteButton.Invite,
            )
        }
        for ((w, h) in listOf(1280 to 820, 1280 to 600, 1920 to 1080)) {
            render("picker-recommend-${w}x$h", w, h, failures, settled = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SocialRecommendSheetContent(content, many, wide = true, onDismiss = {})
                }
            }
            render("picker-invite-${w}x$h", w, h, failures, settled = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    com.nuvio.app.features.watchparty.PartyInvitePicker(rows, slots = 8, wide = true, onDismiss = {}, onInvite = {}, onCancel = {})
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * The phone scenes, at a phone's own scale: density 2, no desktop UI scale, a font scale of 1,
     * and the bottom-nav pill's measured reserve (79dp expanded) in the overlay padding, so the
     * list's end clears where the pill would be. Insets are zero here - device checks own those.
     */
    @Test
    fun renderTheSocialTabAtPhoneSizes() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val sizes = listOf(411 to 914, 891 to 411, 360 to 780, 320 to 600, 800 to 1280)
        for ((w, h) in sizes) {
            renderPhone("phone-social-typical-${w}x$h", w, h, failures) { SocialFeedScene(typicalState) }
            renderPhone("phone-social-full-${w}x$h", w, h, failures) { SocialFeedScene() }
        }
        for ((w, h) in listOf(411 to 914, 320 to 600)) {
            renderPhone("phone-social-friends-${w}x$h", w, h, failures) { SocialFeedScene(tab = SocialTab.Friends) }
            renderPhone("phone-social-inbox-${w}x$h", w, h, failures) { SocialFeedScene(overlay = SocialOverlay.Inbox) }
            renderPhone("phone-sheet-join-${w}x$h", w, h, failures) {
                SheetFrame(edgeToEdge = true) {
                    val item = orderWatchingNowForDisplay(watchingNow).first()
                    SocialJoinSheetContent(item, affordanceFor(item), wide = false, onJoin = {}, onCancelRequest = {}, onDetails = {})
                }
            }
            renderPhone("phone-page-profile-${w}x$h", w, h, failures) {
                val seraph = profile()
                val live = watchingNow.first { it.profile.profileId == seraph.profileId }
                SocialFriendProfilePage(
                    friend = seraph,
                    friends = listOf(seraph),
                    audiences = SocialAudiences(),
                    watching = live,
                    affordance = affordanceFor(live),
                    wide = false,
                    topInset = 0.dp,
                    utcOffsetMs = 0L,
                    onBack = {},
                    onJoin = {},
                    onCancelRequest = {},
                    onWatchingDetails = {},
                    onOpenContent = { _, _, _ -> },
                    onSetAudienceMember = { _, _, _ -> },
                    onRemove = {},
                    loadStats = { null },
                    loadHistory = { _, _ -> Result.success(FriendHistoryPage(emptyList(), null)) },
                    initialHistory = FriendHistoryPage(activityRuns.filter { it.profile.profileId == seraph.profileId }, null),
                    initialStats = SocialTogetherStats(
                        seconds = 50_400, parties = 6,
                        titles = listOf(
                            SocialTogetherTitle("Burning", "tt7282468", "movie", parties = 3),
                            SocialTogetherTitle("Andor", "tt-andor", "series", parties = 2),
                            SocialTogetherTitle("Daredevil", "tt-dd", "series", parties = 1),
                        ),
                    ),
                )
            }
        }
        renderPhone("phone-social-empty-411x914", 411, 914, failures) {
            SocialFeedScene(feedState.copy(watchingNow = emptyList(), activity = emptyList(), friends = emptyList()))
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun renderFriendProfileAndAudiencePages() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val seraph = profile()
        val friends = listOf(seraph, profile("Big Z", "bigz"), profile("A Friend With A Very Long Display Name", "longname"))
        val audiences = SocialAudiences(
            watchingNow = SocialAudience(SocialAudienceMode.Except, except = listOf(seraph.profileId)),
            recent = SocialAudience(SocialAudienceMode.Only, only = listOf(seraph.profileId)),
        )
        for ((w, h) in listOf(320 to 600, 360 to 780, 411 to 914, 891 to 411, 1280 to 820, 1920 to 1080)) {
            val wide = w >= 1000
            fun capture(name: String, content: @Composable () -> Unit) {
                if (wide) render("$name-${w}x$h", w, h, failures, content = content)
                else renderPhone("$name-${w}x$h", w, h, failures, content)
            }
            for (tab in if (wide) listOf(0) else listOf(0, 1)) {
                capture("profile-real-tab$tab") {
                    SocialFriendProfilePage(
                        friend = seraph, friends = friends, audiences = audiences,
                        watching = watchingNow.first { it.profile.profileId == seraph.profileId },
                        affordance = WatchingNowJoinAffordance.AskToJoin, wide = wide, topInset = 0.dp, utcOffsetMs = 0L,
                        onBack = {}, onJoin = {}, onCancelRequest = {}, onWatchingDetails = {},
                        onOpenContent = { _, _, _ -> }, onSetAudienceMember = { _, _, _ -> }, onRemove = {},
                        loadStats = { null }, loadHistory = { _, _ -> Result.success(FriendHistoryPage(emptyList(), null)) },
                        initialHistory = FriendHistoryPage(activityRuns.filter { it.profile.profileId == seraph.profileId }, null),
                        initialStats = SocialTogetherStats(seconds = 50_400, parties = 6,
                            titles = listOf(SocialTogetherTitle("Burning", "tt7282468", "movie", parties = 3))),
                        initialPhoneTab = tab,
                    )
                }
            }
            for (setting in SocialAudienceSetting.entries) {
                capture("audience-real-${setting.wire}") {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SocialAudiencePage(setting, audiences[setting], friends, wide, onBack = {}, onSet = { _, _ -> })
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun popupFixtures(): List<SocialPopup> {
        val seraph = profile()
        val long = profile(name = "A Friend With A Very Long Display Name", handle = "longname")
        return listOf(
            SocialPopup("w", SocialPopupKind.FriendWatching, seraph, "is watching", "Andor", "S1 E2",
                SocialArtRef("Andor", "series", "tt-andor", null), SocialNavigationTarget.Inbox),
            SocialPopup("r", SocialPopupKind.FriendRequest, long, "wants to be friends", "@longname", null,
                SocialArtRef("longname", null, null, null), SocialNavigationTarget.FriendRequests),
            SocialPopup("i", SocialPopupKind.PartyInvite, seraph, "invited you to watch",
                "The Lord of the Rings: The Return of the King", "Watch party",
                SocialArtRef("Lord", "movie", "tt-lotr", null), SocialNavigationTarget.Inbox),
            SocialPopup("j", SocialPopupKind.JoinRequest, seraph, "asked to join", "Daredevil", "Your playback · S2 E6",
                SocialArtRef("Daredevil", "series", "tt-dd", null), SocialNavigationTarget.Inbox),
        )
    }

    /** The shared pop-up card: the phone's compact banner at phone widths, the desktop's full card. */
    @Test
    fun renderSocialPopupCards() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((w, h) in listOf(411 to 420, 360 to 420, 320 to 420, 800 to 420)) {
            renderPhone("popup-banners-${w}x$h", w, h, failures) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 440.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { popupFixtures().forEach { SocialPopupCard(it, compact = true) } }
                }
            }
        }
        render("popup-cards-440x640", 440, 640, failures) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    popupFixtures().forEach { SocialPopupCard(it) }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** A sheet's body on the sheet's surface, as the bottom sheet hosts it. */
    @Composable
    private fun SheetFrame(edgeToEdge: Boolean = false, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .then(if (edgeToEdge) Modifier else Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
            }
        }
    }

    private fun renderPhone(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        runCatching {
            val scene = ImageComposeScene(width = widthDp * 2, height = heightDp * 2, density = Density(2f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density, 1f),
                        LocalNuvioBottomNavigationOverlayPadding provides 79.dp,
                    ) { content() }
                }
            }
            try {
                scene.render(0L).close()
                scene.render(16_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG).use { data ->
                        File(outputDir, "$name.png").writeBytes(data?.bytes ?: error("encodeToData returned null"))
                    }
                }
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }

    @Composable
    private fun HomeSocialScene(recommended: List<RecommendedTitle> = emptyList()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val width = maxWidth
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                homeSocialSections(
                    watchingNow = watchingNow,
                    activity = activityRuns,
                    sectionPadding = 16.dp,
                    availableWidth = width,
                    watchPartyEnabled = true,
                    onStartParty = {},
                    onOpenContent = { _, _, _ -> },
                )
                homeRecommendedSection(recommended, sectionPadding = 16.dp, availableWidth = width) { _, _, _ -> }
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
        /** Draw a second frame a second later, so a dialog's fade-in has finished. */
        settled: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        renderBytes(name, widthDp, heightDp, failures, settled, content)
    }

    private fun renderBytes(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        settled: Boolean = false,
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
                if (settled) scene.render(0L).close()
                scene.render(if (settled) 1_000_000_000L else 0L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG).use { data ->
                        val bytes = data?.bytes ?: error("encodeToData returned null")
                        File(outputDir, "$name.png").writeBytes(bytes)
                        bytes
                    }
                }
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }.getOrNull()
    }
}
