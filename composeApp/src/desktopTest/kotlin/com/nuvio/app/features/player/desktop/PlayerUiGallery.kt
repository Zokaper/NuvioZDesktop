package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.IncomingJoinRequestRow
import com.nuvio.app.features.player.JoinPolicyControl
import com.nuvio.app.features.player.PartyConnectionChip
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.WatchTogetherBridgeInvite
import com.nuvio.app.features.player.WatchTogetherHostSettings
import com.nuvio.app.features.player.WatchTogetherOutgoingMirror
import com.nuvio.app.features.player.WatchTogetherPanelState
import com.nuvio.app.features.player.WatchTogetherPersonRow
import com.nuvio.app.features.player.WatchTogetherRole
import com.nuvio.app.features.player.watchTogetherBridgeState
import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.watchparty.PartyPromotionFailure
import com.nuvio.app.features.watchparty.PartyReadyTone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fixture gallery for the native Watch Together panel (§10): one page per state, each the real
 * `controls.html` / `controls.css` / `controls.js` fed a payload made by the real Kotlin projector,
 * bridge and JSON writer - so what renders is what the player would receive.
 *
 * Hot Reload cannot show this surface; it is web UI inside the native player. Open the pages in a
 * browser at 400, 800, 1280 and 1920 wide and 500 tall:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*PlayerUiGallery"
 * # composeApp/build/player-ui-gallery/index.html
 * ```
 */
class PlayerUiGallery {
    private val outputDir = File("build/player-ui-gallery")

    private fun person(name: String, host: Boolean = false, self: Boolean = false, status: String, tone: PartyReadyTone) =
        WatchTogetherPersonRow(name.lowercase(), if (self) "You" else name, null, "#1E88E5", host, self, status, tone)

    private val people = listOf(
        person("Rayo", host = true, self = true, status = "Playing", tone = PartyReadyTone.Ready),
        person("Seraph", status = "Buffering", tone = PartyReadyTone.Buffering),
        person("debug", status = "Offline", tone = PartyReadyTone.Offline),
    )
    private val incoming = IncomingJoinRequestRow("r1", "ahmed", "Ahmed", null, "#8E24AA", System.currentTimeMillis() + 90_000)
    private val invites = listOf(
        WatchTogetherBridgeInvite(0, "Big Z", "", invited = false),
        WatchTogetherBridgeInvite(1, "Zokaper", "", invited = true),
    )

    private fun active(role: WatchTogetherRole, connection: PartyConnectionChip = PartyConnectionChip.Live, withIncoming: Boolean = false) =
        WatchTogetherPanelState.Active(
            role = role,
            title = "Mayday",
            subline = if (role == WatchTogetherRole.Host) "You're hosting · 3 people" else "Seraph controls playback",
            connection = connection,
            people = if (role == WatchTogetherRole.Host) people else listOf(
                person("Seraph", host = true, status = "Playing", tone = PartyReadyTone.Ready),
                person("Rayo", self = true, status = "Catching up", tone = PartyReadyTone.Working),
            ),
            incomingRequest = incoming.takeIf { withIncoming },
            settings = if (role == WatchTogetherRole.Host) {
                WatchTogetherHostSettings(false, true, JoinPolicyControl(WatchJoinPolicy.approval))
            } else {
                null
            },
            leaveHelper = if (role == WatchTogetherRole.Host) "Seraph becomes host" else null,
            errorMessage = null,
            syncDetails = "Similar version · [TB⚡] Mayday.2023.1080p.WEB-DL",
        )

    private val fixtures: List<Pair<String, com.nuvio.app.features.player.WatchTogetherBridgeState>> = listOf(
        "idle" to watchTogetherBridgeState(
            WatchTogetherPanelState.Idle("Mayday", JoinPolicyControl(WatchJoinPolicy.approval), null), open = true,
        ),
        "idle-incoming" to watchTogetherBridgeState(
            WatchTogetherPanelState.Idle("The Punisher · S1E2", JoinPolicyControl(WatchJoinPolicy.disabled, errorMessage = "Couldn't change who can join. Try again."), incoming),
            open = true,
        ),
        "unshareable" to watchTogetherBridgeState(WatchTogetherPanelState.Unshareable, open = true),
        "starting" to watchTogetherBridgeState(WatchTogetherPanelState.Starting, open = true),
        "start-failed" to watchTogetherBridgeState(
            WatchTogetherPanelState.StartFailed(PartyPromotionFailure.AlreadyInAnotherParty, "You're already in another party"),
            open = true,
        ),
        "connecting" to watchTogetherBridgeState(WatchTogetherPanelState.Connecting, open = true),
        "active-host" to watchTogetherBridgeState(active(WatchTogetherRole.Host, withIncoming = true), open = true, inviteTargets = invites, inviteCode = "K7Q2XP"),
        "active-host-end-confirm" to watchTogetherBridgeState(
            active(WatchTogetherRole.Host, PartyConnectionChip.Reconnecting).copy(errorMessage = "Couldn't invite Big Z"),
            open = true,
            endConfirm = true,
        ),
        "active-guest-delayed" to watchTogetherBridgeState(active(WatchTogetherRole.Guest, PartyConnectionChip.Delayed), open = true),
        "active-elsewhere" to watchTogetherBridgeState(WatchTogetherPanelState.ActiveElsewhere("Daredevil · S2E4"), open = true),
        "ended" to watchTogetherBridgeState(WatchTogetherPanelState.Ended("Seraph ended the party"), open = true),
        "outgoing-pending" to watchTogetherBridgeState(
            WatchTogetherPanelState.Idle("Mayday", JoinPolicyControl(WatchJoinPolicy.direct), null),
            open = true,
            outgoing = WatchTogetherOutgoingMirror("Seraph", null, "#43A047", "pending", System.currentTimeMillis() + 102_000),
        ),
        "outgoing-accepted" to watchTogetherBridgeState(
            WatchTogetherPanelState.Idle("Mayday", JoinPolicyControl(WatchJoinPolicy.direct), null),
            open = true,
            outgoing = WatchTogetherOutgoingMirror("Seraph", null, "#43A047", "accepted", 0L),
        ),
        "closed-badge-active" to watchTogetherBridgeState(active(WatchTogetherRole.Host), open = false),
    )

    @Test
    fun writeGallery() {
        outputDir.mkdirs()
        listOf("controls.css", "controls.js").forEach { name ->
            File(outputDir, name).writeText(resourceText("/player-ui/$name"))
        }
        val html = resourceText("/player-ui/controls.html")
        fixtures.forEach { (name, watchTogether) ->
            val payload = PlayerControlsState(
                title = "Mayday",
                episodeText = "",
                controlsVisible = true,
                showOpeningOverlay = false,
                showWatchTogether = true,
                showSources = true,
                durationMs = 7_200_000,
                positionMs = 1_800_000,
                watchTogether = watchTogether,
            ).toControlsJson(isFullscreen = false)
            val page = html.replace(
                "<script src=\"controls.js\"></script>",
                "<script src=\"controls.js\"></script>\n  <script>window.playerControls($payload);</script>",
            ).replace(
                "<body>",
                "<body style=\"background:linear-gradient(135deg,#3a4a5e,#1b1f27 60%,#57402b)\">",
            )
            assertTrue(page.contains("window.playerControls("), "fixture script was not injected for $name")
            File(outputDir, "$name.html").writeText(page)
        }
        File(outputDir, "index.html").writeText(
            fixtures.joinToString("\n", "<!doctype html><title>Player UI gallery</title><ul>", "</ul>") { (name, _) ->
                "<li><a href=\"$name.html\">$name</a></li>"
            },
        )
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }
}
