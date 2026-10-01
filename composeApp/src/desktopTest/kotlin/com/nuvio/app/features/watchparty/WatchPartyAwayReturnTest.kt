package com.nuvio.app.features.watchparty

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The actual desktop transport must withdraw readiness across the lifecycle/clock race too. */
class WatchPartyAwayReturnTest {
    private val adapter = WatchPartySync
    private fun field(name: String) = adapter.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(adapter, value)
    private fun invoke(name: String) = adapter.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(adapter)

    @Test fun oldReadySampleIsWithdrawnUntilCatchupCompletesEvenAfterChannelCleanup() = runBlocking {
        delay(100)
        try {
            set("authority", PartyAuthorityContext("party", "guest", "host", WatchPartyControlMode.host_only,
                9, PartyGenerationKey("party", 1, 0, 0)))
            set("selfAway", false)
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            adapter.setLocalPresence(true)
            adapter.setLocalPresence(false)
            assertTrue(adapter.isLocallyReturning())
            assertTrue(field("peerStarved").get(adapter) as Boolean)
            invoke("resetProtocolState")
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            assertTrue(field("peerStarved").get(adapter) as Boolean)
            adapter.completeLocalReturn()
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            assertFalse(adapter.isLocallyReturning())
            assertFalse(field("peerStarved").get(adapter) as Boolean)
        } finally {
            set("authority", null)
            set("selfAway", false)
            adapter.completeLocalReturn()
            invoke("resetProtocolState")
        }
    }
}
