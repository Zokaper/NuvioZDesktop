package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/**
 * Presentation-only title card. Domain adornments are slots so social activity does not pretend
 * to be a ContinueWatchingItem and Continue Watching can retain its own watched/next-up behavior.
 */
@Composable
internal fun TitlePresentationCard(
    item: TitlePresentation,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val artwork = item.artwork(style, useEpisodeThumbnails)
    val episodeLine = item.episode?.let { episode ->
        "S${item.season ?: 1} E$episode" +
            item.episodeTitle?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(NuvioTokens.Radius.card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (style) {
            ContinueWatchingSectionStyle.Poster -> Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TitleArtwork(artwork, Modifier.width(74.dp).aspectRatio(2f / 3f))
                TitleCopy(item, episodeLine, leading, trailing, Modifier.weight(1f))
            }
            ContinueWatchingSectionStyle.Card -> Column {
                TitleArtwork(artwork, Modifier.fillMaxWidth().aspectRatio(16f / 7f))
                TitleCopy(item, episodeLine, leading, trailing, Modifier.padding(14.dp))
            }
            ContinueWatchingSectionStyle.Wide -> Row(Modifier.height(156.dp)) {
                TitleArtwork(artwork, Modifier.fillMaxHeight().width(220.dp))
                TitleCopy(item, episodeLine, leading, trailing, Modifier.weight(1f).padding(16.dp))
            }
        }
    }
}

@Composable
private fun TitleArtwork(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(NuvioTokens.Radius.compactCard)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrBlank()) {
            NuvioAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun TitleCopy(
    item: TitlePresentation,
    episodeLine: String?,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (leading != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        episodeLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        item.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                drawStopIndicator = {},
            )
        }
    }
}
