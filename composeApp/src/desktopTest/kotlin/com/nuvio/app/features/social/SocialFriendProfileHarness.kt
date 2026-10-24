package com.nuvio.app.features.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The friend profile (2026-10-24): **five redesigns** after the maintainer found the shipped panel
 * ugly (a stack of 16:9 stills with text beside them, a lone Watched-together tile, two bare
 * switches and a red text button), plus **audience-based privacy settings** replacing the global
 * switch + per-friend hide.
 *
 * - **A, showcase page:** full-screen; the backdrop of what they are watching (or watched last) is the
 *   page's hero, avatar and name on its fade; Join strip, together stats, landscape shelves like Home.
 * - **B, two-pane page:** a sticky identity card (who, live status, together, the per-friend controls)
 *   beside a wide content column: Watching Now banner, then their history as a bucketed still grid.
 * - **C, pop-out card:** a small Discord-style card instead of a page: banner, avatar, live status with
 *   Join, three recent stills, three icon toggles, "All activity" for the long list.
 * - **D, tabbed panel:** keeps today's side panel but splits it into Activity / Together / Privacy
 *   tabs under a compact header with the actions in it.
 * - **E, diary page:** full-screen; their history as a dated timeline (big day numbers, wide stills,
 *   "with you" marks), with a "You two" column: together, titles in common, controls.
 *
 * Privacy (P1 / P2): every audience setting takes All my friends / All my friends, except... /
 * No one, except... / No one, the middle two opening a friend list. P1 expands in place inside Social
 * settings; P2 opens a page per setting with a picker.
 *
 * No product code. Real backdrops from `build/social-design-art/` (see [SocialActivityRedesignHarness]).
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialFriendProfileHarness" --rerun
 * # PNGs land in composeApp/build/social-friend-profile/
 * ```
 */
class SocialFriendProfileHarness {

    private val outputDir = File("build/social-friend-profile")
    private val artDir = File("build/social-design-art")

    private class Seen(
        val id: String,
        val title: String,
        val line: String,
        val ago: String,
        val day: String,
        val month: String,
        val withYou: Boolean = false,
    )

    private class Together(val id: String, val title: String, val parties: Int)

    private val faisal = SocialProfileSummary(profileId = "p-faisal", handle = "faisal", displayName = "faisal", avatarColorHex = "#E8833A", isFriend = true)

    private val friends = listOf(
        faisal,
        SocialProfileSummary(profileId = "p-seraph", handle = "seraph", displayName = "Seraph", avatarColorHex = "#8E6CF0", isFriend = true),
        SocialProfileSummary(profileId = "p-ben", handle = "ben", displayName = "ben", avatarColorHex = "#3A9BE8", isFriend = true),
        SocialProfileSummary(profileId = "p-mika", handle = "mika", displayName = "Mika", avatarColorHex = "#D9487A", isFriend = true),
        SocialProfileSummary(profileId = "p-jules", handle = "jules", displayName = "Jules", avatarColorHex = "#3FB37F", isFriend = true),
        SocialProfileSummary(profileId = "p-ana", handle = "ana", displayName = "Ana", avatarColorHex = "#C79A2E", isFriend = true),
        SocialProfileSummary(profileId = "p-jeremy", handle = "jeremy", displayName = "jeremy", avatarColorHex = "#5A6BD8", isFriend = true),
        SocialProfileSummary(profileId = "p-faye", handle = "faye", displayName = "Faye", avatarColorHex = "#B35FD0", isFriend = true),
    )

    private val nowId = "tt5675620"
    private val nowTitle = "The Punisher"
    private val nowLine = "S1 E6 · The Judas Goat"
    private val nowLeft = "22 min left"
    private val nowProgress = 0.43f

    private val seen = listOf(
        Seen("tt0121955", "South Park", "S2 E6 · 4 episodes", "Yesterday", "23", "OCT"),
        Seen("tt7282468", "Burning", "Movie", "3d ago", "21", "OCT", withYou = true),
        Seen("tt0068361", "The Discreet Charm of the Bourgeoisie", "Movie", "5d ago", "19", "OCT"),
        Seen("tt0071406", "Pastoral: To Die in the Country", "Movie", "1w ago", "16", "OCT"),
        Seen("tt0071141", "Ali: Fear Eats the Soul", "Movie", "2w ago", "10", "OCT"),
        Seen("tt0073198", "Jeanne Dielman, 23, quai du Commerce, 1080 Bruxelles", "Movie", "3w ago", "03", "OCT"),
        Seen("tt1191111", "Enter the Void", "Movie", "4w ago", "28", "SEP"),
        Seen("tt0423176", "The World", "Movie", "4w ago", "27", "SEP"),
    )

    /** A longer history (2026-10-24 history round): three months, series binges and movies, to judge scaling. */
    private val history = listOf(
        Seen("tt0121955", "South Park", "S2 E6 · 4 episodes", "Yesterday", "23", "OCT"),
        Seen("tt7282468", "Burning", "Movie", "3d ago", "21", "OCT", withYou = true),
        Seen("tt0068361", "The Discreet Charm of the Bourgeoisie", "Movie", "5d ago", "19", "OCT"),
        Seen("tt0149460", "Futurama", "S7 E1 · 6 episodes", "1w ago", "17", "OCT"),
        Seen("tt0071406", "Pastoral: To Die in the Country", "Movie", "1w ago", "16", "OCT"),
        Seen("tt0071141", "Ali: Fear Eats the Soul", "Movie", "2w ago", "10", "OCT"),
        Seen("tt0329938", "Transformers: Armada", "S1 E5 · 5 episodes", "2w ago", "08", "OCT"),
        Seen("tt0073198", "Jeanne Dielman, 23, quai du Commerce, 1080 Bruxelles", "Movie", "3w ago", "03", "OCT"),
        Seen("tt1191111", "Enter the Void", "Movie", "4w ago", "28", "SEP"),
        Seen("tt0423176", "The World", "Movie", "4w ago", "27", "SEP"),
        Seen("tt2278388", "The Grand Budapest Hotel", "Movie", "5w ago", "20", "SEP"),
        Seen("tt0430651", "Survive Style 5+", "Movie", "6w ago", "14", "SEP"),
        Seen("tt0914798", "The Boy in the Striped Pajamas", "Movie", "6w ago", "09", "SEP", withYou = true),
        Seen("tt37287335", "Obsession", "Movie", "8w ago", "30", "AUG", withYou = true),
    )

    private val monthNames = mapOf("OCT" to "October", "SEP" to "September", "AUG" to "August")

    private val together = listOf(
        Together("tt7282468", "Burning", 2),
        Together("tt0914798", "The Boy in the Striped Pajamas", 1),
        Together("tt37287335", "Obsession", 1),
    )

    // --- scenes ---------------------------------------------------------------------------------

    @Test
    fun renderFriendProfileCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        renderScene("A-showcase-desktop", 1440, 1500, failures) { DesktopShell { ShowcasePage(wide = true) } }
        renderScene("A-showcase-phone", 411, 1900, failures) { ShowcasePage(wide = false) }
        renderScene("B-two-pane-desktop", 1440, 1240, failures) { DesktopShell { TwoPanePage(wide = true) } }
        renderScene("B-two-pane-phone", 411, 2200, failures) { TwoPanePage(wide = false) }
        renderScene("C-popout-desktop", 1440, 900, failures) { DesktopShell { Dimmed { PopoutCard(Modifier.align(Alignment.Center).width(440.dp)) } } }
        renderScene("C-popout-phone", 411, 914, failures) {
            Dimmed { PopoutCard(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), sheet = true) }
        }
        renderScene("D-tabs-activity-desktop", 1440, 900, failures) { DesktopShell { Dimmed { SidePanel { TabbedPanel(tab = 0, wide = true) } } } }
        renderScene("D-tabs-privacy-desktop", 1440, 900, failures) { DesktopShell { Dimmed { SidePanel { TabbedPanel(tab = 2, wide = true) } } } }
        renderScene("D-tabs-activity-phone", 411, 914, failures) { TabbedPanel(tab = 0, wide = false) }
        renderScene("D-tabs-together-phone", 411, 914, failures) { TabbedPanel(tab = 1, wide = false) }
        renderScene("E-diary-desktop", 1440, 1500, failures) { DesktopShell { DiaryPage(wide = true) } }
        renderScene("E-diary-phone", 411, 2300, failures) { DiaryPage(wide = false) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * H2a picked (2026-10-24). The final page at real window sizes, and three phone placements of
     * "You two" (the stacked one put it above the history): screens at 411x914, top and scrolled.
     */
    @Test
    fun renderProfilePageAtRealSizes() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val history: @Composable (Boolean) -> Unit = { wide -> ShelvesCaptioned(wide) }
        renderScene("F-desktop-1920x1080", 1920, 1080, failures) { DesktopShell { DiaryPage(wide = true, historyBody = history) } }
        renderScene("F-desktop-1440x900", 1440, 900, failures) { DesktopShell { DiaryPage(wide = true, historyBody = history) } }
        renderScene("F-desktop-1440x900-scrolled", 1440, 900, failures) { DesktopShell { DiaryPage(wide = true, historyBody = history, scrollPx = Int.MAX_VALUE) } }
        renderScene("F-tablet-1280x800", 1280, 800, failures) { DiaryPage(wide = true, historyBody = history) }
        for ((name, layout) in listOf("M1-bottom" to "bottom", "M2-strip" to "strip", "M3-tabs" to "tabs")) {
            renderScene("$name-phone-1-top", 411, 914, failures) { DiaryPage(wide = false, historyBody = history, phone = layout) }
            renderScene("$name-phone-2-scrolled", 411, 914, failures) { DiaryPage(wide = false, historyBody = history, phone = layout, scrollPx = 1200) }
        }
        renderScene("M1-bottom-phone-3-end", 411, 914, failures) { DiaryPage(wide = false, historyBody = history, phone = "bottom", scrollPx = Int.MAX_VALUE) }
        renderScene("M2-strip-phone-3-sheet", 411, 914, failures) {
            Box(Modifier.fillMaxSize()) {
                DiaryPage(wide = false, historyBody = history, phone = "strip")
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                BetweenSheet(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        renderScene("M3-tabs-phone-3-you-two", 411, 914, failures) { DiaryPage(wide = false, historyBody = history, phone = "tabs", phoneTab = 1, scrollPx = 520) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** The diary page minus the diary (maintainer, 2026-10-24): candidate histories that scale. */
    @Test
    fun renderDiaryHistoryCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val candidates: List<Pair<String, @Composable (Boolean) -> Unit>> = listOf(
            "H1-month-grid" to { wide -> MonthGrid(wide) },
            "H2-month-shelves" to { wide -> MonthShelves(wide) },
            "H3-month-mosaic" to { wide -> MonthMosaic(wide) },
            "H4-filtered-grid" to { wide -> FilteredGrid(wide) },
            "H2a-captioned" to { wide -> ShelvesCaptioned(wide) },
            "H2b-end-card" to { wide -> ShelvesEndCard(wide) },
            "H2c-lead-tile" to { wide -> ShelvesLeadTile(wide) },
        )
        for ((name, body) in candidates) {
            renderScene("$name-desktop", 1440, 2000, failures) { DesktopShell { DiaryPage(wide = true, historyBody = body) } }
            renderScene("$name-phone", 411, 3200, failures) { DiaryPage(wide = false, historyBody = body) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun renderAudiencePrivacyCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        renderScene("P1-inline-collapsed-desktop", 1440, 900, failures) { DesktopShell { Dimmed { SidePanel { SettingsPanel { InlineAudienceSettings(expanded = false) } } } } }
        renderScene("P1-inline-expanded-desktop", 1440, 1300, failures) { DesktopShell { Dimmed { SidePanel { SettingsPanel { InlineAudienceSettings(expanded = true) } } } } }
        renderScene("P1-inline-expanded-phone", 411, 1500, failures) { SettingsPanel { InlineAudienceSettings(expanded = true) } }
        renderScene("P2-page-desktop", 1440, 900, failures) { DesktopShell { Dimmed { SidePanel { AudiencePage(wide = true) } } } }
        renderScene("P2-page-phone", 411, 914, failures) { AudiencePage(wide = false) }
        renderScene("P2-picker-desktop", 1440, 900, failures) {
            DesktopShell { Dimmed { AudiencePicker(Modifier.align(Alignment.Center).width(520.dp), wide = true) } }
        }
        renderScene("P2-picker-phone", 411, 914, failures) { Dimmed { AudiencePicker(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), wide = false) } }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- A: showcase page -----------------------------------------------------------------------

    @Composable
    private fun ShowcasePage(wide: Boolean) {
        val bg = MaterialTheme.colorScheme.background
        val pad = if (wide) 48.dp else 18.dp
        Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(if (wide) 460.dp else 330.dp)) {
                Art(nowId, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.35f to Color.Transparent, 1f to bg)))
                if (wide) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.7f), 0.55f to Color.Transparent)))
                TopBar(Modifier.align(Alignment.TopStart))
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = pad).padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    LiveAvatar(faisal, if (wide) 112.dp else 84.dp, live = true)
                    Column {
                        Text(faisal.displayName, style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("@faisal · Friends since September", style = MaterialTheme.typography.bodyMedium, color = muted())
                    }
                }
            }
            Column(Modifier.padding(horizontal = pad).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                NowStrip(wide)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("14 h", "together")
                    StatPill("4", "parties")
                    if (wide) StatPill("8", "titles this month")
                }
                Shelf("Recently watched", wide) {
                    seen.forEach { StillTile(it.id, it.title, "${it.line} · ${it.ago}", if (wide) 300.dp else 220.dp, withYou = it.withYou) }
                }
                Shelf("Watched together", wide) {
                    together.forEach { StillTile(it.id, it.title, if (it.parties == 1) "1 party" else "${it.parties} parties", if (wide) 300.dp else 220.dp) }
                }
                Column(Modifier.widthIn(max = 640.dp)) {
                    SocialSectionLabel("Between you two")
                    BetweenYouTwo(card = true)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun NowStrip(wide: Boolean) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LiveTag("WATCHING NOW")
                        Text(nowTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("$nowLine · $nowLeft", style = MaterialTheme.typography.bodyMedium, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (wide) {
                        SocialPillButton("Details", {}, compact = false)
                        SocialPillButton("Join", {}, primary = true, compact = false)
                    }
                }
                ProgressBar(nowProgress)
                if (!wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SocialPillButton("Join", {}, Modifier.weight(1f), primary = true, compact = false)
                        SocialPillButton("Details", {}, Modifier.weight(1f), compact = false)
                    }
                }
            }
        }
    }

    // --- B: two-pane page -----------------------------------------------------------------------

    @Composable
    private fun TwoPanePage(wide: Boolean) {
        val bg = MaterialTheme.colorScheme.background
        if (wide) {
            Row(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Surface(Modifier.width(360.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    IdentityCard(wide = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    NowBanner(Modifier.fillMaxWidth().height(300.dp), wide = true)
                    HistoryGrid(columns = 3)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
                TopBar(Modifier, glass = false)
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { IdentityCard(wide = false) }
                    NowBanner(Modifier.fillMaxWidth().aspectRatio(16f / 10f), wide = false)
                    HistoryGrid(columns = 2)
                }
            }
        }
    }

    @Composable
    private fun IdentityCard(wide: Boolean) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LiveAvatar(faisal, 104.dp, live = true)
                    Icon(Icons.Rounded.MoreVert, null, tint = muted())
                }
                Column {
                    Text(faisal.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("@faisal", style = MaterialTheme.typography.bodyMedium, color = muted())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiveAvatar(faisal, 72.dp, live = true)
                    Column(Modifier.weight(1f)) {
                        Text(faisal.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("@faisal", style = MaterialTheme.typography.bodyMedium, color = muted())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("14 h", "together")
                StatPill("4", "parties")
            }
            HairLine()
            Text("Between you two", style = MaterialTheme.typography.labelLarge, color = muted(), fontWeight = FontWeight.SemiBold)
            BetweenYouTwo(card = false)
            HairLine()
            Text("Remove friend", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun NowBanner(modifier: Modifier, wide: Boolean) {
        Box(modifier.clip(RoundedCornerShape(22.dp))) {
            Art(nowId, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            SocialGlassChip(Modifier.align(Alignment.TopStart).padding(14.dp)) { LiveTag("WATCHING NOW", onArt = true) }
            Column(Modifier.align(Alignment.BottomStart).padding(if (wide) 24.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(nowTitle, color = Color.White, style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("$nowLine · $nowLeft", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SocialPillButton("Join", {}, primary = true, compact = !wide)
                    SocialPillButton("Details", {}, compact = !wide)
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { ProgressBar(nowProgress, thin = true) }
        }
    }

    @Composable
    private fun HistoryGrid(columns: Int) {
        val buckets = listOf("This week" to seen.take(3), "Earlier this month" to seen.drop(3).take(3), "September" to seen.drop(6))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buckets.forEach { (label, items) ->
                SocialSectionLabel(label.uppercase())
                items.chunked(columns).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { item ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                                    Art(item.id, Modifier.matchParentSize())
                                    if (item.withYou) WithYouChip(Modifier.align(Alignment.TopStart).padding(8.dp))
                                }
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.line} · ${item.ago}", style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    // --- C: pop-out card ------------------------------------------------------------------------

    @Composable
    private fun PopoutCard(modifier: Modifier, sheet: Boolean = false) {
        val shape = if (sheet) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(28.dp)
        Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column {
                Box(Modifier.fillMaxWidth().height(194.dp)) {
                    Box(Modifier.fillMaxWidth().height(150.dp)) {
                        Art(nowId, Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface)))
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(5.dp)) {
                        LiveAvatar(faisal, 80.dp, live = true)
                    }
                    GlassIcon(Icons.Rounded.MoreVert, Modifier.align(Alignment.TopEnd).padding(12.dp))
                    if (sheet) {
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape).background(Color.White.copy(alpha = 0.6f)),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp)) {
                    Text(faisal.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@faisal · 14 h together · 4 parties", style = MaterialTheme.typography.bodySmall, color = muted())
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(width = 88.dp, height = 50.dp).clip(RoundedCornerShape(10.dp))) { Art(nowId, Modifier.matchParentSize()) }
                            Column(Modifier.weight(1f)) {
                                LiveTag("WATCHING")
                                Text(nowTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("S1 E6 · $nowLeft", style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1)
                            }
                            SocialPillButton("Join", {}, primary = true)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENTLY", style = MaterialTheme.typography.labelMedium, color = muted(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("All activity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seen.take(3).forEach { item ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) { Art(item.id, Modifier.matchParentSize()) }
                                Text(item.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.ago, style = MaterialTheme.typography.labelSmall, color = muted())
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(Icons.Rounded.Visibility, "Sees Now", on = true, Modifier.weight(1f))
                        ToggleChip(Icons.Rounded.VideoLibrary, "Sees history", on = false, Modifier.weight(1f))
                        ToggleChip(Icons.Rounded.Notifications, "Notify me", on = true, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleChip(icon: ImageVector, label: String, on: Boolean, modifier: Modifier) {
        val fg = if (on) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        Row(
            modifier.clip(RoundedCornerShape(14.dp))
                .background(if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }

    // --- D: tabbed panel ------------------------------------------------------------------------

    @Composable
    private fun TabbedPanel(tab: Int, wide: Boolean) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth().height(if (wide) 170.dp else 190.dp)) {
                Art(nowId, Modifier.matchParentSize().alpha(0.55f))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface)))
                if (!wide) TopBar(Modifier.align(Alignment.TopStart))
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LiveAvatar(faisal, 68.dp, live = true)
                    Column(Modifier.weight(1f)) {
                        Text(faisal.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(SocialLiveColor))
                            Text("Watching The Punisher", style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    SocialPillButton("Join", {}, primary = true)
                    if (wide) GlassIcon(Icons.Rounded.MoreVert)
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Activity", "Together", "Privacy").forEachIndexed { index, label ->
                    val selected = index == tab
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        Text(label, color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> seen.take(6).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { item -> CaptionTile(item.id, item.title, item.ago, Modifier.weight(1f), withYou = item.withYou) }
                        }
                    }
                    1 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BigStat("14 h", "watched together", Modifier.weight(1f))
                            BigStat("4", "parties", Modifier.weight(1f))
                        }
                        together.forEach { item -> CaptionTile(item.id, item.title, if (item.parties == 1) "1 party" else "${item.parties} parties", Modifier.fillMaxWidth()) }
                    }
                    else -> {
                        BetweenYouTwo(card = true)
                        Text(
                            "These follow your Social settings. Turning one off here adds faisal to that setting's except list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Remove friend", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable
    private fun CaptionTile(id: String, title: String, meta: String, modifier: Modifier, withYou: Boolean = false) {
        Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            Art(id, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            if (withYou) WithYouChip(Modifier.align(Alignment.TopStart).padding(8.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // --- E: diary page --------------------------------------------------------------------------

    @Composable
    private fun DiaryPage(
        wide: Boolean,
        historyBody: @Composable (Boolean) -> Unit = { Diary(it) },
        /** Where the phone puts "You two": stack (above history), bottom, strip (stats + sheet), tabs. */
        phone: String = "stack",
        phoneTab: Int = 0,
        scrollPx: Int = 0,
    ) {
        val bg = MaterialTheme.colorScheme.background
        Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState(scrollPx))) {
            Box(Modifier.fillMaxWidth().height(if (wide) 250.dp else 230.dp)) {
                Row(Modifier.matchParentSize()) {
                    seen.take(if (wide) 5 else 3).forEach { Art(it.id, Modifier.weight(1f).fillMaxHeight()) }
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to bg.copy(alpha = 0.55f), 1f to bg)))
                TopBar(Modifier.align(Alignment.TopStart))
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = if (wide) 48.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LiveAvatar(faisal, if (wide) 96.dp else 76.dp, live = true)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(faisal.displayName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("@faisal · 41 titles since September", style = MaterialTheme.typography.bodyMedium, color = muted())
                    }
                }
            }
            if (wide) {
                Row(Modifier.padding(horizontal = 48.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DiaryNow(wide = true)
                        historyBody(true)
                    }
                    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { YouTwo() }
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DiaryNow(wide = false)
                    when (phone) {
                        "bottom" -> {
                            historyBody(false)
                            Spacer(Modifier.height(4.dp))
                            YouTwo()
                        }
                        "strip" -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatPill("14 h", "together")
                                StatPill("4", "parties")
                                StatPill("6", "in common")
                            }
                            BetweenRow()
                            TogetherShelf()
                            historyBody(false)
                        }
                        "tabs" -> {
                            PhoneTabs(phoneTab)
                            if (phoneTab == 0) historyBody(false) else YouTwo()
                        }
                        else -> {
                            YouTwo()
                            historyBody(false)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    private fun DiaryNow(wide: Boolean) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(if (wide) 220.dp else 120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                    Art(nowId, Modifier.matchParentSize())
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { ProgressBar(nowProgress, thin = true) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiveTag("NOW")
                    Text(nowTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (wide) "$nowLine · $nowLeft" else "S1 E6 · $nowLeft", style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!wide) {
                        Spacer(Modifier.height(6.dp))
                        SocialPillButton("Join", {}, primary = true)
                    }
                }
                if (wide) SocialPillButton("Join", {}, primary = true, compact = false)
            }
        }
    }

    @Composable
    private fun Diary(wide: Boolean) {
        var lastMonth = ""
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            seen.forEach { item ->
                if (item.month != lastMonth) {
                    lastMonth = item.month
                    Text(if (item.month == "OCT") "October" else "September", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 12.dp)) {
                    Column(Modifier.width(if (wide) 56.dp else 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.day, style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(item.month, style = MaterialTheme.typography.labelSmall, color = muted(), fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.width(if (wide) 240.dp else 136.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) { Art(item.id, Modifier.matchParentSize()) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.line, style = MaterialTheme.typography.bodySmall, color = muted())
                        if (item.withYou) WithYouChip(Modifier.padding(top = 2.dp), solid = true)
                    }
                }
            }
        }
    }

    // --- history candidates (H1-H4) -------------------------------------------------------------

    private fun Seen.date(): String = "${day.trimStart('0')} ${month.lowercase().replaceFirstChar { it.uppercase() }}"

    @Composable
    private fun MonthHeader(month: String, count: Int, action: String? = null) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(monthNames.getValue(month), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (count == 1) "1 title" else "$count titles", style = MaterialTheme.typography.bodyMedium, color = muted(), modifier = Modifier.padding(bottom = 2.dp).weight(1f))
            if (action != null) {
                Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
            }
        }
    }

    /** The art tile H1, H2 and H4 share: the date on the art, title and what under it. */
    @Composable
    private fun HistoryTile(item: Seen, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                Art(item.id, Modifier.matchParentSize())
                SocialGlassChip(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Text(item.date(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (item.withYou) WithYouChip(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.line, style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun TileGrid(items: List<Seen>, columns: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { HistoryTile(it, Modifier.weight(1f)) }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun ShowEarlier(label: String) {
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            SocialPillButton(label, {}, compact = false)
        }
    }

    /** H1: a grid per month, newest first; older months load on demand. */
    @Composable
    private fun MonthGrid(wide: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            history.filter { it.month != "AUG" }.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size)
                TileGrid(items, if (wide) 3 else 2)
            }
            ShowEarlier("Show August")
        }
    }

    /** H2: one Home-style shelf per month, scrolling sideways; the page only grows by months. */
    @Composable
    private fun MonthShelves(wide: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            history.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size, action = "See all")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { HistoryTile(it, Modifier.width(if (wide) 250.dp else 200.dp)) }
                }
            }
        }
    }

    /** H3: each month opens on its latest title, large, then the rest small with their titles on the art. */
    @Composable
    private fun MonthMosaic(wide: Boolean) {
        val gap = 10.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            history.filter { it.month != "AUG" }.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size)
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (wide) 3 else 2
                    val small = (maxWidth - gap * (columns - 1)) / columns
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        if (wide) {
                            val bigWidth = small * 2 + gap
                            val bigHeight = bigWidth * 9f / 16f
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                MosaicTile(items[0], Modifier.width(bigWidth).height(bigHeight), big = true)
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    items.drop(1).take(2).forEach { MosaicTile(it, Modifier.width(small).height((bigHeight - gap) / 2)) }
                                }
                            }
                            items.drop(3).chunked(3).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    rowItems.forEach { MosaicTile(it, Modifier.width(small).height(small * 9f / 16f)) }
                                }
                            }
                        } else {
                            MosaicTile(items[0], Modifier.fillMaxWidth().aspectRatio(16f / 9f), big = true)
                            items.drop(1).chunked(2).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    rowItems.forEach { MosaicTile(it, Modifier.width(small).height(small * 9f / 16f)) }
                                }
                            }
                        }
                    }
                }
            }
            ShowEarlier("Show August")
        }
    }

    @Composable
    private fun MosaicTile(item: Seen, modifier: Modifier, big: Boolean = false) {
        Box(modifier.clip(RoundedCornerShape(if (big) 18.dp else 12.dp))) {
            Art(item.id, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            if (item.withYou) WithYouChip(Modifier.align(Alignment.TopEnd).padding(8.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(if (big) 16.dp else 10.dp)) {
                Text(
                    item.title,
                    color = Color.White,
                    style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.date()} · ${item.line}",
                    color = Color.White.copy(alpha = 0.75f),
                    style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }

    /** H4: no months; filter chips, one grid, dates on the art, more on scroll. */
    @Composable
    private fun FilteredGrid(wide: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All · 14", "Shows · 3", "Movies · 11", "With you · 3").forEachIndexed { index, label ->
                    val selected = index == 0
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(label, color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            TileGrid(history.take(if (wide) 12 else 10), if (wide) 3 else 2)
        }
    }

    // --- H2 variations (2026-10-24: H2 picked, three takes on it) ---------------------------------

    private val Seen.isSeries: Boolean get() = line.startsWith("S")

    /** H2a: the shelf of H2 with the title and date on the art, tiles a size up. */
    @Composable
    private fun ShelvesCaptioned(wide: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            history.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size, action = "See all")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { item ->
                        MosaicTile(item, Modifier.width(if (wide) 290.dp else 230.dp).aspectRatio(16f / 9f))
                    }
                }
            }
        }
    }

    /** H2b: H2's tiles; series drawn as a stack; each shelf ends in a "See all" card instead of a header link. */
    @Composable
    private fun ShelvesEndCard(wide: Boolean) {
        val tileWidth = if (wide) 250.dp else 200.dp
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            history.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size)
                // September is drawn scrolled to its end so the render shows the end card.
                Row(Modifier.horizontalScroll(rememberScrollState(if (month == "SEP") Int.MAX_VALUE else 0)), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { item -> StackedTile(item, Modifier.width(tileWidth)) }
                    if (items.size > 1) {
                        Box(
                            Modifier.width(tileWidth * 0.6f).padding(top = 6.dp).aspectRatio(0.6f * 16f / 9f / 0.6f)
                                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.ChevronRight, null)
                                }
                                Text("See all ${items.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    /** A series reads as several episodes: two thin cards peek out above its tile. */
    @Composable
    private fun StackedTile(item: Seen, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                if (item.isSeries) {
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).fillMaxWidth(0.84f).height(12.dp)
                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    )
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = (-3).dp).fillMaxWidth(0.92f).height(12.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
                    )
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                    Art(item.id, Modifier.matchParentSize())
                    SocialGlassChip(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Text(item.date(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    if (item.withYou) WithYouChip(Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.line, style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** H2c: each month's latest title leads its shelf at double width, title on the art; the rest as in H2. */
    @Composable
    private fun ShelvesLeadTile(wide: Boolean) {
        val tileWidth = if (wide) 250.dp else 200.dp
        val tileHeight = tileWidth * 9f / 16f
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            history.groupBy { it.month }.forEach { (month, items) ->
                MonthHeader(month, items.size, action = "See all")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val leadWidth = if (wide) tileWidth * 2 else tileWidth * 1.6f
                    // The lead's height matches a regular tile plus its two text lines, so the row stays level.
                    MosaicTile(items[0], Modifier.width(leadWidth).height(tileHeight + 48.dp), big = true)
                    items.drop(1).forEach { HistoryTile(it, Modifier.width(tileWidth)) }
                }
            }
        }
    }

    // --- phone placements of "You two" (2026-10-24) ----------------------------------------------

    /** One row standing in for the per-friend switches; opens [BetweenSheet]. */
    @Composable
    private fun BetweenRow() {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Visibility, null, Modifier.size(22.dp), tint = muted())
                Column(Modifier.weight(1f)) {
                    Text("Between you two", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Sees Now · history hidden · notifying you", style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = muted())
            }
        }
    }

    @Composable
    private fun BetweenSheet(modifier: Modifier) {
        Surface(modifier, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SocialAvatar(faisal.displayName, null, faisal.avatarColorHex, 40.dp)
                    Text("You and faisal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                BetweenYouTwo(card = false)
                HairLine()
                Text("Remove friend", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    /** Watched together as the first shelf, in H2a's style. */
    @Composable
    private fun TogetherShelf() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Watched together", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("3 titles", style = MaterialTheme.typography.bodyMedium, color = muted(), modifier = Modifier.padding(bottom = 2.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                together.forEach { item ->
                    Box(Modifier.width(230.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                        Art(item.id, Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(item.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (item.parties == 1) "1 party" else "${item.parties} parties", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PhoneTabs(selected: Int) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(4.dp),
        ) {
            listOf("History", "You two").forEachIndexed { index, label ->
                val on = index == selected
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (on) Color.White else Color.Transparent).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    private fun YouTwo() {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("You two", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigStat("14 h", "together", Modifier.weight(1f))
                    BigStat("4", "parties", Modifier.weight(1f))
                    BigStat("6", "in common", Modifier.weight(1f))
                }
                Text("WATCHED TOGETHER", style = MaterialTheme.typography.labelMedium, color = muted(), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    together.forEach { item ->
                        Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) { Art(item.id, Modifier.matchParentSize()) }
                    }
                }
                HairLine()
                BetweenYouTwo(card = false)
                HairLine()
                Text("Remove friend", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // --- privacy: P1 inline, P2 page + picker ---------------------------------------------------

    @Composable
    private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.AutoMirroredArrowBack, null)
                Text("Social settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }

    @Composable
    private fun InlineAudienceSettings(expanded: Boolean) {
        SettingsSection(title = "Privacy", isTablet = false) {
            SettingsGroup(isTablet = false) {
                AudienceRow(Icons.Rounded.Visibility, "Who sees what I'm watching", if (expanded) "All my friends, except ben and Mika" else "All my friends, except 2", expanded)
                if (expanded) AudienceOptions(selected = 1, picked = setOf("p-ben", "p-mika"))
                SettingsGroupDivider(isTablet = false)
                AudienceRow(Icons.Rounded.VideoLibrary, "Who sees what I've watched", "All my friends", false)
                SettingsGroupDivider(isTablet = false)
                AudienceRow(Icons.Rounded.Groups, "Who can join my parties", "Ask me first", false)
            }
        }
        SettingsSection(title = "Alerts", isTablet = false) {
            SettingsGroup(isTablet = false) {
                AudienceRow(Icons.Rounded.Notifications, "Tell me when friends start watching", "No one, except faisal and Seraph", false)
            }
        }
    }

    @Composable
    private fun AudienceRow(icon: ImageVector, title: String, value: String, expanded: Boolean) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight, null, tint = muted())
        }
    }

    private val audienceOptions = listOf("All my friends", "All my friends, except…", "No one, except…", "No one")

    @Composable
    private fun AudienceOptions(selected: Int, picked: Set<String>) {
        Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 14.dp)) {
            audienceOptions.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Radio(index == selected)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                if (index == selected && (index == 1 || index == 2)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SearchField("Search friends")
                            friends.forEach { friend -> FriendCheckRow(friend, friend.profileId in picked, size = 32.dp) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AudiencePage(wide: Boolean) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
            Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.AutoMirroredArrowBack, null)
                Text("Social settings", style = MaterialTheme.typography.titleMedium, color = muted())
            }
            Text("Who sees what I'm watching", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your Watching Now card on their Social tab and Home, and their pop-ups.", style = MaterialTheme.typography.bodyMedium, color = muted(), modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            val descriptions = listOf("Every friend, now and later", "Pick who doesn't see it", "Pick who does", "Nobody sees it")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                audienceOptions.forEachIndexed { index, label ->
                    val selected = index == 2
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(18.dp)) else Modifier),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Radio(selected)
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(descriptions[index], style = MaterialTheme.typography.bodySmall, color = muted())
                                }
                            }
                            if (selected) {
                                Row(Modifier.padding(start = 38.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FaceStack(friends.take(2), 30.dp)
                                    Text("faisal and Seraph", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    SocialPillButton("Edit", {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AudiencePicker(modifier: Modifier, wide: Boolean) {
        val shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Who can see what I'm watching", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("No one, except the friends you tick.", style = MaterialTheme.typography.bodyMedium, color = muted())
                SearchField("Search friends")
                Column {
                    friends.take(if (wide) 8 else 7).forEach { friend -> FriendCheckRow(friend, friend.profileId in setOf("p-faisal", "p-seraph"), size = 40.dp) }
                }
                SocialPillButton("Done · 2 friends", {}, Modifier.fillMaxWidth(), primary = true, compact = false)
            }
        }
    }

    @Composable
    private fun FriendCheckRow(friend: SocialProfileSummary, checked: Boolean, size: Dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SocialAvatar(friend.displayName, null, friend.avatarColorHex, size)
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("@${friend.handle}", style = MaterialTheme.typography.bodySmall, color = muted())
            }
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (checked) Color.White else Color.Transparent)
                    .border(2.dp, if (checked) Color.White else muted(), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color.Black)
            }
        }
    }

    @Composable
    private fun SearchField(hint: String) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = muted())
            Text(hint, color = muted(), style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun Radio(selected: Boolean) {
        Box(
            Modifier.size(24.dp).border(2.dp, if (selected) Color.White else muted(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        }
    }

    // --- shared pieces --------------------------------------------------------------------------

    /** The per-friend controls in the new model: each one reads (and edits) an audience list. */
    @Composable
    private fun BetweenYouTwo(card: Boolean) {
        val rows: @Composable () -> Unit = {
            ControlRow(Icons.Rounded.Visibility, "Sees what I'm watching", "All my friends, except 2", on = true)
            ControlRow(Icons.Rounded.VideoLibrary, "Sees what I've watched", "Hidden from faisal", on = false)
            ControlRow(Icons.Rounded.Notifications, "Tell me when faisal starts watching", "No one, except 2 · faisal is one", on = true)
        }
        if (card) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { rows() }
            }
        } else {
            Column { rows() }
        }
    }

    @Composable
    private fun ControlRow(icon: ImageVector, title: String, sub: String, on: Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = muted())
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = on, onCheckedChange = {})
        }
    }

    @Composable
    private fun Shelf(title: String, wide: Boolean, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }

    @Composable
    private fun StillTile(id: String, title: String, meta: String, width: Dp, withYou: Boolean = false) {
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                Art(id, Modifier.matchParentSize())
                if (withYou) WithYouChip(Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun WithYouChip(modifier: Modifier, solid: Boolean = false) {
        if (solid) {
            Row(
                modifier.clip(RoundedCornerShape(999.dp)).background(SocialLiveColor.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                FaceStack(listOf(faisal), 14.dp)
                Text("Watched with you", color = SocialLiveColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        } else {
            SocialGlassChip(modifier) { Text("With you", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    private fun StatPill(value: String, label: String) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = muted())
        }
    }

    @Composable
    private fun BigStat(value: String, label: String, modifier: Modifier) {
        Column(
            modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(14.dp),
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = muted(), maxLines = 1)
        }
    }

    @Composable
    private fun LiveTag(text: String, onArt: Boolean = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(SocialLiveColor))
            Text(text, color = if (onArt) Color.White else SocialLiveColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }

    @Composable
    private fun LiveAvatar(friend: SocialProfileSummary, size: Dp, live: Boolean) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (live) Box(Modifier.fillMaxSize().border(3.dp, SocialLiveColor, CircleShape))
            SocialAvatar(friend.displayName, null, friend.avatarColorHex, size * 0.88f)
        }
    }

    @Composable
    private fun FaceStack(people: List<SocialProfileSummary>, size: Dp) {
        Box(Modifier.width(size + size * 0.7f * (people.size - 1))) {
            people.forEachIndexed { index, person ->
                Box(Modifier.padding(start = size * 0.7f * index)) { SocialAvatar(person.displayName, null, person.avatarColorHex, size) }
            }
        }
    }

    @Composable
    private fun ProgressBar(fraction: Float, thin: Boolean = false) {
        val h = if (thin) 3.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxWidth(fraction).height(h).background(SocialLiveColor))
        }
    }

    @Composable
    private fun HairLine() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
    }

    @Composable
    private fun TopBar(modifier: Modifier, glass: Boolean = true) {
        Row(modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (glass) GlassIcon(Icons.AutoMirroredArrowBack) else Icon(Icons.AutoMirroredArrowBack, null)
            Spacer(Modifier.weight(1f))
            if (glass) GlassIcon(Icons.Rounded.MoreVert) else Icon(Icons.Rounded.MoreVert, null)
        }
    }

    @Composable
    private fun GlassIcon(icon: ImageVector, modifier: Modifier = Modifier) {
        Box(modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
        }
    }

    @Composable
    private fun muted(): Color = MaterialTheme.colorScheme.onSurfaceVariant

    private val Icons.AutoMirroredArrowBack: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack

    /** The desktop app's chrome: a nav sidebar with Social selected, the content beside it. */
    @Composable
    private fun DesktopShell(content: @Composable () -> Unit) {
        Row(Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
            Column(Modifier.width(220.dp).fillMaxHeight().padding(horizontal = 14.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Home", "Search", "Library", "Social", "Downloads", "Settings").forEach { label ->
                    val selected = label == "Social"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(if (label == "Social") Icons.Rounded.Groups else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = if (selected) 1f else 0.5f))
                        Text(label, color = Color.White.copy(alpha = if (selected) 1f else 0.6f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }

    /** The Social tab, dimmed, behind a panel or card. */
    @Composable
    private fun Dimmed(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Phones open these over a full-screen tab; the desktop tiles would only overflow there.
            if (maxWidth >= 600.dp) Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("tt0149460", "tt2278388").forEach { Box(Modifier.width(380.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))) { Art(it, Modifier.matchParentSize()) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("tt0914798", "tt0329938", "tt0430651").forEach { Box(Modifier.width(250.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) { Art(it, Modifier.matchParentSize()) } }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)))
            content()
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.SidePanel(content: @Composable () -> Unit) {
        Surface(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(560.dp),
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
        }
    }

    @Composable
    private fun Art(id: String, modifier: Modifier) {
        val bitmap = loadArt(id)
        if (bitmap != null) {
            Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
            return
        }
        val hue = ((id.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
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

    private fun renderScene(name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, content: @Composable () -> Unit) {
        runCatching {
            val scene = ImageComposeScene(width = widthDp * 2, height = heightDp * 2, density = Density(2f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = 1f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density, 1f),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
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
}
