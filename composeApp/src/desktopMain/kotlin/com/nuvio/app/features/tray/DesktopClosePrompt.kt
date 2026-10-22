package com.nuvio.app.features.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialLiveColor
import com.nuvio.app.features.social.SocialNavigationTarget
import com.nuvio.app.features.social.SocialPillButton
import com.nuvio.app.features.social.SocialPopup
import com.nuvio.app.features.social.SocialPopupCard
import com.nuvio.app.features.social.SocialPopupKind
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialRepository

/**
 * The first press of the window's close button: "Keep Nuvio Z in the tray?" (maintainer's pick B, with a
 * picture of what hiding is for). Hide to tray is the default answer; either answer becomes the
 * "Closing the window" setting. Escape or a click outside dismisses it without answering.
 *
 * It is also where Start with Windows is first offered (2026-10-22): someone choosing to keep Nuvio
 * running is the person it is for. Ticked by default where it can apply, marked Recommended, and
 * applied with either answer - quitting now and starting in the tray at sign-in is a coherent pair.
 */
@Composable
internal fun DesktopClosePromptHost(onQuit: () -> Unit) {
    val visible by DesktopTrayController.closePromptVisible.collectAsState()
    if (!visible) return
    val startEnabled by DesktopStartWithWindows.enabled.collectAsState()
    LaunchedEffect(Unit) { DesktopStartWithWindows.refresh() }
    var startWithWindows by remember { mutableStateOf(true) }
    val offerStart = DesktopStartWithWindows.isAvailable && startEnabled == false
    val answer: (Boolean) -> Unit = { hide ->
        if (offerStart && startWithWindows) DesktopStartWithWindows.setEnabled(true)
        DesktopTrayController.answerClosePrompt(hideToTray = hide, quit = onQuit)
    }
    NuvioTheme {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    DesktopTrayController.dismissClosePrompt()
                }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape) {
                        DesktopTrayController.dismissClosePrompt()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            DesktopClosePromptCard(
                offerStartWithWindows = offerStart,
                startWithWindows = startWithWindows,
                onStartWithWindowsChange = { startWithWindows = it },
                onQuit = { answer(false) },
                onHide = { answer(true) },
                // Swallow clicks on the card itself so they do not reach the scrim.
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

/** The card itself, stateless, so the render harness draws exactly what the window shows. */
@Composable
internal fun DesktopClosePromptCard(
    offerStartWithWindows: Boolean,
    startWithWindows: Boolean,
    onStartWithWindowsChange: (Boolean) -> Unit,
    onQuit: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.width(460.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ClosePromptPicture()
                Text("Keep Nuvio Z in the tray?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Closing the window sends Nuvio Z to the tray. It stays on standby, so you still get notifications " +
                        "from friends and it opens again much faster. While in the tray it uses about as much memory as a browser tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (offerStartWithWindows) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .clickable(role = Role.Checkbox) { onStartWithWindowsChange(!startWithWindows) }
                            .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = startWithWindows, onCheckedChange = onStartWithWindowsChange)
                        Column(Modifier.weight(1f)) {
                            Text("Start Nuvio Z with Windows (Recommended)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Straight to the tray when you sign in, ready the moment you open it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "You can change this any time in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    SocialPillButton("Quit Nuvio Z", onQuit, compact = false)
                    SocialPillButton("Hide to tray", onHide, primary = true, compact = false)
                }
            }
        }
    }
}

/**
 * A miniature desktop corner: a pop-up wholly inside the frame, above a taskbar whose tray shows the Nuvio
 * dot. Drawn from the user's own Social state when there is any (a friend watching, else a friend), so the
 * picture is about people they know; a neutral card otherwise.
 */
@Composable
private fun ClosePromptPicture() {
    val state by SocialRepository.uiState.collectAsState()
    val live = state.watchingNow.firstOrNull()
    val friend = live?.profile ?: state.friends.firstOrNull()
        ?: SocialProfileSummary(profileId = "sample", handle = "friend", displayName = "A friend", avatarColorHex = "#8E6CF0")
    val sample = SocialPopup(
        key = "sample",
        kind = SocialPopupKind.FriendWatching,
        person = friend,
        line = "is watching",
        title = live?.title ?: "something good",
        meta = live?.let { item -> if (item.season != null && item.episode != null) "S${item.season} E${item.episode}" else null },
        art = SocialArtRef(
            title = live?.title ?: "sample",
            contentType = live?.contentType,
            contentId = live?.contentId,
            poster = live?.poster,
            videoId = live?.videoId,
            season = live?.season,
            episode = live?.episode,
        ),
        target = SocialNavigationTarget.Inbox,
    )
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF243B55), Color(0xFF141E30), Color(0xFF3A1C4A)))),
    ) {
        SocialPopupCard(
            sample,
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 22.dp + 12.dp)
                .wrapContentSize(Alignment.BottomEnd, unbounded = true)
                .graphicsLayer {
                    scaleX = 0.72f
                    scaleY = 0.72f
                    transformOrigin = TransformOrigin(1f, 1f)
                },
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp).background(Color(0xFF1C1C22)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(SocialLiveColor))
            Text("12:00", color = Color.White, fontSize = 8.sp)
        }
    }
}
