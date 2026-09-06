package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerControlsPageTest {
    @Test
    fun everyElementRequestedByThePlayerScriptExistsInTheControlsPage() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val htmlIds = Regex("""\bid=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toSet()
        val requestedIds = Regex("""getElementById\(["']([^"']+)["']\)""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            expected = emptySet(),
            actual = requestedIds - htmlIds,
            message = "controls.js requests elements that controls.html does not provide",
        )
    }

    @Test
    fun controlsPageIdsAndScriptConstantsAreUnique() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val htmlIds = Regex("""\bid=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        val scriptConstants = Regex("""(?m)^const\s+([A-Za-z_$][\w$]*)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(emptySet(), htmlIds.duplicates(), "controls.html contains duplicate ids")
        assertEquals(emptySet(), scriptConstants.duplicates(), "controls.js redeclares top-level constants")
    }

    @Test
    fun nativeOpeningSurfaceCarriesThePhaseTwoLoadingBandWithoutLegacyMotion() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val css = resourceText("/player-ui/controls.css")

        listOf("openingFacts", "openingProvider", "openingRelease", "openingManualButton").forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing native loading-band element $id")
            assertTrue(script.contains("getElementById(\"$id\")"), "loading-band element $id is not wired")
        }
        assertTrue(script.contains("openingStageLabel"))
        assertTrue(script.contains("openingFacts"))
        assertTrue(script.contains("openingOffersManualEscape"))
        assertTrue(css.contains("opening-progress-sweep"))
        assertFalse(css.contains("animation: opening-logo-pulse"))
        assertFalse(css.contains("animation: opening-artwork-drift"))
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun List<String>.duplicates(): Set<String> =
        groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
