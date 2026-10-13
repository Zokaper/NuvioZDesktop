package com.nuvio.app.features.reaction

import com.nuvio.app.features.reaction.EpisodeReadPlanner.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeReadPlannerTest {
    private val fps = ReactionFingerprinter.FPS
    private fun s(seconds: Int) = seconds * fps

    private fun FingerprintTrack.fill(range: IntRange) {
        for (i in range) set(i, FrameFingerprint(i.toLong(), 0L, 30f))
    }

    /** Reads the way the session does, one plan at a time, and returns the phases in order. */
    private fun drain(planner: EpisodeReadPlanner, track: FingerprintTrack, position: Int, edges: List<Int>): List<Phase> {
        val phases = ArrayList<Phase>()
        while (true) {
            val plan = planner.next(track, position, edges, emptyList()) ?: return phases
            track.fill(plan.range)
            phases += plan.phase
        }
    }

    @Test
    fun aheadOfThePlayheadFirstThenProbesThenEdgesThenTheRest() {
        val total = s(30 * 60)
        val planner = EpisodeReadPlanner(total)
        val track = FingerprintTrack()
        val position = s(600)

        val first = planner.next(track, position, emptyList(), emptyList())!!
        assertEquals(Phase.AHEAD, first.phase)
        assertEquals(position - s(20), first.range.first)
        assertTrue(first.range.count() <= EpisodeReadPlanner.CHUNK_FRAMES)

        val edges = listOf(s(1500))
        val phases = drain(planner, track, position, edges)
        // Each phase is done before the next starts.
        assertEquals(phases.sortedBy { it.ordinal }, phases)
        assertTrue(Phase.PROBE in phases && Phase.EDGE in phases && Phase.FILL in phases, "$phases")
        assertTrue(track.covers(0, total), "everything is read in the end")
        assertNull(planner.next(track, position, edges, emptyList()))
    }

    @Test
    fun probesStartAtThePlayheadAndCoverTheFilmBeforeAnyFill() {
        val total = s(60 * 60)
        val planner = EpisodeReadPlanner(total)
        val track = FingerprintTrack()
        val position = s(1800)
        track.fill(position - s(20) until position + s(180))
        val firstProbe = planner.next(track, position, emptyList(), emptyList())!!
        assertEquals(Phase.PROBE, firstProbe.phase)
        assertTrue(firstProbe.range.first >= position, "the first probe is ahead of the playhead: ${firstProbe.range}")
        while (true) {
            val plan = planner.next(track, position, emptyList(), emptyList())!!
            if (plan.phase != Phase.PROBE) break
            track.fill(plan.range)
        }
        assertEquals(planner.probeCount, planner.probesDone(track))
        // An eighth of the film read: 8 s every 2 min plus what is ahead.
        assertTrue(track.count < total / 6, "read ${track.count} of $total frames")
        assertTrue(planner.checked(track, position, emptyList()))
    }

    @Test
    fun aRangeAnotherReaderHasIsSkipped() {
        val planner = EpisodeReadPlanner(s(600))
        val track = FingerprintTrack()
        val a = planner.next(track, 0, emptyList(), emptyList())!!
        val b = planner.next(track, 0, emptyList(), listOf(a.range))!!
        assertTrue(b.range.first > a.range.last, "$a then $b")
    }

    @Test
    fun anUnreadableRangeIsNotAskedForAgainAndCountsAsRead() {
        val total = s(600)
        val planner = EpisodeReadPlanner(total)
        val track = FingerprintTrack()
        track.fill(0 until s(500))
        // The file really ends at 500 s although the player said 600.
        val plan = planner.next(track, s(450), emptyList(), emptyList())!!
        assertEquals(s(500), plan.range.first)
        planner.markUnreadable(s(500) until total)
        assertNull(planner.next(track, s(450), emptyList(), emptyList()))
        assertTrue(planner.checked(track, s(450), emptyList()))
    }

    @Test
    fun notCheckedUntilAnEdgeIsRead() {
        val total = s(20 * 60)
        val planner = EpisodeReadPlanner(total)
        val partial = FingerprintTrack()
        // Everything but a minute between two probes, where a new edge turns up.
        partial.fill(0 until s(620))
        partial.fill(s(681) until total)
        assertTrue(planner.checked(partial, 0, emptyList()))
        assertFalse(planner.checked(partial, 0, listOf(s(650))))
    }
}
