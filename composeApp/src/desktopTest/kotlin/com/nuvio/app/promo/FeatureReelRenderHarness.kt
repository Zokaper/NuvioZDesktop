package com.nuvio.app.promo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.language.AudioLanguageOption
import com.nuvio.app.core.language.SubtitleLanguageOption
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.downloads.DownloadMode
import com.nuvio.app.features.downloads.DownloadPolicy
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleLanguageItem
import com.nuvio.app.features.player.PlayerControlSubtitleOptionItem
import com.nuvio.app.features.player.desktop.toControlsJson
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackLoadingScreen
import com.nuvio.app.features.playback.PlaybackLoadingMotion
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.playback.playbackLoadingState
import com.nuvio.app.features.setup.SetupSourcesActions
import com.nuvio.app.features.setup.SetupSourcesState
import com.nuvio.app.features.setup.SetupSpecimen
import com.nuvio.app.features.setup.SetupStep
import com.nuvio.app.features.setup.SetupStepBody
import com.nuvio.app.features.setup.SetupWizardDesktopLayout
import com.nuvio.app.features.setup.SetupWizardPlan
import com.nuvio.app.features.setup.downloadSetupVariant
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.TabletStreamsLayout
import com.nuvio.app.features.updater.formatFileSize
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the surfaces for the **feature reel** (`Nuvio Z/promo/reel/`), the second promo beside the
 * story trailer. Same rule as [PromoRenderHarness], whose seeding, shells and scenes it reuses:
 * production entry points under production constraints, with only the data replaced.
 *
 * The reel works much closer to the UI than the trailer, so most scenes here are the trailer's
 * surfaces again at 2-3x pixel density - the layout is identical (the UI scale follows the window
 * in dp, not the pixel density), there are just enough pixels to push the camera in on one card.
 * The new surfaces are the ones the playback-modes beat needs: the wizard's mode step, Classic's
 * source list (`TabletStreamsLayout`), and the one loading surface (`PlaybackLoadingScreen`) walking
 * through Instant's real steps.
 *
 * Skips itself unless `Nuvio Z/promo/assets/app-art` exists, so CI never runs it.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*FeatureReelRenderHarness" --rerun
 * REEL_ONLY=loading,streams ./gradlew ...   # a subset
 * # PNGs land in Nuvio Z/promo/renders/reel/
 * ```
 */
class FeatureReelRenderHarness {

    private val promo = PromoRenderHarness()
    private val sintel = PromoCatalog.sintel
    private val Desktop = PromoRenderHarness.Kind.Desktop
    private val Phone = PromoRenderHarness.Kind.Phone

    /** The releases every source surface in the reel shows: the quality sheet's, plus a second addon for Classic's list. */
    private val archive = listOf(
        promo.stream("4K HDR", "Sintel.2010.2160p.HDR.HEVC.DTS-HD.MA.5.1.mkv", 6_400_000_000L),
        promo.stream("4K", "Sintel.2010.2160p.WEB-DL.HEVC.AAC.5.1.mkv", 3_100_000_000L),
        promo.stream("1080p", "Sintel.2010.1080p.BluRay.x264.DTS.5.1.mkv", 1_900_000_000L),
        promo.stream("1080p", "Sintel.2010.1080p.WEB-DL.HEVC.AAC.mkv", 820_000_000L),
        promo.stream("720p", "Sintel.2010.720p.WEB-DL.x264.AAC.mkv", 540_000_000L),
        promo.stream("SD", "Sintel.2010.480p.WEB-DL.x264.AAC.mkv", 260_000_000L),
    ).map { it.copy(description = it.behaviorHints.filename) }
    private val library = listOf(
        libraryStream("4K", "Sintel.2010.2160p.UHD.BluRay.x265.10bit.HDR.DTS.mkv", 11_800_000_000L),
        libraryStream("1080p", "Sintel.2010.1080p.BluRay.x265.10bit.AAC.5.1.mkv", 1_400_000_000L),
        libraryStream("1080p", "Sintel.2010.1080p.WEBRip.x264.AAC.mkv", 1_050_000_000L),
        libraryStream("720p", "Sintel.2010.720p.BluRay.x264.AAC.mkv", 700_000_000L),
    )

    private fun libraryStream(name: String, filename: String, size: Long) = StreamItem(
        name = name,
        description = filename,
        url = "https://example.invalid/$filename",
        addonName = "Free Film Library",
        addonId = "free-film-library",
        behaviorHints = StreamBehaviorHints(filename = filename, videoSize = size),
    )

    private val groups = listOf(
        AddonStreamGroup("Open Movie Archive", "open-movie-archive", archive),
        AddonStreamGroup("Free Film Library", "free-film-library", library),
    )

    /**
     * A few more weeks of the demo friends' watching, so the Social list is long enough to scroll the way
     * a real one is. Fictional, like the rest; grouped by title, so some rows gain a second avatar.
     */
    private val longerHistory by lazy {
        val c = PromoCatalog
        val p = PromoPeople
        promo.activity + listOf(
            promo.run("b1", p.jonah, c.bigBuckBunny, 7),
            promo.run("b2", p.sam, c.spring, 29),
            promo.run("b3", p.theo, c.elephantsDream, 58),
            promo.run("b4", p.priya, c.metropolis, 170),
            promo.run("b5", p.jonah, c.charade, 196),
            promo.run("b6", p.ines, c.tearsOfSteel, 228),
            promo.run("b7", p.sam, c.sherlockJr, 262),
            promo.run("b8", p.theo, c.spriteFright, 300),
        ).filter { r -> c.all.any { it.id == r.contentId } }
    }

    @Test
    fun renderReelSurfaces() {
        if (!PromoArt.available) {
            println("FeatureReelRenderHarness: ${PromoArt.promoRoot}/assets/app-art is missing; skipping.")
            return
        }
        File(promo.outDir, "reel").mkdirs()
        promo.seedProfile()
        promo.seedMeta(sintel.details(moreLikeThis = PromoCatalog.openMovies.drop(1).map { it.preview() }))
        val only = System.getenv("REEL_ONLY")?.split(',')?.map(String::trim)?.toSet()
        fun wants(name: String) = only == null || name in only
        val failures = mutableListOf<String>()

        // Close-up stills of the trailer's surfaces. Home opens on the same Continue Watching as the
        // trailer's Home scroll (`desktop-home-scroll`), which the reel cuts to.
        if (wants("home")) {
            promo.render("reel/home", 1920, 1080, Desktop, 3f, failures) {
                promo.DesktopShell(AppScreenTab.Home) { promo.HomeComposition(continueWatching = promo.cwBefore) }
            }
        }
        if (wants("home-cw")) {
            promo.render("reel/home-cw", 1920, 1080, Desktop, 3f, failures) {
                promo.DesktopShell(AppScreenTab.Home) { promo.HomeComposition(remember { LazyListState(0, (760 * 3f).toInt()) }) }
            }
        }
        // Continuity: the phone's Home before Sintel reaches its Continue Watching, and after.
        for ((name, items) in listOf("before" to promo.cwBefore, "after" to null)) {
            if (!wants("phone-home")) continue
            promo.render("reel/phone-home-$name", 411, 914, Phone, 3f, failures) {
                promo.PhoneShell(AppScreenTab.Home, statusInset = true) {
                    if (items == null) promo.HomeComposition() else promo.HomeComposition(continueWatching = items)
                }
            }
        }
        if (wants("social")) {
            promo.render("reel/social", 1920, 1080, Desktop, 2.5f, failures) {
                promo.DesktopShell(AppScreenTab.Social) { promo.Social() }
            }
        }
        if (wants("social-scroll")) {
            // The Social tab scrolled by its own list, as the trailer's Home scroll is: Watching Now down
            // into Friends Recently Watched.
            val state = LazyListState()
            val total = 150
            var done = 0f
            promo.render(
                "reel/social-scroll", 1920, 1080, Desktop, 1.5f, failures, frames = total,
                beforeFrame = { i ->
                    val p = i / (total - 1f)
                    val eased = if (p < 0.5f) 4 * p * p * p else 1 - Math.pow((-2.0 * p + 2), 3.0).toFloat() / 2
                    val target = eased * 560f * 1.5f * 1.32f // 560 dp of list, in pixels at this density and UI scale
                    state.dispatchRawDelta(target - done)
                    done = target
                },
            ) { promo.DesktopShell(AppScreenTab.Social) { promo.Social(state, longerHistory) } }
        }
        if (wants("quality")) {
            promo.render("reel/quality", 1920, 1080, Desktop, 2.5f, failures) {
                Box(Modifier.fillMaxSize()) {
                    promo.Details()
                    promo.QualitySheet()
                }
            }
        }

        // The wizard's playback-mode step, once per mode so each card has a selected rendering.
        if (wants("wizard")) {
            for (mode in PlaybackMode.entries) {
                promo.render("reel/wizard-${mode.name.lowercase()}", 1920, 1080, Desktop, 2f, failures) { WizardModeStep(mode) }
            }
        }

        // Classic: every release, listed by addon, on the desktop's source list.
        if (wants("streams")) {
            promo.render("reel/streams", 1920, 1080, Desktop, 2f, failures) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    TabletStreamsLayout(
                        isEpisode = false,
                        title = sintel.name,
                        logo = null,
                        poster = PromoArt.poster(sintel.slug),
                        background = PromoArt.backdrop(sintel.slug),
                        episodeThumbnail = null,
                        seasonNumber = null,
                        episodeNumber = null,
                        episodeTitle = null,
                        uiState = StreamsUiState(groups = groups, activeAddonIds = groups.map { it.addonId }.toSet()),
                        debridEnabled = false,
                        appendInstantServiceToDefaultName = false,
                        resumePositionMs = null,
                        resumeProgressFraction = null,
                        dominantColorEnabled = false,
                        onStreamSelected = { _, _, _ -> },
                        onStreamLongPress = {},
                        onStreamSecondaryClick = { _, _ -> },
                        onRefresh = {},
                    )
                }
            }
        }

        // Instant: the one loading surface from the tap to the hand-off, stepping through the states
        // the route really passes through. The chosen release's facts come from the production extractor.
        if (wants("loading")) {
            val chosen = archive[1]
            val facts = SourceFactsExtractor.extract(chosen)
            val frame = mutableIntStateOf(0)
            val total = 200
            promo.render(
                "reel/loading", 1920, 1080, Desktop, 1.5f, failures, frames = total,
                beforeFrame = { i -> frame.intValue = i },
            ) {
                val i = frame.intValue
                val state = when {
                    i < 40 -> playbackLoadingState(PlaybackProgressStep.FindingSources)
                    i < 80 -> playbackLoadingState(PlaybackProgressStep.CheckingConnection)
                    i < 110 -> playbackLoadingState(PlaybackProgressStep.ChoosingSource)
                    i < 160 -> playbackLoadingState(PlaybackProgressStep.ResolvingLink, facts = facts, contentLanguage = "en")
                    else -> playbackLoadingState(PlaybackProgressStep.StartingPlayback, facts = facts, contentLanguage = "en")
                }
                // PlaybackLoadingHost's entrance, restated: 0 -> 1 over ENTRY_DURATION_MS, once.
                val entry = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    entry.animateTo(1f, tween(PlaybackLoadingMotion.ENTRY_DURATION_MS, easing = LinearOutSlowInEasing))
                }
                PlaybackLoadingScreen(
                    state = state,
                    artwork = PromoArt.backdrop(sintel.slug),
                    logo = null,
                    title = sintel.name,
                    formatSize = { formatFileSize(it) },
                    entryProgress = entry.value,
                )
            }
        }

        // Theo's phone again, at the trailer's 3x, is already sharp enough; the Downloads run too.

        if (wants("player-state")) exportPlayerStates(failures)

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @androidx.compose.runtime.Composable
    private fun WizardModeStep(mode: PlaybackMode) {
        val plan = SetupWizardPlan(
            playbackModeName = mode.name,
            socialEnabled = true,
            offerSocialIdentity = true,
            downloadModeName = DownloadMode.AUTOMATIC.name,
            isPhone = false,
        )
        SetupWizardDesktopLayout(
            step = SetupStep.PlaybackMode,
            plan = plan,
            specimen = SetupSpecimen.Diagram,
            dismissible = true,
            onDismiss = {},
            playbackMode = mode,
            posterWidthDp = 126,
            posterCornerRadiusDp = 8,
            landscapeCards = false,
            showCardTitles = true,
            heroEnabled = true,
            continueWatchingStyle = ContinueWatchingSectionStyle.Card,
            useEpisodeThumbnails = true,
            blurNextUp = false,
            backgroundMode = MetaScreenBackgroundMode.Cinematic,
            episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
            blurUnwatchedEpisodes = false,
            tabLayout = false,
            nextUpLabel = "Next episode",
            topInset = 0.dp,
            bottomInset = 0.dp,
            onBack = {},
            onAdvance = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            SetupStepBody(
                step = SetupStep.PlaybackMode,
                goingForward = true,
                playbackMode = mode,
                languageStrictness = LanguageStrictness.REQUIRE,
                dynamicRangePolicy = DynamicRangePolicy.ANY,
                qualityCeilingMbps = 0,
                preferredAudioLanguage = AudioLanguageOption.DEVICE,
                preferredSubtitleLanguage = SubtitleLanguageOption.NONE,
                posterWidthDp = 126,
                landscapeCards = false,
                selectedTheme = AppTheme.WHITE,
                amoledEnabled = false,
                socialEnabled = true,
                socialProbeUnknown = false,
                socialSignedIn = true,
                socialHandle = "maya",
                socialHandleBusy = false,
                socialHandleMessage = null,
                onSocialEnabledChange = {},
                onSocialHandleChange = {},
                onSaveSocialHandle = {},
                sources = SetupSourcesState(),
                existingSourceName = null,
                sourcesActions = SetupSourcesActions(),
                downloadMode = DownloadMode.AUTOMATIC,
                downloadPolicy = DownloadPolicy(mode = DownloadMode.AUTOMATIC),
                downloadSetupVariant = downloadSetupVariant(plan),
            )
        }
    }

    // --- the desktop player's panels -----------------------------------------------------------

    /** `PlayerScreenRuntimeUi.formatStreamVideoSize` with the English `streams_size`, restated (it is private and composable). */
    private fun size(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val label = if (gib >= 1.0) "${kotlin.math.round(gib * 10.0) / 10.0} GB" else "${kotlin.math.round(bytes / 1048576.0).toInt()} MB"
        return "SIZE $label" // streams_size
    }

    /**
     * The in-player Sources and Subtitles panels: the same page, fed the rows
     * `buildPlayerControlSourceItems` and `buildPlayerControlSubtitleSelection` build from these streams
     * and tracks. The page opens its panels itself when their buttons are clicked; `tools/reel_player_shot.mjs`
     * does the clicking.
     */
    private fun exportPlayerStates(failures: MutableList<String>) {
        runCatching {
            val base = promo.playerBase()
            val current = archive[1]
            val sources = groups.flatMap { g -> g.streams.map { g.addonId to it } }.mapIndexed { i, (filter, s) ->
                PlayerControlSourceItem(
                    index = i,
                    filterId = filter,
                    label = s.streamLabel,
                    subtitle = s.streamSubtitle.orEmpty(),
                    addonName = s.addonName,
                    isCurrent = s == current,
                    formattedSize = size(s.behaviorHints.videoSize),
                )
            }
            val filters = listOf(com.nuvio.app.features.player.PlayerControlFilterItem(id = "", label = base.allFilterLabel, isSelected = true)) +
                groups.map { com.nuvio.app.features.player.PlayerControlFilterItem(id = it.addonId, label = it.addonName) }
            // Sintel ships subtitles in many languages; these are the built-in tracks of the demo release.
            val tracks = listOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "nl" to "Dutch", "pt" to "Portuguese", "pl" to "Polish")
            val languages = listOf(PlayerControlSubtitleLanguageItem(key = "__off__", label = base.noneLabel)) +
                tracks.map { (code, label) -> PlayerControlSubtitleLanguageItem(key = code, label = label, count = 1, isSelected = code == "en") }
            val options = tracks.mapIndexed { i, (code, label) ->
                PlayerControlSubtitleOptionItem(
                    id = "builtin-$i", languageKey = code, kind = "builtIn", index = i,
                    sourceLabel = "Built-in", title = label, isSelected = code == "en",
                )
            }
            val states = mapOf(
                "sources" to base.copy(sourceFilters = filters, sourceItems = sources),
                "subtitles" to base.copy(
                    subtitleLanguageItems = languages,
                    subtitleOptionItems = options,
                    selectedSubtitleLanguageKey = "en",
                    selectedSubtitleOptionId = "builtin-0",
                ),
            )
            val dir = File(promo.outDir, "reel/player").apply { mkdirs() }
            states.forEach { (name, state) -> File(dir, "$name.json").writeText(state.toControlsJson(isFullscreen = true)) }
        }.onFailure { e ->
            e.printStackTrace()
            failures += "player-state: ${e::class.simpleName}: ${e.message}"
        }
    }
}
