package com.nuvio.app.features.watchparty

enum class PartyClientPhase {
    None,
    Connecting,
    Lobby,
    MatchingHostSource,
    AwaitingFallbackChoice,
    LoadingPlayer,
    ActivePlayer,
    Detached,
    Reconnecting,
    Ended,
}

data class PartyGenerationKey(
    val partyId: String,
    val contentGeneration: Int,
    val sourceGeneration: Int,
    val authorityEpoch: Long,
)

data class ActivePlaybackContext(
    val attachmentId: String,
    val contentId: String,
    val videoId: String,
    val descriptor: PartySourceDescriptorV2,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val trackIntent: PartyTrackIntent = PartyTrackIntent(),
)

data class PartySessionState(
    val phase: PartyClientPhase = PartyClientPhase.None,
    val generation: PartyGenerationKey? = null,
    val playback: ActivePlaybackContext? = null,
    val membershipRetained: Boolean = false,
    val pendingLobbyPartyId: String? = null,
    val guestPostEndChoice: Boolean = false,
)

sealed interface PartySessionEvent {
    data object RestoreStarted : PartySessionEvent
    data class Restored(val generation: PartyGenerationKey) : PartySessionEvent
    data object NoActiveParty : PartySessionEvent
    data class PlayerAttached(val playback: ActivePlaybackContext, val generation: PartyGenerationKey?) : PartySessionEvent
    data class PlayerAttachmentLost(val attachmentId: String) : PartySessionEvent
    data class LobbyEntered(val partyId: String) : PartySessionEvent
    data class MatchStarted(val generation: PartyGenerationKey) : PartySessionEvent
    data object FallbackRequired : PartySessionEvent
    data object SourceResolved : PartySessionEvent
    data object SocketLost : PartySessionEvent
    data class SnapshotAdvanced(val generation: PartyGenerationKey) : PartySessionEvent
    data class Left(val wasHost: Boolean = false) : PartySessionEvent
    data class Ended(val viewerWasHost: Boolean) : PartySessionEvent
}

fun reducePartySession(state: PartySessionState, event: PartySessionEvent): PartySessionState = when (event) {
    PartySessionEvent.RestoreStarted -> state.copy(phase=PartyClientPhase.Connecting)
    is PartySessionEvent.Restored -> state.copy(
        phase=PartyClientPhase.Lobby,generation=event.generation,membershipRetained=true,
        pendingLobbyPartyId=event.generation.partyId,guestPostEndChoice=false,
    )
    PartySessionEvent.NoActiveParty -> PartySessionState(playback=state.playback)
    is PartySessionEvent.PlayerAttached -> state.copy(
        phase=if (event.generation == null) PartyClientPhase.None else PartyClientPhase.ActivePlayer,
        generation=event.generation ?: state.generation,playback=event.playback,
        membershipRetained=event.generation != null || state.membershipRetained,pendingLobbyPartyId=null,
        // The end-of-party choice belongs to the player that was watching when the party ended. A
        // different player attaching later has nothing to continue.
        guestPostEndChoice=state.guestPostEndChoice && state.playback?.attachmentId == event.playback.attachmentId,
    )
    is PartySessionEvent.PlayerAttachmentLost -> if (state.playback?.attachmentId != event.attachmentId) state else state.copy(
        phase=if (state.membershipRetained) PartyClientPhase.Detached else PartyClientPhase.None,
        playback=null,
        guestPostEndChoice=false,
    )
    is PartySessionEvent.LobbyEntered -> state.copy(
        phase=PartyClientPhase.Lobby,membershipRetained=true,pendingLobbyPartyId=event.partyId,
    )
    is PartySessionEvent.MatchStarted -> state.copy(
        phase=PartyClientPhase.MatchingHostSource,generation=event.generation,membershipRetained=true,
        pendingLobbyPartyId=event.generation.partyId,
    )
    PartySessionEvent.FallbackRequired -> state.copy(phase=PartyClientPhase.AwaitingFallbackChoice)
    PartySessionEvent.SourceResolved -> state.copy(phase=PartyClientPhase.LoadingPlayer)
    PartySessionEvent.SocketLost -> state.copy(
        phase=if (state.membershipRetained) PartyClientPhase.Reconnecting else state.phase,
    )
    is PartySessionEvent.SnapshotAdvanced -> {
        val old=state.generation
        val sourceChanged=old != null && (old.partyId!=event.generation.partyId ||
            old.contentGeneration!=event.generation.contentGeneration || old.sourceGeneration!=event.generation.sourceGeneration)
        state.copy(
            phase=if (sourceChanged) PartyClientPhase.MatchingHostSource else state.phase,
            generation=event.generation,
        )
    }
    is PartySessionEvent.Left -> PartySessionState(playback=state.playback)
    is PartySessionEvent.Ended -> if (event.viewerWasHost || state.playback == null) {
        // Nothing to offer: the host ended it on purpose, or this guest was not watching anything -
        // a lobby or a source route, which close themselves on the ended party. A choice raised with
        // no player would sit in state and surface over whichever player opened next.
        PartySessionState(playback=state.playback)
    } else {
        state.copy(phase=PartyClientPhase.Ended,membershipRetained=false,guestPostEndChoice=true)
    }
}

/**
 * Folds one durable snapshot into the session, including the one that ends it.
 *
 * ⚠ **An ended snapshot used to be read as an ordinary generation advance.** `party_close_ended`
 * bumps the authority epoch, which is not a source change, so the session stayed exactly where it
 * was - Lobby, MatchingHostSource, ActivePlayer - for a party that no longer existed, and the only
 * thing that ever reacted to the end was an effect inside the player. A guest in the lobby or on a
 * source route never learned. Terminal handling lives here now, beside every other snapshot
 * transition, so it holds on whichever route the member happens to be on.
 *
 * It is reduced once, on the transition: a session that has already concluded (or never held this
 * party) is left alone, so an ended party still sitting in the repository cannot re-raise the
 * end-of-party choice over a player opened afterwards.
 */
fun observePartySnapshot(
    state: PartySessionState,
    party: WatchPartyState?,
    selfProfileId: String?,
): PartySessionState {
    if (party == null) {
        return if (state.membershipRetained) reducePartySession(state, PartySessionEvent.NoActiveParty) else state
    }
    if (party.status == WatchPartyStatus.ended) {
        val heldThisParty = state.membershipRetained &&
            (state.generation?.partyId == party.id || state.pendingLobbyPartyId == party.id)
        if (!heldThisParty) return state
        return reducePartySession(
            state,
            PartySessionEvent.Ended(viewerWasHost = selfProfileId != null && party.hostProfileId == selfProfileId),
        )
    }
    val generation = party.partyGenerationKey()
    return if (state.generation == null) reducePartySession(state, PartySessionEvent.Restored(generation))
    else reducePartySession(state, PartySessionEvent.SnapshotAdvanced(generation))
}

/**
 * Whether a party found on the server, but not created by this client, is this client's to adopt.
 *
 * Only a host's: `social_join_watching` and an accepted join request both build the party from the
 * host's presence and make the host its host, and neither tells the host. A member who is merely
 * *in* such a party - a guest whose request was just accepted - reaches it through its own lobby.
 * Never over a live party already held.
 */
fun shouldAdoptDiscoveredParty(
    discovered: WatchPartyState,
    selfProfileId: String?,
    heldLiveParty: WatchPartyState?,
): Boolean =
    heldLiveParty == null &&
        selfProfileId != null &&
        discovered.status != WatchPartyStatus.ended &&
        discovered.hostProfileId == selfProfileId

fun PartyGenerationKey.accepts(other: PartyGenerationKey): Boolean =
    partyId==other.partyId && contentGeneration==other.contentGeneration &&
        sourceGeneration==other.sourceGeneration && authorityEpoch==other.authorityEpoch

fun WatchPartyState.partyGenerationKey() = PartyGenerationKey(
    partyId = id,
    contentGeneration = contentGeneration,
    sourceGeneration = sourceGeneration,
    authorityEpoch = authorityEpoch,
)

fun WatchPartyState.authorityContext(selfProfileId: String?): PartyAuthorityContext? =
    selfProfileId?.let {
        PartyAuthorityContext(
            partyId = id,
            selfProfileId = it,
            hostProfileId = hostProfileId,
            controlMode = controlMode,
            durableSequence = sequence,
            generation = partyGenerationKey(),
        )
    }
