package com.nuvio.app.features.reaction

import kotlinx.serialization.json.JsonPrimitive

/**
 * The Reaction Mode button and panel are injected into the player's controls page at runtime
 * (resources/reaction-ui), so the upstream-owned controls.html/js/css carry no Reaction Mode code.
 */
internal object ReactionControlsUi {
    private val script: String by lazy {
        val js = resource("/reaction-ui/reaction.js")
        val css = resource("/reaction-ui/reaction.css")
        js.replace("__REACTION_CSS__", JsonPrimitive(css).toString())
    }

    fun installScript(): String = script

    private fun resource(path: String): String =
        ReactionControlsUi::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing resource $path")
}
