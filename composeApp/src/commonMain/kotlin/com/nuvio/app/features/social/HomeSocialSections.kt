package com.nuvio.app.features.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_recently_watched
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

/**
 * What friends are up to, on Home, **underneath** the user's own Continue Watching.
 *
 * ⚠ **This used to be `TitlePresentationCard` with Continue Watching's own metrics**, so a friend's
 * history was drawn at exactly the size and depth of the user's half-finished episode and took a
 * comparable slice of Home. Inheriting `rememberContinueWatchingLayout` and
 * `continueWatchingLandscapeCardMetrics` is what made the two shelves equal - the visual weight was
 * literally the same numbers - and it is why this is not a matter of shrinking anything by 10%.
 *
 * The shelf is now compact fixed-width cards on a lower row, visibly subordinate to the shelf above
 * it. The corner radius still comes from the user's poster-card preference, because that is a
 * *style* choice that should carry across the app; the dimensions do not, because those are what
 * encode importance.
 */
fun LazyListScope.homeSocialSections(
    watchingNow: List<WatchingNowItem>,
    activity: List<RecentActivityRun>,
    sectionPadding: Dp,
    watchPartyEnabled: Boolean = false,
    onStartParty: (WatchingNowItem) -> Unit = {},
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    if (watchingNow.isNotEmpty()) {
        item(key = "z-social-watching-now") {
            SocialHomeRow(
                title = stringResource(Res.string.social_watching_now),
                sectionPadding = sectionPadding,
                items = watchingNow.take(SocialHomeItemLimit),
                key = { "${it.profile.profileId}:${it.videoId}" },
            ) { item ->
                SocialWatchingNowCard(
                    item = item,
                    watchPartyEnabled = watchPartyEnabled,
                    onOpen = { onOpenContent(item.contentType, item.contentId, item.title) },
                    onStartParty = { onStartParty(item) },
                    modifier = Modifier.width(SocialWatchingNowCardWidth),
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
            ) { run ->
                SocialActivityCard(
                    run = run,
                    onOpen = { onOpenContent(run.contentType, run.contentId, run.title) },
                    modifier = Modifier.width(SocialActivityCardWidth),
                )
            }
        }
    }
}

/**
 * One horizontal shelf of Social cards.
 *
 * The heading is `titleMedium` rather than the `titleLarge` the catalogue shelves use: this row is
 * secondary to what sits above it, and the heading is the first thing that says so.
 */
@Composable
private fun <T> SocialHomeRow(
    title: String,
    sectionPadding: Dp,
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = sectionPadding),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Outside any Surface, so LocalContentColor would fall back to black. The cards below
            // are Surfaces and set their own, which is why only this heading was invisible.
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) { items(items, key = key) { card(it) } }
    }
}
