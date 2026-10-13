package com.nuvio.app.features.reaction

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What a detected rectangle of the reaction frame is. */
internal enum class TileKind { MOVIE, REACTOR, STATIC, SLIVER }

internal data class DetectedTile(
    /** 0..1 in the reaction frame. */
    val rect: LayoutRect,
    val kind: TileKind,
)

internal data class DetectedLayout(val tiles: List<DetectedTile>) {
    val movie: DetectedTile? get() = tiles.firstOrNull { it.kind == TileKind.MOVIE }

    /** Reactor tiles in reading order (rows top to bottom, left to right within a row). */
    val reactors: List<DetectedTile>
        get() = tiles.filter { it.kind == TileKind.REACTOR }.sortedWith(
            compareBy<DetectedTile> { ((it.rect.y + it.rect.h / 2) * 20).roundToInt() }.thenBy { it.rect.x },
        )
}

/**
 * Finds the reactor boxes of a reaction compilation from its motion (Stage 3). Pure: feed it
 * greyscale frames sampled from a stretch of the video, read the layout back.
 *
 * Compilation layouts are grids of camera boxes, separated by thin borders (TEM, VoltReact) or by
 * nothing at all (LoneReact). A single frame cannot tell a border from a shelf, so evidence is
 * gathered over minutes: a tile boundary is a long straight line that is either a thin, still,
 * uniform border, or a persistent step in the time-averaged picture across which the motion is
 * independent (two cameras) or moving on one side only (a camera beside a still background).
 * Tiles are then the rectangles whose four sides are covered by such lines and whose inside no
 * equally strong line crosses; strict first, relaxed passes only for area that is moving.
 * The movie is the tile that changes all at once on scene cuts.
 *
 * Prototyped on TEM, VoltReact and LoneReact compilations (Stage 3 notes in STATUS.md): the movie
 * found in all three, roughly three quarters of the reactor boxes, very few false boxes.
 */
internal class ReactionLayoutDetector(private val width: Int, private val height: Int) {
    private val n = width * height
    private var previous: FloatArray? = null
    private var frames = 0

    private val meanSum = DoubleArray(n)
    private val activitySum = FloatArray(n)
    private val busyCount = IntArray(n)

    // Running sums for motion on either side of every vertical (v) and horizontal (h) line.
    private val vl = FloatArray(n); private val vr = FloatArray(n)
    private val vll = FloatArray(n); private val vrr = FloatArray(n); private val vlr = FloatArray(n)
    private val vlBusy = IntArray(n); private val vrBusy = IntArray(n)
    private val ht = FloatArray(n); private val hb = FloatArray(n)
    private val htt = FloatArray(n); private val hbb = FloatArray(n); private val htb = FloatArray(n)
    private val htBusy = IntArray(n); private val hbBusy = IntArray(n)

    /** Per frame, which coarse cells changed strongly - for telling the movie by its scene cuts. */
    private val cellW = 4
    private val cellsX = width / cellW
    private val cellsY = height / cellW
    private val strongChange = ArrayList<BooleanArray>()

    /** Diagnostics for tests: candidate lines and the side coverage of [debugProbes] rectangles. */
    var debug: String = ""
        private set
    var debugProbes: List<IntArray> = emptyList()

    fun add(frame: ByteArray) {
        require(frame.size == n)
        val current = FloatArray(n) { (frame[it].toInt() and 0xFF).toFloat() }
        for (i in 0 until n) meanSum[i] += current[i].toDouble()
        val prev = previous
        previous = current
        if (prev == null) return
        frames++
        val raw = FloatArray(n) { abs(current[it] - prev[it]) }
        val activity = boxBlur3(raw)
        for (i in 0 until n) {
            activitySum[i] += activity[i]
            if (activity[i] > 3f) busyCount[i]++
        }
        accumulateSides(activity)
        val cells = BooleanArray(cellsX * cellsY)
        for (cy in 0 until cellsY) for (cx in 0 until cellsX) {
            var strong = 0
            for (dy in 0 until cellW) for (dx in 0 until cellW) {
                if (activity[(cy * cellW + dy) * width + cx * cellW + dx] > 12f) strong++
            }
            cells[cy * cellsX + cx] = strong * 2 > cellW * cellW
        }
        strongChange += cells
    }

    private fun boxBlur3(a: FloatArray): FloatArray {
        val out = FloatArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var s = 0f
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) s += a[yy * width + (x + dx).coerceIn(0, width - 1)]
                }
                out[y * width + x] = s / 9f
            }
        }
        return out
    }

    private fun accumulateSides(a: FloatArray) {
        // Vertical lines: patch means over columns x-5..x-2 (left) and x+2..x+5 (right), per row.
        val rowPrefix = FloatArray(width + 1)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) rowPrefix[x + 1] = rowPrefix[x] + a[base + x]
            for (x in 5 until width - 6) {
                val l = (rowPrefix[x - 1] - rowPrefix[x - 5]) / 4f
                val r = (rowPrefix[x + 6] - rowPrefix[x + 2]) / 4f
                val i = base + x
                vl[i] += l; vr[i] += r; vll[i] += l * l; vrr[i] += r * r; vlr[i] += l * r
                if (l > 3f) vlBusy[i]++
                if (r > 3f) vrBusy[i]++
            }
        }
        // Horizontal lines: rows y-5..y-2 above, y+2..y+5 below, per column.
        val colPrefix = FloatArray(height + 1)
        for (x in 0 until width) {
            for (y in 0 until height) colPrefix[y + 1] = colPrefix[y] + a[y * width + x]
            for (y in 5 until height - 6) {
                val t = (colPrefix[y - 1] - colPrefix[y - 5]) / 4f
                val b = (colPrefix[y + 6] - colPrefix[y + 2]) / 4f
                val i = y * width + x
                ht[i] += t; hb[i] += b; htt[i] += t * t; hbb[i] += b * b; htb[i] += t * b
                if (t > 3f) htBusy[i]++
                if (b > 3f) hbBusy[i]++
            }
        }
    }

    fun detect(): DetectedLayout {
        require(frames >= 20) { "too few frames ($frames) to detect a layout" }
        val count = (frames + 1).toDouble()
        val mean = FloatArray(n) { (meanSum[it] / count).toFloat() }
        val busy = FloatArray(n) { busyCount[it].toFloat() / frames }
        val meanActivity = FloatArray(n) { activitySum[it] / frames }
        val still = BooleanArray(n) { meanActivity[it] < 1.5f && busy[it] < 0.1f }
        val uniformV = directionalUniform(mean, vertical = true)
        val uniformH = directionalUniform(mean, vertical = false)
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        for (y in 0 until height) for (x in 1 until width) dx[y * width + x] = abs(mean[y * width + x] - mean[y * width + x - 1])
        for (y in 1 until height) for (x in 0 until width) dy[y * width + x] = abs(mean[y * width + x] - mean[(y - 1) * width + x])

        val evV = BooleanArray(n)
        for (y in 0 until height) for (x in 5 until width - 6) {
            val i = y * width + x
            val gutter = still[i] && uniformV[i] &&
                !(still[i - 3] && still[i + 3] && uniformV[i - 3] && uniformV[i + 3])
            val line = max(dx[i], dx[i + 1])
            val beside = max(dx[i - 3], dx[min(i + 4, y * width + width - 1)])
            val step = line > 6f && line > 1.8f * beside + 2f
            evV[i] = gutter || (step && sidesDiffer(i, vl, vr, vll, vrr, vlr, vlBusy, vrBusy))
        }
        val evH = BooleanArray(n)
        for (y in 5 until height - 6) for (x in 0 until width) {
            val i = y * width + x
            val gutter = still[i] && uniformH[i] &&
                !(still[i - 3 * width] && still[i + 3 * width] && uniformH[i - 3 * width] && uniformH[i + 3 * width])
            val line = max(dy[i], dy[i + width])
            val beside = max(dy[i - 3 * width], dy[min(i + 4 * width, (height - 1) * width + x)])
            val step = line > 6f && line > 1.8f * beside + 2f
            evH[i] = gutter || (step && sidesDiffer(i, ht, hb, htt, hbb, htb, htBusy, hbBusy))
        }
        // The picture's own edges are boundaries.
        for (y in 0 until height) { evV[y * width] = true; evV[y * width + width - 1] = true }
        for (x in 0 until width) { evH[x] = true; evH[(height - 1) * width + x] = true }

        val coverage = Coverage(evV, evH)
        val xs = thin(candidates(evV, vertical = true), IntArray(width) { x -> (0 until height).count { evV[it * width + x] } })
        val ys = thin(candidates(evH, vertical = false), IntArray(height) { y -> (0 until width).count { evH[y * width + it] } })
        val rects = hypotheses(xs, ys, coverage, busy)
        val chosen = select(rects)
        debug = buildString {
            append("xs=").append(xs).append(" ys=").append(ys).append(" hypotheses=").append(rects.size).append('\n')
            fun side(x0: Int, y0: Int, x1: Int, y1: Int) =
                "[$x0,$y0,$x1,$y1] L=${coverage.v(x0, y0, y1)} R=${coverage.v(x1 - 1, y0, y1)} T=${coverage.h(y0, x0, x1)} B=${coverage.h(y1 - 1, x0, x1)} " +
                    "inner=" + (xs.filter { it > x0 + 3 && it < x1 - 3 }.map { "x$it:${coverage.v(it, y0, y1)}" } +
                        ys.filter { it > y0 + 3 && it < y1 - 3 }.map { "y$it:${coverage.h(it, x0, x1)}" })
            debugProbes.forEach { (x0, y0, x1, y1) -> append(side(x0, y0, x1, y1)).append('\n') }
        }
        return DetectedLayout(chosen.map { classify(it, busy, dx, dy) }.let(::markMovie))
    }

    private fun sidesDiffer(
        i: Int, l: FloatArray, r: FloatArray, ll: FloatArray, rr: FloatArray, lr: FloatArray, lBusy: IntArray, rBusy: IntArray,
    ): Boolean {
        val f = frames.toFloat()
        val bl = lBusy[i] / f
        val br = rBusy[i] / f
        if (min(bl, br) < 0.05f && max(bl, br) > 0.2f) return true          // moving beside still
        if (bl <= 0.15f || br <= 0.15f) return false
        val ml = l[i] / f
        val mr = r[i] / f
        val sl = sqrt(max(ll[i] / f - ml * ml, 0f)) + 1e-3f
        val sr = sqrt(max(rr[i] / f - mr * mr, 0f)) + 1e-3f
        val corr = (lr[i] / f - ml * mr) / (sl * sr)
        return corr < 0.25f                                                  // two independent cameras
    }

    private fun directionalUniform(m: FloatArray, vertical: Boolean): BooleanArray {
        val out = BooleanArray(n)
        for (y in 0 until height) for (x in 0 until width) {
            var s = 0f
            var s2 = 0f
            for (k in -4..4) {
                val v = if (vertical) m[(y + k).coerceIn(0, height - 1) * width + x] else m[y * width + (x + k).coerceIn(0, width - 1)]
                s += v
                s2 += v * v
            }
            val meanV = s / 9f
            out[y * width + x] = sqrt(max(s2 / 9f - meanV * meanV, 0f)) < 3f
        }
        return out
    }

    private inner class Coverage(evV: BooleanArray, evH: BooleanArray) {
        // Tolerant of +-2 px misalignment; prefix sums give any segment's coverage in O(1).
        private val pv = Array(width) { IntArray(height + 1) }
        private val ph = Array(height) { IntArray(width + 1) }

        init {
            for (x in 0 until width) for (y in 0 until height) {
                var hit = false
                for (d in -2..2) {
                    val xx = x + d
                    if (xx in 0 until width && evV[y * width + xx]) { hit = true; break }
                }
                pv[x][y + 1] = pv[x][y] + if (hit) 1 else 0
            }
            for (y in 0 until height) for (x in 0 until width) {
                var hit = false
                for (d in -2..2) {
                    val yy = y + d
                    if (yy in 0 until height && evH[yy * width + x]) { hit = true; break }
                }
                ph[y][x + 1] = ph[y][x] + if (hit) 1 else 0
            }
        }

        fun v(x: Int, y0: Int, y1: Int): Float {
            val xx = x.coerceIn(0, width - 1)
            return (pv[xx][y1] - pv[xx][y0]).toFloat() / max(y1 - y0, 1)
        }

        fun h(y: Int, x0: Int, x1: Int): Float {
            val yy = y.coerceIn(0, height - 1)
            return (ph[yy][x1] - ph[yy][x0]).toFloat() / max(x1 - x0, 1)
        }
    }

    /** Positions with a long run of evidence along them, plus the picture edges. */
    private fun candidates(ev: BooleanArray, vertical: Boolean): List<Int> {
        val positions = sortedSetOf(0, if (vertical) width else height)
        val count = if (vertical) width else height
        val length = if (vertical) height else width
        for (p in 0 until count) {
            var runStart = -1
            var last = -10
            var found = false
            for (q in 0 until length) {
                val hit = if (vertical) ev[q * width + p] else ev[p * width + q]
                if (hit) {
                    if (runStart < 0 || q - last > 4) runStart = q
                    last = q
                    if (last - runStart + 1 >= MIN_RUN) { found = true; break }
                }
            }
            if (found) positions += p
        }
        return positions.toList()
    }

    /** A 3 px border gives 3 candidates: keep each cluster's ends and its strongest member. */
    private fun thin(positions: List<Int>, strength: IntArray): List<Int> {
        val groups = mutableListOf<MutableList<Int>>()
        for (p in positions) {
            if (groups.isNotEmpty() && p - groups.last().last() <= 2) groups.last() += p else groups += mutableListOf(p)
        }
        val out = sortedSetOf<Int>()
        for (g in groups) {
            out += g.first()
            out += g.last()
            out += g.maxBy { strength[min(it, strength.size - 1)] }
        }
        return out.toList()
    }

    private class Hypothesis(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val weakest: Float, val interior: Float, val meanSide: Float, val busy: Float) {
        val area get() = (x1 - x0) * (y1 - y0)
    }

    private fun hypotheses(xs: List<Int>, ys: List<Int>, cov: Coverage, busy: FloatArray): List<Hypothesis> {
        val busyPrefix = Array(height + 1) { FloatArray(width + 1) }
        for (y in 0 until height) for (x in 0 until width) {
            busyPrefix[y + 1][x + 1] = busy[y * width + x] + busyPrefix[y][x + 1] + busyPrefix[y + 1][x] - busyPrefix[y][x]
        }
        val floor = PASSES.min()
        val out = ArrayList<Hypothesis>()
        for (i in xs.indices) for (j in i + 1 until xs.size) {
            val x0 = xs[i]
            val x1 = xs[j]
            if (x1 - x0 < MIN_SIZE) continue
            // Lines closer than this to a side are that side, seen through the coverage tolerance.
            val innerX = xs.filter { it > x0 + INNER_MARGIN && it < x1 - INNER_MARGIN }
            for (k in ys.indices) for (l in k + 1 until ys.size) {
                val y0 = ys[k]
                val y1 = ys[l]
                if (y1 - y0 < MIN_SIZE) continue
                val left = cov.v(x0, y0, y1)
                val right = cov.v(x1 - 1, y0, y1)
                val top = cov.h(y0, x0, x1)
                val bottom = cov.h(y1 - 1, x0, x1)
                val weakest = minOf(left, right, top, bottom)
                if (weakest < floor) continue
                var interior = 0f
                for (x in innerX) interior = max(interior, cov.v(x, y0, y1))
                for (y in ys) if (y > y0 + INNER_MARGIN && y < y1 - INNER_MARGIN) interior = max(interior, cov.h(y, x0, x1))
                if (interior >= weakest) continue
                val b = (busyPrefix[y1][x1] - busyPrefix[y0][x1] - busyPrefix[y1][x0] + busyPrefix[y0][x0]) / ((x1 - x0) * (y1 - y0))
                out += Hypothesis(x0, y0, x1, y1, weakest, interior, (left + right + top + bottom) / 4, b)
            }
        }
        return out
    }

    private fun select(rects: List<Hypothesis>): List<Hypothesis> {
        val chosen = ArrayList<Hypothesis>()
        fun clashes(r: Hypothesis) = chosen.any { c ->
            val ix = max(0, min(r.x1, c.x1) - max(r.x0, c.x0))
            val iy = max(0, min(r.y1, c.y1) - max(r.y0, c.y0))
            ix * iy > 0.05f * min(r.area, c.area)
        }
        PASSES.forEachIndexed { pass, threshold ->
            rects.filter { it.weakest >= threshold && it.interior < threshold && (pass == 0 || it.busy >= 0.15f) }
                // The finest valid partition wins: smallest valid rectangles first.
                .sortedWith(compareBy<Hypothesis> { it.area }.thenByDescending { it.meanSide })
                .forEach { if (!clashes(it)) chosen += it }
        }
        return chosen
    }

    private fun classify(r: Hypothesis, busy: FloatArray, dx: FloatArray, dy: FloatArray): Pair<DetectedTile, Float> {
        val w = r.x1 - r.x0
        val h = r.y1 - r.y0
        val area = w * h / (width * height).toFloat()
        val aspect = w / max(h, 1).toFloat()
        var textureSum = 0f
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) textureSum += dx[y * width + x] + dy[y * width + x]
        val texture = textureSum / (w * h)
        val kind = when {
            area < 0.004f || aspect > 6f || aspect < 0.25f -> TileKind.SLIVER
            // Still AND featureless: background or decoration. A calm reactor is still but textured.
            r.busy < 0.08f && texture < 3f -> TileKind.STATIC
            else -> TileKind.REACTOR
        }
        // Scene-cut synchrony: frames in which most of the tile changed at once.
        val cx0 = r.x0 / cellW
        val cx1 = max(cx0 + 1, r.x1 / cellW)
        val cy0 = r.y0 / cellW
        val cy1 = max(cy0 + 1, r.y1 / cellW)
        var cutFrames = 0
        for (cells in strongChange) {
            var strong = 0
            var total = 0
            for (cy in cy0 until min(cy1, cellsY)) for (cx in cx0 until min(cx1, cellsX)) {
                total++
                if (cells[cy * cellsX + cx]) strong++
            }
            if (total > 0 && strong > 0.6f * total) cutFrames++
        }
        val rect = LayoutRect(r.x0 / width.toDouble(), r.y0 / height.toDouble(), w / width.toDouble(), h / height.toDouble())
        return DetectedTile(rect, kind) to cutFrames / max(strongChange.size, 1).toFloat()
    }

    private fun markMovie(tiles: List<Pair<DetectedTile, Float>>): List<DetectedTile> {
        val candidates = tiles.filter { it.first.kind == TileKind.REACTOR }
        val movie = candidates.maxByOrNull { (tile, cuts) -> cuts * 4 + tile.rect.w * tile.rect.h }
        return tiles.map { (tile, cuts) ->
            val isMovie = movie != null && tile === movie.first && (cuts > 0.004f || tile.rect.w * tile.rect.h > 0.15)
            if (isMovie) tile.copy(kind = TileKind.MOVIE) else tile
        }
    }

    companion object {
        const val ANALYSIS_WIDTH = 480
        const val ANALYSIS_HEIGHT = 270
        const val ANALYSIS_FPS = 4
        private const val MIN_RUN = 16
        private const val MIN_SIZE = 14
        private const val INNER_MARGIN = 6
        private val PASSES = listOf(0.75f, 0.6f, 0.5f)
    }
}
