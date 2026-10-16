package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Color as SkiaColor

/**
 * The Windows poster downscale runs on background workers, not inside draw (Performance Phase 2).
 * Every test drives the workers with a test dispatcher, so "has not run yet" is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopImageDownscalerTest {
    private class Recorder(var wants: Boolean = true) : DesktopImageDownscaler.Waiter {
        val results = mutableListOf<Pair<Bitmap?, Boolean>>()

        override fun wantsScaled(width: Int, height: Int): Boolean = wants

        override fun onScaled(width: Int, height: Int, bitmap: Bitmap?, failed: Boolean) {
            results += bitmap to failed
        }
    }

    private class Harness(scope: TestScope, workers: Int = 2, private val fail: Boolean = false) {
        val scaled = mutableListOf<Pair<Int, Int>>()
        val cache: MemoryCache = MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build()
        val downscaler = DesktopImageDownscaler(
            scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler)),
            maxWorkers = workers,
        ) { source, width, height ->
            scaled += width to height
            if (fail) null else scaleHighQuality(source, width, height)
        }
    }

    private fun solid(width: Int, height: Int, color: Int = SkiaColor.RED): Bitmap =
        Bitmap().apply {
            allocN32Pixels(width, height)
            erase(color)
            setImmutable()
        }

    private fun ScaledBitmapPainter.drawAt(width: Int, height: Int): ImageBitmap {
        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat())) {
            with(this@drawAt) { draw(size) }
        }
        return target
    }

    private fun ImageBitmap.centre(): Color = toPixelMap()[width / 2, height / 2]

    @Test
    fun identicalRequestsShareOneScaleAndLandInCoilsMemoryCache() = runTest {
        val h = Harness(this)
        val key = MemoryCache.Key("poster")
        val source = solid(400, 600)
        val first = Recorder()
        val second = Recorder()

        h.downscaler.request(source, 100, 152, h.cache, key, first)
        h.downscaler.request(source, 100, 152, h.cache, key, second)
        advanceUntilIdle()

        assertEquals(listOf(100 to 152), h.scaled)
        val bitmap = assertNotNull(first.results.single().first)
        assertEquals(100, bitmap.width)
        assertEquals(152, bitmap.height)
        assertTrue(bitmap.isImmutable)
        assertTrue(second.results.single().first === bitmap)
        assertTrue(h.downscaler.cached(h.cache, key, 100, 152) != null)
        assertNull(h.downscaler.cached(h.cache, key, 100, 156), "another size is another entry")
        assertNull(h.cache[key], "the source's own entry is untouched")
    }

    @Test
    fun newestRequestIsScaledFirst() = runTest {
        val h = Harness(this, workers = 1)
        val source = solid(400, 600)
        listOf(100, 120, 140).forEach { width ->
            h.downscaler.request(source, width, width, h.cache, MemoryCache.Key("p$width"), Recorder())
        }
        advanceUntilIdle()

        assertEquals(listOf(140, 120, 100), h.scaled.map { it.first })
    }

    @Test
    fun aJobNobodyIsDrawingAnyMoreIsSkippedNotScaled() = runTest {
        val h = Harness(this)
        val gone = Recorder(wants = false)
        h.downscaler.request(solid(400, 600), 100, 152, h.cache, MemoryCache.Key("poster"), gone)
        advanceUntilIdle()

        assertTrue(h.scaled.isEmpty())
        assertEquals(listOf<Pair<Bitmap?, Boolean>>(null to false), gone.results)
        assertNull(h.downscaler.cached(h.cache, MemoryCache.Key("poster"), 100, 152))
    }

    @Test
    fun theScaleAveragesInsteadOfSamplingNearest() {
        // A one-pixel black/white checkerboard: nearest-neighbour would come out all black or all
        // white, a mipmapped downscale comes out mid-grey everywhere.
        val size = 512
        val pixels = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val v = if ((x + y) % 2 == 0) 0 else 255
            val i = (y * size + x) * 4
            pixels[i] = v.toByte(); pixels[i + 1] = v.toByte(); pixels[i + 2] = v.toByte(); pixels[i + 3] = 255.toByte()
        }
        val source = Bitmap().apply {
            installPixels(ImageInfo.makeN32(size, size, ColorAlphaType.PREMUL), pixels, size * 4)
        }

        val scaled = assertNotNull(scaleHighQuality(source, 64, 64))

        for (y in 0 until 64) for (x in 0 until 64) {
            val red = SkiaColor.getR(scaled.getColor(x, y))
            assertTrue(red in 100..156, "pixel $x,$y is $red")
        }
    }

    @Test
    fun theFirstDrawQueuesTheScaleInsteadOfDoingIt() = runTest {
        val h = Harness(this)
        val painter = ScaledBitmapPainter(solid(400, 600), h.cache, MemoryCache.Key("poster"), h.downscaler)

        val first = painter.drawAt(100, 150)
        assertTrue(h.scaled.isEmpty(), "nothing is scaled inside draw")
        assertEquals(0f, first.centre().alpha, "the card stays empty until the scale lands")

        advanceUntilIdle()
        assertEquals(listOf(100 to 150), h.scaled, "one background scale, at the drawn size")
        assertEquals(Color.Red, painter.drawAt(100, 150).centre())
        assertEquals(1, h.scaled.size, "a size it already holds is not scaled again")
    }

    @Test
    fun aCardThatComesBackDrawsFromTheMemoryCacheAtOnce() = runTest {
        val h = Harness(this)
        val source = solid(400, 600)
        val key = MemoryCache.Key("poster")
        ScaledBitmapPainter(source, h.cache, key, h.downscaler).drawAt(100, 150)
        advanceUntilIdle()

        // A new painter for the same poster, as when a card scrolls back into composition.
        val again = ScaledBitmapPainter(source, h.cache, key, h.downscaler)
        assertEquals(Color.Red, again.drawAt(100, 150).centre())
        assertEquals(1, h.scaled.size)
    }

    @Test
    fun aSourceNearItsDrawnSizeIsDrawnDirectly() = runTest {
        val h = Harness(this)
        val painter = ScaledBitmapPainter(solid(104, 156), h.cache, MemoryCache.Key("poster"), h.downscaler)

        assertEquals(Color.Red, painter.drawAt(100, 150).centre())
        advanceUntilIdle()
        assertTrue(h.scaled.isEmpty())
    }

    @Test
    fun aSkippedScaleIsAskedForAgainWhenTheCardDrawsAgain() = runTest {
        val h = Harness(this)
        val painter = ScaledBitmapPainter(solid(400, 600), h.cache, MemoryCache.Key("poster"), h.downscaler)
        painter.drawAt(100, 150)
        Thread.sleep(400) // longer than the window in which a card counts as on screen
        advanceUntilIdle()
        assertTrue(h.scaled.isEmpty(), "the card stopped drawing, so its job was skipped")

        painter.drawAt(100, 150)
        advanceUntilIdle()
        assertEquals(1, h.scaled.size)
        assertEquals(Color.Red, painter.drawAt(100, 150).centre())
    }

    @Test
    fun aFailedScaleFallsBackToTheSourceWithoutRetryingEveryFrame() = runTest {
        val h = Harness(this, fail = true)
        val painter = ScaledBitmapPainter(solid(400, 600), h.cache, MemoryCache.Key("poster"), h.downscaler)
        painter.drawAt(100, 150)
        advanceUntilIdle()

        repeat(3) { assertEquals(Color.Red, painter.drawAt(100, 150).centre()) }
        advanceUntilIdle()
        assertEquals(1, h.scaled.size)
        assertNull(h.downscaler.cached(h.cache, MemoryCache.Key("poster"), 100, 150))
    }

    @Test
    fun aSourceCoilDidNotCacheIsStillScaledInTheBackground() = runTest {
        val h = Harness(this)
        val painter = ScaledBitmapPainter(solid(400, 600), memoryCache = null, memoryCacheKey = null, downscaler = h.downscaler)
        painter.drawAt(100, 150)
        advanceUntilIdle()

        assertEquals(1, h.scaled.size)
        assertEquals(Color.Red, painter.drawAt(100, 150).centre())
    }
}
