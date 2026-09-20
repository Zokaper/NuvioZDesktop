package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Watch Together means by "starved", now that the engine answers it rather than a constant.
 *
 * The case that made this necessary is [aBufferingEngineIsStarvedHoweverMuchItHasAhead]: an engine
 * rebuffering wants far more than a second before it resumes - Android's load control asks for five,
 * and mpv holds `paused-for-cache` until its own cache target is met - so every recovery published at
 * ~1000ms ahead was the old threshold talking over an engine that was still frozen.
 *
 * The mirror of the mobile repository's test of the same name, deliberately: an Android host and a
 * desktop guest that do not mean the same thing by starvation make the stall hold asymmetric, which
 * is the failure this whole signal exists to prevent.
 */
class PartyStarvationTest {
    private fun snapshot(
        readiness: PlayerEngineReadiness,
        positionMs: Long = 60_000L,
        bufferedAheadMs: Long = 0L,
        isPlaying: Boolean = false,
        isLoading: Boolean = false,
        durationMs: Long = 2_400_000L,
        engineName: String = "Desktop-mpv",
    ) = PlayerPlaybackSnapshot(
        isLoading = isLoading,
        isPlaying = isPlaying,
        durationMs = durationMs,
        positionMs = positionMs,
        bufferedPositionMs = positionMs + bufferedAheadMs,
        engineName = engineName,
        engineReadiness = readiness,
    )

    // 1. The finding this change exists for.
    @Test fun aBufferingEngineIsStarvedHoweverMuchItHasAhead() {
        // 1200ms ahead cleared the old 1000ms threshold and published a recovery the engine had not
        // made.
        assertTrue(partyStarvedFor(snapshot(PlayerEngineReadiness.Buffering, bufferedAheadMs = 1_200, isLoading = true)))
        assertTrue(partyStarvedFor(snapshot(PlayerEngineReadiness.Buffering, bufferedAheadMs = 4_999, isLoading = true)))
    }

    // 2. Recovery is the engine's word, not an amount of media.
    @Test fun anEngineThatSaysItIsReadyIsNotStarvedAtAnyBufferLevel() {
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Ready, bufferedAheadMs = 120, isPlaying = true)))
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Ready, bufferedAheadMs = 0)))
    }

    // 3. The feedback loop that cost the 2026-09-19 party its source: the host pauses a starving
    // guest, and the pause must not be what ends the starvation report.
    @Test fun aHostForcedPauseOverAnEmptyEngineIsStillStarved() {
        // Nothing here is playing or loading - the guest is obeying a pause - and it is still empty.
        val held = snapshot(PlayerEngineReadiness.Buffering, bufferedAheadMs = 300, isPlaying = false, isLoading = false)
        assertTrue(partyStarvedFor(held))
    }

    // 4.
    @Test fun anOrdinaryUserPauseIsHealthyRatherThanStarved() {
        // A paused engine that still holds media reports ready, which is the whole reason readiness
        // can be read while paused at all.
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Ready, bufferedAheadMs = 30_000)))
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Ready, bufferedAheadMs = 0)))
    }

    // 5. A correction is a decided instant, not a stall - reporting it is what held the party on
    // every seek before the hold was taken out of the signal.
    @Test fun aBarrierOrCorrectiveSeekIsNeverStarvation() {
        val seeking = snapshot(PlayerEngineReadiness.Buffering, bufferedAheadMs = 0, isLoading = true)
        assertTrue(partyStarvedFor(seeking, holdingForBarrier = false), "the same snapshot without a hold")
        assertFalse(partyStarvedFor(seeking, holdingForBarrier = true))
    }

    @Test fun aClientWithNoUsableSourceYetIsNotStarved() {
        // Startup: opened, buffering, nothing playable, no duration. The start gate waits for this
        // member; the stall guard must not also hold the party open for it.
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Buffering, durationMs = 0, isLoading = true)))
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.NoSource, isLoading = true)))
    }

    // 6.
    @Test fun mpvPausedForCacheIsStarved() {
        assertTrue(partyStarvedFor(snapshot(PlayerEngineReadiness.Buffering, bufferedAheadMs = 1_100)))
    }

    // 7.
    @Test fun mpvWithItsCacheRecoveredIsNotStarved() {
        assertFalse(
            partyStarvedFor(snapshot(PlayerEngineReadiness.Ready, bufferedAheadMs = 400, isPlaying = true)),
            "400ms ahead, and the engine says it is playing",
        )
    }

    /**
     * The fallback, for an engine that cannot answer - iOS today, and any desktop running a bridge
     * from before the readiness export.
     *
     * Unchanged behaviour, deliberately: this is the old rule, kept where it is the only rule
     * available and nowhere else.
     */
    @Test fun anEngineThatCannotAnswerFallsBackToBufferOccupancy() {
        assertTrue(partyStarvedFor(snapshot(PlayerEngineReadiness.Unknown, bufferedAheadMs = 999)))
        assertFalse(partyStarvedFor(snapshot(PlayerEngineReadiness.Unknown, bufferedAheadMs = 1_000)))
        // No buffer position reported at all reads as not starved: a false `true` would hold a
        // healthy party for a member that is fine.
        assertFalse(
            partyStarvedFor(
                PlayerPlaybackSnapshot(durationMs = 2_400_000L, positionMs = 0L, bufferedPositionMs = 0L),
            ),
        )
    }
}
