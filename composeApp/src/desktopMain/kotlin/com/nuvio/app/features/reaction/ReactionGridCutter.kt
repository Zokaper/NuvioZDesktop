package com.nuvio.app.features.reaction

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One layout window's mean frame and per-pixel standard deviation, in grey levels. */
internal class WindowPicture(val width: Int, val height: Int, val mean: ByteArray, val deviation: ByteArray)

/**
 * A long-term picture of one arrangement: the mean of its frames, and how much each pixel changes
 * within a layout window (the standard deviation over 45 s, averaged over windows).
 */
internal class LayoutPicture(val width: Int, val height: Int, val mean: FloatArray, val activity: FloatArray) {
    companion object {
        /** The frame-weighted average of several windows' pictures. */
        fun average(pictures: List<Pair<WindowPicture, Int>>): LayoutPicture? {
            val first = pictures.firstOrNull()?.first ?: return null
            val n = first.width * first.height
            val mean = FloatArray(n)
            val activity = FloatArray(n)
            var total = 0
            for ((p, frames) in pictures) {
                if (p.width != first.width || p.height != first.height) continue
                for (i in 0 until n) {
                    mean[i] += (p.mean[i].toInt() and 0xFF) * frames.toFloat()
                    activity[i] += (p.deviation[i].toInt() and 0xFF) * frames.toFloat()
                }
                total += frames
            }
            if (total == 0) return null
            for (i in 0 until n) { mean[i] /= total; activity[i] /= total }
            return LayoutPicture(first.width, first.height, mean, activity)
        }
    }
}

/**
 * Cuts a compilation's long-term picture into its camera boxes along their borders (a recursive
 * XY-cut). Averaged over minutes, the people in a grid of cameras blur into soft shapes while every
 * border between two cameras stays a sharp straight line - even TEM's, which are under a pixel wide at
 * 480x270 and invisible to motion. So a region is cut along the row or column whose pixels show a
 * step on [LINE_SHARE] of its length or more, the strongest first, and again inside each half, as long
 * as both halves stay at least a [minW] x [minH] camera; what is left that changes over time (people,
 * not borders or backgrounds) and is not in the movie is a camera.
 *
 * Measured on TEM's Interstellar compilation (20 min mean at 480x270, 2026-10-13): 78 of its ~84
 * cameras as their own boxes, edges on the borders. Motion alone found 2-34 per 45 s window; cutting
 * its blocks into an even grid put cuts through faces (Debug 95).
 */
internal object ReactionGridCutter {
    /** Grey levels across a border (|I[+1] - I[-1]|). */
    private const val STEP = 10f
    /** Share of a line's pixels that must step for it to be a border, not a face's outline. */
    private const val LINE_SHARE = 0.7
    /** Grey levels of change within a window: cameras measured 12-19, borders and backgrounds under 1. */
    private const val MIN_ACTIVITY = 3f

    fun cells(picture: LayoutPicture, minW: Int, minH: Int, exclude: LayoutRect?): List<LayoutRect> {
        val w = picture.width
        val h = picture.height
        val m = picture.mean
        val stepY = BooleanArray(w * h) // a horizontal border at y
        val stepX = BooleanArray(w * h) // a vertical border at x
        for (y in 1 until h - 1) for (x in 0 until w) stepY[y * w + x] = abs(m[(y + 1) * w + x] - m[(y - 1) * w + x]) > STEP
        for (y in 0 until h) for (x in 1 until w - 1) stepX[y * w + x] = abs(m[y * w + x + 1] - m[y * w + x - 1]) > STEP
        val leaves = ArrayList<IntArray>()
        cut(0, 0, w, h, stepX, stepY, w, max(minW, 4), max(minH, 4), leaves, 0)
        val ex = exclude?.let { intArrayOf((it.x * w).toInt(), (it.y * h).toInt(), (it.right * w).toInt(), (it.bottom * h).toInt()) }
        return leaves.filter { c ->
            val cw = c[2] - c[0]
            val ch = c[3] - c[1]
            if (cw < minW || ch < minH || cw.toDouble() / ch !in 0.4..3.5) return@filter false
            if (ex != null && overlap(c, ex) > 0.3 * cw * ch) return@filter false
            var sum = 0.0
            var count = 0
            for (y in c[1] + 2 until c[3] - 2) for (x in c[0] + 2 until c[2] - 2) { sum += picture.activity[y * w + x]; count++ }
            count > 0 && sum / count >= MIN_ACTIVITY
        }.map { LayoutRect(it[0] / w.toDouble(), it[1] / h.toDouble(), (it[2] - it[0]) / w.toDouble(), (it[3] - it[1]) / h.toDouble()) }
    }

    private fun cut(x0: Int, y0: Int, x1: Int, y1: Int, stepX: BooleanArray, stepY: BooleanArray, w: Int, minW: Int, minH: Int, out: MutableList<IntArray>, depth: Int) {
        var best = 0.0
        var horizontal = false
        var at = -1
        if (y1 - y0 >= 2 * minH) {
            for (y in y0 + minH until y1 - minH) {
                val share = rowShare(stepY, w, y, x0, x1)
                if (share >= LINE_SHARE && share > best) { best = share; horizontal = true; at = y }
            }
        }
        if (x1 - x0 >= 2 * minW) {
            for (x in x0 + minW until x1 - minW) {
                val share = columnShare(stepX, w, x, y0, y1)
                if (share >= LINE_SHARE && share > best) { best = share; horizontal = false; at = x }
            }
        }
        // A thin border steps on both of its sides: the border is the pixel between the two steps.
        if (at >= 0) {
            fun share(i: Int) = if (horizontal) (if (i in 1 until h(stepY, w) - 1) rowShare(stepY, w, i, x0, x1) else 0.0)
                else (if (i in 1 until w - 1) columnShare(stepX, w, i, y0, y1) else 0.0)
            when {
                share(at + 2) >= 0.8 * best -> at += 1
                share(at - 2) >= 0.8 * best -> at -= 1
            }
        }
        if (at < 0 || depth > 60) {
            out += intArrayOf(x0, y0, x1, y1)
            return
        }
        if (horizontal) {
            cut(x0, y0, x1, at, stepX, stepY, w, minW, minH, out, depth + 1)
            cut(x0, at + 1, x1, y1, stepX, stepY, w, minW, minH, out, depth + 1)
        } else {
            cut(x0, y0, at, y1, stepX, stepY, w, minW, minH, out, depth + 1)
            cut(at + 1, y0, x1, y1, stepX, stepY, w, minW, minH, out, depth + 1)
        }
    }

    private fun h(step: BooleanArray, w: Int) = step.size / w

    private fun rowShare(step: BooleanArray, w: Int, y: Int, x0: Int, x1: Int): Double {
        var n = 0
        for (x in x0 until x1) if (step[y * w + x]) n++
        return n.toDouble() / (x1 - x0)
    }

    private fun columnShare(step: BooleanArray, w: Int, x: Int, y0: Int, y1: Int): Double {
        var n = 0
        for (y in y0 until y1) if (step[y * w + x]) n++
        return n.toDouble() / (y1 - y0)
    }

    private fun overlap(a: IntArray, b: IntArray): Int =
        max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
}
