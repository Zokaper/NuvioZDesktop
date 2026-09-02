package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun tick(
    positionMs: Long,
    capturedAtPartyMs: Long,
    status: WatchPartyStatus = WatchPartyStatus.playing,
    speed: Float = 1f,
    generation: Int = 0,
    sequence: Long = 1,
) = PartyTick(
    partyId = "party",
    contentGeneration = generation,
    sequence = sequence,
    status = status,
    positionMs = positionMs,
    capturedAtPartyMs = capturedAtPartyMs,
    playbackSpeed = speed,
    durationMs = 7_200_000,
)

private fun command(
    kind: PartyCommandKind,
    startPositionMs: Long,
    startAtPartyMs: Long,
    counter: Long = 1,
    commandId: String = "cmd-$counter",
    from: String = "host",
    speed: Float = 1f,
) = PartyCommand(
    commandId = commandId,
    kind = kind,
    issuedByProfileId = from,
    counter = counter,
    contentGeneration = 0,
    startPositionMs = startPositionMs,
    startAtPartyMs = startAtPartyMs,
    playbackSpeed = speed,
)

class WatchPartyClockTest {
    /** The host stamped once, so the midpoint of the round trip is where this machine thinks it did. */
    @Test fun oneExchangeIsTheMidpointDifference() {
        val sample = partyClockSample(sentAtMs = 1_000, hostAtMs = 5_100, receivedAtMs = 1_200)
        assertEquals(200, sample.rttMs)
        assertEquals(4_000, sample.offsetMs)
    }

    /**
     * The offset is only exact on a symmetric path, and the error is bounded by half the round
     * trip - so the shortest exchange in the window is the most trustworthy thing in it, and a long
     * one carrying a wildly different answer is evidence about the network, not about the clock.
     */
    @Test fun theShortestExchangeInTheWindowIsTheOneAccepted() {
        val clock = PartyClock()
            .accept(PartyClockSample(offsetMs = 4_000, rttMs = 40, receivedAtMs = 1_000))
            .accept(PartyClockSample(offsetMs = 4_300, rttMs = 600, receivedAtMs = 2_000))
        assertEquals(4_000, clock.offsetMs)
        assertEquals(40, clock.bestRttMs)
    }

    @Test fun theEstimateLocksOnceEnoughExchangesHaveLanded() {
        var clock = PartyClock()
        repeat(WatchPartyClockLockSamples - 1) { index ->
            clock = clock.accept(PartyClockSample(4_000, 40, (index + 1) * 1_000L))
            assertFalse(clock.locked)
        }
        clock = clock.accept(PartyClockSample(4_000, 40, 9_000))
        assertTrue(clock.locked)
    }

    /**
     * Two machines drift apart by milliseconds a minute, so anything faster is a queued packet.
     * Following it would put a jump into playback that no clock ever made.
     */
    @Test fun aLockedOffsetSlewsRatherThanFollowingOneSample() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(4_300, 10, 2_000))
        assertEquals(4_000 + WatchPartyClockSlewLimitMs, clock.offsetMs)
        assertTrue(clock.locked)
    }

    /**
     * A second-sized move is not drift - it is an NTP correction on one of the machines, or a new
     * host after a transfer. Slewing towards it 50ms at a time would take twenty exchanges.
     */
    @Test fun aStepPastTheRelockThresholdThrowsTheWindowAway() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(9_000, 40, 2_000))
        assertEquals(9_000, clock.offsetMs)
        assertFalse(clock.locked)
        assertEquals(1, clock.samples.size)
    }

    /**
     * On a steady link every round trip measures the same, so ties are the ordinary case rather
     * than the edge one. Keeping the first minimum meant the very first exchange of the party won
     * every comparison for the life of the window and the estimate could not move at all.
     */
    @Test fun aTiedRoundTripGoesToTheMoreRecentExchange() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(4_040, 40, 2_000))
        assertEquals(4_040, clock.offsetMs)
    }

    /**
     * Eight exchanges span forty seconds at the steady cadence, so a lucky short round trip taken
     * before a host transfer would otherwise go on deciding the estimate for all of them.
     */
    @Test fun anExchangeOlderThanTheMaximumAgeStopsCounting() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 10, 1_000)) }
        clock = clock.accept(PartyClockSample(4_030, 400, 1_000 + WatchPartyClockSampleMaxAgeMs + 1))
        assertEquals(1, clock.samples.size)
        assertEquals(400, clock.bestRttMs)
    }

    @Test fun theOpeningExchangesAreFastAndThenSettle() {
        assertEquals(WatchPartyClockFastPingIntervalMs, watchPartyClockPingDelayMs(0))
        assertEquals(WatchPartyClockFastPingIntervalMs, watchPartyClockPingDelayMs(WatchPartyClockFastPingCount - 1))
        assertEquals(WatchPartyClockPingIntervalMs, watchPartyClockPingDelayMs(WatchPartyClockFastPingCount))
    }

    @Test fun aClockWithNoAnswersIsStale() {
        assertTrue(PartyClock().isStale(0))
        val clock = PartyClock().accept(PartyClockSample(0, 20, 1_000))
        assertFalse(clock.isStale(1_000 + WatchPartyClockStaleMs))
        assertTrue(clock.isStale(1_001 + WatchPartyClockStaleMs))
    }
}

class WatchPartyTimelineTest {
    /**
     * The whole point of the tick: the position advances from the instant it was *read*, not from
     * the instant the server happened to commit a row about it.
     */
    @Test fun theExpectedPositionAdvancesFromTheCaptureInstant() {
        assertEquals(3_000, tick(positionMs = 1_000, capturedAtPartyMs = 10_000, speed = 2f).expectedPositionMs(11_000))
        assertEquals(
            1_000,
            tick(positionMs = 1_000, capturedAtPartyMs = 10_000, speed = 2f, status = WatchPartyStatus.paused)
                .expectedPositionMs(11_000),
        )
    }

    /** Adding a Float would promote the position to Float, which quantises after about 4h39m. */
    @Test fun theExpectedPositionKeepsMillisecondPrecisionForLongContent() {
        val position = 16_777_301L
        assertEquals(position + 1_000, tick(positionMs = position, capturedAtPartyMs = 0).expectedPositionMs(1_000))
    }

    /** A socket can deliver two ticks out of order. The older one is simply dropped. */
    @Test fun aTickIsSupersededOnlyByALaterCapture() {
        val held = tick(positionMs = 1_000, capturedAtPartyMs = 10_000)
        assertTrue(tick(positionMs = 1_500, capturedAtPartyMs = 10_500).supersedes(held))
        assertFalse(tick(positionMs = 900, capturedAtPartyMs = 9_500).supersedes(held))
        assertTrue(tick(positionMs = 0, capturedAtPartyMs = 1, generation = 1).supersedes(held))
        assertTrue(tick(positionMs = 0, capturedAtPartyMs = 0).supersedes(null))
    }

    /**
     * The database anchor, kept for the degraded ladder. Still biased by the host's sample age -
     * that is what the tick exists to fix - but it is what a client has when the socket is down.
     */
    @Test fun theDatabaseAnchorStillAdvancesFromTheServerStamp() {
        assertEquals(3_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.playing, 2f))
        assertEquals(1_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.paused, 2f))
        assertEquals(
            100_000_002L,
            expectedPartyPositionMs(
                statePositionMs = 100_000_001L,
                stateUpdatedAtEpochMs = 1_000L,
                serverNowEpochMs = 1_001L,
                status = WatchPartyStatus.playing,
                playbackSpeed = 1f,
            ),
        )
    }

    @Test fun aTickGoesStaleAfterTheHostStopsSending() {
        val held = tick(positionMs = 0, capturedAtPartyMs = 1_000)
        assertFalse(held.isStale(1_000 + WatchPartyTickStaleMs))
        assertTrue(held.isStale(1_001 + WatchPartyTickStaleMs))
    }

    /**
     * The bands the anchor bias used to hide behind. 750ms was never a judgement about what is
     * visible; it was the width of the error, so the policy called the fault "in sync".
     */
    @Test fun theDriftBandsAreDeadbandNudgeAndSeek() {
        assertEquals(DriftCorrectionKind.NONE, partyDriftCorrection(1_000, 1_150, 1f).kind)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(1_000, 1_800, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(1_000, 3_000, 1f).kind)
    }

    /** The rate is chosen for the gap, and clamped where the pitch shift starts to be audible. */
    @Test fun theNudgeRateIsProportionalAndCapped() {
        val tolerance = 1e-4f
        assertEquals(1.05f, partyNudgeSpeed(250, 1f), tolerance)
        assertEquals(0.95f, partyNudgeSpeed(-250, 1f), tolerance)
        assertEquals(1.1f, partyNudgeSpeed(2_000, 1f), tolerance)
        assertEquals(0.9f, partyNudgeSpeed(-2_000, 1f), tolerance)
        // Proportional to the shared speed, so a party watching at 1.5x is nudged by the same share.
        assertEquals(1.65f, partyNudgeSpeed(2_000, 1.5f), tolerance)
    }

    /**
     * No lead any more. A corrective seek is scheduled through [partySeekPlan], so the position it
     * aims at is the one the party will actually be at - nothing has to guess the reload cost.
     */
    @Test fun aCorrectiveSeekAimsAtTheExpectedPositionExactly() {
        assertEquals(6_000, partyDriftCorrection(0, 6_000, 1f).targetPositionMs)
        assertEquals(0, partyDriftCorrection(6_000, 0, 1f).targetPositionMs)
    }

    /**
     * One sample can be wrong - a read taken across a frame drop, a tick that queued behind a
     * retransmit - and a seek taken on it costs the stream its buffer for an error that was not
     * there. Nudged as hard as the policy allows while the gap is being confirmed.
     */
    @Test fun aSeekIsTakenOnlyAfterTheGapHasBeenSeenTwice() {
        val first = DriftTracker().next(0, 3_000, 1f)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, first.correction.kind)
        assertEquals(1, first.tracker.seekStreak)

        val second = first.tracker.next(0, 3_000, 1f)
        assertEquals(DriftCorrectionKind.SEEK, second.correction.kind)
        assertEquals(0, second.tracker.seekStreak)
    }

    @Test fun aGapThatClosesOnItsOwnNeverCostsASeek() {
        val first = DriftTracker().next(0, 3_000, 1f)
        val second = first.tracker.next(0, 100, 1f)
        assertEquals(DriftCorrectionKind.NONE, second.correction.kind)
        assertEquals(0, second.tracker.seekStreak)
    }
}

class WatchPartyBarrierTest {
    @Test fun theLeadIsBoundedAtBothEnds() {
        assertEquals(WatchPartyBarrierMinLeadMs, watchPartyBarrierLeadMs(0))
        assertEquals(340, watchPartyBarrierLeadMs(260))
        assertEquals(WatchPartyBarrierMaxLeadMs, watchPartyBarrierLeadMs(5_000))
    }

    /**
     * The resume that used to jump. Everyone is parked where the pause left them, so there is
     * nothing to seek - each client simply waits for the instant the party agreed on.
     */
    @Test fun aResumeHoldsWhereItIsInsteadOfCatchingUp() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000,
            partyNowMs = 9_700,
        )
        assertNull(plan.seekToMs)
        assertEquals(300, plan.holdMs)
        assertTrue(plan.playAfter)
    }

    /** A hundred milliseconds out is cheaper for the drift nudge to absorb than for a seek to fix. */
    @Test fun aResumeDoesNotSeekForAGapTheNudgeCanAbsorb() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000 + WatchPartyBarrierAlignToleranceMs,
            partyNowMs = 9_700,
        )
        assertNull(plan.seekToMs)
    }

    /**
     * A barrier that has already passed - a slow socket, a client that was reloading - must not be
     * applied at its own stale position. The overshoot goes into the target, so a late client lands
     * where the party is now.
     */
    @Test fun aLateBarrierLandsWhereThePartyIsNow() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000,
            partyNowMs = 10_500,
        )
        assertEquals(5_500, plan.seekToMs)
        assertEquals(0, plan.holdMs)
        assertTrue(plan.playAfter)
    }

    /**
     * Pause carries no lead: the host pauses under its own finger, and a guest that pauses 60ms
     * later and then aligns changes one frame rather than jumping.
     */
    @Test fun pauseIsImmediateAndAlignsWhilePaused() {
        val far = partyBarrierPlan(
            command = command(PartyCommandKind.pause, startPositionMs = 5_000, startAtPartyMs = 0),
            localPositionMs = 5_400,
            partyNowMs = 9_000,
        )
        assertEquals(5_000, far.seekToMs)
        assertEquals(0, far.holdMs)
        assertFalse(far.playAfter)

        val near = partyBarrierPlan(
            command = command(PartyCommandKind.pause, startPositionMs = 5_000, startAtPartyMs = 0),
            localPositionMs = 5_000 + WatchPartyPausedAlignToleranceMs,
            partyNowMs = 9_000,
        )
        assertNull(near.seekToMs)
    }

    /** Seeking to where the party will be at the end of the hold is what makes one seek one seek. */
    @Test fun aCorrectiveSeekTargetsWhereThePartyWillBe() {
        val plan = partySeekPlan(tick(positionMs = 1_000, capturedAtPartyMs = 0), partyNowMs = 10_000)
        assertEquals(10_000 + WatchPartySeekRecoveryLeadMs, plan.resumeAtPartyMs)
        assertEquals(1_000 + 10_000 + WatchPartySeekRecoveryLeadMs, plan.seekToMs)
    }

    @Test fun aCommandIsObeyedOnceAndOlderOnesAreDropped() {
        val first = command(PartyCommandKind.play, 0, 0, counter = 4)
        val log = PartyCommandLog().also { assertTrue(it.accepts(first)) }.record(first)

        assertFalse(log.accepts(first))
        assertFalse(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 3, commandId = "older")))
        assertTrue(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 5, commandId = "newer")))
        // Counters are per sender, so a second controller in a collaborative party is not gated by
        // the first one's numbering.
        assertTrue(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 1, commandId = "other", from = "guest")))
    }

    @Test fun theCommandLogIsBounded() {
        var log = PartyCommandLog()
        repeat(WatchPartyCommandLogSize + 10) { index ->
            log = log.record(command(PartyCommandKind.seek, 0, 0, counter = index.toLong(), commandId = "cmd-$index"))
        }
        assertEquals(WatchPartyCommandLogSize, log.appliedIds.size)
    }

    /** A routine one-second rebuffer must not stop the film for everyone else. */
    @Test fun theHostWaitsOnlyForAGuestStalledPastTheGrace() {
        val watch = GuestBufferingWatch().observe("guest", buffering = true, partyNowMs = 0)
        assertEquals(emptyList(), watch.holdingProfiles(WatchPartyGuestBufferingGraceMs - 1))
        assertEquals(listOf("guest"), watch.holdingProfiles(WatchPartyGuestBufferingGraceMs))
        assertEquals(
            emptyList(),
            watch.observe("guest", buffering = false, partyNowMs = 900)
                .holdingProfiles(WatchPartyGuestBufferingGraceMs),
        )
    }

    /** A member who goes on stalling keeps the instant they started, not the instant last seen. */
    @Test fun aContinuingStallKeepsItsStartingInstant() {
        val watch = GuestBufferingWatch()
            .observe("guest", buffering = true, partyNowMs = 0)
            .observe("guest", buffering = true, partyNowMs = 1_000)
        assertEquals(listOf("guest"), watch.holdingProfiles(WatchPartyGuestBufferingGraceMs))
    }
}

class WatchPartySyncProtocolTest {
    @Test fun everyMessageSurvivesARoundTrip() {
        val messages = listOf(
            PartyTickMessage("host", tick(positionMs = 1_234, capturedAtPartyMs = 99_000, speed = 1.5f)),
            PartyCommandMessage("party", command(PartyCommandKind.seek, 4_000, 5_000, counter = 7)),
            PartyClockPingMessage("party", "guest", exchangeId = "x1", sentAtMs = 10),
            PartyClockPongMessage("party", "host", toProfileId = "guest", exchangeId = "x1", sentAtMs = 10, hostAtMs = 4_010),
            PartyPeerStatusMessage("party", "guest", WatchPartyStatus.buffering, atPartyMs = 500),
        )
        messages.forEach { message ->
            assertEquals(message, decodePartySyncMessage(encodePartySyncMessage(message)))
        }
    }

    /**
     * Desktop and mobile ship separately, so a new client and an old one meet in the field. An
     * unreadable payload is a reason to fall back to the database anchor, never a reason to guess.
     */
    @Test fun anUnreadablePayloadDecodesToNothing() {
        val tickPayload = encodePartySyncMessage(PartyTickMessage("host", tick(0, 0)))
        val newer = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "v") put(key, value) }
            put("v", kotlinx.serialization.json.JsonPrimitive(WatchPartySyncProtocolVersion + 1))
        }
        assertNull(decodePartySyncMessage(newer))

        val unknownType = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "t") put(key, value) }
            put("t", kotlinx.serialization.json.JsonPrimitive("something-else"))
        }
        assertNull(decodePartySyncMessage(unknownType))

        val missingField = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "at") put(key, value) }
        }
        assertNull(decodePartySyncMessage(missingField))
    }
}
