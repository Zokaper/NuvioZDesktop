package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Glyphs in small circles (avatar initials, the Inbox count) must sit on the circle's centre. A bare
 * `fontSize` used to inherit the surrounding style's line height, so the glyph drew low (maintainer,
 * 2026-10-18: "weird off center circles"). Each case is drawn white on a black circle on a black field,
 * and the ink's bounding box is measured against the circle's centre.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialCircleGlyphHarness" --rerun
 * # PNGs land in composeApp/build/social-circle-glyph/
 * ```
 */
class SocialCircleGlyphHarness {

    private val outputDir = File("build/social-circle-glyph")
    private val scale = 4

    @Test
    fun glyphsSitOnTheCircleCentre() {
        outputDir.mkdirs()
        val report = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val cases = buildList {
            for (size in listOf(18, 20, 24, 28, 36, 44, 62)) {
                for (glyph in listOf("D", "M", "1", "9+", "g")) {
                    add(Triple(size, glyph, (size * 0.42f).sp))
                }
            }
            add(Triple(18, "1", 10.sp))
            add(Triple(18, "9+", 10.sp))
        }
        for ((size, glyph, fontSize) in cases) {
            for (legacy in listOf(true, false)) {
                val name = "${if (legacy) "legacy" else "fixed"}-$size-${glyph.replace("+", "plus")}-${fontSize.value.toInt()}"
                val (dx, dy) = measure(name, size, glyph, fontSize, legacy)
                val line = "%-24s dx=%+.2fdp dy=%+.2fdp".format(name, dx, dy)
                report += line
                // A descender ("g") is not a centring target; caps and digits are. Only the vertical is
                // asserted: a "1"'s flag puts its ink box left of its advance, before and after alike.
                if (!legacy && glyph != "g" && abs(dy) > 0.6f) failures += line
            }
        }
        File(outputDir, "report.txt").writeText(report.joinToString("\n"))
        println(report.joinToString("\n"))
        if (failures.isNotEmpty()) fail("Off centre:\n" + failures.joinToString("\n"))
    }

    /** The ink box's centre minus the circle's centre, in dp (positive = right / low). */
    private fun measure(name: String, sizeDp: Int, glyph: String, fontSize: TextUnit, legacy: Boolean): Pair<Float, Float> {
        val field = sizeDp + 16
        val scene = ImageComposeScene(width = field * scale, height = field * scale, density = Density(scale.toFloat())) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                val d = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(d.density, 1f),
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                            if (legacy) LegacyGlyph(glyph, fontSize) else SocialCircleGlyph(glyph, fontSize, Color.White)
                        }
                    }
                }
            }
        }
        try {
            scene.render(0L)
            val image = scene.render(16_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
            File(outputDir, "$name.png").writeBytes(data.bytes)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(Image.makeFromEncoded(data.bytes))
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getColor(x, y)
                val lum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (lum > 3 * 128) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) error("$name: no ink")
            val centre = field * scale / 2f
            val dx = ((minX + maxX + 1) / 2f - centre) / scale
            val dy = ((minY + maxY + 1) / 2f - centre) / scale
            return dx to dy
        } finally {
            scene.close()
        }
    }

    /** What `SocialAvatar` and `SocialInboxButton` drew before. */
    @Composable
    private fun LegacyGlyph(glyph: String, fontSize: TextUnit) {
        Text(glyph, fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
