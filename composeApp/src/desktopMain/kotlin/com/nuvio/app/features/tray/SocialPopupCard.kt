package com.nuvio.app.features.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialArtwork
import com.nuvio.app.features.social.artwork
import com.nuvio.app.features.social.SocialFaces
import com.nuvio.app.features.social.SocialNavigationTarget
import com.nuvio.app.features.social.SocialProfileSummary

internal enum class SocialPopupKind { FriendWatching, FriendRequest, PartyInvite, JoinRequest }

/** One pop-up: who, what happened, the title it is about, and where a click takes you. */
internal data class SocialPopup(
    /** Stable per event, so a refresh that re-delivers it never pops it twice. */
    val key: String,
    val kind: SocialPopupKind,
    val person: SocialProfileSummary,
    val line: String,
    val title: String,
    val meta: String?,
    val art: SocialArtRef,
    val target: SocialNavigationTarget,
)

/**
 * The pop-up itself: a backdrop card in the Watching Now family (maintainer's pick, 2026-10-16, "B").
 *
 * No buttons. The whole card is the target and opens Nuvio on the relevant place; a pop-up you can act on
 * from the corner of the screen was rendered (C, D1-D3) and turned down in favour of one tap that takes you
 * there. The same composable draws the real pop-up window, the in-player copy and the close prompt's picture.
 */
@Composable
internal fun SocialPopupCard(popup: SocialPopup, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier.width(SocialPopupWidth).height(SocialPopupHeight)
                .clip(SocialPopupShape)
                .background(Color(0xFF17181C))
                .border(1.dp, Color.White.copy(alpha = 0.08f), SocialPopupShape),
        ) {
            // A friend request has no title: the art seeds on the person, so it is still a picture.
            SocialArtwork(popup.art.artwork(), seed = popup.title.ifBlank { popup.person.profileId }, modifier = Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.05f)),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.CenterStart).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SocialFaces(listOf(popup.person), 46.dp, liveRing = popup.kind == SocialPopupKind.FriendWatching)
                Column(Modifier.widthIn(max = 250.dp)) {
                    // The name gives way, never the verb: "A Friend With A Ve... wants to be friends".
                    Row {
                        Text(
                            popup.person.displayName,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            " ${popup.line}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        popup.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    popup.meta?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                "NUVIO",
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

internal val SocialPopupWidth = 380.dp
internal val SocialPopupHeight = 132.dp
private val SocialPopupShape = RoundedCornerShape(16.dp)
