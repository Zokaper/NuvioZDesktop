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

    /**
     * Every `<script>` in controls.html shares one global scope, so a top-level name declared by two of
     * them is a SyntaxError that stops the later file outright. Upstream's party.js arrived with the
     * 0.1.31-alpha sync declaring `partyPanel`, which controls.js already declared: debug 117-119 sat on
     * the opening overlay over a playing video with nothing clickable. The test above only ever looked
     * inside controls.js.
     */
    @Test
    fun scriptsSharingThePageDeclareDistinctTopLevelNames() {
        val html = resourceText("/player-ui/controls.html")
        val scripts = Regex("""<script\s+src=["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(scripts.size >= 2, "expected controls.html to load several scripts, found $scripts")
        val declaredBy = scripts.flatMap { name ->
            Regex("""(?m)^(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)""")
                .findAll(resourceText("/player-ui/$name"))
                .map { it.groupValues[1] to name }
                .toList()
        }
        val clashes = declaredBy.groupBy({ it.first }, { it.second })
            .filterValues { files -> files.distinct().size > 1 }
        assertEquals(emptyMap(), clashes, "top-level names declared by more than one script on the page")
    }

    /**
     * Z stubs the opening spinner (it uses the facts band instead). Upstream's `applyTheme` gained a
     * `setColors` call on it in 0.1.31-alpha and the stub had only `setActive`: the TypeError came in the
     * first render, before `controlsReady`, with the same stuck overlay as the name clash above.
     */
    @Test
    fun theOpeningIndicatorStubHasEveryMethodTheScriptCalls() {
        val script = resourceText("/player-ui/controls.js")
        val stub = Regex("""(?m)^const openingLoadingIndicator = \{(.*)\};$""").find(script)?.groupValues?.get(1)
            ?: return // A real indicator again: nothing stubbed to check.
        val defined = Regex("""(\w+)\(\)""").findAll(stub).map { it.groupValues[1] }.toSet()
        val called = Regex("""openingLoadingIndicator\.(\w+)\(""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), called - defined, "controls.js calls methods its opening-indicator stub lacks")
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
            "wtAskAgainOutgoing",
            "wtDismissOutgoing",
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
        listOf("wtSetGuestControl", "wtSetWaitForEveryone", "wtInviteFriend", "wtCancelInvite").forEach { command ->
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

    @Test
    fun thePartyStatusPillOnlyTakesThePointerOnItsButtons() {
        val html = resourceText("/player-ui/controls.html")
        val script = resourceText("/player-ui/controls.js")
        val css = resourceText("/player-ui/controls.css")
        listOf("partyBanner", "partyBannerAvatars", "partyBannerText", "partyBannerAction", "partyBannerSecondary").forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing status pill element $id")
        }
        // Commands come from Kotlin on the payload, never spelled on the page.
        assertTrue(script.contains("state.partyStatus"))
        assertTrue(script.contains("dataset.statusCommand"))
        assertFalse(script.contains("partyBannerVisible"))
        // Anchored at line start: `.party-banner.compact .party-banner-action {` would match otherwise.
        val pill = css.substringAfter("\n.party-banner {").substringBefore("}")
        assertTrue(pill.contains("pointer-events: none"), "the pill itself must let the pointer through")
        val action = css.substringAfter("\n.party-banner-action {").substringBefore("}")
        assertTrue(action.contains("pointer-events: auto"), "the pill's buttons must take the pointer")
        // Compact, not hidden, once the chrome has gone.
        assertTrue(script.contains("classList.toggle(\"compact\", !state.controlsVisible)"))
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun List<String>.duplicates(): Set<String> =
        groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
