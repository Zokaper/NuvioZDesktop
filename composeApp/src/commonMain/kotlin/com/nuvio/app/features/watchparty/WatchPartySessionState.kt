package com.nuvio.app.features.watchparty

enum class PartyClientPhase {
    None,
    Connecting,
    Lobby,
    MatchingHostSource,
    AwaitingFallbackChoice,
    LoadingPlayer,
    ActivePlayer,
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
    data class PlayerExited(val partyId: String) : PartySessionEvent
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
    )
    is PartySessionEvent.PlayerExited -> state.copy(
        phase=PartyClientPhase.Lobby,playback=null,membershipRetained=true,pendingLobbyPartyId=event.partyId,
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
    is PartySessionEvent.Ended -> if (event.viewerWasHost) {
        PartySessionState(playback=state.playback)
    } else {
        state.copy(phase=PartyClientPhase.Ended,membershipRetained=false,guestPostEndChoice=true)
    }
}

fun PartyGenerationKey.accepts(other: PartyGenerationKey): Boolean =
    partyId==other.partyId && contentGeneration==other.contentGeneration &&
        sourceGeneration==other.sourceGeneration && authorityEpoch==other.authorityEpoch
