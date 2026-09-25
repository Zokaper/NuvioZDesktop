package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.streams.StreamBadgePlacement
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamItem
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the Phase 9 stage 6 download flow surfaces and writes PNGs to
 * `composeApp/build/download-flow-render/`, at two phone sizes and two desktop windows.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*DownloadFlowRenderHarness"
 * ```
 *
 * Each dialog is drawn through its **production composable** with `LocalDownloadFlowInline`, which
 * draws the dialog's surface in place instead of opening a window - the only difference from the
 * app. What to look for: nothing wraps that should not (resolution names, sizes, "Continue ·
 * 22 episodes"), a long title ellipsizes, the season chips flow, and the primary button is never
 * squeezed by the secondary one.
 */
class DownloadFlowRenderHarness {
    private val outputDir = File("build/download-flow-render")
    private val gb = 1_000_000_000L

    private val sizes = listOf(
        "phone" to (360 to 780),
        "large-phone" to (420 to 900),
        "desktop" to (1280 to 820),
        "desktop-fhd" to (1920 to 1080),
    )

    private val title = DownloadTitleRef(
        parentMetaId = "tt1",
        parentMetaType = "series",
        contentType = "series",
        title = "The Remarkably Long-Named Chronicles of Everything",
    )

    private val seasons = listOf(
        DownloadFlowRules.SeasonChoice(0, 4, 4),
        DownloadFlowRules.SeasonChoice(1, 22, 0),
        DownloadFlowRules.SeasonChoice(2, 22, 0),
        DownloadFlowRules.SeasonChoice(3, 22, 9),
        DownloadFlowRules.SeasonChoice(4, 22, 22),
        DownloadFlowRules.SeasonChoice(5, 22, 22),
        DownloadFlowRules.SeasonChoice(6, 22, 22),
        DownloadFlowRules.SeasonChoice(7, 22, 22),
        DownloadFlowRules.SeasonChoice(8, 22, 22),
        DownloadFlowRules.SeasonChoice(9, 22, 22),
        DownloadFlowRules.SeasonChoice(10, 22, 22),
        DownloadFlowRules.SeasonChoice(11, 18, 18),
    )

    private val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
        "seasons" to {
            DownloadSeasonChooserDialog(
                step = DownloadFlowStep.ChooseSeasons(title, seasons, setOf(3, 4, 5, 6, 7, 8, 9, 10, 11), unwatchedOnly = true),
                onChange = { _, _ -> },
                onContinue = {},
                onDismiss = {},
            )
        },
        "finding" to {
            DownloadFindingSourcesDialog(DownloadFlowStep.FindingSources(title, 8, 22), onDismiss = {})
        },
        "resolution-single" to {
            DownloadResolutionDialog(
                step = DownloadFlowStep.ChooseResolution(
                    title = title,
                    scope = DownloadScope.Episode(3, 4),
                    targetCount = 1,
                    rows = listOf(
                        DownloadResolutionRow(2160, 14_200_000_000L, 0, 1, 0, overLimit = true, detail = "HEVC · DV · Torrentio"),
                        DownloadResolutionRow(1080, 1_900_000_000L, 0, 1, 0, overLimit = false, detail = "HEVC · Torrentio"),
                        DownloadResolutionRow(720, 850_000_000L, 0, 1, 0, overLimit = false, detail = "AVC · MediaFusion"),
                        DownloadResolutionRow(480, 0L, 1, 1, 0, overLimit = false, detail = null),
                    ),
                    preselectedHeight = 1080,
                    offersChooseManually = true,
                ),
                onDownload = {},
                onChooseManually = {},
                onDismiss = {},
            )
        },
        "resolution-season" to {
            DownloadResolutionDialog(
                step = DownloadFlowStep.ChooseResolution(
                    title = title,
                    scope = DownloadScope.Season(4),
                    targetCount = 22,
                    rows = listOf(
                        DownloadResolutionRow(2160, 180 * gb, 2, 18, 4, overLimit = true, detail = null),
                        DownloadResolutionRow(1080, 24 * gb, 0, 22, 0, overLimit = false, detail = null),
                        DownloadResolutionRow(720, 9 * gb, 0, 20, 2, overLimit = false, detail = null),
                    ),
                    preselectedHeight = 1080,
                    offersChooseManually = false,
                ),
                onDownload = {},
                onChooseManually = {},
                onDismiss = {},
            )
        },
        "nothing-cached" to {
            DownloadNothingFoundDialog(
                DownloadFlowStep.NothingToDownload(title, DownloadEntryDecisionKind.NOTHING_CACHED, offersChooseManually = false),
                onCheckAgain = {},
                onChooseManually = {},
                onDismiss = {},
            )
        },
        "no-sources" to {
            DownloadNothingFoundDialog(
                DownloadFlowStep.NothingToDownload(title, DownloadEntryDecisionKind.NO_SOURCES, offersChooseManually = false),
                onCheckAgain = {},
                onChooseManually = {},
                onDismiss = {},
            )
        },
        "free-space" to {
            DownloadFreeSpaceDialog(
                DownloadFlowStep.NotEnoughSpace(title, neededBytes = 48 * gb, freeBytes = 17 * gb, fitCount = 7, totalCount = 22),
                onDownloadWhatFits = {},
                onDismiss = {},
            )
        },
        "mobile-data" to {
            DownloadMobileDataDialog(onUseMobileData = {}, onWait = {}, onDismiss = {})
        },
        "delete-confirm" to {
            DownloadDeleteConfirmDialog(what = "${title.title} · Season 4", onConfirm = {}, onDismiss = {})
        },
        "choose-sources" to {
            val batch = chooseSourcesBatch()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ChooseSourcesSummary(batch, onPickTheRest = {})
                batch.entries.forEach { ChooseSourcesRow(it, onPick = {}) }
            }
        },
        "source-list-uncached" to {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StreamCard(
                    stream = StreamItem(name = "Torrentio\n1080p", description = "Show.S04E03.1080p.WEB-DL.x265 · 1.9 GB", addonName = "Torrentio", addonId = "t"),
                    enabled = true,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = false,
                    showAddonLogo = true,
                    badgePlacement = StreamBadgePlacement.TOP,
                    onClick = {},
                )
                StreamCard(
                    stream = StreamItem(name = "Torrentio\n2160p", description = "Show.S04E03.2160p.REMUX.DV · 38 GB", addonName = "Torrentio", addonId = "t"),
                    enabled = false,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = false,
                    showAddonLogo = true,
                    badgePlacement = StreamBadgePlacement.TOP,
                    onClick = {},
                    disabledNote = "Not cached on your debrid service",
                )
            }
        },
    )

    private fun chooseSourcesBatch(): DownloadBatch {
        val targets = (1..6).map { DownloadTarget("tt1:4:$it", "Episode title number $it, which runs a little long", "series", 4, it) }
        val entries = targets.mapIndexed { index, target ->
            when (index) {
                0 -> DownloadBatchCoordinator.manualPickEntry(target).copy(
                    state = DownloadBatchEntryState.QUEUED,
                    streamTitle = "Show.S04E01.1080p.WEB-DL.x265",
                )
                1 -> DownloadBatchCoordinator.manualPickEntry(target).copy(state = DownloadBatchEntryState.DISCOVERING)
                else -> DownloadBatchCoordinator.manualPickEntry(target)
            }
        }
        return DownloadBatch(
            id = "b",
            scope = DownloadScope.Season(4),
            contentType = "series",
            parentMetaId = "tt1",
            parentMetaType = "series",
            title = title.title,
            sourcePolicySnapshot = DownloadSourcePolicy(),
            entries = entries,
            createdAtEpochMs = 0L,
        )
    }

    @Test
    fun renderEveryDownloadFlowSurface() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((sceneName, content) in scenes) {
            for ((sizeName, size) in sizes) {
                render("$sceneName-$sizeName", size.first, size.second, failures, content)
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
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
                    CompositionLocalProvider(LocalDownloadFlowInline provides true) {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(if (phone) 24.dp else 0.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // A dialog is at most 560dp wide in the app (BasicAlertDialog's own cap).
                            Box(Modifier.widthIn(max = 560.dp)) { content() }
                        }
                    }
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
