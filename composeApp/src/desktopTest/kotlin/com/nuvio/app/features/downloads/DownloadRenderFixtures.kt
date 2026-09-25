package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.Image
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.random.Random

/**
 * Artwork for the Downloads render harnesses, so a review looks at a media app and not at grey
 * boxes. Each title gets a generated poster (2:3) and still (16:9) in its own palette, with the
 * title set in type the way a key-art poster is. Nothing is fetched: [WithFixtureArt] answers
 * every `fixture://` image through Coil's preview handler, so the production `AsyncImage` draws
 * it on the first frame.
 */
internal object DownloadRenderArt {
    private class Look(val top: Int, val bottom: Int, val glow: Int, val skyline: Boolean)

    private val looks = mapOf(
        "severance" to Look(0xFF0E2A33.toInt(), 0xFF03090C.toInt(), 0xFF7FD1C7.toInt(), skyline = true),
        "the-bear" to Look(0xFF3A1F0E.toInt(), 0xFF0D0705.toInt(), 0xFFF2A541.toInt(), skyline = false),
        "dune-part-two" to Look(0xFFB8672C.toInt(), 0xFF2A1106.toInt(), 0xFFFFD08A.toInt(), skyline = true),
        "oppenheimer" to Look(0xFF4A1405.toInt(), 0xFF080302.toInt(), 0xFFFF7A2F.toInt(), skyline = false),
        "past-lives" to Look(0xFF28405E.toInt(), 0xFF0A1220.toInt(), 0xFFE9B8A6.toInt(), skyline = true),
        "shogun" to Look(0xFF2B0B0B.toInt(), 0xFF070202.toInt(), 0xFFD8352A.toInt(), skyline = false),
        "modern-family" to Look(0xFF2E6FA8.toInt(), 0xFF0F2438.toInt(), 0xFFFFE08A.toInt(), skyline = true),
        "slow-horses" to Look(0xFF2D3320.toInt(), 0xFF0B0C07.toInt(), 0xFFC9C27A.toInt(), skyline = true),
        "everything-everywhere" to Look(0xFF4B1D5E.toInt(), 0xFF0E0616.toInt(), 0xFF5FE3F0.toInt(), skyline = false),
    )
    private val names = mapOf(
        "severance" to "SEVERANCE",
        "the-bear" to "THE BEAR",
        "dune-part-two" to "DUNE",
        "oppenheimer" to "OPPENHEIMER",
        "past-lives" to "PAST LIVES",
        "shogun" to "SHOGUN",
        "modern-family" to "MODERN FAMILY",
        "slow-horses" to "SLOW HORSES",
        "everything-everywhere" to "EVERYTHING",
    )

    fun poster(slug: String) = "fixture://poster/$slug"
    fun backdrop(slug: String) = "fixture://backdrop/$slug"
    fun still(slug: String, episode: Int) = "fixture://still/$slug/$episode"

    private val cache = mutableMapOf<String, Image>()

    val blank: Image by lazy { Bitmap().apply { allocN32Pixels(2, 3); erase(0xFF222222.toInt()) }.asImage() }

    fun image(url: String): Image? {
        if (!url.startsWith("fixture://")) return null
        return cache.getOrPut(url) { draw(url).asImage() }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun lerp(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    private fun draw(url: String): Bitmap {
        val parts = url.removePrefix("fixture://").split('/')
        val kind = parts[0]
        val slug = parts.getOrElse(1) { "" }
        val variant = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val look = looks[slug] ?: Look(0xFF333333.toInt(), 0xFF111111.toInt(), 0xFF999999.toInt(), skyline = false)
        val (w, h) = if (kind == "poster") 300 to 450 else 480 to 270
        val bitmap = Bitmap().apply { allocN32Pixels(w, h) }
        val canvas = Canvas(bitmap)
        val random = Random(slug.hashCode() * 31 + variant)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // Sky: the title's two colours, top to bottom.
        val bands = 96
        for (i in 0 until bands) {
            val y0 = hf * i / bands
            canvas.drawRect(Rect.makeLTRB(0f, y0, wf, y0 + hf / bands + 1f), Paint().apply { color = mix(look.top, look.bottom, i / (bands - 1f)) })
        }
        // A soft light source, placed differently per still.
        val glowX = wf * (0.25f + random.nextFloat() * 0.5f)
        val glowY = hf * (if (kind == "poster") 0.28f else 0.35f + random.nextFloat() * 0.2f)
        canvas.drawCircle(
            glowX, glowY, wf * 0.32f,
            Paint().apply {
                color = (look.glow and 0x00FFFFFF) or (0x88 shl 24)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, wf * 0.12f)
            },
        )
        canvas.drawCircle(glowX, glowY, wf * 0.07f, Paint().apply { color = look.glow; maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 4f) })
        // Ground: a skyline or a line of hills, in near-black.
        val base = hf * (if (kind == "poster") 0.62f else 0.66f)
        val groundPaint = Paint().apply { color = 0xF0050505.toInt() }
        canvas.drawRect(Rect.makeLTRB(0f, base, wf, hf), groundPaint)
        var x = 0f
        while (x < wf) {
            val step = wf * (0.04f + random.nextFloat() * 0.08f)
            if (look.skyline) {
                val top = base - hf * random.nextFloat() * 0.22f
                canvas.drawRect(Rect.makeLTRB(x, top, x + step - 1f, base + 1f), groundPaint)
            } else {
                val rise = hf * (0.02f + random.nextFloat() * 0.07f)
                canvas.drawOval(Rect.makeLTRB(x - step, base - rise, x + step * 1.5f, base + rise), groundPaint)
            }
            x += step
        }
        // A figure, small against the scene.
        if (kind != "backdrop") {
            val fx = wf * (0.3f + random.nextFloat() * 0.4f)
            val fy = hf * (if (kind == "poster") 0.62f else 0.66f)
            val s = hf * 0.05f
            val figure = Paint().apply { color = 0xFF020202.toInt() }
            canvas.drawCircle(fx, fy - s * 2.1f, s * 0.45f, figure)
            canvas.drawRRect(org.jetbrains.skia.RRect.makeXYWH(fx - s * 0.5f, fy - s * 1.6f, s, s * 1.7f, s * 0.3f), figure)
        }
        // Vignette.
        val edge = Paint().apply {
            color = 0x99000000.toInt()
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, wf * 0.08f)
        }
        canvas.drawRect(Rect.makeLTRB(-wf * 0.2f, -hf * 0.2f, wf * 0.06f, hf * 1.2f), edge)
        canvas.drawRect(Rect.makeLTRB(wf * 0.94f, -hf * 0.2f, wf * 1.2f, hf * 1.2f), edge)
        canvas.drawRect(Rect.makeLTRB(-wf * 0.2f, hf * 0.9f, wf * 1.2f, hf * 1.2f), edge)
        // The title, set like key art.
        if (kind == "poster") {
            val name = names[slug] ?: slug.uppercase()
            val typeface = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD)
                ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
            var size = 46f
            var font = Font(typeface, size)
            while (font.measureTextWidth(name) > wf * 0.84f && size > 14f) {
                size -= 2f
                font = Font(typeface, size)
            }
            val textWidth = font.measureTextWidth(name)
            canvas.drawString(name, (wf - textWidth) / 2f, hf * 0.86f, font, Paint().apply { color = Color.WHITE })
            val small = Font(typeface, 11f)
            val tag = "NOW STREAMING"
            canvas.drawString(tag, (wf - small.measureTextWidth(tag)) / 2f, hf * 0.93f, small, Paint().apply { color = 0xB3FFFFFF.toInt() })
        }
        return bitmap
    }
}

/** Serves [DownloadRenderArt] to every `AsyncImage` below it, synchronously, as a preview does. */
@Composable
internal fun WithFixtureArt(content: @Composable () -> Unit) {
    val handler = AsyncImagePreviewHandler { request -> DownloadRenderArt.image(request.data.toString()) ?: DownloadRenderArt.blank }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides handler,
    ) { content() }
}
