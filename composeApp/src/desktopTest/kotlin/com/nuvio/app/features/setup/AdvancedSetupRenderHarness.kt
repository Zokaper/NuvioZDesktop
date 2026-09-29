package com.nuvio.app.features.setup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.downloads.DownloadMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.streams.StreamBackgroundMode
import com.nuvio.app.features.streams.StreamBadgePlacement
import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders Advanced Setup off-screen and writes PNGs (setup + settings pass, plan §18).
 *
 * The same contract as `SetupWizardRenderHarness`, and for the same reason: nothing else executes
 * this Compose code, and a layout defect is only visible by looking. Every scene must compose and
 * render without throwing (asserted); the PNGs are for reading.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*AdvancedSetupRenderHarness"
 * # then read composeApp/build/advanced-setup-render/
 * ```
 *
 * - the hub at four windows (the phone path and the three desktop sizes the wizard harness uses);
 * - every panel of every category, desktop facts, at the same four windows - the two-pane frame on
 *   the desktop windows, the stacked frame at 420 dp;
 * - every panel again with Android facts at 420 dp, which is the only way the phone-only panels
 *   (touch, legacy layout, Random Episode, glow) get drawn here;
 * - the new specimens on their own in every palette.
 *
 * ⚠ Artwork is missing (no network, Coil resolves nothing): judge layout here, artwork on a device.
 */
class AdvancedSetupRenderHarness {

    private val outputDir = File("build/advanced-setup-render")

    private val windows = listOf(420 to 900, 1280 to 820, 2560 to 1440, 3840 to 2160)

    private val desktopFacts = AdvancedSetupFacts(
        isDesktop = true,
        downloadsEnabled = true,
        hasTrackingCredentials = true,
        playbackModeName = PlaybackMode.STREAMLINED.name,
        downloadModeName = DownloadMode.AUTOMATIC.name,
        socialEnabled = true,
        offerSocialIdentity = true,
    )

    private val androidFacts = desktopFacts.copy(isDesktop = false, isAndroid = true, navGlowSupported = true)

    private val values = AdvancedSetupValues(
        playbackMode = PlaybackMode.STREAMLINED,
        player = PlayerSettingsUiState(
            playbackMode = PlaybackMode.STREAMLINED,
            autoSkipSegmentTypes = setOf(AutoSkipSegmentType.RECAP),
            streamAutoPlayNextEpisodeEnabled = true,
            subtitleStyle = SubtitleStyleState(fontSizeSp = 24, backgroundColor = Color.Black.copy(alpha = 0.5f)),
        ),
        downloadMode = DownloadMode.AUTOMATIC,
        socialEnabled = true,
        socialSignedIn = true,
        socialReady = true,
        cardDepth = true,
    )

    private fun platformDensityFor(widthDp: Int): Float = if (widthDp >= 2000) 1f else 2f

    @Test
    fun renderEveryAdvancedSetupSurface() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        for ((w, h) in windows) {
            render("hub-desktop-${w}x$h", w, h, AppTheme.WHITE, failures) { windowWidth ->
                AdvancedSetupHub(
                    facts = desktopFacts,
                    values = values,
                    reviewed = listOf(AdvancedSetupCategory.PlaybackMode, AdvancedSetupCategory.Theme),
                    insets = PaddingValues(0.dp),
                    windowWidth = windowWidth,
                    onOpenCategory = {},
                    onStartTour = {},
                    onClose = {},
                )
            }
        }
        render("hub-android-420x900", 420, 900, AppTheme.WHITE, failures) { windowWidth ->
            AdvancedSetupHub(
                facts = androidFacts,
                values = values,
                reviewed = emptyList(),
                insets = PaddingValues(0.dp),
                windowWidth = windowWidth,
                onOpenCategory = {},
                onStartTour = {},
                onClose = {},
            )
        }

        for ((w, h) in windows) {
            renderPanels("desktop", desktopFacts, w, h, failures)
        }
        renderPanels("android", androidFacts, 420, 900, failures)

        for (theme in AppTheme.entries) {
            render("specimens-${theme.name.lowercase()}", 1280, 820, theme, failures) {
                AdvancedPreviewFrame(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        SpecimenPlayerChrome(
                            legacyLayout = false, showLayoutChoice = true, pauseOverlay = true, loadingOverlay = true,
                            contentWarnings = true, touchPanel = false, touchGestures = true, holdToSpeed = true, holdSpeed = 2f,
                        )
                        SpecimenSkipTimeline(
                            skipIntro = true, autoSkip = setOf(AutoSkipSegmentType.RECAP), showNextEpisode = true,
                            autoPlayNext = false, thresholdMode = com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE,
                            thresholdPercent = 95f, thresholdMinutes = 2f,
                        )
                        SpecimenFriendActivity(socialEnabled = true, shareWatchingNow = true, shareWatched = false)
                    }
                }
            }
            render("specimens-b-${theme.name.lowercase()}", 1280, 820, theme, failures) {
                AdvancedPreviewFrame(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        SpecimenSubtitles(style = SubtitleStyleState(outlineEnabled = true, bold = true))
                        SpecimenSourceList(
                            backgroundMode = StreamBackgroundMode.Cinematic, showSizeBadges = true,
                            badgePlacement = StreamBadgePlacement.TOP, showAddonLogo = true,
                        )
                        SpecimenMetadata(enriched = true)
                    }
                }
            }
            render("specimens-c-${theme.name.lowercase()}", 1280, 820, theme, failures) {
                AdvancedPreviewFrame(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        SpecimenDesktopNavigation(layout = DesktopNavigationLayout.Sidebar, style = NavBarStyle.EXPANDED)
                        SpecimenDesktopNavigation(layout = DesktopNavigationLayout.TopBar, style = NavBarStyle.COMPACT)
                        SpecimenIosTabBar(liquidGlass = true)
                        SpecimenTracking()
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Advanced Setup surfaces failed to render:\n" + failures.joinToString("\n"))
        }
    }

    private fun renderPanels(label: String, facts: AdvancedSetupFacts, w: Int, h: Int, failures: MutableList<String>) {
        for (category in advancedSetupCategories(facts)) {
            val panels = advancedSetupPanels(category, facts)
            panels.forEachIndexed { index, panel ->
                render("panel-$label-${w}x$h-${category.name}-${panel.name}", w, h, AppTheme.WHITE, failures) { windowWidth ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        AdvancedSetupPanelFrame(
                            panel = panel,
                            facts = facts,
                            values = values,
                            touring = false,
                            position = (index + 1) to panels.size,
                            isLast = index == panels.lastIndex,
                            wideDesktop = facts.isDesktop && windowWidth >= 1000.dp,
                            windowHeight = maxHeight,
                            insets = PaddingValues(0.dp),
                            onBack = {},
                            onAdvance = {},
                            onClose = {},
                            onSocialEnabledChange = {},
                        )
                    }
                }
            }
        }
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        theme: AppTheme,
        failures: MutableList<String>,
        content: @Composable (windowWidth: androidx.compose.ui.unit.Dp) -> Unit,
    ) {
        val density = Density(platformDensityFor(widthDp))
        runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density.density).toInt(),
                height = (heightDp * density.density).toInt(),
                density = density,
            ) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = theme,
                    amoled = false,
                    desktopUiScale = desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) { content(maxWidth) }
                }
            }
            try {
                scene.render(0)
                val image = scene.render(1_000_000_000L)
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
