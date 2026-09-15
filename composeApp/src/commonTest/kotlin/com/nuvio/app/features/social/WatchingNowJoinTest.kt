package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.shouldAdoptDiscoveredParty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hardware Bug 6 (2026-09-15): Ask to join and Join did nothing on either client. */
class WatchingNowJoinTest {
    private fun party(
        id: String = "party",
        host: String = "host",
        status: WatchPartyStatus = WatchPartyStatus.playing,
    ) = WatchPartyState(
        id = id,
        hostProfileId = host,
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        sourceGeneration = 1,
        content = PartyContent(contentId = "tt1", contentType = "movie", videoId = "tt1", title = "Movie"),
        sourceFingerprint = null,
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-15T00:00:00Z",
        authorityEpoch = 1,
        members = emptyList(),
    )

    // The guest's button -------------------------------------------------------------------

    @Test fun aDirectJoinOpensThePartyTheServerMadeThisMemberPartOf() {
        val step = decideWatchingNowJoin(Result.success(SocialActionResult("joined", party = party())))
        assertEquals("party", assertIs<WatchingNowJoinStep.OpenParty>(step).party.id)
        // Already a member (a second press, or a membership from before): the same answer.
        assertIs<WatchingNowJoinStep.OpenParty>(
            decideWatchingNowJoin(Result.success(SocialActionResult("already_joined", party = party()))),
        )
    }

    @Test fun askingToJoinWaitsForTheHostInsteadOfStoppingAtAToast() {
        assertEquals(
            WatchingNowJoinStep.AwaitApproval,
            decideWatchingNowJoin(Result.success(SocialActionResult("approval_required", requestId = "r1"))),
        )
    }

    @Test fun everyRefusalSaysSomething() {
        listOf("disabled", "stale", "full", "unsupported_contract", "something_new").forEach { outcome ->
            val notice = assertIs<WatchingNowJoinStep.Notice>(
                decideWatchingNowJoin(Result.success(SocialActionResult(outcome))),
                "outcome=$outcome",
            )
            assertTrue(notice.message.isNotBlank())
        }
        // ⚠ The failure branch that used to be missing entirely.
        val failed = assertIs<WatchingNowJoinStep.Notice>(
            decideWatchingNowJoin(Result.failure(IllegalStateException("friendship_required (42501)"))),
        )
        assertEquals("You can only join friends' playback", failed.message)
        assertIs<WatchingNowJoinStep.Notice>(decideWatchingNowJoin(Result.failure(RuntimeException())))
    }

    @Test fun aJoinedOutcomeWithoutALivePartyIsNotOpened() {
        assertIs<WatchingNowJoinStep.Notice>(decideWatchingNowJoin(Result.success(SocialActionResult("joined"))))
        assertIs<WatchingNowJoinStep.Notice>(
            decideWatchingNowJoin(
                Result.success(SocialActionResult("joined", party = party(status = WatchPartyStatus.ended))),
            ),
        )
    }

    // The guest's wait -----------------------------------------------------------------------

    @Test fun anAcceptedRequestIsFoundAndOpened() {
        val poll = decideJoinApprovalPoll(elapsedMs = 9_000, heldLivePartyId = null, probe = Result.success(party()))
        assertEquals("party", assertIs<JoinApprovalPoll.Joined>(poll).party.id)
    }

    @Test fun waitingContinuesThroughSilenceAndThroughAFailedProbe() {
        assertEquals(JoinApprovalPoll.Continue, decideJoinApprovalPoll(3_000, null, Result.success(null)))
        // The network blinking is not an answer.
        assertEquals(JoinApprovalPoll.Continue, decideJoinApprovalPoll(3_000, null, Result.failure(RuntimeException())))
        // An ended party is not an acceptance.
        assertEquals(
            JoinApprovalPoll.Continue,
            decideJoinApprovalPoll(3_000, null, Result.success(party(status = WatchPartyStatus.ended))),
        )
    }

    @Test fun theWaitEndsWhenTheRequestCanNoLongerBeAccepted() {
        assertEquals(
            JoinApprovalPoll.GaveUp,
            decideJoinApprovalPoll(WatchingNowApprovalWatchMs, null, Result.success(null)),
        )
        // But an acceptance that lands on the last tick still wins.
        assertIs<JoinApprovalPoll.Joined>(decideJoinApprovalPoll(WatchingNowApprovalWatchMs, null, Result.success(party())))
    }

    @Test fun aMemberAlreadyInAPartyIsNeverDraggedToAnother() {
        // Rejection, cancellation and a stale request must not corrupt party state: a party this
        // member reached some other way is left exactly as it is.
        assertEquals(
            JoinApprovalPoll.Superseded,
            decideJoinApprovalPoll(6_000, heldLivePartyId = "their-own-party", probe = Result.success(party())),
        )
    }

    // The host ---------------------------------------------------------------------------------

    @Test fun joinRequestsAreDeliveredToAnApprovalHostOnItsHeartbeat() {
        assertTrue(shouldPollForJoinRequests(WatchJoinPolicy.approval))
        assertFalse(shouldPollForJoinRequests(WatchJoinPolicy.direct), "direct joins create no request")
        assertFalse(shouldPollForJoinRequests(WatchJoinPolicy.disabled))
    }

    @Test fun aJoinablePlayerWithNoPartyLooksForOneBuiltFromIt() {
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, heldParty = null))
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.approval, heldParty = null))
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, party(status = WatchPartyStatus.ended)))
        assertFalse(shouldDiscoverPromotedParty(WatchJoinPolicy.disabled, heldParty = null))
        assertFalse(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, party()), "already in a live party")
    }

    @Test fun onlyTheHostAdoptsADiscoveredPartyInPlace() {
        assertTrue(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = "me", heldLiveParty = null))
        // ⚠ A guest whose request was just accepted is a member too, but that party is not built
        // from the guest's own playback; the guest reaches it through its lobby instead.
        assertFalse(shouldAdoptDiscoveredParty(party(host = "friend"), selfProfileId = "me", heldLiveParty = null))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = "me", heldLiveParty = party(id = "other")))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me", status = WatchPartyStatus.ended), "me", null))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = null, heldLiveParty = null))
    }
}
