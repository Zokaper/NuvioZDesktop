package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage

@Composable
fun SocialActivityChip(
    profile: SocialProfileSummary,
    title: String,
    poster: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    status: String,
    progress: Float? = null,
    trailing: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .then(if (compact) Modifier.width(310.dp) else Modifier.fillMaxWidth())
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `poster` is a 2:3 poster, and cropping it into a 76x48 letterbox mangled the art on
            // every card on the home rows. Shown at the shape it actually is.
            val artModifier = Modifier.width(42.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            if (!poster.isNullOrBlank()) {
                NuvioAsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = artModifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(artModifier.background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SocialAvatar(profile.displayName, profile.avatarUrl, profile.avatarColorHex, 20.dp)
                    Text(
                        "  ${profile.displayName} · $status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val episodeLabel = episode?.let { "S${season ?: 1} E$it${episodeTitle?.let { name -> " · $name" }.orEmpty()}" }
                (episodeLabel ?: trailing)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
            trailing?.takeIf { episode != null }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
