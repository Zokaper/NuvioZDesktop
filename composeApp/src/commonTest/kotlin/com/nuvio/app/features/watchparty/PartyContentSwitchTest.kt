package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PartyContentSwitchTest {

    private val descriptor = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = "org.example",
        releaseFingerprint = "sha256:" + "0123456789abcdef".repeat(4),
    )

    private fun party(
        videoId: String = "tt2:2:9",
        contentGeneration: Int = 4,
        sourceGeneration: Int = 7,
        status: WatchPartyStatus = WatchPartyStatus.playing,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.collaborative,
        fingerprint: PartySourceDescriptorV2? = descriptor,
    ) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = status,
        controlMode = controlMode,
        contentGeneration = contentGeneration,
        sourceGeneration = sourceGeneration,
        content = PartyContent(
            contentId = "tt2",
            contentType = "series",
            videoId = videoId,
            title = "A Show",
            season = 2,
            episode = 9,
        ),
        sourceFingerprint = fingerprint,
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-11T00:00:00Z",
        authorityEpoch = 3,
        members = emptyList(),
    )

    // decidePartyContentHandoff --------------------------------------------------------------

    @Test fun thePartyMovingToANewEpisodeIsAdoptedWithBothGenerations() {
        val handoff = decidePartyContentHandoff(
            party = party(),
            localContentId = "tt2",
            localVideoId = "tt2:2:8",
            handledContentGeneration = 3,
        )
        val adopt = assertIs<PartyContentHandoff.Adopt>(handoff)
        assertEquals("tt2:2:9", adopt.content.videoId)
        assertEquals(4, adopt.contentGeneration)
        // ⚠ Both counters move on a content change, and the source handoff reads the other one.
        assertEquals(7, adopt.sourceGeneration)
        assertEquals(descriptor, adopt.target)
    }

    @Test fun aPlayerAlreadyOnThePartysEpisodeOwesNothing() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), "tt2", "tt2:2:9", handledContentGeneration = null),
        )
    }

    @Test fun oneAdvanceIsAdoptedOnce() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), "tt2", "tt2:2:8", handledContentGeneration = 4),
        )
    }

    @Test fun aMemberWatchingSomethingElseIsNotDraggedIntoTheParty() {
        // They walked out of the party's title and started another one. Adopting here would be
        // hijacking a playback they deliberately chose.
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), localContentId = "tt99", localVideoId = "tt99", handledContentGeneration = null),
        )
    }

    @Test fun anEndedPartyMovesNobody() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(
                party(status = WatchPartyStatus.ended), "tt2", "tt2:2:8", handledContentGeneration = null,
            ),
        )
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(null, "tt2", "tt2:2:8", handledContentGeneration = null),
        )
    }

    @Test fun aContentChangeWithNoSourceYetIsStillAnAdoption() {
        // `party_change_content_v2` accepts a null descriptor and parks the party in
        // `waiting_for_host_source`. The member has still moved episode and must say so.
        val adopt = assertIs<PartyContentHandoff.Adopt>(
            decidePartyContentHandoff(
                party(fingerprint = null), "tt2", "tt2:2:8", handledContentGeneration = null,
            ),
        )
        assertEquals(null, adopt.target)
    }

    // shouldPublishPartyContentChange --------------------------------------------------------

    @Test fun onlyTheHostMovesThePartysEpisode() {
        assertTrue(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:10", publishedContentGeneration = null),
        )
        // ⚠ Collaborative widens who may change the *source*; it does not widen this. The server
        // says the same thing with `host_required`, so answering otherwise only buys a refusal.
        assertFalse(
            shouldPublishPartyContentChange(party(), "guest", "tt2:2:10", publishedContentGeneration = null),
        )
        assertFalse(
            shouldPublishPartyContentChange(
                party(controlMode = WatchPartyControlMode.host_only), "guest", "tt2:2:10", null,
            ),
        )
    }

    @Test fun thePartyIsNeverMovedToWhereItAlreadyIs() {
        // Republishing the current content would burn a generation for a change nobody made and
        // reset every member to `fetching` for it.
        assertFalse(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:9", publishedContentGeneration = null),
        )
    }

    @Test fun oneAdvanceIsPublishedOnce() {
        assertFalse(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:10", publishedContentGeneration = 4),
        )
        // A later generation re-arms it: the party has moved on since this host last published.
        assertTrue(
            shouldPublishPartyContentChange(
                party(contentGeneration = 5), "host", "tt2:2:10", publishedContentGeneration = 4,
            ),
        )
    }

    @Test fun nothingIsPublishedWithoutATargetOrAParty() {
        assertFalse(shouldPublishPartyContentChange(party(), "host", null, null))
        assertFalse(shouldPublishPartyContentChange(party(), "host", "", null))
        assertFalse(shouldPublishPartyContentChange(null, "host", "tt2:2:10", null))
        assertFalse(
            shouldPublishPartyContentChange(party(status = WatchPartyStatus.ended), "host", "tt2:2:10", null),
        )
    }

    // ownsNextEpisodeChoice ------------------------------------------------------------------

    @Test fun theHostKeepsItsCountdownAndAGuestDoesNot() {
        assertTrue(ownsNextEpisodeChoice(party(), "host", "tt2", "tt2:2:9"))
        assertFalse(ownsNextEpisodeChoice(party(), "guest", "tt2", "tt2:2:9"))
        // Collaborative does not hand the episode choice to guests either.
        assertFalse(
            ownsNextEpisodeChoice(
                party(controlMode = WatchPartyControlMode.collaborative), "guest", "tt2", "tt2:2:9",
            ),
        )
    }

    @Test fun ordinaryPlaybackAlwaysOwnsItsOwnNextEpisode() {
        assertTrue(ownsNextEpisodeChoice(null, "guest", "tt2", "tt2:2:9"))
        assertTrue(ownsNextEpisodeChoice(party(status = WatchPartyStatus.ended), "guest", "tt2", "tt2:2:9"))
        // A member watching something else with a party open in the background is having an
        // ordinary evening, and taking their Next episode button away would be wrong.
        assertTrue(ownsNextEpisodeChoice(party(), "guest", "tt99", "tt99"))
        // Including while they are merely one episode behind the party mid-transition: they are
        // not on the party's content, and the handoff - not the countdown - is what moves them.
        assertTrue(ownsNextEpisodeChoice(party(), "guest", "tt2", "tt2:2:8"))
    }
}
