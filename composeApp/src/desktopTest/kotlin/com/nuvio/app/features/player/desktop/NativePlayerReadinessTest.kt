package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerEngineReadiness
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.partyStarvedFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine-native half of Watch Together's starvation signal on desktop: what mpv's own state means.
 *
 * `PartyStarvationTest` covers what the party does with the verdict; this is the mapping that produces
 * it, kept pure - the native side only packs the properties into flags - so it can be tested without
 * an engine, a window or a DLL.
 */
class NativePlayerReadinessTest {
    private fun mpv(
        pausedForCache: Boolean = false,
        cacheBuffering: Boolean = false,
        paused: Boolean = false,
        seeking: Boolean = false,
        idle: Boolean = false,
        durationMs: Long = 2_400_000L,
    ) = mpvEngineReadiness(pausedForCache, cacheBuffering, paused, seeking, idle, durationMs)

    @Test
    fun pausedForCacheIsStarvedEvenWhileThePartyHasItPaused() {
        assertEquals(PlayerEngineReadiness.Buffering, mpv(pausedForCache = true))
        // The whole reason this is read instead of `isLoading`: the host pausing a starving guest must
        // not be what ends the starvation report.
        assertEquals(PlayerEngineReadiness.Buffering, mpv(pausedForCache = true, paused = true))
    }

    @Test
    fun cacheBufferingCountsOnlyWhilePlaybackIsIntended() {
        assertEquals(PlayerEngineReadiness.Buffering, mpv(cacheBuffering = true))
        // A deliberate pause can leave the last percentage standing; believing it would report every
        // paused member as empty.
        assertEquals(PlayerEngineReadiness.Ready, mpv(cacheBuffering = true, paused = true))
    }

    @Test
    fun aRecoveredCacheIsReady() {
        assertEquals(PlayerEngineReadiness.Ready, mpv())
        assertFalse(
            partyStarvedFor(
                PlayerPlaybackSnapshot(
                    isPlaying = true,
                    durationMs = 2_400_000L,
                    positionMs = 60_000L,
                    bufferedPositionMs = 60_400L,
                    engineName = "Desktop-mpv",
                    engineReadiness = mpv(),
                ),
            ),
            "400ms ahead, and the engine says it is playing",
        )
    }

    @Test
    fun seekingAndStartupAnswerNeither() {
        assertEquals(PlayerEngineReadiness.Unknown, mpv(seeking = true))
        assertEquals(PlayerEngineReadiness.NoSource, mpv(idle = true, durationMs = 0L))
        // Idle with a real duration is an ordinary pause, not a missing source.
        assertEquals(PlayerEngineReadiness.Ready, mpv(idle = true, paused = true))
    }

    @Test
    fun bufferingReachesThePartyAsStarvationRegardlessOfBufferLevel() {
        val stalled = PlayerPlaybackSnapshot(
            isLoading = true,
            durationMs = 2_400_000L,
            positionMs = 60_000L,
            bufferedPositionMs = 61_200L,
            engineName = "Desktop-mpv",
            engineReadiness = mpv(pausedForCache = true),
        )
        assertTrue(partyStarvedFor(stalled), "1200ms ahead cleared the retired threshold")
    }

    /**
     * The packing, which is the one part of this that a native change can silently break.
     *
     * Bit positions are duplicated across three bridges, so a mismatch would show up as a readiness
     * that is wrong rather than absent - `paused` read as `paused-for-cache` would report every paused
     * member starved and hold the party forever.
     */
    @Test
    fun flagsUnpackToTheSamePropertiesTheBridgesPack() {
        assertEquals(
            PlayerEngineReadiness.Buffering,
            mpvEngineReadinessFromFlags(NativeMpvReadinessFlags.PAUSED_FOR_CACHE, durationMs = 2_400_000L),
        )
        assertEquals(
            PlayerEngineReadiness.Buffering,
            mpvEngineReadinessFromFlags(NativeMpvReadinessFlags.CACHE_BUFFERING, durationMs = 2_400_000L),
        )
        assertEquals(
            PlayerEngineReadiness.Ready,
            mpvEngineReadinessFromFlags(
                NativeMpvReadinessFlags.CACHE_BUFFERING or NativeMpvReadinessFlags.PAUSED,
                durationMs = 2_400_000L,
            ),
        )
        assertEquals(
            PlayerEngineReadiness.Unknown,
            mpvEngineReadinessFromFlags(NativeMpvReadinessFlags.SEEKING, durationMs = 2_400_000L),
        )
        // What the bridges return for a dead handle, which is a source that is gone rather than empty.
        assertEquals(
            PlayerEngineReadiness.NoSource,
            mpvEngineReadinessFromFlags(NativeMpvReadinessFlags.CORE_IDLE, durationMs = 0L),
        )
        assertEquals(PlayerEngineReadiness.Ready, mpvEngineReadinessFromFlags(0, durationMs = 2_400_000L))
    }
}
