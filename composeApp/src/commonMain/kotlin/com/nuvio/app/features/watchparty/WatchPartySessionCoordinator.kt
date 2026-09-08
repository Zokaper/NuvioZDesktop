package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of party/player attachment. It retains only safe metadata and attachment
 * IDs; the native controller remains owned by PlayerRoute and is never held here.
 */
object WatchPartySessionCoordinator {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val _state=MutableStateFlow(PartySessionState())
    val state:StateFlow<PartySessionState> = _state.asStateFlow()
    private var presenceSessionId:String?=null
    private var presenceDeviceId:String?=null

    fun registerPlayback(context:ActivePlaybackContext,sessionId:String,deviceId:String) {
        presenceSessionId=sessionId
        presenceDeviceId=deviceId
        val party=WatchPartyRepository.uiState.value.party
        val generation=party?.generationKey()
        _state.value=reducePartySession(_state.value,PartySessionEvent.PlayerAttached(context,generation))
    }

    fun unregisterPlayback(attachmentId:String) {
        if (_state.value.playback?.attachmentId!=attachmentId) return
        val party=WatchPartyRepository.uiState.value.party
        _state.value=if(party==null) PartySessionState() else reducePartySession(_state.value,PartySessionEvent.PlayerExited(party.id))
        presenceSessionId=null
        presenceDeviceId=null
        if(party!=null) scope.launch { WatchPartyRepository.setClientLocation("lobby") }
    }

    fun promoteCurrentPlayback() {
        val session=presenceSessionId ?: return
        scope.launch {
            WatchPartyRepository.promotePresence(session).onSuccess {
                val party=WatchPartyRepository.uiState.value.party ?: return@onSuccess
                _state.value=reducePartySession(_state.value,PartySessionEvent.PlayerAttached(_state.value.playback ?: return@onSuccess,party.generationKey()))
                WatchPartyRepository.setClientLocation("player")
            }
        }
    }

    fun restore() {
        _state.value=reducePartySession(_state.value,PartySessionEvent.RestoreStarted)
        scope.launch {
            WatchPartyRepository.restoreActive().onSuccess { party ->
                _state.value=if(party==null) reducePartySession(_state.value,PartySessionEvent.NoActiveParty)
                else reducePartySession(_state.value,PartySessionEvent.Restored(party.generationKey()))
            }
        }
    }

    fun installAuthorizedParty(snapshot:WatchPartyState) {
        WatchPartyRepository.installAuthorizedSnapshot(snapshot)
        _state.value=reducePartySession(_state.value,PartySessionEvent.Restored(snapshot.generationKey()))
    }

    fun leave() {
        scope.launch {
            WatchPartyRepository.leave().onSuccess {
                _state.value=reducePartySession(_state.value,PartySessionEvent.Left())
            }
        }
    }

    fun end() {
        scope.launch {
            WatchPartyRepository.end().onSuccess {
                _state.value=reducePartySession(_state.value,PartySessionEvent.Ended(viewerWasHost=true))
            }
        }
    }

    fun partyEnded(viewerWasHost:Boolean) {
        _state.value=reducePartySession(_state.value,PartySessionEvent.Ended(viewerWasHost))
    }

    fun continueAfterPartyEnd() {
        _state.value=PartySessionState(playback=_state.value.playback)
    }
}

private fun WatchPartyState.generationKey()=PartyGenerationKey(id,contentGeneration,sourceGeneration,authorityEpoch)
