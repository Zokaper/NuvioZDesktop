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
 * How close a seek has to land before it counts as having happened.
 *
 * An exact seek lands within a frame, so this is slack for the position read rather than for the
 * seek: on desktop the position comes from mpv's `time-pos`, which does not advance while a seek is
 * in flight, and a playing party has moved on by a poll interval by the time it does.
 */
const val WatchPartySeekLandedToleranceMs = 250L

/** How long a seek is given to land before the correction gives up and lets the nudge have it. */
const val WatchPartySeekLandingTimeoutMs = 3_000L

/** How often the landing is checked. Short, because everything downstream is waiting on it. */
const val WatchPartySeekLandingPollMs = 50L

/**
 * How long a guest may be buffering before the host holds the party for it.
 *
 * Short enough that nobody watches on alone through someone else's stall, long enough that a
 * routine one-second rebuffer on a torrent source does not stop the film for everyone.
 *
 * **Strictly greater than the longest hold a guest takes on its own account**
 * ([WatchPartySeekRecoveryLeadMs] plus a landing), because those two being equal is what made the
 * host pause and resume the party on every corrective seek: the guest reported `buffering` for
 * exactly the grace, so the guard fired every time and cleared ~300ms later.
 */
const val WatchPartyGuestBufferingGraceMs = 2_500L

/**
 * How long a guest has to be *playing* again - not merely "no longer buffering" - before the host
 * starts the party back up.
 *
 * A guest that has finished aligning is paused on the right frame and not yet running, and reading
 * that as recovery is what turned one stall into a pause, a resume, and another stall.
 */
const val WatchPartyStallRecoverySettleMs = 400L

/** The least time between two automatic holds, so one flapping source cannot machine-gun the party. */
const val WatchPartyStallHoldCooldownMs = 5_000L

/** Holds inside [WatchPartyStallHoldWindowMs] after which the host stops holding for this content. */
const val WatchPartyStallHoldLimit = 3
const val WatchPartyStallHoldWindowMs = 30_000L

/**
 * The longest a single member may hold the party before the party goes on without them.
 *
 * A member that stops answering - a closed laptop, a socket that died without a close - is
 * indistinguishable from one that is buffering, and waiting for it forever is a dead party rather
 * than a considerate one.
 */
const val WatchPartyStallHoldMaxMs = 30_000L

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
    /**
     * Whether playback runs once the barrier is reached.
     *
     * Carried rather than assumed, and it is a [PartyCommandKind.seek] that needs it: scrubbing a
     * *paused* party is the ordinary way of finding a scene, and a seek that resumes everybody
     * turns it into a scrub-and-play. It reached the field - the host paused, dragged the bar, and
     * the film started again on every member. Defaults true so a command from a build that does not
     * send it behaves the way that build's own barrier did.
     */
    val playAfter: Boolean = true,
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
    // A seek that is not going to resume is not late in any meaningful sense - nothing is running
    // for the overshoot to have advanced - so it lands exactly where it was aimed.
    val target = if (command.playAfter) {
        command.startPositionMs + (overshoot.toDouble() * speed.toDouble()).toLong()
    } else {
        command.startPositionMs
    }
    val aligned = kotlin.math.abs(localPositionMs - target) <= WatchPartyBarrierAlignToleranceMs
    return PartyBarrierPlan(
        kind = command.kind,
        seekToMs = if (aligned) null else target,
        holdMs = hold,
        playAfter = command.playAfter,
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
 * A seek this client has issued and is waiting to see land.
 *
 * The position a correction is measured against comes from the engine, and on desktop that is mpv's
 * `time-pos`, which does not advance while a seek is in flight and can read *backwards* to a sample
 * from seconds ago. Evaluating drift against it is how one corrective seek became seven: each pass
 * measured the pre-seek position, called it a fresh gap, and seeked again to a target further ahead
 * than the last one. Nothing is measured or issued while one of these is outstanding.
 *
 * It expires on its own. The alternative - a flag some path has to remember to clear - is a wedged
 * party the first time an exception, a content change or a released player skips the clear.
 */
data class PendingPartySeek(
    val targetMs: Long,
    val issuedAtMs: Long,
    val deadlineAtMs: Long,
) {
    fun hasLanded(positionMs: Long): Boolean =
        kotlin.math.abs(positionMs - targetMs) <= WatchPartySeekLandedToleranceMs

    fun isOutstanding(nowMs: Long, positionMs: Long): Boolean =
        nowMs < deadlineAtMs && !hasLanded(positionMs)

    fun timedOut(nowMs: Long, positionMs: Long): Boolean =
        nowMs >= deadlineAtMs && !hasLanded(positionMs)
}

fun pendingPartySeek(
    targetMs: Long,
    nowMs: Long,
    timeoutMs: Long = WatchPartySeekLandingTimeoutMs,
): PendingPartySeek = PendingPartySeek(
    targetMs = targetMs,
    issuedAtMs = nowMs,
    deadlineAtMs = nowMs + timeoutMs,
)

/**
 * Who the host is waiting for, when the party waits for everyone.
 *
 * A guest that stalls used to be left behind and then dragged back by a seek, which on a torrent or
 * debrid source is most of what a party feels like. Holding for them instead costs the people who
 * are fine a pause they can see the reason for.
 *
 * The transition is in [advance] rather than in the query, because both edges are about elapsed time
 * rather than about a message: a stall becomes a hold when the grace runs out, and a hold ends when
 * the guest has been *playing* again for the settle. Reading "no longer buffering" as recovery is
 * what made one stall produce a pause, a resume, and another stall a moment later - the guest was
 * parked on the right frame and not yet running, which is neither.
 */
data class GuestBufferingWatch(
    val bufferingSinceByProfile: Map<String, Long> = emptyMap(),
    val playingSinceByProfile: Map<String, Long> = emptyMap(),
    val heldSinceByProfile: Map<String, Long> = emptyMap(),
) {
    /** Members the host is holding the party for right now. */
    val holdingProfiles: List<String> get() = heldSinceByProfile.keys.sorted()

    fun observe(profileId: String, status: WatchPartyStatus, partyNowMs: Long): GuestBufferingWatch =
        when (status) {
            WatchPartyStatus.buffering -> copy(
                bufferingSinceByProfile = if (bufferingSinceByProfile.containsKey(profileId)) {
                    bufferingSinceByProfile
                } else {
                    bufferingSinceByProfile + (profileId to partyNowMs)
                },
                playingSinceByProfile = playingSinceByProfile - profileId,
            )
            WatchPartyStatus.playing -> copy(
                bufferingSinceByProfile = bufferingSinceByProfile - profileId,
                playingSinceByProfile = if (playingSinceByProfile.containsKey(profileId)) {
                    playingSinceByProfile
                } else {
                    playingSinceByProfile + (profileId to partyNowMs)
                },
            )
            // A deliberate pause, a lobby, an ended party: not a stall, and not a recovery either.
            // A member sitting here keeps an existing hold alive until [WatchPartyStallHoldWindowMs]
            // releases it, rather than ending one it has not actually come back from.
            else -> copy(
                bufferingSinceByProfile = bufferingSinceByProfile - profileId,
                playingSinceByProfile = playingSinceByProfile - profileId,
            )
        }

    /**
     * Promotes stalls that have outlasted the grace into holds, and releases holds that have
     * recovered - or that have gone quiet for so long that holding for them is just a dead party.
     */
    fun advance(partyNowMs: Long): GuestBufferingWatch {
        val next = heldSinceByProfile.toMutableMap()
        for ((profileId, since) in bufferingSinceByProfile) {
            if (partyNowMs - since < WatchPartyGuestBufferingGraceMs) continue
            if (!next.containsKey(profileId)) next[profileId] = partyNowMs
        }
        val released = next.keys.filter { profileId ->
            if (bufferingSinceByProfile.containsKey(profileId)) return@filter false
            val playingSince = playingSinceByProfile[profileId]
            val settled = playingSince != null && partyNowMs - playingSince >= WatchPartyStallRecoverySettleMs
            // A member that answers nothing at all cannot be waited for forever; the party is worth
            // more than one silent client.
            val abandoned = partyNowMs - (next[profileId] ?: partyNowMs) >= WatchPartyStallHoldMaxMs
            settled || abandoned
        }
        released.forEach { next.remove(it) }
        return if (next == heldSinceByProfile) this else copy(heldSinceByProfile = next)
    }

    fun forget(profileId: String): GuestBufferingWatch = copy(
        bufferingSinceByProfile = bufferingSinceByProfile - profileId,
        playingSinceByProfile = playingSinceByProfile - profileId,
        heldSinceByProfile = heldSinceByProfile - profileId,
    )
}

/**
 * How often a host has held the party for a stalled guest, and whether it should keep doing it.
 *
 * Off is already a legitimate answer to "wait for everyone", and this is what turns it off on the
 * host's behalf when the answer stops helping: a source that flaps produces a hold, a resume, and
 * another hold, and a party spent stopping and starting is worse than one member being a second
 * behind. Deliberately per content generation - a new episode is a new stream and deserves the
 * benefit of the doubt again.
 */
data class StallHoldBudget(val holdsAtMs: List<Long> = emptyList()) {
    fun mayHold(partyNowMs: Long): Boolean {
        val recent = holdsAtMs.filter { partyNowMs - it < WatchPartyStallHoldWindowMs }
        if (recent.size >= WatchPartyStallHoldLimit) return false
        val last = recent.maxOrNull() ?: return true
        return partyNowMs - last >= WatchPartyStallHoldCooldownMs
    }

    fun exhausted(partyNowMs: Long): Boolean =
        holdsAtMs.count { partyNowMs - it < WatchPartyStallHoldWindowMs } >= WatchPartyStallHoldLimit

    fun record(partyNowMs: Long): StallHoldBudget = StallHoldBudget(
        holdsAtMs = (holdsAtMs + partyNowMs).filter { partyNowMs - it < WatchPartyStallHoldWindowMs },
    )
}
