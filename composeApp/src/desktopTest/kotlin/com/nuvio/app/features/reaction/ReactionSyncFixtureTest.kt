package com.nuvio.app.features.reaction

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stage 4 end to end on real video, through the production path: the reaction is decoded by a
 * windowless libmpv, its layout found by the detector, the movie box fingerprinted; the episode is
 * indexed the same way; the matcher's map is scored against the edit list the reaction was made from.
 * Skipped unless pointed at media:
 *
 *   NUVIO_REACTION_SYNC_EPISODE=<film>  NUVIO_REACTION_SYNC_REACTION=<compilation made from it>
 *   NUVIO_REACTION_SYNC_EDL=<lines: episodeFrom episodeTo reactionAt rate mirrored(yes/no), seconds>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionSyncFixtureTest*"
 *
 * The compilation used so far: Tears of Steel (CC BY 3.0, Blender Foundation) composited into the
 * movie box of a real LoneReact compilation with libmpv encode mode - a 40 s intro of other footage,
 * two copyright cuts, a 12 s freeze, a mirrored stretch sped up 4 %, an 8 % zoom with a colour change
 * and a watermark. Target (plan, R5): 95 % of matched episode time within +-100 ms.
 */
class ReactionSyncFixtureTest {
    private data class Edit(val episodeFrom: Double, val episodeTo: Double, val reactionAt: Double, val rate: Double)

    @Test
    fun theMapMatchesTheEditList() {
        val episodePath = System.getenv("NUVIO_REACTION_SYNC_EPISODE") ?: return
        val reactionPath = System.getenv("NUVIO_REACTION_SYNC_REACTION") ?: return
        val edits = File(System.getenv("NUVIO_REACTION_SYNC_EDL") ?: return).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split(Regex("\\s+")).let { Edit(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) } }
        assertTrue(ReactionNative.available, "player_bridge.dll with the reaction exports is needed")

        val reaction = FingerprintTrack()
        val episode = FingerprintTrack()
        val started = System.currentTimeMillis()
        val timeline = runBlocking {
            ReactionTimelineAnalyzer.analyze(reactionPath, 0, null, reaction, {}, {}, {})
        }
        val reactionDone = System.currentTimeMillis()
        runBlocking {
            ReactionEpisodeIndexer.index(
                EpisodeSource(episodePath, emptyList(), "fixture", null), episode,
                0 until 4 * 3600 * ReactionFingerprinter.FPS, { false }, {}, {},
            )
        }
        val episodeDone = System.currentTimeMillis()
        val segments = ReactionMatcher(episode, reaction).update()
        val matchDone = System.currentTimeMillis()

        val report = StringBuilder()
        report.appendLine("reaction: ${reaction.count} frames, ${timeline.epochs.size} layout epochs, movie ${timeline.epochs.map { it.layout.movie?.rect }.distinct()}, ${(reactionDone - started) / 1000.0} s")
        report.appendLine("episode: ${episode.count} frames, ${(episodeDone - reactionDone) / 1000.0} s; match ${(matchDone - episodeDone)} ms")
        segments.forEach { report.appendLine("  $it  reaction@start=${it.reactionAt(it.episodeStartMs) / 1000.0}") }
        timeline.epochs.forEach { report.appendLine("  epoch ${it.startMs / 1000.0}-${it.endMs / 1000.0}s movie ${it.layout.movie?.rect}") }
        // Where the true pairs disagree (distance over the candidate limit, or a blank frame).
        for (edit in edits) {
            var runStart: Double? = null
            var e = edit.episodeFrom
            val notes = ArrayList<String>()
            while (e <= edit.episodeTo) {
                val ei = Math.round(e * ReactionFingerprinter.FPS).toInt()
                val ri = Math.round((edit.reactionAt + (e - edit.episodeFrom) * edit.rate) * ReactionFingerprinter.FPS).toInt()
                val bad = e < edit.episodeTo && (!episode.has(ei) || !reaction.has(ri) || run {
                    val h = if (edit.rate < 1) reaction.mirror(ri) else reaction.hash(ri)
                    ReactionFingerprinter.distance(h, episode.hash(ei)) > ReactionMatcher.MAX_DISTANCE || !reaction.informative(ri) || !episode.informative(ei)
                })
                if (bad && runStart == null) runStart = e
                if (!bad && runStart != null) {
                    if (e - runStart >= 2) notes += "%.1f-%.1f".format(runStart, e)
                    runStart = null
                }
                e += 1.0 / ReactionFingerprinter.FPS
            }
            report.appendLine("  edit ${edit.episodeFrom}-${edit.episodeTo}: disagreeing stretches (episode s) $notes")
        }

        var total = 0
        var missing = 0
        val errors = ArrayList<Double>()
        for (edit in edits) {
            var e = edit.episodeFrom + 0.05
            while (e < edit.episodeTo) {
                total++
                val ms = (e * 1000).toLong()
                val found = segments.firstOrNull { ms >= it.episodeStartMs && ms < it.episodeEndMs }
                if (found == null) missing++ else errors += abs(found.reactionAt(ms) / 1000.0 - (edit.reactionAt + (e - edit.episodeFrom) * edit.rate)) * 1000
                e += 0.1
            }
        }
        errors.sort()
        val within = errors.count { it <= 100 }
        report.appendLine(
            "coverage %.1f %%, within 100 ms %.1f %% of the edit list; error p50 %.0f p95 %.0f ms".format(
                100.0 * (total - missing) / total, 100.0 * within / total,
                errors.getOrElse(errors.size / 2) { 0.0 }, errors.getOrElse((errors.size * 0.95).toInt()) { 0.0 },
            ),
        )
        File("build/reaction-sync-fixture.txt").writeText(report.toString())
        println(report)
        assertTrue(within >= 0.95 * total, report.toString())
    }
}
