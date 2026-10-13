package com.nuvio.app.features.reaction

import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A real session's reaction read again through today's pipeline (parallel readers, the steadied
 * movie box, overlay-normalised hashes), timed, then matched against the episode track that session
 * saved. Needs a player_bridge.dll with the reaction exports (the installed debug app's will do when
 * the native code has not changed). Skipped unless pointed at it all:
 *
 *   NUVIO_REACTION_REANALYSE_VIDEO=<the reaction's analysis copy, e.g. %LOCALAPPDATA%\Nuvio\Cache\reaction\media\yt-*.mp4>
 *   NUVIO_REACTION_REANALYSE_DURATION_S=<its duration in seconds>
 *   NUVIO_REACTION_REANALYSE_EPISODE=<the session's ep-*.bin>
 *   NUVIO_REACTION_REANALYSE_OUT=<folder for the new -r2.bin and layout>
 *   NUVIO_REACTION_NATIVE_RESOURCES=<e.g. C:\Program Files\Nuvio Z Debug\app\resources>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionReanalyseTest*" --rerun
 */
class ReactionReanalyseTest {
    @Test
    fun readTheReactionAgainAndMatch() {
        val video = System.getenv("NUVIO_REACTION_REANALYSE_VIDEO") ?: return
        val durationMs = ((System.getenv("NUVIO_REACTION_REANALYSE_DURATION_S") ?: return).toDouble() * 1000).toLong()
        val episodeFile = File(System.getenv("NUVIO_REACTION_REANALYSE_EPISODE") ?: return)
        val out = File(System.getenv("NUVIO_REACTION_REANALYSE_OUT") ?: return).apply { mkdirs() }
        System.getenv("NUVIO_REACTION_NATIVE_RESOURCES")?.let { System.setProperty("compose.application.resources.dir", it) }
        assertTrue(ReactionNative.available, "player_bridge.dll with the reaction exports is needed")

        val reaction = FingerprintTrack()
        val started = System.currentTimeMillis()
        var timeline: ReactionLayoutTimeline? = null
        runBlocking {
            timeline = ReactionTimelineAnalyzer.analyze(video, 0, durationMs, reaction, {}, {}, {})
        }
        val readMs = System.currentTimeMillis() - started
        println("reaction read in ${readMs / 1000} s: ${reaction.count} frames, ${timeline!!.epochs.size} epochs")
        val movies = timeline!!.epochs.groupingBy { it.layout.movie?.rect }.eachCount().entries.sortedByDescending { it.value }.take(5)
        println("movie boxes (epochs): " + movies.joinToString { "${it.key} x${it.value}" })
        val reactors = timeline!!.epochs.map { it.layout.reactors.size }.sorted()
        println("reactors per epoch: median ${reactors[reactors.size / 2]}, max ${reactors.last()}")
        DataOutputStream(File(out, video.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.') + "-r2.bin").outputStream().buffered()).use {
            it.writeInt(1)
            reaction.write(it)
        }

        val episode = DataInputStream(episodeFile.inputStream().buffered()).use { check(it.readInt() == 1); FingerprintTrack.read(it) }
        val matchStarted = System.currentTimeMillis()
        val segments = ReactionMatcher(episode, reaction).update()
        val covered = segments.sumOf { it.episodeEndMs - it.episodeStartMs }
        val episodeMs = episode.size * ReactionFingerprinter.FRAME_MS
        println("matched ${segments.size} stretches in ${System.currentTimeMillis() - matchStarted} ms, ${covered / 1000} s of ${episodeMs / 1000} s (${"%.1f".format(100.0 * covered / episodeMs)} %)")
        segments.forEach {
            println(
                "  ep ${it.episodeStartMs / 1000.0}-${it.episodeEndMs / 1000.0} s  offset ${"%.3f".format((it.reactionAt(it.episodeStartMs) - it.episodeStartMs) / 1000.0)} s" +
                    (if (it.rate != 1.0) "  x${"%.4f".format(it.rate)}" else "") + (if (it.mirrored) "  mirrored" else "") + "  support ${it.support}",
            )
        }
    }
}
