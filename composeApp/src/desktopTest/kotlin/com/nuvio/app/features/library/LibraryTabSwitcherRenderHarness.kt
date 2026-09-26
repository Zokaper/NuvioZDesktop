package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.nuvio.app.LibrarySubDestination
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadsScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone Library page's two tabs, Library and Downloads (Phase 9). They are two screens that
 * each draw the same switcher, so switching swaps the whole screen: the switcher only looks
 * stationary if both screens put its chips in exactly the same place, selected or not.
 *
 * Renders the production [LibraryScreen] and [DownloadsScreen] with their switchers at two phone
 * widths into `composeApp/build/library-tabs-render/`, and asserts the chip bounds match to the
 * pixel. Before the fix the Downloads screen's chips sat 16dp further right (a second 16dp of
 * padding) and the unselected chip changed width with the label's weight.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*LibraryTabSwitcherRenderHarness"
 * ```
 */
class LibraryTabSwitcherRenderHarness {
    private val outputDir = File("build/library-tabs-render")
    private val widths = listOf(360, 420)
    private val labels = listOf("Library", "Downloads")

    @Composable
    private fun Tab(selected: LibrarySubDestination) {
        val switcher: @Composable () -> Unit = {
            LibraryTopSwitcher(selectedDestination = selected, onDestinationSelected = {})
        }
        when (selected) {
            LibrarySubDestination.Library -> LibraryScreen(modifier = Modifier.fillMaxSize(), topSwitcher = switcher)
            LibrarySubDestination.Downloads -> DownloadsScreen(onOpenDownload = {}, topSwitcher = switcher)
        }
    }

    @Test
    fun theTabsDoNotMoveWhenSwitching() {
        outputDir.mkdirs()
        val problems = mutableListOf<String>()
        for (width in widths) {
            val bounds = LibrarySubDestination.entries.associateWith { selected ->
                render("tabs-${selected.name.lowercase()}-$width", width, selected)
            }
            val library = bounds.getValue(LibrarySubDestination.Library)
            val downloads = bounds.getValue(LibrarySubDestination.Downloads)
            labels.forEach { label ->
                val a = library[label]
                val b = downloads[label]
                if (a == null || b == null) {
                    problems += "$width: no \"$label\" chip on the ${if (a == null) "Library" else "Downloads"} tab"
                    return@forEach
                }
                if (a != b) problems += "$width: \"$label\" moves when switching: Library tab $a, Downloads tab $b"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /** Renders one tab and returns each chip's bounds in pixels. */
    private fun render(name: String, widthDp: Int, selected: LibrarySubDestination): Map<String, Rect> {
        val density = Density(2f)
        val scene = ImageComposeScene(width = widthDp * 2, height = 300 * 2, density = density) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { Tab(selected) }
            }
        }
        try {
            scene.render(0L)
            scene.render(16_000_000L)
            val image = scene.render(600_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
            File(outputDir, "$name.png").writeBytes(data.bytes)
            val chips = scene.semanticsOwners
                .flatMap { it.rootSemanticsNode.flatten() }
                .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                .mapNotNull { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    labels.firstOrNull { it == text }?.let { it to node.boundsInRoot }
                }
                .toMap()
            assertTrue(chips.isNotEmpty(), "$name: no chips found")
            return chips
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
