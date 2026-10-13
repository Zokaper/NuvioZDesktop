package com.nuvio.app.features.reaction

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The matcher replayed on fingerprint tracks a real session saved (the app's `reaction/fingerprints`
 * folder, copied): no video and no player needed, so a field report can be re-run after every matcher
 * change. Skipped unless pointed at a copy:
 *
 *   NUVIO_REACTION_REPLAY_DIR=<folder with one ep-*.bin and one *-r.bin>
 *   NUVIO_REACTION_REPLAY_OFFSET_S=<optional: the film's known offset, reaction minus episode, seconds>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionReplayTest*" --rerun
 *
 * First used on the desktop PC's test of 2026-10-13: TEM's ~3 h Interstellar compilation, which plays
 * the film through at +10.06 s under a full-frame translucent logo; the episode read to 2 h 1 m.
 */
class ReactionReplayTest {
    @Test
    fun replayASavedSession() {
        val dir = File(System.getenv("NUVIO_REACTION_REPLAY_DIR") ?: return)
        val files = dir.listFiles().orEmpty()
        val episodeFile = files.single { it.name.startsWith("ep-") && it.name.endsWith(".bin") }
        val reactionFile = files.single { it.name.endsWith("-r.bin") || it.name.endsWith("-r2.bin") }
        val episode = read(episodeFile)
        val reaction = read(reactionFile)
        println("episode ${episode.count} frames (${episode.size * FRAME_MS / 1000} s), reaction ${reaction.count} frames (${reaction.size * FRAME_MS / 1000} s)")

        val started = System.currentTimeMillis()
        val segments = ReactionMatcher(episode, reaction).update()
        val took = System.currentTimeMillis() - started
        val covered = segments.sumOf { it.episodeEndMs - it.episodeStartMs }
        val episodeMs = episode.size * FRAME_MS
        println("matched ${segments.size} stretches in $took ms, ${covered / 1000} s of ${episodeMs / 1000} s (${"%.1f".format(100.0 * covered / episodeMs)} %)")
        segments.forEach {
            println(
                "  ep ${it.episodeStartMs / 1000.0}-${it.episodeEndMs / 1000.0} s  offset ${"%.3f".format((it.reactionAt(it.episodeStartMs) - it.episodeStartMs) / 1000.0)} s" +
                    (if (it.rate != 1.0) "  x${"%.4f".format(it.rate)}" else "") + (if (it.mirrored) "  mirrored" else "") + "  support ${it.support}",
            )
        }
        val known = System.getenv("NUVIO_REACTION_REPLAY_OFFSET_S")?.toDoubleOrNull() ?: return
        val onKnown = segments.filter { abs((it.reactionAt(it.episodeStartMs) - it.episodeStartMs) / 1000.0 - known) < 0.2 }
            .sumOf { it.episodeEndMs - it.episodeStartMs }
        println("on the known +$known s line: ${onKnown / 1000} s (${"%.1f".format(100.0 * onKnown / episodeMs)} %)")
        assertTrue(onKnown > 0, "the known line was not found")
    }

    private fun read(file: File): FingerprintTrack = DataInputStream(file.inputStream().buffered()).use { input ->
        check(input.readInt() == 1) { "unknown track version in $file" }
        FingerprintTrack.read(input)
    }

    private companion object {
        const val FRAME_MS = ReactionFingerprinter.FRAME_MS
    }
}
