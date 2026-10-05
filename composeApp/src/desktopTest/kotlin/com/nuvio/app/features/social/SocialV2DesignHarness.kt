package com.nuvio.app.features.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Social V2, Stage 0: **candidate visual directions**, rendered so they can be looked at.
 *
 * No product code. Everything here is a prototype that lives in the test source set and reads the
 * real models ([WatchingNowItem], [FriendActivityGroup]) and the real [SocialAvatar] and
 * [SocialAvatarStack], so what is judged is the form and not a mock-up of the data.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialV2DesignHarness" --rerun
 * # PNGs land in composeApp/build/social-v2-design/
 * ```
 *
 * ⚠ **The artwork is synthetic.** The older harness could not load images, so every poster was a
 * grey box, which made every design look worse than it is and made a visual review meaningless.
 * [V2Art] draws a deterministic gradient-and-glow per title instead. It is not real artwork and it
 * will be *less* busy than real backdrops, so the text-over-image legibility seen here is the
 * optimistic case: confirm scrim strength on a device before settling it.
 *
 * The three directions are deliberately different ideas, not three tweaks of one:
 *
 * - **A, presence list** (Spotify friend activity / Discord): the person leads.
 * - **B, poster feed** (Letterboxd activity): artwork leads, the sentence names the person.
 * - **C, backdrop cards** (Apple TV / Netflix rows): a wide backdrop with the sentence on a scrim.
 */
class SocialV2DesignHarness {

    private val outputDir = File("build/social-v2-design")
    private val nowMs = 1_789_473_600_000L // 2026-09-15T12:00:00Z

    // --- fixtures -----------------------------------------------------------------------------

    private fun profile(name: String, handle: String, hex: String) = SocialProfileSummary(
        profileId = "p-$handle", handle = handle, displayName = name, avatarColorHex = hex, isFriend = true,
    )

    private val seraph = profile("Seraph", "seraph", "#8E6CF0")
    private val bigz = profile("Big Z", "bigz", "#E8833A")
    private val zokaper = profile("Zokaper", "zokaper", "#1E88E5")
    private val debug = profile("debug", "debug", "#2BB38A")
    private val ana = profile("Ana", "ana", "#E0527A")
    private val rayo = profile("Rayo", "rayo", "#C2A12B")
    private val ben = profile("Ben", "ben", "#4FB0C6")
    private val longName = profile("A Friend With A Very Long Display Name", "longname", "#7A8B99")

    private val activityRuns = listOf(
        RecentActivityRun(
            runId = "movie", profile = seraph, contentId = "tt-movie", contentType = "movie",
            videoId = "tt-movie", title = "A Perfectly Ordinary Movie",
            firstEventTime = "2026-09-15T09:00:00Z", lastEventTime = "2026-09-15T09:30:00Z",
        ),
        RecentActivityRun(
            runId = "dd-late", profile = seraph, contentId = "tt-dd", contentType = "series",
            videoId = "tt-dd:2:9", title = "Daredevil", season = 2, episode = 9, eventCount = 3,
            firstEventTime = "2026-09-15T08:00:00Z", lastEventTime = "2026-09-15T08:45:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-a", profile = bigz, contentId = "tt-def", contentType = "series",
            videoId = "tt-def:1:2", title = "The Defenders", season = 1, episode = 2,
            firstEventTime = "2026-09-14T20:00:00Z", lastEventTime = "2026-09-14T21:00:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-b", profile = zokaper, contentId = "tt-def", contentType = "series",
            videoId = "tt-def:1:3", title = "The Defenders", season = 1, episode = 3,
            firstEventTime = "2026-09-14T18:00:00Z", lastEventTime = "2026-09-14T18:40:00Z",
        ),
        RecentActivityRun(
            runId = "defenders-c", profile = debug, contentId = "tt-def", contentType = "series",
            videoId = "tt-def:1:1", title = "The Defenders", season = 1, episode = 1,
            firstEventTime = "2026-09-12T18:00:00Z", lastEventTime = "2026-09-12T18:40:00Z",
        ),
        RecentActivityRun(
            runId = "long", profile = longName, contentId = "tt-long", contentType = "series",
            videoId = "tt-long:3:12", title = "A Show With An Unreasonably Long Title That Has To Ellipsize",
            season = 3, episode = 12,
            firstEventTime = "2026-09-01T10:00:00Z", lastEventTime = "2026-09-01T12:00:00Z",
        ),
    )

    private fun live(
        who: SocialProfileSummary, id: String, title: String, season: Int? = null, episode: Int? = null,
        episodeTitle: String? = null, state: SocialPlaybackState = SocialPlaybackState.playing,
        policy: WatchJoinPolicy = WatchJoinPolicy.direct, position: Long = 900_000, duration: Long = 2_700_000,
        partyId: String? = null, hostId: String? = null,
    ) = WatchingNowItem(
        profile = who, contentId = id, contentType = if (season == null) "movie" else "series",
        videoId = if (season == null) id else "$id:$season:$episode", title = title, season = season,
        episode = episode, episodeTitle = episodeTitle, sessionId = "s-${who.handle}",
        positionMs = position, durationMs = duration, effectiveJoinPolicy = policy, state = state,
        heartbeatAt = "2026-09-15T12:00:00Z", partyId = partyId, partyHostProfileId = hostId,
        partyMemberCount = if (partyId != null) 3 else null,
    )

    private val watchingNow = orderWatchingNowForDisplay(
        listOf(
            live(zokaper, "tt5", "The Punisher", 1, 2, "Two Dead Men", policy = WatchJoinPolicy.approval),
            live(seraph, "tt1", "A Perfectly Ordinary Movie", position = 300_000, duration = 7_200_000),
            live(bigz, "tt5", "The Punisher", 1, 2, state = SocialPlaybackState.paused, policy = WatchJoinPolicy.approval),
            live(rayo, "tt7", "Burning", position = 1_079_915, duration = 8_890_326, policy = WatchJoinPolicy.approval,
                partyId = "party", hostId = "p-rayo"),
            live(ana, "tt7", "Burning", position = 1_079_915, duration = 8_890_326, policy = WatchJoinPolicy.approval,
                partyId = "party", hostId = "p-rayo"),
            live(ben, "tt7", "Burning", position = 1_079_915, duration = 8_890_326, policy = WatchJoinPolicy.approval,
                partyId = "party", hostId = "p-rayo"),
        ),
    )

    private val groups = groupFriendActivity(activityRuns)
    private val liveItems = watchingNow.map { it.toItem() }
    private val recentItems = groups.map { it.toItem(nowMs) }

    // --- scenes -------------------------------------------------------------------------------

    @Test
    fun renderVisualDirections() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val variants = listOf("A", "B", "C")
        for (v in variants) {
            renderScene("activity-$v-411x1500", 411, 1500, failures) { ActivityScene(v) }
        }
        renderScene("activity-compare-A-B-C", 1290, 1500, failures) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                variants.forEach { v -> Box(Modifier.width(414.dp).fillMaxHeight()) { ActivityScene(v) } }
            }
        }
        // The narrowest phone, where every candidate has to hold up.
        for (v in variants) renderScene("activity-$v-320x700", 320, 700, failures) { ActivityScene(v) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun renderHomeShelfOptions() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val kinds = listOf("one-shelf-cards", "two-shelves-cards", "one-shelf-posters")
        for (k in kinds) {
            renderScene("home-$k-411x820", 411, 820, failures) { HomeScene(k, tile = 216.dp, continueWidth = 264.dp) }
            renderScene("home-$k-1280x700", 1280, 700, failures) { HomeScene(k, tile = 272.dp, continueWidth = 340.dp) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Composable
    private fun ActivityScene(variant: String) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            V2Chrome()
            Spacer(Modifier.height(14.dp))
            V2SectionLabel("Watching now", liveItems.size.toString(), live = true)
            Column(verticalArrangement = Arrangement.spacedBy(if (variant == "C") 10.dp else 0.dp)) {
                liveItems.forEach { item ->
                    when (variant) {
                        "A" -> V2RowA(item)
                        "B" -> V2RowB(item)
                        else -> V2CardC(item, height = 164.dp, big = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            val buckets = bucketFriendActivity(groups, nowMs)
            buckets.forEach { (bucket, bucketGroups) ->
                Spacer(Modifier.height(10.dp))
                V2SectionLabel(bucket.label)
                Column(verticalArrangement = Arrangement.spacedBy(if (variant == "C") 10.dp else 0.dp)) {
                    bucketGroups.forEach { g ->
                        val item = g.toItem(nowMs)
                        when (variant) {
                            "A" -> V2RowA(item)
                            "B" -> V2RowB(item)
                            else -> V2CardC(item, height = 128.dp, big = false, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    /** Home, under a stand-in Continue Watching band, since the shelf has to read as secondary to it. */
    @Composable
    private fun HomeScene(kind: String, tile: Dp, continueWidth: Dp) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Continue Watching", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf("Severance", "Andor", "Slow Horses", "Shogun", "Fargo").forEach { t ->
                    V2Art(t, Modifier.width(continueWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)))
                }
            }
            when (kind) {
                "two-shelves-cards" -> {
                    V2ShelfTitle("Watching now", liveItems.size.toString(), live = true)
                    V2Shelf { liveItems.forEach { V2HomeTile(it, Modifier.width(tile)) } }
                    V2ShelfTitle("Friends' activity", null, seeAll = true)
                    V2Shelf { recentItems.forEach { V2HomeTile(it, Modifier.width(tile)) } }
                }
                "one-shelf-posters" -> {
                    V2ShelfTitle("Friends", null, seeAll = true)
                    V2Shelf { (liveItems + recentItems).forEach { V2PosterTile(it) } }
                }
                else -> {
                    V2ShelfTitle("Friends", null, seeAll = true)
                    V2Shelf { (liveItems + recentItems).forEach { V2HomeTile(it, Modifier.width(tile)) } }
                }
            }
        }
    }

    // --- candidate A: presence list ----------------------------------------------------------

    @Composable
    private fun V2RowA(item: V2Item) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V2Faces(item, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.who, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    val trailing = when {
                        item.live != null -> if (item.live.playing) "Playing" else "Paused"
                        else -> item.time?.let(::ago)
                    }
                    if (trailing != null) {
                        Text(
                            "  ·  $trailing", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                            color = if (item.live?.playing == true) V2LiveColor else muted,
                        )
                    }
                }
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.meta.isNotBlank()) {
                    Text(item.meta, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val join = item.live?.join
            if (join != null) {
                V2Pill(join, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
            } else {
                V2Art(item.title, Modifier.width(38.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(7.dp)))
            }
        }
    }

    // --- candidate B: poster feed ------------------------------------------------------------

    @Composable
    private fun V2RowB(item: V2Item) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))) {
                    V2Art(item.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    item.live?.let { live ->
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.4f)))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(live.progress).height(3.dp).background(V2LiveColor))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        V2Faces(item, 20.dp)
                        Text(
                            item.sentence, style = MaterialTheme.typography.labelLarge, color = muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        item.meta.takeIf { it.isNotBlank() },
                        item.time?.let(::ago),
                        item.live?.let { if (it.playing) "Playing now" else "Paused" },
                    ).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.live?.join?.let { join ->
                        Spacer(Modifier.height(4.dp))
                        V2Pill(join, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)))
        }
    }

    // --- candidate C: backdrop cards ---------------------------------------------------------

    @Composable
    private fun V2CardC(item: V2Item, height: Dp, big: Boolean, modifier: Modifier = Modifier) {
        val live = item.live
        Box(modifier.height(height).clip(RoundedCornerShape(20.dp))) {
            V2Art(item.title, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.38f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                ),
            )
            val chip = when {
                live == null -> item.time?.let(::ago)
                live.party -> "WATCH PARTY"
                live.playing -> "LIVE"
                else -> "PAUSED"
            }
            live?.join?.let { join ->
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { V2Pill(join, Color.White, Color.Black) }
            }
            if (chip != null) {
                Row(
                    Modifier.align(if (live == null) Alignment.TopEnd else Alignment.TopStart).padding(12.dp)
                        .clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (live != null) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (live.playing) V2LiveColor else Color(0xFFE6B341)))
                    }
                    Text(chip, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = if (live != null) 14.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                V2Faces(item, if (big) 38.dp else 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        item.sentence, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.title, color = Color.White, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.meta.isNotBlank()) {
                        Text(
                            item.meta, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (live != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(live.progress).height(3.dp).background(V2LiveColor))
            }
        }
    }

    // --- Home tile: the backdrop card, quieter -----------------------------------------------

    /**
     * Smaller than a Continue Watching tile and with less on it: the action moves to the top corner so
     * the name and title never fight a button for the bottom line.
     */
    @Composable
    private fun V2HomeTile(item: V2Item, modifier: Modifier = Modifier) {
        val live = item.live
        Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
            V2Art(item.title, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to Color.Black.copy(alpha = 0.2f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (live.playing) V2LiveColor else Color(0xFFE6B341)))
                        Text(if (live.party) "PARTY" else if (live.playing) "LIVE" else "PAUSED", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                val join = live?.join
                if (join != null) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White).padding(horizontal = 11.dp, vertical = 4.dp)) {
                        Text(join, color = Color.Black, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                } else if (live == null && item.time != null) {
                    Text(ago(item.time), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                V2Faces(item, 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(item.who, item.meta).filter(String::isNotBlank).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (live != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(live.progress).height(3.dp).background(V2LiveColor))
            }
        }
    }

    // --- Home option: portrait posters with an avatar badge ----------------------------------

    @Composable
    private fun V2PosterTile(item: V2Item) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.width(124.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                V2Art(item.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                item.live?.let { live ->
                    Row(
                        Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (live.playing) V2LiveColor else Color(0xFFE6B341)))
                        Text(if (live.party) "PARTY" else if (live.playing) "LIVE" else "PAUSED", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { V2Faces(item, 28.dp) }
            }
            Text(item.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.who, item.time?.let(::ago), item.live?.join).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // --- shared pieces ------------------------------------------------------------------------

    @Composable
    private fun V2Chrome() {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Social", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Inbox", style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.size(18.dp).clip(CircleShape).background(V2LiveColor), contentAlignment = Alignment.Center) {
                        Text("2", color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(3.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Activity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Friends", style = MaterialTheme.typography.labelLarge, color = muted)
                }
            }
        }
    }

    @Composable
    private fun V2SectionLabel(text: String, count: String? = null, live: Boolean = false) {
        Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(V2LiveColor))
            Text(
                text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp,
            )
            if (count != null) Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun V2ShelfTitle(text: String, count: String?, live: Boolean = false, seeAll: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(V2LiveColor))
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (count != null) Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (seeAll) Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun V2Shelf(content: @Composable () -> Unit) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }

    /** One avatar, a live ring when [V2Item.live] is playing, or the real overlapping stack for groups. */
    @Composable
    private fun V2Faces(item: V2Item, size: Dp) {
        if (item.friends.size > 1) {
            SocialAvatarStack(item.friends.take(3), (item.friends.size - 3).coerceAtLeast(0), size * 0.78f)
        } else {
            val ring = item.live?.playing == true
            Box(Modifier.size(size + if (ring) 7.dp else 0.dp), contentAlignment = Alignment.Center) {
                if (ring) Box(Modifier.fillMaxSize().border(2.dp, V2LiveColor, CircleShape))
                val p = item.friends.first()
                SocialAvatar(p.displayName, p.avatarUrl, p.avatarColorHex, size)
            }
        }
    }

    @Composable
    private fun V2Pill(text: String, background: Color, foreground: Color) {
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(background).padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text(text, color = foreground, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }

    /**
     * Stand-in artwork: a deterministic gradient with a glow and a vignette, keyed on the title so the
     * same title looks the same in every candidate. **Not real artwork.**
     */
    @Composable
    private fun V2Art(seed: String, modifier: Modifier = Modifier) {
        val hue = ((seed.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
        val top = Color.hsv(hue, 0.55f, 0.62f)
        val bottom = Color.hsv((hue + 38f) % 360f, 0.7f, 0.2f)
        val glow = Color.hsv((hue + 170f) % 360f, 0.45f, 0.95f)
        val h = seed.hashCode()
        val gx = 0.15f + ((h ushr 3) and 0xFF) / 255f * 0.7f
        val gy = 0.1f + ((h ushr 11) and 0xFF) / 255f * 0.5f
        val gr = 0.35f + ((h ushr 19) and 0x7F) / 127f * 0.4f
        Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(top, bottom), start = Offset(0f, 0f), end = Offset(size.width, size.height)))
            drawCircle(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width * gx, size.height * gy),
                    radius = size.maxDimension * gr,
                ),
                radius = size.maxDimension * gr,
                center = Offset(size.width * gx, size.height * gy),
            )
            drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
        }
    }

    // --- model adapters -----------------------------------------------------------------------

    private data class V2LiveInfo(val playing: Boolean, val party: Boolean, val progress: Float, val join: String?)

    private data class V2Item(
        val friends: List<SocialProfileSummary>,
        val who: String,
        val title: String,
        val meta: String,
        val time: String?,
        val live: V2LiveInfo?,
    ) {
        /** "Seraph is watching", "Rayo, Ana & Ben are watching", "Big Z + 2 watched". */
        val sentence: String
            get() = when {
                live == null -> "$who watched"
                friends.size > 1 -> "$who are watching"
                else -> "$who is watching"
            }
    }

    private fun WatchingNowItem.toItem(): V2Item {
        val everyone = listOf(profile) + partyCompanions
        val episode = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            contentType == "movie" -> "Movie"
            else -> ""
        }
        return V2Item(
            friends = everyone,
            who = joinNames(everyone),
            title = title,
            meta = listOfNotNull(episode.ifBlank { null }, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
            time = null,
            live = V2LiveInfo(
                playing = state == SocialPlaybackState.playing,
                party = partyCompanions.isNotEmpty(),
                progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                join = when (effectiveJoinPolicy) {
                    WatchJoinPolicy.direct -> "Join"
                    WatchJoinPolicy.approval -> "Ask to join"
                    WatchJoinPolicy.disabled -> null
                },
            ),
        )
    }

    private fun FriendActivityGroup.toItem(now: Long) = V2Item(
        friends = friends,
        who = joinNames(friends),
        title = title,
        meta = contextLabel(),
        time = relativeTimeLabel(lastEventMs, now).ifBlank { null },
        live = null,
    )

    private fun joinNames(people: List<SocialProfileSummary>): String {
        val names = people.map { it.displayName.ifBlank { it.handle } }
        return when {
            names.size <= 1 -> names.firstOrNull().orEmpty()
            names.size == 2 -> "${names[0]} & ${names[1]}"
            names.size == 3 -> "${names[0]}, ${names[1]} & ${names[2]}"
            else -> "${names[0]} + ${names.size - 1}"
        }
    }

    private fun ago(time: String) = if (time == "Just now") time else "$time ago"

    // --- rendering ----------------------------------------------------------------------------

    private fun renderScene(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        runCatching {
            val scene = ImageComposeScene(width = widthDp * 2, height = heightDp * 2, density = Density(2f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density, 1f),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
            try {
                scene.render(0L)
                val image = scene.render(16_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }

    private companion object {
        val V2LiveColor = Color(0xFF6FD08C)
    }
}
