package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Independent reachability of the durable Supabase RPC plane. */
enum class PartyApiHealth { Unknown, Reachable, Unreachable }

/**
 * What has actually been proved about the live timing plane.
 *
 * A successful join is deliberately [SubscribedUnverified]. Stage 0 proved that a channel may
 * report subscribed, and every send may complete successfully, while another member receives no
 * commands, ticks, or clock traffic at all.
 */
enum class PartyRealtimeHealth { Detached, Connecting, SubscribedUnverified, Live, Degraded }

enum class PartyRealtimeSendOutcome { None, Success, Failed, Unavailable }
enum class PartyRealtimeTrafficKind { Peer, Clock }

enum class PartySyncCapability { FullSync, DurableFallback, RealtimeOnly, OfflineLocalPlayback }

data class PartyHealthState(
    val api: PartyApiHealth = PartyApiHealth.Unknown,
    val realtime: PartyRealtimeHealth = PartyRealtimeHealth.Detached,
    val channelInstance: Long = 0L,
    val polling: Boolean = false,
    val lastDurableSuccessAtMs: Long? = null,
    val lastDurableFailureAtMs: Long? = null,
    val lastHeartbeatAtMs: Long? = null,
    val lastRealtimeReceiveAtMs: Long? = null,
    val lastPeerTrafficAtMs: Long? = null,
    val lastClockTrafficAtMs: Long? = null,
    val lastRealtimeSendAtMs: Long? = null,
    val lastRealtimeSendOutcome: PartyRealtimeSendOutcome = PartyRealtimeSendOutcome.None,
) {
    fun capability(): PartySyncCapability = when {
        realtime == PartyRealtimeHealth.Live && api == PartyApiHealth.Reachable -> PartySyncCapability.FullSync
        api == PartyApiHealth.Reachable -> PartySyncCapability.DurableFallback
        realtime == PartyRealtimeHealth.Live -> PartySyncCapability.RealtimeOnly
        else -> PartySyncCapability.OfflineLocalPlayback
    }
}

sealed interface PartyHealthEvent {
    data class PollingChanged(val running: Boolean) : PartyHealthEvent
    data class DurableSucceeded(val atMs: Long, val heartbeat: Boolean = false) : PartyHealthEvent
    data class DurableRejected(val atMs: Long) : PartyHealthEvent
    data class DurableFailed(val atMs: Long) : PartyHealthEvent
    data class RealtimeConnecting(val instance: Long) : PartyHealthEvent
    data class RealtimeSubscribed(val instance: Long) : PartyHealthEvent
    data class RealtimeReceived(
        val instance: Long,
        val atMs: Long,
        val kind: PartyRealtimeTrafficKind = PartyRealtimeTrafficKind.Peer,
    ) : PartyHealthEvent
    data class RealtimeSendCompleted(
        val instance: Long,
        val atMs: Long,
        val outcome: PartyRealtimeSendOutcome,
    ) : PartyHealthEvent
    data class RealtimeDegraded(val instance: Long) : PartyHealthEvent
    data class RealtimeDetached(val instance: Long) : PartyHealthEvent
}

fun reducePartyHealth(state: PartyHealthState, event: PartyHealthEvent): PartyHealthState = when (event) {
    is PartyHealthEvent.PollingChanged -> state.copy(polling = event.running)
    is PartyHealthEvent.DurableSucceeded -> state.copy(
        api = PartyApiHealth.Reachable,
        lastDurableSuccessAtMs = event.atMs,
        lastHeartbeatAtMs = event.atMs.takeIf { event.heartbeat } ?: state.lastHeartbeatAtMs,
    )
    is PartyHealthEvent.DurableFailed -> state.copy(
        api = PartyApiHealth.Unreachable,
        lastDurableFailureAtMs = event.atMs,
    )
    is PartyHealthEvent.DurableRejected -> state.copy(
        api = PartyApiHealth.Reachable,
        lastDurableFailureAtMs = event.atMs,
    )
    is PartyHealthEvent.RealtimeConnecting -> state.copy(
        realtime = PartyRealtimeHealth.Connecting,
        channelInstance = event.instance,
        lastRealtimeReceiveAtMs = null,
        lastPeerTrafficAtMs = null,
        lastClockTrafficAtMs = null,
        lastRealtimeSendAtMs = null,
        lastRealtimeSendOutcome = PartyRealtimeSendOutcome.None,
    )
    is PartyHealthEvent.RealtimeSubscribed -> if (event.instance != state.channelInstance) state else state.copy(
        realtime = PartyRealtimeHealth.SubscribedUnverified,
    )
    is PartyHealthEvent.RealtimeReceived -> if (event.instance != state.channelInstance) state else state.copy(
        realtime = PartyRealtimeHealth.Live,
        lastRealtimeReceiveAtMs = event.atMs,
        lastPeerTrafficAtMs = if (event.kind == PartyRealtimeTrafficKind.Peer) event.atMs else state.lastPeerTrafficAtMs,
        lastClockTrafficAtMs = if (event.kind == PartyRealtimeTrafficKind.Clock) event.atMs else state.lastClockTrafficAtMs,
    )
    is PartyHealthEvent.RealtimeSendCompleted -> if (event.instance != state.channelInstance) state else state.copy(
        lastRealtimeSendAtMs = event.atMs,
        lastRealtimeSendOutcome = event.outcome,
    )
    is PartyHealthEvent.RealtimeDegraded -> if (event.instance != state.channelInstance) state else state.copy(
        realtime = PartyRealtimeHealth.Degraded,
    )
    is PartyHealthEvent.RealtimeDetached -> if (event.instance != state.channelInstance) state else state.copy(
        realtime = PartyRealtimeHealth.Detached,
        lastRealtimeReceiveAtMs = null,
        lastPeerTrafficAtMs = null,
        lastClockTrafficAtMs = null,
    )
}

/** Stage-1 seam around durable IO. The session never needs to know Supabase types. */
interface DurablePartyGateway {
    val snapshots: Flow<WatchPartyState?>
    fun currentParty(): WatchPartyState?
    suspend fun restoreActiveParty(): Result<WatchPartyState?>
    fun installAuthorizedParty(snapshot: WatchPartyState)
    suspend fun publishLocation(location: WatchPartyClientLocation): Result<Unit>
    suspend fun publishReadiness(
        state: SourceResolutionState,
        durationMs: Long? = null,
        sourceGeneration: Int? = null,
        sourceMatch: PartySourceMatch? = null,
    ): Result<Unit>
    suspend fun promotePlaybackPresence(sessionId: String): Result<Unit>
    suspend fun leaveParty(): Result<Unit>
    suspend fun endParty(): Result<Unit>
    fun updatePlaybackTelemetry(telemetry: PartyPlaybackTelemetry?)
}

data class PartyPlaybackTelemetry(
    val generation: PartyGenerationKey,
    val positionMs: Long,
    val capturedAtMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val status: WatchPartyStatus,
)

/** Immutable authority handed to the live adapter; no UI or repository lookup belongs in it. */
data class PartyAuthorityContext(
    val partyId: String,
    val selfProfileId: String,
    val hostProfileId: String,
    val controlMode: WatchPartyControlMode,
    val durableSequence: Long,
    val generation: PartyGenerationKey,
)

fun PartyAuthorityContext.mayControl(profileId: String): Boolean =
    profileId == hostProfileId || controlMode == WatchPartyControlMode.collaborative

interface PartyRealtimeTransport {
    val state: StateFlow<WatchPartySyncState>
    fun updateAuthority(context: PartyAuthorityContext?)
}
