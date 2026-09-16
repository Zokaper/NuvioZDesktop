package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the playback quality panel off-screen and writes PNGs.
 *
 * ## Why it exists
 *
 * The chip row is **fixed height and does not wrap** - see `FeatureChips` - so every chip added to
 * it spends width that the narrow columns of the four-column grid may not have. The built-in
 * subtitle chip is the fourth (`AI Upscale`, dynamic range, audio, subs), and whether that row
 * still fits is not a question the pure suites or the compiler can answer. Same reasoning as
 * `SetupWizardRenderHarness`: nothing else executes a line of this panel's Compose code.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*PlaybackQualityRenderHarness"
 * # then read the PNGs in composeApp/build/playback-quality-render/
 * ```
 *
 * The scenes below are deliberately the **worst** cases rather than typical ones: every release
 * carries a dynamic range, an audio layout and a subtitle claim at once, and one is an AI upscale
 * as well, so the row is as full as the app can make it.
 */
class PlaybackQualityRenderHarness {

    private val outputDir = File("build/playback-quality-render")

    /** The default desktop window (`Main.kt`), and the narrowest width the grid still uses. */
    private val windows = listOf(1280 to 820, 1100 to 800, 1920 to 1080)

    private fun stream(name: String, filename: String, sizeBytes: Long) = StreamItem(
        name = name,
        description = null,
        url = "https://example.com/${filename}",
        addonName = "Torrentio",
        addonId = "torrentio",
        behaviorHints = StreamBehaviorHints(filename = filename, videoSize = sizeBytes),
    )

    /**
     * One bucket per resolution, each release claiming as much as a release name can.
     *
     * `ESub` and `ENG.SUBS` are the two shapes the parser reads most often; `MultiSubs` is the
     * unnamed claim, which must draw the "Multi subs" chip and never an accented one.
     */
    private fun options(): List<PlaybackQualityOption> {
        val candidates = listOf(
            stream("4K DV Atmos", "Movie.2024.2160p.WEB-DL.DV.Atmos.5.1.ENG.SUBS-GRP.mkv", 26_000_000_000L),
            stream("4K HDR", "Movie.2024.2160p.WEBRip.HDR.Atmos.5.1.MultiSubs-GRP.mkv", 11_000_000_000L),
            stream("1080p", "Movie.2024.1080p.WEB-DL.DDP5.1.HINDI.ESub-GRP.mkv", 5_400_000_000L),
            stream("1080p upscale", "Movie.2024.1080p.AI.Upscale.WEBRip.Atmos.5.1.ENG.SUBS-GRP.mkv", 2_500_000_000L),
            stream("720p", "Movie.2024.720p.WEBRip.AAC.ENG.SUBS-GRP.mkv", 1_500_000_000L),
            stream("SD", "Movie.2024.480p.WEB-DL.DD5.1-GRP.mkv", 730_000_000L),
        ).map { PlaybackSourceCandidate(stream = it) }
        return PlaybackQualityOptions.build(candidates, context(preferEmbedded = "en"))
    }

    private fun context(preferEmbedded: String?) = PlaybackSelectionContext(
        isEpisode = false,
        preferredEmbeddedSubtitleLanguage = preferEmbedded,
    )

    @Test
    fun renderQualityPanel() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val options = options()
        if (options.isEmpty()) fail("no quality options were built - the fixtures are wrong")

        for ((widthDp, heightDp) in windows) {
            for (preferenceOn in listOf(true, false)) {
                render(
                    name = "quality-${widthDp}x$heightDp" + if (preferenceOn) "-prefer-subs" else "-plain",
                    widthDp = widthDp,
                    heightDp = heightDp,
                    failures = failures,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D0D)),
                    ) {
                        // The desktop/wide branch - the one the maintainer sees, and the only one
                        // with a chip row. The narrow branch draws tier rows and no chips at all.
                        QualityColumnsBody(
                            options = options,
                            isLoading = false,
                            isSelecting = false,
                            selectionContext = context(if (preferenceOn) "en" else null),
                            estimatedMbps = 293.0,
                            isConnectionMeasured = true,
                            isConnectionStale = false,
                            isMeasuringConnection = false,
                            onOptionSelected = {},
                            onRetestConnection = {},
                            onChooseManually = {},
                            onAdjustPreferences = {},
                        )
                    }
                }
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString(separator = "\n"))
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        val density = Density(2f)
        runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density.density).toInt(),
                height = (heightDp * density.density).toInt(),
                density = density,
            ) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.entries.last(),
                    amoled = false,
                    desktopUiScale = desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    content()
                }
            }
            try {
                val image = scene.render(0L)
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }
    }
}
