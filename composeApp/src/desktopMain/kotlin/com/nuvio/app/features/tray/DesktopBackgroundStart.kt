package com.nuvio.app.features.tray

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileState
import com.nuvio.app.features.social.SocialFeatureGate
import com.nuvio.app.features.social.SocialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Social without a window, for a start straight to the tray ([DesktopStartWithWindows]).
 *
 * The app normally switches Social on from `MainAppContent`, after `AppGate` has restored the session and
 * picked a profile - none of which exists until a window does. This does the least of the same work: the
 * stored session, the **last profile used** (never one behind a PIN), and `SocialRepository.activate` for
 * it. Pop-ups read nothing else. The window still asks "who's watching" when the profile picker is on.
 *
 * Opening the window hands over cleanly: the gate runs as on any start, and entering the same profile makes
 * `activate` with the same id a no-op. If the user picks another profile there, the usual identity
 * boundary takes it from here. Parties, party restore and the outgoing-request store stay with the window.
 */
internal object DesktopBackgroundStart {
    private val log = Logger.withTag("BackgroundStart")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            runCatching { activateSocial() }.onFailure { log.w(it) { "Social did not start in the background" } }
        }
    }

    private suspend fun activateSocial() {
        AuthRepository.initialize()
        NetworkStatusRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        // Signing in to Windows can come before the network does; the stored session is restored when it can be.
        val auth = withTimeoutOrNull(SessionWaitMs) {
            AuthRepository.state.first { it !is AuthState.Loading }
        }
        if (auth !is AuthState.Authenticated) {
            log.i { "not signed in; Social waits for the window" }
            return
        }
        if (DesktopTrayController.windowVisible.value) return
        ProfileRepository.ensureLoaded(auth.userId)
        val profile = backgroundStartupProfile(ProfileRepository.state.value, ProfileRepository.activeProfileId)
        if (profile == null) {
            log.i { "no unlocked profile to start Social for; it waits for the window" }
            return
        }
        // The window may have opened meanwhile; from then on its gate decides.
        if (DesktopTrayController.windowVisible.value) return
        ProfileRepository.switchToProfile(profile.profileIndex)
        if (!SocialFeatureGate.isEnabled) return
        val profileId = profile.id.takeIf(String::isNotBlank) ?: return
        log.i { "Social started in the background" }
        SocialRepository.activate(profileId)
    }

    private const val SessionWaitMs = 5 * 60_000L
}

/**
 * Whose pop-ups a start in the tray carries: the last profile used, else the only profile - never one with
 * a PIN, which would mean acting as a profile nobody unlocked.
 *
 * Wider than `AppGate`'s own skip (which also needs "remember last profile"): a household that picks a
 * profile at every launch still turned Start with Windows on to get its pop-ups (2026-10-18, the
 * maintainer's own install). Only Social runs as that profile; the window asks as it always does.
 */
internal fun backgroundStartupProfile(state: ProfileState, activeProfileIndex: Int): NuvioProfile? {
    if (state.hasEverSelectedProfile) {
        state.profiles.find { it.profileIndex == activeProfileIndex }?.let { return it.takeUnless(NuvioProfile::pinEnabled) }
    }
    return state.profiles.singleOrNull()?.takeUnless { it.pinEnabled }
}
