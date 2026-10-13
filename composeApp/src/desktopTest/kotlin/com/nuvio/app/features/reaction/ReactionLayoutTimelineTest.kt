package com.nuvio.app.features.reaction

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionLayoutTimelineTest {
    private val w = ReactionLayoutDetector.ANALYSIS_WIDTH
    private val h = ReactionLayoutDetector.ANALYSIS_HEIGHT

    /** Frames of a cols x rows grid of moving "cameras" with 2 px borders, the centre-ish cell a movie. */
    private class GridVideo(val cols: Int, val rows: Int, seed: Int) {
        private val random = Random(seed)
        private val shades = IntArray(cols * rows) { 50 + Random(seed + it).nextInt(140) }
        private val subjects = Array(cols * rows) { doubleArrayOf(10.0, 10.0) }
        private var scene = 0

        fun frame(t: Int, w: Int, h: Int): ByteArray {
            if (t % 20 == 0) scene = random.nextInt()
            val out = ByteArray(w * h) { 20 }
            val cw = w / cols
            val chh = h / rows
            val movie = (rows / 2) * cols + cols / 2
            for (c in 0 until cols * rows) {
                val x0 = (c % cols) * cw
                val y0 = (c / cols) * chh
                val s = subjects[c]
                s[0] = (s[0] + random.nextDouble(-4.0, 4.0)).coerceIn(2.0, cw * 0.5)
                s[1] = (s[1] + random.nextDouble(-2.0, 2.0)).coerceIn(2.0, chh * 0.3)
                for (y in y0 + 2 until y0 + chh - 2) for (x in x0 + 2 until x0 + cw - 2) {
                    val lx = x - x0
                    val ly = y - y0
                    val v = if (c == movie) {
                        30 + abs((lx * 7 + ly * 13 + t) xor scene) % 180
                    } else if (lx in s[0].toInt()..s[0].toInt() + cw / 3 && ly in s[1].toInt()..s[1].toInt() + chh / 2) {
                        120 + (lx * 11 + ly * 5) % 90
                    } else {
                        shades[c] + random.nextInt(-5, 6)
                    }
                    out[y * w + x] = v.coerceIn(0, 255).toByte()
                }
            }
            return out
        }
    }

    @Test
    fun aMidVideoRearrangementBecomesTwoEpochsSplitNearTheChange() {
        val builder = ReactionLayoutTimelineBuilder()
        val before = GridVideo(3, 3, seed = 3)
        val after = GridVideo(2, 2, seed = 9)
        val switchFrame = 360 // 90 s at 4 fps
        for (t in 0 until 720) builder.add(if (t < switchFrame) before.frame(t, w, h) else after.frame(t, w, h))
        builder.finish()
        val timeline = builder.timeline()
        assertEquals(2, timeline.epochs.size, timeline.epochs.joinToString { "${it.startMs}-${it.endMs}:${it.layout.reactors.size}" })
        val boundary = timeline.epochs[0].endMs
        assertTrue(abs(boundary - 90_000) <= 5_000, "layout change placed at $boundary ms, expected ~90000")
        assertTrue(timeline.at(30_000)!!.reactors.size >= 6, "3x3 layout should have ~8 reactors")
        assertTrue(timeline.at(150_000)!!.reactors.size in 2..3, "2x2 layout has 3 reactors and a movie")
    }

    @Test
    fun similarityMatchesLayoutsBoxForBox() {
        val a = DetectedLayout(listOf(DetectedTile(LayoutRect(0.0, 0.0, 0.5, 0.5), TileKind.REACTOR), DetectedTile(LayoutRect(0.5, 0.0, 0.5, 0.5), TileKind.MOVIE)))
        val nudged = DetectedLayout(listOf(DetectedTile(LayoutRect(0.01, 0.0, 0.49, 0.5), TileKind.REACTOR), DetectedTile(LayoutRect(0.5, 0.01, 0.5, 0.49), TileKind.MOVIE)))
        val other = DetectedLayout(listOf(DetectedTile(LayoutRect(0.0, 0.0, 1.0, 0.3), TileKind.REACTOR)))
        assertEquals(1.0, ReactionLayoutTimelineBuilder.similarity(a, nudged), 1e-9)
        assertEquals(0.0, ReactionLayoutTimelineBuilder.similarity(a, other), 1e-9)
    }

    @Test
    fun aLaterAnalysisIsLaidOverWhatWasKnown() {
        val a = DetectedLayout(listOf(DetectedTile(LayoutRect(0.0, 0.0, 0.5, 0.5), TileKind.REACTOR)))
        val b = DetectedLayout(listOf(DetectedTile(LayoutRect(0.5, 0.5, 0.5, 0.5), TileKind.REACTOR)))
        val known = ReactionLayoutTimeline(listOf(LayoutEpoch(0, 60_000, a)))
        // The user seeked to 40 s; analysis from there found another layout until 90 s.
        val merged = known.overlaidWith(ReactionLayoutTimeline(listOf(LayoutEpoch(40_000, 90_000, b))))
        assertEquals(listOf(0L to 40_000L, 40_000L to 90_000L), merged.epochs.map { it.startMs to it.endMs })
        assertEquals(a, merged.at(10_000))
        assertEquals(b, merged.at(50_000))
        assertTrue(merged.covers(89_000))
        assertTrue(!merged.covers(95_000))
    }

    @Test
    fun timelineLookupFallsBackToTheNearestEpoch() {
        val l1 = DetectedLayout(listOf(DetectedTile(LayoutRect(0.0, 0.0, 0.5, 0.5), TileKind.REACTOR)))
        val l2 = DetectedLayout(emptyList())
        val timeline = ReactionLayoutTimeline(listOf(LayoutEpoch(0, 10_000, l1), LayoutEpoch(10_000, 20_000, l2)))
        assertEquals(l1, timeline.at(5_000))
        assertEquals(l2, timeline.at(15_000))
        assertEquals(l2, timeline.at(99_000))
        assertEquals(1, timeline.epochIndexAt(10_000))
    }
}
