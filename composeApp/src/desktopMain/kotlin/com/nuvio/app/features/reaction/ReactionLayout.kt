package com.nuvio.app.features.reaction

import kotlin.math.max
import kotlin.math.min

/** A rectangle in pixels, or in 0..1 units when describing a region of a video frame. */
internal data class LayoutRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val aspect: Double get() = if (h > 0) w / h else 0.0

    fun normalizedIn(width: Double, height: Double) = LayoutRect(x / width, y / height, w / width, h / height)

    fun inset(px: Double) = LayoutRect(x + px, y + px, max(0.0, w - 2 * px), max(0.0, h - 2 * px))

    companion object {
        val FULL = LayoutRect(0.0, 0.0, 1.0, 1.0)
    }
}

internal object ReactionGridLayout {
    /**
     * Equal-height rows that keep the source order, the arrangement chosen for "everyone, equal grid".
     * For each possible row count the tiles are split optimally (the widest row as narrow as
     * possible), then the largest common tile height that fits both axes is used; the row count with
     * the tallest tiles wins. Rows are centred. [aspects] are width / height of each tile.
     */
    fun justify(aspects: List<Double>, width: Double, height: Double, gap: Double): List<LayoutRect> {
        val n = aspects.size
        if (n == 0 || width <= 0 || height <= 0) return emptyList()
        val prefix = DoubleArray(n + 1)
        aspects.forEachIndexed { i, a -> prefix[i + 1] = prefix[i] + a.coerceAtLeast(0.05) }
        fun aspectSum(from: Int, to: Int) = prefix[to] - prefix[from]

        var bestHeight = -1.0
        var bestRows: List<IntRange> = emptyList()
        for (rowCount in 1..n) {
            val rows = optimalRows(n, rowCount, ::aspectSum)
            var tileHeight = (height - gap * (rowCount + 1)) / rowCount
            for (row in rows) {
                val count = row.last - row.first + 1
                tileHeight = min(tileHeight, (width - gap * (count + 1)) / aspectSum(row.first, row.last + 1))
            }
            if (tileHeight > bestHeight) {
                bestHeight = tileHeight
                bestRows = rows
            }
            // More rows than this only shrink tiles once height becomes the binding limit.
            if ((height - gap * (rowCount + 1)) / rowCount < bestHeight) break
        }
        if (bestHeight <= 0) return List(n) { LayoutRect(0.0, 0.0, 0.0, 0.0) }

        val result = arrayOfNulls<LayoutRect>(n)
        val usedHeight = bestHeight * bestRows.size + gap * (bestRows.size - 1)
        var y = (height - usedHeight) / 2
        for (row in bestRows) {
            val count = row.last - row.first + 1
            val rowWidth = aspectSum(row.first, row.last + 1) * bestHeight + gap * (count - 1)
            var x = (width - rowWidth) / 2
            for (i in row) {
                val w = aspects[i].coerceAtLeast(0.05) * bestHeight
                result[i] = LayoutRect(x, y, w, bestHeight)
                x += w + gap
            }
            y += bestHeight + gap
        }
        return result.map { it!! }
    }

    /** Contiguous split of 0 until n into k rows minimising the largest row aspect sum (classic DP). */
    private fun optimalRows(n: Int, k: Int, aspectSum: (Int, Int) -> Double): List<IntRange> {
        val inf = Double.MAX_VALUE
        val cost = Array(k + 1) { DoubleArray(n + 1) { inf } }
        val cut = Array(k + 1) { IntArray(n + 1) }
        cost[0][0] = 0.0
        for (r in 1..k) {
            for (j in r..n) {
                for (i in (r - 1) until j) {
                    if (cost[r - 1][i] == inf) continue
                    val value = max(cost[r - 1][i], aspectSum(i, j))
                    if (value < cost[r][j]) {
                        cost[r][j] = value
                        cut[r][j] = i
                    }
                }
            }
        }
        val rows = ArrayList<IntRange>(k)
        var end = n
        for (r in k downTo 1) {
            val start = cut[r][end]
            rows += start until end
            end = start
        }
        return rows.reversed()
    }
}

internal data class SingleScreenLayout(
    /** Where the episode is drawn, in surface pixels. */
    val movie: LayoutRect,
    /** One rect per reactor tile, in surface pixels, in the input order. */
    val tiles: List<LayoutRect>,
)

internal object ReactionSingleScreenLayout {
    /**
     * The episode in the middle at [movieFraction] of the surface width (shrunk further if it
     * would not fit vertically), reactors around it: left and right columns, plus bands above and
     * below the movie when there is room. Tiles keep their order: left column first, then top,
     * right, bottom. Every split of the tiles between regions in proportion to region area is
     * tried with small adjustments, keeping the one with the tallest smallest tile.
     */
    fun arrange(
        width: Double,
        height: Double,
        movieAspect: Double,
        tileAspects: List<Double>,
        movieFraction: Double,
        gap: Double,
    ): SingleScreenLayout {
        val fraction = movieFraction.coerceIn(0.3, 0.95)
        var movieW = width * fraction
        var movieH = movieW / movieAspect
        if (movieH > height * 0.95) {
            movieH = height * 0.95
            movieW = movieH * movieAspect
        }
        val movie = LayoutRect((width - movieW) / 2, (height - movieH) / 2, movieW, movieH)
        if (tileAspects.isEmpty()) return SingleScreenLayout(movie, emptyList())

        val regions = buildList {
            add(LayoutRect(0.0, 0.0, movie.x, height))
            if (movie.y > height * 0.08) add(LayoutRect(movie.x, 0.0, movie.w, movie.y))
            add(LayoutRect(movie.right, 0.0, width - movie.right, height))
            if (height - movie.bottom > height * 0.08) add(LayoutRect(movie.x, movie.bottom, movie.w, height - movie.bottom))
        }.filter { it.w > gap * 3 && it.h > gap * 3 }

        val counts = proportionalCounts(tileAspects.size, regions.map { it.w * it.h })
        var best: List<LayoutRect>? = null
        var bestScore = -1.0
        for (candidate in neighbourhood(counts)) {
            val tiles = place(regions, candidate, tileAspects, gap)
            val score = tiles.minOfOrNull { it.h } ?: 0.0
            if (score > bestScore) {
                bestScore = score
                best = tiles
            }
        }
        return SingleScreenLayout(movie, best ?: emptyList())
    }

    private fun place(regions: List<LayoutRect>, counts: List<Int>, aspects: List<Double>, gap: Double): List<LayoutRect> {
        val out = ArrayList<LayoutRect>(aspects.size)
        var index = 0
        regions.forEachIndexed { r, region ->
            val slice = aspects.subList(index, index + counts[r])
            index += counts[r]
            ReactionGridLayout.justify(slice, region.w, region.h, gap).forEach { tile ->
                out += LayoutRect(region.x + tile.x, region.y + tile.y, tile.w, tile.h)
            }
        }
        return out
    }

    private fun proportionalCounts(n: Int, areas: List<Double>): List<Int> {
        val total = areas.sum().takeIf { it > 0 } ?: return areas.map { 0 }.toMutableList().also { if (it.isNotEmpty()) it[0] = n }
        val raw = areas.map { n * it / total }
        val counts = raw.map { it.toInt() }.toMutableList()
        var remaining = n - counts.sum()
        raw.indices.sortedByDescending { raw[it] - counts[it] }.forEach { i ->
            if (remaining > 0) {
                counts[i]++
                remaining--
            }
        }
        return counts
    }

    /** The proportional split plus every way of moving up to two tiles between two regions. */
    private fun neighbourhood(base: List<Int>): Sequence<List<Int>> = sequence {
        yield(base)
        for (from in base.indices) for (to in base.indices) {
            if (from == to) continue
            for (moved in 1..2) {
                if (base[from] < moved) continue
                yield(base.toMutableList().also { it[from] -= moved; it[to] += moved })
            }
        }
    }
}

/** mpv's video-margin-ratio-* values that put the episode exactly on [movie] inside the surface. */
internal data class MovieMargins(val left: Double, val right: Double, val top: Double, val bottom: Double) {
    companion object {
        val NONE = MovieMargins(0.0, 0.0, 0.0, 0.0)

        fun of(movie: LayoutRect, width: Double, height: Double) = MovieMargins(
            left = (movie.x / width).coerceIn(0.0, 1.0),
            right = ((width - movie.right) / width).coerceIn(0.0, 1.0),
            top = (movie.y / height).coerceIn(0.0, 1.0),
            bottom = ((height - movie.bottom) / height).coerceIn(0.0, 1.0),
        )
    }
}
