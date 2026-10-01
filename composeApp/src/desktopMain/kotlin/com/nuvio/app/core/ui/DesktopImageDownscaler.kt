package com.nuvio.app.core.ui

import coil3.BitmapImage
import coil3.asImage
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.MipmapMode

/**
 * Does the high-quality poster downscale that [NuvioAsyncImage] needs on Windows, off the UI thread.
 *
 * Posters are decoded at up to 1536 px and shrunk to their card with a mipmapped Skia scale, because
 * Coil's own downsampling is nearest-neighbour and aliases badly. That scale used to run inside the
 * painter's `onDraw`: 5-14 ms of UI-thread work per card scrolling into view.
 *
 * Results are stored in Coil's memory cache under the source image's key plus the target size, so a
 * card that scrolls back into view, or the same poster in another row, draws at once and the scaled
 * copies share Coil's existing budget instead of a cache of their own. Identical requests share one
 * job. Jobs run newest first, and a job whose painters have all stopped drawing is skipped rather
 * than scaled, so a fast scroll does not leave a backlog of posters nobody is looking at.
 */
internal class DesktopImageDownscaler(
    private val scope: CoroutineScope,
    private val maxWorkers: Int,
    private val scale: (source: Bitmap, width: Int, height: Int) -> Bitmap?,
) {
    /** A painter waiting for a scaled bitmap. Both callbacks arrive on a worker thread. */
    interface Waiter {
        /** False lets a job that is still queued be skipped: nobody is drawing this size any more. */
        fun wantsScaled(width: Int, height: Int): Boolean

        /** [bitmap] is null when the job was skipped ([failed] false) or the scale failed. */
        fun onScaled(width: Int, height: Int, bitmap: Bitmap?, failed: Boolean)
    }

    private class Job(
        val key: Any,
        val source: Bitmap,
        val width: Int,
        val height: Int,
        val memoryCache: MemoryCache?,
        val cacheKey: MemoryCache.Key?,
    ) {
        val waiters = ArrayList<Waiter>(1)
    }

    /** Dedupes sources Coil did not memory-cache; Skia bitmaps have no value equality. */
    private class IdentityKey(val source: Bitmap, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean =
            other is IdentityKey && other.source === source && other.width == width && other.height == height

        override fun hashCode(): Int = (System.identityHashCode(source) * 31 + width) * 31 + height
    }

    private val lock = Any()
    private val jobs = HashMap<Any, Job>()
    private val queue = ArrayDeque<Job>()
    private var workers = 0

    /** A finished scale of the source cached under [sourceKey], or null. Cheap enough to call from draw. */
    fun cached(memoryCache: MemoryCache?, sourceKey: MemoryCache.Key?, width: Int, height: Int): Bitmap? {
        if (memoryCache == null || sourceKey == null) return null
        return (memoryCache[scaledKey(sourceKey, width, height)]?.image as? BitmapImage)?.bitmap
    }

    fun request(
        source: Bitmap,
        width: Int,
        height: Int,
        memoryCache: MemoryCache?,
        sourceKey: MemoryCache.Key?,
        waiter: Waiter,
    ) {
        val cacheKey = if (memoryCache != null && sourceKey != null) scaledKey(sourceKey, width, height) else null
        val key: Any = cacheKey ?: IdentityKey(source, width, height)
        synchronized(lock) {
            val existing = jobs[key]
            if (existing != null) {
                if (existing.waiters.none { it === waiter }) existing.waiters += waiter
                // Asked for again: it is on screen now, so it goes back to the front.
                if (queue.remove(existing)) queue.addFirst(existing)
                return
            }
            val job = Job(key, source, width, height, memoryCache, cacheKey)
            job.waiters += waiter
            jobs[key] = job
            queue.addFirst(job)
            if (workers < maxWorkers) {
                workers++
                scope.launch { drain() }
            }
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                queue.removeFirstOrNull() ?: run {
                    workers--
                    null
                }
            } ?: return
            val wanted = synchronized(lock) { job.waiters.toList() }
                .any { it.wantsScaled(job.width, job.height) }
            val bitmap = if (wanted) runCatching { scale(job.source, job.width, job.height) }.getOrNull() else null
            if (bitmap != null && job.cacheKey != null) {
                job.memoryCache?.set(job.cacheKey, MemoryCache.Value(bitmap.asImage()))
            }
            val waiters = synchronized(lock) {
                jobs.remove(job.key)
                job.waiters.toList()
            }
            waiters.forEach { it.onScaled(job.width, job.height, bitmap, failed = wanted && bitmap == null) }
        }
    }

    companion object {
        const val ScaledSizeExtra = "nuvio#desktopScaledSize"

        val Shared = DesktopImageDownscaler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxWorkers = 2,
            scale = ::scaleHighQuality,
        )

        fun scaledKey(sourceKey: MemoryCache.Key, width: Int, height: Int): MemoryCache.Key =
            MemoryCache.Key(sourceKey.key, sourceKey.extras + (ScaledSizeExtra to "${width}x$height"))
    }
}

/** Upstream's mipmapped downscale, unchanged except that the result is immutable. */
internal fun scaleHighQuality(source: Bitmap, width: Int, height: Int): Bitmap? {
    val image = SkiaImage.makeFromBitmap(source)
    try {
        val bitmap = Bitmap()
        if (!bitmap.allocN32Pixels(width, height)) {
            bitmap.close()
            return null
        }
        val scaled = bitmap.peekPixels()?.use { pixels ->
            image.scalePixels(pixels, FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), false)
        } ?: false
        if (!scaled) {
            bitmap.close()
            return null
        }
        // Immutable, so drawing it wraps the pixels instead of copying them every frame.
        bitmap.setImmutable()
        return bitmap
    } finally {
        image.close()
    }
}
