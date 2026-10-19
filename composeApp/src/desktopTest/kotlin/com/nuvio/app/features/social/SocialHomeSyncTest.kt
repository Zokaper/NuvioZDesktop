package com.nuvio.app.features.social

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Social settings -> Home: the Friends shelf (on by default) and "Recommended to you" (off by default,
 * 2026-10-19), each synced per profile only once the user has moved it. Desktop host for the same reason
 * as [SocialOffGateTest]: the storage is a real per-profile file.
 */
class SocialHomeSyncTest {

    @BeforeTest
    fun fresh() {
        SocialFeaturePreferencesStorage.savePayload("{}")
        SocialFeaturePreferencesRepository.clearLocalState()
        SocialFeaturePreferencesRepository.ensureLoaded()
    }

    @AfterTest
    fun clean() {
        SocialFeaturePreferencesStorage.savePayload("{}")
        SocialFeaturePreferencesRepository.clearLocalState()
    }

    @Test
    fun `an untouched profile shows friends, hides recommendations, and exports nothing`() {
        val state = SocialFeaturePreferencesRepository.uiState.value
        assertTrue(state.showFriendsOnHome)
        assertFalse(state.showRecommendedOnHome)
        assertNull(SocialHomeSync.export())
    }

    @Test
    fun `turning recommendations on exports only that switch and survives a restart`() {
        SocialFeaturePreferencesRepository.setShowRecommendedOnHome(true)
        assertEquals(buildJsonObject { put("show_recommended_on_home", true) }, SocialHomeSync.export())

        SocialFeaturePreferencesRepository.clearLocalState()
        SocialFeaturePreferencesRepository.ensureLoaded()
        assertTrue(SocialFeaturePreferencesRepository.uiState.value.showRecommendedOnHome)
        assertTrue(SocialFeaturePreferencesRepository.uiState.value.showFriendsOnHome)
    }

    @Test
    fun `a synced copy without the recommendations key leaves this device's choice alone`() {
        SocialFeaturePreferencesRepository.setShowRecommendedOnHome(true)
        // What an older build exports: the Friends switch alone.
        SocialHomeSync.applyFromSync(JsonObject(mapOf("show_friends_on_home" to JsonPrimitive(false))))
        val state = SocialFeaturePreferencesRepository.uiState.value
        assertFalse(state.showFriendsOnHome)
        assertTrue(state.showRecommendedOnHome)
    }

    @Test
    fun `a synced copy with both keys applies both`() {
        SocialHomeSync.applyFromSync(
            buildJsonObject {
                put("show_friends_on_home", false)
                put("show_recommended_on_home", true)
            },
        )
        val state = SocialFeaturePreferencesRepository.uiState.value
        assertFalse(state.showFriendsOnHome)
        assertTrue(state.showRecommendedOnHome)
        assertEquals(
            buildJsonObject {
                put("show_friends_on_home", false)
                put("show_recommended_on_home", true)
            },
            SocialHomeSync.export(),
        )
    }
}
