package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    /**
     * The transport half of the Watch Together trace.
     *
     * Two clients disagreeing about a party is the whole class of bug here, and it is only ever
     * diagnosable by lining up the two logs side by side - so every line carries the party, the
     * profile and the sequence, and state is logged when it *changes* rather than on every five
     * second poll. `WatchPartyPlayer` carries the other half: what each client decided to do about
     * the state it was given.
     *
     * On desktop this reaches the file written by `DesktopDebugLog`, which needs
     * `-Dnuvio.debugTools=true` (or a debug-channel build). Invite codes are a bearer credential
     * and are never written out in full.
     */
    private val log = Logger.withTag("WatchParty")
    private var lastLoggedState: String? = null
    private var lastLoggedHeartbeatStatus: String? = null
    private var lastLoggedPollFailure: String? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WatchPartyUiState())
    val uiState: StateFlow<WatchPartyUiState> = _uiState.asStateFlow()
    private var channel: RealtimeChannel? = null
    private var collector: Job? = null
    private var pollJob: Job? = null
    private var channelJob: Job? = null
    private var channelPartyId: String? = null
    // Holding a channel is not the same as being subscribed to one: the attempt is held from the
    // moment it exists so a teardown can remove it, so only this says whether it is carrying state.
    private var channelSubscribed = false
    private var clockOffsetPartyId: String? = null

    fun setActiveProfile(profileId: String?) {
        if (_uiState.value.activeProfileId == profileId) return
        log.i { "profile from=${_uiState.value.activeProfileId.shortId()} to=${profileId.shortId()}" }
        lastLoggedState = null
        lastLoggedHeartbeatStatus = null
        lastLoggedPollFailure = null
        scope.launch { if (_uiState.value.party != null) leave() else stopChannel() }
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
        log.i { "create party=${snapshot.id.shortId()} host=${profileId.shortId()} code=****${code.takeLast(4)}" }
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
        log.i {
            "join party=${snapshot.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                "via=${if (partyId != null) "id" else "code"} status=${snapshot.status} members=${snapshot.members.size}"
        }
        installSnapshot(snapshot)
    }

    suspend fun invite(friendProfileId: String): Result<Unit> = partyCall("party_invite_friend") {
        put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_receiver_profile_id", friendProfileId)
    }

    suspend fun updateReady(state: SourceResolutionState, durationMs: Long? = null, error: String? = null): Result<Unit> = call {
        val party = requireParty()
        log.i { "ready party=${party.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} state=$state durationMs=$durationMs error=$error" }
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_ready", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", requireProfile()); put("p_ready_state", state.name)
            durationMs?.let { put("p_duration_ms", it) }; error?.let { put("p_error", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    /**
     * Marks readiness without a caller waiting on the result.
     *
     * Leaving the player has to say so - a member who backed out to the source list is not holding
     * a resolved stream any more, and a host gated on readiness would otherwise wait on a report
     * that is no longer true. The composition that noticed is already going away, so the call
     * cannot run on its scope.
     */
    fun updateReadyDetached(state: SourceResolutionState) {
        if (_uiState.value.party == null) return
        scope.launch { runCatching { updateReady(state) } }
    }

    /**
     * Leaves the lobby without starting the shared clock.
     *
     * Start used to submit `play`, which set the authoritative position running from the instant it
     * was pressed - before the host had so much as opened the source list. Every guest then computed
     * an expected position that had already left their file behind. `buffering` is the honest state
     * for "we have left the lobby and nobody is playing yet": it moves everyone on to pick a source
     * and holds the timeline where it is until a real play command starts it.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun startResolving(): Result<Unit> = submit(
        WatchPartyCommand(Uuid.random().toString(), "buffering", _uiState.value.party?.positionMs ?: 0L),
    )

    suspend fun submit(command: WatchPartyCommand): Result<Unit> = call {
        log.i {
            "command party=${_uiState.value.party?.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                "type=${command.type} positionMs=${command.positionMs} speed=${command.playbackSpeed}"
        }
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
        // Every five seconds forever, so only the transitions are worth a line.
        if (lastLoggedHeartbeatStatus != status.name) {
            lastLoggedHeartbeatStatus = status.name
            log.i {
                "heartbeat party=${_uiState.value.party?.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                    "status=$status positionMs=$positionMs durationMs=$durationMs speed=$speed"
            }
        }
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
        log.i { "clock offsetMs=$bestOffset bestRttMs=${if (bestRtt == Long.MAX_VALUE) -1 else bestRtt}" }
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
        log.i { "end party=${party.id.shortId()} host=${_uiState.value.activeProfileId.shortId()}" }
        ZSupabaseProvider.client.postgrest.rpc("party_end", buildJsonObject { put("p_party_id", party.id); put("p_host_profile_id", requireProfile()) })
        stopPolling(); stopChannel(); clockOffsetPartyId = null
        _uiState.value = WatchPartyUiState(activeProfileId = _uiState.value.activeProfileId)
    }

    suspend fun leave(): Result<Unit> = runCatching {
        val party = _uiState.value.party
        val profile = _uiState.value.activeProfileId
        log.i { "leave party=${party?.id.shortId()} profile=${profile.shortId()}" }
        if (party != null && profile != null) ZSupabaseProvider.client.postgrest.rpc("party_leave", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", profile)
        })
        stopPolling(); stopChannel(); clockOffsetPartyId = null
        _uiState.value = WatchPartyUiState(activeProfileId = profile)
    }

    private fun installSnapshot(snapshot: WatchPartyState, reopenChannel: Boolean = true) {
        // The authoritative state, logged only when it actually moves. This is the line to line up
        // between two machines: same sequence and same state_updated_at means they agree, and a
        // client whose sequence has stopped advancing has lost both realtime and the poll.
        val signature = snapshot.logSignature()
        if (signature != lastLoggedState) {
            lastLoggedState = signature
            log.i { "state viewer=${_uiState.value.activeProfileId.shortId()} $signature" }
        }
        _uiState.value = _uiState.value.copy(party = snapshot, isWorking = false, errorMessage = null)
        // The poll is the floor under this whole feature, so nothing may come before it. Opening the
        // channel used to, and `subscribe(blockUntilSubscribed = true)` never returns for a topic
        // the server refuses - so a channel that could not be authorized left this line unreached
        // for the life of the app. The member kept whatever state they joined with, forever: a
        // lobby that never noticed the party had started.
        startPolling()
        if (reopenChannel) ensureChannel(snapshot.id)
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
                // Drift is measured against the server's clock, so the offset has to be taken for
                // every party - including one whose channel never opens, which is where this used
                // to live. Without it a guest corrects towards this machine's idea of now, and two
                // machines never agree on that to better than a second or two.
                if (clockOffsetPartyId != partyId) {
                    clockOffsetPartyId = partyId
                    runCatching { measureClockOffset() }
                }
                // Realtime is worth another attempt whenever it is down: the party is still live,
                // and the alternative is running the rest of it on this interval. The first attempt
                // belongs to installSnapshot, so this tick is only ever the retry.
                if (!channelSubscribed) ensureChannel(partyId)
                // Deliberately not routed through call(): a background poll must not flip the
                // working flag or overwrite an error the user is still reading.
                runCatching {
                    // `party_heartbeat` with no position is `party_snapshot` plus a liveness stamp:
                    // it refreshes last_seen_at for this member and expires anyone who has stopped
                    // reporting. Only the player used to heartbeat, so a member sitting in the lobby
                    // or on the source list looked disconnected after fifteen seconds - and a host
                    // waiting on them to be ready would give up on them for no reason.
                    val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
                        put("p_party_id", partyId); put("p_profile_id", profileId)
                    }).decodeAs<WatchPartyState>()
                    installSnapshot(snapshot, reopenChannel = false)
                }.onSuccess { lastLoggedPollFailure = null }.onFailure { cause ->
                    // Not routed through call(), and so not logged by it either: a poll failing
                    // every five seconds is the one thing that can strand a member on state that
                    // never moves again, and it used to leave no trace at all. Logged when the
                    // reason changes, not on every tick.
                    val reason = cause.message ?: cause::class.simpleName
                    if (reason != lastLoggedPollFailure) {
                        lastLoggedPollFailure = reason
                        log.w(cause) { "poll failed party=${partyId.shortId()} profile=${profileId.shortId()}" }
                    }
                }
            }
            pollJob = null
        }
    }

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
    }

    /**
     * Opens the party channel without anything waiting on it.
     *
     * Realtime is an accelerator over [startPolling], never a prerequisite for it, and the way to
     * keep that true is to make it structurally impossible for the socket to block a caller: the
     * subscription runs in its own job on the repository scope, so an RPC path that installs a
     * snapshot returns whether or not the channel ever comes up.
     */
    private fun ensureChannel(partyId: String) {
        if (channelSubscribed && channel?.topic == "realtime:party:$partyId") return
        if (channelPartyId == partyId && channelJob?.isActive == true) return
        channelPartyId = partyId
        channelJob?.cancel()
        channelJob = scope.launch { openChannel(partyId) }
    }

    private suspend fun stopChannel() {
        channelJob?.cancel(); channelJob = null; channelPartyId = null
        closeChannel()
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
            // Held before it is subscribed, because a channel dropped on the floor is not idle: the
            // client library goes on retrying its join, and nothing is left holding it to stop.
            channel = next
            collector = next.broadcastFlow<JsonObject>("state").onEach { refresh() }.launchIn(scope)
            // A refused topic never reports itself subscribed - the join is retried in the
            // background and this call simply never returns - so the wait needs a deadline of its
            // own to be a wait at all rather than a coroutine parked for the life of the app.
            withTimeout(WatchPartyChannelSubscribeTimeoutMs) { next.subscribe(blockUntilSubscribed = true) }
            next.track(buildJsonObject { put("profile_id", requireProfile()) })
            channelSubscribed = true
        }

        opened.onFailure { cause ->
            // A timeout is a channel that will not open and is worth saying so. A plain cancellation
            // is this party going away underneath the attempt, and a banner written on the way out
            // would outlive the thing it describes.
            if (cause !is CancellationException || cause is TimeoutCancellationException) {
                log.w { "realtime party=${partyId.shortId()} state=disconnected cause=${cause.message ?: cause::class.simpleName}" }
                closeChannel()
                _uiState.value = _uiState.value.copy(
                    connection = PartyConnectionState.disconnected,
                    errorMessage = "Live sync unavailable: ${cause.message ?: cause::class.simpleName}",
                )
            }
        }.onSuccess {
            log.i { "realtime party=${partyId.shortId()} state=connected" }
            _uiState.value = _uiState.value.copy(connection = PartyConnectionState.connected, errorMessage = null)
            refresh()
        }
    }

    private suspend fun closeChannel() {
        channelSubscribed = false
        collector?.cancel(); collector = null
        // Leaving a channel means sending over the socket that has just failed, so this is bounded
        // for the same reason the subscription is: `leave()` runs through here from a button press,
        // and a party that cannot be left is worse than one that cannot be joined.
        channel?.let {
            runCatching { withTimeout(WatchPartyChannelCloseTimeoutMs) { ZSupabaseProvider.client.realtime.removeChannel(it) } }
        }
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
            .onFailure {
                // Until now a rejected RPC only ever reached the lobby's error line, which the
                // player never shows - so a party that quietly stopped working looked like a party
                // that was working.
                log.w(it) { "rpc failed party=${_uiState.value.party?.id.shortId()} profile=${profileId.shortId()}" }
                _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = it.message)
            }
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

/** UUIDs in full make the trace unreadable; the first eight characters identify a party or profile. */
internal fun String?.shortId(): String = this?.take(8) ?: "-"

private fun WatchPartyState.logSignature(): String = buildString {
    append("party=${id.shortId()} status=$status gen=$contentGeneration seq=$sequence ")
    append("host=${hostProfileId.shortId()} positionMs=$positionMs durationMs=$durationMs ")
    append("speed=$playbackSpeed updatedAt=$stateUpdatedAt mode=$controlMode video=${content.videoId} ")
    append("members=[")
    members.joinTo(this) { member ->
        "${member.profileId.shortId()}:${member.role}:${member.readyState}:${if (member.connected) "up" else "down"}"
    }
    append("]")
}

private fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun parseIsoEpochMs(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
