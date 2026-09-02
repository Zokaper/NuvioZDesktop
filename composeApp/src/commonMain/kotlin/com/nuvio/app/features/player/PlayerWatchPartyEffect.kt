package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.nuvio.app.features.watchparty.DriftCorrectionKind
import com.nuvio.app.features.watchparty.DriftTracker
import com.nuvio.app.features.watchparty.PartyCommand
import com.nuvio.app.features.watchparty.PartyCommandKind
import com.nuvio.app.features.watchparty.PartyConnectionState
import com.nuvio.app.features.watchparty.PartyHoldReason
import com.nuvio.app.features.watchparty.PartyPlaybackGate
import com.nuvio.app.features.watchparty.PartyTick
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.StallHoldBudget
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyHostGraceMs
import com.nuvio.app.features.watchparty.WatchPartyIdleTickIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyPausedAlignToleranceMs
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySeekLandingPollMs
import com.nuvio.app.features.watchparty.WatchPartySnapshotIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyStatusSettleMs
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.WatchPartySync
import com.nuvio.app.features.watchparty.WatchPartyTickIntervalMs
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchparty.expectedPartyPositionMs
import com.nuvio.app.features.watchparty.matchesPlayback
import com.nuvio.app.features.watchparty.partyBarrierPlan
import com.nuvio.app.features.watchparty.partyFallbackDriftCorrection
import com.nuvio.app.features.watchparty.partyMembersAwaitingSource
import com.nuvio.app.features.watchparty.partyPlaybackGate
import com.nuvio.app.features.watchparty.partySeekPlan
import com.nuvio.app.features.watchparty.pendingPartySeek
import com.nuvio.app.features.watchparty.shortId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The decision half of the Watch Together trace; `WatchParty` carries the transport half.
 *
 * What this exists to answer, in order of how often it is the answer: did this player recognise the
 * party at all, what did the gate decide and who was it waiting on, and what did the correction
 * policy do about the position it was handed. Reaches the `DesktopDebugLog` file when desktop is
 * run with `-Dnuvio.debugTools=true`.
 */
/**
 * What a client tells the party it is doing.
 *
 * [shouldPlay] is the intent, and it is the third case that matters: a player that is starved
 * rather than paused reports neither playing nor loading, and calling that `paused` publishes a
 * deliberate pause the user never made - which every other member then obeys, pausing and seeking
 * to a frozen position. A host whose source stutters must look like a host who is buffering,
 * because that is what it is.
 *
 * A function rather than a value computed in composition, because the publishing loops are keyed
 * only on the party generation: anything they close over from the composition that launched them is
 * captured once and never updated again.
 */
private fun partyStatusFor(
    snapshot: PlayerPlaybackSnapshot,
    shouldPlay: Boolean,
    holding: Boolean = false,
): WatchPartyStatus = when {
    // A client parked for a barrier, or waiting for its own corrective seek to land, is neither
    // paused nor starved: it is doing what the party told it to and it will be playing again at an
    // instant that is already decided. `buffering` is right for the *timeline* - the position is
    // frozen and stale by construction, which is exactly what that status means - and wrong for the
    // guest's peer status, so the publisher below simply does not send one while a hold is on. The
    // two being the same fact is what held the party on every correction: the hold and the stall
    // guard's grace were the same length, so the guard fired every single time.
    holding -> WatchPartyStatus.buffering
    snapshot.isLoading -> WatchPartyStatus.buffering
    snapshot.isPlaying -> WatchPartyStatus.playing
    shouldPlay -> WatchPartyStatus.buffering
    else -> WatchPartyStatus.paused
}

private val partyLog = Logger.withTag("WatchPartyPlayer")

/**
 * Identifies one stretch of shared playback.
 *
 * Content generation is part of it because advancing an episode returns the party to a lobby: the
 * start gate has to close again for the new content rather than stay open because the previous one
 * played.
 */
private fun WatchPartyState.generationKey(): String = "$id:$contentGeneration"

/**
 * A position and the instant it was actually read.
 *
 * The pair is the whole fix for the standing sync error, so it is a type rather than two variables
 * that a later edit can quietly stop passing together.
 */
private data class SampledPosition(val positionMs: Long, val atEpochMs: Long)

/**
 * Reads the position now if the engine can be asked, and otherwise takes the last polled snapshot
 * *with the instant it arrived*.
 *
 * Desktop answers directly, so the pair is exact. The fallback is still honest: a 500ms-old sample
 * that knows it is 500ms old is usable, and a 500ms-old sample stamped with the current time is the
 * bug this whole change exists to remove.
 */
private fun PlayerScreenRuntime.samplePlaybackPosition(): SampledPosition {
    val direct = playerController?.samplePositionMs()
    return if (direct != null) {
        SampledPosition(direct, currentEpochMs())
    } else {
        SampledPosition(playbackSnapshot.positionMs, playbackSnapshotAtMs.takeIf { it > 0L } ?: currentEpochMs())
    }
}

/** The player's position now, for callers outside this file that only want the number. */
internal fun PlayerScreenRuntime.partyPositionNowMs(): Long = samplePlaybackPosition().positionMs

/**
 * Seeks to exactly [targetMs] and waits until the player is actually there.
 *
 * Two faults in one. The seek was a keyframe seek, so on a long-GOP release it landed up to nine
 * seconds early - the 2026-09-02 run has three consecutive corrections aiming at 20988, 23488 and
 * 25991 and landing at 18185 every time. And nothing waited for it, so the next pass half a second
 * later measured the *pre-seek* position, called it a fresh gap, and seeked again further ahead.
 * Seven corrective seeks and twenty engine seeks in two minutes, and the guest never converged.
 *
 * The wait is bounded and the record expires on its own, so a source that genuinely cannot serve the
 * position costs a slower correction rather than a wedged party.
 */
private suspend fun PlayerScreenRuntime.seekPartyToExact(targetMs: Long, reason: String) {
    val controller = playerController ?: return
    val issuedAtMs = currentEpochMs()
    partyPendingSeek = pendingPartySeek(targetMs = targetMs, nowMs = issuedAtMs)
    partyLog.i { "seek issue reason=$reason targetMs=$targetMs fromMs=${samplePlaybackPosition().positionMs}" }
    controller.seekToExact(targetMs)
    awaitPartySeekLanded()
}

/** Whether a seek this client issued is still in flight, clearing the record when it is not. */
private fun PlayerScreenRuntime.partySeekOutstanding(): Boolean {
    val pending = partyPendingSeek ?: return false
    val nowMs = currentEpochMs()
    val positionMs = samplePlaybackPosition().positionMs
    if (pending.isOutstanding(nowMs, positionMs)) return true
    partyPendingSeek = null
    val tookMs = nowMs - pending.issuedAtMs
    if (pending.timedOut(nowMs, positionMs)) {
        // Not an error on its own - an unbuffered position on a cold source takes what it takes -
        // but it is the line that names a seek that cannot land, so it is a warning.
        partyLog.w { "seek timeout targetMs=${pending.targetMs} landedMs=$positionMs tookMs=$tookMs" }
    } else {
        partyLog.i { "seek landed targetMs=${pending.targetMs} landedMs=$positionMs tookMs=$tookMs" }
    }
    return false
}

private suspend fun PlayerScreenRuntime.awaitPartySeekLanded() {
    while (partySeekOutstanding()) delay(WatchPartySeekLandingPollMs)
}

/**
 * Applies a playback rate only when it is not the one already running.
 *
 * The tick path evaluates a correction twice a second for the length of a film, and most of those
 * evaluations conclude "no change". Handing mpv the rate it is already at, 7,200 times over two
 * hours, is a filter-chain reconfiguration per call for nothing. The snapshot behind this can be up
 * to a polling interval stale, which costs at worst an occasional redundant call - the case this
 * exists to remove is the steady one, not the racing one.
 */
private fun PlayerScreenRuntime.applyPartySpeed(speed: Float) {
    if (abs(playbackSnapshot.playbackSpeed - speed) < 0.001f) return
    playerController?.setPlaybackSpeed(speed)
}

/** Same argument as [applyPartySpeed]: a player that is already playing does not need telling. */
private fun PlayerScreenRuntime.resumePartyPlayback() {
    shouldPlay = true
    if (!playbackSnapshot.isPlaying) playerController?.play()
}

/** The same instant, read in the party's terms. */
private fun partyInstantOf(epochMs: Long): Long = WatchPartySync.partyNowMs() - (currentEpochMs() - epochMs)

internal data class WatchPartyPlayerStatus(
    val gate: PartyPlaybackGate,
    val isHost: Boolean,
    val syncDegraded: Boolean,
    val hostBuffering: Boolean,
    val timelinePlaying: Boolean,
)

@Composable
internal fun PlayerScreenRuntime.rememberWatchPartyStatus(): WatchPartyPlayerStatus {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val syncState by WatchPartySync.state.collectAsStateWithLifecycle()
    val party = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
    return WatchPartyPlayerStatus(
        gate = partyPlaybackGate(
            party = party,
            viewerProfileId = partyUi.activeProfileId,
            hostStartReleased = party != null && partyStartReleasedKey == party.generationKey(),
            hostBufferingReleased = false,
        ),
        isHost = party != null && party.hostProfileId == partyUi.activeProfileId,
        syncDegraded = party != null && partyUi.connection != PartyConnectionState.connected,
        // The timeline says this seconds before the database row does, and "Host is buffering" is
        // the banner a guest is staring at while it waits.
        hostBuffering = party != null &&
            party.hostProfileId != partyUi.activeProfileId &&
            syncState.tickStatus == WatchPartyStatus.buffering,
        // The gate reads the database row, which is up to five seconds behind. Without this a guest
        // that the timeline has already started plays on under a banner still telling them to wait
        // for the host - which is the feature reporting itself broken while it works.
        timelinePlaying = syncState.tickStatus == WatchPartyStatus.playing,
    )
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffect() {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val matchingParty = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
    val generationKey = matchingParty?.generationKey()
    val isHost = matchingParty != null && matchingParty.hostProfileId == partyUi.activeProfileId
    val mediaLoaded = playbackSnapshot.durationMs > 0L
    val gate = partyPlaybackGate(
        party = matchingParty,
        viewerProfileId = partyUi.activeProfileId,
        hostStartReleased = generationKey != null && partyStartReleasedKey == generationKey,
        hostBufferingReleased = false,
    )

    // A party that is held but does not match this playback is silent by design and indistinguishable
    // from no party at all - which is exactly the shape of "Watch Together did nothing". Logged once
    // per player entry, with both identifiers, so a mismatch names itself.
    LaunchedEffect(partyUi.party?.id, parentMetaId, playbackSession.videoId) {
        val held = partyUi.party
        when {
            held == null -> partyLog.i { "bind party=none content=$parentMetaId video=${playbackSession.videoId}" }
            matchingParty != null -> partyLog.i {
                "bind party=${held.id.shortId()} matched role=${if (isHost) "host" else "guest"} " +
                    "status=${held.status} gen=${held.contentGeneration} video=${playbackSession.videoId}"
            }
            else -> partyLog.w {
                "bind party=${held.id.shortId()} MISMATCH status=${held.status} " +
                    "partyContent=${held.content.contentId}/${held.content.videoId} " +
                    "playerContent=$parentMetaId/${playbackSession.videoId}"
            }
        }
    }

    // Once the party is genuinely playing the start gate is spent. Without this the host's own pause
    // reads as "has not started yet", and the next readiness tick restarts the film under them.
    LaunchedEffect(generationKey, matchingParty?.status) {
        if (generationKey != null && matchingParty?.status == WatchPartyStatus.playing) {
            partyStartReleasedKey = generationKey
        }
    }

    // Readiness is what the host's gate waits on, so it has to be reported both ways: a stream that
    // is open, and one that is not open yet.
    LaunchedEffect(generationKey, mediaLoaded) {
        if (generationKey == null) return@LaunchedEffect
        if (mediaLoaded) {
            WatchPartyRepository.updateReady(SourceResolutionState.ready, playbackSnapshot.durationMs)
        } else {
            WatchPartyRepository.updateReady(SourceResolutionState.resolving)
        }
    }

    // Backing out to the source list gives up the resolved stream. Saying so is what lets a host who
    // has not started yet go back to waiting instead of starting without them.
    DisposableEffect(generationKey) {
        onDispose {
            if (generationKey != null) {
                WatchPartyRepository.updateReadyDetached(SourceResolutionState.resolving)
                partyBarrierAtMs = 0L
                partyReportedPeerStatus = null
                partyHoldingForBarrier = false
                partyPendingSeek = null
                // Per content generation: a new episode is a new stream, and it deserves the
                // benefit of the doubt rather than inheriting the previous one's exhausted budget.
                partyStallHoldBudget = StallHoldBudget()
            }
        }
    }

    LaunchedEffect(generationKey, gate.allowPlayback, mediaLoaded, isHost) {
        if (generationKey == null) return@LaunchedEffect
        partyLog.i {
            val waiting = matchingParty
                ?.let { partyMembersAwaitingSource(it, excludeProfileId = partyUi.activeProfileId) }
                ?.joinToString { "${it.profileId.shortId()}:${it.readyState}" }
                .orEmpty()
            "gate $generationKey role=${if (isHost) "host" else "guest"} allow=${gate.allowPlayback} " +
                "reason=${gate.reason} waitingOn=${gate.waitingOn} [$waiting] mediaLoaded=$mediaLoaded " +
                "released=${partyStartReleasedKey == generationKey}"
        }
        val controller = playerController ?: return@LaunchedEffect
        // Holding a stream that has not opened yet would stop it opening: the load is what produces
        // the duration this gate is waiting to hear about.
        if (!mediaLoaded) return@LaunchedEffect
        // A guest with a live timeline does not need this gate at all, and must not be driven by it:
        // the gate reads the database snapshot, which is up to five seconds old, while the timeline
        // is the same fact half a second old. Two authorities over one transport is how a guest ends
        // up paused by the older of them a moment after the newer one started it.
        if (!isHost && WatchPartySync.isPrecise()) return@LaunchedEffect
        if (!gate.allowPlayback) {
            shouldPlay = false
            controller.pause()
            return@LaunchedEffect
        }
        if (isHost && partyStartReleasedKey != generationKey) {
            // Everyone has a source. The play command is what sets the authoritative clock running,
            // and it is sent from here rather than from the lobby so that it coincides with playback
            // actually beginning. It goes out as a barrier, so every member's first frame is the
            // same frame rather than each one starting when its own copy of the news arrived.
            val sample = samplePlaybackPosition()
            partyLog.i { "gate released $generationKey by=allReady positionMs=${sample.positionMs}" }
            partyStartReleasedKey = generationKey
            startPartyPlayback(sample.positionMs, source = "gate")
        }
    }

    // The durable heartbeat: the liveness stamp and the anchor a client falls back to when the
    // socket is down. Five seconds is right for that job and always was; what was wrong was it
    // being the *only* thing carrying position.
    LaunchedEffect(generationKey) {
        if (generationKey == null) return@LaunchedEffect
        while (true) {
            val live = WatchPartyRepository.uiState.value.party
            if (live == null || live.status == WatchPartyStatus.ended) break
            val snapshot = playbackSnapshot
            val sample = samplePlaybackPosition()
            WatchPartyRepository.heartbeat(
                positionMs = sample.positionMs,
                durationMs = snapshot.durationMs,
                speed = snapshot.playbackSpeed,
                // Derived here, from the snapshot this pass just read, and deliberately not from
                // the composable-level value: this loop is keyed only on the generation, so a value
                // computed in the composition that launched it is captured once and never updated.
                status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier),
                positionCapturedAtMs = sample.atEpochMs,
            )
            delay(WatchPartySnapshotIntervalMs)
        }
    }

    /**
     * The host's timeline.
     *
     * Twice a second while playing, and the position is sampled and stamped on the same line -
     * which is the entire fix for the standing offset. Every other cadence in this feature is a
     * floor beneath this one.
     */
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        while (true) {
            val live = WatchPartyRepository.uiState.value.party
            if (live == null || live.status == WatchPartyStatus.ended) break
            val snapshot = playbackSnapshot
            val status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier)
            if (snapshot.durationMs > 0L) {
                val sample = samplePlaybackPosition()
                WatchPartySync.publishTick(
                    status = status,
                    positionMs = sample.positionMs,
                    capturedAtPartyMs = partyInstantOf(sample.atEpochMs),
                    playbackSpeed = snapshot.playbackSpeed,
                    durationMs = snapshot.durationMs,
                )
            }
            delay(
                if (status == WatchPartyStatus.playing) WatchPartyTickIntervalMs else WatchPartyIdleTickIntervalMs,
            )
        }
    }

    /**
     * The host's status, published out of turn when it changes.
     *
     * The periodic loop alone is not enough at either end of a stall. Entering one it would be up
     * to half a second late, and every guest spends that half second playing on past a host that
     * has stopped. Leaving one it would be up to *two* seconds late, because a host that is not
     * playing ticks on the idle interval - so the recovery, which is the moment everyone is waiting
     * for, was the slowest thing in the feature.
     */
    val hostStatus = partyStatusFor(playbackSnapshot, shouldPlay, partyHoldingForBarrier)
    LaunchedEffect(generationKey, isHost, hostStatus) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        if (playbackSnapshot.durationMs <= 0L) return@LaunchedEffect
        // Keyed on the status, so a flap cancels the pending publish rather than adding to it.
        if (hostStatus != WatchPartyStatus.buffering) delay(WatchPartyStatusSettleMs)
        val snapshot = playbackSnapshot
        val sample = samplePlaybackPosition()
        WatchPartySync.publishTick(
            status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier),
            positionMs = sample.positionMs,
            capturedAtPartyMs = partyInstantOf(sample.atEpochMs),
            playbackSpeed = snapshot.playbackSpeed,
            durationMs = snapshot.durationMs,
        )
    }

    // What a guest is doing, published the moment it changes rather than at the next heartbeat: the
    // host's grace for a stalled guest is shorter than any polling interval this feature has.
    //
    // Deliberately *not* holding-aware: a guest parked for a barrier or waiting for its own
    // corrective seek is doing what the party asked, and telling the host it is buffering is what
    // made the stall guard hold the party on every single correction. The host already knows a
    // barrier is in flight - it sent it - so silence here is the accurate answer, not a missing one.
    val peerStatus = partyStatusFor(playbackSnapshot, shouldPlay)
    LaunchedEffect(generationKey, isHost, peerStatus, partyHoldingForBarrier) {
        if (generationKey == null || isHost) return@LaunchedEffect
        if (partyHoldingForBarrier) return@LaunchedEffect
        // Keyed on the status, so a flap cancels the pending publish rather than adding to it - the
        // same debounce the host's status gets, and for a sharper reason here: the snapshot poll is
        // up to a full interval behind the player, so the first read after a hold ends still says
        // "not playing". Publishing that would be a stall report for a client that has this instant
        // been told to resume. The host's grace is measured in seconds; two hundred milliseconds of
        // honesty costs nothing against it.
        delay(WatchPartyStatusSettleMs)
        if (partyHoldingForBarrier) return@LaunchedEffect
        val settled = partyStatusFor(playbackSnapshot, shouldPlay)
        if (partyReportedPeerStatus == settled) return@LaunchedEffect
        partyReportedPeerStatus = settled
        WatchPartySync.publishPeerStatus(settled)
    }

    // Every transport action, host and guest alike, through one path and one instant.
    LaunchedEffect(generationKey) {
        if (generationKey == null) return@LaunchedEffect
        WatchPartySync.commands.collect { command -> executePartyBarrier(command) }
    }

    // The guest's correction, driven by the timeline rather than by a database row.
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || isHost) return@LaunchedEffect
        var tracker = DriftTracker()
        WatchPartySync.ticks.collect { tick -> tracker = followPartyTick(tick, tracker) }
    }

    // Wait for everyone: a guest that stalls used to be left behind and then dragged back by a seek,
    // which on a torrent or debrid source is most of what a party feels like.
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        WatchPartySync.state
            .map { it.holdingProfiles }
            .distinctUntilChanged()
            .collect { holding -> reactToStalledGuests(holding) }
    }

    /**
     * The degraded ladder: the database anchor, when no timeline is arriving.
     *
     * Reached when the socket is down, or when the host is on a build that publishes no ticks. It
     * is the behaviour the feature shipped with, wider bands and all, and it is deliberately kept
     * whole rather than folded into the tick path: the two are correcting against evidence of
     * different quality, and a single set of bands would be either too loose for the good anchor or
     * far too tight for the bad one.
     */
    LaunchedEffect(
        matchingParty?.sequence,
        matchingParty?.stateUpdatedAt,
        partyUi.serverClockOffsetMs,
        mediaLoaded,
        isHost,
    ) {
        val state = matchingParty ?: return@LaunchedEffect
        if (isHost) return@LaunchedEffect
        if (WatchPartySync.isPrecise()) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
        // seek lands on a player with no timeline, and the play that follows has nothing to play.
        val durationMs = playbackSnapshot.durationMs
        if (durationMs <= 0L || playbackSnapshot.isLoading) return@LaunchedEffect
        val updatedAt = runCatching { kotlin.time.Instant.parse(state.stateUpdatedAt).toEpochMilliseconds() }
            .getOrNull() ?: return@LaunchedEffect
        val serverNow = currentEpochMs() + partyUi.serverClockOffsetMs
        // A position past the end of this file is not a position. Clamping keeps a host on a longer
        // cut - or a shared clock that has run on without anyone playing - from seeking a guest into
        // empty space and leaving them there.
        val raw = expectedPartyPositionMs(state.positionMs, updatedAt, serverNow, state.status, state.playbackSpeed)
        val expected = raw.coerceIn(0L, durationMs - 1L)
        // Same guard as the tick path, for the same reason: the position this whole effect is
        // measured against is the pre-seek one while a seek is in flight, and a correction taken
        // against that is a correction for a gap that has already been answered.
        if (partySeekOutstanding()) return@LaunchedEffect
        val local = samplePlaybackPosition().positionMs
        if (state.status == WatchPartyStatus.playing) {
            val correction = partyFallbackDriftCorrection(local, expected, state.playbackSpeed)
            // `raw` is kept beside `expected` on purpose: the two diverging means the shared clock
            // has run past the end of this file, which is the signature of a timeline that started
            // without anybody playing.
            partyLog.i {
                "fallbackDrift seq=${state.sequence} localMs=$local expectedMs=$expected rawMs=$raw " +
                    "durationMs=$durationMs driftMs=${expected - local} action=${correction.kind} " +
                    "offsetMs=${partyUi.serverClockOffsetMs}"
            }
            when (correction.kind) {
                DriftCorrectionKind.NONE -> controller.setPlaybackSpeed(state.playbackSpeed)
                DriftCorrectionKind.SEEK -> {
                    partyHoldingForBarrier = true
                    try {
                        // Paused across the seek so the landing is a fixed number to compare
                        // against: a player still running would move past the target while the
                        // seek was completing and never look like it had arrived. The `play` at
                        // the end of this branch starts it again.
                        controller.pause()
                        seekPartyToExact(
                            targetMs = correction.targetPositionMs.coerceIn(0L, durationMs - 1L),
                            reason = "fallback-drift",
                        )
                    } finally {
                        partyHoldingForBarrier = false
                    }
                    controller.setPlaybackSpeed(state.playbackSpeed)
                }
                DriftCorrectionKind.TEMPORARY_SPEED ->
                    controller.setPlaybackSpeed(correction.temporarySpeed ?: state.playbackSpeed)
            }
            shouldPlay = true
            controller.play()
        } else if (state.status == WatchPartyStatus.paused ||
            state.status == WatchPartyStatus.buffering ||
            state.status == WatchPartyStatus.lobby
        ) {
            partyLog.i { "fallbackHold seq=${state.sequence} status=${state.status} localMs=$local expectedMs=$expected" }
            // `buffering` is the host stalling, not a position anyone chose, and the position it
            // froze at is stale by construction.
            // A nudge left running into a pause would drift the guest right back out again.
            controller.setPlaybackSpeed(state.playbackSpeed)
            shouldPlay = false
            controller.pause()
            if (state.status != WatchPartyStatus.buffering && abs(local - expected) > 500L) {
                partyHoldingForBarrier = true
                try {
                    seekPartyToExact(targetMs = expected, reason = "fallback-align")
                } finally {
                    partyHoldingForBarrier = false
                }
                controller.pause()
            }
        }
    }

    LaunchedEffect(matchingParty?.hostProfileId, matchingParty?.members) {
        val state = matchingParty ?: return@LaunchedEffect
        val hostConnected = state.members.firstOrNull { it.profileId == state.hostProfileId }?.connected != false
        if (hostConnected || state.hostProfileId == partyUi.activeProfileId) return@LaunchedEffect
        // The server refuses the claim until its own fifteen-second grace has run, and every refusal
        // lands in the party error banner. Waiting the grace out locally first means the claim is
        // attempted once, when it can actually succeed, instead of being rejected on every snapshot.
        delay(WatchPartyHostGraceMs)
        val current = WatchPartyRepository.uiState.value.party ?: return@LaunchedEffect
        val stillGone = current.members.firstOrNull { it.profileId == current.hostProfileId }?.connected == false
        if (stillGone && current.hostProfileId != partyUi.activeProfileId) {
            partyLog.w { "host ${current.hostProfileId.shortId()} gone past grace, claiming" }
            WatchPartyRepository.claimHostAfterGrace()
        }
    }
}

/**
 * Waits for a party instant, re-reading the clock as it goes.
 *
 * The offset can slew while a barrier is pending - it is being re-measured the whole time - so a
 * single `delay` computed once would land on the instant the clock believed in when the wait
 * started rather than the one it believes in when the wait ends.
 */
private suspend fun awaitPartyInstant(atPartyMs: Long) {
    while (true) {
        val remaining = atPartyMs - WatchPartySync.partyNowMs()
        if (remaining <= 0L) return
        delay(if (remaining > 100L) remaining - 50L else remaining)
    }
}

/**
 * Does what a command says, at the instant it says to.
 *
 * The host runs this too, on its own command, which is the point: one implementation means the host
 * and every guest reach the same frame at the same time by construction rather than by two pieces of
 * code agreeing.
 */
private suspend fun PlayerScreenRuntime.executePartyBarrier(command: PartyCommand) {
    val controller = playerController ?: return
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L) return
    val sample = samplePlaybackPosition()
    // A client with no clock estimate cannot be scheduled against one, and pretending otherwise
    // would put the barrier hours away or hours past. Reading the command as though it had arrived
    // exactly on time collapses it to what the transport did before barriers existed: go to the
    // position, act now. Which is the right answer when there is no shared instant to wait for.
    val clockUsable = WatchPartySync.isClockUsable()
    val partyNow = if (clockUsable) WatchPartySync.partyNowMs() else command.startAtPartyMs
    val plan = partyBarrierPlan(command, sample.positionMs, partyNow)
    partyLog.i {
        "barrier kind=${plan.kind} localMs=${sample.positionMs} seekToMs=${plan.seekToMs} " +
            "holdMs=${plan.holdMs} playAfter=${plan.playAfter} speed=${plan.speed}"
    }
    when (plan.kind) {
        PartyCommandKind.pause -> {
            shouldPlay = false
            controller.pause()
            // Aligned *while paused*, where closing a gap costs one frame rather than the visible
            // jump the same correction makes against a running player - and exactly, because a
            // keyframe seek can never satisfy the tolerance that decides whether to align at all,
            // which had a paused guest re-seeking to the same position every tick forever.
            plan.seekToMs?.let {
                partyHoldingForBarrier = true
                try {
                    seekPartyToExact(it.coerceIn(0L, durationMs - 1L), reason = "pause-align")
                } finally {
                    partyHoldingForBarrier = false
                }
                // Said again rather than assumed: a seek can leave a paused player running on some
                // engines, and a guest that quietly resumes under a party pause is the worst of the
                // failures this feature can have.
                controller.pause()
            }
            controller.setPlaybackSpeed(plan.speed)
        }
        PartyCommandKind.speed -> {
            if (clockUsable) awaitPartyInstant(command.startAtPartyMs)
            controller.setPlaybackSpeed(plan.speed)
        }
        PartyCommandKind.play, PartyCommandKind.seek -> {
            partyBarrierAtMs = command.startAtPartyMs
            // Held, not "playing". Desktop drives the engine off `shouldPlay` through its own
            // effect, so setting the intent here started this player the moment the state changed
            // rather than at the instant the barrier names - which is the whole point of a barrier.
            // The flag carries the intent to the status publishers instead, and `shouldPlay` is
            // flipped where it means something: at the instant.
            partyHoldingForBarrier = true
            try {
                if (plan.seekToMs != null) {
                    controller.pause()
                    seekPartyToExact(plan.seekToMs.coerceIn(0L, durationMs - 1L), reason = "barrier")
                } else if (plan.holdMs > 0L) {
                    controller.pause()
                }
                controller.setPlaybackSpeed(plan.speed)
                if (clockUsable) awaitPartyInstant(command.startAtPartyMs)
            } finally {
                partyHoldingForBarrier = false
            }
            shouldPlay = true
            controller.play()
        }
    }
}

/**
 * Follows the host's timeline.
 *
 * Everything this does was previously done against `(sequence, state_updated_at)` - a row whose
 * position and timestamp were taken at different instants, so the correction was chasing a target
 * that was systematically behind the host. The tick carries its own capture instant, so the gap
 * measured here is the real one.
 */
private suspend fun PlayerScreenRuntime.followPartyTick(tick: PartyTick, tracker: DriftTracker): DriftTracker {
    val controller = playerController ?: return tracker
    val durationMs = playbackSnapshot.durationMs
    // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
    // seek lands on a player with no timeline, and the play that follows has nothing to play.
    if (durationMs <= 0L || playbackSnapshot.isLoading) return tracker
    // One authority at a time. Until the clock is locked the database anchor is the better of two
    // imperfect answers, and its effect below is doing the work; two correction paths acting on one
    // player is how a guest gets seeked twice for the same gap.
    if (!WatchPartySync.isPrecise()) return tracker
    // A seek this client issued is still in flight, so the position everything below would be
    // measured against is the one from *before* it. Correcting against that is what turned one
    // corrective seek into a cascade of them, each aimed further ahead than the last.
    if (partySeekOutstanding()) return tracker
    val partyNow = WatchPartySync.partyNowMs()
    // A barrier is already putting this player exactly where it should be. Measuring against a
    // position it is deliberately holding would produce a correction for a gap that is intentional.
    if (partyNow < partyBarrierAtMs) return tracker
    // And a timeline captured before that barrier is about the party as it was *before* the command
    // everyone just obeyed. The host's next tick is up to half a second behind its own pause, so
    // without this a guest resumes at the barrier and is put straight back by the tick in flight.
    if (tick.capturedAtPartyMs < partyBarrierAtMs) return tracker
    // Checked against the tick in hand rather than relying on the one the transport happens to
    // hold, so this reads correctly wherever it is called from.
    if (tick.isStale(partyNow)) return tracker

    if (tick.status == WatchPartyStatus.playing) {
        val expected = tick.expectedPositionMs(partyNow).coerceIn(0L, durationMs - 1L)
        val local = samplePlaybackPosition().positionMs
        val outcome = tracker.next(local, expected, tick.playbackSpeed)
        partyLog.i {
            "drift localMs=$local expectedMs=$expected driftMs=${expected - local} " +
                "action=${outcome.correction.kind} offsetMs=${WatchPartySync.state.value.clockOffsetMs} " +
                "tickAgeMs=${partyNow - tick.capturedAtPartyMs}"
        }
        when (outcome.correction.kind) {
            DriftCorrectionKind.NONE -> applyPartySpeed(tick.playbackSpeed)
            DriftCorrectionKind.TEMPORARY_SPEED ->
                applyPartySpeed(outcome.correction.temporarySpeed ?: tick.playbackSpeed)
            DriftCorrectionKind.SEEK -> {
                // Scheduled, like a host's seek: park on where the party *will* be and start when
                // it gets there. Nothing has to predict the reload cost, so being wrong about it
                // costs a slightly longer hold instead of the standing error a fixed lead left.
                val plan = partySeekPlan(tick, partyNow)
                partyBarrierAtMs = plan.resumeAtPartyMs
                applyPartySpeed(tick.playbackSpeed)
                // Held for the party's own reasons, so the host is not told this client is stalling.
                partyHoldingForBarrier = true
                try {
                    controller.pause()
                    seekPartyToExact(plan.seekToMs.coerceIn(0L, durationMs - 1L), reason = "drift")
                    awaitPartyInstant(plan.resumeAtPartyMs)
                } finally {
                    partyHoldingForBarrier = false
                }
            }
        }
        resumePartyPlayback()
        return outcome.tracker
    }

    // A nudge left running into a pause would drift the guest right back out again. Restored, and
    // the player stopped, before the align below, so the seek is not racing a rate change or a
    // position that is still moving under it.
    applyPartySpeed(tick.playbackSpeed)
    shouldPlay = false
    controller.pause()
    // `buffering` is the host stalling, not a position anyone chose. The host publishes it from its
    // own `isLoading` and the timeline freezes at the last written position, so aligning to it would
    // seek every guest to a position that is stale by construction - and on torrent and debrid
    // sources, where the host rebuffers routinely, that is a stall of one's own per stall of theirs.
    if (tick.status != WatchPartyStatus.buffering) {
        val local = samplePlaybackPosition().positionMs
        if (abs(local - tick.positionMs) > WatchPartyPausedAlignToleranceMs) {
            // Exact, and waited for. A keyframe seek could not land inside the tolerance that
            // decides whether to align at all, so a paused guest re-issued the same seek on every
            // tick for as long as the party stayed paused.
            partyHoldingForBarrier = true
            try {
                seekPartyToExact(tick.positionMs.coerceIn(0L, durationMs - 1L), reason = "paused-align")
            } finally {
                partyHoldingForBarrier = false
            }
            // The align is a seek, and a seek on a paused player can leave it unpaused on some
            // engines; say it again rather than assume.
            controller.pause()
        }
    }
    return DriftTracker()
}

/**
 * Holds the party for a guest whose stream has stalled, and starts it again together.
 *
 * Off is a legitimate answer - one bad connection should not be able to stop the film for everyone -
 * so this is a host-side switch rather than a rule.
 */
private suspend fun PlayerScreenRuntime.reactToStalledGuests(holding: List<String>) {
    if (!WatchPartyRepository.uiState.value.waitForEveryone) return
    val partyNow = WatchPartySync.partyNowMs()
    if (holding.isNotEmpty() && partyAutoPausedForGuests.isEmpty()) {
        if (!playbackSnapshot.isPlaying) return
        // A hold that has just been taken, or one taken too many times, is not taken again. A source
        // that flaps would otherwise produce a hold, a resume and another hold indefinitely, and a
        // party spent stopping and starting is worse than one member being a second behind.
        if (!partyStallHoldBudget.mayHold(partyNow)) {
            if (partyStallHoldBudget.exhausted(partyNow)) {
                partyLog.w {
                    "stall holds exhausted, playing on without " +
                        holding.joinToString { it.shortId() }
                }
            }
            return
        }
        partyStallHoldBudget = partyStallHoldBudget.record(partyNow)
        partyAutoPausedForGuests = holding
        partyLog.i { "waiting for ${holding.joinToString { it.shortId() }}" }
        pausePartyPlayback(samplePlaybackPosition().positionMs, source = "stall-guard")
    } else if (holding.isEmpty() && partyAutoPausedForGuests.isNotEmpty()) {
        partyAutoPausedForGuests = emptyList()
        partyLog.i { "stalled guests recovered, resuming" }
        startPartyPlayback(samplePlaybackPosition().positionMs, source = "stall-guard")
    }
}

/**
 * Places the player at the party's position rather than at this profile's own resume point.
 *
 * A party has one position by definition. Continue Watching is per profile, so a guest who had
 * already seen half the film opened the player at their own bookmark and the correction policy then
 * had to drag them back - visibly, and through a seek that can cost the stream its buffer. Applied
 * during composition, before the surface reads the initial position, because on desktop that value
 * is handed to the engine at attach rather than seeked to afterwards.
 */
internal fun PlayerScreenRuntime.applyWatchPartyStartPosition(party: WatchPartyState?) {
    // Only ever a launch decision. Starting a party from inside a running player creates one at
    // position zero around the film already playing, and moving the host back to the beginning is
    // not what they asked for.
    if (initialLoadCompleted) return
    val matching = party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return
    val key = matching.generationKey()
    if (partyStartPositionAppliedKey == key) return
    partyStartPositionAppliedKey = key
    val positionMs = matching.positionMs.coerceAtLeast(0L)
    partyLog.i {
        "startPosition $key partyMs=$positionMs replacedResumeMs=$activeInitialPositionMs " +
            "resumeFraction=$activeInitialProgressFraction"
    }
    activeInitialPositionMs = positionMs
    activeInitialProgressFraction = null
    initialSeekApplied = positionMs <= 0L
}

/** Whether this client's transport belongs to the party rather than to whoever pressed the button. */
internal fun PlayerScreenRuntime.partyOwnsTransport(): Boolean =
    WatchPartyRepository.uiState.value.party?.matchesPlayback(parentMetaId, playbackSession.videoId) == true

private fun PlayerScreenRuntime.mayControlParty(): Boolean {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return false
    return party.hostProfileId == ui.activeProfileId || party.controlMode == WatchPartyControlMode.collaborative
}

/**
 * Says why a press did nothing.
 *
 * A guest without control has always been unable to move the party, but until the transport was
 * claimed the press still moved *their own* player, so it looked like it worked and desynced them.
 * Refusing it is right; refusing it silently would be reported as the party being broken.
 */
private fun PlayerScreenRuntime.refusePartyControl(): Boolean {
    showGestureMessage("The host controls playback")
    return true
}

/** Starts, or restarts, shared playback at an instant everyone can reach. */
private fun PlayerScreenRuntime.startPartyPlayback(positionMs: Long, source: String) {
    scope.launch {
        // Named for the same reason a pause is: a party that starts and stops has to say who kept
        // asking it to, and "who" is the difference between a user, a readiness gate and a stall
        // guard that has decided a guest has recovered.
        partyLog.i { "play src=$source positionMs=$positionMs" }
        val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
        WatchPartySync.issueCommand(
            kind = PartyCommandKind.play,
            startPositionMs = positionMs,
            startAtPartyMs = startAt,
            playbackSpeed = playbackSnapshot.playbackSpeed,
        )
        // The durable record follows the barrier rather than carrying it. A late joiner reads this;
        // nobody waits on it.
        WatchPartyRepository.play(positionMs)
    }
}

private fun PlayerScreenRuntime.pausePartyPlayback(positionMs: Long, source: String) {
    scope.launch {
        // Every pause names where it came from. A pause with no origin is exactly what the
        // 2026-09-02 run could not explain: the host's player stopped, no command was issued, and
        // the guests learned about it only from the timeline's status.
        partyLog.i { "pause src=$source positionMs=$positionMs" }
        WatchPartySync.issueCommand(
            kind = PartyCommandKind.pause,
            startPositionMs = positionMs,
            // Pause carries no lead. Pausing 60ms apart is worth far more than pausing together and
            // late, and the alignment that follows is paid while paused. Stamped with now rather
            // than zero so the `leadMs` beside it is a number a person can read - the plan is what
            // ignores the instant for a pause, not the sender.
            startAtPartyMs = WatchPartySync.partyNowMs(),
            playbackSpeed = playbackSnapshot.playbackSpeed,
        )
        WatchPartyRepository.pause(positionMs)
    }
}

/**
 * A transport press, turned into a party barrier.
 *
 * Returns true when the party has taken the action, which on desktop is also the signal that stops
 * the native controls layer performing it: a guest without control must not move its own player,
 * and a host must not start before the instant it just told everybody else about.
 */
internal fun PlayerScreenRuntime.submitPartyPlayPause(isPlaying: Boolean, positionMs: Long): Boolean {
    if (!partyOwnsTransport()) return false
    if (!mayControlParty()) return refusePartyControl()
    val party = WatchPartyRepository.uiState.value.party ?: return true
    // Pressing play while the gate is still holding is the force start: the host has decided not to
    // wait, and the command that follows is what tells everyone else the film has begun.
    if (isPlaying && partyStartReleasedKey != party.generationKey()) {
        partyLog.i { "gate released ${party.generationKey()} by=forceStart positionMs=$positionMs" }
    }
    if (isPlaying) partyStartReleasedKey = party.generationKey()
    // The user has taken the transport back. Without this, a guest recovering later would have the
    // stall guard resume over a pause a person made in the meantime - the guard would be undoing a
    // decision it did not take.
    partyAutoPausedForGuests = emptyList()
    if (isPlaying) {
        startPartyPlayback(positionMs, source = "user")
    } else {
        pausePartyPlayback(positionMs, source = "user")
    }
    return true
}

internal fun PlayerScreenRuntime.submitPartySeek(positionMs: Long): Boolean {
    if (!partyOwnsTransport()) return false
    if (!mayControlParty()) return refusePartyControl()
    scope.launch {
        val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
        WatchPartySync.issueCommand(
            kind = PartyCommandKind.seek,
            startPositionMs = positionMs,
            startAtPartyMs = startAt,
            playbackSpeed = playbackSnapshot.playbackSpeed,
        )
        WatchPartyRepository.seek(positionMs)
    }
    return true
}

internal fun PlayerScreenRuntime.submitPartySpeed(speed: Float): Boolean {
    if (!partyOwnsTransport()) return false
    if (!mayControlParty()) return refusePartyControl()
    scope.launch {
        val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
        WatchPartySync.issueCommand(
            kind = PartyCommandKind.speed,
            startPositionMs = samplePlaybackPosition().positionMs,
            startAtPartyMs = startAt,
            playbackSpeed = speed,
        )
        WatchPartyRepository.setSpeed(speed)
    }
    return true
}

/**
 * The live sync numbers, for the playback HUD.
 *
 * Every previous round of this work was measured by pulling two log files off two machines and
 * lining up timestamps. `errMs` is the number the whole feature is judged on - how far this client
 * is from where the party says it should be - and it belongs somewhere a person can read it while
 * the film is playing.
 */
internal fun PlayerScreenRuntime.partyDiagnosticsLine(): String? {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return null
    val isHost = party.hostProfileId == ui.activeProfileId
    val sync = WatchPartySync.state.value
    val partyNow = WatchPartySync.partyNowMs()
    val errMs = WatchPartySync.heldTick()
        ?.takeIf { !isHost && it.status == WatchPartyStatus.playing && !it.isStale(partyNow) }
        ?.let { it.expectedPositionMs(partyNow) - samplePlaybackPosition().positionMs }
    return buildString {
        append("party=${if (isHost) "host" else "guest"} errMs=${errMs ?: "-"} ")
        append("offsetMs=${sync.clockOffsetMs} locked=${sync.clockLocked} rttMs=${sync.bestRttMs} ")
        append("tickAgeMs=${WatchPartySync.tickAgeMs()} tickStatus=${sync.tickStatus} ")
        append("leadMs=${WatchPartySync.barrierLeadMs()} precise=${WatchPartySync.isPrecise()}")
        // The three states that explain a player which is not moving when the party is. Without
        // them a held client and a stuck one look identical on screen.
        if (partyHoldingForBarrier) append(" holding")
        partyPendingSeek?.let { append(" seekTo=${it.targetMs}") }
        if (sync.holdingProfiles.isNotEmpty()) {
            append(" waitingOn=${sync.holdingProfiles.joinToString { it.shortId() }}")
        }
        // How many times this host has stopped the party for somebody. Rising while the film plays
        // is the signature of a guard that is firing on its own corrections rather than on stalls.
        val holds = partyStallHoldBudget.holdsAtMs.size
        if (isHost && holds > 0) append(" holds=$holds")
    }
}

/** The one line the player shows while a party is holding playback back, or has lost sync. */
internal fun WatchPartyPlayerStatus.bannerText(): String? = when {
    gate.reason == PartyHoldReason.WAITING_FOR_PARTICIPANTS -> {
        val people = if (gate.waitingOn == 1) "1 person" else "${gate.waitingOn} people"
        "Waiting for $people to pick a source · press play to start anyway"
    }
    hostBuffering || gate.reason == PartyHoldReason.HOST_BUFFERING -> "Host is buffering"
    gate.reason == PartyHoldReason.WAITING_FOR_HOST && !timelinePlaying -> "Waiting for the host to start"
    // Not "playing on your own": the snapshot poll is still running underneath, so a party without
    // its channel is following along a few seconds at a time rather than not following at all.
    // Saying the stronger thing sent testers looking for a broken party when what had actually
    // broken was one socket.
    syncDegraded -> "Live sync lost · following the party every few seconds"
    else -> null
}
