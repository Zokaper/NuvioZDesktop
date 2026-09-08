package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NativePlayerControlsJsonTest {
    @Test
    fun partyNotificationAndSessionPolicyStateReachTheNativeControlsPayload() {
        val payload = Json.parseToJsonElement(
            PlayerControlsState(
                partyPanelVisible = true,
                partyEndedChoiceVisible = true,
                socialNotificationVisible = true,
                presenceJoinPolicyVisible = true,
                presenceJoinPolicyLabel = "Join: Direct",
            ).toControlsJson(isFullscreen = false),
        ).jsonObject

        assertEquals(true, payload.getValue("partyPanelVisible").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("partyEndedChoiceVisible").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("socialNotificationVisible").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("presenceJoinPolicyVisible").jsonPrimitive.boolean)
        assertEquals("Join: Direct", payload.getValue("presenceJoinPolicyLabel").jsonPrimitive.content)
    }

    @Test
    fun openingScaleSurvivesTheActualControlsPayloadWhileProgressRemainsBounded() {
        for (scale in listOf(0.75f, 1f, 1.4186993f, 2f)) {
            val payload = Json.parseToJsonElement(
                PlayerControlsState(openingScale = scale, openingProgress = 1.5f)
                    .toControlsJson(isFullscreen = false),
            ).jsonObject
            assertEquals(scale, payload.getValue("openingScale").jsonPrimitive.float)
            assertEquals(1f, payload.getValue("openingProgress").jsonPrimitive.float)
        }
    }

    @Test
    fun invalidOpeningScalesUseTheDefaultWithoutProducingInvalidJson() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val payload = Json.parseToJsonElement(
                PlayerControlsState(openingScale = scale).toControlsJson(isFullscreen = false),
            ).jsonObject
            assertEquals(1f, payload.getValue("openingScale").jsonPrimitive.float)
        }
    }
}
