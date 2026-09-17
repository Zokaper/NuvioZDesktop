package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.language.AudioLanguageOption
import com.nuvio.app.core.language.SubtitleLanguageOption
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sources step drawn through the real desktop frame and the real `SetupStepBody`, with clicks
 * sent through the pointer pipeline. Also writes one PNG per state to
 * `build/setup-wizard-render/sources-*.png`, for the same reason `SetupWizardRenderHarness` exists.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SetupSourcesStepUiTest {

    private val outputDir = File("build/setup-wizard-render").also { it.mkdirs() }

    @Test
    fun freshProfileIsAskedTheQuestionAndBothAnswersFire() {
        var recommended = 0
        var manual = 0
        scene(
            "question",
            SetupSourcesState(),
            existing = null,
            actions = SetupSourcesActions(onUseRecommended = { recommended++ }, onSetUpManually = { manual++ }),
        ) { scene ->
            scene.node("Use the recommended Nuvio Z source setup?")
            scene.node("You can use your own plugin, but some features may not work as expected.")
            scene.click("Use recommended setup")
            scene.click("Set up manually")
            assertEquals(1, recommended)
            assertEquals(1, manual)
        }
    }

    @Test
    fun installedStreamAddonIsDetectedAndOffersAllThreeWays() {
        var keep = 0
        var recommended = 0
        var manual = 0
        scene(
            "existing",
            SetupSourcesState(),
            existing = "Torrentio",
            actions = SetupSourcesActions(
                onKeepExisting = { keep++ },
                onUseRecommended = { recommended++ },
                onSetUpManually = { manual++ },
            ),
        ) { scene ->
            scene.node("Sources are already configured")
            assertTrue(scene.texts().any { it.startsWith("Torrentio is installed") })
            scene.click("Keep current sources")
            scene.click("Use recommended setup instead")
            scene.click("Manage manually")
            assertEquals(listOf(1, 1, 1), listOf(keep, recommended, manual))
            assertTrue(scene.texts().none { it == "Use the recommended Nuvio Z source setup?" })
        }
    }

    @Test
    fun recommendedFormHasOnlyKeyTwoLanguagesAndOneButton() {
        var setUp = 0
        scene(
            "recommended",
            SetupSourcesState(mode = SetupSourcesMode.Recommended, torBoxApiKey = "tb_SECRET", sourceLanguageCode = "ja"),
            existing = null,
            actions = SetupSourcesActions(onSetUpRecommended = { setUp++ }),
        ) { scene ->
            scene.node("TorBox API key")
            scene.node("Source language")
            scene.node("Subtitle language")
            scene.node("Japanese")
            assertTrue(scene.texts().none { it.contains("tb_SECRET") }, "the key field must be masked")
            scene.click("Set up sources")
            assertEquals(1, setUp)
        }
    }

    @Test
    fun recommendedFailureAndBusyStatesRender() {
        scene(
            "recommended-failed",
            SetupSourcesState(
                mode = SetupSourcesMode.Recommended,
                recommendedFailure = RecommendedSourceFailure.Rejected,
                recommendedFailureDetail = "Invalid API key",
            ),
            existing = null,
        ) { scene ->
            scene.node("AIOStreams couldn't create the configuration. Check your TorBox API key and try again.")
            scene.node("Invalid API key")
        }
        scene(
            "recommended-busy",
            SetupSourcesState(mode = SetupSourcesMode.Recommended, busy = true),
            existing = null,
        ) { scene ->
            scene.node("Setting up your sources. This can take up to a minute.")
        }
    }

    @Test
    fun successStateHidesRecoveryUntilAsked() {
        scene(
            "configured",
            SetupSourcesState(
                mode = SetupSourcesMode.Recommended,
                configuredName = "Nuvio Z Recommended",
                recovery = AioStreamsRecovery("https://aio.example/stremio/configure", "uuid-1", "generated-password"),
            ),
            existing = "Nuvio Z Recommended",
        ) { scene ->
            scene.node("Sources configured")
            assertTrue(scene.texts().none { it == "generated-password" })
            scene.click("Show AIOStreams recovery details")
            scene.node("generated-password")
            scene.node("uuid-1")
        }
    }

    @Test
    fun manualPathKeepsTheManifestField() {
        var installs = 0
        scene(
            "manual",
            SetupSourcesState(mode = SetupSourcesMode.Manual, manualUrl = "https://addon.example/manifest.json"),
            existing = null,
            actions = SetupSourcesActions(onInstallManual = { installs++ }),
        ) { scene ->
            scene.node("Set up manually")
            scene.node("https://addon.example/manifest.json")
            scene.click("Add source")
            assertEquals(1, installs)
        }
    }

    // --- scene plumbing ----------------------------------------------------------------------

    private var clockMs = 0L

    private fun scene(
        name: String,
        state: SetupSourcesState,
        existing: String?,
        actions: SetupSourcesActions = SetupSourcesActions(),
        block: (ImageComposeScene) -> Unit,
    ) {
        for ((widthDp, heightDp) in listOf(1280 to 820, 420 to 900)) {
            clockMs = 0L
            val density = Density(1f)
            val scene = ImageComposeScene(width = widthDp, height = heightDp, density = density) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.WHITE,
                    amoled = false,
                    desktopUiScale = desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    Frame(state, existing, actions, compact = widthDp < 1000)
                }
            }
            try {
                scene.advance(0)
                scene.advance(1_000)
                val image = scene.render(clockMs * 1_000_000L)
                image.encodeToData(EncodedImageFormat.PNG)?.let {
                    File(outputDir, "sources-$name-${widthDp}x$heightDp.png").writeBytes(it.bytes)
                }
                // Interactions are asserted once, on the default desktop window.
                if (widthDp == 1280) block(scene)
            } finally {
                scene.close()
            }
        }
    }

    @Composable
    private fun Frame(state: SetupSourcesState, existing: String?, actions: SetupSourcesActions, compact: Boolean) {
        val body: @Composable () -> Unit = {
            SetupStepBody(
                step = SetupStep.Sources,
                goingForward = true,
                playbackMode = PlaybackMode.STREAMLINED,
                languageStrictness = LanguageStrictness.REQUIRE,
                dynamicRangePolicy = DynamicRangePolicy.ANY,
                qualityCeilingMbps = 0,
                preferredAudioLanguage = AudioLanguageOption.DEVICE,
                preferredSubtitleLanguage = SubtitleLanguageOption.NONE,
                posterWidthDp = 126,
                landscapeCards = false,
                selectedTheme = AppTheme.WHITE,
                amoledEnabled = false,
                socialEnabled = false,
                socialProbeUnknown = false,
                socialSignedIn = false,
                socialHandle = "",
                socialHandleBusy = false,
                socialHandleMessage = null,
                onSocialEnabledChange = {},
                onSocialHandleChange = {},
                onSaveSocialHandle = {},
                sources = state,
                existingSourceName = existing,
                sourcesActions = actions,
            )
        }
        if (compact) {
            // The stacked layout's panel colour and gutters, so the phone PNG is not white-on-white.
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.surface)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) { body() }
            return
        }
        SetupWizardDesktopLayout(
            step = SetupStep.Sources,
            plan = SetupWizardPlan(playbackModeName = PlaybackMode.STREAMLINED.name),
            specimen = SetupSpecimen.Diagram,
            dismissible = false,
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
            body = body,
        )
    }

    private fun ImageComposeScene.advance(ms: Long) {
        clockMs += ms
        render(clockMs * 1_000_000L)
    }

    private fun ImageComposeScene.click(text: String) {
        val at = node(text).boundsInRoot.center
        sendPointerEvent(PointerEventType.Move, at, timeMillis = clockMs)
        advance(8)
        sendPointerEvent(
            PointerEventType.Press, at, timeMillis = clockMs,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        advance(8)
        sendPointerEvent(
            PointerEventType.Release, at, timeMillis = clockMs,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
        advance(250)
    }

    private fun ImageComposeScene.allNodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.flatten() }

    private fun ImageComposeScene.texts(): List<String> = allNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private fun ImageComposeScene.node(text: String): SemanticsNode = allNodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    } ?: error("no node reads \"$text\"; saw ${texts()}")

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
