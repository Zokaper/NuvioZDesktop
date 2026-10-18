package com.nuvio.app.features.tray

import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialNavigationTarget
import com.nuvio.app.features.social.SocialPopup
import com.nuvio.app.features.social.SocialPopupKind
import com.nuvio.app.features.social.SocialProfileSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialPopupQueueTest {
    private fun popup(key: String) = SocialPopup(
        key = key,
        kind = SocialPopupKind.FriendWatching,
        person = SocialProfileSummary(profileId = "p-$key", handle = key, displayName = key),
        line = "started watching",
        title = "Andor",
        meta = null,
        art = SocialArtRef(title = "Andor", contentType = null, contentId = null, poster = null),
        target = SocialNavigationTarget.Inbox,
    )

    private fun SocialPopupQueue.keys() = visible.map { it.key }

    @Test fun aBurstStacksThreeAndTheRestWaitInsteadOfPushingTheOldestOff() {
        val queue = SocialPopupQueue(maxVisible = 3, visibleMs = 7_000)
        listOf("a", "b", "c", "d", "e").forEach { queue.offer(popup(it), nowMs = 0) }
        assertEquals(listOf("a", "b", "c"), queue.keys())
        assertEquals(2, queue.waitingCount)
        // The oldest goes on its own time and the next waiting one takes the slot, with a full turn.
        queue.dismiss("a", nowMs = 1_000)
        assertEquals(listOf("b", "c", "d"), queue.keys())
        assertTrue(queue.expire(nowMs = 7_000))
        assertEquals(listOf("d", "e"), queue.keys())
        assertEquals(8_000L, queue.nextDueAtMs())
    }

    @Test fun theSamePopupIsNeverQueuedTwice() {
        val queue = SocialPopupQueue()
        queue.offer(popup("a"), nowMs = 0)
        queue.offer(popup("a"), nowMs = 5)
        assertEquals(listOf("a"), queue.keys())
        assertEquals(0, queue.waitingCount)
    }

    @Test fun hoverHoldsEverythingAndGivesTimeBackOnRelease() {
        val queue = SocialPopupQueue(visibleMs = 7_000, afterHoverMs = 3_000)
        queue.offer(popup("a"), nowMs = 0)
        queue.offer(popup("b"), nowMs = 5_000)
        queue.hold(nowMs = 6_500)
        assertNull(queue.nextDueAtMs())
        assertFalse(queue.expire(nowMs = 60_000), "nothing goes while the pointer is over the stack")
        assertEquals(listOf("a", "b"), queue.keys())
        // a had 0.5s left and gets the 3s floor; b keeps its 5.5s.
        queue.release(nowMs = 60_000)
        assertEquals(63_000L, queue.nextDueAtMs())
        queue.expire(nowMs = 63_000)
        assertEquals(listOf("b"), queue.keys())
        assertEquals(65_500L, queue.nextDueAtMs())
    }

    @Test fun aCardThatArrivesDuringAHoverWaitsForTheRelease() {
        val queue = SocialPopupQueue(visibleMs = 7_000, afterHoverMs = 3_000)
        queue.hold(nowMs = 0)
        queue.offer(popup("a"), nowMs = 100)
        assertEquals(listOf("a"), queue.keys())
        assertNull(queue.nextDueAtMs())
        queue.release(nowMs = 10_000)
        assertEquals(17_000L, queue.nextDueAtMs())
    }
}
