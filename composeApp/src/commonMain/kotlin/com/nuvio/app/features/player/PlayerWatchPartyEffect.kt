package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.nuvio.app.features.watchparty.DriftCorrectionKind
import com.nuvio.app.features.watchparty.PartyConnectionState
import com.nuvio.app.features.watchparty.PartyHoldReason
import com.nuvio.app.features.watchparty.PartyPlaybackGate
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyBufferingHoldDeadlineMs
import com.nuvio.app.features.watchparty.WatchPartyHostGraceMs
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySnapshotIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.expectedPartyPositionMs
import com.nuvio.app.features.watchparty.matchesPlayback
import com.nuvio.app.features.watchparty.partyDriftCorrection
import com.nuvio.app.features.watchparty.partyMembersAwaitingSource
import com.nuvio.app.features.watchparty.partyPlaybackGate
import com.nuvio.app.features.watchparty.shortId
import kotlinx.coroutines.delay
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
 * A function rather than a value computed in composition, because the heartbeat loop is keyed only
 * on the party generation: anything it closes over from the composition that launched it is
 * captured once and never updated again.
 */
private fun partyStatusFor(snapshot: PlayerPlaybackSnapshot, shouldPlay: Boolean): WatchPartyStatus = when {
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
 * One uninterrupted buffering episode, ignoring heartbeat timestamp churn. A latched host can keep
 * rewriting `state_updated_at` every five seconds without advancing either the command sequence or
 * the state, so using the timestamp here would restart the safety deadline forever.
 */
private fun WatchPartyState.bufferingKey(): String? =
    if (status == WatchPartyStatus.buffering) "${generationKey()}:$sequence" else null

internal data class WatchPartyPlayerStatus(
    val gate: PartyPlaybackGate,
    val isHost: Boolean,
    val syncDegraded: Boolean,
)

@Composable
internal fun PlayerScreenRuntime.rememberWatchPartyStatus(): WatchPartyPlayerStatus {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val party = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
    return WatchPartyPlayerStatus(
        gate = partyPlaybackGate(
            party = party,
            viewerProfileId = partyUi.activeProfileId,
            hostStartReleased = party != null && partyStartReleasedKey == party.generationKey(),
            hostBufferingReleased = party?.bufferingKey() == partyBufferingReleasedKey,
        ),
        isHost = party != null && party.hostProfileId == partyUi.activeProfileId,
        syncDegraded = party != null && partyUi.connection != PartyConnectionState.connected,
    )
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffect() {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val matchingParty = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
    val generationKey = matchingParty?.generationKey()
    val bufferingKey = matchingParty?.bufferingKey()
    val isHost = matchingParty != null && matchingParty.hostProfileId == partyUi.activeProfileId
    val mediaLoaded = playbackSnapshot.durationMs > 0L
    val gate = partyPlaybackGate(
        party = matchingParty,
        viewerProfileId = partyUi.activeProfileId,
        hostStartReleased = generationKey != null && partyStartReleasedKey == generationKey,
        hostBufferingReleased = bufferingKey != null && partyBufferingReleasedKey == bufferingKey,
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
        if (matchingParty?.status != WatchPartyStatus.buffering) {
            partyBufferingReleasedKey = null
        }
    }

    // A guest follows a real host stall briefly, but never forever. This key deliberately excludes
    // state_updated_at: a broken host can continue publishing the same buffering state on every
    // heartbeat, and timestamp churn must not buy it another twelve seconds each time.
    LaunchedEffect(generationKey, bufferingKey, isHost) {
        if (generationKey == null || bufferingKey == null || isHost) return@LaunchedEffect
        delay(WatchPartyBufferingHoldDeadlineMs)
        val current = WatchPartyRepository.uiState.value.party
            ?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
            ?: return@LaunchedEffect
        if (current.bufferingKey() != bufferingKey) return@LaunchedEffect
        partyLog.w {
            "buffering deadline party=${current.id.shortId()} seq=${current.sequence} " +
                "positionMs=${current.positionMs}; guest resuming until host state advances"
        }
        partyBufferingReleasedKey = bufferingKey
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
        if (!gate.allowPlayback) {
            shouldPlay = false
            controller.pause()
            return@LaunchedEffect
        }
        if (isHost && partyStartReleasedKey != generationKey) {
            // Everyone has a source. The play command is what sets the authoritative clock running,
            // and it is sent from here rather than from the lobby so that it coincides with playback
            // actually beginning.
            partyLog.i { "gate released $generationKey by=allReady positionMs=${playbackSnapshot.positionMs}" }
            partyStartReleasedKey = generationKey
            shouldPlay = true
            controller.play()
            WatchPartyRepository.play(playbackSnapshot.positionMs)
        }
    }

    // What this client will tell the party it is doing.
    //
    // `shouldPlay` is the intent, and it is the third case that matters: a player that is starved
    // rather than paused reports neither playing nor loading, and calling that `paused` published a
    // deliberate pause the user never made - which every other member then obeyed, pausing and
    // seeking to a frozen position. A host whose source stutters must look like a host who is
    // buffering, because that is what it is.
    // Only a key for the immediate-publish effect below. Every heartbeat derives its own status
    // from the snapshot it just read - see the comment in the periodic loop.
    val reportedStatus = partyStatusFor(playbackSnapshot, shouldPlay)

    LaunchedEffect(generationKey) {
        if (generationKey == null) return@LaunchedEffect
        while (true) {
            // The loop used to test a value captured when it was launched, so it never stopped on
            // its own; the live party is the only thing that can say whether it should still run.
            val live = WatchPartyRepository.uiState.value.party
            if (live == null || live.status == WatchPartyStatus.ended) break
            val snapshot = playbackSnapshot
            WatchPartyRepository.heartbeat(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                speed = snapshot.playbackSpeed,
                // Derived here, from the snapshot this pass just read, and deliberately not from
                // the composable-level value: this loop is keyed only on the generation, so a value
                // computed in the composition that launched it is captured once and never updated.
                // Latching it pinned the party on whatever the host happened to be doing at the
                // instant playback started - reliably `buffering`, because `isPlaying` has not
                // turned true yet - and republished that every five seconds for the rest of the
                // session. Every guest then sat on WAITING_FOR_HOST forever while the host played on.
                status = partyStatusFor(snapshot, shouldPlay),
            )
            delay(WatchPartySnapshotIntervalMs)
        }
    }

    /**
     * Publishes a status change as soon as it happens, rather than at the next heartbeat tick.
     *
     * Commands are the fast path and this is the floor beneath them, for the same reason the poll
     * is the floor beneath the broadcast: a transport that only runs every five seconds is a
     * five second worst case for anything that does not go through a command. The short delay
     * coalesces a stutter - a source flapping between playing and starved must not spend a
     * request on every flap - while still landing a real pause inside a round trip.
     */
    LaunchedEffect(generationKey, reportedStatus) {
        if (generationKey == null) return@LaunchedEffect
        delay(250L)
        val live = WatchPartyRepository.uiState.value.party
        if (live == null || live.status == WatchPartyStatus.ended) return@LaunchedEffect
        val snapshot = playbackSnapshot
        WatchPartyRepository.heartbeat(
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            speed = snapshot.playbackSpeed,
            status = partyStatusFor(snapshot, shouldPlay),
        )
    }

    LaunchedEffect(
        matchingParty?.sequence,
        matchingParty?.stateUpdatedAt,
        partyUi.serverClockOffsetMs,
        mediaLoaded,
        partyBufferingReleasedKey,
    ) {
        val state = matchingParty ?: return@LaunchedEffect
        if (state.hostProfileId == partyUi.activeProfileId) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
        // seek lands on a player with no timeline, and the play that follows has nothing to play.
        val durationMs = playbackSnapshot.durationMs
        if (durationMs <= 0L || playbackSnapshot.isLoading) return@LaunchedEffect
        val updatedAt = runCatching { kotlin.time.Instant.parse(state.stateUpdatedAt).toEpochMilliseconds() }.getOrNull() ?: return@LaunchedEffect
        val serverNow = kotlin.time.Clock.System.now().toEpochMilliseconds() + partyUi.serverClockOffsetMs
        // A position past the end of this file is not a position. Clamping keeps a host on a longer
        // cut - or a shared clock that has run on without anyone playing - from seeking a guest into
        // empty space and leaving them there.
        val raw = expectedPartyPositionMs(state.positionMs, updatedAt, serverNow, state.status, state.playbackSpeed)
        val expected = raw.coerceIn(0L, durationMs - 1L)
        if (state.status == WatchPartyStatus.playing) {
            val correction = partyDriftCorrection(playbackSnapshot.positionMs, expected, state.playbackSpeed)
            // The line that says whether this client is actually in sync. `raw` is kept beside
            // `expected` on purpose: the two diverging means the shared clock has run past the end
            // of this file, which is the signature of a timeline that started without anybody playing.
            partyLog.i {
                "drift seq=${state.sequence} localMs=${playbackSnapshot.positionMs} expectedMs=$expected " +
                    "rawMs=$raw durationMs=$durationMs driftMs=${expected - playbackSnapshot.positionMs} " +
                    "action=${correction.kind} offsetMs=${partyUi.serverClockOffsetMs}"
            }
            when (correction.kind) {
                DriftCorrectionKind.NONE -> controller.setPlaybackSpeed(state.playbackSpeed)
                DriftCorrectionKind.SEEK -> { controller.seekTo(correction.targetPositionMs); controller.setPlaybackSpeed(state.playbackSpeed) }
                // The restore path is the NONE branch above: once the gap is back inside the
                // dead-band the next pass puts the shared speed back. That used to be done by
                // sleeping ten seconds here, which meant every snapshot arriving during the sleep
                // was skipped - the guest stopped correcting for exactly as long as it was busy
                // correcting, and the poll only delivers one snapshot every five.
                DriftCorrectionKind.TEMPORARY_SPEED ->
                    controller.setPlaybackSpeed(correction.temporarySpeed ?: state.playbackSpeed)
            }
            shouldPlay = true
            controller.play()
        } else if (state.status == WatchPartyStatus.paused || state.status == WatchPartyStatus.buffering || state.status == WatchPartyStatus.lobby) {
            val bufferingReleased = state.bufferingKey() == partyBufferingReleasedKey
            partyLog.i {
                "hold seq=${state.sequence} status=${state.status} localMs=${playbackSnapshot.positionMs} " +
                    "expectedMs=$expected durationMs=$durationMs released=$bufferingReleased"
            }
            if (state.status == WatchPartyStatus.buffering && bufferingReleased) {
                controller.setPlaybackSpeed(state.playbackSpeed)
                shouldPlay = true
                controller.play()
                return@LaunchedEffect
            }
            // `buffering` is the host stalling, not a position anyone chose. The host publishes it
            // from its own `isLoading`, `expectedPartyPositionMs` freezes at the last written
            // position for any non-playing status, and the 500ms test below then passes almost
            // every time - so on torrent and debrid sources, where the host rebuffers routinely,
            // every host stall cost every guest a seek and a stall of its own. Hold position and
            // wait: it is a transient the host leaves within seconds, and the position it froze at
            // is stale by construction.
            if (state.status != WatchPartyStatus.buffering &&
                abs(playbackSnapshot.positionMs - expected) > 500L
            ) {
                controller.seekTo(expected)
            }
            // A nudge left running into a pause would drift the guest right back out again.
            controller.setPlaybackSpeed(state.playbackSpeed)
            shouldPlay = false
            controller.pause()
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

internal fun PlayerScreenRuntime.submitPartyPlayPause(isPlaying: Boolean, positionMs: Long) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (!party.matchesPlayback(parentMetaId, playbackSession.videoId)) return
    val allowed = party.hostProfileId == ui.activeProfileId || party.controlMode == WatchPartyControlMode.collaborative
    if (!allowed) return
    // Pressing play while the gate is still holding is the force start: the host has decided not to
    // wait, and the command that follows is what tells everyone else the film has begun.
    if (isPlaying && partyStartReleasedKey != party.generationKey()) {
        partyLog.i { "gate released ${party.generationKey()} by=forceStart positionMs=$positionMs" }
    }
    if (isPlaying) partyStartReleasedKey = party.generationKey()
    scope.launch { if (isPlaying) WatchPartyRepository.play(positionMs) else WatchPartyRepository.pause(positionMs) }
}

internal fun PlayerScreenRuntime.submitPartySeek(positionMs: Long) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (!party.matchesPlayback(parentMetaId, playbackSession.videoId)) return
    if (party.hostProfileId != ui.activeProfileId && party.controlMode != WatchPartyControlMode.collaborative) return
    scope.launch { WatchPartyRepository.seek(positionMs) }
}

internal fun PlayerScreenRuntime.submitPartySpeed(speed: Float) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (!party.matchesPlayback(parentMetaId, playbackSession.videoId)) return
    if (party.hostProfileId != ui.activeProfileId && party.controlMode != WatchPartyControlMode.collaborative) return
    scope.launch { WatchPartyRepository.setSpeed(speed) }
}

/** The one line the player shows while a party is holding playback back, or has lost sync. */
internal fun WatchPartyPlayerStatus.bannerText(): String? = when {
    gate.reason == PartyHoldReason.WAITING_FOR_PARTICIPANTS -> {
        val people = if (gate.waitingOn == 1) "1 person" else "${gate.waitingOn} people"
        "Waiting for $people to pick a source · press play to start anyway"
    }
    gate.reason == PartyHoldReason.WAITING_FOR_HOST -> "Waiting for the host to start"
    gate.reason == PartyHoldReason.HOST_BUFFERING -> "Host is buffering"
    // Not "playing on your own": the snapshot poll is still running underneath, so a party without
    // its channel is following along a few seconds at a time rather than not following at all.
    // Saying the stronger thing sent testers looking for a broken party when what had actually
    // broken was one socket.
    syncDegraded -> "Live sync lost · following the party every few seconds"
    else -> null
}
