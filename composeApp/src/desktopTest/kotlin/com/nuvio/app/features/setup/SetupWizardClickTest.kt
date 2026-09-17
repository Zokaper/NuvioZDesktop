package com.nuvio.app.features.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One click on an enabled wizard button fires exactly once, including a click whose pointer moves.
 *
 * ⚠ **The movement is the whole test.** The wizard's root carries `nuvioConsumePointerEvents()` so
 * that a tap missing its controls cannot reach the settings screen it is drawn over. That modifier
 * used to consume *every* change on the `Final` pass, and Compose's tap detector checks the `Final`
 * pass of every event between press and release: an ancestor's `Final` handler runs before the
 * button's, so any pointer movement inside the click - one pixel of trackpad travel is enough -
 * arrived already consumed and cancelled the click. A mouse held still never showed it, which is why
 * it read as "some buttons need two to four presses on macOS" and never reproduced on a desk mouse.
 *
 * Driven through `ImageComposeScene.sendPointerEvent`, the same pointer pipeline a window uses, rather
 * than by invoking the semantics click action - an action call skips hit testing and gesture
 * detection entirely, which is exactly the path that was broken.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SetupWizardClickTest {

    @Test
    fun stillClickOnAdvanceFiresOnce() {
        var advances = 0
        withScene({ ConsumingRoot { Box(Modifier.align(Alignment.Center)) { Advance { advances++ } } } }) { scene ->
            scene.click(scene.nodeWithText(NextLabel).center(), jitter = false)
            assertEquals(1, advances)
        }
    }

    @Test
    fun clickWithPointerMovementOnAdvanceFiresOnce() {
        var advances = 0
        withScene({ ConsumingRoot { Box(Modifier.align(Alignment.Center)) { Advance { advances++ } } } }) { scene ->
            scene.click(scene.nodeWithText(NextLabel).center(), jitter = true)
            assertEquals(1, advances, "a click that moved a pixel between press and release was lost")
        }
    }

    @Test
    fun repeatedClicksFireExactlyOncePerClick() {
        var advances = 0
        withScene({ ConsumingRoot { Box(Modifier.align(Alignment.Center)) { Advance { advances++ } } } }) { scene ->
            val centre = scene.nodeWithText(NextLabel).center()
            repeat(6) { index -> scene.click(centre, jitter = index % 2 == 0) }
            assertEquals(6, advances)
        }
    }

    @Test
    fun clicksNearTheEdgeOfTheButtonStillFire() {
        var advances = 0
        withScene({ ConsumingRoot { Box(Modifier.align(Alignment.Center)) { Advance { advances++ } } } }) { scene ->
            // Left and right edges at mid-height. The node's bounds include Material's 48 dp minimum
            // touch target above and below the drawn button, and that padding is expanded for touch
            // input only - a mouse there is genuinely outside the button.
            val bounds = scene.nodeWithText(NextLabel).boundsInRoot
            scene.click(Offset(bounds.left + 3f, bounds.center.y), jitter = true)
            scene.click(Offset(bounds.right - 3f, bounds.center.y), jitter = true)
            assertEquals(2, advances)
        }
    }

    /**
     * The reason the modifier exists must survive the fix: a surface drawn over the app still stops
     * a click that misses its own controls from reaching the button underneath it.
     */
    @Test
    fun consumingSurfaceStillBlocksTheScreenUnderneath() {
        var underneath = 0
        withScene({
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center)) { Advance { underneath++ } }
                Box(Modifier.fillMaxSize().nuvioConsumePointerEvents())
            }
        }) { scene ->
            val centre = scene.nodeWithText(NextLabel).center()
            scene.click(centre, jitter = false)
            scene.click(centre, jitter = true)
            assertEquals(0, underneath, "a click on the covering surface reached the screen underneath")
        }
    }

    /**
     * The real desktop frame, walked with Next and Back while every step's body and specimen are
     * still mid-transition from the previous click: no leftover outgoing page may swallow the next
     * press, and no press may advance twice.
     */
    @Test
    fun desktopLayoutNavigatesOneStepPerClickDuringTransitions() {
        val plan = SetupWizardPlan(playbackModeName = PlaybackMode.STREAMLINED.name)
        var step by mutableStateOf(SetupStep.Look)
        var advances = 0
        var backs = 0
        withScene({
            ConsumingRoot {
                WizardFrame(
                    step = step,
                    plan = plan,
                    onBack = {
                        backs++
                        previousSetupStep(step, plan)?.let { step = it }
                    },
                    onAdvance = {
                        advances++
                        nextSetupStep(step, plan)?.let { step = it }
                    },
                )
            }
        }) { scene ->
            val forward = setupWizardSteps(plan).dropWhile { it != SetupStep.Look }.drop(1)
            forward.forEachIndexed { index, expected ->
                scene.click(scene.nodeWithText(NextLabel).center(), jitter = index % 2 == 1, settleMs = 16)
                assertEquals(expected, step, "Next #${index + 1} should land on $expected")
            }
            assertEquals(forward.size, advances)

            scene.click(scene.nodeWithText(BackLabel).center(), jitter = true, settleMs = 16)
            assertEquals(1, backs)
            assertEquals(previousSetupStep(forward.last(), plan), step)
        }
    }

    @Composable
    private fun ConsumingRoot(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        Box(modifier = Modifier.fillMaxSize().nuvioConsumePointerEvents(), content = content)
    }

    @Composable
    private fun Advance(onAdvance: () -> Unit) {
        SetupAdvanceButton(step = SetupStep.Look, plan = SetupWizardPlan(), onAdvance = onAdvance)
    }

    @Composable
    private fun WizardFrame(step: SetupStep, plan: SetupWizardPlan, onBack: () -> Unit, onAdvance: () -> Unit) {
        SetupWizardDesktopLayout(
            step = step,
            plan = plan,
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
            onBack = onBack,
            onAdvance = onAdvance,
            modifier = Modifier.fillMaxSize(),
        ) {
            SetupStepBody(
                step = step,
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
                sourcesReady = true,
                onSocialEnabledChange = {},
                onSocialHandleChange = {},
                onSaveSocialHandle = {},
                addonUrl = "",
                addonBusy = false,
                addonError = null,
                addonInstalledName = null,
                existingSourceName = "Existing Streams",
                onAddonUrlChange = {},
                onInstallAddon = {},
                onOpenAioStreams = {},
            )
        }
    }

    private var clockMs = 0L

    private fun withScene(content: @Composable () -> Unit, block: (ImageComposeScene) -> Unit) {
        clockMs = 0L
        val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                content()
            }
        }
        try {
            scene.advance(0)
            // Past every entrance animation, so the first press lands on a settled frame.
            scene.advance(1_000)
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.advance(ms: Long) {
        clockMs += ms
        render(clockMs * 1_000_000L)
    }

    /** Press, optionally move a pixel, release - the shape of a real trackpad click. */
    private fun ImageComposeScene.click(at: Offset, jitter: Boolean, settleMs: Long = 250) {
        val pressed = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Move, at, timeMillis = clockMs)
        advance(8)
        sendPointerEvent(PointerEventType.Press, at, timeMillis = clockMs, buttons = pressed, button = PointerButton.Primary)
        advance(8)
        if (jitter) {
            sendPointerEvent(PointerEventType.Move, at + Offset(1f, 1f), timeMillis = clockMs, buttons = pressed)
            advance(8)
        }
        sendPointerEvent(
            PointerEventType.Release,
            if (jitter) at + Offset(1f, 1f) else at,
            timeMillis = clockMs,
            buttons = PointerButtons(),
            button = PointerButton.Primary,
        )
        advance(settleMs)
    }

    private fun ImageComposeScene.nodeWithText(text: String): SemanticsNode {
        val all = semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.flatten() }
        return all.firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        } ?: error("no semantics node reads \"$text\"; saw ${all.mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }}")
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun SemanticsNode.center(): Offset = boundsInRoot.center

    private companion object {
        // `setup_next` and `setup_back` in the default locale.
        const val NextLabel = "Next"
        const val BackLabel = "Back"
    }
}
