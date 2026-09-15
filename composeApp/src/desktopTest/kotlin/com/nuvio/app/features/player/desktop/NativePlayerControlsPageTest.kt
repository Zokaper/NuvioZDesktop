package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerControlsPageTest {
    @Test
    fun everyElementRequestedByThePlayerScriptExistsInTheControlsPage() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val htmlIds = Regex("""\bid=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toSet()
        val requestedIds = Regex("""getElementById\(["']([^"']+)["']\)""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            expected = emptySet(),
            actual = requestedIds - htmlIds,
            message = "controls.js requests elements that controls.html does not provide",
        )
    }

    @Test
    fun controlsPageIdsAndScriptConstantsAreUnique() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val htmlIds = Regex("""\bid=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        val scriptConstants = Regex("""(?m)^const\s+([A-Za-z_$][\w$]*)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(emptySet(), htmlIds.duplicates(), "controls.html contains duplicate ids")
        assertEquals(emptySet(), scriptConstants.duplicates(), "controls.js redeclares top-level constants")
    }

    @Test
    fun nativeOpeningSurfaceCarriesThePhaseTwoLoadingBandWithoutLegacyMotion() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val css = resourceText("/player-ui/controls.css")

        listOf("openingFacts", "openingProvider", "openingRelease", "openingManualButton").forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing native loading-band element $id")
            assertTrue(script.contains("getElementById(\"$id\")"), "loading-band element $id is not wired")
        }
        assertTrue(script.contains("openingStageLabel"))
        assertTrue(script.contains("openingFacts"))
        assertTrue(script.contains("openingOffersManualEscape"))
        assertTrue(css.contains("opening-progress-sweep"))
        assertFalse(css.contains("animation: opening-logo-pulse"))
        assertFalse(css.contains("animation: opening-artwork-drift"))
    }

    @Test
    fun watchTogetherPanelAndNotificationActionsAreWiredThroughTheNativePage() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")

        listOf(
            "partyRoomClose",
            "wtStartParty",
            "wtRetry",
            "wtOpenExisting",
            "wtLeaveElsewhere",
            "wtAcceptRequest",
            "wtDeclineRequest",
            "wtCancelOutgoing",
            "wtJoinAccepted",
            "wtDismissAccepted",
            "wtDismissError",
            "wtCopyInviteCode",
            "partyLeave",
            "partyEnd",
            "partyEndContinue",
            "partyEndExit",
            "socialNotificationDismiss",
        ).forEach { command ->
            assertTrue(html.contains("data-command=\"$command\""), "missing native command $command")
        }
        // Commands carrying a value are delegated from the panel.
        listOf("wtSetJoinPolicy", "wtEndConfirm").forEach { command ->
            assertTrue(html.contains("data-wt-command=\"$command\""), "missing valued command $command")
        }
        listOf("wtSetGuestControl", "wtSetWaitForEveryone", "wtInviteFriend").forEach { command ->
            assertTrue(script.contains("\"$command\""), "missing dynamic panel command $command")
        }
        // Gone: the header policy pill, the old action buttons, and the centred end-of-party modal.
        listOf("presenceJoinPolicyCycle", "partyToggleControlMode", "partyToggleWait", "partyEndedChoice").forEach { removed ->
            assertFalse(html.contains(removed), "$removed should be gone from the page")
            assertFalse(script.contains(removed), "$removed should be gone from the script")
        }
        assertFalse(html.contains("data-command=\"partyLobby\""), "active playback must not navigate to the lobby")
        listOf("socialNotificationAccept", "socialNotificationDecline", "socialNotificationJoin").forEach { command ->
            assertTrue(script.contains(command), "missing dynamic notification command $command")
        }
    }

    @Test
    fun theHeaderButtonOnlyOpensThePanelAndTheLockNamesTheHost() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        assertTrue(html.contains("id=\"watchTogetherBadge\""))
        assertTrue(script.contains("state.partyTransportLocked"))
        assertTrue(script.contains("can pause or seek"))
        assertFalse(script.contains("The host controls playback"))
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun List<String>.duplicates(): Set<String> =
        groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
