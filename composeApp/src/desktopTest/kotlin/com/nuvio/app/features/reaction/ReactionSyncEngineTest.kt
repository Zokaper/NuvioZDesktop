package com.nuvio.app.features.reaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionSyncEngineTest {
    // Episode 0-60 s is in the reaction from 10 s (a 10 s intro); 60-90 s is cut; 90-200 s resumes at 70 s.
    private val map = SyncMap(
        listOf(
            SyncSegment(episodeStartMs = 0, episodeEndMs = 60_000, reactionStartMs = 10_000),
            SyncSegment(episodeStartMs = 90_000, episodeEndMs = 200_000, reactionStartMs = 70_000),
        ),
    )

    private fun playing(at: Long) = MasterSample(positionMs = at, paused = false, speed = 1.0)
    private fun follower(at: Long?, paused: Boolean = false, speed: Double = 1.0, seeking: Boolean = false) =
        FollowerSample(positionMs = at, paused = paused, speed = speed, seeking = seeking, durationMs = 600_000)

    @Test
    fun locateMapsEpisodeTimeIntoTheReaction() {
        assertEquals(SyncLocation.InSegment(map.segments[0], 40_000), map.locate(30_000))
        assertEquals(SyncLocation.Gap(map.segments[1]), map.locate(75_000))
        assertEquals(SyncLocation.InSegment(map.segments[1], 80_000), map.locate(100_000))
        assertEquals(SyncLocation.Gap(null), map.locate(250_000))
    }

    @Test
    fun overlappingSegmentsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SyncMap(listOf(SyncSegment(0, 10_000, 0), SyncSegment(5_000, 20_000, 30_000)))
        }
    }

    @Test
    fun manualOffsetCoversBothSigns() {
        val ahead = SyncMap.manualOffset(12_000)
        assertEquals(SyncLocation.InSegment(ahead.segments.single(), 17_000), ahead.locate(5_000))
        val behind = SyncMap.manualOffset(-8_000)
        // The reaction starts 8 s into the episode: before that, the episode plays alone.
        assertEquals(SyncLocation.Gap(behind.segments.single()), behind.locate(3_000))
        assertEquals(SyncLocation.InSegment(behind.segments.single(), 2_000), behind.locate(10_000))
        // Bounded by the reaction's own length when known.
        val bounded = SyncMap.manualOffset(5_000, reactionDurationMs = 65_000)
        assertEquals(SyncLocation.Gap(null), bounded.locate(61_000))
    }

    @Test
    fun inSyncDoesNothing() {
        val engine = ReactionSyncEngine()
        val decision = engine.step(0, playing(30_000), follower(40_010), map)
        assertEquals(emptyList(), decision.commands)
        assertEquals(StageVisibility.SHOWING, decision.visibility)
        assertEquals(10L, decision.errorMs)
    }

    @Test
    fun smallDriftIsCorrectedWithSpeedNotSeeks() {
        val engine = ReactionSyncEngine()
        val behind = engine.step(0, playing(30_000), follower(39_700), map)
        val speed = behind.commands.filterIsInstance<FollowerCommand.SetSpeed>().single().speed
        assertTrue(speed > 1.0 && speed <= 1.05, "behind by 300 ms should speed up gently, got $speed")
        assertTrue(behind.commands.none { it is FollowerCommand.Seek })

        val ahead = ReactionSyncEngine().step(0, playing(30_000), follower(40_100), map)
        val slower = ahead.commands.filterIsInstance<FollowerCommand.SetSpeed>().single().speed
        assertTrue(slower < 1.0 && slower >= 0.95, "ahead by 100 ms should slow down, got $slower")
    }

    @Test
    fun farAheadHoldsOnItsFrameUntilTheEpisodeCatchesUp() {
        val engine = ReactionSyncEngine()
        // 400 ms ahead (e.g. a seek that overshot): freeze, don't seek, don't speed-shift.
        val hold = engine.step(0, playing(30_000), follower(40_400), map)
        assertEquals(listOf<FollowerCommand>(FollowerCommand.SetPaused(true)), hold.commands)
        // Still ahead by 200 ms while held: keep holding.
        val still = engine.step(200, playing(30_200), follower(40_400, paused = true), map)
        assertTrue(still.commands.isEmpty())
        // The episode reaches the held frame: release.
        val release = engine.step(400, playing(30_395), follower(40_400, paused = true), map)
        assertTrue(release.commands.contains(FollowerCommand.SetPaused(false)), "${release.commands}")
    }

    @Test
    fun farBehindSeeksRatherThanSpeedingUpForever() {
        val decision = ReactionSyncEngine().step(0, playing(30_000), follower(39_000), map)
        assertTrue(decision.commands.any { it is FollowerCommand.Seek })
    }

    @Test
    fun largeErrorSeeksAheadByTheLearnedLatencyAndHoldsUntilItLands() {
        val engine = ReactionSyncEngine(SyncTuning(initialSeekLatencyMs = 2_000.0))
        val first = engine.step(0, playing(30_000), follower(10_000), map)
        val seek = first.commands.filterIsInstance<FollowerCommand.Seek>().single()
        assertEquals(42_000, seek.positionMs, "aim 2 s ahead of the 40 s target, where the episode will be")
        assertTrue(first.waitingForSeek)

        // Still seeking: hold, no new seek.
        val during = engine.step(500, playing(30_500), follower(10_000, paused = true, seeking = true), map)
        assertTrue(during.commands.none { it is FollowerCommand.Seek })
        assertTrue(during.waitingForSeek)

        // Landed at 42 s while the episode reached 32 s (target 42 s): resume, no further seek.
        val landed = engine.step(2_000, playing(32_000), follower(42_000, paused = true), map)
        assertTrue(landed.commands.contains(FollowerCommand.SetPaused(false)))
        assertTrue(landed.commands.none { it is FollowerCommand.Seek })
        assertTrue(engine.seekLatencyMs in 1_900.0..2_100.0)
    }

    @Test
    fun pausedMasterIsMatchedExactlyAndPausesTheReaction() {
        val engine = ReactionSyncEngine()
        val paused = MasterSample(positionMs = 30_000, paused = true, speed = 1.0)
        val decision = engine.step(0, paused, follower(40_200), map)
        assertEquals(FollowerCommand.Seek(40_000, exact = true), decision.commands.filterIsInstance<FollowerCommand.Seek>().single())
        assertTrue(decision.commands.contains(FollowerCommand.SetPaused(true)))

        val aligned = ReactionSyncEngine().step(0, paused, follower(40_020), map)
        assertEquals(listOf<FollowerCommand>(FollowerCommand.SetPaused(true)), aligned.commands)
    }

    @Test
    fun stalledMasterPausesTheReaction() {
        val decision = ReactionSyncEngine().step(0, playing(30_000).copy(stalled = true), follower(40_000), map)
        assertTrue(decision.commands.contains(FollowerCommand.SetPaused(true)))
    }

    @Test
    fun aGapPausesParksAtTheNextSegmentAndCountsDown() {
        val engine = ReactionSyncEngine()
        val decision = engine.step(0, playing(75_000), follower(59_000), map)
        assertEquals(StageVisibility.GAP, decision.visibility)
        assertEquals(15_000L, decision.resumesInMs)
        assertTrue(decision.commands.contains(FollowerCommand.SetPaused(true)))
        assertEquals(70_000, decision.commands.filterIsInstance<FollowerCommand.Seek>().single().positionMs)

        // Already parked: no more seeks while waiting out the gap.
        val parked = ReactionSyncEngine().step(0, playing(80_000), follower(70_100, paused = true), map)
        assertTrue(parked.commands.none { it is FollowerCommand.Seek })
        assertEquals(10_000L, parked.resumesInMs)
    }

    @Test
    fun theEndOfTheMapIsAGapWithNothingToResume() {
        val decision = ReactionSyncEngine().step(0, playing(250_000), follower(180_000), map)
        assertEquals(StageVisibility.GAP, decision.visibility)
        assertNull(decision.resumesInMs)
        assertTrue(decision.commands.none { it is FollowerCommand.Seek })
    }

    @Test
    fun aTinyGapIsBridgedNotTreatedAsACut() {
        val tight = SyncMap(listOf(SyncSegment(0, 10_000, 0), SyncSegment(11_000, 20_000, 11_000)))
        val decision = ReactionSyncEngine().step(0, playing(10_500), follower(10_500), tight)
        assertEquals(StageVisibility.SHOWING, decision.visibility)
    }

    @Test
    fun aSpedUpUploadRunsTheReactionFaster() {
        val fast = SyncMap(listOf(SyncSegment(0, 100_000, 0, rate = 1.05)))
        val decision = ReactionSyncEngine().step(0, playing(10_000), follower(10_500), fast)
        assertEquals(1.05, decision.commands.filterIsInstance<FollowerCommand.SetSpeed>().single().speed, 1e-9)
    }

    @Test
    fun theReactionNeverPlaysPastItsEnd() {
        val decision = ReactionSyncEngine().step(
            0,
            playing(30_000),
            FollowerSample(positionMs = 35_000, paused = false, speed = 1.0, seeking = false, durationMs = 35_000),
            map,
        )
        assertEquals(StageVisibility.GAP, decision.visibility)
        assertTrue(decision.commands.contains(FollowerCommand.SetPaused(true)))
    }
}
