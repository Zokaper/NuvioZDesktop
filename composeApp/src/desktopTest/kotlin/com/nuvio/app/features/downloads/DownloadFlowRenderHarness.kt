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

    private fun ref(slug: String, name: String, type: String = "series") = DownloadTitleRef(
        parentMetaId = "tt-$slug",
        parentMetaType = type,
        contentType = type,
        title = name,
        poster = DownloadRenderArt.poster(slug),
        background = DownloadRenderArt.backdrop(slug),
    )

    // A show watched through season 2 and part of season 3.
    private val title = ref("modern-family", "Modern Family")
    private val bear = ref("the-bear", "The Bear")
    private val dune = ref("dune-part-two", "Dune: Part Two", type = "movie")

    private val seasons = listOf(
        DownloadFlowRules.SeasonChoice(0, 4, 4),
        DownloadFlowRules.SeasonChoice(1, 24, 0),
        DownloadFlowRules.SeasonChoice(2, 24, 0),
        DownloadFlowRules.SeasonChoice(3, 24, 9),
        DownloadFlowRules.SeasonChoice(4, 24, 24),
        DownloadFlowRules.SeasonChoice(5, 24, 24),
        DownloadFlowRules.SeasonChoice(6, 24, 24),
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
            DownloadFindingSourcesDialog(DownloadFlowStep.FindingSources(bear, 4, 10), onDismiss = {})
        },
        "resolution-single" to {
            DownloadResolutionDialog(
                step = DownloadFlowStep.ChooseResolution(
                    title = dune,
                    scope = DownloadScope.Movie,
                    targetCount = 1,
                    rows = listOf(
                        DownloadResolutionRow(2160, 24_600_000_000L, 0, 1, 0, overLimit = true, detail = "HEVC · Dolby Vision · Torrentio"),
                        DownloadResolutionRow(1080, 6_800_000_000L, 0, 1, 0, overLimit = false, detail = "HEVC · WEB-DL · Torrentio"),
                        DownloadResolutionRow(720, 2_900_000_000L, 0, 1, 0, overLimit = false, detail = "AVC · WEB-DL · MediaFusion"),
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
                    title = bear,
                    scope = DownloadScope.Season(3),
                    targetCount = 10,
                    rows = listOf(
                        DownloadResolutionRow(2160, 64 * gb, 2, 8, 2, overLimit = true, detail = null),
                        DownloadResolutionRow(1080, 17 * gb, 0, 10, 0, overLimit = false, detail = null),
                        DownloadResolutionRow(720, 7 * gb, 0, 9, 1, overLimit = false, detail = null),
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
                DownloadFlowStep.NotEnoughSpace(title, neededBytes = 48 * gb, freeBytes = 17 * gb, fitCount = 7, totalCount = 24),
                onDownloadWhatFits = {},
                onDismiss = {},
            )
        },
        "mobile-data" to {
            DownloadMobileDataDialog(onUseMobileData = {}, onWait = {}, onDismiss = {})
        },
        "delete-confirm" to {
            DownloadDeleteConfirmDialog(what = "${title.title} · Season 3", onConfirm = {}, onDismiss = {})
        },
        "choose-sources" to {
            val batch = chooseSourcesBatch()
            // As the screen draws it: the screen's gutter, then the capped content column.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ChooseSourcesContent(batch, onPickTheRest = {}, onPick = {}, modifier = Modifier.downloadsContentWidth(720.dp))
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

    private fun picked(bytes: Long) = SourceSelectionResult.Selected(
        streamUrl = "https://a/x.mkv",
        facts = SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = bytes, releaseQuality = "WEB-DL"),
        addonKey = AddonSourceKey("a", "u"),
        calculatedCapBytes = 0L,
    )

    private fun chooseSourcesBatch(): DownloadBatch {
        val names = listOf("Anjin", "Servants of Two Masters", "Tomorrow Is Tomorrow", "The Eightfold Fence", "Broken to the Fist", "Ladies of the Willow World")
        val targets = names.mapIndexed { index, name -> DownloadTarget("tt-shogun:1:${index + 1}", name, "series", 1, index + 1) }
        val entries = targets.mapIndexed { index, target ->
            when (index) {
                0 -> DownloadBatchCoordinator.manualPickEntry(target).copy(
                    state = DownloadBatchEntryState.QUEUED,
                    streamTitle = "Shogun.S01E01.1080p.WEB-DL.x265",
                    selection = picked(2_300_000_000L),
                )
                1 -> DownloadBatchCoordinator.manualPickEntry(target).copy(
                    state = DownloadBatchEntryState.QUEUED,
                    streamTitle = "Shogun.S01E02.1080p.WEB-DL.x265",
                    selection = picked(2_100_000_000L),
                )
                2 -> DownloadBatchCoordinator.manualPickEntry(target).copy(state = DownloadBatchEntryState.DISCOVERING)
                else -> DownloadBatchCoordinator.manualPickEntry(target)
            }
        }
        return DownloadBatch(
            id = "b",
            scope = DownloadScope.Season(1),
            contentType = "series",
            parentMetaId = "tt-shogun",
            parentMetaType = "series",
            title = "Shōgun",
            poster = DownloadRenderArt.poster("shogun"),
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
                // Choose sources is a screen, not a dialog: full width, top-aligned, no dialog cap.
                render("$sceneName-$sizeName", size.first, size.second, failures, isScreen = sceneName == "choose-sources", content = content)
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        isScreen: Boolean = false,
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
                    CompositionLocalProvider(LocalDownloadFlowInline provides true) { WithFixtureArt {
                        if (isScreen) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                            return@WithFixtureArt
                        }
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(if (phone) 24.dp else 0.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // A dialog is at most 560dp wide in the app (BasicAlertDialog's own cap).
                            Box(Modifier.widthIn(max = 560.dp)) { content() }
                        }
                    } }
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
