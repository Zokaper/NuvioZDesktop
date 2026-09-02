package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_addon_differences
import nuvio.composeapp.generated.resources.watch_party_choose_source
import nuvio.composeapp.generated.resources.watch_party_resolve_source
import nuvio.composeapp.generated.resources.watch_party_source_explanation
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

private val lobbyLog = Logger.withTag("WatchPartyLobby")

@Composable
fun WatchPartyLobbyScreen(
    route: WatchPartyLobbyRoute,
    onBack: () -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    onChooseSource: (WatchPartyState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val syncState by WatchPartySync.state.collectAsStateWithLifecycle()
    val socialState by SocialRepository.uiState.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Start submitted a play command and stopped there, so the party went to `playing` server-side
    // while everyone sat in the lobby watching nothing happen.
    //
    // Playback cannot simply be launched from here: each participant resolves their own source, by
    // design, so there is no shared stream to open. Leaving the lobby for the title is the honest
    // transition - from there the usual player flow runs, and BindWatchPartyEffect takes over the
    // synchronisation once playback starts.
    val addonSignature = remember(addonsState.addons) { watchPartyAddonSignature(addonsState.addons) }
    var handedOffSourceGeneration by remember(route) { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.party?.id, addonSignature) {
        if (state.party != null) WatchPartyRepository.publishAddonSignature(addonSignature)
    }

    LaunchedEffect(state.party?.sourceGeneration, state.party?.sourceFingerprint) {
        val party = state.party ?: return@LaunchedEffect
        val isHost = party.hostProfileId == state.activeProfileId
        if (
            isHost ||
            party.sourceFingerprint == null ||
            party.effectiveStage() !in setOf(
                WatchPartyStage.resolving_sources,
                WatchPartyStage.ready_to_launch,
                WatchPartyStage.playing,
            )
        ) return@LaunchedEffect
        if (handedOffSourceGeneration == party.sourceGeneration) return@LaunchedEffect
        handedOffSourceGeneration = party.sourceGeneration
        onChooseSource(party)
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
                initialPositionMs = route.initialPositionMs,
                initialPlaybackSpeed = route.initialPlaybackSpeed,
            )
        }
    }

    val party = state.party
    // Hosted outside a Surface, so LocalContentColor falls back to black. Without this the whole
    // lobby - the invite code included - is black on a dark background.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    LazyColumn(
        modifier = modifier.fillMaxSize().widthIn(max = 1040.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                Text(stringResource(Res.string.watch_party_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        state.errorMessage?.let { item { PartyPanel { Text(it, color = MaterialTheme.colorScheme.error) } } }
        if (party == null) {
            item { PartyPanel { Text(if (state.isWorking) "Creating session…" else "Unable to open this session.") } }
        } else {
            item {
                PartyPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        party.content.poster?.let { poster ->
                            NuvioAsyncImage(
                                model = poster,
                                contentDescription = null,
                                modifier = Modifier.size(width = 92.dp, height = 132.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(party.content.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            party.content.episode?.let { Text("S${party.content.season ?: 1} E$it · ${party.content.episodeTitle.orEmpty()}") }
                            Text(
                                party.effectiveStage().name.replace('_', ' ').replaceFirstChar(Char::uppercase),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${state.connection.name.replaceFirstChar(Char::uppercase)} · ${party.members.size}/$WatchPartyMaxParticipants")
                            Text(partySyncLabel(state.connection, syncState), style = MaterialTheme.typography.bodySmall)
                            state.inviteCode?.let {
                                SelectionContainer { Text("Invite code  $it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            val hostSignature = party.members.firstOrNull { it.profileId == party.hostProfileId }?.addonSignature.orEmpty()
            val addonMismatches = party.members.filter { member ->
                member.connected && member.profileId != party.hostProfileId &&
                    comparePartyAddonSignatures(hostSignature, member.addonSignature).differs
            }
            if (addonMismatches.isNotEmpty()) {
                item {
                    PartyPanel {
                        Text(stringResource(Res.string.watch_party_addon_differences), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                        Text(
                            "${addonMismatches.size} ${if (addonMismatches.size == 1) "person has" else "people have"} a different set of stream addons. You can continue; an alternate source may be needed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
            if (party.hostProfileId == state.activeProfileId) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.waitForEveryone,
                            onClick = { WatchPartyRepository.setWaitForEveryone(!state.waitForEveryone) },
                            label = { Text("Wait for everyone") },
                        )
                        Text(
                            if (state.waitForEveryone) {
                                "Playback pauses for anyone whose stream stalls, and starts again together."
                            } else {
                                "Playback carries on when someone's stream stalls; they catch up on their own."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(member.displayName(state.activeProfileId).take(1).uppercase(), fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(member.displayName(state.activeProfileId), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${member.role.replaceFirstChar(Char::uppercase)} · ${member.readyState.lobbyLabel()}${member.sourceMatch?.let { " · ${it.name}" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            member.readyError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(
                                if (member.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                }
            }
            if (party.hostProfileId == state.activeProfileId) {
                item {
                    // "I'm ready" used to sit here and mark a member ready from the lobby, where
                    // nobody has resolved anything yet - it reported a source that did not exist and
                    // was the one thing that could defeat the host's own readiness gate. Readiness is
                    // now reported by the player, once a stream is actually open.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val retainedFingerprint = party.sourceFingerprint
                                    WatchPartyRepository.beginSourceSelection(addonSignature).onSuccess {
                                        val selectingParty = WatchPartyRepository.uiState.value.party
                                        if (retainedFingerprint == null || selectingParty == null) {
                                            selectingParty?.let(onChooseSource)
                                        } else {
                                            WatchPartyRepository.selectSource(
                                                fingerprint = retainedFingerprint,
                                                expectedSourceGeneration = selectingParty.sourceGeneration,
                                            ).onSuccess {
                                                WatchPartyRepository.uiState.value.party?.let(onChooseSource)
                                            }
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (party.sourceFingerprint == null) stringResource(Res.string.watch_party_choose_source)
                                else stringResource(Res.string.watch_party_resolve_source),
                            )
                        }
                        Text(
                            stringResource(Res.string.watch_party_source_explanation),
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

/**
 * What the party's synchronisation is actually doing.
 *
 * The connection line above says whether the channel is up, which is not the same question: a
 * channel can be up while the shared clock is still being measured, and during those first few
 * seconds the party is following the database anchor rather than the host's timeline. Saying so is
 * the difference between "it is warming up" and "it is broken".
 */
private fun partySyncLabel(connection: PartyConnectionState, sync: WatchPartySyncState): String = when {
    connection != PartyConnectionState.connected -> "Following the party every few seconds"
    !sync.clockLocked -> "Measuring the shared clock…"
    sync.bestRttMs < 0 -> "Live sync"
    else -> "Live sync · ${sync.bestRttMs} ms round trip"
}

private fun SourceResolutionState.lobbyLabel(): String = when (this) {
    SourceResolutionState.joined -> "no source yet"
    SourceResolutionState.waiting_for_host -> "waiting for host"
    SourceResolutionState.fetching -> "finding the host source"
    SourceResolutionState.resolving -> "picking a source"
    SourceResolutionState.choosing_fallback -> "choosing an alternate"
    SourceResolutionState.source_ready -> "source ready"
    SourceResolutionState.buffering -> "buffering"
    SourceResolutionState.ready -> "ready"
    SourceResolutionState.failed -> "no source found"
    SourceResolutionState.left -> "left"
    SourceResolutionState.disconnected -> "disconnected"
}
