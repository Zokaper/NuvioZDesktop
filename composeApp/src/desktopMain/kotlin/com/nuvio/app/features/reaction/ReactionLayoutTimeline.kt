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
