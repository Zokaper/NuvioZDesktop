package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tray's Windows calls really reach Windows on the runtime the app ships with (JBR 25). They are
 * looked up reflectively so the source also compiles on JDK 17, where both quietly report "unavailable" -
 * which is exactly the failure this test would otherwise hide.
 */
class WindowsForeignTest {
    private val modernWindows = DesktopHostOs.current == DesktopHostOs.WINDOWS && Runtime.version().feature() >= 22

    @Test
    fun theWorkingSetTrimSucceedsOnWindows() {
        if (!modernWindows) return
        assertTrue(WindowsForeign.emptyWorkingSet(), "K32EmptyWorkingSet did not run")
    }

    @Test
    fun theNotificationStateCanBeAsked() {
        if (!modernWindows) return
        val state = assertNotNull(WindowsForeign.userNotificationState(), "SHQueryUserNotificationState did not run")
        assertTrue(state in 1..7, "unexpected QUERY_USER_NOTIFICATION_STATE $state")
    }

    @Test
    fun theForegroundGrantCanBeMade() {
        if (!modernWindows) return
        // A test process has no foreground to pass on, so Windows may refuse; what matters is that the call is made.
        assertNotNull(WindowsForeign.allowSetForegroundWindow(ProcessHandle.current().pid()), "AllowSetForegroundWindow did not run")
    }
}
