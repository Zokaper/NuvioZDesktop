package com.nuvio.app.features.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.components.ContinueWatchingLandscapeCardMetrics
import com.nuvio.app.features.home.components.ContinueWatchingLayout
import com.nuvio.app.features.home.components.TitlePresentation
import com.nuvio.app.features.home.components.TitlePresentationCard
import com.nuvio.app.features.home.components.continueWatchingLandscapeCardMetrics
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_recently_watched
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

fun LazyListScope.homeSocialSections(
    watchingNow: List<WatchingNowItem>,
    activity: List<RecentActivityRun>,
    sectionPadding: Dp,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    if (watchingNow.isNotEmpty()) {
        item(key = "z-social-watching-now") {
            SocialHomeRow(
                title = stringResource(Res.string.social_watching_now),
                sectionPadding = sectionPadding,
                items = watchingNow.take(SocialHomeItemLimit),
                key = { "${it.profile.profileId}:${it.videoId}" },
            ) { item, layout, cardMetrics ->
                TitlePresentationCard(
                    item = TitlePresentation(
                        title = item.title,
                        poster = item.poster,
                        background = item.background,
                        episodeThumbnail = item.episodeThumbnail,
                        season = item.season,
                        episode = item.episode,
                        episodeTitle = item.episodeTitle,
                        progress = item.progressFraction,
                    ),
                    style = style,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    layout = layout,
                    cardMetrics = cardMetrics,
                    leading = {
                        SocialActivityIdentity(item.profile, if (item.state == SocialPlaybackState.playing) "Playing" else "Paused")
                    },
                    trailing = { Text("${item.roundedProgressPercent}%", style = MaterialTheme.typography.labelMedium) },
                    onClick = { onOpenContent(item.contentType, item.contentId, item.title) },
                )
            }
        }
    }
    if (activity.isNotEmpty()) {
        item(key = "z-social-recent") {
            SocialHomeRow(
                title = stringResource(Res.string.social_recently_watched),
                sectionPadding = sectionPadding,
                items = activity.take(SocialHomeItemLimit),
                key = RecentActivityRun::runId,
            ) { run, layout, cardMetrics ->
                TitlePresentationCard(
                    item = TitlePresentation(
                        title = run.title,
                        poster = run.poster,
                        background = run.background,
                        episodeThumbnail = run.episodeThumbnail,
                        season = run.season,
                        episode = run.episode,
                        episodeTitle = run.episodeTitle,
                    ),
                    style = style,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    layout = layout,
                    cardMetrics = cardMetrics,
                    leading = { SocialActivityIdentity(run.profile, "Recently watched") },
                    trailing = if (run.eventCount > 1) {
                        { Text("${run.eventCount} episodes", style = MaterialTheme.typography.labelMedium) }
                    } else {
                        null
                    },
                    onClick = { onOpenContent(run.contentType, run.contentId, run.title) },
                )
            }
        }
    }
}

@Composable
private fun <T> SocialHomeRow(
    title: String,
    sectionPadding: Dp,
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T, ContinueWatchingLayout, ContinueWatchingLandscapeCardMetrics) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val layout = rememberContinueWatchingLayout(maxWidth.value)
        val posterCardStyle = rememberPosterCardStyleUiState()
        val cardMetrics = remember(posterCardStyle.widthDp, posterCardStyle.cornerRadiusDp) {
            val basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(posterCardStyle.widthDp)
            continueWatchingLandscapeCardMetrics(
                basePosterWidthDp = basePosterWidthDp,
                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = sectionPadding),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // Outside any Surface, so LocalContentColor would fall back to black. The cards below
                // are Surfaces and set their own, which is why only this heading was invisible.
                color = MaterialTheme.colorScheme.onBackground,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = sectionPadding),
                horizontalArrangement = Arrangement.spacedBy(layout.itemGap),
            ) { items(items, key = key) { card(it, layout, cardMetrics) } }
        }
    }
}

@Composable
private fun SocialActivityIdentity(profile: SocialProfileSummary, status: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SocialAvatar(profile.displayName, profile.avatarUrl, profile.avatarColorHex, 20.dp)
        Text("${profile.displayName} · $status", style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
