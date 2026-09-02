package com.nuvio.app.features.watchparty

import kotlin.math.abs

/**
 * Where the party is now, and what a guest should do about being somewhere else.
 *
 * The whole of the "a few seconds out of sync" report is one arithmetic fault, and it is in what
 * the old anchor paired. Party state carried `(position_ms, state_updated_at)`, where the position
 * was a host sample lifted from a 500ms Compose polling loop and the timestamp was the *server's*
 * `now()` when the RPC executed. Those are two different instants: the sample was already up to
 * 500ms old before it was serialized, and then it crossed the network. Every guest computed
 * `position + (serverNow - state_updated_at)` and so ran behind the host by that entire gap - a
 * constant, re-applied on every anchor, and invisible from either side because both machines
 * computed the same wrong number.
 *
 * A [PartyTick] carries the position and **the instant that position was read**, on one clock. The
 * propagation delay is then measured rather than absorbed, and the residual error is what is left
 * after that: clock estimate error, and the genuine difference between two machines' playback
 * rates. Both are tens of milliseconds.
 *
 * Import-free, so `scripts/run-pure-suites.sh` runs this file rather than a copy of it.
 */

/** Host cadence while the party is playing. Twice a second is what makes a nudge a nudge. */
const val WatchPartyTickIntervalMs = 500L

/** Host cadence while nothing is moving: enough to prove liveness, cheap enough to ignore. */
const val WatchPartyIdleTickIntervalMs = 2_000L

/**
 * Past this a tick is not evidence about now.
 *
 * Four idle intervals' worth of silence, so a paused party at 2s ticks is never mistaken for a host
 * that has gone away, while a playing one is noticed within a couple of missed ticks.
 */
const val WatchPartyTickStaleMs = 4_000L

/**
 * Drift the guest lives with: below this, correcting is more visible than the error.
 *
 * Was 750ms, which was never a judgement about what is visible - it was the width of the bias
 * above, so the policy defined the fault as being in sync. With an anchor that carries its own
 * timestamp the steady-state error is tens of milliseconds, and 200ms is comfortably outside it
 * while still being under the threshold at which two rooms sound out of step.
 */
const val WatchPartyDriftDeadbandMs = 200L

/**
 * Above this a nudge cannot close the gap in reasonable time, so the guest takes the stall and
 * seeks.
 *
 * Was 4s, chosen when a seek was the expensive escalation from a nudge that could not close
 * anything. A seek is now scheduled - see [PartySeekPlan] - so it lands exactly instead of landing
 * behind, which makes it affordable enough to use on the gaps that actually matter.
 */
const val WatchPartySeekThresholdMs = 1_500L

/** How long a speed nudge is given to close the gap it was chosen for. */
const val WatchPartyNudgeWindowMs = 5_000L

/**
 * Bound on the nudge, as a fraction of the party's shared speed. The player preserves pitch, so
 * +-10% is unobjectionable across a few seconds of dialogue; past that it is audible.
 */
const val WatchPartyMaxNudgeRate = 0.10f

/**
 * Consecutive ticks over [WatchPartySeekThresholdMs] before a seek is taken.
 *
 * At the tick rate this is half a second of agreement. One sample can be wrong - a position read
 * taken across a frame drop, a tick that queued behind a retransmit - and a seek taken on one bad
 * sample costs the stream its buffer to fix an error that was not there.
 */
const val WatchPartySeekStreakToCorrect = 2

/**
 * A host position sample paired with the instant it was read, in party time.
 *
 * [sequence] and [contentGeneration] are carried so a tick can be matched against the durable
 * snapshot: a tick for content this client has not been told about yet is not applied, it is a
 * reason to go and ask.
 */
data class PartyTick(
    val partyId: String,
    val contentGeneration: Int,
    val sequence: Long,
    val status: WatchPartyStatus,
    val positionMs: Long,
    /** When [positionMs] was read off the host's player, on the party clock. */
    val capturedAtPartyMs: Long,
    val playbackSpeed: Float,
    val durationMs: Long,
)

/**
 * Where the party is at [partyNowMs].
 *
 * Only a playing party advances. Everything else holds at the position it stopped at, which is why
 * a host that stutters must publish `buffering` and not `paused`: both freeze here, but only one of
 * them tells a guest that the frozen position is stale by construction.
 */
fun PartyTick.expectedPositionMs(partyNowMs: Long): Long {
    if (status != WatchPartyStatus.playing) return positionMs.coerceAtLeast(0L)
    val elapsed = (partyNowMs - capturedAtPartyMs).coerceAtLeast(0L)
    // Double, not Float: adding a Float promotes the Long position to Float, which starts
    // quantising millisecond values after roughly 4h39m of playback.
    return (positionMs.toDouble() + elapsed.toDouble() * playbackSpeed.toDouble())
        .toLong()
        .coerceAtLeast(0L)
}

fun PartyTick.isStale(partyNowMs: Long): Boolean =
    partyNowMs - capturedAtPartyMs > WatchPartyTickStaleMs

/**
 * Whether this tick says anything the held one does not.
 *
 * Ordered on the capture instant rather than on [sequence], because `party_heartbeat` moves the
 * host's position without bumping a sequence and ticks do not touch the database at all. A socket
 * can deliver two ticks out of order; the older one is then simply dropped.
 */
fun PartyTick.supersedes(held: PartyTick?): Boolean = when {
    held == null -> true
    held.partyId != partyId -> true
    contentGeneration != held.contentGeneration -> contentGeneration > held.contentGeneration
    else -> capturedAtPartyMs > held.capturedAtPartyMs
}

/**
 * The database anchor, for the degraded ladder.
 *
 * Still biased by whatever the host's sample age was - that is what the tick exists to fix - but
 * this is what a client has when the socket is down or the other end is an older build, and it is
 * better than nothing by five seconds.
 */
fun expectedPartyPositionMs(
    statePositionMs: Long,
    stateUpdatedAtEpochMs: Long,
    serverNowEpochMs: Long,
    status: WatchPartyStatus,
    playbackSpeed: Float,
): Long {
    if (status != WatchPartyStatus.playing) return statePositionMs.coerceAtLeast(0L)
    val elapsed = (serverNowEpochMs - stateUpdatedAtEpochMs).coerceAtLeast(0L)
    return (statePositionMs.toDouble() + elapsed.toDouble() * playbackSpeed.toDouble())
        .toLong()
        .coerceAtLeast(0L)
}

enum class DriftCorrectionKind { NONE, TEMPORARY_SPEED, SEEK }

data class DriftCorrection(
    val kind: DriftCorrectionKind,
    val targetPositionMs: Long,
    val temporarySpeed: Float? = null,
    val restoreSpeed: Float,
)

/**
 * Chooses how a guest closes the gap between where it is and where the party says it should be.
 *
 * The [DriftCorrectionKind.SEEK] target is the expected position **exactly**, with no lead. The old
 * policy added a fixed 2.5s because seeking to where the party is now lands the guest where the
 * party was by the time the reload finishes - a real problem, solved here by not guessing: a
 * corrective seek is scheduled through [partySeekPlan], which holds the guest paused until the
 * party actually reaches the position it seeked to. Being wrong about how long a reload takes then
 * costs a slightly longer or shorter pause, never a position error.
 */
fun partyDriftCorrection(localPositionMs: Long, expectedPositionMs: Long, sharedSpeed: Float): DriftCorrection {
    val drift = expectedPositionMs - localPositionMs
    val magnitude = abs(drift)
    return when {
        magnitude <= WatchPartyDriftDeadbandMs ->
            DriftCorrection(DriftCorrectionKind.NONE, expectedPositionMs, restoreSpeed = sharedSpeed)
        magnitude <= WatchPartySeekThresholdMs -> DriftCorrection(
            kind = DriftCorrectionKind.TEMPORARY_SPEED,
            targetPositionMs = expectedPositionMs,
            temporarySpeed = partyNudgeSpeed(drift, sharedSpeed),
            restoreSpeed = sharedSpeed,
        )
        else -> DriftCorrection(
            kind = DriftCorrectionKind.SEEK,
            targetPositionMs = expectedPositionMs,
            restoreSpeed = sharedSpeed,
        )
    }
}

/**
 * The playback rate that closes [driftMs] over [WatchPartyNudgeWindowMs] while the party goes on
 * advancing at [sharedSpeed] - so the guest has to cover the party's advance *and* the gap.
 *
 * Clamped as a fraction of the shared speed rather than in absolute terms, so a party watching at
 * 1.5x is nudged by the same proportion a party at 1x is.
 */
internal fun partyNudgeSpeed(driftMs: Long, sharedSpeed: Float): Float {
    val rate = (driftMs.toFloat() / WatchPartyNudgeWindowMs)
        .coerceIn(-WatchPartyMaxNudgeRate, WatchPartyMaxNudgeRate)
    return (sharedSpeed * (1f + rate)).coerceIn(0.25f, 4f)
}

/**
 * Carries the one thing a correction decision cannot read off a single sample: whether the gap is
 * still there.
 */
data class DriftTracker(val seekStreak: Int = 0)

data class DriftOutcome(val correction: DriftCorrection, val tracker: DriftTracker)

/**
 * The correction for this tick, with the seek held back until the gap has been seen twice.
 *
 * A gap wide enough to seek for that closes on its own by the next tick was a measurement, not a
 * drift, and seeking for it costs the stream its buffer for nothing.
 */
fun DriftTracker.next(localPositionMs: Long, expectedPositionMs: Long, sharedSpeed: Float): DriftOutcome {
    val correction = partyDriftCorrection(localPositionMs, expectedPositionMs, sharedSpeed)
    if (correction.kind != DriftCorrectionKind.SEEK) {
        return DriftOutcome(correction, DriftTracker(seekStreak = 0))
    }
    val streak = seekStreak + 1
    if (streak < WatchPartySeekStreakToCorrect) {
        // Nudge as hard as the policy allows meanwhile, so the half second spent confirming the gap
        // is not also spent ignoring it.
        return DriftOutcome(
            correction = DriftCorrection(
                kind = DriftCorrectionKind.TEMPORARY_SPEED,
                targetPositionMs = expectedPositionMs,
                temporarySpeed = partyNudgeSpeed(expectedPositionMs - localPositionMs, sharedSpeed),
                restoreSpeed = sharedSpeed,
            ),
            tracker = DriftTracker(seekStreak = streak),
        )
    }
    return DriftOutcome(correction, DriftTracker(seekStreak = 0))
}
