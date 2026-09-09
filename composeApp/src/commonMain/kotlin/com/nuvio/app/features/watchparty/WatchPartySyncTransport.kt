package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
internal object WatchPartySync : PartyRealtimeTransport {

    private val log = Logger.withTag("WatchPartySync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WatchPartySyncState())
    override val state: StateFlow<WatchPartySyncState> = _state.asStateFlow()

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
    private var stateCollector: Job? = null
    private var statusCollector: Job? = null
    private var clockJob: Job? = null
    private var healthMonitorJob: Job? = null
    private val desiredAuthority = MutableStateFlow<PartyAuthorityContext?>(null)

    private var clock = PartyClock()
    private var commandLog = PartyCommandLog()
    private var bufferWatch = GuestBufferingWatch()
    private var tick: PartyTick? = null
    private val guestRttMs = mutableMapOf<String, Long>()
    private val guestStatus = mutableMapOf<String, WatchPartyStatus>()
    private val guestLastTelemetryAtPartyMs = mutableMapOf<String, Long>()
    private val outstandingPings = mutableMapOf<String, Long>()
    private var commandCounter = 0L
    private var peerStatus: WatchPartyStatus? = null
    private var authority: PartyAuthorityContext? = null
    private var channelInstance: Long = 0L
    private var healthSink: (PartyHealthEvent) -> Unit = {}
    private var refreshRequest: () -> Unit = {}
    private var stateBroadcastSink: (JsonObject) -> Unit = {}
    private var failureSink: (String?) -> Unit = {}
    private var lastValidatedReceiveAtMs: Long? = null

    init {
        scope.launch {
            desiredAuthority.collectLatest { context ->
                if (context == null) closeChannel(clearProtocol = true)
                else maintainChannel(context.partyId, context.selfProfileId)
            }
        }
    }

    fun configure(
        health: (PartyHealthEvent) -> Unit,
        stateBroadcast: (JsonObject) -> Unit,
        refresh: () -> Unit,
        failure: (String?) -> Unit,
    ) {
        healthSink = health
        stateBroadcastSink = stateBroadcast
        refreshRequest = refresh
        failureSink = failure
    }

    override fun updateAuthority(context: PartyAuthorityContext?) {
        val previous = authority
        authority = context
        if (
            previous != null && context != null &&
            previous.partyId == context.partyId &&
            previous.selfProfileId == context.selfProfileId &&
            previous.generation != context.generation
        ) {
            invalidateGenerationState()
        }
        val desired = desiredAuthority.value
        if (desired?.partyId != context?.partyId || desired?.selfProfileId != context?.selfProfileId) {
            desiredAuthority.value = context
        }
    }

    /** The one place that decides whose clock this is: a host is the clock, so its offset is zero. */
    private fun isHost(): Boolean = authority?.let { it.hostProfileId == it.selfProfileId } == true

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
        val before = bufferWatch
        bufferWatch = bufferWatch.advance(partyNowMs())
        val now = partyNowMs()
        (bufferWatch.heldSinceByProfile.keys - before.heldSinceByProfile.keys).forEach { profileId ->
            WatchPartyDiagnostics.hold(
                partyId = boundPartyId,
                profileId = profileId,
                event = "start",
                engineState = guestStatus[profileId],
                telemetryAgeMs = guestLastTelemetryAtPartyMs[profileId]?.let { now - it } ?: -1L,
                holdAgeMs = 0L,
                classification = "genuine-stall-candidate",
            )
        }
        (before.heldSinceByProfile.keys - bufferWatch.heldSinceByProfile.keys).forEach { profileId ->
            WatchPartyDiagnostics.hold(
                partyId = boundPartyId,
                profileId = profileId,
                event = "release",
                engineState = guestStatus[profileId],
                telemetryAgeMs = guestLastTelemetryAtPartyMs[profileId]?.let { now - it } ?: -1L,
                holdAgeMs = before.heldSinceByProfile[profileId]?.let { now - it } ?: -1L,
                classification = if (guestStatus[profileId] == WatchPartyStatus.playing) "recovered" else "telemetry-stale",
            )
        }
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

    private fun attach(
        channel: RealtimeChannel,
        partyId: String,
        channelInstance: Long,
    ) {
        resetProtocolState()
        this.channel = channel
        boundPartyId = partyId
        this.channelInstance = channelInstance
        WatchPartyDiagnostics.channelAttached(partyId)
        log.i { "attach party=${partyId.shortId()} role=${if (isHost()) "host" else "guest"}" }
        collector = channel.broadcastFlow<JsonObject>(WatchPartySyncEvent)
            .onEach { payload -> receive(payload) }
            .launchIn(scope)
        stateCollector = channel.broadcastFlow<JsonObject>("state")
            .onEach(stateBroadcastSink)
            .launchIn(scope)
    }

    private fun resetProtocolState() {
        WatchPartyDiagnostics.channelDetached(boundPartyId)
        collector?.cancel(); collector = null
        stateCollector?.cancel(); stateCollector = null
        statusCollector?.cancel(); statusCollector = null
        clockJob?.cancel(); clockJob = null
        healthMonitorJob?.cancel(); healthMonitorJob = null
        boundPartyId = null
        clock = PartyClock()
        commandLog = PartyCommandLog()
        bufferWatch = GuestBufferingWatch()
        tick = null
        _ticks.resetReplayCache()
        guestRttMs.clear()
        guestStatus.clear()
        guestLastTelemetryAtPartyMs.clear()
        outstandingPings.clear()
        commandCounter = 0
        peerStatus = null
        lastValidatedReceiveAtMs = null
        _state.value = WatchPartySyncState()
    }

    /** Clears state scoped to the durable identity tuple without replacing a healthy channel. */
    private fun invalidateGenerationState() {
        commandLog = PartyCommandLog()
        bufferWatch = GuestBufferingWatch()
        tick = null
        _ticks.resetReplayCache()
        guestRttMs.clear()
        guestStatus.clear()
        guestLastTelemetryAtPartyMs.clear()
        outstandingPings.clear()
        commandCounter = 0
        peerStatus = null
        publishState()
    }

    private suspend fun maintainChannel(partyId: String, profileId: String) {
        var retryMs = 500L
        while (desiredAuthority.value?.let { it.partyId == partyId && it.selfProfileId == profileId } == true) {
            try {
                openChannel(partyId, profileId)
                retryMs = 500L
                val live = channel ?: continue
                live.status.drop(1).first { it == RealtimeChannel.Status.UNSUBSCRIBED }
                healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
                WatchPartyDiagnostics.transport(
                    "subscribe-failed", partyId, realtime = "disconnected",
                    detail = failure::class.simpleName,
                )
                log.w { "realtime party=${partyId.shortId()} state=disconnected cause=${failure.message ?: failure::class.simpleName}" }
                failureSink("Live sync unavailable: ${failure.message ?: failure::class.simpleName}")
            } finally {
                closeChannel(clearProtocol = true, detached = false)
            }
            healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(5_000L)
        }
    }

    private suspend fun openChannel(partyId: String, profileId: String) {
        WatchPartyDiagnostics.transport("subscribe-start", partyId, realtime = "subscribing")
        if (!ZSessionBridge.ensureSession(profileId)) {
            throw IllegalStateException(ZSessionBridge.lastFailure ?: "Nuvio Z session unavailable")
        }
        ZSupabaseProvider.client.realtime.setAuth()
        channelInstance += 1
        val openingInstance = channelInstance
        healthSink(PartyHealthEvent.RealtimeConnecting(openingInstance))
        val next = ZSupabaseProvider.client.channel("party:$partyId") {
            isPrivate = true
            broadcast { acknowledgeBroadcasts = true }
            presence { key = profileId }
        }
        attach(next, partyId, openingInstance)
        statusCollector = next.status.onEach { status ->
            when (status) {
                RealtimeChannel.Status.SUBSCRIBING -> healthSink(PartyHealthEvent.RealtimeConnecting(openingInstance))
                RealtimeChannel.Status.SUBSCRIBED -> healthSink(PartyHealthEvent.RealtimeSubscribed(openingInstance))
                RealtimeChannel.Status.UNSUBSCRIBING,
                RealtimeChannel.Status.UNSUBSCRIBED,
                -> if (desiredAuthority.value?.partyId == partyId) {
                    healthSink(PartyHealthEvent.RealtimeDegraded(openingInstance))
                }
            }
        }.launchIn(scope)
        withTimeout(WatchPartyChannelSubscribeTimeoutMs) { next.subscribe(blockUntilSubscribed = true) }
        next.track(buildJsonObject { put("profile_id", profileId) })
        clockJob = scope.launch { runClockExchange(partyId) }
        healthMonitorJob = scope.launch {
            while (true) {
                delay(1_000)
                val lastReceive = lastValidatedReceiveAtMs ?: continue
                if (currentEpochMs() - lastReceive > WatchPartyClockStaleMs) {
                    healthSink(PartyHealthEvent.RealtimeDegraded(openingInstance))
                }
            }
        }
        failureSink(null)
        WatchPartyDiagnostics.transport("subscribe-complete", partyId, realtime = "subscribed")
        log.i { "realtime party=${partyId.shortId()} state=subscribed-unverified" }
        healthSink(PartyHealthEvent.RealtimeSubscribed(openingInstance))
        refreshRequest()
    }

    private suspend fun closeChannel(clearProtocol: Boolean, detached: Boolean = true) {
        val closing = channel
        val closingPartyId = boundPartyId
        val closingInstance = channelInstance
        channel = null
        if (clearProtocol) resetProtocolState()
        if (closing != null) {
            runCatching {
                withTimeout(WatchPartyChannelCloseTimeoutMs) {
                    ZSupabaseProvider.client.realtime.removeChannel(closing)
                }
            }
        }
        if (detached) healthSink(PartyHealthEvent.RealtimeDetached(closingInstance))
        WatchPartyDiagnostics.transport("channel-closed", closingPartyId, realtime = "disconnected")
    }

    /**
     * The host's position, paired with the instant it was read.
     *
     * [capturedAtPartyMs] is the argument that matters and it is the caller's to get right: it has
     * to be stamped where the position was *sampled*, not where the message was built, or this
     * re-introduces the very bias the tick exists to remove.
     */
    fun publishTick(
        status: WatchPartyStatus,
        positionMs: Long,
        capturedAtPartyMs: Long,
        playbackSpeed: Float,
        durationMs: Long,
    ) {
        val context = authority ?: return
        val generation = context.generation
        val next = PartyTick(
            partyId = context.partyId,
            contentGeneration = generation.contentGeneration,
            sequence = context.durableSequence,
            status = status,
            positionMs = positionMs,
            capturedAtPartyMs = capturedAtPartyMs,
            playbackSpeed = playbackSpeed,
            durationMs = durationMs,
            sourceGeneration = generation.sourceGeneration,
            authorityEpoch = generation.authorityEpoch,
        )
        tick = next
        _ticks.tryEmit(next)
        publishState()
        scope.launch { send(PartyTickMessage(fromProfileId = context.selfProfileId, tick = next)) }
    }

    /**
     * Broadcasts a transport action and applies it here through the same path every guest uses.
     *
     * Returns null when this client may not control the party, so a caller cannot half-issue one.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun issueCommand(
        kind: PartyCommandKind,
        startPositionMs: Long,
        startAtPartyMs: Long,
        playbackSpeed: Float,
        playAfter: Boolean = true,
        diagnosticInputId: String? = null,
    ): PartyCommand? {
        val context = authority ?: run {
            diagnosticInputId?.let { WatchPartyDiagnostics.rejected(it, kind, null, null, "authority-missing") }
            return null
        }
        val profileId = context.selfProfileId
        if (!context.mayControl(profileId)) {
            diagnosticInputId?.let { WatchPartyDiagnostics.rejected(it, kind, null, profileId, "permission") }
            return null
        }
        val generation = context.generation
        commandCounter += 1
        val command = PartyCommand(
            commandId = Uuid.random().toString(),
            kind = kind,
            issuedByProfileId = profileId,
            counter = commandCounter,
            contentGeneration = generation.contentGeneration,
            startPositionMs = startPositionMs,
            startAtPartyMs = startAtPartyMs,
            playbackSpeed = playbackSpeed,
            playAfter = playAfter,
            sourceGeneration = generation.sourceGeneration,
            authorityEpoch = generation.authorityEpoch,
        )
        log.i {
            "issue party=${context.partyId.shortId()} kind=$kind posMs=$startPositionMs " +
                "startAtMs=$startAtPartyMs leadMs=${startAtPartyMs - partyNowMs()} n=$commandCounter " +
                "playAfter=$playAfter"
        }
        commandLog = commandLog.record(command)
        WatchPartyDiagnostics.accepted(diagnosticInputId, command, context.partyId)
        dispatchPartyCommandLocallyFirst(
            command = command,
            emitDirective = { _commands.tryEmit(it) },
            enqueueSend = {
                scope.launch { send(PartyCommandMessage(partyId = context.partyId, command = it)) }
            },
        )
        return command
    }

    /** What this client is doing, for a host deciding whether to wait for it. */
    fun publishPeerStatus(status: WatchPartyStatus) {
        val context = authority ?: return
        val generation = context.generation
        val profileId = context.selfProfileId
        if (profileId == context.hostProfileId) return
        // Only when it changes: the clock exchange re-sends the held one for liveness, and logging
        // every one of those would bury the transitions that decide whether the host holds.
        if (peerStatus != status) {
            log.i { "peer publish party=${context.partyId.shortId()} status=$status" }
        }
        peerStatus = status
        scope.launch {
            send(PartyPeerStatusMessage(
                partyId = context.partyId,
                fromProfileId = profileId,
                status = status,
                atPartyMs = partyNowMs(),
                rttMs = clock.bestRttMs,
                contentGeneration = generation.contentGeneration,
                sourceGeneration = generation.sourceGeneration,
                authorityEpoch = generation.authorityEpoch,
            ))
        }
    }

    private suspend fun send(message: PartySyncMessage) {
        val commandMessage = message as? PartyCommandMessage
        val startedAt = currentEpochMs()
        val live = channel
        val sendInstance = channelInstance
        if (live == null) {
            healthSink(
                PartyHealthEvent.RealtimeSendCompleted(
                    sendInstance,
                    startedAt,
                    PartyRealtimeSendOutcome.Unavailable,
                ),
            )
            commandMessage?.let {
                WatchPartyDiagnostics.send(it.command, it.partyId, startedAt, outcome = "unavailable")
            }
            return
        }
        // A send that throws is a socket that has gone away, and the poll underneath this is what
        // covers that. Failing loudly here would put a banner on every transient reconnect.
        runCatching { live.broadcast(WatchPartySyncEvent, encodePartySyncMessage(message)) }
            .onSuccess {
                healthSink(
                    PartyHealthEvent.RealtimeSendCompleted(
                        sendInstance,
                        currentEpochMs(),
                        PartyRealtimeSendOutcome.Success,
                    ),
                )
                commandMessage?.let { WatchPartyDiagnostics.send(it.command, it.partyId, startedAt, outcome = "success") }
            }
            .onFailure { cause ->
                healthSink(
                    PartyHealthEvent.RealtimeSendCompleted(
                        sendInstance,
                        currentEpochMs(),
                        PartyRealtimeSendOutcome.Failed,
                    ),
                )
                commandMessage?.let { WatchPartyDiagnostics.send(it.command, it.partyId, startedAt, outcome = "failed") }
                log.d { "send failed kind=${message::class.simpleName} cause=${cause.message}" }
            }
    }

    private fun receive(payload: JsonObject) {
        // Null is every kind of "this build cannot act on it": a newer protocol, an unknown type, a
        // field an older sender did not write. All of them mean fall back, none of them mean guess.
        val message = decodePartySyncMessage(payload) ?: return
        val context = authority ?: return
        val generation = context.generation
        val self = context.selfProfileId
        if (message.partyId != context.partyId) return
        if (message.fromProfileId == self) return
        // Subscription and successful sends proved nothing in Stage 0. Only an authenticated,
        // party-matching message from another member establishes live peer delivery.
        val trafficKind = if (message is PartyClockPingMessage || message is PartyClockPongMessage) {
            PartyRealtimeTrafficKind.Clock
        } else {
            PartyRealtimeTrafficKind.Peer
        }
        val receivedAt = currentEpochMs()
        lastValidatedReceiveAtMs = receivedAt
        healthSink(PartyHealthEvent.RealtimeReceived(channelInstance, receivedAt, trafficKind))
        if (
            message.contentGeneration != generation.contentGeneration ||
            message.sourceGeneration != generation.sourceGeneration ||
            message.authorityEpoch != generation.authorityEpoch
        ) {
            refreshRequest()
            return
        }
        when (message) {
            is PartyClockPingMessage -> if (isHost()) scope.launch { answerPing(message, context) }
            is PartyClockPongMessage -> acceptPong(message, context.hostProfileId, self)
            is PartyTickMessage -> acceptTick(message, context)
            is PartyCommandMessage -> acceptCommand(message, context)
            is PartyPeerStatusMessage -> acceptPeerStatus(message)
        }
    }

    private suspend fun answerPing(ping: PartyClockPingMessage, context: PartyAuthorityContext) {
        val generation = context.generation
        send(
            PartyClockPongMessage(
                partyId = context.partyId,
                fromProfileId = context.selfProfileId,
                toProfileId = ping.fromProfileId,
                exchangeId = ping.exchangeId,
                sentAtMs = ping.sentAtMs,
                // The host is the clock, so this is the whole of what the exchange is for.
                hostAtMs = currentEpochMs(),
                contentGeneration = generation.contentGeneration,
                sourceGeneration = generation.sourceGeneration,
                authorityEpoch = generation.authorityEpoch,
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

    private fun acceptTick(message: PartyTickMessage, context: PartyAuthorityContext) {
        // Only the host publishes a timeline. A payload cannot promote itself: the durable snapshot
        // is the only thing that says who the host is.
        if (message.fromProfileId != context.hostProfileId) return
        val generation = context.generation
        val next = message.tick
        if (
            next.contentGeneration != generation.contentGeneration ||
            next.sourceGeneration != generation.sourceGeneration ||
            next.authorityEpoch != generation.authorityEpoch
        ) {
            refreshRequest()
            return
        }
        val held = tick
        if (held != null && next.contentGeneration != held.contentGeneration) {
            // Content moved under us. The tick cannot say what to, so ask the thing that can.
            refreshRequest()
        }
        if (!next.supersedes(held)) return
        tick = next
        _ticks.tryEmit(next)
        publishState()
    }

    private fun acceptCommand(message: PartyCommandMessage, context: PartyAuthorityContext) {
        val command = message.command
        if (!context.mayControl(command.issuedByProfileId)) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-permission")
            return
        }
        if (
            command.contentGeneration != context.generation.contentGeneration ||
            command.sourceGeneration != context.generation.sourceGeneration ||
            command.authorityEpoch != context.generation.authorityEpoch
        ) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-generation")
            refreshRequest()
            return
        }
        if (!commandLog.accepts(command)) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-duplicate")
            return
        }
        commandLog = commandLog.record(command)
        WatchPartyDiagnostics.received(command, context.partyId, outcome = "accepted")
        log.i {
            "command party=${context.partyId.shortId()} kind=${command.kind} posMs=${command.startPositionMs} " +
                "inMs=${command.startAtPartyMs - partyNowMs()} from=${command.issuedByProfileId.shortId()}"
        }
        _commands.tryEmit(command)
    }

    private fun acceptPeerStatus(message: PartyPeerStatusMessage) {
        if (message.rttMs >= 0) guestRttMs[message.fromProfileId] = message.rttMs
        // Freshness is stamped on receipt in the host's clock domain. The sender timestamp is
        // useful content, but it must not be allowed to keep its own presence fresh indefinitely.
        guestLastTelemetryAtPartyMs[message.fromProfileId] = partyNowMs()
        val before = if (isHost()) advanceBufferWatch() else emptyList()
        if (isHost()) {
            bufferWatch = bufferWatch.observe(
                profileId = message.fromProfileId,
                status = message.status,
                partyNowMs = partyNowMs(),
            )
        }
        val after = if (isHost()) advanceBufferWatch() else emptyList()
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
        publishState()
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
            val context = authority ?: return
            if (context.partyId != partyId) return
            val exchangeId = Uuid.random().toString()
            val sentAt = currentEpochMs()
            outstandingPings[exchangeId] = sentAt
            // An exchange that is never answered would otherwise accumulate forever on a host that
            // is on an older build, which is exactly the case this has to survive.
            outstandingPings.entries.removeAll { (_, at) -> sentAt - at > WatchPartyClockStaleMs }
            val generation = context.generation
            send(
                PartyClockPingMessage(
                    partyId = partyId,
                    fromProfileId = context.selfProfileId,
                    exchangeId = exchangeId,
                    sentAtMs = sentAt,
                    contentGeneration = generation.contentGeneration,
                    sourceGeneration = generation.sourceGeneration,
                    authorityEpoch = generation.authorityEpoch,
                ),
            )
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
            tickCapturedAtPartyMs = held?.capturedAtPartyMs,
            holdingProfiles = advanceBufferWatch(),
            peerTelemetry = guestStatus.mapValues { (profileId, status) ->
                PartyPeerTelemetry(
                    status = status,
                    receivedAtPartyMs = guestLastTelemetryAtPartyMs[profileId] ?: 0L,
                )
            },
        )
    }

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
    val tickCapturedAtPartyMs: Long? = null,
    val holdingProfiles: List<String> = emptyList(),
    val peerTelemetry: Map<String, PartyPeerTelemetry> = emptyMap(),
)

data class PartyPeerTelemetry(
    val status: WatchPartyStatus,
    val receivedAtPartyMs: Long,
)

internal inline fun dispatchPartyCommandLocallyFirst(
    command: PartyCommand,
    emitDirective: (PartyCommand) -> Unit,
    enqueueSend: (PartyCommand) -> Unit,
) {
    emitDirective(command)
    enqueueSend(command)
}
