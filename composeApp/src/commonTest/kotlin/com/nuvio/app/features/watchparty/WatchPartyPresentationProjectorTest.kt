package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPartyPresentationProjectorTest {
    private fun member(id: String) = WatchPartyParticipant(
        profileId = id,
        role = if (id == "host") "host" else "member",
        readyState = SourceResolutionState.ready,
        connected = true,
        clientLocation = WatchPartyClientLocation.player,
        joinedAt = "2026-09-09T00:00:00Z",
    )

    private fun party(status: WatchPartyStatus = WatchPartyStatus.playing) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = status,
        controlMode = WatchPartyControlMode.collaborative,
        contentGeneration = 1,
        sourceGeneration = 2,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-09T00:00:00Z",
        authorityEpoch = 3,
        members = listOf(member("host"), member("guest")),
    )

    @Test fun globalPartyStatusIsNeverUsedAsAMemberEngineProxy() {
        val projected = PartyPresentationProjector.project(
            party = party(WatchPartyStatus.playing),
            selfProfileId = "observer",
            health = PartyHealthState(),
            realtime = WatchPartySyncState(),
            partyNowMs = 10_000,
        )
        assertEquals("Ready", projected.members.getValue("host").label)
        assertEquals("Ready", projected.members.getValue("guest").label)
    }

    @Test fun freshPerMemberTelemetryWinsAndExpiresIndependently() {
        val realtime = WatchPartySyncState(
            tickStatus = WatchPartyStatus.buffering,
            tickCapturedAtPartyMs = 9_000,
            peerTelemetry = mapOf("guest" to PartyPeerTelemetry(WatchPartyStatus.paused, 9_500)),
        )
        val fresh = PartyPresentationProjector.project(
            party = party(), selfProfileId = "observer", health = PartyHealthState(),
            realtime = realtime, partyNowMs = 10_000,
        )
        assertEquals("Buffering", fresh.members.getValue("host").label)
        assertEquals("Paused", fresh.members.getValue("guest").label)
        val stale = PartyPresentationProjector.project(
            party = party(), selfProfileId = "observer", health = PartyHealthState(),
            realtime = realtime, partyNowMs = 10_000 + WatchPartyClockStaleMs + 1,
        )
        assertEquals("Ready", stale.members.getValue("host").label)
        assertEquals("Ready", stale.members.getValue("guest").label)
        assertNull(stale.freshHostStatus)
    }

    @Test fun healthPlanesProjectTruthfulCapabilitiesAndBanners() {
        val durableOnly = PartyPresentationProjector.project(
            party = party(), selfProfileId = "guest",
            health = PartyHealthState(
                api = PartyApiHealth.Reachable,
                realtime = PartyRealtimeHealth.SubscribedUnverified,
            ),
            realtime = WatchPartySyncState(), partyNowMs = 0,
        )
        assertEquals(PartySyncCapability.DurableFallback, durableOnly.capability)
        assertEquals(PartyConnectionState.reconnecting, durableOnly.connection)
        assertEquals("Live sync unavailable — following the party every few seconds", durableOnly.connectionBanner)

        val full = PartyPresentationProjector.project(
            party = party(), selfProfileId = "guest",
            health = PartyHealthState(api = PartyApiHealth.Reachable, realtime = PartyRealtimeHealth.Live),
            realtime = WatchPartySyncState(), partyNowMs = 0,
        )
        assertEquals(PartySyncCapability.FullSync, full.capability)
        assertEquals(PartyConnectionState.connected, full.connection)
        assertNull(full.connectionBanner)
    }

    @Test fun actualLocalPlaybackOwnsTheSelfLabel() {
        val projected = PartyPresentationProjector.project(
            party = party(WatchPartyStatus.playing),
            selfProfileId = "guest",
            health = PartyHealthState(),
            realtime = WatchPartySyncState(
                peerTelemetry = mapOf("guest" to PartyPeerTelemetry(WatchPartyStatus.playing, 10_000)),
            ),
            partyNowMs = 10_000,
            localPlaybackStatus = WatchPartyStatus.paused,
        )
        assertEquals("Paused", projected.members.getValue("guest").label)
    }
}
