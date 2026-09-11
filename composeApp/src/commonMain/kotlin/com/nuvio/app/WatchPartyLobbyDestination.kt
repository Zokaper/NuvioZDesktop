package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.PartyStreamLaunchContext
import com.nuvio.app.features.streams.PartyStreamLaunchPurpose
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.watchparty.WatchPartyLobbyScreen
import com.nuvio.app.features.watchparty.hydratePartyLaunchArtwork
import com.nuvio.app.features.watchparty.partySourceKey
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.launch

private val watchPartyLobbyDestinationLog = Logger.withTag("WatchPartyLobbyDestination")

/**
 * Navigation owner for the durable lobby.
 *
 * The route carries only an opaque membership target. Party state and source descriptors stay in
 * their process-scoped repositories, and a playback preparation route receives only the safe
 * descriptor plus party generations through [StreamLaunchStore].
 */
@Composable
internal fun WatchPartyLobbyDestination(
    route: WatchPartyLobbyRoute,
    navController: NuvioNavigator,
    playbackProfileId: Int,
) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(route.partyId, route.inviteCode) {
        val held = WatchPartyRepository.uiState.value.party
        val heldMatches = held != null &&
            held.status != WatchPartyStatus.ended &&
            (route.partyId == null || held.id == route.partyId)

        watchPartyLobbyDestinationLog.i {
            "open target=${route.partyId?.take(8) ?: "invite"} held=${held?.id?.take(8)} matches=$heldMatches"
        }
        when {
            heldMatches -> WatchPartyRepository.refresh()
            !route.partyId.isNullOrBlank() -> WatchPartyRepository.join(partyId = route.partyId)
            !route.inviteCode.isNullOrBlank() -> WatchPartyRepository.join(inviteCode = route.inviteCode)
            else -> navController.popBackStack(route)
        }
    }

    LaunchedEffect(state.party?.id) {
        state.party?.id?.let(WatchPartySessionCoordinator::enterLobby)
    }

    suspend fun prepareSource(party: WatchPartyState, purpose: PartyStreamLaunchPurpose) {
        val target = party.sourceFingerprint.takeIf {
            purpose == PartyStreamLaunchPurpose.RESOLVE_PLAYBACK
        }
        if (purpose == PartyStreamLaunchPurpose.RESOLVE_PLAYBACK && target == null) return

        val key = target?.let { party.partySourceKey() }
        if (key != null) {
            PartySourceRealizer.reusable(key)?.let { retained ->
                val playerLaunch = retained.copy(
                    initialPositionMs = WatchPartyRepository.authoritativePositionMs(party),
                    initialProgressFraction = null,
                    // The retained StreamRoute was deliberately removed on lobby exit. This is a
                    // direct attachment, not a fresh failure chain owned by a source route.
                    autoPickedWithFailureChain = false,
                )
                val playerLaunchId = PlayerLaunchStore.put(playerLaunch)
                navController.navigate(PlayerRoute(playerLaunchId, playerLaunch.title))
                return
            }
        }

        val content = party.content
        // The party wire carries identity, not presentation - no logo, no background, no episode
        // thumbnail - so a party launch reached the loading screen with nothing to draw and fell
        // back to plain title text while ordinary playback showed the centred logo. Hydrated from
        // the same metadata cache ordinary playback reads, on this client, rather than widening
        // the backend contract to ship artwork URLs through it.
        val artwork = hydratePartyLaunchArtwork(content)
        val launchId = StreamLaunchStore.put(
            StreamLaunch(
                profileId = playbackProfileId,
                type = content.contentType,
                videoId = content.videoId,
                parentMetaId = content.contentId,
                parentMetaType = content.contentType,
                title = content.title,
                logo = artwork.logo,
                poster = artwork.poster,
                background = artwork.background,
                episodeThumbnail = artwork.episodeThumbnail,
                seasonNumber = content.season,
                episodeNumber = content.episode,
                episodeTitle = content.episodeTitle,
                resumePositionMs = WatchPartyRepository.authoritativePositionMs(party),
                // ⚠ **Not `purpose == SELECT_SOURCE`.** That read as "the host asked for the
                // source list", and `manualSelection` is the first thing `PlaybackModeRouter`
                // tests, so it overrode the host's playback mode outright: a Streamlined or
                // Instant host pressed "Choose a source" and got Classic's release list. The
                // host's mode is the product authority for *which question to ask*, and the
                // router already answers that correctly once nothing short-circuits it.
                // `StreamDestination` is what keeps the *answer* party-shaped, staging the
                // descriptor instead of opening a player whichever way the source was chosen.
                manualSelection = false,
                partyContext = PartyStreamLaunchContext(
                    partyId = party.id,
                    isHost = party.hostProfileId == state.activeProfileId,
                    contentGeneration = party.contentGeneration,
                    sourceGeneration = party.sourceGeneration,
                    targetFingerprint = target,
                    purpose = purpose,
                ),
            ),
        )
        navController.navigate(StreamRoute(launchId = launchId, title = content.title))
    }

    WatchPartyLobbyScreen(
        onBack = { navController.popBackStack(route) },
        onOpenContent = { contentType, contentId, title ->
            navController.navigate(DetailRoute(type = contentType, id = contentId, title = title))
        },
        // Preparing a source now reads metadata before it builds the launch, so it suspends. The
        // screen's callback is not suspending and should not become one - this is a navigation
        // that happens to need a cache read first, not an operation the lobby waits on.
        onChooseSource = { party, purpose -> scope.launch { prepareSource(party, purpose) } },
        modifier = Modifier.fillMaxSize(),
    )
}
