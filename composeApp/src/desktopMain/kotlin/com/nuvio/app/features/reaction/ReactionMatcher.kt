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
 *     to the expected offset; picking the earliest biased every segment by a frame.
 *  5. Overlaps in episode time (a replayed scene) go to the better supported segment, segments on
 *     one line are joined across the hole between them, and each is grown along its line while the
 *     frames still agree (through blank episode frames, which cannot disagree).
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
        val lines = chains.mapNotNull(::fit)
        val resolved = merge(resolve(lines))
        val grown = resolved.map { grow(it, resolved) }
        return merge(resolve(grown)).map {
            MatchedSegment(
                episodeStartMs = it.episodeStart.roundToLong(),
                episodeEndMs = it.episodeEnd.roundToLong(),
                interceptMs = it.intercept,
                rate = it.rate,
                mirrored = it.mirrored,
                support = it.support,
            )
        }
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

    /** Overlapping episode time goes to the better supported line; a line inside another is dropped. */
    private fun resolve(lines: List<Line>): List<Line> {
        val kept = ArrayList<Line>()
        for (line in lines.sortedByDescending { it.support }) {
            var start = line.episodeStart
            var end = line.episodeEnd
            var dropped = false
            for (k in kept) {
                if (min(end, k.episodeEnd) <= max(start, k.episodeStart)) continue
                if (start >= k.episodeStart && end <= k.episodeEnd) { dropped = true; break }
                if (start < k.episodeStart) end = min(end, k.episodeStart) else start = max(start, k.episodeEnd)
            }
            if (!dropped && end - start >= MIN_SEGMENT_MS) {
                kept += Line(line.intercept, line.rate, line.mirrored, start, end, line.support)
            }
        }
        return kept.sortedBy { it.episodeStart }
    }

    /**
     * Joins neighbours that lie on one line, however far apart: nothing in between says otherwise (a
     * reaction cutting to a full-screen reactor cam while the film runs on, or a dark scene with no
     * usable frames), and the same line after the hole means the reaction kept playing the episode.
     * Not across another stretch of the reaction (a replay sitting between them).
     */
    private fun merge(lines: List<Line>): List<Line> {
        val sorted = lines.sortedBy { it.episodeStart }
        val out = ArrayList<Line>()
        for (line in sorted) {
            val previous = out.lastOrNull()
            val joinable = previous != null && previous.mirrored == line.mirrored && abs(previous.rate - line.rate) < 0.002 &&
                abs((previous.intercept + previous.rate * line.episodeStart) - (line.intercept + line.rate * line.episodeStart)) <= 60 &&
                sorted.none { other ->
                    other !== line && other !== previous &&
                        overlaps(other.reactionStart, other.reactionEnd, previous.reactionEnd, line.reactionStart)
                }
            if (joinable) {
                out[out.size - 1] = Line(
                    previous!!.intercept, previous.rate, previous.mirrored,
                    previous.episodeStart, max(previous.episodeEnd, line.episodeEnd), previous.support + line.support,
                )
            } else {
                out += line
            }
        }
        return out
    }

    private fun overlaps(a0: Double, a1: Double, b0: Double, b1: Double) = min(a1, b1) > max(a0, b0)

    private val Line.reactionStart get() = intercept + rate * episodeStart
    private val Line.reactionEnd get() = intercept + rate * episodeEnd

    /**
     * Extends a line's ends while the frames it predicts still agree. A blank episode frame (black, a
     * fade) cannot disagree with anything: growth carries on through it, and keeps that stretch once
     * a frame agrees again or the episode itself begins or ends there (a film opening on black).
     */
    private fun grow(line: Line, all: List<Line>): Line {
        // 1 agrees, 0 says nothing (the episode is blank there), -1 disagrees or is unknown.
        fun verdict(episodeMs: Double): Int {
            if (all.any { it !== line && episodeMs >= it.episodeStart && episodeMs < it.episodeEnd }) return -1
            val ei = (episodeMs / FRAME_MS).roundToInt()
            val ri = ((line.intercept + line.rate * episodeMs) / FRAME_MS).roundToInt()
            if (!episode.has(ei) || !reaction.has(ri)) return -1
            if (episode.detail(ei) < BLANK_DETAIL) return if (reaction.detail(ri) < BLANK_DETAIL) 1 else 0
            val h = if (line.mirrored) reaction.mirror(ri) else reaction.hash(ri)
            return if (ReactionFingerprinter.distance(h, episode.hash(ei)) <= GROW_DISTANCE) 1 else -1
        }
        var start = line.episodeStart
        var misses = 0
        var t = start - FRAME_MS
        var onlyBlank = true
        while (t >= 0 && misses < 3) {
            when (verdict(t)) {
                1 -> { start = t; misses = 0; onlyBlank = true }
                0 -> {}
                else -> { misses++; onlyBlank = false }
            }
            t -= FRAME_MS
        }
        if (t < 0 && onlyBlank) start = 0.0
        var end = line.episodeEnd
        misses = 0
        t = end
        onlyBlank = true
        val episodeEnd = episode.size * FRAME_MS.toDouble()
        while (misses < 3 && t < episodeEnd) {
            when (verdict(t)) {
                1 -> { end = t + FRAME_MS; misses = 0; onlyBlank = true }
                0 -> {}
                else -> { misses++; onlyBlank = false }
            }
            t += FRAME_MS
        }
        if (t >= episodeEnd && onlyBlank) end = episodeEnd
        return Line(line.intercept, line.rate, line.mirrored, start, end, line.support)
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
        private const val GROW_DISTANCE = 20
        private const val BLANK_DETAIL = 12f
        private const val MIN_SEGMENT_MS = 2_000.0

        fun toSyncMap(segments: List<MatchedSegment>, nudgeMs: Long = 0): SyncMap =
            SyncMap(segments.map { it.toSyncSegment().let { s -> s.copy(reactionStartMs = s.reactionStartMs + nudgeMs) } })
    }
}
