package com.nuvio.app.features.reaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionShaderTest {
    @Test
    fun oneTileLinePerReactorWithNormalisedRects() {
        val shader = ReactionShader.build(
            listOf(
                ShaderTile(LayoutRect(0.0, 0.0, 0.25, 0.25), LayoutRect(0.0, 0.0, 0.5, 0.5)),
                ShaderTile(LayoutRect(0.75, 0.75, 0.25, 0.25), LayoutRect(0.5, 0.5, 0.5, 0.5)),
            ),
        )
        assertTrue(shader.startsWith("//!PARAM dim"))
        assertTrue(shader.contains("//!HOOK MAIN"))
        assertTrue(shader.contains("//!WIDTH OUTPUT.w"))
        assertTrue(shader.contains("TILE(0, vec4(0.000000, 0.000000, 0.500000, 0.500000), vec4(0.000000, 0.000000, 0.250000, 0.250000))"))
        assertTrue(shader.contains("TILE(1, vec4(0.500000, 0.500000, 1.000000, 1.000000), vec4(0.750000, 0.750000, 1.000000, 1.000000))"))
        // The macro continues across lines with a single backslash, which GLSL requires.
        assertTrue(shader.contains("{ \\\n"))
        assertTrue(!shader.contains("\\\\"))
    }

    @Test
    fun liveOptionsUseADotDecimalWhateverTheLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("dim=0.500,blackout=5", ReactionShader.options(0.5, 5))
            assertTrue(ReactionShader.build(listOf(ShaderTile(LayoutRect.FULL, LayoutRect.FULL))).contains("vec4(0.000000, 0.000000, 1.000000, 1.000000)"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
