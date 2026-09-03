package com.nuvio.app.features.watchparty

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_addon_differences
import nuvio.composeapp.generated.resources.watch_party_choose_source
import nuvio.composeapp.generated.resources.watch_party_resolve_source
import nuvio.composeapp.generated.resources.watch_party_source_explanation
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

private val lobbyLog = Logger.withTag("WatchPartyLobby")

/**
 * The lobby's amber.
 *
 * The scheme has no warning role, and `error` is the wrong thing to say about somebody who is
 * merely still looking for a source - it reads as a failure that nobody needs to act on.
 */
private val PartyWorkingColor = Color(0xFFE0A458)

/**
 * The lobby's green, fixed rather than themed.
 *
 * Readiness is semantic, and `colorScheme.primary` follows the user's theme picker: under Crimson
 * a red "ready" sits beside a pink `colorScheme.error` "no source found" and the pair says nothing.
 * The accent still carries emphasis - the stage rail, the invite tile - where no state is meant.
 */
private val PartyReadyColor = Color(0xFF6FD08C)

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
    val isHost = party != null && party.hostProfileId == state.activeProfileId

    // Hosted outside a Surface, so LocalContentColor falls back to black. Without this the whole
    // lobby - the invite code included - is black on a dark background.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(modifier.fillMaxSize()) {
            // The party is about one specific title, and this screen used to show a 92x132 poster on
            // the settings-screen background. The art is most of what makes it a lobby, not a form.
            PartyLobbyBackdrop(party?.content?.poster)

            // `widthIn` without a centring parent pinned the whole lobby to the left edge of a wide
            // monitor, which is what "1040dp max width" quietly meant here. The social screen got
            // its BoxWithConstraints; this one never did.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= 900.dp
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().widthIn(max = 1040.dp),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.watch_party_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    state.errorMessage?.let { message ->
                        item { PartyNotice(message, MaterialTheme.colorScheme.error) }
                    }

                    if (party == null) {
                        item {
                            PartyPanel {
                                if (state.isWorking) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text("Opening the session…")
                                    }
                                } else {
                                    Text("Unable to open this session.")
                                }
                            }
                        }
                        return@LazyColumn
                    }

                    item {
                        PartyHero(
                            party = party,
                            inviteCode = state.inviteCode,
                            connection = state.connection,
                            sync = syncState,
                            wide = wide,
                        )
                    }

                    item { PartyStageRail(party.effectiveStage()) }

                    val hostSignature = party.members
                        .firstOrNull { it.profileId == party.hostProfileId }?.addonSignature.orEmpty()
                    val addonMismatches = party.members.filter { member ->
                        member.connected && member.profileId != party.hostProfileId &&
                            comparePartyAddonSignatures(hostSignature, member.addonSignature).differs
                    }
                    if (addonMismatches.isNotEmpty()) {
                        item {
                            PartyNotice(
                                stringResource(Res.string.watch_party_addon_differences) + " — " +
                                    "${addonMismatches.size} ${if (addonMismatches.size == 1) "person has" else "people have"} " +
                                    "a different set of stream addons. You can continue; an alternate source may be needed.",
                                PartyWorkingColor,
                            )
                        }
                    }

                    item {
                        PartyParticipants(
                            party = party,
                            viewerProfileId = state.activeProfileId,
                            invitableFriends = socialState.friends.filterNot { friend ->
                                party.members.any { it.profileId == friend.profileId }
                            },
                            onInvite = { profileId -> scope.launch { WatchPartyRepository.invite(profileId) } },
                        )
                    }

                    if (isHost) {
                        item {
                            PartyHostSettings(
                                controlMode = party.controlMode,
                                onControlMode = { mode ->
                                    scope.launch { WatchPartyRepository.setControlMode(mode) }
                                },
                                waitForEveryone = state.waitForEveryone,
                                onWaitForEveryone = { WatchPartyRepository.setWaitForEveryone(it) },
                            )
                        }
                    }

                    item {
                        PartyActionBar(
                            isHost = isHost,
                            hasSource = party.sourceFingerprint != null,
                            // "I'm ready" used to sit here and mark a member ready from the lobby,
                            // where nobody has resolved anything yet - it reported a source that did
                            // not exist and was the one thing that could defeat the host's own
                            // readiness gate. Readiness is now reported by the player, once a stream
                            // is actually open.
                            onPrimary = {
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
                            onLeave = {
                                scope.launch {
                                    if (isHost) WatchPartyRepository.end() else WatchPartyRepository.leave()
                                    onBack()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The title's own art, blurred behind the lobby.
 *
 * `Modifier.blur` is a no-op below API 31 and the tint is all that survives there - see the note on
 * `isBackdropBlurSupported` - so the scrim is heavy enough to carry the screen without it. Only the
 * poster is on [PartyContent], so this is a 2:3 image cropped to fill; at this radius it reads as
 * colour rather than as a stretched poster.
 */
@Composable
private fun PartyLobbyBackdrop(poster: String?) {
    Box(Modifier.fillMaxSize()) {
        if (!poster.isNullOrBlank()) {
            NuvioAsyncImage(
                model = poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(72.dp).alpha(0.5f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.74f),
                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                ),
            ),
        )
    }
}

@Composable
private fun PartyHero(
    party: WatchPartyState,
    inviteCode: String?,
    connection: PartyConnectionState,
    sync: WatchPartySyncState,
    wide: Boolean,
) {
    PartyPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
            party.content.poster?.let { poster ->
                NuvioAsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.size(width = 104.dp, height = 156.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.poster)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    party.content.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                party.content.episode?.let { episode ->
                    Text(
                        "S${party.content.season ?: 1} E$episode" +
                            party.content.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    party.stageHeadline(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                PartySyncLine(connection, sync, party.members.size)
            }
            if (wide && inviteCode != null) PartyInviteCode(inviteCode)
        }
        if (!wide && inviteCode != null) {
            Spacer(Modifier.height(4.dp))
            PartyInviteCode(inviteCode, fillWidth = true)
        }
    }
}

/**
 * The invite code, as the thing a host actually came here for.
 *
 * It used to be the last line of a paragraph of body text at `titleMedium`, with no way to take it
 * short of dragging a selection across it.
 */
@Composable
private fun PartyInviteCode(code: String, fillWidth: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Surface(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clickable {
                clipboard.setText(AnnotatedString(code))
                copied = true
            },
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "INVITE CODE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy invite code",
                    modifier = Modifier.size(18.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (copied) "Copied" else "Click to copy",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun PartySyncLine(connection: PartyConnectionState, sync: WatchPartySyncState, memberCount: Int) {
    val connected = connection == PartyConnectionState.connected
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (connected) PartyReadyColor else PartyWorkingColor,
            ),
        )
        Text(
            "${partySyncLabel(connection, sync)} · $memberCount/$WatchPartyMaxParticipants",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where the party is, as four steps rather than one word.
 *
 * The stage was rendered as `resolving_sources` with its underscores swapped out, which named the
 * state without saying whether it was near the start or the end of getting everyone watching.
 */
@Composable
private fun PartyStageRail(stage: WatchPartyStage) {
    val reached = stage.railIndex()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WatchPartyStageRail.forEachIndexed { index, step ->
            val done = index <= reached
            val color by animateColorAsState(
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                label = "party-stage-$index",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(color))
                Text(
                    step.railLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == reached) FontWeight.Bold else FontWeight.Normal,
                    color = if (done) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyParticipants(
    party: WatchPartyState,
    viewerProfileId: String?,
    invitableFriends: List<SocialProfileSummary>,
    onInvite: (String) -> Unit,
) {
    // WatchPartyRepository.invite existed with no caller, so the receiving side rendered invites
    // that nothing could ever send. Only friends can be invited, which party_invite_friend enforces
    // regardless of what is listed here.
    var inviting by remember { mutableStateOf(false) }
    PartyPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Participants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "${party.readyCount()} of ${party.members.count { it.connected }} ready",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            party.members.forEach { member ->
                PartyParticipantTile(
                    member = member,
                    isHost = member.profileId == party.hostProfileId,
                    viewerProfileId = viewerProfileId,
                )
            }
            if (invitableFriends.isNotEmpty() && party.members.size < WatchPartyMaxParticipants) {
                PartyInviteTile(expanded = inviting, onClick = { inviting = !inviting })
            }
        }
        if (inviting && invitableFriends.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                invitableFriends.forEach { friend ->
                    Surface(
                        modifier = Modifier.clickable { onInvite(friend.profileId) },
                        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PartyAvatar(
                                name = friend.displayName,
                                avatarUrl = friend.avatarUrl,
                                colorHex = friend.avatarColorHex,
                                size = 26.dp,
                            )
                            Text(friend.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One participant, as a tile whose readiness can be read across the room.
 *
 * The old row put the answer - "source ready" - in the middle of a run-on `bodySmall` string
 * between the role and the source match, in the smallest type on the screen, with the only colour
 * anywhere on it spent on a 9dp connection dot at the far right.
 */
@Composable
private fun PartyParticipantTile(
    member: WatchPartyParticipant,
    isHost: Boolean,
    viewerProfileId: String?,
) {
    val tone = member.readyTone()
    val offline = tone == PartyReadyTone.Offline
    Surface(
        modifier = Modifier.width(172.dp).alpha(if (offline) 0.55f else 1f),
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The ring is the ambient signal: a tile still working turns and the finished ones
                // sit still, so "who are we waiting for" is answerable without reading a word.
                if (tone == PartyReadyTone.Working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(62.dp),
                        color = PartyWorkingColor,
                        strokeWidth = 2.5.dp,
                        trackColor = Color.Transparent,
                    )
                }
                PartyAvatar(
                    name = member.displayName(viewerProfileId),
                    avatarUrl = member.profile?.avatarUrl,
                    colorHex = member.profile?.avatarColorHex,
                    size = 52.dp,
                )
                if (isHost) {
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Host",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Text(
                member.displayName(viewerProfileId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            PartyStatusPill(tone, member.readyLabel())
            if (!offline && member.sourceMatch == PartySourceMatch.alternate) {
                Text(
                    "different source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            member.readyError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyInviteTile(expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(172.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "Close" else "Invite",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PartyStatusPill(tone: PartyReadyTone, label: String) {
    val color = when (tone) {
        PartyReadyTone.Ready -> PartyReadyColor
        PartyReadyTone.Working -> PartyWorkingColor
        PartyReadyTone.Failed -> MaterialTheme.colorScheme.error
        PartyReadyTone.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = color.copy(alpha = if (tone == PartyReadyTone.Offline) 0.10f else 0.18f),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (tone) {
                PartyReadyTone.Ready -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                PartyReadyTone.Failed -> Icon(
                    Icons.Rounded.PriorityHigh,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                else -> Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PartyAvatar(name: String, avatarUrl: String?, colorHex: String?, size: Dp) {
    val background = colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            // Carried on the model since the feature shipped and drawn by nothing, so every avatar
            // in the party and on the social tab was a monogram.
            NuvioAsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PartyHostSettings(
    controlMode: WatchPartyControlMode,
    onControlMode: (WatchPartyControlMode) -> Unit,
    waitForEveryone: Boolean,
    onWaitForEveryone: (Boolean) -> Unit,
) {
    PartyPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = controlMode == WatchPartyControlMode.host_only,
                onClick = { onControlMode(WatchPartyControlMode.host_only) },
                label = { Text("Host controls") },
            )
            FilterChip(
                selected = controlMode == WatchPartyControlMode.collaborative,
                onClick = { onControlMode(WatchPartyControlMode.collaborative) },
                label = { Text("Collaborative") },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Wait for everyone", fontWeight = FontWeight.SemiBold)
                Text(
                    if (waitForEveryone) {
                        "Playback pauses for anyone whose stream stalls, and starts again together."
                    } else {
                        "Playback carries on when someone's stream stalls; they catch up on their own."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = waitForEveryone, onCheckedChange = onWaitForEveryone)
        }
    }
}

@Composable
private fun PartyActionBar(
    isHost: Boolean,
    hasSource: Boolean,
    onPrimary: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isHost) {
                Button(onClick = onPrimary) {
                    Text(
                        if (!hasSource) {
                            stringResource(Res.string.watch_party_choose_source)
                        } else {
                            stringResource(Res.string.watch_party_resolve_source)
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onLeave) { Text(if (isHost) "End session" else "Leave") }
        }
        if (isHost) {
            Text(
                stringResource(Res.string.watch_party_source_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PartyNotice(message: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        color = accent.copy(alpha = 0.12f),
    ) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

@Composable private fun PartyPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.card),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
    else -> "Live sync · ${sync.bestRttMs} ms"
}
