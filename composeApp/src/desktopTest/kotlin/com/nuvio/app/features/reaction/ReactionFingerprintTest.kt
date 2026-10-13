package com.nuvio.app.features.reaction

import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionFingerprintTest {
    /** A film-like picture: soft shapes over a gradient, no two alike. */
    private fun picture(w: Int, h: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val blobs = List(7) { doubleArrayOf(r.nextDouble(w.toDouble()), r.nextDouble(h.toDouble()), r.nextDouble(6.0, 24.0), r.nextDouble(-90.0, 90.0)) }
        val gx = r.nextDouble(-1.0, 1.0)
        return ByteArray(w * h) { i ->
            val x = (i % w).toDouble()
            val y = (i / w).toDouble()
            var v = 110 + gx * (x - w / 2) * 0.6 + 12 * sin(y / 7.0 + seed)
            for ((bx, by, radius, amp) in blobs) {
                val d2 = ((x - bx) * (x - bx) + (y - by) * (y - by)) / (radius * radius)
                if (d2 < 9) v += amp * kotlin.math.exp(-d2)
            }
            v.toInt().coerceIn(0, 255).toByte()
        }
    }

    /** [src] shown inside a reaction: scaled into [box], letterboxed to 16:9, brightness/contrast changed. */
    private fun inReaction(src: ByteArray, sw: Int, sh: Int, fw: Int, fh: Int, box: PixelRect, bar: Int, gain: Double, lift: Int): ByteArray {
        val frame = ByteArray(fw * fh) { 70 }
        val picTop = box.y0 + bar
        val picH = box.height - 2 * bar
        for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
            val inPicture = y in picTop until picTop + picH
            frame[y * fw + x] = if (!inPicture) 4 else {
                val sx = ((x - box.x0 + 0.5) * sw / box.width).toInt().coerceIn(0, sw - 1)
                val sy = ((y - picTop + 0.5) * sh / picH).toInt().coerceIn(0, sh - 1)
                ((src[sy * sw + sx].toInt() and 0xFF) * gain + lift).toInt().coerceIn(0, 255).toByte()
            }
        }
        return frame
    }

    @Test
    fun theSameShotHashesAlikeInsideAReaction() {
        val sw = 128
        val sh = 54 // 2.37:1, like a film with no bars in the file
        val box = PixelRect(60, 30, 180, 98)
        for (seed in 1..6) {
            val episode = picture(sw, sh, seed)
            val mine = ReactionFingerprinter.of(episode, sw, PixelRect(0, 0, sw, sh))
            val frame = inReaction(episode, sw, sh, 240, 136, box, bar = 9, gain = 1.15, lift = 12)
            val letterbox = SlidingLetterbox(240, box)
            repeat(3) { letterbox.add(it, frame) }
            val area = letterbox.activeAt(1)
            assertTrue(area.y0 in 38..40 && area.y1 in 88..90, "bars found: $area")
            val theirs = ReactionFingerprinter.of(frame, 240, area)
            val d = ReactionFingerprinter.distance(mine.hash, theirs.hash)
            assertTrue(d <= 8, "seed $seed: distance $d")
        }
    }

    @Test
    fun barsAreJudgedFromNearbyFramesNotTheWholeHistory() {
        // The same box: first a full-height picture, then a letterboxed film. The film's bars must be
        // found although the earlier footage lit those rows.
        val box = PixelRect(60, 30, 180, 98)
        val letterbox = SlidingLetterbox(240, box)
        val full = picture(120, 68, 1)
        for (i in 0 until 200) {
            val frame = if (i < 100) {
                ByteArray(240 * 136) { j -> val x = j % 240; val y = j / 240; if (x in 60 until 180 && y in 30 until 98) full[(y - 30) * 120 + x - 60] else 70 }
            } else {
                inReaction(picture(128, 54, 2), 128, 54, 240, 136, box, bar = 9, gain = 1.0, lift = 0)
            }
            letterbox.add(i, frame)
        }
        assertEquals(30, letterbox.activeAt(50).y0)
        val film = letterbox.activeAt(160)
        assertTrue(film.y0 in 38..40 && film.y1 in 88..90, "film: $film")
    }

    @Test
    fun differentShotsHashApart() {
        val all = (1..12).map { ReactionFingerprinter.of(picture(128, 72, it), 128, PixelRect(0, 0, 128, 72)).hash }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val d = ReactionFingerprinter.distance(all[i], all[j])
            assertTrue(d > ReactionMatcher.MAX_DISTANCE, "pictures $i and $j only $d bits apart")
        }
    }

    @Test
    fun aMirroredShotMatchesThroughTheMirrorHash() {
        val w = 128
        val h = 72
        for (seed in 1..6) {
            val original = picture(w, h, seed)
            val flipped = ByteArray(w * h) { i -> original[(i / w) * w + (w - 1 - i % w)] }
            val a = ReactionFingerprinter.of(original, w, PixelRect(0, 0, w, h))
            val b = ReactionFingerprinter.of(flipped, w, PixelRect(0, 0, w, h))
            // Exact in theory; summation order can move a coefficient sitting on the median.
            assertTrue(ReactionFingerprinter.distance(a.hash, b.mirrorHash) <= 2, "seed $seed")
            assertTrue(ReactionFingerprinter.distance(a.hash, b.hash) > ReactionMatcher.MAX_DISTANCE, "seed $seed: plain hashes should differ")
        }
    }

    @Test
    fun blackAndFlatFramesAreNotInformative() {
        val black = ByteArray(128 * 72) { 3 }
        val noisy = Random(3).let { r -> ByteArray(128 * 72) { (2 + r.nextInt(4)).toByte() } }
        assertTrue(!ReactionFingerprinter.of(black, 128, PixelRect(0, 0, 128, 72)).informative)
        assertTrue(!ReactionFingerprinter.of(noisy, 128, PixelRect(0, 0, 128, 72)).informative)
        assertTrue(ReactionFingerprinter.of(picture(128, 72, 4), 128, PixelRect(0, 0, 128, 72)).informative)
    }

    @Test
    fun missingRangesAndTheCacheFormatRoundTrip() {
        val track = FingerprintTrack()
        for (i in 10 until 20) track.set(i, FrameFingerprint(i.toLong(), -i.toLong(), 20f))
        for (i in 30 until 35) track.set(i, FrameFingerprint(i.toLong(), 0, 2f))
        assertEquals(0 until 10, track.missingRange(0, 40))
        assertEquals(20 until 30, track.missingRange(10, 40))
        assertEquals(35 until 40, track.missingRange(30, 40))
        assertEquals(null, track.missingRange(10, 20))
        val bytes = java.io.ByteArrayOutputStream()
        track.write(java.io.DataOutputStream(bytes))
        val copy = FingerprintTrack.read(java.io.DataInputStream(bytes.toByteArray().inputStream()))
        assertEquals(15, copy.count)
        assertEquals(-12L, copy.mirror(12))
        assertTrue(copy.informative(15) && !copy.informative(31) && !copy.has(25))
    }
}
