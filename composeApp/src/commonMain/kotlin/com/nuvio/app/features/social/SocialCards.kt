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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize

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

/**
 * Home's shelf metrics. Compact by construction: a card this tall cannot compete with a Continue
 * Watching poster.
 *
 * ⚠ These went the other way once. Recently Watched at 260x76 with 92dp of artwork left about
 * 140dp for the title, which is where `The D…` came from - the redesign was right to demote the
 * shelf and wrong about how far. The hierarchy is held by the *height* and by the artwork being a
 * small 16:9 still rather than a poster; a card can be legible without being a Continue Watching
 * card. The Social feed's own cards are wider again - see [SocialFeedMetrics].
 */
internal val SocialActivityCardWidth = 300.dp
internal val SocialWatchingNowCardWidth = 344.dp

/**
 * Minimum heights, so a row of cards is a row rather than a ragged edge.
 *
 * Cards used to size to their own content, and the content genuinely differs - a Watching Now card
 * with a join action is taller than one whose friend is not accepting company, and an activity card
 * for a movie has no episode line. Three of those side by side landed the artwork at three different
 * heights. A minimum rather than a fixed height: it evens the common case out and still grows rather
 * than clipping if a translation runs long.
 *
 * These are also the numbers that make "substantially lower than Continue Watching" a fact rather
 * than an intention.
 */
internal val SocialActivityCardHeight = 88.dp
internal val SocialWatchingNowCardHeight = 132.dp

/** Artwork widths. Home's shelf is compact; the Social feed passes its own, larger, values. */
internal val SocialActivityArtworkWidth = 104.dp
internal val SocialWatchingNowArtworkWidth = 116.dp
internal val SocialActivityArtworkWidthWide = 124.dp
internal val SocialWatchingNowArtworkWidthWide = 160.dp

/** The friends roster beside the feed on a wide window, so the feed's own width excludes it. */
internal val SocialFriendsRailWidth = 360.dp

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
        // ⚠ **One weight, not two.** This asked for `weight(1f, fill = false)` and then put a
        // `Spacer(Modifier.weight(1f))` in front of the trailing badge - so the two split the slack
        // and the name was left with almost none, ellipsizing to "Se…" and "Bi…" on Home's cards
        // where every friend looked the same. The name takes the remaining width and the badge sits
        // after it; the render harness is what caught this.
        Text(
            profile.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
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
    artworkWidth: Dp = SocialActivityArtworkWidth,
) {
    SocialCardSurface(
        onClick = onOpen,
        modifier = modifier.heightIn(min = SocialActivityCardHeight),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // Top, not centre: with cards evened out to a common height, centring floats the
            // artwork to a different place on every card in the row.
            verticalAlignment = Alignment.Top,
        ) {
            SocialCardArtwork(
                poster = run.poster,
                background = run.background,
                episodeThumbnail = run.episodeThumbnail,
                width = artworkWidth,
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
    artworkWidth: Dp = SocialWatchingNowArtworkWidth,
) {
    val playing = item.state == SocialPlaybackState.playing
    SocialCardSurface(
        onClick = onOpen,
        modifier = modifier.heightIn(min = SocialWatchingNowCardHeight),
        accent = true,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SocialCardArtwork(
                poster = item.poster,
                background = item.background,
                episodeThumbnail = item.episodeThumbnail,
                width = artworkWidth,
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
                            // ⚠ A guard, not a layout. When the card was measured at 180dp this
                            // label came out one character per line - a vertical `A s k t o j o i n`
                            // beside a card taller than the window. The width bug is fixed in
                            // [socialFeedMetrics]; this makes the *next* constraint mistake
                            // ellipsize where it can be seen, rather than shred the card.
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
        // ⚠ **Name it.** Material's `Surface` defaults its content colour to
        // `contentColorFor(color)`, and `surface.copy(alpha = …)` matches no colour-scheme role, so
        // that lookup returns unspecified and falls through to `LocalContentColor.current`. The
        // Social screen happens to provide `onBackground` at its root, so these cards read white
        // there; Home provides nothing, `LocalContentColor` is its black default, and every card
        // title on Home rendered black on a dark card - invisible, while the identity and metadata
        // lines beside it were fine because they name their own colour. The card owns its content
        // colour rather than inheriting whichever screen hosts it.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (accent) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        } else {
            null
        },
    ) {
        Box(Modifier.fillMaxHeight().padding(8.dp)) { content() }
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
            // `IntrinsicSize.Min` measures the row to its tallest card and `fillMaxHeight` brings
            // the rest up to it, so a card with a join action and one without still make a row.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                // The same gap [socialFeedMetrics] divides the width by; they have to agree.
                horizontalArrangement = Arrangement.spacedBy(SocialGridGap),
            ) {
                row.forEach { entry ->
                    card(entry, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
