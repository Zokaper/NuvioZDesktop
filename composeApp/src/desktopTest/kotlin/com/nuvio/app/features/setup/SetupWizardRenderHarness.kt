package com.nuvio.app.features.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the setup wizard off-screen and writes PNGs.
 *
 * ## Why this is committed rather than kept in a scratchpad
 *
 * Four revisions of this wizard reached a device broken, and every one of the defects was
 * something only *looking* could catch: an unreadable translucent sheet, chips aligned hard left,
 * a preview that swapped what it was showing, a playback-mode card clipped in half, and rails
 * filled with a colour too dark to see. None of it is reachable by the pure suites, none of it is
 * a compile error, and the parser check cannot see any of it.
 *
 * `STATUS.md` claimed for three revisions that a harness was "provided". It was not in either
 * repository - it lived in a session scratchpad, so the next agent could not run it and neither
 * could the maintainer. That is the exact failure `scripts/run-pure-suites.sh` was moved into the
 * repository to fix. **Leave this here.**
 *
 * ## What it does and does not prove
 *
 * It **asserts that every scene composes and renders without throwing**, which is worth having on
 * its own: nothing else in either repository executes a line of the wizard's Compose code, so a
 * runtime crash on a step reaches a device today. What it cannot do is judge a layout - that is
 * what the PNGs are for.
 *
 * Run it and look at the output:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SetupWizardRenderHarness"
 * # then read the PNGs in composeApp/build/setup-wizard-render/
 * ```
 *
 * ⚠ **Artwork will be missing.** There is no network here and Coil resolves nothing, so every
 * poster and backdrop is its placeholder fill. That is not a fault of the render - it is the
 * aeroplane-mode state, which is a device check in its own right. Judge *layout* from these, and
 * judge artwork on a device.
 */
class SetupWizardRenderHarness {

    private val outputDir = File("build/setup-wizard-render")

    @Test
    fun renderEveryWizardSurface() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        // The Welcome step, which is the whole screen: a real home still under the frosted panel.
        // ⚠ This is the one that has to be looked at hardest. The panel's tint is tuned for a
        // device where the blur does nothing, and this scene has no blur either - Haze needs a
        // real graphics context - so what these PNGs show is close to the **worst** case, which
        // is exactly the one revision 2 failed. If the heading is hard to read here, deepen the
        // alphas in `SetupWelcomeSurface`.
        // ⚠ 1280x820 is the default desktop window and is the size the Welcome panel's new
        // bounded-card layout has to look right at; 420x900 is the phone path, which must be
        // unchanged by that work.
        for ((widthDp, heightDp) in listOf(420 to 900, 1100 to 800, 1280 to 820, 1600 to 900)) {
            for (theme in listOf(AppTheme.WHITE, AppTheme.entries.last())) {
                for (amoled in listOf(false, true)) {
                    val name = "welcome-${widthDp}x$heightDp-${theme.name.lowercase()}" +
                        if (amoled) "-amoled" else ""
                    render(name, widthDp, heightDp, theme, amoled, failures) {
                        SetupWizardScreen(onFinished = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        // The desktop two-pane layout, one PNG per step, at the default window (1280x820, see
        // `Main.kt`) and one larger size.
        //
        // ⚠ **This is new coverage, and it is the first time a whole wizard step has been
        // renderable at all.** `SetupWizardScreen` holds the current step in `rememberSaveable`
        // state, so there is no way in from outside and everything above this line can only draw
        // Welcome or a band in isolation. `SetupWizardDesktopLayout` takes `step` as a parameter
        // precisely so this loop can exist - if it ever starts reading the step from state, this
        // goes back to covering nothing.
        for ((widthDp, heightDp) in listOf(1280 to 820, 1600 to 900)) {
            renderDesktopSteps(widthDp, heightDp, failures)
        }

        // The bands for steps 2-8, at the settings each one can be asked to draw. The band is
        // pure with respect to its parameters, which is what makes this exhaustive rather than a
        // sample - see the header of `SetupSpecimen.kt`.
        for ((widthDp, _) in listOf(420 to 0, 1100 to 0)) {
            renderBands(widthDp, failures)
        }

        // The playback-mode storyboard, one PNG per frame. ⚠ Advanced through
        // `ImageComposeScene`'s **virtual clock** rather than by a frame-override parameter on
        // the composable: a second way into the drawing is a preview that can lie about the
        // first, which is the rule the whole specimen file is built on.
        for (mode in PlaybackMode.entries) {
            renderStoryboard(mode, failures)
        }

        if (failures.isNotEmpty()) {
            fail("Scenes failed to render:\n" + failures.joinToString("\n"))
        }
        println("Setup wizard renders written to ${outputDir.absolutePath}")
    }

    /**
     * Every step except Welcome, through the desktop two-pane layout.
     *
     * The bodies are the **real** `SetupStepBody`, not a stand-in, so what these PNGs show is the
     * actual control set for each step inside the actual pane. That is the whole point: the
     * defects this harness exists to catch - a clipped card, a chip aligned hard left, a rail too
     * dark to see - all live in the bodies rather than in the frame around them.
     *
     * ⚠ The plan is `offerSources = true` so that Sources is included and the step count reads
     * 8. A run with an addon installed drops that step; the layout is the same either way.
     */
    private fun renderDesktopSteps(widthDp: Int, heightDp: Int, failures: MutableList<String>) {
        val plan = SetupWizardPlan(offerSources = true)
        val steps = setupWizardSteps(plan).filter { it != SetupStep.Welcome }

        for (step in steps) {
            val specimen = when (step) {
                SetupStep.Cards -> SetupSpecimen.Cards
                SetupStep.Home -> SetupSpecimen.Home
                SetupStep.Details -> SetupSpecimen.Details
                SetupStep.Theme -> SetupSpecimen.Theme
                else -> SetupSpecimen.Diagram
            }
            val name = "desktop-${step.name.lowercase()}-${widthDp}x$heightDp"
            render(name, widthDp, heightDp, AppTheme.WHITE, false, failures) {
                SetupWizardDesktopLayout(
                    step = step,
                    plan = plan,
                    specimen = specimen,
                    dismissible = step != SetupStep.Cards,
                    onDismiss = {},
                    playbackMode = PlaybackMode.STREAMLINED,
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
                        step = step,
                        goingForward = true,
                        playbackMode = PlaybackMode.STREAMLINED,
                        posterWidthDp = 126,
                        posterCornerRadiusDp = 8,
                        landscapeCards = false,
                        hideLabels = false,
                        heroEnabled = true,
                        continueWatchingStyle = ContinueWatchingSectionStyle.Card,
                        useEpisodeThumbnails = true,
                        blurNextUp = false,
                        backgroundMode = MetaScreenBackgroundMode.Cinematic,
                        episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
                        blurUnwatchedEpisodes = false,
                        tabLayout = false,
                        selectedTheme = AppTheme.WHITE,
                        amoledEnabled = false,
                        addonUrl = "",
                        addonBusy = false,
                        addonError = null,
                        addonInstalledName = null,
                        onAddonUrlChange = {},
                        onInstallAddon = {},
                    )
                }
            }
        }
    }

    private fun renderBands(widthDp: Int, failures: MutableList<String>) {
        val variants = listOf<Pair<String, BandSettings>>(
            "cards-poster-dense" to BandSettings(SetupSpecimen.Cards, posterWidthDp = 112),
            "cards-wide-large" to BandSettings(
                SetupSpecimen.Cards,
                posterWidthDp = 140,
                landscape = true,
                cornerRadiusDp = 16,
            ),
            "home-card" to BandSettings(SetupSpecimen.Home),
            "home-poster-noHero" to BandSettings(
                SetupSpecimen.Home,
                heroEnabled = false,
                continueWatchingStyle = ContinueWatchingSectionStyle.Poster,
            ),
            "details-plain-stacked" to BandSettings(
                SetupSpecimen.Details,
                backgroundMode = MetaScreenBackgroundMode.Normal,
            ),
            "details-dominant-tabbed" to BandSettings(
                SetupSpecimen.Details,
                backgroundMode = MetaScreenBackgroundMode.DominantColor,
                tabLayout = true,
            ),
            "details-compact-episodes" to BandSettings(
                SetupSpecimen.Details,
                episodeCardStyle = MetaEpisodeCardStyle.List,
                blurUnwatched = true,
            ),
            "theme" to BandSettings(SetupSpecimen.Theme),
        )

        for ((name, settings) in variants) {
            // ⚠ The frame is 120 dp taller than the band asks for, so content that overruns its
            // budget shows as a spill rather than being cropped by the scene edge and looking
            // fine. `SetupSpecimenBand` clips, so a spill here means the *band* is clipping it on
            // a device - which is the thing to catch.
            val heightDp = (settings.specimen.preferredHeight.value + 120f).toInt()
            render("band-$name-$widthDp", widthDp, heightDp, AppTheme.WHITE, false, failures) {
                Box(modifier = Modifier.fillMaxSize()) {
                    settings.Band()
                }
            }
        }
    }

    private fun renderStoryboard(mode: PlaybackMode, failures: MutableList<String>) {
        val frames = setupStoryboardFrames(mode.name)
        var elapsedMillis = 0L
        frames.forEachIndexed { index, frame ->
            // Land in the middle of each hold, past the 300 ms stage tween, so the PNG shows the
            // frame at rest rather than mid-transition.
            val sampleAt = elapsedMillis + (frame.holdMillis / 2)
            val name = "storyboard-${mode.name.lowercase()}-$index-${frame.stage.name.lowercase()}"
            render(
                name = name,
                widthDp = 420,
                heightDp = 260,
                theme = AppTheme.WHITE,
                amoled = false,
                failures = failures,
                nanoTime = sampleAt * 1_000_000L,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SetupDiagram(step = SetupStep.PlaybackMode, playbackMode = mode)
                }
            }
            elapsedMillis += frame.holdMillis
        }
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        theme: AppTheme,
        amoled: Boolean,
        failures: MutableList<String>,
        nanoTime: Long = 0L,
        content: @Composable () -> Unit,
    ) {
        val density = Density(2f)
        runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density.density).toInt(),
                height = (heightDp * density.density).toInt(),
                density = density,
            ) {
                NuvioTheme(darkTheme = true, appTheme = theme, amoled = amoled) {
                    content()
                }
            }
            try {
                val image = scene.render(nanoTime)
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

    /**
     * One band's worth of settings.
     *
     * Every field is a parameter of `SetupSpecimenBand`, deliberately: that composable reads no
     * repository, which is what lets this harness draw every combination without standing up any
     * app state. If a setting ever has to be read internally instead, this stops being able to
     * cover it and the specimen has become something that can lie.
     */
    private data class BandSettings(
        val specimen: SetupSpecimen,
        val posterWidthDp: Int = 126,
        val cornerRadiusDp: Int = 8,
        val landscape: Boolean = false,
        val showTitles: Boolean = true,
        val heroEnabled: Boolean = true,
        val continueWatchingStyle: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
        val useEpisodeThumbnails: Boolean = true,
        val blurNextUp: Boolean = false,
        val backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Cinematic,
        val episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
        val blurUnwatched: Boolean = false,
        val tabLayout: Boolean = false,
    ) {
        @Composable
        fun Band() {
            SetupSpecimenBand(
                specimen = specimen,
                step = SetupStep.Cards,
                playbackMode = PlaybackMode.STREAMLINED,
                height = specimen.preferredHeight,
                contentPaddingTop = 0.dp,
                posterWidthDp = posterWidthDp,
                posterCornerRadiusDp = cornerRadiusDp,
                landscapeCards = landscape,
                showCardTitles = showTitles,
                heroEnabled = heroEnabled,
                continueWatchingStyle = continueWatchingStyle,
                useEpisodeThumbnails = useEpisodeThumbnails,
                blurNextUp = blurNextUp,
                backgroundMode = backgroundMode,
                episodeCardStyle = episodeCardStyle,
                blurUnwatchedEpisodes = blurUnwatched,
                tabLayout = tabLayout,
                nextUpLabel = "Next episode",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
