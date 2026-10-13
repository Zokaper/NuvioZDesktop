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
    fun steadied(wideLayouts: List<DetectedLayout> = emptyList()): ReactionLayoutTimeline = steadyMovie().steadyReactors(wideLayouts)

    /**
     * Every reactor of an arrangement, in every epoch of it. A 45 s window only sees the people who
     * moved in it: on TEM's compilation (~90 cameras) windows found anywhere from 3 to 34 boxes, some
     * of them blocks of a dozen cameras whose borders went unseen, and the stage jumped between
     * those sets every few seconds (Debug 93: "half of the reactors are gone", tiny faces in one tile).
     *
     * Epochs are grouped into arrangements: two layouts belong together when none of their boxes
     * cross (one inside another is the same cameras seen more or less finely; two boxes that
     * partly overlap are different arrangements, e.g. LoneReact rearranging mid-video). Per
     * arrangement, every box seen in two windows or for a minute is kept, a box that holds two or
     * more kept boxes covering half of it is a block and gives way to them, and every epoch of the
     * arrangement shows the whole set (plus any box only its own window saw, there only).
     */
    private fun steadyReactors(wideLayouts: List<DetectedLayout>): ReactionLayoutTimeline {
        if (epochs.isEmpty()) return this
        // Distinct layouts (the builder's candidates), with how long each was on. Wide layouts (from
        // 3 min windows) only lend their boxes to the sets: they cover cameras the 45 s windows never
        // saw move, as blocks.
        val layouts = LinkedHashMap<DetectedLayout, Long>()
        for (e in epochs) layouts[e.layout] = (layouts[e.layout] ?: 0L) + (e.endMs - e.startMs)
        val epochLayouts = layouts.keys.toSet()
        for (wide in wideLayouts) if (wide !in layouts) layouts[wide] = WIDE_WINDOW_MS
        val arrangements = ArrayList<MutableList<DetectedLayout>>()
        for (layout in layouts.keys.sortedByDescending { layouts.getValue(it) }) {
            arrangements.firstOrNull { group -> group.all { compatible(it, layout) } }?.add(layout) ?: arrangements.add(mutableListOf(layout))
        }
        val steady = HashMap<DetectedLayout, DetectedLayout>()
        for (group in arrangements) {
            if (group.none { it in epochLayouts }) continue
            val reactors = reactorSet(group, layouts)
            for (layout in group.filter { it in epochLayouts }) {
                val movie = layout.movie
                // A box only this window saw is not spread over the video, but it stays here.
                val own = layout.reactors.filter { t -> reactors.none { overlapShare(it.rect, t.rect) > 0.25 } }
                val kept = (reactors + own).filter { r -> movie == null || insideShare(r.rect, movie.rect) <= 0.5 }
                steady[layout] = DetectedLayout(layout.tiles.filter { it.kind != TileKind.REACTOR } + kept)
            }
        }
        return ReactionLayoutTimeline(epochs.map { it.copy(layout = steady.getValue(it.layout)) })
    }

    private class Box(var rect: LayoutRect, val members: MutableList<LayoutRect>, var windows: Int, var ms: Long, val cell: Boolean = false)

    private fun reactorSet(group: List<DetectedLayout>, onMs: Map<DetectedLayout, Long>): List<DetectedTile> {
        val boxes = ArrayList<Box>()
        for (layout in group) {
            val seen = HashSet<Box>()
            for (tile in layout.tiles.filter { it.kind == TileKind.REACTOR }) {
                val box = boxes.firstOrNull { ReactionLayoutTimelineBuilder.iou(it.rect, tile.rect) >= 0.5 }
                    ?: Box(tile.rect, ArrayList(), 0, 0L).also { boxes += it }
                box.members += tile.rect
                if (seen.add(box)) {
                    box.windows++
                    box.ms += onMs.getValue(layout)
                }
                box.rect = median(box.members)
            }
        }
        val solid = if (group.size == 1) boxes else boxes.filter { it.windows >= 2 || it.ms >= MIN_REACTOR_MS }
        val singles = solid.filter { s -> solid.none { it !== s && insideShare(it.rect, s.rect) >= 0.8 && area(it.rect) < 0.6 * area(s.rect) } }
        val split = ArrayList<Box>()
        for (box in solid) {
            val inside = solid.filter { it !== box && insideShare(it.rect, box.rect) >= 0.8 && area(it.rect) < 0.6 * area(box.rect) }
            if (inside.isEmpty()) {
                // A wall of small cameras seen as one box, with none of them found alone inside it:
                // split it on the cameras' own size when it is clearly several of them.
                val cells = if (singles.size >= GRID_MIN_SINGLES) gridCells(box.rect, singles, minCells = 3) else null
                if (cells == null) split += box else cells.forEach { split += Box(it, mutableListOf(it), box.windows, box.ms, cell = true) }
                continue
            }
            // A block of cameras found as one box: split on the size of the cameras found inside it, or
            // give way to them when it does not divide evenly and they cover half of it.
            val cells = gridCells(box.rect, inside, minCells = 2)
            when {
                cells != null -> cells.forEach { split += Box(it, mutableListOf(it), box.windows, box.ms, cell = true) }
                inside.sumOf { area(it.rect) } < 0.5 * area(box.rect) -> split += box
            }
        }
        // Whatever still overlaps: a box seen as it is beats a cell cut from a block, then the steadier
        // box wins, then the smaller one.
        val chosen = ArrayList<Box>()
        for (box in split.sortedWith(compareBy<Box> { it.cell }.thenByDescending { it.windows }.thenBy { area(it.rect) })) {
            if (chosen.none { overlapShare(it.rect, box.rect) > 0.25 }) chosen += box
        }
        return chosen.map { DetectedTile(it.rect, TileKind.REACTOR) }
    }

    /**
     * [block] as an even grid of cameras the size of [like]'s median, if it is one: both sides within
     * a quarter of a camera of a whole number of them, and at least [minCells] cells.
     */
    private fun gridCells(block: LayoutRect, like: List<Box>, minCells: Int): List<LayoutRect>? {
        if (like.isEmpty()) return null
        val cw = like.map { it.rect.w }.sorted()[like.size / 2]
        val ch = like.map { it.rect.h }.sorted()[like.size / 2]
        if (cw <= 0 || ch <= 0) return null
        val nx = kotlin.math.round(block.w / cw).toInt()
        val ny = kotlin.math.round(block.h / ch).toInt()
        if (nx < 1 || ny < 1 || nx * ny < minCells) return null
        if (abs(block.w / cw - nx) > 0.25 || abs(block.h / ch - ny) > 0.25) return null
        val w = block.w / nx
        val h = block.h / ny
        return (0 until ny).flatMap { row -> (0 until nx).map { col -> LayoutRect(block.x + col * w, block.y + row * h, w, h) } }
    }

    private fun area(r: LayoutRect) = r.w * r.h

    /** No two reactor boxes of [a] and [b] cross: each pair is apart, the same, or one inside the other. */
    private fun compatible(a: DetectedLayout, b: DetectedLayout): Boolean {
        val ra = a.tiles.filter { it.kind == TileKind.REACTOR }
        val rb = b.tiles.filter { it.kind == TileKind.REACTOR }
        var crossings = 0
        for (x in ra) for (y in rb) {
            val share = overlapShare(x.rect, y.rect)
            if (share > 0.3 && share < 0.8 && ReactionLayoutTimelineBuilder.iou(x.rect, y.rect) < 0.5) crossings++
        }
        return crossings <= max(1, min(ra.size, rb.size) / 10)
    }

    /** How much of [inner] lies inside [outer]. */
    private fun insideShare(inner: LayoutRect, outer: LayoutRect): Double {
        val ix = max(0.0, min(inner.right, outer.right) - max(inner.x, outer.x))
        val iy = max(0.0, min(inner.bottom, outer.bottom) - max(inner.y, outer.y))
        return if (inner.w * inner.h <= 0) 0.0 else ix * iy / (inner.w * inner.h)
    }

    /** Their overlap as a share of the smaller one. */
    private fun overlapShare(a: LayoutRect, b: LayoutRect): Double =
        if (a.w * a.h <= b.w * b.h) insideShare(a, b) else insideShare(b, a)

    private fun median(rects: List<LayoutRect>): LayoutRect {
        fun mid(values: List<Double>) = values.sorted().let { (it[it.size / 2] + it[(it.size - 1) / 2]) / 2 }
        return LayoutRect(mid(rects.map { it.x }), mid(rects.map { it.y }), mid(rects.map { it.w }), mid(rects.map { it.h }))
    }

    private fun steadyMovie(): ReactionLayoutTimeline {
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
        /** A reactor box seen in one window only is kept when that window was on this long. */
        const val MIN_REACTOR_MS = 60_000L
        /** How long a wide (3 min) layout window counts for, among the 45 s ones. */
        const val WIDE_WINDOW_MS = 180_000L
        /** Cameras found alone before a box with none inside it may be split on their size. */
        const val GRID_MIN_SINGLES = 12
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

    /** The distinct layouts the windows proposed so far. */
    val layouts: List<DetectedLayout> get() = candidates.toList()

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
