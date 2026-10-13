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

    /** TEM's Interstellar compilation, as its 45 s windows saw it (first real test, 2026-10-13). */
    @Test
    fun theMovieBoxIsHeldSteadyAndAMistakenReactorGoesBack() {
        val frame = LayoutRect(0.25, 0.15, 0.50, 0.53)
        val scope = LayoutRect(0.25, 0.22, 0.50, 0.40)
        val bigger = LayoutRect(0.25, 0.15, 0.51, 0.56)
        val leftReactor = LayoutRect(0.0, 0.50, 0.07, 0.11)
        val other = DetectedTile(LayoutRect(0.0, 0.0, 0.12, 0.12), TileKind.REACTOR)
        fun layout(movie: LayoutRect, vararg extra: DetectedTile) = DetectedLayout(listOf(other, DetectedTile(movie, TileKind.MOVIE)) + extra)
        // A piece of the real movie found as a "reactor" while the left reactor was taken for the movie.
        val moviePiece = DetectedTile(LayoutRect(0.30, 0.25, 0.30, 0.30), TileKind.REACTOR)
        val epochs = listOf(
            LayoutEpoch(0, 40_000, layout(frame)),
            LayoutEpoch(40_000, 70_000, layout(scope)),
            LayoutEpoch(70_000, 80_000, layout(leftReactor, moviePiece)),
            LayoutEpoch(80_000, 85_000, layout(bigger)),
            LayoutEpoch(85_000, 120_000, layout(frame)),
            LayoutEpoch(120_000, 150_000, layout(scope)),
        )
        val steady = ReactionLayoutTimeline(epochs).steadied()
        steady.epochs.forEach { assertEquals(frame, it.layout.movie?.rect, "epoch ${it.startMs}") }
        val mistaken = steady.epochs[2].layout
        assertTrue(mistaken.reactors.any { it.rect == leftReactor }, "the left reactor is a reactor again")
        assertTrue(mistaken.tiles.none { it == moviePiece }, "the piece of the movie is no reactor")
        assertTrue(steady.epochs.all { e -> e.layout.reactors.any { it == other } })
    }

    @Test
    fun aGenuinelyDifferentArrangementKeepsItsOwnMovie() {
        val main = LayoutRect(0.25, 0.15, 0.50, 0.53)
        val fullScreen = LayoutRect(0.0, 0.0, 1.0, 1.0)
        val reactorOnly = DetectedLayout(listOf(DetectedTile(LayoutRect(0.3, 0.2, 0.4, 0.5), TileKind.REACTOR)))
        val epochs = listOf(
            LayoutEpoch(0, 100_000, DetectedLayout(listOf(DetectedTile(main, TileKind.MOVIE)))),
            LayoutEpoch(100_000, 120_000, DetectedLayout(listOf(DetectedTile(fullScreen, TileKind.MOVIE)))),
            // A stretch of reactors only, one where the movie usually is: not given a movie.
            LayoutEpoch(120_000, 130_000, reactorOnly),
        )
        val steady = ReactionLayoutTimeline(epochs).steadied()
        assertEquals(main, steady.epochs[0].layout.movie?.rect)
        assertEquals(fullScreen, steady.epochs[1].layout.movie?.rect)
        assertEquals(reactorOnly, steady.epochs[2].layout)
    }

    /** A 4 x 3 wall of cameras; each window sees a different part of it, one sees a block as one box. */
    @Test
    fun everyReactorOfAnArrangementIsShownInEveryEpoch() {
        val cams = List(12) { i -> LayoutRect((i % 4) * 0.25, (i / 4) * 0.2, 0.25, 0.2) }
        fun reactors(vararg rects: LayoutRect) = DetectedLayout(rects.map { DetectedTile(it, TileKind.REACTOR) })
        val blockOfFour = LayoutRect(0.0, 0.0, 0.5, 0.4) // cameras 0, 1, 4, 5 with their borders unseen
        val epochs = listOf(
            LayoutEpoch(0, 45_000, reactors(*cams.subList(0, 6).toTypedArray())),
            LayoutEpoch(45_000, 90_000, reactors(*cams.subList(6, 12).toTypedArray(), cams[0])),
            LayoutEpoch(90_000, 135_000, reactors(blockOfFour, cams[2], cams[3], cams[7], cams[11])),
            LayoutEpoch(135_000, 180_000, reactors(cams[1], cams[4], cams[5], cams[8], cams[9], cams[10])),
            // The block again, so it is as steady as the cameras inside it and has to give way.
            LayoutEpoch(180_000, 225_000, reactors(blockOfFour, cams[6], cams[10])),
        )
        val steady = ReactionLayoutTimeline(epochs).steadied()
        steady.epochs.forEach { e ->
            assertEquals(cams.toSet(), e.layout.reactors.map { it.rect }.toSet(), "epoch ${e.startMs}")
        }
    }

    @Test
    fun aRealRearrangementKeepsItsOwnReactors() {
        // LoneReact-style: two cameras side by side, later three across - their boxes cross.
        val two = DetectedLayout(listOf(DetectedTile(LayoutRect(0.0, 0.0, 0.5, 1.0), TileKind.REACTOR), DetectedTile(LayoutRect(0.5, 0.0, 0.5, 1.0), TileKind.REACTOR)))
        val three = DetectedLayout(List(3) { DetectedTile(LayoutRect(it / 3.0, 0.0, 1 / 3.0, 1.0), TileKind.REACTOR) })
        val steady = ReactionLayoutTimeline(
            listOf(LayoutEpoch(0, 100_000, two), LayoutEpoch(100_000, 200_000, three), LayoutEpoch(200_000, 300_000, two)),
        ).steadied()
        assertEquals(2, steady.epochs[0].layout.reactors.size)
        assertEquals(3, steady.epochs[1].layout.reactors.size)
        assertEquals(2, steady.epochs[2].layout.reactors.size)
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
