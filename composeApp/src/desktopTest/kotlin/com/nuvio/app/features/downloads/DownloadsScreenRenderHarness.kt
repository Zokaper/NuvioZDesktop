package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        "desktop" to (1280 to 820),
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
    ) = DownloadItem(
        id = id,
        ownerProfileId = 1,
        contentType = if (episode != null) "series" else "movie",
        parentMetaId = "tt-$slug",
        parentMetaType = if (episode != null) "series" else "movie",
        videoId = "tt-$slug:${season ?: 0}:${episode ?: 0}",
        title = title,
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
        updatedAtEpochMs = 0L,
    )

    private val severance = "Severance"
    private val queueItems = listOf(
        item("sv3", "severance", severance, DownloadStatus.Downloading, 2, 3, "Who Is Alive?", DownloadActivity.TRANSFERRING, downloaded = 1_240 * mb, total = 2_100 * mb, position = 0),
        item("sv4", "severance", severance, DownloadStatus.Queued, 2, 4, "Woe's Hollow", DownloadActivity.RETRY_BACKOFF, position = 1, attempts = 2, retryAt = now + 4_000, error = "HTTP 503"),
        item("sv5", "severance", severance, DownloadStatus.Queued, 2, 5, "Trojan's Horse", DownloadActivity.WAITING_FOR_WIFI, position = 2),
        item("sv6", "severance", severance, DownloadStatus.Paused, 2, 6, "Attila", DownloadActivity.USER_PAUSED, pause = DownloadPauseReason.User, downloaded = 610 * mb, total = 1_950 * mb, position = 3),
        item("dune", "dune-part-two", "Dune: Part Two", DownloadStatus.Queued, activity = DownloadActivity.WAITING_FOR_CONNECTION, position = 4),
    )
    private val completed = listOf(
        item("sv1", "severance", severance, DownloadStatus.Completed, 2, 1, "Hello, Ms. Cobel", total = 2_050 * mb),
        item("sv2", "severance", severance, DownloadStatus.Completed, 2, 2, "Goodbye, Mrs. Selvig", total = 1_980 * mb),
        item("mf1", "modern-family", "Modern Family", DownloadStatus.Completed, 3, 1, "Dude Ranch", total = 1_850 * mb),
        item("mf2", "modern-family", "Modern Family", DownloadStatus.Completed, 3, 2, "When Good Kids Go Bad", total = 1_870 * mb),
        item("mf3", "modern-family", "Modern Family", DownloadStatus.Completed, 3, 3, "Phil on Wire", total = 1_900 * mb),
        item("eeaao", "everything-everywhere", "Everything Everywhere All at Once", DownloadStatus.Completed, total = 9_400 * mb),
    )

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

    private val attentionItems = listOf(
        item("opp", "oppenheimer", "Oppenheimer", DownloadStatus.Failed, failure = DownloadFailureKind.STORAGE, total = 31 * gb),
        item("pl", "past-lives", "Past Lives", DownloadStatus.Failed, attempts = 5, error = "HTTP 403"),
    )

    @Composable
    private fun Screen() {
        val items = queueItems + completed + attentionItems
        val batches = listOf(bear, slowHorses)
        val attention = AttentionGrouping.group(items, batches, now)
        val unfinished = items.filter {
            it.status != DownloadStatus.Completed && DownloadPresenter.item(it, now).phase != DownloadUserPhase.NEEDS_YOU
        }
        val queue = DownloadQueueGrouping.group(unfinished, completed, now)
        NuvioScreen(topPadding = 0.dp) {
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    NuvioScreenHeader(modifier = Modifier.downloadsContentWidth(), title = "Downloads")
                }
            }
            downloadsRootContent(
                uiState = DownloadsUiState(items),
                batches = batches,
                storage = DownloadStorageSummary(usedBytes = 42 * gb, freeBytes = 118 * gb),
                attention = attention,
                queue = queue,
                cleanup = WatchedCleanup(completed.filter { it.parentMetaId == "tt-modern-family" }, 5_620 * mb),
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
            )
        }
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

    @Test
    fun renderTheDownloadsScreen() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((sizeName, size) in sizes) {
            val phone = size.first < 600
            render("screen-$sizeName", size.first, if (phone) (size.second * 2.4).toInt() else (size.second * 1.9).toInt(), failures) { Screen() }
            render("detail-$sizeName", size.first, 520, failures) { Detail() }
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
