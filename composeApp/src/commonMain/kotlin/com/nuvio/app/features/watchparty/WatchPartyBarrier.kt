package com.nuvio.app.features.watchparty

/**
 * Transport that happens at an instant everybody agrees on, instead of as fast as each machine can
 * manage.
 *
 * The jump on pause and resume was never a correction fault - it was the absence of a shared
 * instant. The host pressed play at position P and published it; the guest received it a
 * propagation delay later, computed `P + delay`, and jumped forward by exactly the delay it had
 * just spent waiting. On pause the same thing ran backwards: the guest played on until the pause
 * arrived and was then seeked back. Both are structural, and no tightening of the correction bands
 * removes either.
 *
 * A barrier is the fix. Play and seek carry the position **and the party instant at which to be
 * playing it**; every client, the host included, parks on that frame and starts when the party
 * clock says to. Nobody catches up, because nobody was behind.
 *
 * Pause is the deliberate exception and carries no lead: the host pauses under its own finger and
 * guests pause on receipt, then align while paused. A correction under a pause is one frame
 * changing rather than a jump, and it lands before the next resume - so the resume is clean.
 * Pausing 60ms apart is worth far more than pausing 300ms late together.
 *
 * Import-free, so `scripts/run-pure-suites.sh` runs this file rather than a copy of it.
 */

/** Floor on the barrier lead. Below this a slow local seek misses its own start. */
const val WatchPartyBarrierMinLeadMs = 250L

/** Ceiling on the barrier lead. Past this the host feels their own button lag. */
const val WatchPartyBarrierMaxLeadMs = 800L

/** Headroom over the worst observed one-way latency, for the receiver's own scheduling jitter. */
const val WatchPartyBarrierLatencyMarginMs = 80L

/**
 * Position error tolerated before a barrier takes a seek to close it.
 *
 * A seek costs the stream its buffer, so anything the drift nudge can absorb after the start is
 * cheaper closed that way. Deliberately wider than [WatchPartyPausedAlignToleranceMs], because that
 * one is paid while paused.
 */
const val WatchPartyBarrierAlignToleranceMs = 120L

/** Position error tolerated on a pause. Closing it costs one frame, so the bar is lower. */
const val WatchPartyPausedAlignToleranceMs = 100L

/**
 * How long a corrective seek is given to reload before the guest resumes.
 *
 * Not an estimate of the reload cost that has to be right. The guest seeks to where the party will
 * be at the end of this hold and waits there, so a hold that is too long costs a slightly longer
 * pause and a hold that is too short costs the overrun - neither puts a standing error into the
 * position, which is what the old fixed 2.5s seek lead did whenever it guessed wrong.
 */
const val WatchPartySeekRecoveryLeadMs = 1_500L

/** Commands remembered for de-duplication. A two hour party issues a few hundred at most. */
const val WatchPartyCommandLogSize = 64

/**
 * How long a guest may be buffering before the host holds the party for it.
 *
 * Short enough that nobody watches on alone through someone else's stall, long enough that a
 * routine one-second rebuffer on a torrent source does not stop the film for everyone.
 */
const val WatchPartyGuestBufferingGraceMs = 1_500L

enum class PartyCommandKind { play, pause, seek, speed }

/**
 * A transport action, addressed to an instant rather than to a moment of arrival.
 *
 * [counter] is per sender and strictly increasing, which is what lets a client drop a command that
 * overtook a newer one on the socket. [issuedByProfileId] is checked against the host named by the
 * durable snapshot before anything is applied - a payload cannot promote itself.
 */
data class PartyCommand(
    val commandId: String,
    val kind: PartyCommandKind,
    val issuedByProfileId: String,
    val counter: Long,
    val contentGeneration: Int,
    val startPositionMs: Long,
    /** The party instant at which to be at [startPositionMs]. Ignored for [PartyCommandKind.pause]. */
    val startAtPartyMs: Long,
    val playbackSpeed: Float,
)

/**
 * What this client does about a command, and when.
 *
 * [seekToMs] is null when the local position is already close enough to be left alone, which is the
 * ordinary case for a resume: everyone is parked where the pause left them.
 */
data class PartyBarrierPlan(
    val kind: PartyCommandKind,
    val seekToMs: Long?,
    val holdMs: Long,
    val playAfter: Boolean,
    val speed: Float,
)

/** The lead the host puts on a barrier, from the worst round trip it has measured. */
fun watchPartyBarrierLeadMs(maxOneWayLatencyMs: Long): Long =
    (maxOneWayLatencyMs + WatchPartyBarrierLatencyMarginMs)
        .coerceIn(WatchPartyBarrierMinLeadMs, WatchPartyBarrierMaxLeadMs)

/**
 * Turns a command into what this client should do about it.
 *
 * A command whose barrier has already passed - a slow socket, a client that was busy reloading - is
 * not dropped and is not applied at its stale position either. The overshoot is carried into the
 * target, so a late client lands where the party is *now* and starts immediately, which is the same
 * answer the drift policy would have reached one tick later and several hundred milliseconds worse.
 */
fun partyBarrierPlan(
    command: PartyCommand,
    localPositionMs: Long,
    partyNowMs: Long,
): PartyBarrierPlan {
    val speed = command.playbackSpeed
    if (command.kind == PartyCommandKind.pause) {
        val aligned = kotlin.math.abs(localPositionMs - command.startPositionMs) <= WatchPartyPausedAlignToleranceMs
        return PartyBarrierPlan(
            kind = PartyCommandKind.pause,
            seekToMs = if (aligned) null else command.startPositionMs,
            holdMs = 0L,
            playAfter = false,
            speed = speed,
        )
    }
    val hold = (command.startAtPartyMs - partyNowMs).coerceAtLeast(0L)
    val overshoot = (partyNowMs - command.startAtPartyMs).coerceAtLeast(0L)
    if (command.kind == PartyCommandKind.speed) {
        return PartyBarrierPlan(
            kind = PartyCommandKind.speed,
            seekToMs = null,
            holdMs = hold,
            playAfter = true,
            speed = speed,
        )
    }
    val target = command.startPositionMs + (overshoot.toDouble() * speed.toDouble()).toLong()
    val aligned = kotlin.math.abs(localPositionMs - target) <= WatchPartyBarrierAlignToleranceMs
    return PartyBarrierPlan(
        kind = command.kind,
        seekToMs = if (aligned) null else target,
        holdMs = hold,
        playAfter = true,
        speed = speed,
    )
}

/** A corrective seek, expressed the same way a host command is: a position and when to be at it. */
data class PartySeekPlan(val seekToMs: Long, val resumeAtPartyMs: Long)

/**
 * Where to seek so that resuming after the reload lands in sync rather than behind it.
 *
 * The caller clamps [PartySeekPlan.seekToMs] against its own duration - the tick carries the
 * host's, and a guest on a differently cut file must not be seeked past its own end.
 */
fun partySeekPlan(
    tick: PartyTick,
    partyNowMs: Long,
    recoveryLeadMs: Long = WatchPartySeekRecoveryLeadMs,
): PartySeekPlan {
    val resumeAt = partyNowMs + recoveryLeadMs
    return PartySeekPlan(seekToMs = tick.expectedPositionMs(resumeAt), resumeAtPartyMs = resumeAt)
}

/**
 * What has already been obeyed.
 *
 * Two guards, because they catch different things: the identifier catches the same command arriving
 * twice - the socket redelivering, or the durable snapshot confirming what the broadcast already
 * did - and the per-sender counter catches an older command arriving after a newer one.
 */
data class PartyCommandLog(
    val appliedIds: List<String> = emptyList(),
    val lastCounterByProfile: Map<String, Long> = emptyMap(),
) {
    fun accepts(command: PartyCommand): Boolean {
        if (command.commandId in appliedIds) return false
        val last = lastCounterByProfile[command.issuedByProfileId] ?: return true
        return command.counter > last
    }

    fun record(command: PartyCommand): PartyCommandLog = PartyCommandLog(
        appliedIds = (appliedIds + command.commandId).takeLast(WatchPartyCommandLogSize),
        lastCounterByProfile = lastCounterByProfile + (command.issuedByProfileId to command.counter),
    )
}

/**
 * Who the host is waiting for, when the party waits for everyone.
 *
 * A guest that stalls used to be left behind and then dragged back by a seek, which on a torrent or
 * debrid source is most of what a party feels like. Holding for them instead costs the people who
 * are fine a pause they can see the reason for.
 */
data class GuestBufferingWatch(val bufferingSinceByProfile: Map<String, Long> = emptyMap()) {
    fun observe(profileId: String, buffering: Boolean, partyNowMs: Long): GuestBufferingWatch = when {
        !buffering -> GuestBufferingWatch(bufferingSinceByProfile - profileId)
        bufferingSinceByProfile.containsKey(profileId) -> this
        else -> GuestBufferingWatch(bufferingSinceByProfile + (profileId to partyNowMs))
    }

    fun forget(profileId: String): GuestBufferingWatch =
        GuestBufferingWatch(bufferingSinceByProfile - profileId)

    /** Members stalled for longer than the grace, so a flap costs the party nothing. */
    fun holdingProfiles(partyNowMs: Long): List<String> = bufferingSinceByProfile
        .filterValues { since -> partyNowMs - since >= WatchPartyGuestBufferingGraceMs }
        .keys
        .sorted()
}
