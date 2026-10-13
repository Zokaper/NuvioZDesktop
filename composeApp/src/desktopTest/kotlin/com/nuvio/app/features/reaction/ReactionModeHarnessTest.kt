package com.nuvio.app.features.reaction

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real playback, on this machine, through the production path: the real NativePlayerController and
 * bridge (libmpv + the WebView2 controls page with the injected reaction panel) as the master, and
 * ReactionSession driving a second libmpv stage. Opens windows and plays video, so it only runs
 * when pointed at media:
 *
 *   NUVIO_REACTION_HARNESS_EPISODE=<60 s video>  NUVIO_REACTION_HARNESS_REACTION=<90 s video>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionModeHarnessTest*"
 *
 * Both can be generated with libmpv's encode mode (see STATUS.md). Writes a timeline and
 * screenshots to build/reaction-harness/. Silent (the stage gets ao=null, the episode is muted),
 * and screenshots are of the harness's own windows only - never a capture of the screen.
 */
class ReactionModeHarnessTest {
    private val out = File("build/reaction-harness").apply { mkdirs() }
    private val timeline = StringBuilder("t_ms,phase,master_ms,master_paused,follower_ms,follower_paused,error_ms,visibility,main_volume\n")
    private val started = System.currentTimeMillis()

    @Test
    fun theReactionFollowsTheEpisode() {
        val episode = System.getenv("NUVIO_REACTION_HARNESS_EPISODE") ?: return
        val reaction = System.getenv("NUVIO_REACTION_HARNESS_REACTION") ?: return
        // Optional: stream the reaction from YouTube instead (needs yt-dlp + Deno, e.g. NUVIO_YTDLP_PATH /
        // NUVIO_DENO_PATH). Network seeks take seconds, so settling phases are given longer.
        val reactionUrl = System.getenv("NUVIO_REACTION_HARNESS_REACTION_URL")
        val settle = if (reactionUrl != null) 3 else 1
        System.setProperty("nuvio.reactionMode", "1")
        System.setProperty("nuvio.reactionDataDir", File(out, "data").absolutePath)
        assertTrue(ReactionModeFlag.enabled, "flag")
        assertTrue(ReactionNative.available, "player_bridge.dll lacks the reaction exports - rebuild it")

        val host = NativePlayerHost()
        val frame = JFrame("Reaction harness")
        SwingUtilities.invokeAndWait {
            frame.setBounds(40, 40, 1280, 760)
            frame.contentPane.add(host)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.isVisible = true
        }
        val controller = NativePlayerController(host)
        var playbackError: String? = null
        SwingUtilities.invokeAndWait {
            controller.attach(File(episode).toURI().toString(), emptyMap(), true, 0L, 1, false) { playbackError = it }
        }
        try {
            val state = waitFor("player attached and panel injected", 30_000) {
                ReactionSession.debugState.takeIf { it.playerHandle != 0L && it.uiReady }
            }
            val handle = state.playerHandle
            ReactionNative.mainSetProperty(handle, "mute", "yes")
            // The controller reveals its canvas on the first frame; in the app Compose lays it out,
            // here the frame has to be told to.
            SwingUtilities.invokeAndWait {
                frame.contentPane.revalidate()
                frame.contentPane.repaint()
            }
            ReactionSession.extraStageOptionsForTest = listOf("ao=null")
            fun event(type: String, value: Double = 0.0) = assertTrue(ReactionSession.onControlsEvent(handle, type, value))

            // Optional: a real Firefox with the Reaction Link extension is running (web-ext, throwaway
            // profile, autoplay blocked) with a YouTube reaction open. Pick that tab, as the panel would.
            if (System.getenv("NUVIO_REACTION_HARNESS_FIREFOX") == "1") {
                val tab = waitFor("Firefox tab reported by the extension", 60_000) {
                    ReactionSession.debugState.browserTabs.firstOrNull { it.videoId != null }
                }
                ReactionSession.monitorOverride = Rectangle(1330, 40, 560, 315)
                event("reactionMode", 0.0)
                event("reactionPickTab", tab.tabId.toDouble())
                val linked = waitFor("the picked tab playing on the stage", 60_000) {
                    ReactionSession.debugState.takeIf { it.stageOpen && !it.loading && it.followerMs != null && it.linkedTabId == tab.tabId }
                }
                File(out, "firefox.txt").writeText("tab=${tab}\nstate=$linked\n")
                record("firefox-playing", 3_000)
                screenshot("firefox-linked")
                event("reactionStop")
                return
            }

            // "Second monitor": a window beside the harness frame on this one-screen machine.
            ReactionSession.monitorOverride = Rectangle(1330, 40, 560, 315)
            event("reactionMode", 0.0)
            ReactionSession.startSource(reactionUrl?.let { ReactionSource.WebVideo(it) } ?: ReactionSource.LocalFile(reaction))
            waitFor("stage playing", 60_000) {
                ReactionSession.debugState.takeIf { it.stageOpen && !it.loading && it.followerMs != null }
            }

            // 1. Offset +5 s while playing: speed nudges hold it.
            event("reactionNudge", 5_000.0)
            record("settle-offset", 4_000L * settle)
            val playing = record("measure-offset", 3_000)
            val worst = playing.mapNotNull { it.errorMs }.maxOf { abs(it) }
            assertTrue(worst < 150, "playing drift too large: $worst ms")

            // 2. The master pauses: the reaction pauses on the same frame.
            NativePlayerBridge.setPaused(handle, true)
            val paused = record("master-paused", 1_500L * settle).last()
            assertEquals(true, paused.followerPaused, "reaction kept playing while the episode paused")
            assertTrue(abs(paused.errorMs ?: 9_999) <= 80, "paused misalignment ${paused.errorMs} ms")

            // 3. The master seeks: the reaction follows.
            NativePlayerBridge.setPaused(handle, false)
            NativePlayerBridge.seekTo(handle, 30_000)
            record("after-seek", 3_000L * settle)
            val afterSeek = record("measure-seek", 1_500)
            assertTrue(afterSeek.mapNotNull { it.errorMs }.all { abs(it) < 150 }, "after a seek: ${afterSeek.map { it.errorMs }}")

            // 4. A gap: offset -35 s means the reaction only starts 35 s into the episode.
            event("reactionNudge", -40_000.0)
            NativePlayerBridge.seekTo(handle, 31_000)
            val gap = record("gap", 2_000).last()
            assertEquals(StageVisibility.GAP, gap.visibility)
            assertEquals(true, gap.followerPaused)
            assertTrue((gap.mainVolume ?: 0.0) > 50.0, "episode audio did not come up in the gap: ${gap.mainVolume}")
            screenshot("gap-dual")
            val resumed = waitFor("reactions resume at 35 s", 10_000L * settle) {
                ReactionSession.debugState.takeIf { it.visibility == StageVisibility.SHOWING && (it.masterMs ?: 0) > 35_500 }
            }
            record("resumed", 1_500L * settle)
            assertTrue((ReactionSession.debugState.mainVolume ?: 100.0) < 5.0, "episode not muted under reactions: ${resumed.mainVolume}")
            screenshot("showing-dual")

            // 5. Single-screen mode: the episode shrinks into the middle, reactions around it.
            event("reactionMode", 1.0)
            waitFor("single-screen stage playing", 30_000) {
                ReactionSession.debugState.takeIf { it.mode == StageMode.SINGLE && !it.loading && it.followerMs != null }
            }
            record("single", 3_000L * settle)
            val margin = ReactionNative.mainGetProperty(handle, "video-margin-ratio-left")?.toDoubleOrNull() ?: 0.0
            assertTrue(margin > 0.05, "episode was not shrunk: margin $margin")
            event("reactionToggle")
            record("panel", 1_500)
            screenshot("single-with-panel")
            // Ground truth, when WGC is available: the margin left of the episode must show the stage
            // (its background or a tile), not the main video's own black. A stage stacked anywhere
            // but directly under the container is invisible here - this is what caught it.
            File(out, "single-with-panel-wgc.png").takeIf { it.isFile }?.let { file ->
                val image = javax.imageio.ImageIO.read(file)
                val margin = java.awt.Color(image.getRGB((image.width * 0.08).toInt(), image.height / 2))
                assertTrue(margin.red + margin.green + margin.blue > 12, "single-screen stage not on screen: margin $margin")
            }
            val single = record("single-measure", 2_000)
            assertTrue(single.mapNotNull { it.errorMs }.all { abs(it) < 150 }, "single-screen drift: ${single.map { it.errorMs }}")

            // 5b. A long gap in single-screen mode: the episode grows back to full size, then shrinks
            // again once reactions are near. Offset -95 s puts the next reaction ~45 s away.
            event("reactionNudge", -60_000.0)
            record("long-gap", 2_000)
            val grown = ReactionNative.mainGetProperty(handle, "video-margin-ratio-left")?.toDoubleOrNull() ?: -1.0
            assertEquals(StageVisibility.GAP, ReactionSession.debugState.visibility)
            assertTrue(grown < 0.005, "episode did not grow back during a long gap: margin $grown")
            screenshot("single-long-gap")
            event("reactionNudge", 60_000.0)
            record("long-gap-over", 2_000)
            val shrunk = ReactionNative.mainGetProperty(handle, "video-margin-ratio-left")?.toDoubleOrNull() ?: 0.0
            assertTrue(shrunk > 0.05, "episode did not shrink back when reactions resumed: margin $shrunk")

            // 6. Off: margins and volume restored.
            event("reactionStop")
            record("stopped", 1_000)
            val restored = ReactionNative.mainGetProperty(handle, "video-margin-ratio-left")?.toDoubleOrNull() ?: -1.0
            assertEquals(0.0, restored, 1e-6)
            assertEquals(null, playbackError)
        } finally {
            File(out, "timeline.csv").writeText(timeline.toString())
            ReactionSession.monitorOverride = null
            ReactionSession.extraStageOptionsForTest = emptyList()
            SwingUtilities.invokeAndWait {
                controller.dispose()
                frame.dispose()
            }
        }
    }

    private fun record(phase: String, durationMs: Long): List<ReactionDebugState> {
        val samples = mutableListOf<ReactionDebugState>()
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val s = ReactionSession.debugState
            samples += s
            timeline.append("${System.currentTimeMillis() - started},$phase,${s.masterMs},${s.masterPaused},${s.followerMs},${s.followerPaused},${s.errorMs},${s.visibility},${s.mainVolume}\n")
            Thread.sleep(100)
        }
        return samples
    }

    private fun <T> waitFor(what: String, timeoutMs: Long, probe: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        File(out, "timeline.csv").writeText(timeline.toString())
        fail("timed out waiting for $what; last state ${ReactionSession.debugState}")
    }

    /** The harness window via PrintWindow (scripts/reaction/capture-window.py) and the stage via mpv. */
    private fun screenshot(name: String) {
        val handle = ReactionSession.debugState.playerHandle
        val diagnostics = listOf("osd-dimensions", "current-vo", "hwdec-current", "video-params/pixelformat", "video-params/w", "estimated-vf-fps")
        File(out, "$name-diagnostics.txt").writeText(
            diagnostics.joinToString("\n") { "stage $it = ${ReactionSession.stagePropertyForTest(it)}" } + "\n" +
                diagnostics.joinToString("\n") { "main  $it = ${ReactionNative.mainGetProperty(handle, it)}" } + "\n",
        )
        ReactionSession.stageScreenshotForTest(File(out, "$name-stage.png").absolutePath)
        capture("Nuvio Z Reactions", File(out, "$name-stage-window.png"))
        capture("Reaction harness", File(out, "$name-player.png"), "NuvioPlayerBridgeContainerWindow")
        runScript("--tree", "Reaction harness", File(out, "$name-windows.txt").absolutePath)
        // Ground truth for what the window shows on screen, when a WGC-capable python is configured.
        System.getenv("NUVIO_REACTION_HARNESS_WGC_PYTHON")?.let { python ->
            runScript("Reaction harness", File(out, "$name-wgc.png").absolutePath, script = "wgc-capture.py", python = python)
        }
    }

    private fun capture(title: String, file: File, descendantClass: String? = null) =
        runScript(*listOfNotNull(title, file.absolutePath, descendantClass).toTypedArray())

    private fun runScript(vararg args: String, script: String = "capture-window.py", python: String = "python") {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "scripts/reaction/$script") }
            .firstOrNull { it.isFile } ?: return
        runCatching {
            ProcessBuilder(listOf(python, file.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
                .waitFor(20, TimeUnit.SECONDS)
        }
    }
}
