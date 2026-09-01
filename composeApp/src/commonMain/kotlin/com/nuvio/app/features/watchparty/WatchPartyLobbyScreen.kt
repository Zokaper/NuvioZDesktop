package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.launch

private val lobbyLog = Logger.withTag("WatchPartyLobby")

@Composable
fun WatchPartyLobbyScreen(
    route: WatchPartyLobbyRoute,
    onBack: () -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val socialState by SocialRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Start submitted a play command and stopped there, so the party went to `playing` server-side
    // while everyone sat in the lobby watching nothing happen.
    //
    // Playback cannot simply be launched from here: each participant resolves their own source, by
    // design, so there is no shared stream to open. Leaving the lobby for the title is the honest
    // transition - from there the usual player flow runs, and BindWatchPartyEffect takes over the
    // synchronisation once playback starts.
    val partyStatus = state.party?.status
    LaunchedEffect(partyStatus) {
        val party = state.party ?: return@LaunchedEffect
        if (partyStatus == WatchPartyStatus.lobby || partyStatus == WatchPartyStatus.ended) return@LaunchedEffect
        lobbyLog.i {
            "handoff party=${party.id.shortId()} status=$partyStatus " +
                "content=${party.content.contentType}/${party.content.contentId} video=${party.content.videoId}"
        }
        onOpenContent(party.content.contentType, party.content.contentId, party.content.title)
    }

    LaunchedEffect(route) {
        // Skipping whenever any party was held meant a stale one from earlier in the session
        // suppressed creation entirely: the host was shown somebody else's old lobby, with no
        // invite code, and nothing explained why. Only a party that actually satisfies this route
        // counts as already open.
        val held = state.party
        val heldSatisfiesRoute = held != null && held.status != WatchPartyStatus.ended && when {
            route.partyId != null -> held.id == route.partyId
            !route.inviteCode.isNullOrBlank() -> true
            route.videoId != null -> held.content.videoId == route.videoId
            else -> true
        }
        lobbyLog.i {
            "open route partyId=${route.partyId.shortId()} code=${if (route.inviteCode.isNullOrBlank()) "-" else "****" + route.inviteCode.takeLast(4)} " +
                "content=${route.contentId} video=${route.videoId} held=${held?.id.shortId()} satisfies=$heldSatisfiesRoute"
        }
        if (heldSatisfiesRoute) return@LaunchedEffect
        if (route.partyId != null) {
            WatchPartyRepository.join(partyId = route.partyId)
        } else if (!route.inviteCode.isNullOrBlank()) {
            WatchPartyRepository.join(inviteCode = route.inviteCode)
        } else if (route.contentId != null && route.contentType != null && route.videoId != null) {
            WatchPartyRepository.create(
                PartyContent(
                    contentId = route.contentId,
                    contentType = route.contentType,
                    videoId = route.videoId,
                    title = route.title.orEmpty(),
                    poster = route.poster,
                    season = route.season,
                    episode = route.episode,
                    episodeTitle = route.episodeTitle,
                ),
                sourceFingerprint = route.sourceReleaseFingerprint?.let {
                    SourceFingerprint(
                        addonId = route.sourceAddonId,
                        infoHash = route.sourceInfoHash,
                        fileIndex = route.sourceFileIndex,
                        releaseFingerprint = it,
                    )
                },
            )
        }
    }

    val party = state.party
    // Hosted outside a Surface, so LocalContentColor falls back to black. Without this the whole
    // lobby - the invite code included - is black on a dark background.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                Text("Watch Together", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        state.errorMessage?.let { item { PartyPanel { Text(it, color = MaterialTheme.colorScheme.error) } } }
        if (party == null) {
            item { PartyPanel { Text(if (state.isWorking) "Creating session…" else "Unable to open this session.") } }
        } else {
            item {
                PartyPanel {
                    Text(party.content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    party.content.episode?.let { Text("S${party.content.season ?: 1} E$it") }
                    Text("${state.connection.name.replaceFirstChar(Char::uppercase)} · ${party.members.size}/$WatchPartyMaxParticipants")
                    state.inviteCode?.let {
                        // Only the last four characters are stored in plaintext, so a code that
                        // cannot be read off this screen cannot be recovered at all. Selectable so
                        // it can be copied rather than transcribed.
                        SelectionContainer { Text("Invite code: $it", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = party.controlMode == WatchPartyControlMode.host_only,
                        onClick = { scope.launch { WatchPartyRepository.setControlMode(WatchPartyControlMode.host_only) } },
                        label = { Text("Host controls") },
                    )
                    FilterChip(
                        selected = party.controlMode == WatchPartyControlMode.collaborative,
                        onClick = { scope.launch { WatchPartyRepository.setControlMode(WatchPartyControlMode.collaborative) } },
                        label = { Text("Collaborative") },
                    )
                }
            }
            // WatchPartyRepository.invite existed with no caller, so the receiving side rendered
            // invites that nothing could ever send. Only friends can be invited, which
            // party_invite_friend enforces regardless of what is listed here.
            val invitableFriends = socialState.friends.filterNot { friend ->
                party.members.any { it.profileId == friend.profileId }
            }
            if (invitableFriends.isNotEmpty()) {
                item { Text("Invite a friend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(invitableFriends, key = { "invite:${it.profileId}" }) { friend ->
                    PartyPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(friend.displayName, fontWeight = FontWeight.SemiBold)
                                Text("@${friend.handle}")
                            }
                            Button(onClick = { scope.launch { WatchPartyRepository.invite(friend.profileId) } }) {
                                Text("Invite")
                            }
                        }
                    }
                }
            }

            item { Text("Participants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(party.members, key = WatchPartyParticipant::profileId) { member ->
                PartyPanel {
                    Text(if (member.profileId == state.activeProfileId) "You" else member.profileId.take(8), fontWeight = FontWeight.SemiBold)
                    Text("${member.role} · ${member.readyState.lobbyLabel()}${member.readyError?.let { " · $it" }.orEmpty()}")
                }
            }
            if (party.hostProfileId == state.activeProfileId) {
                item {
                    // "I'm ready" used to sit here and mark a member ready from the lobby, where
                    // nobody has resolved anything yet - it reported a source that did not exist and
                    // was the one thing that could defeat the host's own readiness gate. Readiness is
                    // now reported by the player, once a stream is actually open.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { scope.launch { WatchPartyRepository.startResolving() } }) { Text("Start") }
                        Text(
                            "Everyone picks their own source next. Playback waits until they all have one.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                if (party.hostProfileId == state.activeProfileId) {
                    OutlinedButton(onClick = { scope.launch { WatchPartyRepository.end(); onBack() } }) { Text("End session") }
                } else {
                    OutlinedButton(onClick = { scope.launch { WatchPartyRepository.leave(); onBack() } }) { Text("Leave") }
                }
            }
        }
    }
    }
}

@Composable private fun PartyPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

private fun SourceResolutionState.lobbyLabel(): String = when (this) {
    SourceResolutionState.joined -> "no source yet"
    SourceResolutionState.resolving -> "picking a source"
    SourceResolutionState.buffering -> "buffering"
    SourceResolutionState.ready -> "ready"
    SourceResolutionState.failed -> "no source found"
    SourceResolutionState.left -> "left"
}
