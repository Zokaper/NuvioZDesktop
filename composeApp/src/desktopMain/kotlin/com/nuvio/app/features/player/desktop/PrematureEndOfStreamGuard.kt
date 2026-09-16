package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackSnapshot

/**
 * Tells a real end of file from a stream that stopped delivering bytes.
 *
 * ⚠ **mpv cannot tell these apart, and the Nuvio side used to believe it every time.** A network
 * read that fails mid-file - a dropped CDN connection, or the HTTP re-read mpv makes when an
 * embedded subtitle track is switched on in a large MKV - is reported as `eof-reached`, and with
 * `keep-open=yes` the player then sits on the last frame. The 2026-09-15 report is exactly that:
 * a subtitle picked at 224 s of a 3,088 s episode, six seconds of buffering, then `ended=true`.
 * The episode was marked watched, the saved position rewritten to the end, the player paused, and
 * no error reached the failure chain, so only switching source got out of it.
 *
 * An end far short of the duration is therefore treated as a failure: the snapshot is masked
 * (still loading, not ended) so nothing downstream marks the item complete, a bounded number of
 * seeks back to the last position re-open the stream, and when those run out the caller fails
 * the source through the normal error path.
 *
 * Ends at or beyond [COMPLETION_FRACTION] of the duration pass through untouched - that is the
 * same threshold that counts an item as watched, so a file whose container over-reports its
 * duration still ends the way it always did.
 */
internal class PrematureEndOfStreamGuard(
    private val maxRecoveries: Int = MAX_RECOVERIES,
    private val recoveryTimeoutMs: Long = RECOVERY_TIMEOUT_MS,
    private val progressResetMs: Long = PROGRESS_RESET_MS,
) {
    sealed interface Decision {
        /** Deliver the snapshot unchanged. */
        data object Pass : Decision

        /** Deliver a masked snapshot; a recovery is in flight or the source has already failed. */
        data object Suppress : Decision

        /** Seek to [targetMs] (and resume when [resume]) to re-open the stream. */
        data class Recover(
            val targetMs: Long,
            val resume: Boolean,
            val attempt: Int,
            val positionMs: Long,
            val durationMs: Long,
        ) : Decision

        /** Recoveries are exhausted; fail the source. Returned once per source. */
        data class Fail(val positionMs: Long, val durationMs: Long, val attempts: Int) : Decision
    }

    private var sourceKey: String? = null
    private var recoveries = 0
    private var recoveryAnchorMs = 0L
    private var recoveryStartedAtMs: Long? = null
    private var failed = false
    private var wasActive = true

    fun evaluate(sourceKey: String, snapshot: PlayerPlaybackSnapshot, nowMs: Long): Decision {
        if (sourceKey != this.sourceKey) reset(sourceKey)

        if (!snapshot.isEnded) {
            recoveryStartedAtMs = null
            if (snapshot.durationMs > 0L) {
                wasActive = snapshot.isPlaying || snapshot.isLoading
            }
            if (recoveries > 0 && snapshot.positionMs >= recoveryAnchorMs + progressResetMs) {
                // Real playback resumed well past the failure: a later, unrelated drop gets a
                // fresh budget instead of inheriting this one.
                recoveries = 0
            }
            return Decision.Pass
        }

        if (!isPremature(snapshot)) return Decision.Pass
        if (failed) return Decision.Suppress

        val startedAt = recoveryStartedAtMs
        if (startedAt != null && nowMs - startedAt < recoveryTimeoutMs) {
            // The seek has not been processed yet; mpv still reports the end until it is.
            return Decision.Suppress
        }

        if (recoveries >= maxRecoveries) {
            failed = true
            recoveryStartedAtMs = null
            return Decision.Fail(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                attempts = recoveries,
            )
        }

        recoveries += 1
        recoveryAnchorMs = snapshot.positionMs
        recoveryStartedAtMs = nowMs
        return Decision.Recover(
            targetMs = (snapshot.positionMs - RECOVERY_REWIND_MS).coerceAtLeast(0L),
            resume = wasActive,
            attempt = recoveries,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
        )
    }

    private fun reset(sourceKey: String) {
        this.sourceKey = sourceKey
        recoveries = 0
        recoveryAnchorMs = 0L
        recoveryStartedAtMs = null
        failed = false
        wasActive = true
    }

    companion object {
        const val COMPLETION_FRACTION = 0.90
        const val MAX_RECOVERIES = 2
        const val RECOVERY_TIMEOUT_MS = 10_000L
        const val PROGRESS_RESET_MS = 30_000L
        const val RECOVERY_REWIND_MS = 2_000L

        /** Below this a duration is a placeholder clip, which `WatchingPolicies` already ignores. */
        const val MIN_REAL_DURATION_MS = 121_000L

        fun isPremature(snapshot: PlayerPlaybackSnapshot): Boolean =
            snapshot.isEnded &&
                snapshot.durationMs >= MIN_REAL_DURATION_MS &&
                snapshot.positionMs < snapshot.durationMs * COMPLETION_FRACTION

        /** What everything downstream sees while the guard holds a premature end. */
        fun masked(snapshot: PlayerPlaybackSnapshot): PlayerPlaybackSnapshot =
            snapshot.copy(isEnded = false, isPlaying = false, isLoading = true)
    }
}
