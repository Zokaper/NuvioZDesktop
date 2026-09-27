package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the Phase 9 stage 7 Downloads screen - storage, the watched-cleanup suggestion, Needs
 * you, the queue with a season as one row, a preparing batch and On this device - plus the detail
 * sheet, into `composeApp/build/downloads-screen-render/`, at two phone sizes and two desktop
 * windows.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*DownloadsScreenRenderHarness"
 * ```
 *
 * The screen body is the production `downloadsRootContent` inside the production `NuvioScreen`,
 * so the width cap and the section order are the app's. The data is a plausible library with
 * generated artwork ([DownloadRenderArt]). What to look for: rows lead with artwork; warning colour
 * sits on icons, not paragraphs; on desktop the column stops growing and centres; "Download now
 * anyway" reads as part of its episode.
 */
class DownloadsScreenRenderHarness {
    private val outputDir = File("build/downloads-screen-render")
    private val gb = 1_000_000_000L
    private val mb = 1_000_000L
    private val now = 1_000_000L

    private val sizes = listOf(
        "phone" to (360 to 780),
        "large-phone" to (420 to 900),
        "desktop-narrow" to (960 to 720),
        "desktop" to (1280 to 820),
        "desktop-1440" to (1440 to 900),
        "desktop-fhd" to (1920 to 1080),
    )

    private fun item(
        id: String,
        slug: String,
        title: String,
        status: DownloadStatus,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        activity: DownloadActivity? = null,
        downloaded: Long = 0L,
        total: Long? = null,
        position: Long = 0L,
        failure: DownloadFailureKind? = null,
        pause: DownloadPauseReason? = null,
        attempts: Int = 0,
        retryAt: Long? = null,
        error: String? = null,
        stream: String = "WEB-DL 1080p",
        updated: Long = 0L,
    ) = DownloadItem(
        id = id,
        ownerProfileId = 1,
        contentType = if (episode != null) "series" else "movie",
        parentMetaId = "tt-$slug",
        parentMetaType = if (episode != null) "series" else "movie",
        videoId = "tt-$slug:${season ?: 0}:${episode ?: 0}",
        title = title,
        logo = DownloadRenderArt.logo(slug),
        poster = DownloadRenderArt.poster(slug),
        background = DownloadRenderArt.backdrop(slug),
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = episode?.let { DownloadRenderArt.still(slug, it) },
        streamTitle = "$title ${season?.let { "S0${it}E0$episode " }.orEmpty()}$stream x265",
        providerName = "Torrentio",
        fileName = "$id.mkv",
        status = status,
        activity = activity,
        pauseReason = pause,
        failureKind = failure,
        downloadedBytes = downloaded,
        totalBytes = total,
        queuePosition = position,
        attemptCount = attempts,
        nextRetryAtEpochMs = retryAt,
        errorMessage = error,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = updated,
        localFileUri = if (status == DownloadStatus.Completed) "file:///$id.mkv" else null,
    )

    private val severance = "Severance"
    private val queueItems = listOf(
        item("sv3", "severance", severance, DownloadStatus.Downloading, 2, 3, "Who Is Alive?", DownloadActivity.TRANSFERRING, downloaded = 1_240 * mb, total = 2_100 * mb, position = 0),
        item("sv4", "severance", severance, DownloadStatus.Queued, 2, 4, "Woe's Hollow", DownloadActivity.RETRY_BACKOFF, position = 1, attempts = 2, retryAt = now + 4_000, error = "HTTP 503"),
        item("sv5", "severance", severance, DownloadStatus.Queued, 2, 5, "Trojan's Horse", DownloadActivity.WAITING_FOR_WIFI, position = 2),
        item("sv6", "severance", severance, DownloadStatus.Paused, 2, 6, "Attila", DownloadActivity.USER_PAUSED, pause = DownloadPauseReason.User, downloaded = 610 * mb, total = 1_950 * mb, position = 3),
        item("dune", "dune-part-two", "Dune: Part Two", DownloadStatus.Queued, activity = DownloadActivity.WAITING_FOR_CONNECTION, position = 4),
    )
    private val mfSeason3 = listOf(
        "Dude Ranch", "When Good Kids Go Bad", "Phil on Wire", "Door to Door", "Hit and Run", "Go Bullfrogs!",
        "Treehouse", "After the Fire", "Punkin Chunkin", "Express Christmas", "Lifetime Supply", "Egg Drop",
        "Little Bo Bleep", "Me? Jealous?", "Aunt Mommy", "Virgin Territory", "Leap Day", "Send Out the Clowns",
        "Election Day", "The Last Walt", "Planes, Trains and Cars", "Disneyland", "Tableau Vivant", "Baby on Board",
    )
    private val mfOverviews = listOf(
        "The family heads to a dude ranch for a vacation, where Jay tries to teach Manny to be a cowboy.",
        "Claire and Phil suspect the kids are up to something; Mitchell and Cam meet a new neighbour.",
        "Phil takes up tightrope walking to prove a point, while Claire runs for town council.",
        "Claire goes door to door for signatures and Jay gets into a feud over a golf cart.",
        "A stop sign at a dangerous corner sets Claire off on a campaign. Cam coaches a football team.",
        "Cam is determined to lead his team to victory, and Phil tries to be a cool dad at a college visit.",
        "Phil and Luke build a treehouse; Mitchell and Cam go out for a night without the baby.",
        "A fire at a neighbour's house brings the family together to help, with mixed results.",
    )

    /**
     * The library the "many episodes" pass is about: Modern Family with two whole seasons and most
     * of a third on the device, S1-S2 watched, S3 watched to E6 and E7 half-way through, S3E11-E12
     * still downloading. Plus a finished Severance season and a film.
     */
    private val modernFamily: List<DownloadItem> = buildList {
        for (season in 1..2) {
            for (ep in 1..24) {
                add(item("mf$season-$ep", "modern-family", "Modern Family", DownloadStatus.Completed, season, ep, "Episode $ep", total = (1_720 + ep * 9) * mb, updated = 10L))
            }
        }
        mfSeason3.take(12).forEachIndexed { index, name ->
            val ep = index + 1
            add(
                when (ep) {
                    11 -> item("mf3-$ep", "modern-family", "Modern Family", DownloadStatus.Downloading, 3, ep, name, DownloadActivity.TRANSFERRING, downloaded = 840 * mb, total = 1_880 * mb, position = 10)
                    12 -> item("mf3-$ep", "modern-family", "Modern Family", DownloadStatus.Queued, 3, ep, name, DownloadActivity.QUEUED_FOR_SLOT, position = 11)
                    else -> item("mf3-$ep", "modern-family", "Modern Family", DownloadStatus.Completed, 3, ep, name, total = (1_800 + ep * 13) * mb, updated = 20L)
                },
            )
        }
    }
    private val completed = listOf(
        item("sv1", "severance", severance, DownloadStatus.Completed, 2, 1, "Hello, Ms. Cobel", total = 2_050 * mb, updated = 5L),
        item("sv2", "severance", severance, DownloadStatus.Completed, 2, 2, "Goodbye, Mrs. Selvig", total = 1_980 * mb, updated = 5L),
        item("eeaao", "everything-everywhere", "Everything Everywhere All at Once", DownloadStatus.Completed, total = 9_400 * mb, updated = 30L),
    ) + modernFamily.filter { it.status == DownloadStatus.Completed }

    /** More finished titles, so the idle desktop grid has rows to lay out. */
    private val moreLibrary = listOf(
        item("dune-done", "dune-part-two", "Dune: Part Two", DownloadStatus.Completed, total = 11_200 * mb, updated = 25L),
        item("past-done", "past-lives", "Past Lives", DownloadStatus.Completed, total = 4_300 * mb, updated = 24L),
        item("opp-done", "oppenheimer", "Oppenheimer", DownloadStatus.Completed, total = 14_800 * mb, updated = 23L),
    ) + (1..8).map { ep ->
        item("sh$ep", "shogun", "Shogun", DownloadStatus.Completed, 1, ep, "Episode $ep", total = 3_100 * mb, updated = 22L)
    } + (1..6).map { ep ->
        item("bear$ep-done", "the-bear", "The Bear", DownloadStatus.Completed, 2, ep, "Episode $ep", total = 1_400 * mb, updated = 21L)
    }

    private val metadata = mapOf(
        "tt-modern-family" to DownloadTitleMetadata(
            parentMetaId = "tt-modern-family",
            description = "Three different but related families face trials and tribulations in their own uniquely comedic ways: " +
                "Jay and his much younger wife, his daughter's family of five, and his son's, with their adopted daughter.",
            releaseInfo = "2009–2020",
            genres = listOf("Comedy", "Family", "Romance"),
            imdbRating = "8.5",
            ageRating = "TV-PG",
            episodes = (1..3).flatMap { season ->
                (1..24).map { ep ->
                    DownloadEpisodeMetadata(
                        season = season,
                        episode = ep,
                        title = if (season == 3) mfSeason3[ep - 1] else "Episode $ep",
                        overview = mfOverviews[(ep + season) % mfOverviews.size],
                        thumbnail = DownloadRenderArt.still("modern-family", season * 100 + ep),
                        runtimeMinutes = 21 + (ep % 3),
                    )
                }
            },
        ),
        "tt-everything-everywhere" to DownloadTitleMetadata(
            parentMetaId = "tt-everything-everywhere",
            releaseInfo = "2022",
            runtime = "2h 19m",
            genres = listOf("Action", "Adventure", "Comedy"),
        ),
    )

    private val watch: (DownloadItem) -> DownloadWatchState = { item ->
        val season = item.seasonNumber
        val ep = item.episodeNumber ?: 0
        when {
            item.parentMetaId != "tt-modern-family" -> DownloadWatchState.Unwatched
            season != null && season < 3 -> DownloadWatchState(watched = true)
            season == 3 && ep <= 6 -> DownloadWatchState(watched = true)
            season == 3 && ep == 7 -> DownloadWatchState(fraction = 0.42f, updatedAtEpochMs = 99L, remainingMs = 12 * 60_000L)
            else -> DownloadWatchState.Unwatched
        }
    }

    private fun entry(ep: Int, name: String, state: DownloadBatchEntryState, decision: DownloadEntryDecisionKind?, usable: Boolean? = true, bytes: Long = 5_500 * mb / 2) =
        DownloadBatchEntry(
            id = "bear$ep", videoId = "tt-the-bear:3:$ep", title = name, season = 3, episode = ep,
            state = state, decision = decision, hasUsableSources = usable,
            selection = if (state == DownloadBatchEntryState.APPROVAL_NEEDED) {
                SourceSelectionResult.ApprovalNeeded("https://a/$ep.mkv", SourceFacts(sizeBytes = bytes), AddonSourceKey("a", "u"), 0L, "r")
            } else {
                null
            },
        )

    private val bear = DownloadBatch(
        id = "bear", ownerProfileId = 1, scope = DownloadScope.Season(3), contentType = "series",
        parentMetaId = "tt-the-bear", parentMetaType = "series", title = "The Bear",
        poster = DownloadRenderArt.poster("the-bear"), background = DownloadRenderArt.backdrop("the-bear"),
        sourcePolicySnapshot = DownloadSourcePolicy(), createdAtEpochMs = 0L,
        entries = listOf(
            entry(1, "Tomorrow", DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.OVER_LIMIT),
            entry(2, "Next", DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.OVER_LIMIT),
            entry(3, "Doors", DownloadBatchEntryState.SKIPPED, DownloadEntryDecisionKind.NOTHING_CACHED, usable = false),
        ),
    )

    private val slowHorses = DownloadBatch(
        id = "sh", ownerProfileId = 1, scope = DownloadScope.Season(4), contentType = "series",
        parentMetaId = "tt-slow-horses", parentMetaType = "series", title = "Slow Horses",
        poster = DownloadRenderArt.poster("slow-horses"),
        sourcePolicySnapshot = DownloadSourcePolicy(), createdAtEpochMs = 0L,
        entries = (1..6).map { ep ->
            DownloadBatchEntry(
                id = "sh$ep", videoId = "tt-slow-horses:4:$ep", title = "Episode $ep", season = 4, episode = ep,
                state = if (ep <= 2) DownloadBatchEntryState.READY else DownloadBatchEntryState.DISCOVERING,
            )
        },
    )

    /** Assisted "choose when ready": one season still finding its sources, one ready to choose. */
    private fun choiceBatch(
        id: String,
        slug: String,
        title: String,
        season: Int,
        episodes: Int,
        found: Int,
        early: Int? = null,
        checking: Boolean = false,
    ) = DownloadBatch(
        id = id, ownerProfileId = 1, scope = DownloadScope.Season(season), contentType = "series",
        parentMetaId = "tt-$slug", parentMetaType = "series", title = title,
        poster = DownloadRenderArt.poster(slug),
        sourcePolicySnapshot = DownloadSourcePolicy(), createdAtEpochMs = 0L,
        awaitsQualityChoice = !checking,
        earlyResolutionHeight = early,
        entries = (1..episodes).map { ep ->
            DownloadBatchEntry(
                id = "$id$ep", videoId = "tt-$slug:$season:$ep", title = "Episode $ep", season = season, episode = ep,
                state = when {
                    checking && ep <= found -> DownloadBatchEntryState.READY
                    checking -> DownloadBatchEntryState.RESOLVING
                    ep <= found -> DownloadBatchEntryState.AWAITING_CHOICE
                    else -> DownloadBatchEntryState.DISCOVERING
                },
            )
        },
    )

    private val lanterns = choiceBatch("ln", "lanterns", "Lanterns", 1, episodes = 8, found = 8)
    private val pluribus = choiceBatch("pb", "pluribus", "Pluribus", 1, episodes = 22, found = 7, early = 1080)
    /** "Choose now", discovery done, the sources being checked (physical `.56`: this row was missing). */
    private val shrinking = choiceBatch("sk", "shrinking", "Shrinking", 2, episodes = 12, found = 5, early = 720, checking = true)

    private val attentionItems = listOf(
        item("opp", "oppenheimer", "Oppenheimer", DownloadStatus.Failed, failure = DownloadFailureKind.STORAGE, total = 31 * gb),
        item("pl", "past-lives", "Past Lives", DownloadStatus.Failed, attempts = 5, error = "HTTP 403"),
    )

    @Composable
    private fun Screen(windowWidth: Int, phone: Boolean, libraryOnly: Boolean = false) {
        // As `DownloadsScreen` decides it, in a desktop window with its 68dp sidebar.
        val available = if (phone) windowWidth else windowWidth - 68
        val wide = !phone && downloadsUsesWideLayout(available.dp)
        val libraryFirst = wide && libraryOnly
        val contentMaxWidth = if (libraryFirst) DownloadsLibraryMaxWidth else DownloadsContentMaxWidth
        val libraryColumns = when {
            libraryFirst -> downloadsLibraryColumns(minOf(available.dp - DownloadsWideGutter * 2, DownloadsLibraryMaxWidth))
            minOf(available - 32, 880) >= 600 -> 2
            else -> 1
        }
        val items = if (libraryOnly) completed + moreLibrary else queueItems + completed + moreLibrary + attentionItems + modernFamily.filter { it.status != DownloadStatus.Completed }
        val batches = if (libraryOnly) emptyList() else listOf(bear, slowHorses, lanterns, pluribus, shrinking)
        val attention = AttentionGrouping.group(items, batches, now)
        val unfinished = items.filter {
            it.status != DownloadStatus.Completed && DownloadPresenter.item(it, now).phase != DownloadUserPhase.NEEDS_YOU
        }
        val queue = DownloadQueueGrouping.group(unfinished, items, batches, now)
        val content: LazyListScope.(DownloadsPart) -> Unit = { part ->
            downloadsRootContent(
                uiState = DownloadsUiState(items),
                batches = batches,
                storage = DownloadStorageSummary(usedBytes = 42 * gb, freeBytes = 118 * gb),
                attention = attention,
                queue = queue,
                cleanup = WatchedCleanup(completed.filter { it.parentMetaId == "tt-modern-family" }.take(3), 5_620 * mb),
                nowEpochMs = now,
                onOpenDownload = {},
                onOpenShow = { _, _ -> },
                onRequestTitleDeletion = {},
                onAttentionAction = { _, _ -> },
                onChooseMember = {},
                onOpenDetail = {},
                onReviewCleanup = {},
                onCancelGroup = {},
                initiallyExpandedGroups = true,
                part = part,
                metadata = metadata,
                watch = watch,
                libraryColumns = libraryColumns,
                contentMaxWidth = contentMaxWidth,
            )
        }
        if (libraryFirst) {
            // Nothing under way: one wide column, the library as the page.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(68.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow))
                NuvioScreen(topPadding = 0.dp, horizontalPadding = DownloadsWideGutter) {
                    stickyHeader {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                            NuvioScreenHeader(modifier = Modifier.downloadsContentWidth(contentMaxWidth), title = "Downloads")
                        }
                    }
                    content(DownloadsPart.All)
                }
            }
        } else if (wide) {
            // The desktop window: its sidebar (collapsed, 68dp), then the production two-pane layout.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(68.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow))
                DownloadsWideLayout(
                    header = { width -> NuvioScreenHeader(modifier = width, title = "Downloads") },
                    content = content,
                )
            }
        } else {
            NuvioScreen(topPadding = 0.dp) {
                stickyHeader {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                        NuvioScreenHeader(modifier = Modifier.downloadsContentWidth(), title = "Downloads")
                    }
                }
                content(DownloadsPart.All)
            }
        }
    }

    /** A show's own page: Modern Family, Season 3 open (next up is S3 E7). */
    @Composable
    private fun Show(season: Int? = null) {
        DownloadedShowPage(
            episodes = modernFamily,
            metadata = metadata["tt-modern-family"],
            watch = watch,
            nowEpochMs = now,
            selectedSeason = season,
            onSelectSeason = {},
            onBack = {},
            onPlay = {},
            onOpenDetail = {},
            onDeleteTitle = {},
            onDeleteSeason = {},
            onDeleteWatched = { _, _ -> },
            onDeleteEpisode = {},
        )
    }

    @Composable
    private fun Detail() {
        val retrying = queueItems[1]
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            DownloadDetailContent(
                item = retrying,
                presentation = DownloadPresenter.item(retrying, now),
                nowEpochMs = now,
                onPause = {}, onResume = {}, onChange = {}, onDownloadNext = {}, onDelete = {},
            )
        }
    }

    /** Settings -> Downloads (stage 8): an unanswered mode on a derived Assisted, device defaults. */
    @Composable
    private fun Settings() {
        NuvioScreen(topPadding = 0.dp) {
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    NuvioScreenHeader(title = "Downloads")
                }
            }
            downloadsSettingsContent(
                addons = emptyList(),
                policy = DownloadSourcePolicy(),
                downloadPolicy = DownloadPolicy(),
                effectiveMode = DownloadMode.ASSISTED,
                deviceSettings = DownloadDeviceSettings(),
                onOpenFolder = {},
            )
        }
    }

    @Test
    fun renderTheDownloadsScreen() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((sizeName, size) in sizes) {
            val phone = size.first < 600
            // Desktop: the window's own height - the panes scroll, as they do in the app.
            // As the app decides it: two library cards to a row once the one column fits two.
            render("screen-$sizeName", size.first, if (phone) (size.second * 2.4).toInt() else size.second, failures) { Screen(size.first, phone) }
            // The whole main pane, for review: the window above shows only what fits.
            if (!phone) render("screen-$sizeName-full", size.first, (size.second * 2.4).toInt(), failures) { Screen(size.first, phone) }
            // Everything finished: the library alone, as most visits to Downloads look.
            render("library-$sizeName", size.first, size.second, failures) { Screen(size.first, phone, libraryOnly = true) }
            // A show's page: the window, then the whole season.
            render("show-$sizeName", size.first, size.second, failures) { Show() }
            render("show-$sizeName-full", size.first, if (phone) 3000 else 2200, failures) { Show() }
            render("show-$sizeName-season1", size.first, size.second, failures) { Show(season = 1) }
            render("detail-$sizeName", size.first, 520, failures) { Detail() }
            render("settings-$sizeName", size.first, if (phone) 1500 else 1300, failures) { Settings() }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun render(name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, content: @Composable () -> Unit) {
        val phone = widthDp < 600
        val density = Density(if (phone) 2f else 1f)
        runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density.density).toInt(),
                height = (heightDp * density.density).toInt(),
                density = density,
            ) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.WHITE,
                    amoled = false,
                    desktopUiScale = if (phone) 1f else desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    WithFixtureArt {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
            try {
                scene.render(0L)
                scene.render(16_000_000L)
                val image = scene.render(600_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }
}
