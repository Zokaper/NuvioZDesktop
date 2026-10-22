package com.nuvio.app.features.tray

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.clickable
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
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialNavigation
import com.nuvio.app.features.social.SocialPopup
import com.nuvio.app.features.social.SocialPopupCard
import com.nuvio.app.features.social.SocialPopupKind
import com.nuvio.app.features.social.SocialPopupLedger
import com.nuvio.app.features.social.SocialPopupWidth
import com.nuvio.app.features.social.SocialStorage
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.SocialUiState
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

/**
 * Steam-style Social pop-ups on the desktop (Social V2, 2026-10-16).
 *
 * ## What pops
 *
 * What [socialPopupCandidates][com.nuvio.app.features.social.socialPopupCandidates] offers, once each
 * ([SocialPopupLedger], shared with the phone's banners), filtered by the tray settings. Something older
 * than [FreshnessMs] is not popped at all; it is history, and the Inbox holds it.
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
    // Same store and keys as before the ledger was shared: SocialStorage's desktop actual reads them.
    private val ledger = SocialPopupLedger(SocialStorage::loadPopupSeen, SocialStorage::savePopupSeen, FreshnessMs)

    private val _visible = MutableStateFlow<List<SocialPopup>>(emptyList())
    val visible: StateFlow<List<SocialPopup>> = _visible.asStateFlow()

    private val _windowFocused = MutableStateFlow(false)

    /** The player's video is on screen: a click must not navigate away from it. */
    private val _playerOnScreen = MutableStateFlow(false)

    private val queue = SocialPopupQueue(maxVisible = MaxVisible, visibleMs = VisibleMs)
    private var expiryJob: Job? = null
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
        // During playback a click still raises the window - Nuvio may be behind another app with
        // the film paused - but leaves the player where it is rather than navigating out of it.
        if (!_playerOnScreen.value) SocialNavigation.open(popup.target)
        DesktopTrayController.show()
        DesktopTrayController.bringToFront()
    }

    fun dismiss(key: String) = mutate { queue.dismiss(key, it) }

    /** The pointer is over the stack: nothing goes until it leaves. */
    fun setHovered(hovered: Boolean) = mutate { now -> if (hovered) queue.hold(now) else queue.release(now) }

    /** Every change goes through here: publish what is on screen, then wait for the next card's time. */
    @Synchronized
    private fun mutate(change: (nowMs: Long) -> Unit) {
        change(System.currentTimeMillis())
        _visible.value = queue.visible
        expiryJob?.cancel()
        val due = queue.nextDueAtMs() ?: return
        expiryJob = scope.launch {
            delay((due - System.currentTimeMillis()).coerceAtLeast(0L))
            mutate { now -> queue.expire(now) }
        }
    }

    @Synchronized
    private fun onState(state: SocialUiState) {
        if (!SocialFeaturePreferencesRepository.isEnabledNow) return
        val fresh = ledger.take(state, System.currentTimeMillis())
        if (fresh.isEmpty()) return
        val prefs = DesktopTrayPreferences.current
        val allowed = fresh.filter { prefs.allows(it.kind) }
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
        mutate { now -> allowed.forEach { queue.offer(it, now) } }
    }

    private fun DesktopTrayPreferencesState.allows(kind: SocialPopupKind): Boolean = popupsEnabled && when (kind) {
        SocialPopupKind.FriendRequest -> popupFriendRequests
        SocialPopupKind.PartyInvite, SocialPopupKind.JoinRequest -> popupParties
        SocialPopupKind.FriendWatching -> popupFriendWatching
        SocialPopupKind.Recommendation -> popupRecommendations
    }

    private const val FreshnessMs = 6L * 60L * 60L * 1000L
    private const val VisibleMs = 7_000L
    private const val MaxVisible = 3
}

/**
 * The pop-up window over the desktop: undecorated, transparent, always on top, never focusable, no taskbar
 * entry, pinned to the bottom-right of the primary screen's work area.
 *
 * Cards slide in from the screen edge and back out (2026-10-18; they used to just appear). A card stays
 * composed until its exit has finished, so the window is sized from the stack's measured height rather
 * than a count of cards: it follows the stack as cards expand and collapse, and it is not created at all
 * while nothing is on screen or leaving. A window the size of three slots would be simpler, but its
 * transparent part would swallow clicks meant for whatever is under it.
 */
@Composable
internal fun ApplicationScope.DesktopSocialPopupWindow() {
    val popups by DesktopSocialPopups.visible.collectAsState()
    // On screen, plus the ones still sliding out. Oldest first, like the queue.
    val rendered = remember { mutableStateListOf<SocialPopup>() }
    LaunchedEffect(popups) {
        popups.forEach { popup -> if (rendered.none { it.key == popup.key }) rendered += popup }
    }
    if (rendered.isEmpty()) return
    val workArea = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val margin = 14
    var stackHeight by remember { mutableStateOf(0) }
    val width = SocialPopupWidth.value.toInt() + margin * 2
    val height = stackHeight.coerceAtLeast(1) + margin * 2
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
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().padding(margin.dp), contentAlignment = Alignment.BottomEnd) {
                SocialPopupStack(
                    rendered = rendered,
                    showing = popups,
                    onGone = { key -> rendered.removeAll { it.key == key } },
                    // Measured unbounded, so a card expanding past the window's current height is
                    // still counted, and the window grows to it on the next frame.
                    modifier = Modifier
                        .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                        .onSizeChanged { stackHeight = with(density) { it.height.toDp().value.toInt() } },
                )
            }
        }
    }
}

@Composable
private fun SocialPopupStack(
    rendered: List<SocialPopup>,
    showing: List<SocialPopup>,
    onGone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { DesktopSocialPopups.setHovered(hovered) }
    Column(modifier.hoverable(hover), horizontalAlignment = Alignment.End) {
        rendered.forEach { popup ->
            key(popup.key) {
                val visible = remember { MutableTransitionState(false) }
                visible.targetState = showing.any { it.key == popup.key }
                AnimatedVisibility(
                    visibleState = visible,
                    enter = slideInHorizontally(PopupMotion) { it } + fadeIn(PopupFade) + expandVertically(PopupResize, expandFrom = Alignment.Top),
                    exit = slideOutHorizontally(PopupMotion) { it } + fadeOut(PopupFade) + shrinkVertically(PopupResize, shrinkTowards = Alignment.Top),
                ) {
                    // The gap travels with its card, so a collapsing slot takes its spacing with it.
                    SocialPopupCard(
                        popup,
                        Modifier.padding(top = PopupGap).clickable(enabled = visible.targetState) { DesktopSocialPopups.onClick(popup) },
                    )
                }
                if (visible.isIdle && !visible.currentState && !visible.targetState) {
                    LaunchedEffect(Unit) { onGone(popup.key) }
                }
            }
        }
    }
}

private val PopupGap = 10.dp
private val PopupMotion = tween<IntOffset>(durationMillis = 280, easing = FastOutSlowInEasing)
private val PopupFade = tween<Float>(durationMillis = 220)
private val PopupResize = tween<IntSize>(durationMillis = 240, easing = FastOutSlowInEasing)
