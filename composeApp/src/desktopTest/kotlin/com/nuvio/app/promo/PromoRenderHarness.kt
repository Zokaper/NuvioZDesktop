package com.nuvio.app.promo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.DesktopHoverSidebar
import com.nuvio.app.DesktopSidebarCollapsedWidth
import com.nuvio.app.LibrarySubDestination
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.desktop.toControlsJson
import kotlin.math.roundToInt
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.downloads.AttentionGrouping
import com.nuvio.app.features.downloads.DownloadActivity
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPresenter
import com.nuvio.app.features.downloads.DownloadQueueGrouping
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadStorageSummary
import com.nuvio.app.features.downloads.DownloadUserPhase
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.downloadsContentWidth
import com.nuvio.app.features.downloads.downloadsRootContent
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.library.LibraryTopSwitcher
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackQualitySheet
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileState
import com.nuvio.app.features.social.OutgoingJoinRequestState
import com.nuvio.app.features.social.RecentActivityRun
import com.nuvio.app.features.social.SocialCapabilities
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialFeed
import com.nuvio.app.features.social.SocialFeedActions
import com.nuvio.app.features.social.SocialFeedModel
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialUiState
import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.social.WatchingNowItem
import com.nuvio.app.features.social.bucketFriendActivity
import com.nuvio.app.features.social.groupFriendActivity
import com.nuvio.app.features.social.homeSocialSections
import com.nuvio.app.features.social.watchingNowJoinAffordance
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.PartyHealthState
import com.nuvio.app.features.watchparty.PartyLobbyActions
import com.nuvio.app.features.watchparty.PartyLobbyContent
import com.nuvio.app.features.watchparty.PartyLobbyModel
import com.nuvio.app.features.watchparty.PartyParticipantProfile
import com.nuvio.app.features.watchparty.PartyPresentationProjector
import com.nuvio.app.features.watchparty.PartyRealtimeHealth
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyLobbyFrame
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyStage
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.WatchPartySyncState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the real Nuvio Z surfaces for the promo trailer (`Nuvio Z/promo/`).
 *
 * The same rule as every harness beside it and as `SetupHomeStill`: **production entry points under
 * production constraints, with only the data replaced.** Home is composed from the four sections
 * `HomeScreen` itself lays out, in its order, with its metrics; the details page is the real
 * `MetaDetailsScreen` reading a seeded `MetaDetailsRepository`; the lobby is `PartyLobbyContent`
 * with the real title rail; Social is `SocialFeed`; Downloads is `downloadsRootContent` under the
 * phone header and Library/Downloads switcher `DownloadsScreen` draws. The desktop sidebar and the
 * phone nav bar are the real composables.
 *
 * Skips itself unless `Nuvio Z/promo/assets/app-art` exists, so CI never runs it.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*PromoRenderHarness" --rerun
 * # PNGs land in Nuvio Z/promo/renders/
 * ```
 */
class PromoRenderHarness {

    private val outDir = File(PromoArt.promoRoot, "renders")
    /** The render's own clock, because Home's social rows format "2h ago" against the real one. */
    private val nowMs = System.currentTimeMillis()

    private val sintel = PromoCatalog.sintel

    // --- seeding ------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, field: String): MutableStateFlow<T> {
        val f = owner.javaClass.getDeclaredField(field)
        f.isAccessible = true
        return f.get(owner) as MutableStateFlow<T>
    }

    /** The signed-in profile the chrome shows (the nav bar's avatar): Maya, or Theo on his phone. */
    private fun seedProfile(person: SocialProfileSummary = PromoPeople.maya) {
        val profile = NuvioProfile(id = "demo-${person.handle}", profileIndex = 1, name = person.displayName, avatarColorHex = person.avatarColorHex)
        flow<ProfileState>(ProfileRepository, "_state").value =
            ProfileState(profiles = listOf(profile), activeProfile = profile, isLoaded = true, hasEverSelectedProfile = true)
        SocialFeaturePreferencesRepository.setEnabled(true)
    }

    /** Puts [meta] in `MetaDetailsRepository`'s own cache, so `fetch` and `load` both answer it. */
    private fun seedMeta(meta: MetaDetails) {
        val repo = MetaDetailsRepository
        val cacheField = repo.javaClass.getDeclaredField("cachedMetaByRequestKey").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(repo) as MutableMap<String, Any>
        val entryClass = Class.forName("com.nuvio.app.features.details.MetaDetailsRepository\$CachedMetaEntry")
        val ctor = entryClass.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
        val fingerprint = runCatching {
            val settings = com.nuvio.app.features.mdblist.MdbListSettingsRepository.snapshot()
            val m = repo.javaClass.declaredMethods.first { it.name.startsWith("buildMetaScreenSettingsFingerprint") }
            m.isAccessible = true
            m.invoke(repo, settings) as String?
        }.getOrNull()
        cache["${meta.type}:${meta.id}"] = ctor.newInstance(meta, meta, fingerprint)
        flow<MetaDetailsUiState>(repo, "_uiState").value = MetaDetailsUiState(meta = meta)
    }

    // --- rendering ----------------------------------------------------------------------------

    private enum class Kind { Desktop, Phone }

    /**
     * One scene. [frames] is how many PNGs to write; [beforeFrame] runs before each one and can
     * move state (a scroll, a progress figure). Time advances [frameNanos] per frame, so the app's
     * own animations run at their real speed.
     */
    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        kind: Kind,
        density: Float,
        failures: MutableList<String>,
        frames: Int = 1,
        settleMs: Long = 900,
        frameNanos: Long = 16_666_667L,
        beforeFrame: (Int) -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        runCatching {
            val clock = VirtualClockDispatcher()
            val scene = ImageComposeScene(
                width = (widthDp * density).toInt(),
                height = (heightDp * density).toInt(),
                density = Density(density),
                coroutineContext = clock,
            ) {
                NuvioTheme(
                    darkTheme = true,
                    appTheme = AppTheme.WHITE,
                    amoled = false,
                    desktopUiScale = if (kind == Kind.Phone) 1f else desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    WithPromoArt {
                        if (kind == Kind.Phone) {
                            val d = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(d.density, 1f),
                                LocalNuvioBottomNavigationOverlayPadding provides 79.dp,
                            ) { content() }
                        } else {
                            content()
                        }
                    }
                }
            }
            try {
                // Let entrance animations and first-frame effects settle before anything is kept.
                var t = 0L
                val settleFrames = (settleMs * 1_000_000L / frameNanos).toInt()
                fun frame(): org.jetbrains.skia.Image {
                    clock.advanceTo(t / 1_000_000L)
                    val image = scene.render(t)
                    clock.pump()
                    t += frameNanos
                    return image
                }
                if (frames == 1) {
                    repeat(settleFrames) { frame() }
                }
                val dir = if (frames == 1) outDir else File(outDir, name).apply { mkdirs() }
                for (i in 0 until frames) {
                    beforeFrame(i)
                    val image = frame()
                    val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                    val file = if (frames == 1) File(dir, "$name.png") else File(dir, "f_%04d.png".format(i))
                    file.writeBytes(data.bytes)
                }
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            error.printStackTrace()
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }
    }

    @Test
    fun renderPromoSurfaces() {
        if (!PromoArt.available) {
            println("PromoRenderHarness: ${PromoArt.promoRoot}/assets/app-art is missing; skipping.")
            return
        }
        outDir.mkdirs()
        seedProfile()
        seedMeta(sintel.details(moreLikeThis = PromoCatalog.openMovies.drop(1).map { it.preview() }))
        val only = System.getenv("PROMO_ONLY")?.split(',')?.map(String::trim)?.toSet()
        fun wants(name: String) = only == null || name in only

        val failures = mutableListOf<String>()

        if (wants("desktop-home")) {
            render("desktop-home", 1920, 1080, Kind.Desktop, 1.5f, failures) { DesktopShell(AppScreenTab.Home) { HomeComposition() } }
        }
        if (wants("desktop-home-scroll")) {
            // A real scroll through Home, in the app's own list, parallax and all.
            val state = LazyListState()
            val total = 150
            var done = 0f
            render(
                "desktop-home-scroll", 1920, 1080, Kind.Desktop, 1f, failures, frames = total,
                beforeFrame = { i ->
                    val p = i / (total - 1f)
                    val eased = if (p < 0.5f) 4 * p * p * p else 1 - Math.pow((-2.0 * p + 2), 3.0).toFloat() / 2
                    val target = eased * 1000f
                    state.dispatchRawDelta(target - done)
                    done = target
                },
            ) { DesktopShell(AppScreenTab.Home) { HomeComposition(state, cwBefore) } }
        }
        if (wants("phone-home")) {
            render("phone-home", 411, 914, Kind.Phone, 3f, failures) { PhoneShell(AppScreenTab.Home, statusInset = true) { HomeComposition() } }
        }
        if (wants("phone-home-scrolled")) {
            render("phone-home-scrolled", 411, 914, Kind.Phone, 3f, failures) {
                PhoneShell(AppScreenTab.Home, statusInset = true) { HomeComposition(remember { LazyListState(0, (430 * 3)) }) }
            }
        }
        if (wants("desktop-home-cw")) {
            render("desktop-home-cw", 1920, 1080, Kind.Desktop, 1.5f, failures) {
                DesktopShell(AppScreenTab.Home) { HomeComposition(remember { LazyListState(0, (760 * 1.5f).toInt()) }) }
            }
        }
        if (wants("desktop-details")) {
            render("desktop-details", 1920, 1080, Kind.Desktop, 1.5f, failures) { Details() }
        }
        if (wants("desktop-quality")) {
            // The Streamlined sheet opening over the details page: the app's own entrance motion.
            render("desktop-quality", 1920, 1080, Kind.Desktop, 1.5f, failures, frames = 36) {
                Box(Modifier.fillMaxSize()) {
                    Details()
                    QualitySheet()
                }
            }
        }
        if (wants("desktop-lobby")) {
            render("desktop-lobby", 1920, 1080, Kind.Desktop, 1.5f, failures) { Lobby(viewer = "p-maya") }
        }
        if (wants("phone-lobby")) {
            render("phone-lobby", 411, 914, Kind.Phone, 3f, failures) { PhoneStatusInset { Lobby(viewer = "p-theo") } }
        }
        if (wants("desktop-social")) {
            render("desktop-social", 1920, 1080, Kind.Desktop, 1.5f, failures) { DesktopShell(AppScreenTab.Social) { Social() } }
        }
        if (wants("phone-social")) {
            render("phone-social", 411, 914, Kind.Phone, 3f, failures) { PhoneShell(AppScreenTab.Social, statusInset = true) { Social() } }
        }
        seedProfile(PromoPeople.theo)
        if (wants("theo-social")) {
            render("theo-social", 411, 914, Kind.Phone, 3f, failures) { TheoPhone(OutgoingJoinRequestState.Idle) }
        }
        if (wants("theo-requested")) {
            // The tap: Ask to join becomes Requested and the dock comes in, over the app's own motion.
            val outgoing = androidx.compose.runtime.mutableStateOf<OutgoingJoinRequestState>(OutgoingJoinRequestState.Idle)
            render(
                "theo-requested", 411, 914, Kind.Phone, 3f, failures, frames = 45,
                beforeFrame = { i -> if (i == 1) outgoing.value = theoPending() },
            ) { TheoPhone(outgoing.value) }
        }
        if (wants("theo-accepted")) {
            val outgoing = androidx.compose.runtime.mutableStateOf<OutgoingJoinRequestState>(theoPending())
            render(
                "theo-accepted", 411, 914, Kind.Phone, 3f, failures, frames = 45,
                beforeFrame = { i ->
                    if (i == 1) {
                        outgoing.value = OutgoingJoinRequestState.Accepted(
                            theoBinding, mayaTarget, sintelRequestContent,
                            party = party.copy(status = WatchPartyStatus.playing, stage = WatchPartyStage.playing),
                            countdownDeadlineMs = com.nuvio.app.features.watchparty.currentEpochMs() + 3_000L,
                        )
                    }
                },
            ) { TheoPhone(outgoing.value) }
        }
        seedProfile()
        if (wants("phone-downloads")) {
            render("phone-downloads", 411, 914, Kind.Phone, 3f, failures) { PhoneShell(AppScreenTab.Library, statusInset = true) { PhoneDownloads(0f) } }
        }
        if (wants("phone-downloads-run")) {
            val total = 90
            val progress = androidx.compose.runtime.mutableFloatStateOf(0f)
            render(
                "phone-downloads-run", 411, 914, Kind.Phone, 2f, failures, frames = total,
                beforeFrame = { i -> progress.floatValue = i / (total - 1f) },
            ) { PhoneShell(AppScreenTab.Library, statusInset = true) { PhoneDownloads(progress.floatValue) } }
        }

        if (wants("player-state")) exportPlayerStates(failures)

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- the desktop player page ---------------------------------------------------------------

    /** `PlayerScreenRuntimeUi`'s private `toCssColorString`, restated. */
    private fun androidx.compose.ui.graphics.Color.css(): String {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        val a = ((alpha.coerceIn(0f, 1f) * 1000f).roundToInt() / 1000f).toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
        return "rgba($r, $g, $b, $a)"
    }

    /**
     * The desktop player's controls are an HTML page (`player-ui/`) fed by
     * `PlayerControlsState.toControlsJson`. This writes the JSON that page receives - through that
     * serializer, with the theme tokens `PlayerScreenRuntimeUi` reads and, for the party shot, the
     * Watch Together state from the production projection - so the trailer loads the real page
     * over a real frame of the film.
     */
    private fun exportPlayerStates(failures: MutableList<String>) {
        runCatching {
            var colors: com.nuvio.app.core.ui.NuvioColorTokens? = null
            val scene = ImageComposeScene(8, 8, Density(1f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false) {
                    colors = MaterialTheme.nuvio.colors
                }
            }
            scene.render(0L)
            scene.close()
            val c = colors ?: error("theme colours were not read")
            val base = com.nuvio.app.features.player.PlayerControlsState(
                title = sintel.name,
                streamTitle = "Sintel.2010.2160p.WEB-DL.HEVC.AAC.5.1",
                providerName = "Open Movie Archive",
                pauseOverlayEpisodeInfo = "Open Movie Archive",
                pauseOverlayDescription = sintel.description,
                themeAccentColor = c.accent.css(),
                themeAccentStrongColor = c.accentStrong.css(),
                themeOnAccentColor = c.onAccent.css(),
                themeFocusColor = c.focusRing.css(),
                themeSelectedSurfaceColor = c.accent.copy(alpha = 0.24f).css(),
                themeSelectedSurfaceHoverColor = c.accent.copy(alpha = 0.34f).css(),
                themeSelectedRingColor = c.accent.copy(alpha = 0.35f).css(),
                themeTimelineFillColor = c.playerTimelineFill.css(),
                themeTimelineTrackColor = c.playerTimelineTrack.css(),
                themeBufferingColor = c.playerBuffering.css(),
                themeBufferingTrackColor = c.playerBuffering.copy(alpha = 0.28f).css(),
                themeControlForegroundColor = c.playerControlsForeground.css(),
                themeSurfaceElevatedColor = c.surfaceElevated.css(),
                themeSurfaceCardColor = c.surfaceCard.css(),
                themeSurfacePopoverColor = c.surfacePopover.css(),
                themeTextPrimaryColor = c.textPrimary.css(),
                themeTextSecondaryColor = c.textSecondary.css(),
                themeTextMutedColor = c.textMuted.css(),
                themeBorderDefaultColor = c.borderDefault.css(),
                isPlaying = true,
                controlsVisible = true,
                showSources = true,
                showWatchTogether = true,
                durationMs = sintel.durationMs,
                positionMs = 7 * 60_000L + 48_000L,
            )
            // The storyline: Maya is watching alone; Theo asks to join from his phone; the request
            // reaches her player; she lets him in. Every panel and pill below is the production
            // projection of those inputs, exactly as PlayerScreenRuntimeUi computes them.
            val health = PartyHealthState(realtime = PartyRealtimeHealth.Live)
            val theo = PromoPeople.theo
            val request = com.nuvio.app.features.player.IncomingJoinRequestRow(
                requestId = "r-theo",
                profileId = theo.profileId,
                name = theo.displayName,
                avatarUrl = null,
                avatarColorHex = theo.avatarColorHex,
                expiresAtMs = nowMs + 95_000L,
            )
            val together = party.copy(
                status = WatchPartyStatus.playing,
                stage = WatchPartyStage.playing,
                members = listOf(
                    member(PromoPeople.maya, SourceResolutionState.ready),
                    member(theo, SourceResolutionState.ready),
                ),
            )
            fun panel(p: WatchPartyState?, incoming: com.nuvio.app.features.player.IncomingJoinRequestRow?) =
                com.nuvio.app.features.player.projectWatchTogetherPanel(
                    com.nuvio.app.features.player.WatchTogetherPanelInputs(
                        shareable = true,
                        playbackContentId = sintel.id,
                        playbackVideoId = sintel.id,
                        playbackTitle = sintel.name,
                        viewerProfileId = PromoPeople.maya.profileId,
                        party = p,
                        health = health,
                        nowMs = nowMs,
                        incomingRequest = incoming,
                        members = PartyPresentationProjector.project(
                            party = p,
                            selfProfileId = PromoPeople.maya.profileId,
                            health = health,
                            realtime = WatchPartySyncState(clockLocked = true, bestRttMs = 38),
                            partyNowMs = 0L,
                        ).members,
                    ),
                )
            fun pill(incoming: Boolean, panelOpen: Boolean) = com.nuvio.app.features.player.partyStatusBridgeState(
                com.nuvio.app.features.watchparty.projectPartyPlaybackStatus(
                    com.nuvio.app.features.watchparty.PartyPlaybackStatusInputs(
                        inParty = false,
                        isHost = false,
                        incomingRequester = if (incoming) {
                            com.nuvio.app.features.watchparty.PartyStatusPerson(theo.profileId, theo.displayName, null, theo.avatarColorHex)
                        } else {
                            null
                        },
                        panelOpen = panelOpen,
                    ),
                ),
            )
            fun bridge(p: com.nuvio.app.features.player.WatchTogetherPanelState, open: Boolean) =
                com.nuvio.app.features.player.watchTogetherBridgeState(p, open = open, inviteCode = "MX7Q4KRT2WLA")
            val states = mapOf(
                "watching" to base.copy(watchTogether = bridge(panel(null, null), open = false)),
                "request" to base.copy(watchTogether = bridge(panel(null, request), open = false), partyStatus = pill(true, false)),
                "request-open" to base.copy(watchTogether = bridge(panel(null, request), open = true), partyStatus = pill(true, true)),
                "together" to base.copy(watchTogether = bridge(panel(together, null), open = true)),
                "together-closed" to base.copy(watchTogether = bridge(panel(together, null), open = false)),
            )
            val dir = File(outDir, "player").apply { mkdirs() }
            dir.listFiles { f -> f.name.endsWith(".json") }?.forEach(File::delete)
            states.forEach { (name, state) -> File(dir, "$name.json").writeText(state.toControlsJson(isFullscreen = true)) }
        }.onFailure { e ->
            e.printStackTrace()
            failures += "player-state: ${e::class.simpleName}: ${e.message}"
        }
    }

    // --- shells -------------------------------------------------------------------------------

    /** `MainTabsDestination` on a desktop window: the content inset by the collapsed rail, the rail over it. */
    @Composable
    private fun DesktopShell(tab: AppScreenTab, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize().padding(start = DesktopSidebarCollapsedWidth)) { content() }
            DesktopHoverSidebar(
                selectedTab = tab,
                onTabSelected = {},
                onProfileSelected = {},
                onAddProfileRequested = {},
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }

    /**
     * `MainTabsDestination` on a phone: the floating nav bar over the tab, with the app's tabs.
     * [statusInset] stands in for the status-bar inset the desktop test host reports as zero, for
     * screens that pad a header below it (a hero draws under it, so Home does not).
     */
    @Composable
    private fun PhoneShell(tab: AppScreenTab, statusInset: Boolean = false, content: @Composable () -> Unit) {
        val haze = rememberHazeState()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(
                Modifier.fillMaxSize().hazeSource(state = haze).background(MaterialTheme.colorScheme.background)
                    .padding(top = if (statusInset) PhoneStatusBar else 0.dp),
            ) { content() }
            NuvioNavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PhoneGestureBar),
                hazeState = haze,
            ) {
                NavItem(selected = tab == AppScreenTab.Home, onClick = {}, icon = Icons.Filled.Home, contentDescription = null, label = "Home")
                NavItem(selected = tab == AppScreenTab.Search, onClick = {}, icon = Res.drawable.sidebar_search, contentDescription = null, label = "Search")
                NavItem(selected = tab == AppScreenTab.Library, onClick = {}, icon = Res.drawable.sidebar_library, contentDescription = null, label = "Library")
                NavItem(selected = tab == AppScreenTab.Social, onClick = {}, icon = Icons.Filled.People, contentDescription = null, label = "Social")
                NavItem(selected = tab == AppScreenTab.Settings, onClick = {}, label = "Settings") {
                    com.nuvio.app.features.profiles.ProfileSwitcherTab(
                        selected = tab == AppScreenTab.Settings,
                        onClick = {},
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                    )
                }
            }
        }
    }

    @Composable
    private fun PhoneStatusInset(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = PhoneStatusBar)) { content() }
    }

    // --- Home ---------------------------------------------------------------------------------

    /** Before the story: Sintel is not in Continue Watching yet. */
    private val cwBefore: List<ContinueWatchingItem> = listOf(
        cw(PromoCatalog.metropolis, 0.31f),
        cw(PromoCatalog.tearsOfSteel, 0.72f),
        cw(PromoCatalog.charade, 0.18f),
        cw(PromoCatalog.elephantsDream, 0.55f),
        cw(PromoCatalog.theGeneral, 0.4f),
    )

    /** After: where the party left off (the player shows 4:50; a little later, 5:10). */
    private val cwAfter: List<ContinueWatchingItem> = listOf(cw(sintel, 310f / 888f)) + cwBefore.take(4)

    private fun cw(t: PromoTitle, fraction: Float) = ContinueWatchingItem(
        parentMetaId = t.id,
        parentMetaType = "movie",
        videoId = t.id,
        title = t.name,
        subtitle = t.year,
        imageUrl = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) } ?: PromoArt.poster(t.slug),
        poster = PromoArt.poster(t.slug),
        background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) },
        resumePositionMs = (t.durationMs * fraction).toLong(),
        durationMs = t.durationMs,
        progressFraction = fraction,
    )

    private val heroItems = listOf(sintel, PromoCatalog.spring, PromoCatalog.charge).map { it.preview() }

    /** `HomeScreen`'s list, section for section: hero, Continue Watching, Social, catalog rows. */
    @Composable
    private fun HomeComposition(listState: LazyListState = rememberLazyListState(), continueWatching: List<ContinueWatchingItem> = cwAfter) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            val sectionPadding = homeSectionHorizontalPaddingForWidth(width.value)
            val layout = rememberContinueWatchingLayout(width.value)
            NuvioScreen(horizontalPadding = 0.dp, topPadding = 0.dp, listState = listState) {
                item(key = "home_hero") {
                    HomeHeroSection(
                        items = heroItems,
                        viewportHeight = maxHeight,
                        sectionPadding = if (width >= 768.dp) sectionPadding else null,
                        listState = listState,
                    )
                }
                item(key = "cw") {
                    HomeContinueWatchingSection(
                        items = continueWatching,
                        style = ContinueWatchingSectionStyle.Card,
                        dataSourceKey = WatchProgressSource.NUVIO_SYNC,
                        sectionPadding = sectionPadding,
                        layout = layout,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                homeSocialSections(
                    watchingNow = watchingNow,
                    activity = activity,
                    sectionPadding = sectionPadding,
                    availableWidth = width,
                    watchPartyEnabled = true,
                    onStartParty = {},
                    onOpenContent = { _, _, _ -> },
                )
                item(key = "open") {
                    HomeCatalogRowSection(
                        section = PromoCatalog.section("open", "Open Movies", PromoCatalog.openMovies),
                        sectionPadding = sectionPadding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                item(key = "classics") {
                    HomeCatalogRowSection(
                        section = PromoCatalog.section("classics", "Public Domain Classics", PromoCatalog.classics),
                        sectionPadding = sectionPadding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }

    // --- details and sources ------------------------------------------------------------------

    @Composable
    private fun Details() {
        MetaDetailsScreen(
            type = "movie",
            id = sintel.id,
            onBack = {},
            onWatchTogether = {},
            onPlay = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            onPlayManually = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
        )
    }

    private fun stream(name: String, filename: String, sizeBytes: Long) = StreamItem(
        name = name,
        description = null,
        url = "https://example.invalid/$filename",
        addonName = "Open Movie Archive",
        addonId = "open-movie-archive",
        behaviorHints = StreamBehaviorHints(filename = filename, videoSize = sizeBytes),
    )

    @Composable
    private fun QualitySheet() {
        val context = PlaybackSelectionContext(runtimeMinutes = sintel.runtimeMin, isEpisode = false, preferredEmbeddedSubtitleLanguage = "en")
        val options = remember {
            PlaybackQualityOptions.build(
                listOf(
                    stream("4K HDR", "Sintel.2010.2160p.HDR.HEVC.DTS-HD.MA.5.1.mkv", 6_400_000_000L),
                    stream("4K", "Sintel.2010.2160p.WEB-DL.HEVC.AAC.5.1.mkv", 3_100_000_000L),
                    stream("1080p", "Sintel.2010.1080p.BluRay.x264.DTS.5.1.mkv", 1_900_000_000L),
                    stream("1080p", "Sintel.2010.1080p.WEB-DL.HEVC.AAC.mkv", 820_000_000L),
                    stream("720p", "Sintel.2010.720p.WEB-DL.x264.AAC.mkv", 540_000_000L),
                    stream("SD", "Sintel.2010.480p.WEB-DL.x264.AAC.mkv", 260_000_000L),
                ).map { PlaybackSourceCandidate(stream = it) },
                context,
            )
        }
        PlaybackQualitySheet(
            options = options,
            isLoading = false,
            isSelecting = false,
            selectionContext = context,
            estimatedMbps = 240.0,
            isConnectionMeasured = true,
            isConnectionStale = false,
            isMeasuringConnection = false,
            onOptionSelected = {},
            onRetestConnection = {},
            onChooseManually = {},
            onAdjustPreferences = {},
            onDismiss = {},
        )
    }

    // --- Watch Together -----------------------------------------------------------------------

    private fun member(p: SocialProfileSummary, state: SourceResolutionState) = WatchPartyParticipant(
        profileId = p.profileId,
        role = "member",
        readyState = state,
        readyError = null,
        profile = PartyParticipantProfile(displayName = p.displayName, handle = p.handle, avatarColorHex = p.avatarColorHex),
        connected = true,
        sourceMatch = null,
        joinedAt = "2026-09-21T12:00:00Z",
    )

    private val party = WatchPartyState(
        id = "party-sintel",
        hostProfileId = PromoPeople.maya.profileId,
        status = WatchPartyStatus.lobby,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        stage = WatchPartyStage.ready_to_launch,
        content = PartyContent(
            contentId = sintel.id,
            contentType = "movie",
            videoId = sintel.id,
            title = sintel.name,
            poster = PromoArt.poster(sintel.slug),
        ),
        positionMs = 0,
        durationMs = sintel.durationMs,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "",
        members = listOf(
            member(PromoPeople.maya, SourceResolutionState.ready),
            member(PromoPeople.theo, SourceResolutionState.ready),
            member(PromoPeople.ines, SourceResolutionState.ready),
            member(PromoPeople.jonah, SourceResolutionState.source_ready),
        ),
    )

    @Composable
    private fun Lobby(viewer: String) {
        val sync = WatchPartySyncState(clockLocked = true, bestRttMs = 38)
        val model = PartyLobbyModel(
            party = party,
            viewerProfileId = viewer,
            isHost = viewer == party.hostProfileId,
            presentation = PartyPresentationProjector.project(
                party = party,
                selfProfileId = viewer,
                health = PartyHealthState(realtime = PartyRealtimeHealth.Live),
                realtime = sync,
                partyNowMs = 0L,
            ),
            sync = sync,
            inviteCode = if (viewer == party.hostProfileId) "MX7Q4KRT2WLA" else null,
            errorMessage = null,
            joinHandoff = null,
            addonNotice = null,
            hostSourceStaged = true,
            hasSource = true,
            sourceLabel = "2160p · WEB-DL · Open Movie Archive",
            waitForEveryone = true,
            pauseForAwayUsers = false,
            invitableFriends = listOf(PromoPeople.priya, PromoPeople.sam),
        )
        WatchPartyLobbyFrame(poster = PromoArt.poster(sintel.slug)) {
            PartyLobbyContent(
                model = model,
                actions = PartyLobbyActions(
                    onRequestDeparture = {}, onChoose = {}, onStart = {}, onLeave = {}, onInvite = {},
                    onControlMode = {}, onWaitForEveryone = {}, onPauseForAwayUsers = {},
                ),
            )
        }
    }

    // --- Social -------------------------------------------------------------------------------

    private val watchingNow: List<WatchingNowItem> = listOf(
        WatchingNowItem(
            profile = PromoPeople.theo, contentId = PromoCatalog.metropolis.id, contentType = "movie",
            videoId = PromoCatalog.metropolis.id, title = PromoCatalog.metropolis.name,
            poster = PromoArt.poster("metropolis"), background = PromoArt.backdrop("metropolis").takeIf { PromoArt.has("backdrop", "metropolis") },
            sessionId = "s-theo", positionMs = 41 * 60_000L, durationMs = PromoCatalog.metropolis.durationMs,
            effectiveJoinPolicy = WatchJoinPolicy.direct, state = SocialPlaybackState.playing,
            heartbeatAt = iso(nowMs - 20_000L),
        ),
    ) + listOf(PromoPeople.jonah, PromoPeople.priya, PromoPeople.sam).map { p ->
        WatchingNowItem(
            profile = p, contentId = PromoCatalog.spriteFright.id, contentType = "movie",
            videoId = PromoCatalog.spriteFright.id, title = PromoCatalog.spriteFright.name,
            poster = PromoArt.poster("sprite-fright"), background = PromoArt.backdrop("sprite-fright").takeIf { PromoArt.has("backdrop", "sprite-fright") },
            sessionId = "s-${p.handle}", positionMs = 4 * 60_000L + 12_000L, durationMs = PromoCatalog.spriteFright.durationMs,
            effectiveJoinPolicy = WatchJoinPolicy.approval, state = SocialPlaybackState.playing,
            heartbeatAt = iso(nowMs - 20_000L),
            partyId = "party-sprite", partyHostProfileId = PromoPeople.jonah.profileId, partyMemberCount = 3,
        )
    } + WatchingNowItem(
        profile = PromoPeople.ines, contentId = PromoCatalog.charade.id, contentType = "movie",
        videoId = PromoCatalog.charade.id, title = PromoCatalog.charade.name,
        poster = PromoArt.poster("charade"), sessionId = "s-ines",
        positionMs = 67 * 60_000L, durationMs = PromoCatalog.charade.durationMs,
        effectiveJoinPolicy = WatchJoinPolicy.approval, state = SocialPlaybackState.paused,
        heartbeatAt = iso(nowMs - 40_000L),
    )

    private fun run(id: String, p: SocialProfileSummary, t: PromoTitle, hoursAgo: Int) = RecentActivityRun(
        runId = id, profile = p, contentId = t.id, contentType = "movie", videoId = t.id, title = t.name,
        poster = PromoArt.poster(t.slug),
        firstEventTime = iso(nowMs - hoursAgo * 3_600_000L - 1_800_000L),
        lastEventTime = iso(nowMs - hoursAgo * 3_600_000L),
    )

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    private val activity = listOf(
        run("a1", PromoPeople.priya, PromoCatalog.spring, 2),
        run("a2", PromoPeople.theo, PromoCatalog.nightOfTheLivingDead, 3),
        run("a3", PromoPeople.sam, PromoCatalog.nightOfTheLivingDead, 5),
        run("a4", PromoPeople.ines, PromoCatalog.hisGirlFriday, 20),
        run("a5", PromoPeople.jonah, PromoCatalog.cosmos, 26),
        run("a6", PromoPeople.theo, PromoCatalog.theGeneral, 30),
        run("a7", PromoPeople.priya, PromoCatalog.charge, 50),
        run("a8", PromoPeople.sam, PromoCatalog.tearsOfSteel, 75),
        run("a9", PromoPeople.ines, PromoCatalog.sherlockJr, 100),
    ).filter { r -> PromoCatalog.all.any { it.id == r.contentId } }

    @Composable
    private fun Social() {
        val state = SocialUiState(
            capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true),
            activeProfileId = PromoPeople.maya.profileId,
            me = PromoPeople.maya,
            friends = PromoPeople.friends,
            watchingNow = watchingNow,
            activity = activity,
        )
        val groups = groupFriendActivity(state.activity)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SocialFeed(
                model = SocialFeedModel(
                    state = state,
                    handle = "maya",
                    activityGroups = groups,
                    activityBuckets = bucketFriendActivity(groups, nowMs),
                    activityNowMs = nowMs,
                    joinAffordance = { item -> watchingNowJoinAffordance(item, OutgoingJoinRequestState.Idle, null) },
                ),
                actions = SocialFeedActions(),
                listState = rememberLazyListState(),
            )
        }
    }

    // --- Theo's side of Watch Together ---------------------------------------------------------

    private val theoBinding = com.nuvio.app.features.social.JoinRequestBinding(PromoPeople.theo.profileId, 1)
    private val mayaTarget = com.nuvio.app.features.social.JoinRequestTarget(
        profileId = PromoPeople.maya.profileId,
        displayName = PromoPeople.maya.displayName,
        avatarColorHex = PromoPeople.maya.avatarColorHex,
        sessionId = "s-maya",
    )
    private val sintelRequestContent = com.nuvio.app.features.social.JoinRequestContent(
        contentId = sintel.id, videoId = sintel.id, title = sintel.name,
        poster = PromoArt.poster(sintel.slug), background = PromoArt.backdrop(sintel.slug),
    )
    private fun theoPending() = OutgoingJoinRequestState.Pending(
        theoBinding, mayaTarget, sintelRequestContent, "r-theo",
        com.nuvio.app.features.watchparty.currentEpochMs() + 95_000L,
    )

    /** Maya, as her friends see her once she is watching: presence published, Ask to join. */
    private val mayaWatching = WatchingNowItem(
        profile = PromoPeople.maya, contentId = sintel.id, contentType = "movie", videoId = sintel.id,
        title = sintel.name, poster = PromoArt.poster(sintel.slug), background = PromoArt.backdrop(sintel.slug),
        sessionId = "s-maya", positionMs = 290_000L, durationMs = sintel.durationMs,
        effectiveJoinPolicy = WatchJoinPolicy.approval, state = SocialPlaybackState.playing,
        heartbeatAt = iso(nowMs - 10_000L),
    )

    /**
     * Theo's Android phone: the Social tab with Maya in Watching Now, and the outgoing-request dock
     * where `MainAppContent` puts it on a phone - top end, under the status bar.
     */
    @Composable
    private fun TheoPhone(outgoing: OutgoingJoinRequestState) = Box(Modifier.fillMaxSize()) {
        PhoneShell(AppScreenTab.Social, statusInset = true) {
            val state = SocialUiState(
                capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true),
                activeProfileId = PromoPeople.theo.profileId,
                me = PromoPeople.theo,
                friends = listOf(PromoPeople.maya, PromoPeople.ines, PromoPeople.jonah, PromoPeople.priya, PromoPeople.sam),
                watchingNow = listOf(mayaWatching) + watchingNow.filter { it.profile.profileId != PromoPeople.theo.profileId },
                activity = activity.filter { it.profile.profileId != PromoPeople.theo.profileId },
            )
            val groups = groupFriendActivity(state.activity)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                SocialFeed(
                    model = SocialFeedModel(
                        state = state,
                        handle = "theo",
                        activityGroups = groups,
                        activityBuckets = bucketFriendActivity(groups, nowMs),
                        activityNowMs = nowMs,
                        joinAffordance = { item -> watchingNowJoinAffordance(item, outgoing, null) },
                    ),
                    actions = SocialFeedActions(),
                    listState = rememberLazyListState(),
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            com.nuvio.app.features.social.WatchTogetherDock(
                state = outgoing,
                windowWidth = maxWidth,
                modifier = Modifier.padding(end = 16.dp, top = PhoneStatusBar + 12.dp),
            )
        }
    }

    // --- Downloads ----------------------------------------------------------------------------

    private val mb = 1_000_000L

    private fun dl(
        t: PromoTitle,
        status: DownloadStatus,
        total: Long,
        fraction: Float = 1f,
        activity: DownloadActivity? = null,
        position: Long = 0,
        quality: String = "2160p WEB-DL HEVC",
    ) = DownloadItem(
        id = "dl-${t.slug}",
        ownerProfileId = 1,
        contentType = "movie",
        parentMetaId = t.id,
        parentMetaType = "movie",
        videoId = t.id,
        title = t.name,
        poster = PromoArt.poster(t.slug),
        background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) },
        streamTitle = "${t.name.replace(' ', '.')}.${t.year}.${quality.replace(' ', '.')}",
        providerName = "Open Movie Archive",
        fileName = "${t.slug}.mkv",
        status = status,
        activity = activity,
        downloadedBytes = (total * fraction).toLong(),
        totalBytes = total,
        queuePosition = position,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    /** [run] 0..1 advances the two transfers, as a real queue would between two frames. */
    @Composable
    private fun PhoneDownloads(run: Float) {
        val items = listOf(
            dl(sintel, DownloadStatus.Downloading, 3_100 * mb, 0.38f + 0.22f * run, DownloadActivity.TRANSFERRING, 0),
            dl(PromoCatalog.spring, DownloadStatus.Downloading, 1_450 * mb, 0.12f + 0.25f * run, DownloadActivity.TRANSFERRING, 1),
            dl(PromoCatalog.cosmos, DownloadStatus.Queued, 2_300 * mb, 0f, null, 2),
            dl(PromoCatalog.charge, DownloadStatus.Completed, 610 * mb),
            dl(PromoCatalog.bigBuckBunny, DownloadStatus.Completed, 1_280 * mb, quality = "1080p BluRay x264"),
            dl(PromoCatalog.metropolis, DownloadStatus.Completed, 4_900 * mb, quality = "1080p BluRay x264"),
            dl(PromoCatalog.theGeneral, DownloadStatus.Completed, 2_200 * mb, quality = "1080p BluRay x264"),
        )
        val attention = AttentionGrouping.group(items, emptyList(), nowMs)
        val unfinished = items.filter {
            it.status != DownloadStatus.Completed && DownloadPresenter.item(it, nowMs).phase != DownloadUserPhase.NEEDS_YOU
        }
        val queue = DownloadQueueGrouping.group(unfinished, items, emptyList(), nowMs)
        NuvioScreen(topPadding = 0.dp) {
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    NuvioScreenHeader(
                        modifier = Modifier.downloadsContentWidth(),
                        title = "Downloads",
                        actions = {
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                    Box(Modifier.downloadsContentWidth()) {
                        LibraryTopSwitcher(selectedDestination = LibrarySubDestination.Downloads, onDestinationSelected = {})
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            downloadsRootContent(
                uiState = DownloadsUiState(items),
                batches = emptyList(),
                storage = DownloadStorageSummary(usedBytes = items.sumOf { it.downloadedBytes }, freeBytes = 96_000 * mb),
                attention = attention,
                queue = queue,
                cleanup = null,
                nowEpochMs = nowMs,
                onOpenDownload = {},
                onOpenShow = { _, _ -> },
                onRequestTitleDeletion = {},
                onAttentionAction = { _, _ -> },
                onChooseMember = {},
                onOpenDetail = {},
                onReviewCleanup = {},
                onCancelGroup = {},
            )
        }
    }

    private companion object {
        /** A typical phone's status bar and gesture bar, which the desktop test host reports as zero. */
        val PhoneStatusBar: Dp = 44.dp
        val PhoneGestureBar: Dp = 18.dp
    }
}
