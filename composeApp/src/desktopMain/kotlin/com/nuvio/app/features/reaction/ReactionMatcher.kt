package com.nuvio.app.features.reaction

import com.nuvio.app.features.reaction.ReactionFingerprinter.FRAME_MS
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One stretch where the reaction shows the episode: reaction time = [interceptMs] + [rate] * episode
 * time, for episode time in [episodeStartMs, episodeEndMs). [mirrored]: the reaction flipped the movie.
 */
internal data class MatchedSegment(
    val episodeStartMs: Long,
    val episodeEndMs: Long,
    val interceptMs: Double,
    val rate: Double,
    val mirrored: Boolean,
    /** Frames that agree with the line: how much evidence the segment has. */
    val support: Int,
) {
    fun reactionAt(episodeMs: Long): Long = (interceptMs + rate * episodeMs).roundToLong()

    fun toSyncSegment(): SyncSegment = SyncSegment(
        episodeStartMs = episodeStartMs,
        episodeEndMs = episodeEndMs,
        reactionStartMs = reactionAt(episodeStartMs),
        rate = rate,
        confidence = support.toDouble(),
    )
}

/**
 * Finds where the episode appears in the reaction (Stage 4, SyncMap). Pure; both tracks are sampled
 * on the same 125 ms grid. Prototyped against a synthetic compilation with known edits (a 40 s
 * intro, two copyright cuts, a 12 s freeze while the reactor talks, a mirrored stretch sped up 4 %,
 * an 8 % zoom with a colour change, a watermark): every segment found, mirroring and the 0.9615 rate
 * recovered, every matched moment within 19 ms of the truth.
 *
 *  1. Candidates: every (reaction frame, episode frame) pair whose hashes are within [MAX_DISTANCE]
 *     bits, plain or mirrored. Incremental: each pair is compared once however the tracks grow.
 *  2. Votes: in overlapping windows of reaction time, each frame votes for the offsets (reaction
 *     minus episode time) of its best candidates only (within a bit of its closest). A real match
 *     piles up on one offset. Voting with every candidate failed on slow shots: each frame matched
 *     its whole shot almost equally, so no window ever had a clear winner. A window that does is an
 *     anchor.
 *  3. Chains: an anchor joins the recent chain whose offset it continues (drifting no faster than a
 *     10 % speed change); otherwise it starts one. A cut (offset jumps) or a freeze (offset grows with
 *     the pause) starts a new segment, and a stray anchor ends up alone instead of splitting one.
 *  4. Line: per chain, the best candidate of each frame near the chain's offset, a trimmed least
 *     squares fit of reaction against episode time. Ties among equally good neighbouring frames go
 *     to the expected offset; picking the earliest biased every segment by a frame. A vote over the
 *     whole film adds the offsets most frames agree on, so a line is found even where no single
 *     window was clear enough to anchor. Lines within a frame of each other are one line.
 *  5. Path: every episode frame is scored against every line with ALL its frames' distances, not
 *     only the close ones (a log-likelihood of the distance: on a real line it centres near 22 bits
 *     even under a channel's logo, at a random offset near 31), and one best path through the film
 *     picks a line or none per frame, paying for each switch. So a line carries on through weak,
 *     dark or unread stretches until the evidence says the reaction cut, a stray false match
 *     cannot split a line, and a gap needs real evidence that the film is not shown.
 *  6. Each run of the path is refitted on its own frames, averaging which neighbouring reaction
 *     frame matches best: offsets between two 125 ms grid points come out between them.
 *
 * Measured on the first real test (TEM's Interstellar compilation under a full-frame translucent
 * logo, 2026-10-13): the chain-and-join assembly this replaced matched 32 % of the read film in 33
 * stretches, the one +10.06 s line split by 60 ms join tolerance on a 125 ms grid and by false
 * matches; the path put 97 % of it on that line.
 */
internal class ReactionMatcher(private val liveEpisode: FingerprintTrack, private val liveReaction: FingerprintTrack) {
    private class Candidate(val episodeIndex: Int, val distance: Int, val mirrored: Boolean)

    // Analysis keeps writing to the live tracks; each update works on a copy.
    private var episode = FingerprintTrack()
    private var reaction = FingerprintTrack()

    private var candidates = arrayOfNulls<MutableList<Candidate>>(0)
    private var joinedReaction = BooleanArray(0)
    private var joinedEpisode = BooleanArray(0)

    /** Compares every pair not compared yet, then aligns from scratch (alignment is cheap). */
    fun update(): List<MatchedSegment> {
        episode = liveEpisode.snapshot()
        reaction = liveReaction.snapshot()
        joinNewFrames()
        return align()
    }

    private fun joinNewFrames() {
        val reactionIndices = reaction.indices()
        val episodeIndices = episode.indices()
        if (candidates.size < reaction.size) {
            candidates = candidates.copyOf(reaction.size)
            joinedReaction = joinedReaction.copyOf(reaction.size)
        }
        if (joinedEpisode.size < episode.size) joinedEpisode = joinedEpisode.copyOf(episode.size)
        val oldEpisode = episodeIndices.filter { joinedEpisode[it] && episode.informative(it) }.toIntArray()
        val newEpisode = episodeIndices.filter { !joinedEpisode[it] && episode.informative(it) }.toIntArray()
        val allEpisode = (oldEpisode + newEpisode).sortedArray()
        val episodeHashes = LongArray(episode.size)
        for (e in allEpisode) episodeHashes[e] = episode.hash(e)

        IntStream.of(*reactionIndices).parallel().forEach { r ->
            if (!reaction.informative(r)) return@forEach
            val against = if (joinedReaction[r]) newEpisode else allEpisode
            if (against.isEmpty()) return@forEach
            val plain = reaction.hash(r)
            val mirror = reaction.mirror(r)
            var found: MutableList<Candidate>? = null
            for (e in against) {
                val h = episodeHashes[e]
                val dp = ReactionFingerprinter.distance(plain, h)
                val dm = ReactionFingerprinter.distance(mirror, h)
                if (dp <= MAX_DISTANCE) (found ?: ArrayList<Candidate>().also { found = it }) += Candidate(e, dp, false)
                if (dm <= MAX_DISTANCE) (found ?: ArrayList<Candidate>().also { found = it }) += Candidate(e, dm, true)
            }
            val add = found ?: return@forEach
            val merged = (candidates[r].orEmpty() + add).sortedBy { it.distance }
            candidates[r] = merged.take(MAX_CANDIDATES).toMutableList()
        }
        for (r in reactionIndices) if (reaction.informative(r)) joinedReaction[r] = true
        for (e in newEpisode) joinedEpisode[e] = true
    }

    private class Anchor(val reactionMs: Double, val offsetMs: Double, val mirrored: Boolean)

    private class Line(
        val intercept: Double, val rate: Double, val mirrored: Boolean,
        val episodeStart: Double, val episodeEnd: Double, val support: Int,
    )

    private fun align(): List<MatchedSegment> {
        val anchors = anchors()
        val chains = chain(anchors)
        val lines = cluster(chains.mapNotNull(::fit) + globalLines())
        if (lines.isEmpty()) return emptyList()
        return bestPath(lines).mapNotNull(::refine)
    }

    /**
     * Rate-1 lines from the offsets the closest candidates of every frame vote for, over the whole
     * film: a compilation that plays the film through shows one offset for minutes on end, even when
     * its frames are too far from the episode's for any 6 s window to stand out on its own.
     */
    private fun globalLines(): List<Line> {
        val votes = HashMap<Long, Int>()
        for (r in candidates.indices) {
            val list = candidates[r] ?: continue
            val closest = list.firstOrNull()?.distance ?: continue
            val seen = HashSet<Long>()
            for (c in list) {
                if (c.distance > closest + 1) break
                val k = key(c.mirrored, r - c.episodeIndex)
                if (seen.add(k)) votes.merge(k, 1, Int::plus)
            }
        }
        fun around(k: Long): Int = (-1..1).sumOf { votes[k + (it.toLong() shl 1)] ?: 0 }
        val peaks = votes.keys
            .filter { k -> around(k) >= GLOBAL_MIN_VOTES && (-2..2).all { d -> d == 0 || (votes[k + (d.toLong() shl 1)] ?: 0) <= votes.getValue(k) } }
            .sortedByDescending(::around)
            .take(GLOBAL_MAX_LINES)
        val episodeEnd = episode.size * FRAME_MS.toDouble()
        return peaks.map { k ->
            Line((k shr 1) * FRAME_MS.toDouble(), 1.0, k and 1L == 1L, 0.0, episodeEnd, around(k))
        }
    }

    /** Lines that predict the same reaction time to within about a frame are one line. */
    private fun cluster(lines: List<Line>): List<Line> {
        val kept = ArrayList<Line>()
        for (line in lines.sortedByDescending { it.support }) {
            val mid = (line.episodeStart + line.episodeEnd) / 2
            val same = kept.indexOfFirst { k ->
                k.mirrored == line.mirrored && abs(k.rate - line.rate) < 0.002 &&
                    abs((k.intercept + k.rate * mid) - (line.intercept + line.rate * mid)) <= SAME_LINE_MS
            }
            if (same < 0) {
                kept += line
            } else {
                val k = kept[same]
                kept[same] = Line(k.intercept, k.rate, k.mirrored, min(k.episodeStart, line.episodeStart), max(k.episodeEnd, line.episodeEnd), k.support + line.support)
            }
        }
        return kept
    }

    /** What one episode frame says about one line: its log-likelihood, 0 when it cannot tell. */
    private fun evidence(line: Line, e: Int): Float {
        val u = (line.intercept + line.rate * e * FRAME_MS) / FRAME_MS
        val r0 = kotlin.math.floor(u).toInt()
        if (r0 < 0 || r0 + 1 >= reaction.size) return OUTSIDE
        if (!episode.has(e) || episode.detail(e) < BLANK_DETAIL) return 0f
        val h = episode.hash(e)
        var best = Int.MAX_VALUE
        for (r in r0..r0 + 1) {
            if (!reaction.has(r)) continue
            val d = ReactionFingerprinter.distance(if (line.mirrored) reaction.mirror(r) else reaction.hash(r), h)
            if (d < best) best = d
        }
        if (best == Int.MAX_VALUE) return 0f
        return (1.9f - 0.5f * (best - 22)).coerceIn(-2f, 4f) * FRAME_WEIGHT
    }

    private class Run(val line: Line, val from: Int, val until: Int)

    /**
     * One best path over the episode's frames: in each, one of [lines] or none. Staying is free;
     * starting, ending or switching a line costs [SWITCH_COST], so a run has to be worth it.
     */
    private fun bestPath(lines: List<Line>): List<Run> {
        val frames = episode.size
        if (frames == 0) return emptyList()
        val n = lines.size
        val none = n
        val back = Array(frames) { ByteArray(n + 1) }
        require(n < 127)
        var cost = DoubleArray(n + 1) { if (it == none) 0.0 else -SWITCH_COST }
        val next = DoubleArray(n + 1)
        for (e in 0 until frames) {
            // The best two line states, for "switch from the best other line".
            var first = -1
            var second = -1
            for (i in 0 until n) {
                if (first < 0 || cost[i] > cost[first]) { second = first; first = i } else if (second < 0 || cost[i] > cost[second]) second = i
            }
            for (j in 0..n) {
                var from = j
                var value = cost[j]
                val other = if (first != j) first else second
                if (other >= 0 && cost[other] - SWITCH_COST > value) { from = other; value = cost[other] - SWITCH_COST }
                if (j != none && cost[none] - SWITCH_COST > value) { from = none; value = cost[none] - SWITCH_COST }
                back[e][j] = from.toByte()
                next[j] = value + if (j == none) 0.0 else evidence(lines[j], e).toDouble()
            }
            cost = next.copyOf()
        }
        val path = IntArray(frames)
        path[frames - 1] = cost.indices.maxBy { cost[it] }
        for (e in frames - 1 downTo 1) path[e - 1] = back[e][path[e]].toInt()
        val runs = ArrayList<Run>()
        var start = 0
        for (e in 1..frames) {
            if (e == frames || path[e] != path[start]) {
                if (path[start] != none && (e - start) * FRAME_MS >= MIN_SEGMENT_MS) runs += Run(lines[path[start]], start, e)
                start = e
            }
        }
        return runs
    }

    /**
     * The run's own line: per informative frame, which of the three reaction frames around the
     * predicted one is closest (ties to the prediction), and the intercept is their mean offset -
     * twice, the second time around the first answer. A mean of grid offsets can fall between
     * grid points; the median a chain uses cannot.
     */
    private fun refine(run: Run): MatchedSegment? {
        val line = run.line
        var intercept = line.intercept
        var support = 0
        repeat(2) {
            var sum = 0.0
            var count = 0
            for (e in run.from until run.until) {
                if (!episode.has(e) || episode.detail(e) < BLANK_DETAIL) continue
                val u = (intercept + line.rate * e * FRAME_MS) / FRAME_MS
                val center = u.roundToInt()
                var best = Int.MAX_VALUE
                var bestR = -1
                for (r in center - 1..center + 1) {
                    if (!reaction.has(r)) continue
                    val d = ReactionFingerprinter.distance(if (line.mirrored) reaction.mirror(r) else reaction.hash(r), episode.hash(e))
                    if (d < best || (d == best && abs(r - u) < abs(bestR - u))) { best = d; bestR = r }
                }
                if (bestR < 0 || best > REFINE_DISTANCE) continue
                sum += bestR * FRAME_MS - line.rate * e * FRAME_MS
                count++
            }
            if (count >= 8) intercept = sum / count
            support = count
        }
        return MatchedSegment(
            episodeStartMs = run.from * FRAME_MS,
            episodeEndMs = run.until * FRAME_MS,
            interceptMs = intercept,
            rate = line.rate,
            mirrored = line.mirrored,
            support = support,
        )
    }

    private fun anchors(): List<Anchor> {
        val n = candidates.size
        val out = ArrayList<Anchor>()
        var w0 = 0
        while (w0 < n - WINDOW / 2) {
            val w1 = min(n, w0 + WINDOW)
            // (mirrored, offset bin) -> frame -> best weight
            val votes = HashMap<Long, HashMap<Int, Int>>()
            for (r in w0 until w1) for (c in candidates[r].orEmpty()) {
                // Sorted by distance, so the first is the closest.
                if (c.distance > candidates[r]!!.first().distance + 1) break
                val bin = binOf((r - c.episodeIndex) * FRAME_MS.toDouble())
                val perFrame = votes.getOrPut(key(c.mirrored, bin)) { HashMap() }
                val weight = MAX_DISTANCE + 1 - c.distance
                if ((perFrame[r] ?: 0) < weight) perFrame[r] = weight
            }
            if (votes.isNotEmpty()) anchorOf(votes, w0, w1)?.let(out::add)
            w0 += WINDOW_STEP
        }
        return out
    }

    private fun anchorOf(votes: HashMap<Long, HashMap<Int, Int>>, w0: Int, w1: Int): Anchor? {
        var bestScore = -1
        var bestKey = 0L
        var bestCount = 0
        for (k in votes.keys) {
            val mirrored = k and 1L == 1L
            val bin = (k shr 1).toInt()
            val frames = HashMap<Int, Int>()
            for (b in bin - 1..bin + 1) votes[key(mirrored, b)]?.forEach { (r, w) -> if ((frames[r] ?: 0) < w) frames[r] = w }
            val score = frames.values.sum()
            if (score > bestScore) { bestScore = score; bestKey = k; bestCount = frames.size }
        }
        val mirrored = bestKey and 1L == 1L
        val bin = (bestKey shr 1).toInt()
        var second = 0
        for ((k, perFrame) in votes) {
            if ((k and 1L == 1L) == mirrored && abs((k shr 1).toInt() - bin) <= 3) continue
            second = max(second, perFrame.values.sum())
        }
        if (bestCount < max(8.0, 0.3 * (w1 - w0)) || bestScore < 1.5 * second) return null
        var sum = 0.0
        var weights = 0.0
        for (r in w0 until w1) for (c in candidates[r].orEmpty()) {
            if (c.mirrored != mirrored) continue
            val offset = (r - c.episodeIndex) * FRAME_MS.toDouble()
            if (abs(offset - bin * BIN_MS) <= 1.5 * BIN_MS) {
                val w = (MAX_DISTANCE + 1 - c.distance).toDouble()
                sum += offset * w
                weights += w
            }
        }
        return Anchor((w0 + w1) / 2.0 * FRAME_MS, sum / weights, mirrored)
    }

    private fun chain(anchors: List<Anchor>): List<List<Anchor>> {
        val chains = ArrayList<MutableList<Anchor>>()
        for (a in anchors) {
            var best: MutableList<Anchor>? = null
            var bestMiss = Double.MAX_VALUE
            for (c in chains) {
                val last = c.last()
                val dt = a.reactionMs - last.reactionMs
                if (dt > 30_000 || last.mirrored != a.mirrored) continue
                val miss = abs(a.offsetMs - last.offsetMs)
                if (miss <= 0.12 * dt + 300 && miss < bestMiss) { bestMiss = miss; best = c }
            }
            if (best != null) best += a else chains += mutableListOf(a)
        }
        return chains
    }

    /**
     * The chain's line. The rate comes from the anchors (Theil-Sen: the median slope over pairs, so a
     * few bad anchors cannot tilt it); the intercept is where the good candidates of every frame in
     * the chain are densest for that rate. Choosing one candidate per frame first and fitting those
     * did not survive slow shots: in a near-still shot the closest frame is a coin toss among
     * neighbours, and the fit tilted (a 1.000 rate came out 0.987).
     */
    private fun fit(chain: List<Anchor>): Line? {
        val mirrored = chain.first().mirrored
        val slopes = ArrayList<Double>()
        for (i in chain.indices) for (j in i + 1 until chain.size) {
            val dr = chain[j].reactionMs - chain[i].reactionMs
            if (dr >= 4_000) slopes += (chain[j].offsetMs - chain[i].offsetMs) / dr
        }
        // offset = r - e with r = a + rate * e, so d(offset)/dr = 1 - 1/rate.
        val slope = if (slopes.isEmpty()) 0.0 else median(slopes.toDoubleArray())
        var rate = (1.0 / (1.0 - slope)).coerceIn(0.9, 1.1)
        // A speed-up has to show: enough span, and more drift across it than the anchors scatter.
        // A 25 s chain of noisy anchors once claimed 0.974.
        val span = chain.last().reactionMs - chain.first().reactionMs
        val mid = chain.map { it.offsetMs - slope * it.reactionMs }.toDoubleArray().let(::median)
        val scatter = median(DoubleArray(chain.size) { abs(chain[it].offsetMs - slope * chain[it].reactionMs - mid) })
        if (abs(rate - 1.0) < RATE_SNAP || span < 40_000 || abs(slope) * span < 6 * (scatter + 50)) rate = 1.0

        val from = max(0, ((chain.first().reactionMs - WINDOW / 2 * FRAME_MS) / FRAME_MS).toInt())
        val to = min(candidates.size - 1, ((chain.last().reactionMs + WINDOW / 2 * FRAME_MS) / FRAME_MS).toInt())
        // intercept bin -> frame -> weight, every frame counted once per bin.
        val bins = HashMap<Int, HashMap<Int, Int>>()
        for (index in from..to) {
            val list = candidates[index] ?: continue
            val closest = list.firstOrNull { it.mirrored == mirrored }?.distance ?: continue
            for (c in list) {
                if (c.mirrored != mirrored || c.distance > closest + 2) continue
                val intercept = index * FRAME_MS - rate * c.episodeIndex * FRAME_MS
                val bin = Math.floorDiv(intercept.roundToLong(), INTERCEPT_BIN_MS).toInt()
                val weight = (MAX_DISTANCE + 1 - c.distance).let { it * it }
                val perFrame = bins.getOrPut(bin) { HashMap() }
                if ((perFrame[index] ?: 0) < weight) perFrame[index] = weight
            }
        }
        var peak = 0
        var peakScore = -1L
        for (bin in bins.keys) {
            val frames = HashMap<Int, Int>()
            for (b in bin - 2..bin + 2) bins[b]?.forEach { (f, w) -> if ((frames[f] ?: 0) < w) frames[f] = w }
            val score = frames.values.sumOf { it.toLong() }
            if (score > peakScore) { peakScore = score; peak = bin }
        }
        if (peakScore < 0) return null
        val line = (peak + 0.5) * INTERCEPT_BIN_MS

        // Inliers: per frame, the closest candidate on the line (within a frame of it).
        val es = ArrayList<Double>()
        val rs = ArrayList<Double>()
        for (index in from..to) {
            var best: Candidate? = null
            var bestKey = Double.MAX_VALUE
            for (c in candidates[index].orEmpty()) {
                if (c.mirrored != mirrored) continue
                val miss = abs(index * FRAME_MS - rate * c.episodeIndex * FRAME_MS - line)
                if (miss > FRAME_MS) continue
                val key = c.distance * 1_000.0 + miss
                if (key < bestKey) { bestKey = key; best = c }
            }
            if (best != null) { es += best.episodeIndex * FRAME_MS.toDouble(); rs += index * FRAME_MS.toDouble() }
        }
        if (es.size < 10) return null
        val e = es.toDoubleArray()
        val r = rs.toDoubleArray()
        if (rate != 1.0) {
            // A sped-up upload: the inliers are clean now, so least squares sharpens the rate.
            rate = leastSquares(e, r).second.coerceIn(0.9, 1.1)
            if (abs(rate - 1.0) < RATE_SNAP) rate = 1.0
        }
        val intercept = median(DoubleArray(e.size) { r[it] - rate * e[it] })
        return Line(intercept, rate, mirrored, e.min(), e.max() + FRAME_MS, e.size)
    }

    private fun key(mirrored: Boolean, bin: Int): Long = (bin.toLong() shl 1) or (if (mirrored) 1L else 0L)

    private fun binOf(offsetMs: Double): Int = (offsetMs / BIN_MS).roundToInt()

    private fun leastSquares(x: DoubleArray, y: DoubleArray): Pair<Double, Double> {
        val n = x.size
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx) }
        val b = if (sxx > 0) sxy / sxx else 1.0
        return (my - b * mx) to b
    }

    private fun median(values: DoubleArray): Double {
        val s = values.sortedArray()
        return if (s.isEmpty()) 0.0 else (s[s.size / 2] + s[(s.size - 1) / 2]) / 2
    }

    companion object {
        const val MAX_DISTANCE = 14
        private const val MAX_CANDIDATES = 48
        /** 6 s windows every 2 s. */
        private const val WINDOW = 48
        private const val WINDOW_STEP = 16
        private const val BIN_MS = 125.0
        private const val INTERCEPT_BIN_MS = 25L
        /** A rate this close to 1 is 1: real speed-ups are 2-10 %. */
        private const val RATE_SNAP = 0.004
        private const val BLANK_DETAIL = 12f
        private const val MIN_SEGMENT_MS = 2_000.0
        /** A refit counts a frame whose best neighbour is this close; a real line's median is ~22. */
        private const val REFINE_DISTANCE = 24
        /** Two lines this close (well under two grid steps) are one line seen twice. */
        private const val SAME_LINE_MS = 200.0
        /** Frames on one offset, across the film, before it is tried as a line. */
        private const val GLOBAL_MIN_VOTES = 24
        private const val GLOBAL_MAX_LINES = 12
        /** Neighbouring frames are far from independent (a still shot repeats its hash). */
        private const val FRAME_WEIGHT = 0.25f
        /** A line that would show reaction time outside the video cannot be on screen. */
        private const val OUTSIDE = -50f
        /**
         * What a cut, a start or an end of the film on screen must be worth: ~12 s of frames saying no.
         * At 30 one 8 s probe of the episode, read beside an unread stretch, pulled a line 28 s off
         * (Debug 93); a real cut is minutes of evidence either way.
         */
        private const val SWITCH_COST = 40.0

        fun toSyncMap(segments: List<MatchedSegment>, nudgeMs: Long = 0): SyncMap =
            SyncMap(segments.map { it.toSyncSegment().let { s -> s.copy(reactionStartMs = s.reactionStartMs + nudgeMs) } })
    }
}
