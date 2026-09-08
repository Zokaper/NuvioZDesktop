package com.nuvio.app.features.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SocialPresenceSessionState(
    val deviceId: String? = null,
    val sessionId: String? = null,
    val effectivePolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
)

/** Process-scoped handle for the current player's per-session join-policy override. */
object SocialPresenceSession {
    private val _state = MutableStateFlow(SocialPresenceSessionState())
    val state = _state.asStateFlow()

    fun attach(deviceId: String, sessionId: String, defaultPolicy: WatchJoinPolicy) {
        val current = _state.value
        _state.value = if (current.deviceId == deviceId && current.sessionId == sessionId) {
            current
        } else {
            SocialPresenceSessionState(deviceId, sessionId, defaultPolicy)
        }
    }

    fun detach(deviceId: String, sessionId: String) {
        val current = _state.value
        if (current.deviceId == deviceId && current.sessionId == sessionId) {
            _state.value = SocialPresenceSessionState()
        }
    }

    suspend fun cyclePolicy(): Result<WatchJoinPolicy> {
        val current = _state.value
        val deviceId = current.deviceId ?: return Result.failure(IllegalStateException("No active presence"))
        val sessionId = current.sessionId ?: return Result.failure(IllegalStateException("No active presence"))
        val next = when (current.effectivePolicy) {
            WatchJoinPolicy.direct -> WatchJoinPolicy.approval
            WatchJoinPolicy.approval -> WatchJoinPolicy.disabled
            WatchJoinPolicy.disabled -> WatchJoinPolicy.direct
        }
        return SocialRepository.setPresenceJoinPolicy(deviceId, sessionId, next).map { next }.onSuccess {
            _state.value = current.copy(effectivePolicy = next)
        }
    }
}
