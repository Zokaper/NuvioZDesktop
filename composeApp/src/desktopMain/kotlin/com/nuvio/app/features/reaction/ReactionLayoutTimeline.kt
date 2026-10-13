package com.nuvio.app.features.reaction

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One stretch of the reaction video with one arrangement of boxes. */
internal data class LayoutEpoch(val startMs: Long, val endMs: Long, val layout: DetectedLayout)

internal class ReactionLayoutTimeline(val epochs: List<LayoutEpoch>) {
    /** The layout at reaction time [ms]: the epoch containing it, else the nearest one. */
    fun at(ms: Long): DetectedLayout? {
        if (epochs.isEmpty()) return null
        epochs.firstOrNull { ms in it.startMs until it.endMs }?.let { return it.layout }
        return epochs.minBy { min(abs(ms - it.startMs), abs(ms - it.endMs)) }.layout
    }

    fun epochIndexAt(ms: Long): Int = epochs.indexOfFirst { ms in it.startMs until it.endMs }

    val coveredUntilMs: Long get() = epochs.maxOfOrNull { it.endMs } ?: 0

    fun covers(ms: Long): Boolean = epochs.any { ms in it.startMs until it.endMs }

    /**
     * The compilation's own movie box, held steady. Each 45 s window finds the movie by itself, and
     * on TEM's Interstellar compilation (first real test, 2026-10-13) a quarter of the windows took
     * a 0.07 x 0.11 reactor at the left edge for it, while the rest flipped between the frame's
     * inside (0.50 x 0.53) and the scope picture within it (0.50 x 0.40) every few seconds. The
     * smaller box crops the IMAX scenes, and a reactor's box matches nothing. Bars belong to the
     * content and are found frame by frame (SlidingLetterbox), so the box should be the frame's.
     *
     * The main box is the most-seen family of overlapping movie boxes, and within it the largest box
     * seen for a fair share of that time. Epochs whose movie is in that family, too small to be the
     * movie, or missing (with no reactor where the movie usually is) get the main box; the small
     * "movie" goes back to being a reactor and pieces of the movie found as tiles are dropped. A
     * genuinely different arrangement (a large box elsewhere) is left alone.
     */
    fun steadied(): ReactionLayoutTimeline {
        val movies = epochs.mapNotNull { e -> e.layout.movie?.let { it.rect to (e.endMs - e.startMs) } }
            .filter { (rect, _) -> rect.w * rect.h >= MIN_MOVIE_AREA }
        if (movies.isEmpty()) return this
        val families = ArrayList<MutableList<Pair<LayoutRect, Long>>>()
        for (m in movies.sortedByDescending { it.second }) {
            families.firstOrNull { f -> ReactionLayoutTimelineBuilder.iou(f.first().first, m.first) >= 0.5 }?.add(m) ?: families.add(mutableListOf(m))
        }
        val main = families.maxBy { f -> f.sumOf { it.second } }
        val familyMs = main.sumOf { it.second }
        if (familyMs * 3 < epochs.sumOf { it.endMs - it.startMs }) return this
        // Windows see the same box a pixel or two apart: those are one size, shown by its most-seen look.
        val sizes = ArrayList<MutableList<Pair<LayoutRect, Long>>>()
        for (m in main) {
            sizes.firstOrNull { s -> ReactionLayoutTimelineBuilder.iou(s.first().first, m.first) >= 0.85 }?.add(m) ?: sizes.add(mutableListOf(m))
        }
        val looks = sizes.map { s -> s.groupBy({ it.first }, { it.second }).maxBy { it.value.sum() }.key to s.sumOf { it.second } }
        val box = looks.filter { it.second * 100 >= familyMs * MAIN_SHARE_PERCENT }.maxByOrNull { it.first.w * it.first.h }?.first
            ?: looks.maxBy { it.second }.first
        return ReactionLayoutTimeline(epochs.map { e -> e.copy(layout = steady(e.layout, box)) })
    }

    private fun steady(layout: DetectedLayout, box: LayoutRect): DetectedLayout {
        val movie = layout.movie
        fun insideBox(tile: DetectedTile): Double {
            val r = tile.rect
            val ix = max(0.0, min(r.right, box.right) - max(r.x, box.x))
            val iy = max(0.0, min(r.bottom, box.bottom) - max(r.y, box.y))
            return if (r.w * r.h <= 0) 0.0 else ix * iy / (r.w * r.h)
        }
        val tooSmall = movie != null && movie.rect.w * movie.rect.h < 0.25 * box.w * box.h
        val sameFamily = movie != null && ReactionLayoutTimelineBuilder.iou(movie.rect, box) >= 0.3
        when {
            movie == null -> if (layout.tiles.any { it.kind == TileKind.REACTOR && insideBox(it) > 0.5 }) return layout
            !tooSmall && !sameFamily -> return layout
        }
        if (movie != null && sameFamily && movie.rect == box) return layout
        val kept = layout.tiles.mapNotNull { t ->
            when {
                t === movie && tooSmall -> t.copy(kind = TileKind.REACTOR)
                t === movie -> null
                insideBox(t) > 0.5 -> null
                else -> t
            }
        }
        return DetectedLayout(kept + DetectedTile(box, TileKind.MOVIE))
    }

    /** These epochs, with [newer] laid over them wherever it has something to say. */
    fun overlaidWith(newer: ReactionLayoutTimeline): ReactionLayoutTimeline {
        if (newer.epochs.isEmpty()) return this
        val from = newer.epochs.first().startMs
        val until = newer.coveredUntilMs
        val kept = epochs.mapNotNull { e ->
            when {
                e.endMs <= from || e.startMs >= until -> e
                e.startMs < from -> e.copy(endMs = from)
                e.endMs > until -> e.copy(startMs = until)
                else -> null
            }
        }
        return ReactionLayoutTimeline((kept + newer.epochs).sortedBy { it.startMs })
    }

    private companion object {
        /** Below this share of the frame a "movie" is a reactor's box (TEM's mistaken one: 0.008). */
        const val MIN_MOVIE_AREA = 0.04
        /** The main box must have been seen for this much of its family's time. */
        const val MAIN_SHARE_PERCENT = 15
    }
}

/**
 * Builds a [ReactionLayoutTimeline] while the analysis frames stream in (Stage 3, layout epochs).
 *
 * Compilations rearrange their boxes mid-video (LoneReact does). Every [windowFrames] frames a full
 * [ReactionLayoutDetector] run proposes a layout; distinct proposals become candidates. Separately,
 * a small time-averaged picture is kept for every [chunkFrames] frames, and each chunk is assigned
 * the candidate whose box edges actually show up in it - so a layout change is placed to within a
 * chunk, and one badly detected window cannot paint the wrong boxes over its whole span.
 */
internal class ReactionLayoutTimelineBuilder(
    private val width: Int = ReactionLayoutDetector.ANALYSIS_WIDTH,
    private val height: Int = ReactionLayoutDetector.ANALYSIS_HEIGHT,
    private val fps: Int = ReactionLayoutDetector.ANALYSIS_FPS,
    private val startMs: Long = 0,
    private val windowFrames: Int = 180,
    private val chunkFrames: Int = 20,
) {
    private val cw = width / 2
    private val ch = height / 2
    private var detector = ReactionLayoutDetector(width, height)
    private var framesInWindow = 0
    private var framesTotal = 0
    private val chunkSum = IntArray(cw * ch)
    private var framesInChunk = 0
    private val chunks = ArrayList<ByteArray>()
    private val candidates = ArrayList<DetectedLayout>()

    /** Returns true when a window just finished (the timeline may have changed). */
    fun add(frame: ByteArray): Boolean {
        detector.add(frame)
        framesInWindow++
        framesTotal++
        for (y in 0 until ch) for (x in 0 until cw) {
            val i = (y * 2) * width + x * 2
            chunkSum[y * cw + x] += ((frame[i].toInt() and 0xFF) + (frame[i + 1].toInt() and 0xFF) +
                (frame[i + width].toInt() and 0xFF) + (frame[i + width + 1].toInt() and 0xFF))
        }
        framesInChunk++
        if (framesInChunk == chunkFrames) closeChunk()
        if (framesInWindow < windowFrames) return false
        closeWindow()
        return true
    }

    /** Flushes a partial final window and chunk (the end of the video). */
    fun finish() {
        if (framesInChunk >= chunkFrames / 2) closeChunk() else framesInChunk = 0
        if (framesInWindow >= 40) closeWindow()
    }

    private fun closeChunk() {
        val divisor = framesInChunk * 4
        chunks += ByteArray(cw * ch) { (chunkSum[it] / divisor).toByte() }
        chunkSum.fill(0)
        framesInChunk = 0
    }

    private fun closeWindow() {
        val layout = runCatching { detector.detect() }.getOrNull()
        detector = ReactionLayoutDetector(width, height)
        framesInWindow = 0
        if (layout == null || (layout.reactors.isEmpty() && layout.movie == null)) return
        val same = candidates.indexOfFirst { similarity(it, layout) >= 0.7 }
        when {
            same < 0 -> candidates += layout
            // Two looks at the same arrangement: keep the one that found more boxes.
            layout.reactors.size > candidates[same].reactors.size -> candidates[same] = layout
        }
    }

    fun timeline(): ReactionLayoutTimeline {
        if (candidates.isEmpty() || chunks.isEmpty()) return ReactionLayoutTimeline(emptyList())
        val chunkMs = chunkFrames * 1000L / fps
        // Each chunk: the candidate whose edges are most present in its averaged picture.
        val raw = chunks.map { chunk -> candidates.indices.maxBy { score(candidates[it], chunk) } }
        // Smooth single-chunk flickers.
        val assigned = raw.indices.map { i ->
            val before = raw.getOrNull(i - 1)
            val after = raw.getOrNull(i + 1)
            if (before != null && before == after && raw[i] != before) before else raw[i]
        }
        val epochs = ArrayList<LayoutEpoch>()
        var start = 0
        for (i in 1..assigned.size) {
            if (i == assigned.size || assigned[i] != assigned[start]) {
                epochs += LayoutEpoch(startMs + start * chunkMs, startMs + i * chunkMs, candidates[assigned[start]])
                start = i
            }
        }
        return ReactionLayoutTimeline(epochs)
    }

    /** Fraction of the layout's box sides that show a clear step in this chunk's averaged picture. */
    private fun score(layout: DetectedLayout, chunk: ByteArray): Double {
        var hits = 0
        var total = 0
        fun px(x: Int, y: Int) = chunk[y.coerceIn(0, ch - 1) * cw + x.coerceIn(0, cw - 1)].toInt() and 0xFF
        for (tile in layout.tiles) {
            if (tile.kind != TileKind.REACTOR && tile.kind != TileKind.MOVIE) continue
            val x0 = (tile.rect.x * cw).toInt()
            val y0 = (tile.rect.y * ch).toInt()
            val x1 = (tile.rect.right * cw).toInt()
            val y1 = (tile.rect.bottom * ch).toInt()
            // Sides on the picture's own edge prove nothing; sample the inner ones.
            for (y in y0 + 2 until y1 - 2 step 2) {
                if (x0 > 1) { total++; if (abs(px(x0 - 1, y) - px(x0 + 1, y)) > 10) hits++ }
                if (x1 < cw - 1) { total++; if (abs(px(x1 - 2, y) - px(x1, y)) > 10) hits++ }
            }
            for (x in x0 + 2 until x1 - 2 step 2) {
                if (y0 > 1) { total++; if (abs(px(x, y0 - 1) - px(x, y0 + 1)) > 10) hits++ }
                if (y1 < ch - 1) { total++; if (abs(px(x, y1 - 2) - px(x, y1)) > 10) hits++ }
            }
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    val processedMs: Long get() = startMs + framesTotal * 1000L / fps

    companion object {
        /** Share of boxes (by overlap) two layouts have in common. */
        fun similarity(a: DetectedLayout, b: DetectedLayout): Double {
            val ta = a.tiles.filter { it.kind == TileKind.REACTOR || it.kind == TileKind.MOVIE }
            val tb = b.tiles.filter { it.kind == TileKind.REACTOR || it.kind == TileKind.MOVIE }
            if (ta.isEmpty() || tb.isEmpty()) return if (ta.isEmpty() && tb.isEmpty()) 1.0 else 0.0
            val matched = ta.count { x -> tb.any { y -> iou(x.rect, y.rect) > 0.6 } }
            return matched.toDouble() / max(ta.size, tb.size)
        }

        fun iou(a: LayoutRect, b: LayoutRect): Double {
            val ix = max(0.0, min(a.right, b.right) - max(a.x, b.x))
            val iy = max(0.0, min(a.bottom, b.bottom) - max(a.y, b.y))
            val inter = ix * iy
            val union = a.w * a.h + b.w * b.h - inter
            return if (union <= 0) 0.0 else inter / union
        }
    }
}
