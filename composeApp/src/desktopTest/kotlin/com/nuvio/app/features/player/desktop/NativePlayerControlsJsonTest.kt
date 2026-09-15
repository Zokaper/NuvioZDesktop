package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.WatchTogetherBridgeInvite
import com.nuvio.app.features.player.WatchTogetherBridgePerson
import com.nuvio.app.features.player.WatchTogetherBridgeState
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativePlayerControlsJsonTest {
    @Test
    fun watchTogetherStateAndTheTransportLockReachTheNativeControlsPayload() {
        val payload = Json.parseToJsonElement(
            PlayerControlsState(
                watchTogether = WatchTogetherBridgeState(
                    open = true,
                    stateName = "active",
                    badge = "incoming",
                    title = "Mayday",
                    joinPolicy = 2,
                    people = listOf(WatchTogetherBridgePerson("You", "", "#111111", true, true, "Playing", "ready")),
                    inviteTargets = listOf(WatchTogetherBridgeInvite(0, "Seraph", "", invited = true)),
                    incomingVisible = true,
                    incomingName = "Ahmed \"A\"",
                ),
                socialNotificationVisible = true,
                partyTransportLocked = true,
                partyHostName = "Seraph",
            ).toControlsJson(isFullscreen = false),
        ).jsonObject

        val wt = payload.getValue("watchTogether").jsonObject
        assertEquals(true, wt.getValue("open").jsonPrimitive.boolean)
        assertEquals("active", wt.getValue("state").jsonPrimitive.content)
        assertEquals("incoming", wt.getValue("badge").jsonPrimitive.content)
        assertEquals("Mayday", wt.getValue("title").jsonPrimitive.content)
        assertEquals(2, wt.getValue("joinPolicy").jsonPrimitive.int)
        assertEquals("Ahmed \"A\"", wt.getValue("incomingName").jsonPrimitive.content)
        assertEquals("You", wt.getValue("people").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(true, wt.getValue("inviteTargets").jsonArray.single().jsonObject.getValue("invited").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("socialNotificationVisible").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("partyTransportLocked").jsonPrimitive.boolean)
        assertEquals("Seraph", payload.getValue("partyHostName").jsonPrimitive.content)
        // Gone with the header policy pill and the centred end-of-party modal.
        assertFalse("presenceJoinPolicyVisible" in payload)
        assertFalse("partyEndedChoiceVisible" in payload)
        assertFalse("partyRoom" in payload)
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
