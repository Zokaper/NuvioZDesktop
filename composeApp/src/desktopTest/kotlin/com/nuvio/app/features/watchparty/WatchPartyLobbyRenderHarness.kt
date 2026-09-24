package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.social.SocialProfileSummary
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the Watch Together lobby off-screen, in every composition it has, and writes PNGs.
 *
 * ⚠ **Through the production entry points.** The scene is [WatchPartyLobbyFrame] - the backdrop,
 * the inset consumption and the `BoxWithConstraints` the branches read - around
 * [PartyLobbyContent], which is exactly what `WatchPartyLobbyScreen` composes once it has folded
 * the repositories into a [PartyLobbyModel]. Nothing is rebuilt here; only the model is a fixture.
 * The `SocialRenderHarness` docstring explains why that matters: a harness that composes its
 * subject under constraints production never applies is worse than no harness.
 *
 * Two honest gaps, both on purpose:
 *
 * - **No insets.** `WindowInsets.safeDrawing` is zero on desktop, so the status bar, the cutout and
 *   the gesture bar are not drawn. Those are device checks.
 * - **The desktop title rail is a stand-in.** The real one fetches metadata over the network, which
 *   has nothing to answer here. Its width and position are the production ones.
 *
 * Phone scenes are composed at density 2 with **no desktop UI scale and a font scale of 1**, so a
 * 411dp scene is 411 layout dp as a phone lays it out. Desktop scenes use the desktop scale, as the
 * app does.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*WatchPartyLobbyRenderHarness" --rerun
 * # then LOOK at composeApp/build/lobby-render/
 * ```
 *
 * What to ask of the phone PNGs: is the action bar on screen without scrolling; does a 40-character
 * name ellipsize without pushing its status pill off; is the invite code no wider than its pane in
 * landscape; does anything wrap that should not (`End session`, `Start watching`, a status pill)?
 */
class WatchPartyLobbyRenderHarness {

    private val outputDir = File("build/lobby-render")

    private val phoneSizes = listOf(411 to 914, 891 to 411, 360 to 780, 320 to 600, 800 to 1280)
    private val desktopSizes = listOf(1280 to 820, 1920 to 1080)

    private fun member(
        id: String,
        name: String,
        state: SourceResolutionState = SourceResolutionState.joined,
        connected: Boolean = true,
        error: String? = null,
        match: PartySourceMatch? = null,
        color: String = "#1E88E5",
    ) = WatchPartyParticipant(
        profileId = id,
        role = "member",
        readyState = state,
        readyError = error,
        profile = PartyParticipantProfile(displayName = name, handle = name.lowercase().take(12), avatarColorHex = color),
        connected = connected,
        sourceMatch = match,
        joinedAt = "2026-09-21T12:00:00Z",
    )

    private fun party(
        members: List<WatchPartyParticipant>,
        stage: WatchPartyStage = WatchPartyStage.lobby,
        episode: Boolean = false,
        title: String = "Obsession",
    ) = WatchPartyState(
        id = "party",
        hostProfileId = members.first().profileId,
        status = WatchPartyStatus.lobby,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        stage = stage,
        content = PartyContent(
            contentId = "tt1",
            contentType = if (episode) "series" else "movie",
            videoId = "tt1",
            title = title,
            poster = "https://invalid.example/poster.jpg",
            season = if (episode) 2 else null,
            episode = if (episode) 4 else null,
            episodeTitle = if (episode) "The Long Night" else null,
        ),
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "",
        members = members,
    )

    private val friends = listOf(
        SocialProfileSummary(profileId = "f1", handle = "seraph", displayName = "Seraph", avatarColorHex = "#8E24AA", isFriend = true),
        SocialProfileSummary(profileId = "f2", handle = "bigz", displayName = "Big Z", avatarColorHex = "#43A047", isFriend = true),
    )

    private fun model(
        party: WatchPartyState,
        viewer: String,
        inviteCode: String? = "ASQU45HVPB4K",
        addonNotice: String? = null,
        error: String? = null,
        staged: Boolean = false,
        invitable: List<SocialProfileSummary> = friends,
    ): PartyLobbyModel {
        val sync = WatchPartySyncState(clockLocked = true, bestRttMs = 362)
        val presentation = PartyPresentationProjector.project(
            party = party,
            selfProfileId = viewer,
            health = PartyHealthState(realtime = PartyRealtimeHealth.Live),
            realtime = sync,
            partyNowMs = 0L,
        )
        return PartyLobbyModel(
            party = party,
            viewerProfileId = viewer,
            isHost = viewer == party.hostProfileId,
            presentation = presentation,
            sync = sync,
            inviteCode = inviteCode,
            errorMessage = error,
            joinHandoff = null,
            addonNotice = addonNotice,
            hostSourceStaged = staged,
            hasSource = staged,
            sourceLabel = if (staged) "1080p · WEB-DL · Torrentio" else null,
            waitForEveryone = true,
            pauseForAwayUsers = false,
            invitableFriends = invitable,
        )
    }

    private val addonNotice =
        "Addon differences — 1 person has a different set of stream addons. You can continue; an alternate source may be needed."

    private val longName = "A Friend With A Very Long Display Name Ok"

    /** The scenes, by name. Each is one row of the plan's validation matrix. */
    private val scenes: List<Pair<String, PartyLobbyModel>> = run {
        val host = member("host", "main debug", SourceResolutionState.joined, color = "#E53935")
        val you = member("you", "zokaper", SourceResolutionState.joined)
        listOf(
            // Host, two people, nothing picked yet, an addon warning: the ordinary first minute.
            "host" to model(party(listOf(host, you)), viewer = "host", addonNotice = addonNotice),
            // The same party as the guest sees it: no invite code, no host settings, no primary.
            "guest" to model(party(listOf(host, you)), viewer = "you", inviteCode = null, addonNotice = addonNotice),
            // Alone, with friends to invite.
            "solo" to model(party(listOf(host)), viewer = "host"),
            // A source is staged, people are resolving, one failed, a long name, an episode.
            "staged" to model(
                party(
                    listOf(
                        host,
                        member("a", longName, SourceResolutionState.fetching, color = "#FB8C00"),
                        member("b", "Seraph", SourceResolutionState.source_ready, match = PartySourceMatch.alternate, color = "#8E24AA"),
                        member("c", "Big Z", SourceResolutionState.failed, error = "No stream found on this user's addons", color = "#43A047"),
                    ),
                    stage = WatchPartyStage.resolving_sources,
                    episode = true,
                    title = "A Show With An Unreasonably Long Title That Has To Ellipsize",
                ),
                viewer = "host",
                staged = true,
                invitable = emptyList(),
            ),
            // A full room with an error, as the guest.
            "crowd" to model(
                party(
                    listOf(host) + (1..7).map { i ->
                        member(
                            "m$i",
                            if (i == 3) longName else "Guest number $i",
                            if (i % 2 == 0) SourceResolutionState.ready else SourceResolutionState.joined,
                            connected = i != 5,
                            color = listOf("#1E88E5", "#8E24AA", "#43A047", "#FB8C00")[i % 4],
                        )
                    },
                ),
                viewer = "m2",
                inviteCode = "ASQU45HVPB4K",
                error = "Couldn't reach the party. Retrying.",
                invitable = emptyList(),
            ),
        )
    }

    private val actions = PartyLobbyActions(
        onRequestDeparture = {},
        onChoose = {},
        onStart = {},
        onLeave = {},
        onInvite = {},
        onControlMode = {},
        onWaitForEveryone = {},
        onPauseForAwayUsers = {},
    )

    @Test
    fun renderEveryLobbyComposition() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((name, model) in scenes) {
            for ((w, h) in phoneSizes) render("$name-${w}x$h", w, h, phone = true, model, failures)
            if (name == "host" || name == "staged") {
                for ((w, h) in desktopSizes) render("desktop-$name-${w}x$h", w, h, phone = false, model, failures)
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Composable
    private fun Scene(model: PartyLobbyModel) {
        WatchPartyLobbyFrame(poster = model.party.content.poster) {
            PartyLobbyContent(
                model = model,
                actions = actions,
                titleRail = { content, railModifier ->
                    Surface(
                        modifier = railModifier,
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ) {
                        Box(Modifier.padding(18.dp)) { Text("${content.title} (title rail stand-in)") }
                    }
                },
            )
        }
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        phone: Boolean,
        model: PartyLobbyModel,
        failures: MutableList<String>,
    ) {
        val density = if (phone || widthDp < 2000) 2f else 1f
        runCatching {
            val scene = ImageComposeScene(
                width = (widthDp * density).toInt(),
                height = (heightDp * density).toInt(),
                density = Density(density),
            ) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.WHITE,
                    amoled = false,
                    desktopUiScale = if (phone) 1f else desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    if (phone) {
                        // NuvioTheme gives desktop a 1.08 font scale; a phone at default settings has 1.
                        val d = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) { Scene(model) }
                    } else {
                        Scene(model)
                    }
                }
            }
            try {
                scene.render(0L)
                val image = scene.render(16_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }
}
