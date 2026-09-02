package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The party's timing plane, carried between clients over the channel that is already open.
 *
 * `WatchPartyRepository` owns the durable half - membership, readiness, who the host is, the
 * snapshot a late joiner needs - and every one of those still goes through Postgres, because none
 * of them is on the latency path and all of them are already correct. What used to be on that path
 * and should never have been is the position and the transport: a pause went host -> PostgREST ->
 * Postgres -> trigger -> Realtime -> guest, two server hops for a button press, and the best
 * measurement of it was 225ms.
 *
 * Here a pause is one hop. The channel is private and RLS-gated to party members, so the ability to
 * send on it is already the ability to be in the party; what a payload *claims* is checked against
 * the durable snapshot, which is the only thing that says who the host is.
 *
 * State this object holds is deliberately not in `WatchPartyUiState`: it changes twice a second and
 * recomposing the lobby at that rate would be the cost of the feature. [state] carries the summary
 * the UI actually wants, and it is written only when something in it changes.
 */
internal object WatchPartySync {

    private val log = Logger.withTag("WatchPartySync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WatchPartySyncState())
    val state: StateFlow<WatchPartySyncState> = _state.asStateFlow()

    /**
     * Barriers this client has to execute, host included.
     *
     * A host does not receive its own broadcast, so [issueCommand] emits here as well as sending -
     * which is what makes the host and every guest run the *same* code against the same instant.
     * Anything else and the two paths drift apart the first time one of them is changed.
     *
     * No replay: a player re-entering the screen must align from a tick, not re-execute a transport
     * action the user took minutes ago.
     */
    private val _commands = MutableSharedFlow<PartyCommand>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<PartyCommand> = _commands.asSharedFlow()

    /**
     * The host's timeline, at the rate it is published.
     *
     * Deliberately not part of [state]: this moves twice a second, and putting it where the lobby
     * collects it would recompose the screen at that rate for the whole film. The player is the
     * only thing that wants every one of them, so it is the only thing that gets them. Replay of
     * one, so a player that attaches between ticks aligns immediately rather than half a second
     * later.
     */
    private val _ticks = MutableSharedFlow<PartyTick>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: SharedFlow<PartyTick> = _ticks.asSharedFlow()

    private var channel: RealtimeChannel? = null
    private var boundPartyId: String? = null
    private var collector: Job? = null
    private var clockJob: Job? = null

    private var clock = PartyClock()
    private var commandLog = PartyCommandLog()
    private var bufferWatch = GuestBufferingWatch()
    private var tick: PartyTick? = null
    private val guestRttMs = mutableMapOf<String, Long>()
    private val guestStatus = mutableMapOf<String, WatchPartyStatus>()
    private val outstandingPings = mutableMapOf<String, Long>()
    private var commandCounter = 0L
    private var peerStatus: WatchPartyStatus? = null

    /** The one place that decides whose clock this is: a host is the clock, so its offset is zero. */
    private fun isHost(): Boolean {
        val ui = WatchPartyRepository.uiState.value
        val party = ui.party ?: return false
        return party.hostProfileId == ui.activeProfileId
    }

    fun partyNowMs(): Long {
        val now = currentEpochMs()
        return if (isHost()) now else clock.partyNowMs(now)
    }

    /** Whether the tight bands are earned: a fresh anchor on a clock this client has actually locked. */
    fun isPrecise(): Boolean {
        val held = tick ?: return false
        if (held.isStale(partyNowMs())) return false
        return isHost() || clock.locked
    }

    /**
     * Whether a party instant means anything on this machine yet.
     *
     * A guest that has exchanged nothing with the host has an offset of zero, which is not an
     * estimate - it is the absence of one, and two wall clocks are routinely seconds apart. A
     * barrier scheduled against it would be either far in the future or long past. The host is
     * always usable, because it *is* the clock.
     */
    fun isClockUsable(): Boolean = isHost() || clock.samples.isNotEmpty()

    fun heldTick(): PartyTick? = tick

    /** How old the held timeline is, for the debug overlay. -1 when there is none. */
    fun tickAgeMs(): Long = tick?.let { partyNowMs() - it.capturedAtPartyMs } ?: -1L

    /** The lead a barrier is given, sized from the worst round trip anyone has reported. */
    fun barrierLeadMs(): Long {
        val worstRtt = guestRttMs.values.filter { it >= 0 }.maxOrNull()
            ?: clock.bestRttMs.takeIf { it >= 0 }
            ?: 0L
        return watchPartyBarrierLeadMs(worstRtt / 2)
    }

    /**
     * Members the host is holding the party for, when the party waits for everyone.
     *
     * [GuestBufferingWatch.advance] is where both edges are decided - a stall becoming a hold when
     * the grace runs out, a hold ending when the guest has been playing again for the settle - and
     * neither of those is a message, so it has to be driven from a read rather than from `observe`.
     */
    private fun advanceBufferWatch(): List<String> {
        bufferWatch = bufferWatch.advance(partyNowMs())
        return bufferWatch.holdingProfiles
    }

    fun holdingProfiles(): List<String> = advanceBufferWatch()

    /**
     * Forgets what everyone was doing before the party started playing.
     *
     * A guest reports `buffering` for the whole time it is resolving its own source, which is
     * routinely ten seconds and is not a stall - the readiness gate is what that phase is for. Left
     * in the window it became a hold the instant the gate released, so the party's first act was to
     * play and immediately pause again for somebody who was already ready.
     */
    fun resetStallWatch() {
        if (bufferWatch == GuestBufferingWatch()) return
        bufferWatch = GuestBufferingWatch()
        publishState()
    }

    fun attach(channel: RealtimeChannel, partyId: String) {
        if (boundPartyId == partyId && collector?.isActive == true) return
        detach()
        this.channel = channel
        boundPartyId = partyId
        log.i { "attach party=${partyId.shortId()} role=${if (isHost()) "host" else "guest"}" }
        collector = channel.broadcastFlow<JsonObject>(WatchPartySyncEvent)
            .onEach { payload -> receive(payload) }
            .launchIn(scope)
        clockJob = scope.launch { runClockExchange(partyId) }
    }

    fun detach() {
        collector?.cancel(); collector = null
        clockJob?.cancel(); clockJob = null
        channel = null
        boundPartyId = null
        clock = PartyClock()
        commandLog = PartyCommandLog()
        bufferWatch = GuestBufferingWatch()
        tick = null
        _ticks.resetReplayCache()
        guestRttMs.clear()
        guestStatus.clear()
        outstandingPings.clear()
        commandCounter = 0
        peerStatus = null
        _state.value = WatchPartySyncState()
    }

    /**
     * The host's position, paired with the instant it was read.
     *
     * [capturedAtPartyMs] is the argument that matters and it is the caller's to get right: it has
     * to be stamped where the position was *sampled*, not where the message was built, or this
     * re-introduces the very bias the tick exists to remove.
     */
    suspend fun publishTick(
        status: WatchPartyStatus,
        positionMs: Long,
        capturedAtPartyMs: Long,
        playbackSpeed: Float,
        durationMs: Long,
    ) {
        val party = WatchPartyRepository.uiState.value.party ?: return
        val profileId = WatchPartyRepository.uiState.value.activeProfileId ?: return
        val next = PartyTick(
            partyId = party.id,
            contentGeneration = party.contentGeneration,
            sequence = party.sequence,
            status = status,
            positionMs = positionMs,
            capturedAtPartyMs = capturedAtPartyMs,
            playbackSpeed = playbackSpeed,
            durationMs = durationMs,
        )
        tick = next
        _ticks.tryEmit(next)
        publishState()
        send(PartyTickMessage(fromProfileId = profileId, tick = next))
    }

    /**
     * Broadcasts a transport action and applies it here through the same path every guest uses.
     *
     * Returns null when this client may not control the party, so a caller cannot half-issue one.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun issueCommand(
        kind: PartyCommandKind,
        startPositionMs: Long,
        startAtPartyMs: Long,
        playbackSpeed: Float,
        playAfter: Boolean = true,
    ): PartyCommand? {
        val ui = WatchPartyRepository.uiState.value
        val party = ui.party ?: return null
        val profileId = ui.activeProfileId ?: return null
        if (!mayControl(profileId, party)) return null
        commandCounter += 1
        val command = PartyCommand(
            commandId = Uuid.random().toString(),
            kind = kind,
            issuedByProfileId = profileId,
            counter = commandCounter,
            contentGeneration = party.contentGeneration,
            startPositionMs = startPositionMs,
            startAtPartyMs = startAtPartyMs,
            playbackSpeed = playbackSpeed,
            playAfter = playAfter,
        )
        log.i {
            "issue party=${party.id.shortId()} kind=$kind posMs=$startPositionMs " +
                "startAtMs=$startAtPartyMs leadMs=${startAtPartyMs - partyNowMs()} n=$commandCounter " +
                "playAfter=$playAfter"
        }
        commandLog = commandLog.record(command)
        send(PartyCommandMessage(partyId = party.id, command = command))
        _commands.tryEmit(command)
        return command
    }

    /** What this client is doing, for a host deciding whether to wait for it. */
    suspend fun publishPeerStatus(status: WatchPartyStatus) {
        val ui = WatchPartyRepository.uiState.value
        val party = ui.party ?: return
        val profileId = ui.activeProfileId ?: return
        if (profileId == party.hostProfileId) return
        // Only when it changes: the clock exchange re-sends the held one for liveness, and logging
        // every one of those would bury the transitions that decide whether the host holds.
        if (peerStatus != status) {
            log.i { "peer publish party=${party.id.shortId()} status=$status" }
        }
        peerStatus = status
        send(
            PartyPeerStatusMessage(
                partyId = party.id,
                fromProfileId = profileId,
                status = status,
                atPartyMs = partyNowMs(),
                rttMs = clock.bestRttMs,
            ),
        )
    }

    private suspend fun send(message: PartySyncMessage) {
        val live = channel ?: return
        // A send that throws is a socket that has gone away, and the poll underneath this is what
        // covers that. Failing loudly here would put a banner on every transient reconnect.
        runCatching { live.broadcast(WatchPartySyncEvent, encodePartySyncMessage(message)) }
            .onFailure { cause -> log.d { "send failed kind=${message::class.simpleName} cause=${cause.message}" } }
    }

    private fun receive(payload: JsonObject) {
        // Null is every kind of "this build cannot act on it": a newer protocol, an unknown type, a
        // field an older sender did not write. All of them mean fall back, none of them mean guess.
        val message = decodePartySyncMessage(payload) ?: return
        val ui = WatchPartyRepository.uiState.value
        val party = ui.party ?: return
        val self = ui.activeProfileId ?: return
        if (message.partyId != party.id) return
        if (message.fromProfileId == self) return
        when (message) {
            is PartyClockPingMessage -> if (isHost()) scope.launch { answerPing(message, party.id, self) }
            is PartyClockPongMessage -> acceptPong(message, party.hostProfileId, self)
            is PartyTickMessage -> acceptTick(message, party.hostProfileId)
            is PartyCommandMessage -> acceptCommand(message, party)
            is PartyPeerStatusMessage -> acceptPeerStatus(message)
        }
    }

    private suspend fun answerPing(ping: PartyClockPingMessage, partyId: String, self: String) {
        send(
            PartyClockPongMessage(
                partyId = partyId,
                fromProfileId = self,
                toProfileId = ping.fromProfileId,
                exchangeId = ping.exchangeId,
                sentAtMs = ping.sentAtMs,
                // The host is the clock, so this is the whole of what the exchange is for.
                hostAtMs = currentEpochMs(),
            ),
        )
    }

    private fun acceptPong(pong: PartyClockPongMessage, hostProfileId: String, self: String) {
        if (pong.toProfileId != self) return
        // Only the host answers, and only for an exchange this client started: `t0` is echoed
        // rather than remembered, so without this a peer could hand us any offset it liked.
        if (pong.fromProfileId != hostProfileId) return
        if (outstandingPings.remove(pong.exchangeId) == null) return
        val sample = partyClockSample(
            sentAtMs = pong.sentAtMs,
            hostAtMs = pong.hostAtMs,
            receivedAtMs = currentEpochMs(),
        )
        val before = clock
        clock = clock.accept(sample)
        if (before.locked != clock.locked || absDelta(before.offsetMs, clock.offsetMs) >= WatchPartyClockSlewLimitMs) {
            log.i {
                "clock offsetMs=${clock.offsetMs} rttMs=${sample.rttMs} bestRttMs=${clock.bestRttMs} " +
                    "locked=${clock.locked} samples=${clock.samples.size}"
            }
        }
        publishState()
    }

    private fun acceptTick(message: PartyTickMessage, hostProfileId: String) {
        // Only the host publishes a timeline. A payload cannot promote itself: the durable snapshot
        // is the only thing that says who the host is.
        if (message.fromProfileId != hostProfileId) return
        val next = message.tick
        val held = tick
        if (held != null && next.contentGeneration != held.contentGeneration) {
            // Content moved under us. The tick cannot say what to, so ask the thing that can.
            WatchPartyRepository.requestRefresh()
        }
        if (!next.supersedes(held)) return
        tick = next
        _ticks.tryEmit(next)
        publishState()
    }

    private fun acceptCommand(message: PartyCommandMessage, party: WatchPartyState) {
        val command = message.command
        if (!mayControl(command.issuedByProfileId, party)) return
        if (command.contentGeneration != party.contentGeneration) {
            WatchPartyRepository.requestRefresh()
            return
        }
        if (!commandLog.accepts(command)) return
        commandLog = commandLog.record(command)
        log.i {
            "command party=${party.id.shortId()} kind=${command.kind} posMs=${command.startPositionMs} " +
                "inMs=${command.startAtPartyMs - partyNowMs()} from=${command.issuedByProfileId.shortId()}"
        }
        _commands.tryEmit(command)
    }

    private fun acceptPeerStatus(message: PartyPeerStatusMessage) {
        if (!isHost()) return
        if (message.rttMs >= 0) guestRttMs[message.fromProfileId] = message.rttMs
        val before = advanceBufferWatch()
        bufferWatch = bufferWatch.observe(
            profileId = message.fromProfileId,
            status = message.status,
            partyNowMs = partyNowMs(),
        )
        val after = advanceBufferWatch()
        // The decisive input to the whole "wait for everyone" behaviour, and it was invisible: the
        // 2026-09-02 run showed the host pausing for a guest with nothing in either log saying what
        // the guest had reported. Logged on the guest's *transitions* rather than per message - the
        // clock exchange re-sends the held status for liveness, and a line each would bury them.
        if (guestStatus.put(message.fromProfileId, message.status) != message.status || before != after) {
            log.i {
                "peer status from=${message.fromProfileId.shortId()} status=${message.status} " +
                    "rttMs=${message.rttMs} holding=[${after.joinToString { it.shortId() }}]"
            }
        }
        if (before != after) publishState()
    }

    /**
     * Keeps the estimate current, and keeps this client's own status current with it.
     *
     * A guest's status rides the same loop so that liveness costs nothing extra; a *change* of
     * status is published the moment it happens, from the player, because the host's grace for a
     * stalled guest is shorter than this interval.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun runClockExchange(partyId: String) {
        while (true) {
            if (isHost()) {
                // The host is the clock. Nothing to measure, and the pongs it owes are answered
                // from the collector rather than from here.
                delay(WatchPartyClockPingIntervalMs)
                continue
            }
            val ui = WatchPartyRepository.uiState.value
            val profileId = ui.activeProfileId
            if (ui.party?.id != partyId || profileId == null) return
            val exchangeId = Uuid.random().toString()
            val sentAt = currentEpochMs()
            outstandingPings[exchangeId] = sentAt
            // An exchange that is never answered would otherwise accumulate forever on a host that
            // is on an older build, which is exactly the case this has to survive.
            outstandingPings.entries.removeAll { (_, at) -> sentAt - at > WatchPartyClockStaleMs }
            send(PartyClockPingMessage(partyId, profileId, exchangeId, sentAt))
            peerStatus?.let { publishPeerStatus(it) }
            delay(watchPartyClockPingDelayMs(clock.samples.size))
        }
    }

    private fun publishState() {
        val held = tick
        _state.value = WatchPartySyncState(
            clockLocked = isHost() || clock.locked,
            clockOffsetMs = if (isHost()) 0L else clock.offsetMs,
            bestRttMs = clock.bestRttMs,
            tickStatus = held?.status,
            holdingProfiles = advanceBufferWatch(),
        )
    }

    private fun mayControl(profileId: String, party: WatchPartyState): Boolean =
        profileId == party.hostProfileId || party.controlMode == WatchPartyControlMode.collaborative

    private fun absDelta(a: Long, b: Long): Long = if (a > b) a - b else b - a
}

/**
 * The summary of the timing plane that the UI and the debug overlay want.
 *
 * Written only when something in it changes, so a tick twice a second does not recompose anything.
 */
data class WatchPartySyncState(
    val clockLocked: Boolean = false,
    val clockOffsetMs: Long = 0L,
    val bestRttMs: Long = -1L,
    val tickStatus: WatchPartyStatus? = null,
    val holdingProfiles: List<String> = emptyList(),
)
