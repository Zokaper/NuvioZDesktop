package com.nuvio.app.features.reaction

import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReactionLayoutDetectorTest {
    private companion object {
        const val SUBJECT_W = 48
        const val SUBJECT_H = 56
    }

    private val w = ReactionLayoutDetector.ANALYSIS_WIDTH
    private val h = ReactionLayoutDetector.ANALYSIS_HEIGHT

    /** A 3x3 mosaic: the centre cell is the movie (hard cuts), the other eight are cameras. */
    private fun mosaic(gutter: Int, frames: Int, seed: Int = 7): Pair<List<ByteArray>, List<IntArray>> {
        val random = Random(seed)
        val cellW = w / 3
        val cellH = h / 3
        val cells = (0 until 9).map { c -> intArrayOf((c % 3) * cellW, (c / 3) * cellH, (c % 3 + 1) * cellW, (c / 3 + 1) * cellH) }
        // Each camera: its own still room (a smooth shade with soft shapes, as real rooms are) and a
        // subject wandering independently.
        val backgrounds = cells.indices.map { c ->
            val r = Random(seed * 31 + c)
            val shade = 50 + r.nextInt(140)
            val gx = r.nextDouble(-0.4, 0.4)
            val gy = r.nextDouble(-0.4, 0.4)
            IntArray(cellW * cellH) { i -> (shade + gx * (i % cellW) + gy * (i / cellW) + 6 * kotlin.math.sin(i % cellW / 9.0)).toInt() }
        }
        val subjects = cells.indices.map { doubleArrayOf(cellW / 2.0, cellH / 2.0) }
        var sceneSeed = 1
        val out = ArrayList<ByteArray>()
        for (t in 0 until frames) {
            if (t % 20 == 0) sceneSeed = random.nextInt()
            val frame = ByteArray(w * h) { 20 }
            cells.forEachIndexed { c, (x0, y0, x1, y1) ->
                val movie = c == 4
                val scene = Random(sceneSeed)
                val sx = subjects[c]
                if (!movie) {
                    // People fill much of a webcam frame and never sit perfectly still.
                    sx[0] = (sx[0] + random.nextDouble(-4.0, 4.0)).coerceIn(4.0, cellW - SUBJECT_W - 4.0)
                    sx[1] = (sx[1] + random.nextDouble(-2.0, 2.0)).coerceIn(4.0, cellH - SUBJECT_H - 4.0)
                }
                val sceneShade = scene.nextInt(30, 200)
                for (y in y0 + gutter until y1 - gutter) for (x in x0 + gutter until x1 - gutter) {
                    val lx = x - x0
                    val ly = y - y0
                    val value = if (movie) {
                        // A shot: its own picture, panning slowly, replaced wholesale at every cut.
                        (sceneShade + ((lx + t) * 7 + ly * 13 xor sceneSeed) % 60).coerceIn(0, 255)
                    } else {
                        val inSubject = lx in sx[0].toInt()..sx[0].toInt() + SUBJECT_W && ly in sx[1].toInt()..sx[1].toInt() + SUBJECT_H
                        // Webcam sensor noise everywhere, a textured subject that moves.
                        if (inSubject) 120 + ((lx - sx[0].toInt()) * 11 + (ly - sx[1].toInt()) * 5) % 90 else backgrounds[c][ly * cellW + lx] + random.nextInt(-5, 6)
                    }
                    frame[y * w + x] = value.coerceIn(0, 255).toByte()
                }
            }
            out += frame
        }
        return out to cells
    }

    private fun assertFinds(expected: IntArray, layout: DetectedLayout, tolerancePx: Int, kind: TileKind) {
        val match = layout.tiles.firstOrNull { tile ->
            tile.kind == kind &&
                abs(tile.rect.x * w - expected[0]) <= tolerancePx && abs(tile.rect.y * h - expected[1]) <= tolerancePx &&
                abs(tile.rect.right * w - expected[2]) <= tolerancePx && abs(tile.rect.bottom * h - expected[3]) <= tolerancePx
        }
        assertNotNull(match, "no $kind at ${expected.toList()} in ${layout.tiles.map { "${it.kind} ${(it.rect.x * w).toInt()},${(it.rect.y * h).toInt()},${(it.rect.right * w).toInt()},${(it.rect.bottom * h).toInt()}" }}")
    }

    @Test
    fun aGutteredMosaicIsFoundTileForTileWithTheMovieInTheMiddle() {
        val (frames, cells) = mosaic(gutter = 2, frames = 240)
        val detector = ReactionLayoutDetector(w, h)
        frames.forEach(detector::add)
        val layout = detector.detect()
        cells.forEachIndexed { c, cell -> assertFinds(cell, layout, tolerancePx = 5, if (c == 4) TileKind.MOVIE else TileKind.REACTOR) }
        assertEquals(8, layout.reactors.size, "${layout.tiles}")
    }

    @Test
    fun cameraBoxesWithNoBordersAreStillSeparated() {
        val (frames, cells) = mosaic(gutter = 0, frames = 240, seed = 11)
        val detector = ReactionLayoutDetector(w, h)
        frames.forEach(detector::add)
        val layout = detector.detect()
        assertNotNull(layout.movie, "movie not found")
        assertFinds(cells[4], layout, tolerancePx = 5, TileKind.MOVIE)
        assertTrue(layout.reactors.size >= 6, "found ${layout.reactors.size} of 8 borderless boxes: ${layout.tiles}")
    }

    @Test
    fun reactorsComeInReadingOrder() {
        val layout = DetectedLayout(
            listOf(
                DetectedTile(LayoutRect(0.5, 0.5, 0.2, 0.2), TileKind.REACTOR),
                DetectedTile(LayoutRect(0.1, 0.5, 0.2, 0.2), TileKind.REACTOR),
                DetectedTile(LayoutRect(0.6, 0.0, 0.2, 0.2), TileKind.REACTOR),
            ),
        )
        assertEquals(listOf(0.6, 0.1, 0.5), layout.reactors.map { it.rect.x })
    }

    /**
     * Real compilations, when present locally (never in the repo - copyrighted): 480x270 grey frames
     * at 4 fps in `<name>.gray`, made with libmpv encode mode (see STATUS.md). Prints the layout and
     * checks the floor the prototype reached.
     */
    @Test
    fun realCompilationsWhenAvailable() {
        val dir = System.getenv("NUVIO_REACTION_LAYOUT_FIXTURES")?.let(::File) ?: return
        val floors = mapOf("tem" to 40, "volt" to 15, "lone" to 6)
        for ((name, floor) in floors) {
            val file = File(dir, "$name.gray").takeIf { it.isFile } ?: continue
            val detector = ReactionLayoutDetector(w, h)
            val bytes = file.readBytes()
            val frameSize = w * h
            for (offset in 0 until bytes.size / frameSize) detector.add(bytes.copyOfRange(offset * frameSize, (offset + 1) * frameSize))
            val layout = detector.detect()
            File(dir, "$name-kotlin.txt").writeText(layout.tiles.joinToString("\n") { "${it.kind} ${it.rect}" })
            assertNotNull(layout.movie, "$name: no movie")
            assertTrue(layout.reactors.size >= floor, "$name: ${layout.reactors.size} reactors, prototype floor $floor")
        }
    }
}
