package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.desktop.PrematureEndOfStreamGuard.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrematureEndOfStreamGuardTest {
    private val source = "https://example.test/episode.mkv"
    private val durationMs = 3_087_776L

    private fun playing(positionMs: Long) = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = true,
        durationMs = durationMs,
        positionMs = positionMs,
    )

    private fun ended(positionMs: Long, duration: Long = durationMs) = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = false,
        isEnded = true,
        durationMs = duration,
        positionMs = positionMs,
    )

    @Test
    fun theReportedFailureRecoversInsteadOfEnding() {
        // 2026-09-15: ended=true at 224 s of a 3,088 s episode, right after a subtitle switch.
        val guard = PrematureEndOfStreamGuard()
        assertEquals(Decision.Pass, guard.evaluate(source, playing(223_890L), nowMs = 0L))

        val decision = guard.evaluate(source, ended(224_558L), nowMs = 500L)

        val recover = assertIs<Decision.Recover>(decision)
        assertEquals(222_558L, recover.targetMs)
        assertTrue(recover.resume)
        assertEquals(1, recover.attempt)
    }

    @Test
    fun maskedSnapshotIsNeverEndedSoNothingMarksItWatched() {
        val masked = PrematureEndOfStreamGuard.masked(ended(224_558L))
        assertFalse(masked.isEnded)
        assertFalse(masked.isPlaying)
        assertTrue(masked.isLoading)
        assertEquals(224_558L, masked.positionMs)
    }

    @Test
    fun aRealEndPassesThrough() {
        val guard = PrematureEndOfStreamGuard()
        assertEquals(Decision.Pass, guard.evaluate(source, ended(durationMs - 400L), nowMs = 0L))
    }

    @Test
    fun anEndPastTheCompletionThresholdPassesForOverReportedDurations() {
        val guard = PrematureEndOfStreamGuard()
        assertEquals(Decision.Pass, guard.evaluate(source, ended((durationMs * 0.91).toLong()), nowMs = 0L))
    }

    @Test
    fun placeholderLengthClipsAreLeftAlone() {
        val guard = PrematureEndOfStreamGuard()
        assertEquals(Decision.Pass, guard.evaluate(source, ended(5_000L, duration = 60_000L), nowMs = 0L))
    }

    @Test
    fun anUnknownDurationIsNotJudged() {
        val guard = PrematureEndOfStreamGuard()
        assertEquals(Decision.Pass, guard.evaluate(source, ended(5_000L, duration = 0L), nowMs = 0L))
    }

    @Test
    fun anInFlightRecoveryIsNotRepeatedEveryPoll() {
        val guard = PrematureEndOfStreamGuard()
        assertIs<Decision.Recover>(guard.evaluate(source, ended(224_558L), nowMs = 0L))
        assertEquals(Decision.Suppress, guard.evaluate(source, ended(224_558L), nowMs = 500L))
        assertEquals(Decision.Suppress, guard.evaluate(source, ended(224_558L), nowMs = 9_000L))
    }

    @Test
    fun aRecoveryThatNeverClearsTheEndTimesOutIntoTheNextAttemptThenFails() {
        val guard = PrematureEndOfStreamGuard()
        assertIs<Decision.Recover>(guard.evaluate(source, ended(224_558L), nowMs = 0L))
        val second = guard.evaluate(source, ended(224_558L), nowMs = 10_000L)
        assertEquals(2, assertIs<Decision.Recover>(second).attempt)

        val fail = guard.evaluate(source, ended(224_558L), nowMs = 20_000L)
        assertEquals(2, assertIs<Decision.Fail>(fail).attempts)

        // Failed once, reported once: the source stays masked until it is replaced.
        assertEquals(Decision.Suppress, guard.evaluate(source, ended(224_558L), nowMs = 30_000L))
    }

    @Test
    fun repeatedDropsWithoutRealProgressExhaustTheBudget() {
        val guard = PrematureEndOfStreamGuard()
        assertIs<Decision.Recover>(guard.evaluate(source, ended(224_558L), nowMs = 0L))
        guard.evaluate(source, playing(223_000L), nowMs = 1_000L)
        assertIs<Decision.Recover>(guard.evaluate(source, ended(225_000L), nowMs = 2_000L))
        guard.evaluate(source, playing(223_500L), nowMs = 3_000L)
        assertIs<Decision.Fail>(guard.evaluate(source, ended(225_200L), nowMs = 4_000L))
    }

    @Test
    fun realProgressAfterARecoveryRefundsTheBudget() {
        val guard = PrematureEndOfStreamGuard()
        assertIs<Decision.Recover>(guard.evaluate(source, ended(224_558L), nowMs = 0L))
        guard.evaluate(source, playing(223_000L), nowMs = 1_000L)
        assertIs<Decision.Recover>(guard.evaluate(source, ended(225_000L), nowMs = 2_000L))
        guard.evaluate(source, playing(300_000L), nowMs = 80_000L)

        val later = guard.evaluate(source, ended(900_000L), nowMs = 700_000L)
        assertEquals(1, assertIs<Decision.Recover>(later).attempt)
    }

    @Test
    fun aUserPausedStreamIsNotResumedByRecovery() {
        val guard = PrematureEndOfStreamGuard()
        guard.evaluate(
            source,
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, durationMs = durationMs, positionMs = 224_000L),
            nowMs = 0L,
        )
        val recover = assertIs<Decision.Recover>(guard.evaluate(source, ended(224_558L), nowMs = 500L))
        assertFalse(recover.resume)
    }

    @Test
    fun aNewSourceStartsWithAFreshBudget() {
        val guard = PrematureEndOfStreamGuard()
        guard.evaluate(source, ended(224_558L), nowMs = 0L)
        guard.evaluate(source, ended(224_558L), nowMs = 10_000L)
        assertIs<Decision.Fail>(guard.evaluate(source, ended(224_558L), nowMs = 20_000L))

        val next = guard.evaluate("https://example.test/other.mkv", ended(224_558L), nowMs = 21_000L)
        assertEquals(1, assertIs<Decision.Recover>(next).attempt)
    }

    @Test
    fun urlsInMpvLogsKeepOnlyTheHost() {
        val line = "error:stream: Failed to open https://store-011.wnam.tb-cdn.io/dl/abc?token=secret."
        assertEquals("error:stream: Failed to open https://store-011.wnam.tb-cdn.io/…", line.redactUrls())
    }
}
