package com.nuvio.app.features.social

import com.nuvio.app.AppScreenTab
import com.nuvio.app.coerceAvailableTab
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What "Social off" means once it is one shared value, and what the repository does with it.
 *
 * Runs on the desktop host because the preference's storage is a real per-profile file (redirected to a
 * temp home by the build). The behaviours under test are common code, so the phones execute the same
 * logic; the surfaces below all derive from this one value.
 */
class SocialOffGateTest {

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

    private fun presence() = SocialPresencePublish(
        sessionId = "00000000-0000-0000-0000-000000000001",
        contentId = "tt1", contentType = "movie", videoId = "tt1", title = "T",
        positionMs = 0, durationMs = 1, playbackSpeed = 1f, state = SocialPlaybackState.playing,
    )

    // --------------------------------------------------------------------------- the one value

    @Test
    fun `social off then on follows the shared value for every reader`() {
        SocialFeaturePreferencesRepository.adoptShared(false)
        assertFalse(SocialFeaturePreferencesRepository.isEnabledNow)
        assertFalse(SocialFeaturePreferencesRepository.uiState.value.enabled)

        SocialFeaturePreferencesRepository.adoptShared(true)
        assertTrue(SocialFeaturePreferencesRepository.isEnabledNow)
        assertTrue(SocialFeaturePreferencesRepository.uiState.value.enabled)
    }

    @Test
    fun `a value received from the shared copy settles any pending local change`() {
        SocialFeaturePreferencesRepository.setEnabled(false)
        assertEquals(false, SocialFeaturePreferencesRepository.pendingPreference())

        SocialFeaturePreferencesRepository.adoptShared(false)
        assertNull(SocialFeaturePreferencesRepository.pendingPreference())
    }

    @Test
    fun `a change made on this device is pending until the shared copy has it, and survives a restart`() {
        SocialFeaturePreferencesRepository.setEnabled(false)
        assertEquals(false, SocialFeaturePreferencesRepository.pendingPreference())
        assertEquals(false, SocialFeaturePreferencesRepository.uiState.value.storedPreference)

        // A relaunch: nothing in memory, only the file.
        SocialFeaturePreferencesRepository.clearLocalState()
        SocialFeaturePreferencesRepository.ensureLoaded()
        assertEquals(false, SocialFeaturePreferencesRepository.pendingPreference())
        assertFalse(SocialFeaturePreferencesRepository.isEnabledNow)
    }

    @Test
    fun `switching a second time while the first is still pending updates the pending change`() {
        SocialFeaturePreferencesRepository.setEnabled(false)
        SocialFeaturePreferencesRepository.setEnabled(true)
        assertEquals(true, SocialFeaturePreferencesRepository.pendingPreference())
        assertTrue(SocialFeaturePreferencesRepository.isEnabledNow)
    }

    // ----------------------------------------------------------- what Social off takes away

    @Test
    fun `social off removes the social destination on both families`() {
        for (ownDestination in listOf(true, false)) {
            assertEquals(AppScreenTab.Home, coerceAvailableTab(AppScreenTab.Social, socialEnabled = false, downloadsOwnDestination = ownDestination))
            assertEquals(AppScreenTab.Social, coerceAvailableTab(AppScreenTab.Social, socialEnabled = true, downloadsOwnDestination = ownDestination))
        }
    }

    @Test
    fun `the state the home screen is handed when social is off has no friends, activity or party controls`() {
        val off = SocialUiState()
        assertTrue(off.watchingNow.isEmpty())
        assertTrue(off.activity.isEmpty())
        assertTrue(off.friends.isEmpty())
        assertFalse(off.capabilities.watchPartyEnabled)
        assertEquals(0, off.unreadCount)
    }

    @Test
    fun `social off publishes no presence, whoever asks`() = runTest {
        SocialFeaturePreferencesRepository.adoptShared(false)
        val off = SocialRepository.publishPresence("device-0000000000000001", presence())
        assertTrue(off.isFailure)
        assertEquals("Social is off", off.exceptionOrNull()?.message)
    }

    @Test
    fun `social on restores presence to the normal path`() = runTest {
        SocialFeaturePreferencesRepository.adoptShared(true)
        val on = SocialRepository.publishPresence("device-0000000000000001", presence())
        // No active social profile in a unit test, so it fails - but for the ordinary reason, not the gate's.
        assertTrue(on.isFailure)
        assertFalse(on.exceptionOrNull()?.message == "Social is off")
    }
}
