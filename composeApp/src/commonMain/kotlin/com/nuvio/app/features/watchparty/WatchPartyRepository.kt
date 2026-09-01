package com.nuvio.app.features.watchparty

import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WatchPartyUiState(
    val activeProfileId: String? = null,
    val party: WatchPartyState? = null,
    // The host's own invite code. Only the last four characters are stored server-side, so a code
    // that is not held here is gone for good; it lived in the lobby composition and vanished the
    // moment the host navigated away.
    val inviteCode: String? = null,
    val connection: PartyConnectionState = PartyConnectionState.disconnected,
    val serverClockOffsetMs: Long = 0,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
)

object WatchPartyRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WatchPartyUiState())
    val uiState: StateFlow<WatchPartyUiState> = _uiState.asStateFlow()
    private var channel: RealtimeChannel? = null
    private var collector: Job? = null
    private var pollJob: Job? = null

    fun setActiveProfile(profileId: String?) {
        if (_uiState.value.activeProfileId == profileId) return
        scope.launch { if (_uiState.value.party != null) leave() else closeChannel() }
        _uiState.value = WatchPartyUiState(activeProfileId = profileId)
    }

    suspend fun create(
        content: PartyContent,
        sourceFingerprint: SourceFingerprint? = null,
        qualityIntent: JsonObject? = null,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.host_only,
    ): Result<String> = call {
        val profileId = requireProfile()
        val code = generateInviteCode()
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_create", buildJsonObject {
            put("p_host_profile_id", profileId); put("p_invite_code", code)
            put("p_content", json.encodeToJsonElement(content))
            sourceFingerprint?.let { put("p_source_fingerprint", json.encodeToJsonElement(it)) }
            qualityIntent?.let { put("p_quality_intent", it) }
            put("p_control_mode", controlMode.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
        _uiState.value = _uiState.value.copy(inviteCode = code)
        code
    }

    suspend fun join(partyId: String? = null, inviteCode: String? = null): Result<Unit> = call {
        _uiState.value = _uiState.value.copy(inviteCode = null)
        require(partyId != null || !inviteCode.isNullOrBlank()) { "Party ID or invite code required" }
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_join", buildJsonObject {
            put("p_profile_id", requireProfile())
            partyId?.let { put("p_party_id", it) }
            inviteCode?.let { put("p_invite_code", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun invite(friendProfileId: String): Result<Unit> = partyCall("party_invite_friend") {
        put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_receiver_profile_id", friendProfileId)
    }

    suspend fun updateReady(state: SourceResolutionState, durationMs: Long? = null, error: String? = null): Result<Unit> = call {
        val party = requireParty()
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_ready", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", requireProfile()); put("p_ready_state", state.name)
            durationMs?.let { put("p_duration_ms", it) }; error?.let { put("p_error", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun submit(command: WatchPartyCommand): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_submit_command", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_command_id", command.commandId)
            put("p_command_type", command.type); put("p_payload", buildJsonObject {
                command.positionMs?.let { put("position_ms", it) }; command.playbackSpeed?.let { put("playback_speed", it) }
            })
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun play(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "play", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun pause(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "pause", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun seek(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "seek", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun setSpeed(speed: Float) = submit(WatchPartyCommand(Uuid.random().toString(), "speed", playbackSpeed = speed))

    suspend fun heartbeat(positionMs: Long, durationMs: Long, speed: Float, status: WatchPartyStatus): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_position_ms", positionMs)
            put("p_duration_ms", durationMs); put("p_playback_speed", speed); put("p_status", status.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun changeContent(content: PartyContent, fingerprint: SourceFingerprint?, qualityIntent: JsonObject? = null): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_change_content", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_content", json.encodeToJsonElement(content))
            fingerprint?.let { put("p_source_fingerprint", json.encodeToJsonElement(it)) }; qualityIntent?.let { put("p_quality_intent", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun setControlMode(mode: WatchPartyControlMode): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_control_mode", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_mode", mode.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun refresh(): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_snapshot", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile())
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun measureClockOffset(): Result<Long> = call {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        repeat(3) {
            val started = currentEpochMs()
            val serverIso = ZSupabaseProvider.client.postgrest.rpc("party_clock").decodeAs<String>()
            val ended = currentEpochMs()
            val server = parseIsoEpochMs(serverIso) ?: return@repeat
            val rtt = ended - started
            if (rtt < bestRtt) { bestRtt = rtt; bestOffset = server - ((started + ended) / 2) }
        }
        _uiState.value = _uiState.value.copy(serverClockOffsetMs = bestOffset)
        bestOffset
    }

    suspend fun claimHostAfterGrace(): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_claim_or_transfer_host", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_requester_profile_id", requireProfile())
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun end(): Result<Unit> = call {
        val party = requireParty()
        ZSupabaseProvider.client.postgrest.rpc("party_end", buildJsonObject { put("p_party_id", party.id); put("p_host_profile_id", requireProfile()) })
        stopPolling(); closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = _uiState.value.activeProfileId)
    }

    suspend fun leave(): Result<Unit> = runCatching {
        val party = _uiState.value.party
        val profile = _uiState.value.activeProfileId
        if (party != null && profile != null) ZSupabaseProvider.client.postgrest.rpc("party_leave", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", profile)
        })
        stopPolling(); closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = profile)
    }

    private suspend fun installSnapshot(snapshot: WatchPartyState, reopenChannel: Boolean = true) {
        _uiState.value = _uiState.value.copy(party = snapshot, isWorking = false, errorMessage = null)
        if (reopenChannel && channel?.topic != "realtime:party:${snapshot.id}") openChannel(snapshot.id)
        startPolling()
    }

    /**
     * Polls the snapshot while a party is held.
     *
     * Party state reached clients only through the realtime broadcast, so a member whose channel was
     * not working never learned anything had changed. When the host started, they simply stayed in
     * the lobby while everyone else moved on, with nothing to indicate why.
     *
     * Realtime stays the fast path; this is the floor beneath it. `party_snapshot` is the same call
     * the screen already makes, and it is cheap enough at this interval to be worth never being
     * wrong for longer than it.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(WatchPartySnapshotIntervalMs)
                val partyId = _uiState.value.party?.id ?: break
                val profileId = _uiState.value.activeProfileId ?: break
                // Deliberately not routed through call(): a background poll must not flip the
                // working flag or overwrite an error the user is still reading.
                runCatching {
                    val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_snapshot", buildJsonObject {
                        put("p_party_id", partyId); put("p_profile_id", profileId)
                    }).decodeAs<WatchPartyState>()
                    installSnapshot(snapshot, reopenChannel = false)
                }
            }
            pollJob = null
        }
    }

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
    }

    private suspend fun openChannel(partyId: String) {
        // The party topic is a private channel gated by RLS on realtime.messages, so the socket must
        // carry the Z token rather than the publishable key.
        val profileId = _uiState.value.activeProfileId
        if (profileId != null && !ZSessionBridge.ensureSession(profileId)) {
            _uiState.value = _uiState.value.copy(
                connection = PartyConnectionState.disconnected,
                errorMessage = ZSessionBridge.lastFailure,
            )
            return
        }
        closeChannel()
        _uiState.value = _uiState.value.copy(connection = PartyConnectionState.reconnecting)

        // `reconnecting` was set before the attempt and cleared only on success, so anything thrown
        // below left the lobby reporting "Reconnecting" forever with no way to tell why.
        //
        // Realtime is an accelerator, not the source of truth: every snapshot still comes from an
        // RPC, so a party whose channel will not open is degraded rather than broken. Say so, and
        // carry on.
        val opened = runCatching {
            ZSupabaseProvider.client.realtime.setAuth()
            val next = ZSupabaseProvider.client.channel("party:$partyId") { isPrivate = true; presence { key = requireProfile() } }
            collector = next.broadcastFlow<JsonObject>("state").onEach { refresh() }.launchIn(scope)
            next.subscribe(blockUntilSubscribed = true)
            next.track(buildJsonObject { put("profile_id", requireProfile()) })
            channel = next
        }

        opened.onFailure { cause ->
            closeChannel()
            _uiState.value = _uiState.value.copy(
                connection = PartyConnectionState.disconnected,
                errorMessage = "Live sync unavailable: ${cause.message ?: cause::class.simpleName}",
            )
        }.onSuccess {
            _uiState.value = _uiState.value.copy(connection = PartyConnectionState.connected, errorMessage = null)
        }

        refresh(); measureClockOffset()
    }

    private suspend fun closeChannel() {
        collector?.cancel(); collector = null
        channel?.let { runCatching { ZSupabaseProvider.client.realtime.removeChannel(it) } }
        channel = null
    }

    private suspend fun partyCall(name: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> = call {
        ZSupabaseProvider.client.postgrest.rpc(name, buildJsonObject(params)); refresh()
    }
    private suspend fun <T> call(block: suspend () -> T): Result<T> {
        _uiState.value = _uiState.value.copy(isWorking = true, errorMessage = null)
        val profileId = _uiState.value.activeProfileId
        if (profileId != null && !ZSessionBridge.ensureSession(profileId)) {
            _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = "Nuvio Z is unavailable")
            return Result.failure(IllegalStateException("Nuvio Z session unavailable"))
        }
        var result = runCatching { block() }
        if (result.isFailure && profileId != null) {
            // A rejected Z token is the expected failure once one expires; the official session is
            // still live, so re-exchanging is the recovery. Retried once, so a real server error is
            // reported rather than looped on. Party control actions are latency-sensitive, which is
            // why this recovers in place instead of surfacing a reconnect to the user.
            ZSessionBridge.invalidate()
            if (ZSessionBridge.ensureSession(profileId)) result = runCatching { block() }
        }
        return result.onSuccess { _uiState.value = _uiState.value.copy(isWorking = false) }
            .onFailure { _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = it.message) }
    }
    private fun requireProfile(): String = requireNotNull(_uiState.value.activeProfileId) { "No active profile" }
    private fun requireParty(): WatchPartyState = requireNotNull(_uiState.value.party) { "No active party" }
}

/**
 * Invite codes are a bearer credential: anyone holding one can join the party. They therefore come
 * from [Uuid.random], which is specified to draw from the platform's secure generator on every
 * target, rather than from [kotlin.random.Random], whose sequence is predictable once observed.
 * The alphabet is exactly 32 characters, so masking five bits per byte stays uniform.
 */
@OptIn(ExperimentalUuidApi::class)
private fun generateInviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val bytes = Uuid.random().toByteArray()
    return buildString { repeat(12) { index -> append(alphabet[bytes[index].toInt() and 31]) } }
}

private fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun parseIsoEpochMs(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
