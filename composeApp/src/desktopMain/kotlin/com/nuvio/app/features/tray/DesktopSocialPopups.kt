package com.nuvio.app.features.tray

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialInboxEvent
import com.nuvio.app.features.social.SocialNavigation
import com.nuvio.app.features.social.SocialNavigationTarget
import com.nuvio.app.features.social.SocialNotificationKind
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.SocialUiState
import com.nuvio.app.features.social.artRef
import com.nuvio.app.features.social.parseSocialTimestampMs
import com.nuvio.app.features.social.socialNavigationKey
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Steam-style Social pop-ups on the desktop (Social V2, 2026-10-16).
 *
 * ## What pops
 *
 * Friend requests, party invites, requests to join your playback, and "a friend started watching" for
 * friends you asked to be told about (an inbox event the backend only writes for them). Each is popped
 * **once**: the keys of everything ever popped - or present when a profile was first seen on this
 * machine - are kept per profile, so a restart, a refresh or a reconnect never repeats one. Something
 * older than [FreshnessMs] is not popped at all; it is history, and the Inbox holds it.
 *
 * ## Where it pops
 *
 * Always in the same place, like Steam's: bottom-right above the taskbar, in a small always-on-top
 * window that never takes focus and has no taskbar entry - whether Nuvio is in front, behind something
 * or hidden in the tray. (Until 2026-10-17 the card was drawn inside Nuvio's window when it was in
 * front; the maintainer found the switch odd, and during playback the mpv surface and WebView2 controls
 * covered it.) Over another fullscreen game or app only when the user allowed it; Nuvio's own fullscreen
 * player never holds one back.
 *
 * A click opens Nuvio on the relevant place ([SocialNavigation]) and dismisses the card - except while
 * a video is on screen, where it only dismisses, because opening Social would end what is playing.
 * No buttons.
 */
internal object DesktopSocialPopups {
    private val log = Logger.withTag("SocialPopups")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val store = DesktopStorage.store("nuvio_social_popups")

    private val _visible = MutableStateFlow<List<SocialPopup>>(emptyList())
    val visible: StateFlow<List<SocialPopup>> = _visible.asStateFlow()

    private val _windowFocused = MutableStateFlow(false)

    /** The player's video is on screen: a click must not navigate away from it. */
    private val _playerOnScreen = MutableStateFlow(false)

    private var seenProfileId: String? = null
    private val seen = LinkedHashSet<String>()
    private val dismissJobs = mutableMapOf<String, Job>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            SocialRepository.uiState.collect { state -> onState(state) }
        }
    }

    /** Nuvio's own window gained or lost focus (or went away with the tray). */
    fun setWindowFocused(focused: Boolean) {
        _windowFocused.value = focused
    }

    /** The native player surface was promoted (true) or concealed (false). */
    fun setPlayerOnScreen(onScreen: Boolean) {
        _playerOnScreen.value = onScreen
    }

    fun onClick(popup: SocialPopup) {
        dismiss(popup.key)
        if (_playerOnScreen.value) return
        SocialNavigation.open(popup.target)
        DesktopTrayController.show()
        DesktopTrayController.bringToFront()
    }

    fun dismiss(key: String) {
        dismissJobs.remove(key)?.cancel()
        _visible.value = _visible.value.filterNot { it.key == key }
    }

    @Synchronized
    private fun onState(state: SocialUiState) {
        val profileId = state.activeProfileId ?: return
        // A loading or offline-cache state is a replay of what this machine already had; baselining or
        // popping from it would either swallow fresh items or pop stale ones.
        if (state.isLoading || state.isOfflineCache) return
        if (!SocialFeaturePreferencesRepository.isEnabledNow) return
        if (seenProfileId != profileId) {
            seenProfileId = profileId
            seen.clear()
            val stored = store.getString(seenKey(profileId))
            if (stored == null) {
                // First time this profile is seen on this machine: everything already here is history.
                seen += candidates(state).map(SocialPopup::key)
                persist(profileId)
                return
            }
            runCatching { json.decodeFromString<List<String>>(stored) }.getOrNull()?.let(seen::addAll)
        }
        val nowMs = System.currentTimeMillis()
        val fresh = candidates(state).filter { it.key !in seen }
        if (fresh.isEmpty()) return
        fresh.forEach { seen += it.key }
        persist(profileId)
        val prefs = DesktopTrayPreferences.current
        val allowed = fresh.filter { popup ->
            (createdAtMs[popup.key] ?: 0L) >= nowMs - FreshnessMs && prefs.allows(popup.kind)
        }
        if (allowed.isEmpty()) return
        // Nuvio in front means any fullscreen is Nuvio's own player, which never holds a pop-up back.
        // Activation as well as focus: with keyboard focus inside the player's native child windows, AWT
        // can report the frame as unfocused while it is still the window in front.
        val front = DesktopTrayController.windowVisible.value &&
            (_windowFocused.value || DesktopTrayController.mainWindow?.isActive == true)
        val kinds = allowed.joinToString { it.kind.name }
        if (!front && !prefs.popupsOverFullscreen && WindowsNotificationState.isFullscreenBusy()) {
            log.i { "held $kinds: another app is fullscreen" }
            return
        }
        log.i { "popped $kinds (nuvio in front=$front, video on screen=${_playerOnScreen.value})" }
        allowed.forEach(::show)
    }

    private fun show(popup: SocialPopup) {
        _visible.value = (_visible.value.filterNot { it.key == popup.key } + popup).takeLast(MaxVisible)
        dismissJobs[popup.key]?.cancel()
        dismissJobs[popup.key] = scope.launch {
            delay(VisibleMs)
            dismiss(popup.key)
        }
    }

    private fun DesktopTrayPreferencesState.allows(kind: SocialPopupKind): Boolean = popupsEnabled && when (kind) {
        SocialPopupKind.FriendRequest -> popupFriendRequests
        SocialPopupKind.PartyInvite, SocialPopupKind.JoinRequest -> popupParties
        SocialPopupKind.FriendWatching -> popupFriendWatching
    }

    /** When each candidate was created, by key, for the freshness check. Filled by [candidates]. */
    private val createdAtMs = mutableMapOf<String, Long>()

    /** Everything in [state] that could pop: pending actionable notices and friend-started-watching events. */
    private fun candidates(state: SocialUiState): List<SocialPopup> {
        createdAtMs.clear()
        val popups = mutableListOf<SocialPopup>()
        state.notifications
            .filter { it.readAt == null && it.state == "pending" && it.availableActions.isNotEmpty() }
            .forEach { notice ->
                val content = notice.contentSummary
                val popup = when (notice.kind) {
                    SocialNotificationKind.FriendRequest -> SocialPopup(
                        key = "notice:${notice.id}",
                        kind = SocialPopupKind.FriendRequest,
                        person = notice.actor,
                        line = "wants to be friends",
                        title = "@${notice.actor.handle}",
                        meta = null,
                        art = SocialArtRef(title = notice.actor.handle, contentType = null, contentId = null, poster = null),
                        target = SocialNavigationTarget.FriendRequests,
                    )
                    SocialNotificationKind.PartyInvitation -> SocialPopup(
                        key = "notice:${notice.id}",
                        kind = SocialPopupKind.PartyInvite,
                        person = notice.actor,
                        line = "invited you to watch",
                        title = content?.title ?: "a Watch Together party",
                        meta = listOfNotNull("Watch party", episodeLabel(content?.season, content?.episode)).joinToString(" · "),
                        art = content?.artRef() ?: SocialArtRef("party", null, null, null),
                        target = SocialNavigationTarget.Inbox,
                    )
                    SocialNotificationKind.WatchingNowJoinRequest -> SocialPopup(
                        key = "notice:${notice.id}",
                        kind = SocialPopupKind.JoinRequest,
                        person = notice.actor,
                        line = "asked to join",
                        title = content?.title ?: "your playback",
                        meta = listOfNotNull("Your playback", episodeLabel(content?.season, content?.episode)).joinToString(" · "),
                        art = content?.artRef() ?: SocialArtRef("join", null, null, null),
                        target = SocialNavigationTarget.Inbox,
                    )
                }
                createdAtMs[popup.key] = parseSocialTimestampMs(notice.createdAt) ?: 0L
                popups += popup
            }
        state.inbox
            .filter { it.kind == SocialInboxEvent.FriendWatching && it.readAt == null }
            .filter { event -> state.prefsFor(event.actor.profileId).notifyWhenWatching }
            .forEach { event ->
                val payload = event.payload
                val live = state.watchingNow.firstOrNull { it.profile.profileId == event.actor.profileId }
                val popup = SocialPopup(
                    key = "inbox:${event.id}",
                    kind = SocialPopupKind.FriendWatching,
                    person = event.actor,
                    line = "is watching",
                    title = payload.title ?: live?.title ?: "something",
                    meta = episodeLabel(payload.season, payload.episode),
                    art = SocialArtRef(
                        title = payload.title.orEmpty(),
                        contentType = payload.contentType,
                        contentId = payload.contentId,
                        poster = payload.poster,
                        landscape = listOf(payload.background),
                        videoId = payload.videoId,
                        season = payload.season,
                        episode = payload.episode,
                    ),
                    target = live?.let { SocialNavigationTarget.WatchingSession(it.socialNavigationKey()) }
                        ?: SocialNavigationTarget.Inbox,
                )
                createdAtMs[popup.key] = parseSocialTimestampMs(event.createdAt) ?: 0L
                popups += popup
            }
        return popups
    }

    private fun episodeLabel(season: Int?, episode: Int?): String? =
        if (season != null && episode != null) "S$season E$episode" else null

    private fun persist(profileId: String) {
        val keys = seen.toList().takeLast(MaxRemembered)
        store.putString(seenKey(profileId), json.encodeToString(keys))
    }

    private fun seenKey(profileId: String) = "seen_$profileId"

    private const val FreshnessMs = 6L * 60L * 60L * 1000L
    private const val VisibleMs = 7_000L
    private const val MaxVisible = 3
    private const val MaxRemembered = 400
}

/**
 * The pop-up window over the desktop: undecorated, transparent, always on top, never focusable, no taskbar
 * entry, sized to its cards and pinned to the bottom-right of the primary screen's work area.
 */
@Composable
internal fun ApplicationScope.DesktopSocialPopupWindow() {
    val popups by DesktopSocialPopups.visible.collectAsState()
    if (popups.isEmpty()) return
    val workArea = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val margin = 14
    val gap = 10
    val width = SocialPopupWidth.value.toInt() + margin * 2
    val height = popups.size * SocialPopupHeight.value.toInt() + (popups.size - 1) * gap + margin * 2
    val x = workArea.x + workArea.width - width
    val y = workArea.y + workArea.height - height
    Window(
        create = {
            ComposeWindow().apply {
                // No taskbar button, no title bar, and never steals focus from whatever the user is doing.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                focusableWindowState = false
                isAutoRequestFocus = false
                isResizable = false
                title = "Nuvio Z"
            }
        },
        dispose = ComposeWindow::dispose,
        update = { window ->
            window.setBounds(x, y, width, height)
        },
    ) {
        NuvioTheme {
            Box(Modifier.fillMaxSize().padding(margin.dp), contentAlignment = Alignment.BottomEnd) {
                SocialPopupStack(popups)
            }
        }
    }
}

@Composable
private fun SocialPopupStack(popups: List<SocialPopup>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        popups.forEach { popup ->
            key(popup.key) {
                SocialPopupCard(popup, Modifier.clickable { DesktopSocialPopups.onClick(popup) })
            }
        }
    }
}
