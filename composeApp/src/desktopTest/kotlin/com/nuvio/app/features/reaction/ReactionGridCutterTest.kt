package com.nuvio.app.features.reaction

import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionGridCutterTest {
    /**
     * TEM-like: a wall of cameras around a movie, 1 px borders, each camera a soft face on its own
     * background (as minutes of averaging leave it); the movie and the borders do or do not change.
     */
    @Test
    fun aWallOfCamerasIsCutOnItsBorders() {
        val w = 480
        val h = 270
        val mean = FloatArray(w * h) { 20f }
        val activity = FloatArray(w * h)
        val r = Random(7)
        val cams = ArrayList<IntArray>()
        val cw = 40
        val ch = 34
        // Two rows along the bottom, a column on each side; the middle is the movie.
        for (row in 0 until 2) for (col in 0 until 12) cams += intArrayOf(col * cw, h - (2 - row) * ch, col * cw + cw - 1, h - (1 - row) * ch - 1)
        for (row in 0 until 5) for (side in 0 until 2) cams += intArrayOf(if (side == 0) 0 else w - cw, row * ch, (if (side == 0) 0 else w - cw) + cw - 1, row * ch + ch - 1)
        for (c in cams) {
            val background = 40f + r.nextInt(150)
            val cx = (c[0] + c[2]) / 2.0
            val cy = (c[1] + c[3]) / 2.0
            for (y in c[1] until c[3]) for (x in c[0] until c[2]) {
                val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / 90.0
                mean[y * w + x] = (background + 60 * exp(-d2)).toFloat().coerceAtMost(255f)
                activity[y * w + x] = 6f + 10f * exp(-d2).toFloat()
            }
        }
        val movie = LayoutRect(cw / w.toDouble(), 0.0, 1 - 2.0 * cw / w, (h - 2.0 * ch) / h)
        for (y in 0 until (h - 2 * ch)) for (x in cw until w - cw) { mean[y * w + x] = 60f + (x * 7 + y * 3) % 90; activity[y * w + x] = 25f }

        val cells = ReactionGridCutter.cells(LayoutPicture(w, h, mean, activity), (cw * 0.6).toInt(), (ch * 0.6).toInt(), movie)
        val expected = cams.map { LayoutRect(it[0] / w.toDouble(), it[1] / h.toDouble(), (it[2] - it[0] + 1) / w.toDouble(), (it[3] - it[1] + 1) / h.toDouble()) }
        val found = expected.count { e -> cells.any { ReactionLayoutTimelineBuilder.iou(it, e) >= 0.8 } }
        val missed = expected.filter { e -> cells.none { ReactionLayoutTimelineBuilder.iou(it, e) >= 0.8 } }
            .map { e -> "$e -> " + cells.maxByOrNull { ReactionLayoutTimelineBuilder.iou(it, e) } }
        assertEquals(expected.size, found, "cameras found as their own cell: $found of ${expected.size}; cells ${cells.size}; missed $missed")
        assertTrue(cells.size <= expected.size + 2, "no extra slivers: ${cells.size} cells")
    }
}
