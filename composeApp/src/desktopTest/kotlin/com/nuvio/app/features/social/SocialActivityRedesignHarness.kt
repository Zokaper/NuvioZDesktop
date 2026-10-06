package com.nuvio.app.features.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * Social activity redesign (2026-10-06): **two candidate directions for Recently watched**, rendered
 * after physical QA of the shipped V2 layout found it crowded.
 *
 * What QA found on a real account, and what these renders answer:
 * - Fourteen identical image-plus-three-lines tiles in a two-column grid, "faisal watched" eight
 *   times. Every viewing had equal weight and nothing led the eye.
 * - The 16:9 stills were portrait posters cropped to a strip, because no activity event carries a
 *   backdrop (fixed separately by `SocialTitleArt`). These renders draw **real backdrops** when
 *   `build/social-design-art/<content id>.(jpg|webp)` exists, so judge them with real art: synthetic
 *   gradients made the earlier rounds look calmer than the app.
 *
 * Directions:
 * - **A, people shelves** (Netflix rows, where each row is a person): one header per friend, then a
 *   horizontal shelf of backdrop tiles with the title under the art. A title two or more friends
 *   watched is lifted into a "Together" spotlight above the shelves.
 * - **B, art grid**: Recently watched as a day-bucketed grid of backdrop tiles with the title and
 *   the friend on the art, grouped by title as today.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialActivityRedesignHarness" --rerun
 * # PNGs land in composeApp/build/social-redesign/
 * ```
 *
 * No product code. Prototypes read the real models and grouping ([groupFriendActivity],
 * [bucketFriendActivity]) and the real avatars.
 */
class SocialActivityRedesignHarness {

    private val outputDir = File("build/social-redesign")
    private val artDir = File("build/social-design-art")
    private val nowMs = parseSocialTimestampMs("2026-10-06T12:00:00Z")!!

    // --- fixtures: the shape of the QA account, with made-up people -------------------------

    private fun profile(name: String, handle: String, hex: String) = SocialProfileSummary(
        profileId = "p-$handle", handle = handle, displayName = name, avatarColorHex = hex, isFriend = true,
    )

    private val me = profile("big z", "zokaper", "#1E88E5")
    private val faye = profile("faye", "faye", "#E0527A")
    private val jules = profile("jules renner", "jules", "#4FB0C6")
    private val seraph = profile("Seraph", "seraph", "#8E6CF0")
    private val mika = profile("mika", "mika", "#C2A12B")
    private val ana = profile("Ana", "ana", "#2BB38A")
    private val ben = profile("ben", "ben", "#E8833A")
    private val friends = listOf(faye, jules, seraph, mika, ana, ben)

    private var runCounter = 0
    private fun run(
        who: SocialProfileSummary, id: String, title: String, at: String,
        season: Int? = null, episode: Int? = null, events: Int = 1,
    ) = RecentActivityRun(
        runId = "r${runCounter++}", profile = who, contentId = id, contentType = if (season == null) "movie" else "series",
        videoId = if (season == null) id else "$id:$season:$episode", title = title, season = season, episode = episode,
        eventCount = events, firstEventTime = at, lastEventTime = at,
    )

    /** Server order: newest first. */
    private val runs = listOf(
        run(ana, "tt0329938", "Transformers: Armada", "2026-10-06T07:00:00Z", 1, 5, events = 5),
        run(faye, "tt0914798", "The Boy in the Striped Pajamas", "2026-10-05T21:00:00Z"),
        run(jules, "tt0914798", "The Boy in the Striped Pajamas", "2026-10-05T20:00:00Z"),
        run(ben, "tt37287335", "Obsession", "2026-10-05T18:00:00Z"),
        run(mika, "tt0149460", "Futurama", "2026-10-03T22:00:00Z", 7, 1, events = 77),
        run(faye, "tt0068361", "The Discreet Charm of the Bourgeoisie", "2026-10-02T21:00:00Z"),
        run(faye, "tt0071406", "Pastoral: To Die in the Country", "2026-10-02T18:00:00Z"),
        run(jules, "tt2278388", "The Grand Budapest Hotel", "2026-09-30T20:00:00Z"),
        run(faye, "tt0071141", "Ali: Fear Eats the Soul", "2026-09-29T20:00:00Z"),
        run(faye, "tt0073198", "Jeanne Dielman, 23, quai du Commerce, 1080 Bruxelles", "2026-09-29T15:00:00Z"),
        run(jules, "tt0121955", "South Park", "2026-09-29T12:00:00Z", 2, 6, events = 225),
        run(faye, "tt1191111", "Enter the Void", "2026-09-28T23:00:00Z"),
        run(seraph, "tt5675620", "The Punisher", "2026-09-28T21:00:00Z", 1, 5, events = 3),
        run(faye, "tt0423176", "The World", "2026-09-27T20:00:00Z"),
        run(faye, "tt0430651", "Survive Style 5+", "2026-09-24T20:00:00Z"),
        run(ben, "tt7282468", "Burning", "2026-09-21T20:00:00Z"),
    )

    private val groups = groupFriendActivity(runs)

    private val live = WatchingNowItem(
        profile = seraph, contentId = "tt5675620", contentType = "series", videoId = "tt5675620:1:6",
        title = "The Punisher", season = 1, episode = 6, episodeTitle = "The Judas Goat", sessionId = "s-seraph",
        positionMs = 1_300_000, durationMs = 3_000_000, effectiveJoinPolicy = WatchJoinPolicy.approval,
        state = SocialPlaybackState.playing, heartbeatAt = "2026-10-06T12:00:00Z",
    )

    /** One person's shelf: their own titles, newest first. */
    private data class PersonShelf(val person: SocialProfileSummary, val titles: List<FriendActivityGroup>)

    private val shelves: List<PersonShelf> = friends
        .map { person -> PersonShelf(person, groupFriendActivity(runs.filter { it.profile.profileId == person.profileId })) }
        .filter { it.titles.isNotEmpty() }
        .sortedByDescending { it.titles.first().lastEventMs ?: 0L }

    private val together = groups.filter { it.friends.size >= 2 }

    // --- tests --------------------------------------------------------------------------------

    @Test
    fun renderDirections() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((direction, label) in listOf("A" to "shelves", "B" to "grid")) {
            listOf(411 to 914, 360 to 780, 320 to 640).forEach { (w, h) ->
                renderScene("$direction-$label-phone-${w}x$h", w, h, failures) { PhoneScene(direction, withLive = true) }
            }
            renderScene("$direction-$label-phone-scroll-411x2400", 411, 2400, failures) { PhoneScene(direction, withLive = true) }
            listOf(1280 to 820, 1600 to 1000, 1920 to 1080).forEach { (w, h) ->
                renderScene("$direction-$label-desktop-${w}x$h", w, h, failures) { DesktopScene(direction, withLive = false) }
            }
            renderScene("$direction-$label-desktop-scroll-1600x1800", 1600, 1800, failures) { DesktopScene(direction, withLive = true) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- scenes -------------------------------------------------------------------------------

    @Composable
    private fun PhoneScene(direction: String, withLive: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) { PhoneChrome() }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxSize().clipToBounds().verticalScroll(rememberScrollState())) {
                    Feed(direction, withLive, width, horizontal = if (direction == "F") 20.dp else 16.dp, phone = true)
                }
            }
        }
    }

    @Composable
    private fun DesktopScene(direction: String, withLive: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val rail = 340.dp
            val timelineDirection = direction.startsWith("E")
            val pageWidth = if (timelineDirection) minOf(maxWidth, 1280.dp) else maxWidth
            val feedWidth = pageWidth - rail - 28.dp * 3
            Column(Modifier.width(pageWidth).fillMaxHeight()) {
                NavPill()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Identity(52.dp, big = true, modifier = Modifier.weight(1f))
                    Pill("Invite code")
                    Pill("Inbox")
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().clipToBounds().verticalScroll(rememberScrollState())) {
                        Feed(direction, withLive, feedWidth, horizontal = 0.dp, phone = false)
                    }
                    Rail(Modifier.width(rail).fillMaxHeight())
                }
            }
        }
    }

    @Composable
    private fun Feed(direction: String, withLive: Boolean, width: Dp, horizontal: Dp, phone: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (withLive) {
                Column(Modifier.padding(horizontal = horizontal)) {
                    SectionLabel("Watching now", live = true)
                    val liveModifier = when {
                        direction == "F" && phone -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        // Two grid cells wide, 16:9: the backdrop's own shape, never a cropped strip.
                        direction == "F" -> pileGrid(width, phone).let { (_, cell) -> Modifier.width(cell * 2 + PileGap).aspectRatio(16f / 9f) }
                        else -> Modifier.fillMaxWidth(if (phone) 1f else 0.5f).height(if (phone) 170.dp else 190.dp)
                    }
                    LiveCard(live, liveModifier)
                }
            } else {
                Column(Modifier.padding(horizontal = horizontal)) {
                    SectionLabel("Watching now")
                    Text(
                        "Nobody's watching right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            when (direction) {
                "A" -> ShelvesFeed(width, horizontal, phone)
                "C" -> DigestFeed(width, horizontal, phone)
                "D" -> LogFeed(width, horizontal, phone)
                "E" -> TimelineFeed(horizontal, phone, coarse = false)
                "E2" -> TimelineFeed(horizontal, phone, coarse = true)
                "F" -> PilesFeed(width, horizontal, phone)
                else -> GridFeed(width, horizontal, phone)
            }
        }
    }

    // --- A: people shelves ---------------------------------------------------------------------

    @Composable
    private fun ShelvesFeed(width: Dp, horizontal: Dp, phone: Boolean) {
        if (!phone) {
            PackedShelves(width)
            return
        }
        // A phone shows a little over two tiles: the cut tells you the shelf scrolls.
        val tile = (width - horizontal) / 2.25f
        together.firstOrNull()?.let { group ->
            Column(Modifier.padding(horizontal = horizontal)) {
                SectionLabel("Together")
                TogetherCard(group, Modifier.fillMaxWidth().aspectRatio(2.2f))
            }
            Spacer(Modifier.height(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            shelves.forEach { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PersonHeader(shelf, Modifier.padding(horizontal = horizontal))
                    Row(
                        Modifier.fillMaxWidth().clipToBounds().padding(start = horizontal),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        shelf.titles.take(8).forEach { group -> ShelfTile(group, Modifier.width(tile)) }
                    }
                }
            }
        }
    }

    /**
     * Desktop: a shelf is only as wide as its titles, and short shelves share a line, so one friend
     * with one film does not leave a whole row empty. A shelf longer than the line gets the line to
     * itself and scrolls. The Together spotlight is a block two tiles wide in the same flow.
     */
    @Composable
    private fun PackedShelves(width: Dp) {
        val gap = 12.dp
        val perLine = ((width + gap) / (250.dp + gap)).toInt().coerceIn(3, 6)
        val tile = (width - gap * (perLine - 1)) / perLine
        data class Block(val slots: Int, val content: @Composable () -> Unit)
        val blocks = buildList {
            together.firstOrNull()?.let { group ->
                add(Block(2) {
                    Column(Modifier.width(tile * 2 + gap)) {
                        SectionHeader("Together", null)
                        Spacer(Modifier.height(10.dp))
                        TogetherCard(group, Modifier.fillMaxWidth().height(tile * 9f / 16f))
                    }
                })
            }
            shelves.forEach { shelf ->
                val slots = shelf.titles.size.coerceAtMost(perLine)
                add(Block(slots) {
                    Column(Modifier.width(if (shelf.titles.size > perLine) width else tile * slots + gap * (slots - 1))) {
                        PersonHeader(shelf, Modifier, compact = slots < 3)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth().clipToBounds(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            shelf.titles.take(perLine + 1).forEach { group -> ShelfTile(group, Modifier.width(tile)) }
                        }
                    }
                })
            }
        }
        // Greedy packing in order; the extra gap between two shelves on a line is paid in slots.
        val lines = mutableListOf<MutableList<Block>>()
        var used = 0
        blocks.forEach { block ->
            val need = block.slots
            if (lines.isEmpty() || used + need > perLine) {
                lines += mutableListOf(block); used = need
            } else {
                lines.last() += block; used += need
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { line.forEach { it.content() } }
            }
        }
    }

    @Composable
    private fun SectionHeader(text: String, count: String?) {
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (count != null) Text("  ·  $count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun PersonHeader(shelf: PersonShelf, modifier: Modifier, compact: Boolean = false) {
        val count = shelf.titles.size
        val last = relativeTimeLabel(shelf.titles.first().lastEventMs, nowMs)
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialAvatar(shelf.person.displayName, null, shelf.person.avatarColorHex, 32.dp)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)) {
                        append(shelf.person.displayName)
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(if (compact) "  ·  $last" else "  ·  ${if (count == 1) "1 title" else "$count titles"} · $last")
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** Title under the art (Apple TV "Up Next"): the art stays clean however busy it is. */
    @Composable
    private fun ShelfTile(group: FriendActivityGroup, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Art(group.contentId, group.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(3.dp))
            Text(
                group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(group.contextLabel(), ago(relativeTimeLabel(group.lastEventMs, nowMs))).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun TogetherCard(group: FriendActivityGroup, modifier: Modifier) {
        Box(modifier.clip(RoundedCornerShape(16.dp))) {
            Art(group.contentId, group.title, Modifier.matchParentSize())
            Scrim(Modifier.matchParentSize())
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SocialAvatarStack(group.stackedFriends, group.avatarOverflow, 30.dp)
                Column {
                    Text(
                        "${group.friendNamesLabel()} both watched",
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        group.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // --- B: art grid ----------------------------------------------------------------------------

    @Composable
    private fun GridFeed(width: Dp, horizontal: Dp, phone: Boolean) {
        val columns = if (phone) 2 else ((width + 14.dp) / (250.dp)).toInt().coerceIn(3, 5)
        Column(Modifier.padding(horizontal = horizontal)) {
            bucketFriendActivity(groups, nowMs).forEach { (bucket, bucketGroups) ->
                SectionLabel(bucket.label)
                Spacer(Modifier.height(2.dp))
                Grid(bucketGroups, columns, gap = if (phone) 10.dp else 14.dp) { group, modifier -> GridTile(group, phone, modifier) }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    /** Everything on the art: faces top-left, title and "faye · 2d" at the bottom. */
    @Composable
    private fun GridTile(group: FriendActivityGroup, phone: Boolean, modifier: Modifier) {
        Box(modifier.aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))) {
            Art(group.contentId, group.title, Modifier.matchParentSize())
            Scrim(Modifier.matchParentSize())
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                SocialAvatarStack(group.stackedFriends, group.avatarOverflow, if (phone) 22.dp else 26.dp)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(
                    group.title, color = Color.White,
                    style = if (phone) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(group.friendNamesLabel(), relativeTimeLabel(group.lastEventMs, nowMs)).filter(String::isNotBlank).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // --- round 2 (2026-10-06): "the art is too prominent, still messy" ------------------------

    /**
     * Two calmer directions. The person and the words lead; artwork is a small landscape accent
     * (never a portrait poster). C groups by person, D is one line per title.
     */
    @Test
    fun renderCalmDirections() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((direction, label) in listOf("C" to "digest", "D" to "log")) {
            listOf(411 to 914, 320 to 640).forEach { (w, h) ->
                renderScene("$direction-$label-phone-${w}x$h", w, h, failures) { PhoneScene(direction, withLive = true) }
            }
            renderScene("$direction-$label-phone-scroll-411x2000", 411, 2000, failures) { PhoneScene(direction, withLive = true) }
            listOf(1280 to 820, 1600 to 1000).forEach { (w, h) ->
                renderScene("$direction-$label-desktop-${w}x$h", w, h, failures) { DesktopScene(direction, withLive = false) }
            }
            renderScene("$direction-$label-desktop-scroll-1600x1500", 1600, 1500, failures) { DesktopScene(direction, withLive = true) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- C: one quiet card per friend ----------------------------------------------------------

    @Composable
    private fun DigestFeed(width: Dp, horizontal: Dp, phone: Boolean) {
        val columns = if (phone || width < 900.dp) 1 else 2
        Column(Modifier.padding(horizontal = horizontal)) {
            SectionLabel("Recently watched")
            Grid(shelves, columns, gap = 10.dp) { shelf, modifier -> DigestCard(shelf, modifier) }
        }
    }

    @Composable
    private fun DigestCard(shelf: PersonShelf, modifier: Modifier) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val strong = MaterialTheme.colorScheme.onBackground
        val last = relativeTimeLabel(shelf.titles.first().lastEventMs, nowMs)
        Row(
            modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SocialAvatar(shelf.person.displayName, null, shelf.person.avatarColorHex, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(shelf.person.displayName) }
                        withStyle(SpanStyle(color = muted)) { append("  ·  $last") }
                    },
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = muted)) { append("Watched ") }
                        shelf.titles.take(3).forEachIndexed { i, group ->
                            if (i > 0) withStyle(SpanStyle(color = muted)) { append(", ") }
                            withStyle(SpanStyle(color = strong)) { append(group.title) }
                            if (group.isEpisodic) {
                                withStyle(SpanStyle(color = muted)) { append(" (${group.contextLabel().substringBefore(" ·")})") }
                            }
                        }
                        val more = shelf.titles.size - 3
                        if (more > 0) withStyle(SpanStyle(color = muted)) { append(" and $more more") }
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shelf.titles.take(4).forEach { group ->
                        Art(group.contentId, group.title, Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }

    // --- D: the log -----------------------------------------------------------------------------

    @Composable
    private fun LogFeed(width: Dp, horizontal: Dp, phone: Boolean) {
        val columns = if (phone || width < 900.dp) 1 else 2
        Column(Modifier.padding(horizontal = horizontal)) {
            bucketFriendActivity(groups, nowMs).forEach { (bucket, bucketGroups) ->
                SectionLabel(bucket.label)
                // Wide gutter: a row's thumbnail must not read as belonging to the avatar beside it.
                Column {
                    bucketGroups.chunked(columns).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                            line.forEach { LogRow(it, Modifier.weight(1f)) }
                            repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    @Composable
    private fun LogRow(group: FriendActivityGroup, modifier: Modifier) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val strong = MaterialTheme.colorScheme.onBackground
        Row(
            modifier.padding(vertical = 9.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
                SocialAvatarStack(group.stackedFriends, group.avatarOverflow, if (group.friends.size > 1) 28.dp else 36.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(group.friendNamesLabel()) }
                        withStyle(SpanStyle(color = muted)) { append(" watched ") }
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(group.title) }
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(group.contextLabel(), relativeTimeLabel(group.lastEventMs, nowMs)).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Art(group.contentId, group.title, Modifier.width(84.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        }
    }

    // --- round 3 (2026-10-06): "I like D, but per-friend, but also a timeline" -----------------

    /**
     * E, the friend-day timeline (after Letterboxd's activity log): day headers, and within a day one
     * row per friend whose titles collapse into one sentence. A title two friends watched that day is
     * its own row. Rows keep D's form: avatar, sentence, meta, small landscape art.
     */
    @Test
    fun renderTimelineDirection() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((direction, label) in listOf("E" to "timeline", "E2" to "timeline-weeks")) {
            listOf(411 to 914, 320 to 640).forEach { (w, h) ->
                renderScene("$direction-$label-phone-${w}x$h", w, h, failures) { PhoneScene(direction, withLive = true) }
            }
            renderScene("$direction-$label-phone-scroll-411x2000", 411, 2000, failures) { PhoneScene(direction, withLive = true) }
            listOf(1280 to 820, 1600 to 1000, 1920 to 1080).forEach { (w, h) ->
                renderScene("$direction-$label-desktop-${w}x$h", w, h, failures) { DesktopScene(direction, withLive = false) }
            }
            renderScene("$direction-$label-desktop-scroll-1600x1500", 1600, 1500, failures) { DesktopScene(direction, withLive = true) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** One timeline row: one friend's titles that day, or one title several friends watched that day. */
    private data class TimelineEntry(val people: List<SocialProfileSummary>, val titles: List<FriendActivityGroup>)

    private val dayMs = 86_400_000L

    private fun dayLabel(dayStart: Long): String {
        val today = nowMs / dayMs * dayMs
        val days = ((today - dayStart) / dayMs).toInt()
        val date = java.time.LocalDate.ofEpochDay(dayStart / dayMs)
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            else -> "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${date.dayOfMonth}"
        }
    }

    /** E2: Today, Yesterday, This week, Last week, Earlier. Fewer headers, more collapse per friend. */
    private fun weekBucket(dayStart: Long): Long {
        val days = ((nowMs / dayMs * dayMs - dayStart) / dayMs)
        return when {
            days <= 1 -> -days          // 0 today, -1 yesterday
            days < 7 -> -2
            days < 14 -> -3
            else -> -4
        }
    }

    private val weekLabels = mapOf(0L to "Today", -1L to "Yesterday", -2L to "This week", -3L to "Last week", -4L to "Earlier")

    private fun buildTimeline(coarse: Boolean): List<Pair<String, List<TimelineEntry>>> = runs
        .groupBy { run ->
            val day = (parseSocialTimestampMs(run.lastEventTime) ?: 0L) / dayMs * dayMs
            if (coarse) weekBucket(day) else day
        }
        .toSortedMap(compareByDescending { it })
        .map { (day, dayRuns) ->
            val titles = groupFriendActivity(dayRuns)
            val shared = titles.filter { it.friends.size > 1 }.map { TimelineEntry(it.friends, listOf(it)) }
            val solo = titles.filter { it.friends.size == 1 }
                .groupBy { it.friends.single().profileId }
                .values.map { TimelineEntry(listOf(it.first().friends.single()), it) }
            (if (coarse) weekLabels.getValue(day) else dayLabel(day)) to (shared + solo)
        }

    private val timeline = buildTimeline(coarse = false)
    private val weekTimeline = buildTimeline(coarse = true)

    @Composable
    private fun TimelineFeed(horizontal: Dp, phone: Boolean, coarse: Boolean) {
        Column(Modifier.padding(horizontal = horizontal)) {
            (if (coarse) weekTimeline else timeline).forEach { (label, entries) ->
                SectionLabel(label)
                entries.forEach { TimelineRow(it) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    @Composable
    private fun TimelineRow(entry: TimelineEntry) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val strong = MaterialTheme.colorScheme.onBackground
        val titles = entry.titles
        val names = when (entry.people.size) {
            1 -> entry.people[0].displayName
            2 -> "${entry.people[0].displayName} & ${entry.people[1].displayName}"
            else -> "${entry.people[0].displayName} + ${entry.people.size - 1}"
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
                SocialAvatarStack(entry.people.take(3), (entry.people.size - 3).coerceAtLeast(0), if (entry.people.size > 1) 28.dp else 36.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(names) }
                        withStyle(SpanStyle(color = muted)) { append(" watched ") }
                        val shown = titles.take(3)
                        shown.forEachIndexed { i, group ->
                            if (i > 0) withStyle(SpanStyle(color = muted)) { append(if (i == shown.lastIndex && titles.size <= 3) " and " else ", ") }
                            withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(group.title) }
                        }
                        if (titles.size > 3) withStyle(SpanStyle(color = muted)) { append(" and ${titles.size - 3} more") }
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = if (titles.size == 1) {
                    titles[0].contextLabel()
                } else {
                    val films = titles.count { !it.isEpisodic }
                    val shows = titles.size - films
                    listOfNotNull(
                        films.takeIf { it > 0 }?.let { if (it == 1) "1 film" else "$it films" },
                        shows.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" },
                    ).joinToString(" · ")
                }
                Text(
                    listOf(meta, relativeTimeLabel(titles.first().lastEventMs, nowMs)).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            ThumbStack(titles)
        }
    }

    /** Up to three stills fanned to the left, newest on top, each edged in the page colour. */
    @Composable
    private fun ThumbStack(titles: List<FriendActivityGroup>) {
        val shown = titles.take(3)
        val step = 14.dp
        Box(Modifier.width(84.dp + step * (shown.size - 1)).height(48.dp)) {
            shown.reversed().forEachIndexed { i, group ->
                val depth = shown.size - 1 - i
                Box(
                    Modifier.padding(start = step * (shown.size - 1 - depth), top = (depth * 3).dp)
                        .width(84.dp - (depth * 6).dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(1.5.dp)
                        .clip(RoundedCornerShape(7.dp)),
                ) { Art(group.contentId, group.title, Modifier.matchParentSize()) }
            }
        }
    }

    // --- round 4 (2026-10-06): "I like the stacked multi-title rows, but desktop is dead space and
    // mobile is cramped" ---------------------------------------------------------------------------

    /**
     * F, piles: E2's sections and per-friend collapse, but each entry is a card whose art is a tidy
     * deck of that friend's stills (front on top, the rest peeking above it, smaller and darker), with
     * the words under it. Cards flow into columns, so desktop fills its width and a phone gets two.
     */
    @Test
    fun renderPilesDirection() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        listOf(411 to 914, 360 to 780, 320 to 640).forEach { (w, h) ->
            renderScene("F-piles-phone-${w}x$h", w, h, failures) { PhoneScene("F", withLive = true) }
        }
        renderScene("F-piles-phone-scroll-411x1900", 411, 1900, failures) { PhoneScene("F", withLive = true) }
        listOf(1280 to 820, 1600 to 1000, 1920 to 1080).forEach { (w, h) ->
            renderScene("F-piles-desktop-${w}x$h", w, h, failures) { DesktopScene("F", withLive = false) }
        }
        renderScene("F-piles-desktop-scroll-1600x1400", 1600, 1400, failures) { DesktopScene("F", withLive = true) }
        renderScene("F-piles-desktop-live-1600x1000", 1600, 1000, failures) { DesktopScene("F", withLive = true) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** Columns and cell width for the pile grid in a content area [width] wide. */
    private fun pileGrid(width: Dp, phone: Boolean): Pair<Int, Dp> {
        val columns = if (phone) 2 else ((width + PileGap) / (200.dp + PileGap)).toInt().coerceIn(3, 6)
        val gap = if (phone) PilePhoneGap else PileGap
        return columns to (width - gap * (columns - 1)) / columns
    }

    @Composable
    private fun PilesFeed(width: Dp, horizontal: Dp, phone: Boolean) {
        val gap = if (phone) PilePhoneGap else PileGap
        val (columns, _) = pileGrid(width - horizontal * 2, phone)
        // The timeline without holes: a section's label sits above its first card, inside the grid,
        // so Today, Yesterday and This week share a line when they are short.
        val cells = weekTimeline.flatMap { (label, entries) -> entries.mapIndexed { i, entry -> (if (i == 0) label else null) to entry } }
        Column(Modifier.padding(horizontal = horizontal), verticalArrangement = Arrangement.spacedBy(if (phone) 26.dp else 22.dp)) {
            cells.chunked(columns).forEach { line ->
                val labelled = line.any { it.first != null }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { (label, entry) ->
                        Column(Modifier.weight(1f)) {
                            if (labelled) {
                                Box(Modifier.height(30.dp), contentAlignment = Alignment.TopStart) {
                                    if (label != null) {
                                        Text(
                                            label.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp,
                                        )
                                    }
                                }
                            }
                            PileCard(entry, phone, Modifier.fillMaxWidth())
                        }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun PileCard(entry: TimelineEntry, phone: Boolean, modifier: Modifier) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val strong = MaterialTheme.colorScheme.onBackground
        val titles = entry.titles
        val names = when (entry.people.size) {
            1 -> entry.people[0].displayName
            2 -> "${entry.people[0].displayName} & ${entry.people[1].displayName}"
            else -> "${entry.people[0].displayName} + ${entry.people.size - 1}"
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Pile(titles)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SocialAvatarStack(entry.people.take(3), (entry.people.size - 3).coerceAtLeast(0), if (phone) 20.dp else 22.dp)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(names) }
                        withStyle(SpanStyle(color = muted)) { append(" · ${relativeTimeLabel(titles.first().lastEventMs, nowMs)}") }
                    },
                    style = if (phone) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (titles.size == 1) {
                Text(
                    titles[0].title, style = MaterialTheme.typography.bodyMedium, color = strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val context = titles[0].contextLabel()
                if (context.isNotBlank()) {
                    Text(context, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val films = titles.count { !it.isEpisodic }
                val shows = titles.size - films
                Text(
                    listOfNotNull(
                        films.takeIf { it > 0 }?.let { if (it == 1) "1 film" else "$it films" },
                        shows.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = strong, maxLines = 1,
                )
                Text(
                    titles.joinToString(", ") { it.title },
                    style = MaterialTheme.typography.labelMedium, color = muted,
                    maxLines = if (phone) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /**
     * Up to three stills fanned to the right: the newest in front at the left, each older one a step
     * further right, a little shorter and darker, so its edge shows. Every pile has the same 16:9
     * footprint, so a line of cards lines up whatever the counts.
     */
    @Composable
    private fun Pile(titles: List<FriendActivityGroup>) {
        val shown = titles.take(3)
        val shift = 12.dp
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            val cardWidth = maxWidth - shift * (shown.size - 1)
            shown.indices.reversed().forEach { depth ->
                val group = shown[depth]
                Box(
                    Modifier.padding(start = shift * depth, top = (6 * depth).dp, bottom = (6 * depth).dp)
                        .width(cardWidth).fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Art(group.contentId, group.title, Modifier.matchParentSize())
                    if (depth > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f + 0.2f * depth)))
                }
            }
        }
    }

    // --- shared pieces --------------------------------------------------------------------------

    @Composable
    private fun LiveCard(item: WatchingNowItem, modifier: Modifier) {
        Box(modifier.clip(RoundedCornerShape(16.dp))) {
            Art(item.contentId, item.title, Modifier.matchParentSize())
            Scrim(Modifier.matchParentSize())
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(LiveColor))
                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(45.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().border(2.dp, LiveColor, CircleShape))
                    SocialAvatar(item.profile.displayName, null, item.profile.avatarColorHex, 38.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("${item.profile.displayName} is watching", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("S1 E6 · The Judas Goat", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                Text("▶ Ask to join", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(item.progressFraction).height(3.dp).background(LiveColor))
            }
        }
    }

    @Composable
    private fun Rail(modifier: Modifier) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) { Text("Add by @handle", style = MaterialTheme.typography.bodyMedium, color = muted) }
            Spacer(Modifier.height(8.dp))
            SectionLabel("Friends", count = "${friends.size}")
            friends.forEach { person ->
                val isLive = person == seraph
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        if (isLive) Box(Modifier.fillMaxSize().border(2.dp, LiveColor, CircleShape))
                        SocialAvatar(person.displayName, null, person.avatarColorHex, 40.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(person.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            if (isLive) "Watching The Punisher" else "@${person.handle}",
                            style = MaterialTheme.typography.labelMedium, color = if (isLive) LiveColor else muted, maxLines = 1,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = muted)
                }
            }
        }
    }

    /** The desktop top bar the shell draws; the Social header sits right under its reservation. */
    @Composable
    private fun NavPill() {
        Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.padding(top = 14.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Home", "Search", "Library", "Downloads", "Social", "Settings").forEach { tab ->
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (tab == "Social") Color.White.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) { Text(tab, style = MaterialTheme.typography.titleSmall) }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.PhoneChrome() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Identity(42.dp, big = false, modifier = Modifier.weight(1f))
            Pill("Inbox")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(3.dp)) {
            listOf("Activity" to true, "Friends" to false).forEach { (label, selected) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                        .background(if (selected) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
    }

    @Composable
    private fun Identity(avatar: Dp, big: Boolean, modifier: Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SocialAvatar(me.displayName, null, me.avatarColorHex, avatar)
            Column {
                Text(
                    me.displayName, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Text("@${me.handle} · ${friends.size} friends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Pill(text: String) {
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }

    @Composable
    private fun SectionLabel(text: String, count: String? = null, live: Boolean = false) {
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(LiveColor))
            Text(
                text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp,
            )
            if (count != null) Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun <T> Grid(items: List<T>, columns: Int, gap: Dp, cell: @Composable (T, Modifier) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun Scrim(modifier: Modifier) {
        Box(
            modifier.background(
                Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.Black.copy(alpha = 0.2f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f)),
                ),
            ),
        )
    }

    /** A real backdrop from [artDir] when present, else a deterministic gradient. */
    @Composable
    private fun Art(contentId: String, seed: String, modifier: Modifier) {
        val bitmap = loadArt(contentId)
        if (bitmap != null) {
            Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
            return
        }
        val h = seed.hashCode()
        val hue = ((h.toLong() and 0x7fffffffL) % 360L).toFloat()
        Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.5f), Color.hsv((hue + 38f) % 360f, 0.65f, 0.16f)), Offset.Zero, Offset(size.width, size.height)))
        }
    }

    private val artCache = mutableMapOf<String, ImageBitmap?>()

    private fun loadArt(contentId: String): ImageBitmap? = artCache.getOrPut(contentId) {
        listOf("jpg", "webp", "png").map { File(artDir, "$contentId.$it") }.firstOrNull(File::isFile)?.let { file ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }

    private fun ago(time: String) = if (time.isBlank() || time == "Just now") time else "$time ago"

    private fun renderScene(name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, content: @Composable () -> Unit) {
        runCatching {
            val scene = ImageComposeScene(width = widthDp * 2, height = heightDp * 2, density = Density(2f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density, 1f),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
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
        val LiveColor = Color(0xFF6FD08C)
        val PileGap = 18.dp
        val PilePhoneGap = 16.dp
    }
}
