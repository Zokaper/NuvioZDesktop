package com.nuvio.app.features.reaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionLayoutTest {
    // The nine LoneReact boxes measured in Stage 0 (S5), as width / height.
    private val loneReact = listOf(1.82, 3.69, 1.82, 1.71, 1.73, 1.72, 1.73, 5.45, 1.80)

    private fun assertInside(tiles: List<LayoutRect>, width: Double, height: Double) {
        tiles.forEach { t ->
            assertTrue(t.x >= -1e-6 && t.y >= -1e-6 && t.right <= width + 1e-6 && t.bottom <= height + 1e-6, "outside: $t")
        }
    }

    private fun assertNoOverlap(tiles: List<LayoutRect>) {
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            val a = tiles[i]
            val b = tiles[j]
            val overlaps = a.x < b.right - 1e-6 && b.x < a.right - 1e-6 && a.y < b.bottom - 1e-6 && b.y < a.bottom - 1e-6
            assertTrue(!overlaps, "tiles $i and $j overlap: $a / $b")
        }
    }

    @Test
    fun justifyMatchesTheStageZeroSpike() {
        // The spike's Python DP produced three 170 px rows for these boxes at 1280x720, gap 8.
        val tiles = ReactionGridLayout.justify(loneReact, 1280.0, 720.0, 8.0)
        assertEquals(9, tiles.size)
        assertEquals(3, tiles.map { it.y }.distinct().size)
        assertEquals(170.0, tiles.first().h, 1.0)
        assertTrue(tiles.all { it.h == tiles.first().h }, "every row shares one height")
        assertInside(tiles, 1280.0, 720.0)
        assertNoOverlap(tiles)
        // Aspect ratios are kept, so nothing is cropped or stretched.
        tiles.forEachIndexed { i, t -> assertEquals(loneReact[i], t.aspect, 1e-6) }
    }

    @Test
    fun eightyTilesStillFillTheScreen() {
        val tem = List(80) { 1.0 }
        val tiles = ReactionGridLayout.justify(tem, 2560.0, 1440.0, 6.0)
        assertEquals(80, tiles.size)
        assertInside(tiles, 2560.0, 1440.0)
        assertNoOverlap(tiles)
        // 80 squares on 16:9: a 12 x 7 grid is the best equal-height fit, about 200 px each.
        assertTrue(tiles.first().h > 190, "tiles too small: ${tiles.first().h}")
    }

    @Test
    fun aSingleTileIsCentredAndAspectCorrect() {
        val tiles = ReactionGridLayout.justify(listOf(16.0 / 9.0), 1000.0, 1000.0, 0.0)
        val t = tiles.single()
        assertEquals(1000.0, t.w, 1e-6)
        assertEquals(562.5, t.h, 1e-6)
        assertEquals(218.75, t.y, 1e-6)
    }

    @Test
    fun singleScreenKeepsTheMovieCentredAndTilesAroundIt() {
        val layout = ReactionSingleScreenLayout.arrange(
            width = 1920.0, height = 1080.0, movieAspect = 16.0 / 9.0,
            tileAspects = loneReact, movieFraction = 0.6, gap = 8.0,
        )
        assertEquals(1152.0, layout.movie.w, 1e-6)
        assertEquals(1920.0 / 2, layout.movie.x + layout.movie.w / 2, 1e-6)
        assertEquals(1080.0 / 2, layout.movie.y + layout.movie.h / 2, 1e-6)
        assertEquals(9, layout.tiles.size)
        assertInside(layout.tiles, 1920.0, 1080.0)
        assertNoOverlap(layout.tiles + layout.movie)
        assertTrue(layout.tiles.all { it.h > 60 }, "every reactor readable: ${layout.tiles.map { it.h.toInt() }}")
    }

    @Test
    fun marginsPutTheEpisodeExactlyOnTheMovieRect() {
        val movie = LayoutRect(384.0, 216.0, 1152.0, 648.0)
        val m = MovieMargins.of(movie, 1920.0, 1080.0)
        assertEquals(0.2, m.left, 1e-9)
        assertEquals(0.2, m.right, 1e-9)
        assertEquals(0.2, m.top, 1e-9)
        assertEquals(0.2, m.bottom, 1e-9)
    }
}
