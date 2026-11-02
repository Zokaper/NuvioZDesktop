package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPartyLifecycleTest {

    @Test
    fun `the first tick has nothing to compare against`() {
        assertNull(suspendedGapMs(previousWallMs = null, nowWallMs = 1_000_000L, intervalMs = 2_000L))
    }

    @Test
    fun `an on-time or merely busy tick is not a sleep`() {
        assertNull(suspendedGapMs(1_000_000L, 1_002_000L, 2_000L))
        // A GC pause or a loaded machine: late, but nowhere near a lid closing.
        assertNull(suspendedGapMs(1_000_000L, 1_002_000L + DesktopSuspendGapMs, 2_000L))
    }

    @Test
    fun `a lid closed for a minute is a wake with the time it was gone`() {
        assertEquals(60_000L, suspendedGapMs(1_000_000L, 1_062_000L, 2_000L))
    }

    @Test
    fun `a clock set backwards is not a sleep`() {
        assertNull(suspendedGapMs(1_000_000L, 900_000L, 2_000L))
    }
}
