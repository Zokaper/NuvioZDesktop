package com.nuvio.app.features.reaction

import com.nuvio.app.features.reaction.ReactionFingerprinter.FRAME_MS
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The matcher on synthetic fingerprints with the edit list of the synthetic compilation it was
 * prototyped on: a 40 s intro, two copyright cuts, a 12 s freeze while the reactor talks, a mirrored
 * stretch sped up 4 %, and a stretch whose hashes are noisier (a zoom plus a colour change).
 */
class ReactionMatcherTest {
    private data class Edit(val episodeFrom: Double, val episodeTo: Double, val reactionAt: Double, val rate: Double = 1.0, val mirrored: Boolean = false, val noise: Int = 3)

    private val edits = listOf(
        Edit(0.0, 150.0, 40.0),
        Edit(180.0, 330.0, 190.0),
        Edit(330.0, 480.0, 352.0, rate = 1 / 1.04, mirrored = true),
        Edit(520.0, 700.0, 496.2308, noise = 6),
    )

    /** An episode: shots of 2-8 s whose hash drifts a bit from frame to frame; some dark stretches. */
    private fun episode(seconds: Int, seed: Int): FingerprintTrack {
        val r = Random(seed)
        val track = FingerprintTrack()
        var hash = r.nextLong()
        var shotLeft = 0
        var dark = false
        for (i in 0 until seconds * ReactionFingerprinter.FPS) {
            if (shotLeft == 0) {
                hash = r.nextLong() and MASK
                shotLeft = r.nextInt(16, 64)
                dark = r.nextInt(12) == 0
            }
            shotLeft--
            if (r.nextInt(4) == 0) hash = hash xor (1L shl r.nextInt(63))
            track.set(i, FrameFingerprint(hash, r.nextLong() and MASK, if (dark) 3f else 30f))
        }
        return track
    }

    private fun noisy(hash: Long, bits: Int, r: Random): Long {
        var h = hash
        repeat(r.nextInt(bits + 1)) { h = h xor (1L shl r.nextInt(63)) }
        return h
    }

    /** The reaction those edits make: unrelated footage outside them, a freeze after the second. */
    private fun reaction(episode: FingerprintTrack, seconds: Int, seed: Int): FingerprintTrack {
        val r = Random(seed)
        val track = FingerprintTrack()
        for (i in 0 until seconds * ReactionFingerprinter.FPS) {
            val t = i * FRAME_MS / 1000.0
            val edit = edits.firstOrNull { e -> t >= e.reactionAt && t < e.reactionAt + (e.episodeTo - e.episodeFrom) * e.rate }
            val frozen = t >= 340.0 && t < 352.0
            val shown = when {
                edit != null -> ((edit.episodeFrom + (t - edit.reactionAt) / edit.rate) * ReactionFingerprinter.FPS).roundToInt()
                frozen -> (329.875 * ReactionFingerprinter.FPS).roundToInt()
                else -> null
            }
            val fingerprint = if (shown == null || !episode.has(shown)) {
                FrameFingerprint(r.nextLong() and MASK, r.nextLong() and MASK, 30f)
            } else {
                val source = episode.hash(shown)
                val mirrored = edit?.mirrored == true
                val n = edit?.noise ?: 3
                FrameFingerprint(
                    hash = if (mirrored) r.nextLong() and MASK else noisy(source, n, r),
                    mirrorHash = if (mirrored) noisy(source, n, r) else r.nextLong() and MASK,
                    detail = episode.detail(shown),
                )
            }
            track.set(i, fingerprint)
        }
        return track
    }

    @Test
    fun findsEveryStretchWithItsRateAndMirroring() {
        val ep = episode(734, seed = 11)
        val segments = ReactionMatcher(ep, reaction(ep, 700, seed = 12)).update()
        assertEquals(4, segments.size, segments.joinToString("\n"))
        edits.zip(segments).forEach { (edit, found) ->
            assertEquals(edit.mirrored, found.mirrored, "$found")
            assertTrue(abs(found.rate - edit.rate) < 0.002, "rate ${found.rate} for $edit")
            // The very ends are only as exact as the frames there (a dark shot can hide a second).
            assertTrue(abs(found.episodeStartMs / 1000.0 - edit.episodeFrom) <= 6.0, "start of $found for $edit")
            assertTrue(abs(found.episodeEndMs / 1000.0 - edit.episodeTo) <= 6.0, "end of $found for $edit")
            var e = edit.episodeFrom + 6
            while (e < edit.episodeTo - 6) {
                val truth = (edit.reactionAt + (e - edit.episodeFrom) * edit.rate) * 1000
                val error = abs(found.reactionAt((e * 1000).toLong()) - truth)
                assertTrue(error <= 70, "error ${error.roundToInt()} ms at episode $e s in $found")
                e += 0.5
            }
        }
    }

    @Test
    fun anUnrelatedReactionMatchesNothing() {
        val ep = episode(600, seed = 21)
        val other = episode(600, seed = 22)
        assertEquals(emptyList(), ReactionMatcher(ep, other).update())
    }

    @Test
    fun growingTracksGiveTheSameAnswerAsWholeOnes() {
        val ep = episode(734, seed = 31)
        val full = reaction(ep, 700, seed = 32)
        val whole = ReactionMatcher(ep, full).update()

        // The same data arriving the way analysis delivers it: both sides in pieces, out of step.
        val epLive = FingerprintTrack()
        val reLive = FingerprintTrack()
        val matcher = ReactionMatcher(epLive, reLive)
        var last: List<MatchedSegment> = emptyList()
        for (step in 0 until 10) {
            for (i in step * ep.size / 10 until (step + 1) * ep.size / 10) epLive.set(i, FrameFingerprint(ep.hash(i), ep.mirror(i), ep.detail(i)))
            for (i in step * full.size / 10 until (step + 1) * full.size / 10) reLive.set(i, FrameFingerprint(full.hash(i), full.mirror(i), full.detail(i)))
            last = matcher.update()
        }
        // Equal up to ties: the per-frame candidate cap keeps equally good frames in arrival order.
        assertEquals(whole.size, last.size, "$whole\n$last")
        whole.zip(last).forEach { (a, b) ->
            assertTrue(abs(a.episodeStartMs - b.episodeStartMs) <= FRAME_MS && abs(a.episodeEndMs - b.episodeEndMs) <= FRAME_MS, "$a / $b")
            assertTrue(abs(a.rate - b.rate) < 0.001 && abs(a.interceptMs - b.interceptMs) < 40 && a.mirrored == b.mirrored, "$a / $b")
        }
    }

    /**
     * TEM's Interstellar compilation, as the first real test saw it: the film played through at one
     * offset, but under a logo every frame's hash is 14-29 bits from the episode's (so hardly any
     * frame is within [ReactionMatcher.MAX_DISTANCE]), a recap shows an earlier scene for 5 s, and
     * of the episode only the planner's probes and the first minutes are read yet.
     */
    @Test
    fun aFilmPlayedThroughIsOneStretchThroughNoisyAndUnreadParts() {
        val seconds = 1800
        val ep = episode(seconds, seed = 41)
        val r = Random(42)
        val fps = ReactionFingerprinter.FPS
        val offset = 10 * fps
        val reaction = FingerprintTrack()
        for (i in 0 until (seconds + 20) * fps) {
            val e = i - offset
            reaction.set(
                i,
                if (e in 0 until ep.size) FrameFingerprint(flip(ep.hash(e), 14 + r.nextInt(16), r), r.nextLong() and MASK, ep.detail(e))
                else FrameFingerprint(r.nextLong() and MASK, r.nextLong() and MASK, 30f),
            )
        }
        for (k in 0 until 5 * fps) reaction.set(1500 * fps + k, FrameFingerprint(noisy(ep.hash(200 * fps + k), 3, r), r.nextLong() and MASK, 30f))
        val read = FingerprintTrack()
        for (e in 0 until ep.size) {
            if (e < 180 * fps || (e / fps) % 120 < 8) read.set(e, FrameFingerprint(ep.hash(e), ep.mirror(e), ep.detail(e)))
        }
        // The read part's frames that are close enough for the old rule: a handful.
        val segments = ReactionMatcher(read, reaction).update()
        val onLine = segments.filter { !it.mirrored && it.rate == 1.0 && abs(it.interceptMs - offset * FRAME_MS) <= 70 }
        val covered = onLine.sumOf { it.episodeEndMs - it.episodeStartMs }
        assertTrue(covered >= 0.9 * seconds * 1000, "${covered / 1000} s on the line of $seconds s: $segments")
    }

    private fun flip(hash: Long, bits: Int, r: Random): Long {
        var h = hash
        val chosen = HashSet<Int>()
        while (chosen.size < bits) chosen += r.nextInt(63)
        for (b in chosen) h = h xor (1L shl b)
        return h
    }

    @Test
    fun theSyncMapCarriesTheNudge() {
        val segment = MatchedSegment(10_000, 20_000, interceptMs = 5_000.0, rate = 1.0, mirrored = false, support = 50)
        val map = ReactionMatcher.toSyncMap(listOf(segment), nudgeMs = 300)
        val at = map.locate(12_000) as SyncLocation.InSegment
        assertEquals(17_300, at.reactionMs)
    }

    private companion object {
        const val MASK = (1L shl 63) - 1
    }
}
