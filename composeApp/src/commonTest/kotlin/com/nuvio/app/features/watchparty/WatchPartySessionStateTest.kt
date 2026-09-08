package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySessionStateTest {
    private val generation=PartyGenerationKey("party",1,2,3)
    private val playback=ActivePlaybackContext(
        attachmentId="attachment",contentId="tt1",videoId="tt1",
        descriptor=PartySourceDescriptorV2(
            originKind=PartySourceOriginKind.embedded,originId="nuvio",
            releaseFingerprint=partyReleaseFingerprint("Movie 2026 1080p"),
        ),positionMs=10,durationMs=100,playbackSpeed=1f,
    )

    @Test fun restoreReturnsToDurableLobby() {
        val state=reducePartySession(reducePartySession(PartySessionState(),PartySessionEvent.RestoreStarted),PartySessionEvent.Restored(generation))
        assertEquals(PartyClientPhase.Lobby,state.phase)
        assertTrue(state.membershipRetained)
        assertEquals("party",state.pendingLobbyPartyId)
    }

    @Test fun playerExitRetainsMembershipAndDropsOnlyAttachment() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val lobby=reducePartySession(active,PartySessionEvent.PlayerExited("party"))
        assertEquals(PartyClientPhase.Lobby,lobby.phase)
        assertTrue(lobby.membershipRetained)
        assertNull(lobby.playback)
        assertEquals(generation,lobby.generation)
    }

    @Test fun promotionAttachesWithoutReplacingPlayback() {
        val normal=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,null))
        val promoted=reducePartySession(normal,PartySessionEvent.PlayerAttached(playback,generation))
        assertEquals(playback,promoted.playback)
        assertEquals(PartyClientPhase.ActivePlayer,promoted.phase)
    }

    @Test fun generationChangeCancelsReuseAndReturnsToMatching() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val changed=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation.copy(sourceGeneration=3)))
        assertEquals(PartyClientPhase.MatchingHostSource,changed.phase)
        assertFalse(generation.accepts(changed.generation!!))
    }

    @Test fun sameGenerationSnapshotDoesNotRestartMatching() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val unchanged=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation))
        assertEquals(PartyClientPhase.ActivePlayer,unchanged.phase)
        assertEquals(generation,unchanged.generation)
    }

    @Test fun repeatedPlayerLobbyCyclesAreIdempotent() {
        var state=PartySessionState()
        repeat(3) {
            state=reducePartySession(state,PartySessionEvent.PlayerAttached(playback,generation))
            assertEquals(PartyClientPhase.ActivePlayer,state.phase)
            state=reducePartySession(state,PartySessionEvent.PlayerExited("party"))
            assertEquals(PartyClientPhase.Lobby,state.phase)
            assertEquals(generation,state.generation)
            assertTrue(state.membershipRetained)
        }
    }

    @Test fun contentGenerationChangeStartsNewMatchingFlow() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val changed=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation.copy(contentGeneration=2)))
        assertEquals(PartyClientPhase.MatchingHostSource,changed.phase)
    }

    @Test fun hostEndKeepsNormalPlaybackWhileGuestGetsChoice() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val host=reducePartySession(active,PartySessionEvent.Ended(viewerWasHost=true))
        assertEquals(playback,host.playback)
        assertEquals(PartyClientPhase.None,host.phase)
        val guest=reducePartySession(active,PartySessionEvent.Ended(viewerWasHost=false))
        assertEquals(PartyClientPhase.Ended,guest.phase)
        assertTrue(guest.guestPostEndChoice)
    }
}
