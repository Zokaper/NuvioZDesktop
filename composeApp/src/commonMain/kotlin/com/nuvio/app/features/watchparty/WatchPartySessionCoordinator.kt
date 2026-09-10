package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private object RepositoryDurablePartyGateway : DurablePartyGateway {
    override val snapshots = WatchPartyRepository.uiState.map { it.party }.distinctUntilChanged()
    override fun currentParty() = WatchPartyRepository.uiState.value.party
    override suspend fun restoreActiveParty() = WatchPartyRepository.restoreActive()
    override fun installAuthorizedParty(snapshot: WatchPartyState) = WatchPartyRepository.installAuthorizedSnapshot(snapshot)
    override suspend fun publishLocation(location: WatchPartyClientLocation) = WatchPartyRepository.setClientLocation(location)
    override suspend fun publishReadiness(
        state: SourceResolutionState,
        durationMs: Long?,
        sourceGeneration: Int?,
        sourceMatch: PartySourceMatch?,
    ) = WatchPartyRepository.updateReady(state, durationMs, sourceGeneration = sourceGeneration, sourceMatch = sourceMatch)
    override suspend fun promotePlaybackPresence(sessionId: String) = WatchPartyRepository.promotePresence(sessionId)
    override suspend fun leaveParty() = WatchPartyRepository.leave()
    override suspend fun endParty() = WatchPartyRepository.end()
    override fun updatePlaybackTelemetry(telemetry: PartyPlaybackTelemetry?) = WatchPartyRepository.updatePlaybackTelemetry(telemetry)
}

private sealed interface PartySessionIntent {
    data class Register(val context: ActivePlaybackContext, val sessionId: String, val deviceId: String) : PartySessionIntent
    data class Unregister(val attachmentId: String) : PartySessionIntent
    data class Lobby(val partyId: String) : PartySessionIntent
    data class Readiness(
        val state: SourceResolutionState,
        val durationMs: Long?,
        val sourceGeneration: Int?,
        val sourceMatch: PartySourceMatch?,
    ) : PartySessionIntent
    data object Promote : PartySessionIntent
    data object Restore : PartySessionIntent
    data class Installed(val snapshot: WatchPartyState) : PartySessionIntent
    data object Leave : PartySessionIntent
    data object End : PartySessionIntent
    data class PartyEnded(val viewerWasHost: Boolean) : PartySessionIntent
    data object ContinueAfterEnd : PartySessionIntent
    data class Snapshot(val value: WatchPartyState?) : PartySessionIntent
}

/**
 * Serialized process owner for party session semantics.
 *
 * It carried a shadow comparison against the legacy repository snapshot while Stage 1 was switching
 * over. Nothing ever read it after the switch, and a comparison nobody looks at is not a safety net
 * - it is a second answer with no arbiter, which is the thing these stages exist to remove.
 */
object WatchPartySessionCoordinator {
    private val gateway: DurablePartyGateway = RepositoryDurablePartyGateway
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val intents = Channel<PartySessionIntent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PartySessionState())
    val state: StateFlow<PartySessionState> = _state.asStateFlow()
    private var presenceSessionId: String? = null
    private var presenceDeviceId: String? = null

    init {
        scope.launch { for (intent in intents) reduce(intent) }
        scope.launch { gateway.snapshots.collect { intents.send(PartySessionIntent.Snapshot(it)) } }
        // Readiness from the work itself, not from whichever screen happened to be composed.
        // Matching and resolving are the window the host's wait gate is looking at, and until this
        // ran nobody reported them: a member spent the whole preparation showing the party the
        // state they joined with.
        scope.launch {
            PartySourceRealizer.state
                .map(::partyReadinessReport)
                .distinctUntilChanged()
                .collect { report ->
                    report?.let { (state, sourceGeneration) ->
                        intents.send(
                            PartySessionIntent.Readiness(
                                state = state,
                                durationMs = null,
                                sourceGeneration = sourceGeneration,
                                sourceMatch = null,
                            ),
                        )
                    }
                }
        }
    }

    fun registerPlayback(context: ActivePlaybackContext, sessionId: String, deviceId: String) =
        enqueue(PartySessionIntent.Register(context, sessionId, deviceId))
    fun unregisterPlayback(attachmentId: String) = enqueue(PartySessionIntent.Unregister(attachmentId))
    fun enterLobby(partyId: String) = enqueue(PartySessionIntent.Lobby(partyId))
    fun reportReadiness(
        state: SourceResolutionState,
        durationMs: Long? = null,
        sourceGeneration: Int? = null,
        sourceMatch: PartySourceMatch? = null,
    ) = enqueue(PartySessionIntent.Readiness(state, durationMs, sourceGeneration, sourceMatch))
    fun reportPlaybackTelemetry(telemetry: PartyPlaybackTelemetry?) = gateway.updatePlaybackTelemetry(telemetry)
    fun promoteCurrentPlayback() = enqueue(PartySessionIntent.Promote)
    fun restore() = enqueue(PartySessionIntent.Restore)
    fun installAuthorizedParty(snapshot: WatchPartyState) {
        // Preserve the established navigation contract: callers install the authorized snapshot
        // before opening its lobby. Only the semantic reducer update waits in the serialized queue.
        gateway.installAuthorizedParty(snapshot)
        enqueue(PartySessionIntent.Installed(snapshot))
    }
    fun leave() = enqueue(PartySessionIntent.Leave)
    fun end() = enqueue(PartySessionIntent.End)
    fun partyEnded(viewerWasHost: Boolean) = enqueue(PartySessionIntent.PartyEnded(viewerWasHost))
    fun continueAfterPartyEnd() = enqueue(PartySessionIntent.ContinueAfterEnd)

    private fun enqueue(intent: PartySessionIntent) { intents.trySend(intent) }

    private suspend fun reduce(intent: PartySessionIntent) {
        when (intent) {
            is PartySessionIntent.Register -> {
                val wasAttached = _state.value.playback?.attachmentId == intent.context.attachmentId &&
                    _state.value.phase == PartyClientPhase.ActivePlayer
                presenceSessionId = intent.sessionId
                presenceDeviceId = intent.deviceId
                val party = gateway.currentParty()
                _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttached(intent.context, party?.partyGenerationKey()))
                if (party != null && !wasAttached) gateway.publishLocation(WatchPartyClientLocation.player)
            }
            is PartySessionIntent.Unregister -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttachmentLost(intent.attachmentId))
                if (_state.value.playback == null) {
                    presenceSessionId = null
                    presenceDeviceId = null
                    gateway.updatePlaybackTelemetry(null)
                }
            }
            is PartySessionIntent.Lobby -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.LobbyEntered(intent.partyId))
                gateway.publishLocation(WatchPartyClientLocation.lobby)
            }
            is PartySessionIntent.Readiness -> gateway.publishReadiness(
                intent.state,
                intent.durationMs,
                intent.sourceGeneration,
                intent.sourceMatch,
            )
            PartySessionIntent.Promote -> {
                val session = presenceSessionId ?: return
                gateway.promotePlaybackPresence(session).onSuccess {
                    val party = gateway.currentParty() ?: return@onSuccess
                    val playback = _state.value.playback ?: return@onSuccess
                    _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttached(playback, party.partyGenerationKey()))
                    gateway.publishLocation(WatchPartyClientLocation.player)
                }
            }
            PartySessionIntent.Restore -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.RestoreStarted)
                gateway.restoreActiveParty().onSuccess { party ->
                    _state.value = if (party == null) reducePartySession(_state.value, PartySessionEvent.NoActiveParty)
                    else reducePartySession(_state.value, PartySessionEvent.Restored(party.partyGenerationKey()))
                }
            }
            is PartySessionIntent.Installed -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Restored(intent.snapshot.partyGenerationKey()))
            }
            PartySessionIntent.Leave -> gateway.leaveParty().onSuccess {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Left())
            }
            PartySessionIntent.End -> gateway.endParty().onSuccess {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Ended(viewerWasHost = true))
            }
            is PartySessionIntent.PartyEnded -> _state.value = reducePartySession(_state.value, PartySessionEvent.Ended(intent.viewerWasHost))
            PartySessionIntent.ContinueAfterEnd -> _state.value = PartySessionState(playback = _state.value.playback)
            is PartySessionIntent.Snapshot -> observeSnapshot(intent.value)
        }
    }

    private fun observeSnapshot(party: WatchPartyState?) {
        if (party == null) {
            if (_state.value.membershipRetained) _state.value = reducePartySession(_state.value, PartySessionEvent.NoActiveParty)
            return
        }
        val generation = party.partyGenerationKey()
        _state.value = if (_state.value.generation == null) reducePartySession(_state.value, PartySessionEvent.Restored(generation))
        else reducePartySession(_state.value, PartySessionEvent.SnapshotAdvanced(generation))
    }
}
