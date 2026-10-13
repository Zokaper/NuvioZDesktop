package com.nuvio.app.features.reaction

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rectangle in whole pixels of an analysis frame: [x0, x1) x [y0, y1). */
internal data class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0

    companion object {
        fun of(rect: LayoutRect, width: Int, height: Int) = PixelRect(
            (rect.x * width).roundToInt().coerceIn(0, width - 1),
            (rect.y * height).roundToInt().coerceIn(0, height - 1),
            (rect.right * width).roundToInt().coerceIn(1, width),
            (rect.bottom * height).roundToInt().coerceIn(1, height),
        )
    }
}

/**
 * One sampled frame of the episode, or of the movie inside a reaction (Stage 4).
 *
 * [hash] is a 63-bit DCT perceptual hash: the picture squeezed to 32x32, its 8x8 lowest frequencies
 * (DC dropped) thresholded at their median. Squeezing whatever rectangle the picture occupies to the
 * same 32x32 makes it indifferent to resolution, aspect and placement; dropping DC and thresholding at
 * the median makes it indifferent to brightness and contrast, which reaction channels change.
 * [mirrorHash] is the hash of the picture flipped left-right (uploads mirror the movie to dodge
 * matching), derived from the same coefficients: flipping negates the odd horizontal frequencies.
 * [detail] is the picture's standard deviation; below [ReactionFingerprinter.MIN_DETAIL] the frame
 * (black, a fade, a flat title card) looks like every other such frame and is not matched.
 */
internal data class FrameFingerprint(val hash: Long, val mirrorHash: Long, val detail: Float) {
    val informative get() = detail >= ReactionFingerprinter.MIN_DETAIL
}

internal object ReactionFingerprinter {
    /** Fingerprints are taken at this rate on both sides, on the grid k * 125 ms. */
    const val FPS = 8
    const val FRAME_MS = 1000L / FPS
    const val MIN_DETAIL = 8f
    private const val N = 32
    private const val K = 8

    private val cosines = Array(K) { u -> DoubleArray(N) { x -> cos(PI * (2 * x + 1) * u / (2 * N)) } }

    fun of(frame: ByteArray, width: Int, region: PixelRect): FrameFingerprint {
        val small = resample(frame, width, region)
        var sum = 0.0
        var sumSq = 0.0
        for (v in small) { sum += v; sumSq += v * v }
        val mean = sum / small.size
        val detail = sqrt(max(sumSq / small.size - mean * mean, 0.0)).toFloat()

        // Only the K x K lowest frequencies are needed: rows first, then columns.
        val rows = Array(N) { DoubleArray(K) }
        for (y in 0 until N) for (u in 0 until K) {
            var s = 0.0
            val c = cosines[u]
            for (x in 0 until N) s += small[y * N + x] * c[x]
            rows[y][u] = s
        }
        val coef = DoubleArray(K * K)
        for (v in 0 until K) for (u in 0 until K) {
            var s = 0.0
            val c = cosines[v]
            for (y in 0 until N) s += rows[y][u] * c[y]
            coef[v * K + u] = s
        }
        val plain = DoubleArray(K * K - 1) { coef[it + 1] }
        val mirrored = DoubleArray(K * K - 1) { i -> val idx = i + 1; if ((idx % K) % 2 == 1) -coef[idx] else coef[idx] }
        return FrameFingerprint(bits(plain), bits(mirrored), detail)
    }

    private fun bits(values: DoubleArray): Long {
        val median = values.sortedArray().let { (it[it.size / 2] + it[(it.size - 1) / 2]) / 2 }
        var out = 0L
        for (i in values.indices) if (values[i] > median) out = out or (1L shl i)
        return out
    }

    /** Box-averages [region] to N x N, each output cell weighted by how much of each pixel it covers. */
    private fun resample(frame: ByteArray, width: Int, region: PixelRect): DoubleArray {
        val wx = weights(region.x0, region.width)
        val wy = weights(region.y0, region.height)
        val out = DoubleArray(N * N)
        val rowBuffer = DoubleArray(N)
        for (oy in 0 until N) {
            rowBuffer.fill(0.0)
            for ((sy, weightY) in wy[oy]) {
                val base = sy * width
                for (ox in 0 until N) {
                    var s = 0.0
                    for ((sx, weightX) in wx[ox]) s += (frame[base + sx].toInt() and 0xFF) * weightX
                    rowBuffer[ox] += s * weightY
                }
            }
            for (ox in 0 until N) out[oy * N + ox] = rowBuffer[ox]
        }
        return out
    }

    private fun weights(start: Int, length: Int): Array<List<Pair<Int, Double>>> {
        val scale = length.toDouble() / N
        return Array(N) { i ->
            val a = i * scale
            val b = (i + 1) * scale
            val parts = ArrayList<Pair<Int, Double>>()
            for (s in floor(a).toInt() until ceil(b).toInt()) {
                val covered = min(b, s + 1.0) - max(a, s.toDouble())
                if (covered > 0) parts += (start + min(s, length - 1)) to covered
            }
            val total = parts.sumOf { it.second }
            parts.map { it.first to it.second / total }
        }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

/**
 * Finds the picture inside a region that may also hold letterbox or pillarbox bars: the rows and
 * columns that stay dark in every frame within [reach] frames either side. A 2.39:1 film shown in a
 * 16:9 box has bars the episode file does not, and both sides must hash the same picture.
 *
 * The evidence is local in time because bars belong to the content, not to the box: a compilation's
 * intro showed other footage, without bars, in the very box the film later played in with them, and
 * one verdict for the box hashed the film with its bars (a fifth of it went unmatched). Through a
 * stretch too dark to tell, the last confident verdict stands. A sliver of something else at the
 * box's very edge (the detector is a pixel or two off) does not hide bars: the search starts inside.
 */
internal class SlidingLetterbox(private val width: Int, private val region: PixelRect, private val reach: Int = REACH) {
    private val rows = java.util.TreeMap<Int, FloatArray>()
    private val cols = java.util.TreeMap<Int, FloatArray>()
    private var lastConfident: PixelRect? = null

    fun add(index: Int, frame: ByteArray) {
        val rowMeans = FloatArray(region.height)
        val colSums = FloatArray(region.width)
        for (y in region.y0 until region.y1) {
            var rowSum = 0f
            val base = y * width
            for (x in region.x0 until region.x1) {
                val v = (frame[base + x].toInt() and 0xFF).toFloat()
                rowSum += v
                colSums[x - region.x0] += v
            }
            rowMeans[y - region.y0] = rowSum / region.width
        }
        for (x in colSums.indices) colSums[x] /= region.height
        rows[index] = rowMeans
        cols[index] = colSums
    }

    fun activeAt(index: Int): PixelRect {
        val rowMax = FloatArray(region.height)
        val colMax = FloatArray(region.width)
        for (profile in rows.subMap(index - reach, true, index + reach, true).values) {
            for (i in profile.indices) if (profile[i] > rowMax[i]) rowMax[i] = profile[i]
        }
        for (profile in cols.subMap(index - reach, true, index + reach, true).values) {
            for (i in profile.indices) if (profile[i] > colMax[i]) colMax[i] = profile[i]
        }
        if ((rowMax.maxOrNull() ?: 0f) < CONFIDENT_LEVEL) return lastConfident ?: region
        val (r0, r1) = trim(rowMax)
        val (c0, c1) = trim(colMax)
        return PixelRect(region.x0 + c0, region.y0 + r0, region.x0 + c1, region.y0 + r1).also { lastConfident = it }
    }

    fun forgetBefore(index: Int) {
        rows.headMap(index).clear()
        cols.headMap(index).clear()
    }

    private fun trim(profile: FloatArray): Pair<Int, Int> {
        val n = profile.size
        val margin = max(1, (n * 0.04).roundToInt())
        var a = margin
        var b = n - margin
        while (a < n * 0.3 && profile[a] < BAR_LEVEL) a++
        while (b > n * 0.7 && profile[b - 1] < BAR_LEVEL) b--
        if (a == margin) a = 0
        if (b == n - margin) b = n
        return a to b
    }

    companion object {
        /** +-5 s at 8 fps. */
        const val REACH = 40
        private const val BAR_LEVEL = 28f
        private const val CONFIDENT_LEVEL = 80f
    }
}

/**
 * Fingerprints frames once each has [SlidingLetterbox.REACH] frames of context after it (or the
 * video ended), each inside its own region.
 */
internal class SlidingFingerprinter(private val width: Int, private val track: FingerprintTrack) {
    private val placed = ArrayDeque<Triple<Int, ByteArray, PixelRect>>()
    private val letterboxes = HashMap<PixelRect, SlidingLetterbox>()

    fun add(index: Int, frame: ByteArray, region: PixelRect) {
        letterboxes.getOrPut(region) { SlidingLetterbox(width, region) }.add(index, frame)
        placed.addLast(Triple(index, frame, region))
        drain(all = false)
    }

    fun finish() = drain(all = true)

    private fun drain(all: Boolean) {
        val last = placed.lastOrNull()?.first ?: return
        while (placed.isNotEmpty() && (all || placed.first().first <= last - SlidingLetterbox.REACH)) {
            val (index, frame, region) = placed.removeFirst()
            val letterbox = letterboxes.getValue(region)
            val area = letterbox.activeAt(index)
            letterbox.forgetBefore(index - SlidingLetterbox.REACH)
            if (area.width >= 8 && area.height >= 8) track.set(index, ReactionFingerprinter.of(frame, width, area))
        }
    }
}

/**
 * Fingerprints of one video on the global grid (index k = k * 125 ms). Filled out of order: analysis
 * starts wherever playback is, and later runs fill what was skipped.
 */
internal class FingerprintTrack(capacity: Int = 0) {
    private var hashes = LongArray(capacity)
    private var mirrors = LongArray(capacity)
    private var details = FloatArray(capacity)
    private var present = BooleanArray(capacity)

    /** One past the highest index ever set. */
    var size = 0
        private set
    var count = 0
        private set

    @Synchronized
    fun set(index: Int, fingerprint: FrameFingerprint) {
        ensure(index + 1)
        if (!present[index]) count++
        hashes[index] = fingerprint.hash
        mirrors[index] = fingerprint.mirrorHash
        details[index] = fingerprint.detail
        present[index] = true
        if (index >= size) size = index + 1
    }

    fun has(index: Int) = index in 0 until size && present[index]
    fun hash(index: Int) = hashes[index]
    fun mirror(index: Int) = mirrors[index]
    fun detail(index: Int) = details[index]
    fun informative(index: Int) = has(index) && details[index] >= ReactionFingerprinter.MIN_DETAIL

    /** Indexes that are set, in order. */
    @Synchronized
    fun indices(): IntArray = (0 until size).filter { present[it] }.toIntArray()

    @Synchronized
    fun covers(fromIndex: Int, toIndex: Int): Boolean = (fromIndex until min(toIndex, size)).all { present[it] } && toIndex <= size

    /** The first run of unset indexes in [from, until), or null when all of it is set. */
    @Synchronized
    fun missingRange(from: Int, until: Int): IntRange? {
        var a = max(0, from)
        while (a < until && a < size && present[a]) a++
        if (a >= until) return null
        var b = a
        while (b < until && (b >= size || !present[b])) b++
        return a until b
    }

    /** A copy that analysis threads cannot change while the matcher reads it. */
    @Synchronized
    fun snapshot(): FingerprintTrack = FingerprintTrack(0).also {
        it.hashes = hashes.copyOf(size)
        it.mirrors = mirrors.copyOf(size)
        it.details = details.copyOf(size)
        it.present = present.copyOf(size)
        it.size = size
        it.count = count
    }

    private fun ensure(capacity: Int) {
        if (capacity <= hashes.size) return
        val next = max(capacity, hashes.size * 2 + 64)
        hashes = hashes.copyOf(next)
        mirrors = mirrors.copyOf(next)
        details = details.copyOf(next)
        present = present.copyOf(next)
    }

    /** Compact binary form for the on-disk cache: count, then (index, hash, mirror, detail) per frame. */
    @Synchronized
    fun write(out: java.io.DataOutputStream) {
        out.writeInt(count)
        for (i in 0 until size) if (present[i]) {
            out.writeInt(i)
            out.writeLong(hashes[i])
            out.writeLong(mirrors[i])
            out.writeFloat(details[i])
        }
    }

    companion object {
        fun read(input: java.io.DataInputStream): FingerprintTrack {
            val n = input.readInt()
            val track = FingerprintTrack(n)
            repeat(n) {
                val index = input.readInt()
                track.set(index, FrameFingerprint(input.readLong(), input.readLong(), input.readFloat()))
            }
            return track
        }
    }
}
