package com.nuvio.app.features.reaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/**
 * A layout timeline a real session saved (`reaction/layouts/<key>.json`, any version) steadied again
 * with today's rules, reactors per epoch reported and the result written next to it as
 * `<name>-steady.json` for drawing over frames. Skipped unless pointed at one:
 *
 *   NUVIO_REACTION_LAYOUT_REPLAY=<layout json>
 *   ./gradlew :composeApp:desktopTest --tests "*ReactionLayoutReplayTest*" --rerun
 */
class ReactionLayoutReplayTest {
    @Test
    fun steadyASavedLayout() {
        val file = File(System.getenv("NUVIO_REACTION_LAYOUT_REPLAY") ?: return)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val epochs = root.getValue("epochs").jsonArray.map { e ->
            val o = e.jsonObject
            LayoutEpoch(
                o.getValue("startMs").jsonPrimitive.long,
                o.getValue("endMs").jsonPrimitive.long,
                DetectedLayout(
                    o.getValue("tiles").jsonArray.map { t ->
                        val p = t.jsonObject
                        DetectedTile(
                            LayoutRect(p.getValue("x").jsonPrimitive.double, p.getValue("y").jsonPrimitive.double, p.getValue("w").jsonPrimitive.double, p.getValue("h").jsonPrimitive.double),
                            TileKind.valueOf(p.getValue("kind").jsonPrimitive.content),
                        )
                    },
                ),
            )
        }
        val before = epochs.map { it.layout.reactors.size }
        val started = System.currentTimeMillis()
        val steady = ReactionLayoutTimeline(epochs).steadied()
        val after = steady.epochs.map { it.layout.reactors.size }
        println("steadied ${epochs.size} epochs in ${System.currentTimeMillis() - started} ms")
        println("reactors per epoch before: min ${before.min()} median ${before.sorted()[before.size / 2]} max ${before.max()}, ${before.toSet().size} different counts")
        println("reactors per epoch after:  min ${after.min()} median ${after.sorted()[after.size / 2]} max ${after.max()}, distinct sets ${steady.epochs.map { it.layout.reactors }.toSet().size}")
        val out = buildJsonObject {
            put(
                "epochs",
                buildJsonArray {
                    steady.epochs.forEach { e ->
                        add(
                            buildJsonObject {
                                put("startMs", e.startMs)
                                put("endMs", e.endMs)
                                put(
                                    "tiles",
                                    buildJsonArray {
                                        e.layout.tiles.forEach { t ->
                                            add(buildJsonObject { put("x", t.rect.x); put("y", t.rect.y); put("w", t.rect.w); put("h", t.rect.h); put("kind", t.kind.name) })
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        File(file.parentFile, file.nameWithoutExtension + "-steady.json").writeText(out.toString())
    }
}
