package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the Phase 9 stage 7 Downloads screen pieces - storage bar, Needs you cards, the watched
 * cleanup suggestion, the queue with a season as one row, a lone film row and the detail sheet -
 * into `composeApp/build/downloads-screen-render/`, at two phone sizes and two desktop windows.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*DownloadsScreenRenderHarness"
 * ```
 *
 * The pieces are the production composables; only the LazyColumn around them is the harness's,
 * in the screen's order. What to look for: every status line is plain words (no retry counters, no
 * engine names outside the detail), nothing wraps inside a button, the season row reads as one row.
 */
class DownloadsScreenRenderHarness {
    private val outputDir = File("build/downloads-screen-render")
    private val gb = 1_000_000_000L
    private val now = 1_000_000L

    private val sizes = listOf(
        "phone" to (360 to 780),
        "large-phone" to (420 to 900),
        "desktop" to (1280 to 820),
        "desktop-fhd" to (1920 to 1080),
    )

    private fun item(
        id: String,
        episode: Int?,
        status: DownloadStatus,
        activity: DownloadActivity? = null,
        downloaded: Long = 0L,
        total: Long? = null,
        position: Long = 0L,
        title: String = "The Remarkably Long-Named Chronicles of Everything",
        parent: String = "tt1",
        failure: DownloadFailureKind? = null,
        sizeApproval: Boolean = false,
        pause: DownloadPauseReason? = null,
        attempts: Int = 0,
        retryAt: Long? = null,
        error: String? = null,
    ) = DownloadItem(
        id = id,
        ownerProfileId = 1,
        contentType = if (episode != null) "series" else "movie",
        parentMetaId = parent,
        parentMetaType = if (episode != null) "series" else "movie",
        videoId = "$parent:$episode",
        title = title,
        seasonNumber = episode?.let { 2 },
        episodeNumber = episode,
        episodeTitle = episode?.let { "An episode title that goes on for a while, number $it" },
        streamTitle = "Show.S02E0$episode.1080p.WEB-DL.x265-GROUP",
        providerName = "Torrentio",
        fileName = "$id.mkv",
        status = status,
        activity = activity,
        pauseReason = pause,
        failureKind = failure,
        downloadedBytes = downloaded,
        totalBytes = total,
        queuePosition = position,
        sizeApprovalRequired = sizeApproval,
        attemptCount = attempts,
        nextRetryAtEpochMs = retryAt,
        errorMessage = error,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private val queueItems = listOf(
        item("e3", 3, DownloadStatus.Downloading, DownloadActivity.TRANSFERRING, downloaded = 900_000_000L, total = 2_100_000_000L, position = 0),
        item("e4", 4, DownloadStatus.Queued, DownloadActivity.RETRY_BACKOFF, position = 1, attempts = 2, retryAt = now + 4_000, error = "HTTP 503"),
        item("e5", 5, DownloadStatus.Queued, DownloadActivity.WAITING_FOR_WIFI, position = 2),
        item("e6", 6, DownloadStatus.Paused, DownloadActivity.USER_PAUSED, pause = DownloadPauseReason.User, downloaded = 300_000_000L, total = 2_000_000_000L, position = 3),
        item("m", null, DownloadStatus.Queued, DownloadActivity.WAITING_FOR_CONNECTION, position = 4, title = "A Film", parent = "tt9"),
    )
    private val completed = listOf(
        item("e1", 1, DownloadStatus.Completed, total = 2 * gb),
        item("e2", 2, DownloadStatus.Completed, total = 2 * gb),
    )

    private fun entry(ep: Int, state: DownloadBatchEntryState, decision: DownloadEntryDecisionKind?, usable: Boolean? = true) =
        DownloadBatchEntry(
            id = "e$ep", videoId = "tt5:$ep", title = "Episode $ep with a long title", season = 1, episode = ep,
            state = state, decision = decision, hasUsableSources = usable,
            selection = if (state == DownloadBatchEntryState.APPROVAL_NEEDED) {
                SourceSelectionResult.ApprovalNeeded("https://a/$ep.mkv", SourceFacts(sizeBytes = 3 * gb), AddonSourceKey("a", "u"), 0L, "r")
            } else {
                null
            },
        )

    private val batch = DownloadBatch(
        id = "b", ownerProfileId = 1, scope = DownloadScope.Season(1), contentType = "series",
        parentMetaId = "tt5", parentMetaType = "series", title = "Another Show",
        sourcePolicySnapshot = DownloadSourcePolicy(), createdAtEpochMs = 0L,
        entries = listOf(
            entry(1, DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.OVER_LIMIT),
            entry(2, DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.OVER_LIMIT),
            entry(3, DownloadBatchEntryState.SKIPPED, DownloadEntryDecisionKind.NOTHING_CACHED, usable = false),
        ),
    )

    private val attentionItems = listOf(
        item("s", null, DownloadStatus.Failed, failure = DownloadFailureKind.STORAGE, title = "A Very Large Film", parent = "tt7"),
        item("g", null, DownloadStatus.Failed, attempts = 5, title = "The Film That Would Not Download", parent = "tt6", error = "HTTP 403"),
    )

    @Composable
    private fun Screen() {
        val attention = AttentionGrouping.group(attentionItems, listOf(batch), now)
        val queue = DownloadQueueGrouping.group(queueItems, completed, now)
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            item { DownloadStorageBar(DownloadStorageSummary(usedBytes = 42 * gb, freeBytes = 118 * gb)) }
            item { DownloadSectionTitle("Needs you") }
            attention.forEach { card -> item { DownloadAttentionCard(card, onAction = {}, onChooseMember = {}) } }
            item { DownloadWatchedCleanupCard(WatchedCleanup(completed, 4 * gb), onReview = {}) }
            item { DownloadSectionTitle("Downloading") }
            queue.forEachIndexed { index, group ->
                item {
                    if (group.isSeason) {
                        DownloadQueueGroupRow(
                            group = group,
                            controls = DownloadGroupControls({}, {}, {}, null, if (index < queue.lastIndex) ({}) else null),
                            onOpenItem = {}, onPauseItem = {}, onResumeItem = {},
                            initiallyExpanded = true,
                        )
                    } else {
                        DownloadQueueItemRow(group.items.single(), group.presentations.single(), now, {}, {}, {})
                    }
                }
            }
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
            render("screen-$sizeName", size.first, size.second * 2, failures) { Screen() }
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
                    // Full width, as NuvioScreen draws it: no cap in production, so none here.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
            try {
                scene.render(0L)
                val image = scene.render(16_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }
}
