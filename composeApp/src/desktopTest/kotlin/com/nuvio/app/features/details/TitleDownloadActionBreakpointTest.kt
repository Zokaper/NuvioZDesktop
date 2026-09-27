package com.nuvio.app.features.details

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.promo.PromoRenderHarness
import com.nuvio.app.promo.VirtualClockDispatcher
import com.nuvio.app.promo.WithPromoArt
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The title-level Download is on the details hero at **every** width (wizard polish, 2026-09-27).
 *
 * The details screen switches hero layout at 1000 dp on desktop (`useDesktopDetailLayout`): below
 * it the stacked `ACTIONS` section, above it `DesktopDetailHero`, which owns that section. Download
 * was only ever added to the first, so a wide window lost it - leaving only the season row's
 * download, which is a narrower action. This renders the **production** `MetaDetailsScreen` for a
 * show and a film at a phone, a medium and a wide width, opens the action row the way a user does
 * ("More actions"), and asserts the same Download entry is there each time. It also writes the
 * PNGs to `build/title-download-render/`, for looking at.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TitleDownloadActionBreakpointTest {

    private val outputDir = File("build/title-download-render").also { it.mkdirs() }

    /** Phone, medium (a narrow desktop window or a tablet) and wide (the default desktop window). */
    private val widths = listOf(400 to 860, 820 to 900, 1280 to 820)

    private val show = MetaDetails(
        id = "nz-test-show",
        type = "series",
        name = "Lanterns",
        description = "Two lantern-bearers patrol a city that only exists at night.",
        releaseInfo = "2025",
        genres = listOf("Drama"),
        videos = (1..3).map { episode ->
            MetaVideo(id = "nz-test-show:1:$episode", title = "Episode $episode", season = 1, episode = episode)
        },
    )

    private val film = MetaDetails(
        id = "nz-test-film",
        type = "movie",
        name = "Sintel",
        description = "A young woman crosses a frozen world to find the dragon she once saved.",
        releaseInfo = "2010",
        runtime = "15 min",
        genres = listOf("Animation"),
    )

    @Test
    fun aShowOffersTitleLevelDownloadAtEveryWidth() = assertDownloadAtEveryWidth(show, "Download seasons")

    @Test
    fun aFilmOffersTitleLevelDownloadAtEveryWidth() = assertDownloadAtEveryWidth(film, "Download")

    private fun assertDownloadAtEveryWidth(meta: MetaDetails, downloadLabel: String) {
        val missing = mutableListOf<String>()
        for ((widthDp, heightDp) in widths) {
            val found = renderAndFind(meta, widthDp, heightDp, downloadLabel)
            if (!found) missing += "${widthDp}x$heightDp"
        }
        assertTrue(missing.isEmpty(), "\"$downloadLabel\" missing from the ${meta.type} hero at: $missing")
    }

    private fun renderAndFind(meta: MetaDetails, widthDp: Int, heightDp: Int, downloadLabel: String): Boolean {
        PromoRenderHarness().seedMeta(meta)
        val clock = VirtualClockDispatcher()
        val scene = ImageComposeScene(
            width = widthDp,
            height = heightDp,
            density = Density(1f),
            coroutineContext = clock,
        ) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                WithPromoArt {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                        MetaDetailsScreen(type = meta.type, id = meta.id, onBack = {})
                    }
                }
            }
        }
        var t = 0L
        fun frames(count: Int) = repeat(count) {
            clock.advanceTo(t / 1_000_000L)
            scene.render(t)
            clock.pump()
            t += 16_666_667L
        }
        try {
            frames(60)
            val more = scene.nodeDescribed("More actions")
                ?: fail("no \"More actions\" control at ${widthDp}x$heightDp; saw ${scene.descriptions()}")
            scene.click(more.boundsInRoot.center) { frames(it) }
            frames(30)
            scene.render(t).encodeToData(EncodedImageFormat.PNG)?.let {
                File(outputDir, "${meta.type}-${widthDp}x$heightDp.png").writeBytes(it.bytes)
            }
            return scene.nodeDescribed(downloadLabel) != null
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.click(at: Offset, advance: (Int) -> Unit) {
        sendPointerEvent(PointerEventType.Move, at)
        advance(1)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        advance(1)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        advance(2)
    }

    private fun ImageComposeScene.allNodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.flatten() }

    private fun ImageComposeScene.descriptions(): List<String> =
        allNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun ImageComposeScene.nodeDescribed(text: String): SemanticsNode? = allNodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == text } == true
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
