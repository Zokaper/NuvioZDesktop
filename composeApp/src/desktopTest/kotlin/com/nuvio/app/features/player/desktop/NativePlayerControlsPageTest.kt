package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }
}
