package com.nuvio.app.features.reaction

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import java.awt.Rectangle
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stage 4 in the real player: Nuvio Z's NativePlayerController plays the episode, ReactionSession
 * indexes it, analyses the reaction, matches the two and drives the stage from the matched map - no
 * offset is ever entered. The reaction's position is checked against the edit list it was made from,
 * not against the session's own map. Opens windows and plays video, so it only runs when pointed at
 * media (see ReactionSyncFixtureTest for the compilation used):
 *
 *   NUVIO_REACTION_AUTO_EPISODE=<film>  NUVIO_REACTION_AUTO_REACTION=<compilation>  NUVIO_REACTION_AUTO_EDL=<edit list>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionAutoSyncHarnessTest*"
 *
 * Writes build/reaction-auto/timeline.csv (truth error per sample) and report.txt.
 */
class ReactionAutoSyncHarnessTest {
    private data class Edit(val episodeFrom: Double, val episodeTo: Double, val reactionAt: Double, val rate: Double)

    private val out = File("build/reaction-auto").apply { mkdirs() }
    private val timeline = StringBuilder("t_ms,phase,master_ms,follower_ms,truth_ms,truth_error_ms,visibility,segments\n")
    private val report = StringBuilder()
    private val started = System.currentTimeMillis()
    private var edits: List<Edit> = emptyList()

    private fun truthMs(episodeMs: Long): Long? {
        val e = episodeMs / 1000.0
        val edit = edits.firstOrNull { e >= it.episodeFrom && e < it.episodeTo } ?: return null
        return ((edit.reactionAt + (e - edit.episodeFrom) * edit.rate) * 1000).toLong()
    }

    @Test
    fun theReactionFindsItsPlaceInTheEpisode() {
        val episode = System.getenv("NUVIO_REACTION_AUTO_EPISODE") ?: return
        val reaction = System.getenv("NUVIO_REACTION_AUTO_REACTION") ?: return
        edits = File(System.getenv("NUVIO_REACTION_AUTO_EDL") ?: return).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split(Regex("\\s+")).let { Edit(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) } }
        System.setProperty("nuvio.reactionMode", "1")
        val data = File(out, "data")
        // A cold start: nothing analysed or indexed before.
        data.deleteRecursively()
        System.setProperty("nuvio.reactionDataDir", data.absolutePath)
        assertTrue(ReactionNative.available, "player_bridge.dll lacks the reaction exports - rebuild it")

        val host = NativePlayerHost()
        val frame = JFrame("Reaction auto harness")
        SwingUtilities.invokeAndWait {
            frame.setBounds(40, 40, 1280, 760)
            frame.contentPane.add(host)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.isVisible = true
        }
        val controller = NativePlayerController(host)
        var playbackError: String? = null
        // Start mid-film, the way a resumed episode does: 60 s is inside the first matched stretch.
        SwingUtilities.invokeAndWait {
            controller.attach(File(episode).toURI().toString(), emptyMap(), true, 60_000L, 1, false) { playbackError = it }
        }
        try {
            val handle = waitFor("player attached and panel injected", 30_000) {
                ReactionSession.debugState.takeIf { it.playerHandle != 0L && it.uiReady }
            }.playerHandle
            ReactionNative.mainSetProperty(handle, "mute", "yes")
            SwingUtilities.invokeAndWait {
                frame.contentPane.revalidate()
                frame.contentPane.repaint()
            }
            ReactionSession.extraStageOptionsForTest = listOf("ao=null")
            ReactionSession.monitorOverride = Rectangle(1330, 40, 560, 315)
            assertTrue(ReactionSession.onControlsEvent(handle, "reactionMode", 0.0))
            val chosen = System.currentTimeMillis()
            ReactionSession.startSource(ReactionSource.LocalFile(reaction))

            // 1. Matching: the reaction waits (paused, dimmed) until it knows where it is, then joins.
            record("matching", 2_000)
            val joined = waitFor("the first matched stretch playing", 5 * 60_000) {
                ReactionSession.debugState.takeIf { it.matchedSegments > 0 && it.visibility == StageVisibility.SHOWING && it.followerPaused == false }
            }
            report.appendLine("joined ${(System.currentTimeMillis() - chosen) / 1000.0} s after choosing the reaction, at episode ${joined.masterMs} ms")
            record("settle-1", 6_000)
            check("stretch 1 (plain)", record("measure-1", 4_000))

            // 2. A copyright cut (episode 150-180 s is not in the reaction): reactions pause, say when
            //    they resume, and pick up on the far side.
            NativePlayerBridge.seekTo(handle, 170_000)
            val gap = record("cut", 2_500).last()
            assertEquals(StageVisibility.GAP, gap.visibility, "in the cut")
            assertTrue((gap.resumesInMs ?: 0) in 5_000..12_000, "resumes in ${gap.resumesInMs} ms")
            waitFor("reactions resume after the cut", 20_000) {
                ReactionSession.debugState.takeIf { it.visibility == StageVisibility.SHOWING && (it.masterMs ?: 0) > 180_500 }
            }
            record("settle-2", 6_000)
            check("stretch 2 (after the cut)", record("measure-2", 3_000))

            // 3. The mirrored stretch, sped up 4 %: the rate keeps it on.
            NativePlayerBridge.seekTo(handle, 400_000)
            record("settle-3", 6_000)
            check("stretch 3 (mirrored, x0.96)", record("measure-3", 8_000))

            // 4. The zoomed, recoloured stretch.
            NativePlayerBridge.seekTo(handle, 600_000)
            record("settle-4", 6_000)
            check("stretch 4 (zoom + colour)", record("measure-4", 4_000))

            val state = ReactionSession.debugState
            report.appendLine("final: segments=${state.matchedSegments} reactionAnalysed=${state.reactionAnalysed} episodeIndexed=${state.episodeIndexed}")
            assertTrue(state.autoSync, "fell back to manual sync")
            ReactionSession.onControlsEvent(handle, "reactionStop", 0.0)
            record("stopped", 1_000)
            assertEquals(null, playbackError)
        } finally {
            File(out, "timeline.csv").writeText(timeline.toString())
            File(out, "report.txt").writeText(report.toString())
            println(report)
            ReactionSession.monitorOverride = null
            ReactionSession.extraStageOptionsForTest = emptyList()
            SwingUtilities.invokeAndWait {
                controller.dispose()
                frame.dispose()
            }
        }
    }

    /** Every sample showing reactions sits within 150 ms of the truth (sampling is one 40 ms tick). */
    private fun check(phase: String, samples: List<ReactionDebugState>) {
        val errors = samples.filter { it.visibility == StageVisibility.SHOWING }.mapNotNull { s ->
            val master = s.masterMs ?: return@mapNotNull null
            val follower = s.followerMs ?: return@mapNotNull null
            truthMs(master)?.let { follower - it }
        }
        val worst = errors.maxOfOrNull { abs(it) }
        report.appendLine("$phase: ${errors.size} samples, median ${errors.sorted().getOrNull(errors.size / 2)} ms, worst $worst ms")
        assertTrue(errors.size >= samples.size / 2, "$phase: reactions not showing (${errors.size} of ${samples.size})")
        assertTrue(worst != null && worst < 150, "$phase: worst error $worst ms")
    }

    private fun record(phase: String, durationMs: Long): List<ReactionDebugState> {
        val samples = mutableListOf<ReactionDebugState>()
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val s = ReactionSession.debugState
            samples += s
            val truth = s.masterMs?.let(::truthMs)
            val error = if (truth != null && s.followerMs != null) s.followerMs - truth else null
            timeline.append("${System.currentTimeMillis() - started},$phase,${s.masterMs},${s.followerMs},$truth,$error,${s.visibility},${s.matchedSegments}\n")
            Thread.sleep(100)
        }
        return samples
    }

    private fun <T> waitFor(what: String, timeoutMs: Long, probe: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            probe()?.let { return it }
            record("wait", 100)
        }
        File(out, "timeline.csv").writeText(timeline.toString())
        fail("timed out waiting for $what; last state ${ReactionSession.debugState}")
    }
}
