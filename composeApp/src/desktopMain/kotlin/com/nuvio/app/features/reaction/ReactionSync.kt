package com.nuvio.app.features.reaction

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One continuous stretch where the reaction video shows the episode: episode time
 * [episodeStartMs, episodeEndMs) plays in the reaction from [reactionStartMs], advancing at [rate]
 * reaction-ms per episode-ms (uploads are sometimes sped up a few percent to dodge matching).
 */
internal data class SyncSegment(
    val episodeStartMs: Long,
    val episodeEndMs: Long,
    val reactionStartMs: Long,
    val rate: Double = 1.0,
    val confidence: Double = 1.0,
) {
    init {
        require(episodeEndMs > episodeStartMs) { "empty segment $episodeStartMs..$episodeEndMs" }
        require(rate > 0.0)
    }

    fun contains(episodeMs: Long): Boolean = episodeMs in episodeStartMs until episodeEndMs

    fun reactionAt(episodeMs: Long): Long =
        reactionStartMs + ((episodeMs - episodeStartMs) * rate).roundToLong()
}

internal sealed interface SyncLocation {
    data class InSegment(val segment: SyncSegment, val reactionMs: Long) : SyncLocation

    /** The episode is somewhere the reaction skipped. [next] is where reactions resume, if anywhere. */
    data class Gap(val next: SyncSegment?) : SyncLocation
}

/** The episode -> reaction map. Segments never overlap in episode time (the builder guarantees it). */
internal class SyncMap(segments: List<SyncSegment>) {
    val segments: List<SyncSegment> = segments.sortedBy { it.episodeStartMs }

    init {
        this.segments.zipWithNext().forEach { (a, b) ->
            require(a.episodeEndMs <= b.episodeStartMs) { "overlapping segments $a / $b" }
        }
    }

    fun locate(episodeMs: Long): SyncLocation {
        // Few segments (tens), so a linear scan is clearer than a binary search and just as fast.
        for (segment in segments) {
            if (segment.contains(episodeMs)) return SyncLocation.InSegment(segment, segment.reactionAt(episodeMs))
            if (segment.episodeStartMs > episodeMs) return SyncLocation.Gap(segment)
        }
        return SyncLocation.Gap(null)
    }

    companion object {
        /**
         * The manual map: reaction time = episode time + [offsetMs], everywhere both exist.
         * A positive offset means the reaction starts later in its own timeline (an intro before
         * the episode footage); a negative one means the episode's opening is not in the reaction.
         */
        fun manualOffset(offsetMs: Long, reactionDurationMs: Long? = null): SyncMap {
            val episodeStart = maxOf(0L, -offsetMs)
            val reactionStart = episodeStart + offsetMs
            val episodeEnd = reactionDurationMs
                ?.let { duration -> duration - offsetMs }
                ?.takeIf { it > episodeStart }
                ?: Long.MAX_VALUE
            return SyncMap(listOf(SyncSegment(episodeStart, episodeEnd, reactionStart)))
        }
    }
}

/** What the playback master (Nuvio Z's player) is doing right now. */
internal data class MasterSample(
    val positionMs: Long,
    val paused: Boolean,
    val speed: Double,
    /** Seeking or refilling its cache: the master's clock is not advancing. */
    val stalled: Boolean = false,
)

/** What the reaction player is doing right now. */
internal data class FollowerSample(
    val positionMs: Long?,
    val paused: Boolean,
    val speed: Double,
    val seeking: Boolean,
    val durationMs: Long? = null,
)

internal sealed interface FollowerCommand {
    data class Seek(val positionMs: Long, val exact: Boolean = true) : FollowerCommand
    data class SetSpeed(val speed: Double) : FollowerCommand
    data class SetPaused(val paused: Boolean) : FollowerCommand
}

internal enum class StageVisibility { SHOWING, GAP }

internal data class SyncDecision(
    val commands: List<FollowerCommand>,
    val visibility: StageVisibility,
    /** Episode ms until reactions resume; null when not in a gap or nothing follows. */
    val resumesInMs: Long?,
    /** Follower minus target (positive = reaction ahead); null when not comparable. */
    val errorMs: Long?,
    val waitingForSeek: Boolean,
)

internal data class SyncTuning(
    /** Reaction behind by more than this while playing: seek (with lead) rather than speed up. */
    val seekThresholdBehindMs: Long = 600,
    /** Reaction ahead by more than this: hold it on its frame until the episode catches up... */
    val holdThresholdAheadMs: Long = 120,
    /** ...unless it is so far ahead that waiting would freeze it for too long: then seek. */
    val holdMaxAheadMs: Long = 2_500,
    /** A hold ends when the episode is within this of the held frame. */
    val holdReleaseMs: Long = 15,
    /** Paused, nothing moves, so line it up exactly. */
    val seekThresholdPausedMs: Long = 60,
    /** Below this the two are considered in sync. */
    val deadbandMs: Long = 35,
    /** The correction removes the error over roughly this long. */
    val correctionHorizonMs: Double = 4_000.0,
    val maxSpeedCorrection: Double = 0.05,
    /** A gap this short is noise from the matcher, not a cut: play straight through it. */
    val bridgeGapsShorterThanMs: Long = 1_500,
    /** In a gap, the follower is parked at the next segment unless it is already this close. */
    val prepositionToleranceMs: Long = 400,
    val initialSeekLatencyMs: Double = 600.0,
)

/**
 * Keeps the reaction player on the episode. Pure: give it samples, get commands. It never touches
 * the master - the episode is the playback master and is only ever read.
 */
internal class ReactionSyncEngine(private val tuning: SyncTuning = SyncTuning()) {
    private var pendingSeekTarget: Long? = null
    private var pendingSeekIssuedAt: Long = 0
    private var pendingSeekSawSeeking = false

    /** Exponential average of how long a seek takes to land, so seeks while playing aim ahead. */
    var seekLatencyMs: Double = tuning.initialSeekLatencyMs
        private set

    private var holding = false

    fun reset() {
        pendingSeekTarget = null
        pendingSeekSawSeeking = false
        holding = false
    }

    /** A first guess before any seek has been measured: ~100 ms for a local file, ~1.5 s for the web. */
    fun seedSeekLatency(ms: Double) {
        seekLatencyMs = ms
    }

    fun step(nowMs: Long, master: MasterSample, follower: FollowerSample, map: SyncMap): SyncDecision {
        val location = bridgeShortGap(map.locate(master.positionMs), master.positionMs)
        val commands = mutableListOf<FollowerCommand>()

        val waiting = settlePendingSeek(nowMs, follower)

        if (location is SyncLocation.Gap) {
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            val next = location.next
            if (next != null && !waiting) {
                val parked = follower.positionMs?.let { abs(it - next.reactionStartMs) <= tuning.prepositionToleranceMs } ?: false
                if (!parked) issueSeek(nowMs, next.reactionStartMs, commands)
            }
            return SyncDecision(
                commands = commands,
                visibility = StageVisibility.GAP,
                resumesInMs = next?.let { it.episodeStartMs - master.positionMs },
                errorMs = null,
                waitingForSeek = waiting || pendingSeekTarget != null,
            )
        }

        location as SyncLocation.InSegment
        val masterRunning = !master.paused && !master.stalled
        val reactionDuration = follower.durationMs
        if (reactionDuration != null && location.reactionMs >= reactionDuration) {
            // The map runs past the end of the reaction video: nothing left to show.
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            return SyncDecision(commands, StageVisibility.GAP, null, null, waiting)
        }

        if (waiting) {
            // Hold still until the seek lands; resuming early would play from the wrong place.
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            return SyncDecision(commands, StageVisibility.SHOWING, null, null, true)
        }

        val position = follower.positionMs
        val baseSpeed = master.speed * location.segment.rate
        if (position == null) {
            issueSeek(nowMs, location.reactionMs, commands)
            return SyncDecision(commands, StageVisibility.SHOWING, null, null, true)
        }

        val error = position - location.reactionMs
        if (!masterRunning) {
            holding = false
            if (abs(error) > tuning.seekThresholdPausedMs) issueSeek(nowMs, location.reactionMs, commands)
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            return SyncDecision(commands, StageVisibility.SHOWING, null, error, pendingSeekTarget != null)
        }

        // Ahead: freeze on the current frame and let the episode catch up. Exact to a tick, and
        // costs nothing - no seek, no speed change.
        if (holding) {
            if (error > tuning.holdReleaseMs && error <= tuning.holdMaxAheadMs) {
                if (!follower.paused) commands += FollowerCommand.SetPaused(true)
                return SyncDecision(commands, StageVisibility.SHOWING, null, error, false)
            }
            holding = false
        } else if (error > tuning.holdThresholdAheadMs && error <= tuning.holdMaxAheadMs) {
            holding = true
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            return SyncDecision(commands, StageVisibility.SHOWING, null, error, false)
        }

        if (error > tuning.holdMaxAheadMs || -error > tuning.seekThresholdBehindMs) {
            // Aim where the episode will be when the seek lands; any overshoot is absorbed by a hold.
            val lead = (seekLatencyMs * baseSpeed).roundToLong()
            issueSeek(nowMs, location.reactionMs + lead, commands)
            if (!follower.paused) commands += FollowerCommand.SetPaused(true)
            return SyncDecision(commands, StageVisibility.SHOWING, null, error, true)
        }

        if (follower.paused) commands += FollowerCommand.SetPaused(false)
        val desiredSpeed = if (abs(error) > tuning.deadbandMs) {
            val correction = (-error / tuning.correctionHorizonMs)
                .coerceIn(-tuning.maxSpeedCorrection, tuning.maxSpeedCorrection)
            baseSpeed * (1.0 + correction)
        } else {
            baseSpeed
        }
        if (abs(desiredSpeed - follower.speed) > 0.002) commands += FollowerCommand.SetSpeed(desiredSpeed)
        return SyncDecision(commands, StageVisibility.SHOWING, null, error, false)
    }

    private fun bridgeShortGap(location: SyncLocation, episodeMs: Long): SyncLocation {
        if (location !is SyncLocation.Gap) return location
        val next = location.next ?: return location
        if (next.episodeStartMs - episodeMs > tuning.bridgeGapsShorterThanMs) return location
        // Treat as already inside the next segment, extrapolated backwards.
        return SyncLocation.InSegment(next, next.reactionAt(episodeMs).coerceAtLeast(0))
    }

    private fun issueSeek(nowMs: Long, target: Long, commands: MutableList<FollowerCommand>) {
        commands += FollowerCommand.Seek(target.coerceAtLeast(0), exact = true)
        pendingSeekTarget = target
        pendingSeekIssuedAt = nowMs
        pendingSeekSawSeeking = false
    }

    /** True while a seek we issued has not landed yet. Learns the seek latency when it does. */
    private fun settlePendingSeek(nowMs: Long, follower: FollowerSample): Boolean {
        val target = pendingSeekTarget ?: return false
        if (follower.seeking) {
            pendingSeekSawSeeking = true
            return true
        }
        val elapsed = nowMs - pendingSeekIssuedAt
        val landed = follower.positionMs?.let { abs(it - target) < 1_500 } ?: false
        // mpv reports `seeking` a beat after the command; give it that beat before reading "done".
        if (!pendingSeekSawSeeking && elapsed < 250 && !landed) return true
        if (elapsed > 20_000 || landed || pendingSeekSawSeeking) {
            if (landed) seekLatencyMs = seekLatencyMs * 0.7 + elapsed.toDouble().coerceIn(50.0, 5_000.0) * 0.3
            pendingSeekTarget = null
            return false
        }
        return true
    }
}
