package com.nuvio.app.features.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import androidx.compose.foundation.lazy.LazyListScope

/**
 * Social's own presentation, deliberately **not** Continue Watching's.
 *
 * The first version reused `TitlePresentationCard` outright, which made a friend's history look
 * exactly as important as the user's own half-finished episode: the same 16:9 weight, the same
 * shelf prominence, a poster wall down the Social tab and a large slice of Home given to other
 * people's viewing. That is not a sizing problem, it is a hierarchy one.
 *
 *   **Continue Watching** - my active playback. Primary, prominent.
 *   **Friends Recently Watched** - ambient social history. Secondary, glanceable.
 *   **Watching Now** - live and actionable. Between the two: more present than history because
 *   there is something to *do*, still Social rather than another catalogue.
 *
 * ⚠ **Only low-level primitives are shared.** [SocialCardArtwork], [SocialIdentityLine] and
 * `SocialAvatar` are shared; [SocialActivityCard] and [SocialWatchingNowCard] are two distinct
 * components above them. One universal card with nullable parameters for every difference is how
 * the `TitlePresentationCard` over-reuse happened in the first place - these two surfaces have
 * different jobs and are allowed to look like it.
 */

/** Compact by construction: a card this tall cannot compete with a Continue Watching poster. */
internal val SocialActivityCardWidth = 260.dp
internal val SocialWatchingNowCardWidth = 300.dp

/** The friends roster beside the feed on a wide window, so the feed's own width excludes it. */
internal val SocialFriendsRailWidth = 360.dp

/**
 * How many activity cards fit across, at this width.
 *
 * Capped at three: past that the cards start reading as a catalogue grid again, which is the thing
 * this redesign exists to undo. Never below one, so a narrow window collapses to a single column
 * rather than to none.
 */
internal fun socialGridColumns(available: Dp): Int =
    ((available - 24.dp) / (SocialActivityCardWidth + 12.dp)).toInt().coerceIn(1, 3)

/** Small 16:9 still. Episode thumbnail, then background, then poster - never a cropped 2:3. */
@Composable
internal fun SocialCardArtwork(
    poster: String?,
    background: String?,
    episodeThumbnail: String?,
    width: Dp,
) {
    val model = episodeThumbnail?.takeIf { it.isNotBlank() }
        ?: background?.takeIf { it.isNotBlank() }
        ?: poster?.takeIf { it.isNotBlank() }
    val shape = RoundedCornerShape(NuvioTokens.Radius.compactCard)
    Box(
        Modifier.width(width)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // ⚠ A 2:3 poster in a 16:9 box is a letterbox crop that mangles the art, which is what the
        // shared card did. `Crop` is still right - the box is small and centre-weighted art
        // survives it - but the fallback order above is what keeps a real still in front of it.
        var artworkFailed by remember(model) { mutableStateOf(false) }
        if (model != null && !artworkFailed) {
            NuvioAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { artworkFailed = true },
            )
        }
    }
}

/**
 * Who this is, outside the artwork.
 *
 * Identity used to sit over the art competing with the title's own metadata. Out here it reads as
 * a byline, which is what it is: the card is about a *friend*, and the title is what they watched.
 */
@Composable
internal fun SocialIdentityLine(
    profile: SocialProfileSummary,
    avatarSize: Dp = 18.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SocialAvatar(profile.displayName, profile.avatarUrl, profile.avatarColorHex, avatarSize)
        Text(
            profile.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * One compact metadata line: `S2 E9 · 5 episodes`.
 *
 * The episode count is part of the sentence rather than a badge over the artwork. The badge version
 * clipped and bled, and it was shouting a number nobody needed at that size.
 */
internal fun socialMetadataLine(season: Int?, episode: Int?, episodeTitle: String?, eventCount: Int): String =
    buildList {
        val numbering = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> null
        }
        numbering?.let(::add)
        if (numbering == null) episodeTitle?.takeIf { it.isNotBlank() }?.let(::add)
        if (eventCount > 1) add(if (eventCount == 2) "2 episodes" else "$eventCount episodes")
    }.joinToString(" · ")

/**
 * Ambient social history: what a friend has been up to.
 *
 * Reads as an activity entry, not as another thing in the catalogue - small still on the left,
 * friend and title stacked beside it, one metadata line under them. Substantially shorter than a
 * Continue Watching card by construction, because the artwork is what sets the height.
 */
@Composable
internal fun SocialActivityCard(
    run: RecentActivityRun,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SocialCardSurface(onClick = onOpen, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialCardArtwork(
                poster = run.poster,
                background = run.background,
                episodeThumbnail = run.episodeThumbnail,
                width = 92.dp,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SocialIdentityLine(run.profile)
                Text(
                    run.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = socialMetadataLine(run.season, run.episode, run.episodeTitle, run.eventCount)
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A friend, mid-episode, with something to do about it.
 *
 * More present than [SocialActivityCard] - live state, progress, and the party action - and still
 * Social rather than a Continue Watching clone. The action follows the **effective** join policy
 * the model already carries; no policy is invented here.
 */
@Composable
internal fun SocialWatchingNowCard(
    item: WatchingNowItem,
    watchPartyEnabled: Boolean,
    onOpen: () -> Unit,
    onStartParty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = item.state == SocialPlaybackState.playing
    SocialCardSurface(onClick = onOpen, modifier = modifier, accent = true) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialCardArtwork(
                poster = item.poster,
                background = item.background,
                episodeThumbnail = item.episodeThumbnail,
                width = 104.dp,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                SocialIdentityLine(item.profile, trailing = { SocialLiveBadge(playing) })
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = socialMetadataLine(item.season, item.episode, item.episodeTitle, eventCount = 1)
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
                // ⚠ Off means no action at all, not a disabled button. A control that is visibly
                // refused invites the user to keep pressing it; a friend who is not accepting
                // company simply has nothing to press.
                if (watchPartyEnabled && item.effectiveJoinPolicy != WatchJoinPolicy.disabled) {
                    TextButton(
                        onClick = onStartParty,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(Icons.Rounded.Groups, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when (item.effectiveJoinPolicy) {
                                WatchJoinPolicy.direct -> "Join"
                                WatchJoinPolicy.approval -> "Ask to join"
                                WatchJoinPolicy.disabled -> ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/** The one container both cards sit in, so they share depth and corner without sharing a layout. */
@Composable
private fun SocialCardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (accent) 0.85f else 0.55f),
        border = if (accent) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        } else {
            null
        },
    ) {
        Box(Modifier.padding(8.dp)) { content() }
    }
}

/**
 * Lays compact Social cards out across the width instead of one per row.
 *
 * A `LazyVerticalGrid` cannot be nested in the feed's `LazyColumn`, and the feed has to stay one
 * scrollable list - so the rows are chunked here and each chunk is one lazy item. That keeps the
 * key per *card* rather than per row, which is what preserves item identity when the column count
 * changes under a resize.
 *
 * Cards are weighted rather than fixed-width so a row divides the space it is actually given, and
 * a short final row is padded with empty weights so its cards do not stretch to fill it.
 */
internal fun <T> LazyListScope.socialGridItems(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    card: @Composable (T, Modifier) -> Unit,
) {
    val rows = items.chunked(columns.coerceAtLeast(1))
    rows.forEach { row ->
        item(key = key(row.first())) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { entry ->
                    card(entry, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
