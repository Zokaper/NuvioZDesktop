package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun member(
    profileId: String,
    readyState: SourceResolutionState,
    connected: Boolean = true,
) = WatchPartyParticipant(
    profileId = profileId,
    role = if (profileId == "host") "host" else "participant",
    readyState = readyState,
    connected = connected,
    joinedAt = "2026-09-01T00:00:00Z",
)

private fun party(
    hostProfileId: String,
    status: WatchPartyStatus,
    members: List<WatchPartyParticipant>,
) = WatchPartyState(
    id = "party",
    hostProfileId = hostProfileId,
    status = status,
    controlMode = WatchPartyControlMode.host_only,
    contentGeneration = 0,
    content = PartyContent(contentId = "tt1", contentType = "series", videoId = "tt1:1:2", title = "Title"),
    positionMs = 0,
    durationMs = 0,
    playbackSpeed = 1f,
    sequence = 1,
    stateUpdatedAt = "2026-09-01T00:00:00Z",
    members = members,
)

class WatchPartyModelsTest {
    @Test fun expectedPositionUsesServerTimeAndSpeed() {
        assertEquals(3_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.playing, 2f))
        assertEquals(1_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.paused, 2f))
    }

    @Test fun driftPolicyUsesDeadbandSpeedAndSeek() {
        assertEquals(DriftCorrectionKind.NONE, partyDriftCorrection(1_000, 1_700, 1f).kind)
        // Inside the widened nudge band, where a seek used to be the answer.
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(1_000, 3_000, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(1_000, 6_000, 1f).kind)
    }

    /**
     * The band a seek used to be taken in. A seek reloads, the shared clock runs on through the
     * reload, and the guest is handed a larger drift than it started with - so anything a nudge can
     * close has to be nudged.
     */
    @Test fun nudgeBandReachesTheOldSeekThreshold() {
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(0, 2_501, 1f).kind)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(0, 4_000, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(0, 4_001, 1f).kind)
    }

    /** The rate is chosen for the gap, and it closes it: the old fixed 1.03f recovered 300ms in ten seconds. */
    @Test fun nudgeRateIsProportionalAndCapped() {
        // A playback rate is compared with a tolerance: the cap is applied as a float multiply, so
        // 1.5f * 1.1f is 1.6500001f and an exact comparison would be testing IEEE 754, not policy.
        val tolerance = 1e-4f
        assertEquals(1.075f, partyNudgeSpeed(750, 1f), tolerance)
        assertEquals(0.925f, partyNudgeSpeed(-750, 1f), tolerance)
        // Capped at +-10%, so a 2s gap converges over two windows rather than shifting pitch audibly.
        assertEquals(1.1f, partyNudgeSpeed(2_000, 1f), tolerance)
        assertEquals(0.9f, partyNudgeSpeed(-2_000, 1f), tolerance)
        // Proportional to the shared speed, so a party watching at 1.5x is nudged by the same share.
        assertEquals(1.65f, partyNudgeSpeed(2_000, 1.5f), tolerance)
    }

    /**
     * A guest that seeks to where the party is *now* resumes where the party *was*, because the
     * shared clock runs on through the reload. Leading only applies when behind.
     */
    @Test fun seekLeadsTheTargetOnlyWhenBehind() {
        assertEquals(6_000 + WatchPartySeekLeadMs, partyDriftCorrection(0, 6_000, 1f).targetPositionMs)
        assertEquals(0, partyDriftCorrection(6_000, 0, 1f).targetPositionMs)
    }

    @Test fun durationCompatibilityUsesLargerTolerance() {
        assertTrue(arePartyDurationsCompatible(7_200_000, 7_300_000))
        assertFalse(arePartyDurationsCompatible(3_600_000, 3_800_000))
    }

    @Test fun hostWaitsForConnectedMembersWithoutASource() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("guest", SourceResolutionState.resolving),
            ),
        )
        val gate = partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false)
        assertFalse(gate.allowPlayback)
        assertEquals(PartyHoldReason.WAITING_FOR_PARTICIPANTS, gate.reason)
        assertEquals(1, gate.waitingOn)
    }

    @Test fun hostStartsOnceEveryConnectedMemberIsReady() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.joined),
                member("guest", SourceResolutionState.ready),
            ),
        )
        assertTrue(partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false).allowPlayback)
    }

    @Test fun membersWhoCannotBeWaitedForDoNotBlockTheStart() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("gone", SourceResolutionState.joined, connected = false),
                member("failed", SourceResolutionState.failed),
                member("left", SourceResolutionState.left),
            ),
        )
        assertTrue(partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false).allowPlayback)
    }

    @Test fun aReleasedGateDoesNotRestartTheHostAfterAPause() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.paused,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("guest", SourceResolutionState.resolving),
            ),
        )
        assertFalse(partyPlaybackGate(party, "host", hostStartReleased = false).allowPlayback)
        assertTrue(partyPlaybackGate(party, "host", hostStartReleased = true).allowPlayback)
    }

    @Test fun guestsWaitForTheHostAndFollowOnce() {
        val members = listOf(member("host", SourceResolutionState.ready), member("guest", SourceResolutionState.ready))
        val waiting = party(hostProfileId = "host", status = WatchPartyStatus.buffering, members = members)
        assertEquals(PartyHoldReason.WAITING_FOR_HOST, partyPlaybackGate(waiting, "guest", false).reason)
        val playing = waiting.copy(status = WatchPartyStatus.playing)
        assertTrue(partyPlaybackGate(playing, "guest", false).allowPlayback)
    }

    @Test fun aPartyForAnotherVideoIsNotThisPlayback() {
        val party = party(hostProfileId = "host", status = WatchPartyStatus.playing, members = emptyList())
        assertTrue(party.matchesPlayback("tt1", "tt1:1:2"))
        assertFalse(party.matchesPlayback("tt1", "tt1:1:3"))
        assertFalse(party.copy(status = WatchPartyStatus.ended).matchesPlayback("tt1", "tt1:1:2"))
    }

    @Test fun infoHashMatchWins() {
        val host = SourceFingerprint(infoHash="ABC",fileIndex=1,releaseFingerprint="x")
        val same = SourceFingerprint(infoHash="abc",fileIndex=1,releaseFingerprint="different")
        assertEquals(10_000, sourceFingerprintMatchScore(host,same))
    }
}
