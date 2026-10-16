package com.nuvio.app.features.social

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Social settings surface, tray pop-ups and the close-to-tray prompt (2026-10-16): **candidates to
 * pick from before any of it is built** (plan "Scope added 2026-10-16").
 *
 * - **Settings entry:** A, a gear beside Inbox; B, tapping your own identity block (your own profile,
 *   the mirror of a friend's profile sheet). Either way the Settings tab keeps one "Social" row that
 *   opens the same page, because the master switch must stay reachable while the tab is hidden.
 * - **Settings form on desktop:** a full page like Inbox, vs a right-hand panel over the dashboard.
 * - **Pop-ups:** A, a compact Steam-like strip; B, a backdrop card in the Watching Now family; C, the
 *   compact strip with the actions inline. Each for the four kinds that pop, plus placement on a
 *   1920x1080 desktop and the in-player variant.
 * - **Close prompt:** A, a plain dialog; B, the same with a picture of what hiding means.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialSettingsTrayHarness" --rerun
 * # PNGs land in composeApp/build/social-settings-tray/
 * ```
 *
 * No product code. Real backdrops come from `build/social-design-art/` when present.
 */
class SocialSettingsTrayHarness {

    private val outputDir = File("build/social-settings-tray")
    private val artDir = File("build/social-design-art")
    private val nowMs = parseSocialTimestampMs("2026-10-16T12:00:00Z")!!

    private fun profile(name: String, handle: String, hex: String) = SocialProfileSummary(
        profileId = "p-$handle", handle = handle, displayName = name, avatarColorHex = hex, isFriend = true,
    )

    private val me = profile("big z", "zokaper", "#1E88E5")
    private val faye = profile("faye", "faye", "#E0527A")
    private val seraph = profile("Seraph", "seraph", "#8E6CF0")
    private val ben = profile("ben", "ben", "#E8833A")
    private val ana = profile("Ana", "ana", "#2BB38A")
    private val longName = profile("A Friend With A Very Long Display Name", "longname", "#4FB0C6")

    private fun watching(who: SocialProfileSummary, id: String, title: String, season: Int? = null, episode: Int? = null) =
        WatchingNowItem(
            profile = who, contentId = id, contentType = if (season == null) "movie" else "series",
            videoId = if (season == null) id else "$id:$season:$episode", title = title, season = season, episode = episode,
            sessionId = "s-${who.handle}", positionMs = 900_000, durationMs = 2_700_000,
            effectiveJoinPolicy = WatchJoinPolicy.approval, state = SocialPlaybackState.playing,
            heartbeatAt = "2026-10-16T12:00:00Z",
        )

    private val feedState = SocialUiState(
        capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true, partyContractVersion = 2),
        socialV2Backend = true,
        activeProfileId = me.profileId,
        me = me,
        friends = listOf(faye, seraph, ben, ana),
        watchingNow = listOf(
            watching(seraph, "tt5675620", "The Punisher", 1, 5),
            watching(faye, "tt0914798", "The Boy in the Striped Pajamas"),
        ),
        activity = listOf(
            RecentActivityRun(
                runId = "r1", profile = ana, contentId = "tt0329938", contentType = "series", videoId = "tt0329938:1:5",
                title = "Transformers: Armada", season = 1, episode = 5, eventCount = 5,
                firstEventTime = "2026-10-16T07:00:00Z", lastEventTime = "2026-10-16T07:00:00Z",
            ),
            RecentActivityRun(
                runId = "r2", profile = ben, contentId = "tt7282468", contentType = "movie", videoId = "tt7282468",
                title = "Burning", firstEventTime = "2026-10-15T20:00:00Z", lastEventTime = "2026-10-15T20:00:00Z",
            ),
            RecentActivityRun(
                runId = "r3", profile = faye, contentId = "tt2278388", contentType = "movie", videoId = "tt2278388",
                title = "The Grand Budapest Hotel", firstEventTime = "2026-10-14T20:00:00Z", lastEventTime = "2026-10-14T20:00:00Z",
            ),
        ),
    )

    // --- the renders -------------------------------------------------------------------------

    @Test
    fun renderSettingsSurface() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        // Entry points: the header row only, at each width, both candidates on one sheet.
        renderScene("entry-points", 1280, 560, failures) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (variant in listOf("A", "B")) {
                    Caption(if (variant == "A") "A - gear beside Inbox" else "B - tap your own identity (your profile + settings)")
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                        Frame(411.dp) { HeaderPrototype(variant, wide = false) }
                        Frame(320.dp) { HeaderPrototype(variant, wide = false) }
                    }
                    Frame(1232.dp) { HeaderPrototype(variant, wide = true) }
                }
            }
        }

        // The page itself on phones: tall frames show the whole page; 320x600 shows the first screen.
        for ((w, h) in listOf(411 to 1960, 320 to 2140, 320 to 600)) {
            renderScene("settings-phone-${w}x$h", w, h, failures) {
                SettingsPage(wide = false, desktop = false, onBackLabel = true)
            }
        }
        // Desktop shows the desktop-only sections (pop-ups, background); same page, desktop rows.
        for ((w, h) in listOf(1280 to 820, 1920 to 1080)) {
            renderScene("settings-desktop-page-${w}x$h", w, h, failures) {
                SettingsPage(wide = true, desktop = true, onBackLabel = true)
            }
            renderScene("settings-desktop-panel-${w}x$h", w, h, failures) {
                Box(Modifier.fillMaxSize()) {
                    DashboardBackground()
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    Surface(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp)
                            .padding(top = 12.dp, bottom = 12.dp, end = 12.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            SettingsPage(wide = false, desktop = true, onBackLabel = false, panel = true)
                        }
                    }
                }
            }
        }
        // The second door: Settings keeps one row that opens the same page.
        renderScene("settings-tab-door", 760, 300, failures) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Caption("Settings tab -> Profile & social keeps one row; it opens the same page")
                Spacer(Modifier.height(10.dp))
                SettingsSection(title = "PROFILE & SOCIAL", isTablet = true) {
                    SettingsGroup(isTablet = true) {
                        SettingsNavigationRow(title = "Profiles", description = "Switch, add or edit profiles", isTablet = true, onClick = {})
                        SettingsGroupDivider(isTablet = true)
                        SettingsNavigationRow(
                            title = "Social",
                            description = "On - privacy, pop-ups and the tray",
                            isTablet = true,
                            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = {},
                        )
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun renderPopups() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val kinds = PopupKind.entries
        for (style in PopupStyle.entries) {
            renderScene("popups-${style.name}", 860, 120 + kinds.size * 190, failures) {
                Column(Modifier.fillMaxSize().background(WallpaperBrush).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Caption(style.caption, color = Color.White)
                    kinds.forEach { kind ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Popup(style, kind)
                            Text(kind.note, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(380.dp))
                        }
                    }
                }
            }
            // Where it sits: bottom-right above the taskbar, newest at the bottom, at most three.
            renderScene("popup-placement-${style.name}-1920x1080", 1920, 1080, failures, density = 1f) {
                Box(Modifier.fillMaxSize().background(WallpaperBrush)) {
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 48.dp + 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Popup(style, PopupKind.Invite)
                        Popup(style, PopupKind.Watching)
                    }
                    Taskbar(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        // While you watch: drawn inside the player, top-right, smaller and quieter.
        renderScene("popup-in-player-1920x1080", 1920, 1080, failures, density = 1f) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                art("tt0149460")?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Popup(PopupStyle.A, PopupKind.Watching, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp), inPlayer = true)
            }
        }
        renderScene("popup-in-player-D1-1920x1080", 1920, 1080, failures, density = 1f) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                art("tt0149460")?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Popup(PopupStyle.D1, PopupKind.Invite, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp))
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun renderClosePrompt() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val variants = listOf("A" to PopupStyle.D1) + listOf(PopupStyle.D1, PopupStyle.D2, PopupStyle.D3).map { "B" to it }
        for ((variant, picture) in variants) {
            val name = if (variant == "A") "close-prompt-A-1280x820" else "close-prompt-B-${picture.name}-1280x820"
            renderScene(name, 1280, 820, failures) {
                Box(Modifier.fillMaxSize()) {
                    DashboardBackground()
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                    ClosePrompt(variant, Modifier.align(Alignment.Center), picture)
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- settings prototypes -----------------------------------------------------------------

    @Composable
    private fun HeaderPrototype(variant: String, wide: Boolean) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SocialAvatar(me.displayName, null, me.avatarColorHex, if (wide) 52.dp else 42.dp)
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            me.displayName,
                            style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (variant == "B") Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "@${me.handle} · 4 friends",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (wide) SocialPillButton("Invite code", {})
            SocialInboxButton(2, {})
            if (variant == "A") {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Settings, "Social settings", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }

    /**
     * The one page. [desktop] adds Pop-ups and Background; phones get Notifications instead. [wide]
     * lays the sections in two columns, which is what a full desktop page needs to not be a strip.
     */
    @Composable
    private fun SettingsPage(wide: Boolean, desktop: Boolean, onBackLabel: Boolean, panel: Boolean = false) {
        Column(Modifier.fillMaxSize().background(if (panel) Color.Transparent else MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (panel) 20.dp else 4.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBackLabel) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Text("Social settings", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (panel) Icon(Icons.Rounded.Close, "Close")
            }
            val horizontal = if (wide) 28.dp else if (panel) 20.dp else 16.dp
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        YouSection(true); PrivacySection(true); HomeSection(true)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        PopupsSection(true); BackgroundSection(true); TurnOffSection(true)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = horizontal), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    YouSection(false)
                    PrivacySection(false)
                    if (desktop) {
                        PopupsSection(false); BackgroundSection(false)
                    } else {
                        PhoneNotificationsSection()
                    }
                    HomeSection(false)
                    TurnOffSection(false)
                }
            }
        }
    }

    @Composable
    private fun YouSection(t: Boolean) = SettingsSection("YOU", isTablet = t) {
        SettingsGroup(isTablet = t) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SocialAvatar(me.displayName, null, me.avatarColorHex, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(me.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("@${me.handle} · 4 friends", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SocialPillButton("Change handle", {})
            }
        }
    }

    @Composable
    private fun PrivacySection(t: Boolean) = SettingsSection("PRIVACY", isTablet = t) {
        SettingsGroup(isTablet = t) {
            SettingsSwitchRow("Share what I'm watching", "Friends see you in Watching Now", checked = true, isTablet = t, onCheckedChange = {})
            SettingsGroupDivider(t)
            SettingsSwitchRow("Share what I've watched", "Friends see your Recently watched", checked = true, isTablet = t, onCheckedChange = {})
            SettingsGroupDivider(t)
            ChoiceRow("Who can join you", listOf("Anyone", "Ask first", "No one"), selected = 1)
            SettingsGroupDivider(t)
            SettingsNavigationRow(
                title = "Hidden from", description = "faye, ben - change on a friend's profile", isTablet = t,
                trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = {},
            )
        }
    }

    @Composable
    private fun PopupsSection(t: Boolean) = SettingsSection("POP-UPS", isTablet = t) {
        SettingsGroup(isTablet = t) {
            SettingsSwitchRow("Show pop-ups", "Small cards in the corner of your screen", checked = true, isTablet = t, onCheckedChange = {})
            SettingsGroupDivider(t)
            SettingsSwitchRow("Friend requests", checked = true, isTablet = t, onCheckedChange = {})
            SettingsGroupDivider(t)
            SettingsSwitchRow("Party invites and join requests", checked = true, isTablet = t, onCheckedChange = {})
            SettingsGroupDivider(t)
            SettingsSwitchRow(
                "Friends starting to watch", "Only friends you've turned on, from their profile (2)",
                checked = true, isTablet = t, onCheckedChange = {},
            )
            SettingsGroupDivider(t)
            SettingsSwitchRow("Over fullscreen apps and games", "Off: they wait in your Inbox", checked = false, isTablet = t, onCheckedChange = {})
        }
    }

    @Composable
    private fun BackgroundSection(t: Boolean) = SettingsSection("IN THE BACKGROUND", isTablet = t) {
        SettingsGroup(isTablet = t) {
            ChoiceRow("Closing the window", listOf("Hide to tray", "Quit Nuvio"), selected = 0)
            SettingsGroupDivider(t)
            SettingsSwitchRow(
                "Start with Windows", "Opens hidden in the tray, about 150 MB",
                checked = false, isTablet = t, onCheckedChange = {},
            )
        }
    }

    @Composable
    private fun PhoneNotificationsSection() = SettingsSection("NOTIFICATIONS", isTablet = false) {
        SettingsGroup(isTablet = false) {
            SettingsSwitchRow("Friend requests", checked = true, isTablet = false, onCheckedChange = {})
            SettingsGroupDivider(false)
            SettingsSwitchRow("Party invites and join requests", checked = true, isTablet = false, onCheckedChange = {})
            SettingsGroupDivider(false)
            SettingsSwitchRow("Recommendations", checked = true, isTablet = false, onCheckedChange = {})
            SettingsGroupDivider(false)
            SettingsNavigationRow(
                title = "Friends starting to watch", description = "In your Inbox only - turn on per friend, from their profile (2)",
                isTablet = false, onClick = {},
            )
        }
    }

    @Composable
    private fun HomeSection(t: Boolean) = SettingsSection("HOME", isTablet = t) {
        SettingsGroup(isTablet = t) {
            SettingsSwitchRow("Show friends on Home", "Watching now and recent, in one shelf", checked = true, isTablet = t, onCheckedChange = {})
        }
    }

    @Composable
    private fun TurnOffSection(t: Boolean) = SettingsGroup(isTablet = t) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Turn off Social", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "Hides the Social tab and stops sharing. Turn it back on from Settings.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** Two or three values that all fit: chips beat a row that cycles on tap. */
    @Composable
    private fun ChoiceRow(title: String, options: List<String>, selected: Int) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(3.dp),
            ) {
                options.forEachIndexed { index, option ->
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (index == selected) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            option, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                            color = if (index == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    // --- pop-up prototypes -------------------------------------------------------------------

    private enum class PopupStyle(val caption: String) {
        A("A - compact strip (Steam-like): click opens Nuvio on the right sheet"),
        B("B - backdrop card, the Watching Now family: click opens Nuvio"),
        C("C - compact strip with the actions inline"),
        D1("D1 - artwork card, buttons under the text"),
        D2("D2 - artwork card, same height as B, buttons bottom-right"),
        D3("D3 - artwork banner on top, who + buttons underneath"),
    }

    private enum class PopupKind(val note: String) {
        Watching("Friend started watching - only for friends with Notify turned on"),
        Request("Friend request"),
        Invite("Party invite"),
        JoinRequest("Someone asks to join your playback"),
    }

    private data class PopupCopy(val who: SocialProfileSummary, val line: String, val title: String, val meta: String?, val art: String?, val actions: List<String>)

    private fun copyFor(kind: PopupKind) = when (kind) {
        PopupKind.Watching -> PopupCopy(seraph, "is watching", "The Punisher", "S1 E5", "tt5675620", listOf("Ask to join"))
        PopupKind.Request -> PopupCopy(longName, "wants to be friends", "@longname", null, null, listOf("Accept", "Decline"))
        PopupKind.Invite -> PopupCopy(ben, "invited you to watch", "Burning", "Watch party · 3 watching", "tt7282468", listOf("Join"))
        PopupKind.JoinRequest -> PopupCopy(ana, "asked to join", "Futurama", "Your playback · S7 E1", "tt0149460", listOf("Accept", "Decline"))
    }

    @Composable
    private fun Popup(style: PopupStyle, kind: PopupKind, modifier: Modifier = Modifier, inPlayer: Boolean = false) {
        val c = copyFor(kind)
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            when (style) {
                PopupStyle.A, PopupStyle.C -> Row(
                    modifier.width(if (inPlayer) 330.dp else 360.dp).height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF17181C).copy(alpha = if (inPlayer) 0.88f else 0.97f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(SocialLiveColor))
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SocialFaces(listOf(c.who), 44.dp, liveRing = kind == PopupKind.Watching)
                            Column(Modifier.weight(1f)) {
                                Text(c.who.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.line, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                                Text(
                                    listOfNotNull(c.title, c.meta).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = SocialLiveColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            c.art?.let { id ->
                                art(id)?.let {
                                    Image(it, null, Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                }
                            }
                        }
                        if (style == PopupStyle.C && c.actions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 56.dp)) {
                                c.actions.forEachIndexed { i, a -> DarkPill(a, primary = i == 0) }
                            }
                        }
                    }
                }
                PopupStyle.B -> Box(
                    modifier.width(380.dp).height(132.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF17181C)),
                ) {
                    val bitmap = c.art?.let(::art)
                    if (bitmap != null) {
                        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A2B5C), Color(0xFF14121C)))))
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                        ),
                    )
                    Row(
                        Modifier.align(Alignment.CenterStart).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SocialFaces(listOf(c.who), 46.dp, liveRing = kind == PopupKind.Watching)
                        Column(Modifier.widthIn(max = 250.dp)) {
                            // The name gives way, never the verb: "A Friend With A Ve... wants to be friends".
                            Row {
                                Text(
                                    c.who.displayName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(" ${c.line}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
                            }
                            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            c.meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
                        }
                    }
                    Text(
                        "NUVIO", Modifier.align(Alignment.TopEnd).padding(10.dp),
                        fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.55f),
                    )
                }
                PopupStyle.D1, PopupStyle.D2 -> Box(
                    modifier.width(380.dp).then(if (style == PopupStyle.D2) Modifier.height(132.dp) else Modifier.height(IntrinsicSize.Min))
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF17181C)),
                ) {
                    PopupArt(c, Modifier.matchParentSize())
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.1f))),
                        ),
                    )
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SocialFaces(listOf(c.who), 46.dp, liveRing = kind == PopupKind.Watching)
                            PopupText(c, Modifier.widthIn(max = 250.dp))
                        }
                        if (style == PopupStyle.D1) {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.padding(start = 58.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                c.actions.forEachIndexed { i, a -> DarkPill(a, primary = i == 0) }
                            }
                        }
                    }
                    if (style == PopupStyle.D2) {
                        Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            c.actions.reversed().forEach { a -> DarkPill(a, primary = a == c.actions.first()) }
                        }
                    }
                    NuvioMark(Modifier.align(Alignment.TopEnd).padding(10.dp))
                }
                PopupStyle.D3 -> Column(
                    modifier.width(360.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF17181C))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                ) {
                    Box(Modifier.fillMaxWidth().height(116.dp)) {
                        PopupArt(c, Modifier.matchParentSize())
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.85f))),
                            ),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            c.meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                        }
                        NuvioMark(Modifier.align(Alignment.TopEnd).padding(10.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SocialFaces(listOf(c.who), 34.dp, liveRing = kind == PopupKind.Watching)
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.who.displayName, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(c.line, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        c.actions.forEachIndexed { i, a -> DarkPill(a, primary = i == 0, small = true) }
                    }
                }
            }
        }
    }

    /** The title's backdrop, or for a friend request (no title) a wash of the friend's own colour. */
    @Composable
    private fun PopupArt(c: PopupCopy, modifier: Modifier) {
        val bitmap = c.art?.let(::art)
        if (bitmap != null) {
            Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
        } else {
            val tint = c.who.avatarColorHex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color(0xFF3A2B5C)
            Box(modifier.background(Brush.linearGradient(listOf(Color.Black, tint.copy(alpha = 0.55f), tint))))
        }
    }

    @Composable
    private fun PopupText(c: PopupCopy, modifier: Modifier) {
        Column(modifier) {
            // The name gives way, never the verb: "A Friend With A Ve... wants to be friends".
            Row {
                Text(
                    c.who.displayName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(" ${c.line}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
            }
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
        }
    }

    @Composable
    private fun NuvioMark(modifier: Modifier) {
        Text("NUVIO", modifier, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.55f))
    }

    @Composable
    private fun DarkPill(text: String, primary: Boolean, small: Boolean = false) {
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
                .padding(horizontal = if (small) 11.dp else 14.dp, vertical = if (small) 5.dp else 6.dp),
        ) {
            Text(text, color = if (primary) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun Taskbar(modifier: Modifier) {
        Row(
            modifier.fillMaxWidth().height(48.dp).background(Color(0xFF1C1C22).copy(alpha = 0.92f)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            repeat(5) { Box(Modifier.padding(horizontal = 6.dp).size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f))) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(18.dp).clip(CircleShape).background(SocialLiveColor.copy(alpha = 0.9f)))
            Spacer(Modifier.width(12.dp))
            Text("12:00", color = Color.White, fontSize = 12.sp)
        }
    }

    // --- close prompt ------------------------------------------------------------------------

    @Composable
    private fun ClosePrompt(variant: String, modifier: Modifier, pictureStyle: PopupStyle = PopupStyle.D1) {
        Surface(modifier.width(440.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (variant == "B") {
                        // A miniature desktop corner: the pop-up sits wholly inside, above a taskbar whose tray
                        // shows the Nuvio dot. Scaled from its bottom-right corner so nothing crosses the frame.
                        Box(
                            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(WallpaperBrush),
                        ) {
                            Popup(
                                pictureStyle, PopupKind.Watching,
                                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 22.dp + 12.dp)
                                    .wrapContentSize(Alignment.BottomEnd, unbounded = true)
                                    .graphicsLayer { scaleX = 0.72f; scaleY = 0.72f; transformOrigin = TransformOrigin(1f, 1f) },
                            )
                            Row(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp).background(Color(0xFF1C1C22)).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(SocialLiveColor))
                                Text("12:00", color = Color.White, fontSize = 8.sp)
                            }
                        }
                    }
                    Text("Keep Nuvio in the tray?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Closing the window hides Nuvio next to the clock, so friends' pop-ups can still reach you. " +
                            "While hidden it uses about as much memory as a browser tab.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "You can change this any time in Social settings.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        SocialPillButton("Quit Nuvio", {}, compact = false)
                        SocialPillButton("Hide to tray", {}, primary = true, compact = false)
                    }
                }
            }
        }
    }

    // --- shared ------------------------------------------------------------------------------

    @Composable
    private fun DashboardBackground() {
        SocialFeed(
            model = SocialFeedModel(
                state = feedState,
                activityGroups = groupFriendActivity(feedState.activity),
                activityTimeline = friendActivityTimeline(feedState.activity, nowMs),
                activityNowMs = nowMs,
                joinAffordance = { watchingNowJoinAffordance(it, OutgoingJoinRequestState.Idle, null) },
            ),
            actions = SocialFeedActions(),
            listState = rememberLazyListState(),
        )
    }

    @Composable
    private fun Frame(width: Dp, content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.width(width).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            content = content,
        )
    }

    @Composable
    private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }

    private val artCache = mutableMapOf<String, ImageBitmap?>()
    private fun art(contentId: String): ImageBitmap? = artCache.getOrPut(contentId) {
        listOf("jpg", "webp", "png").map { File(artDir, "$contentId.$it") }.firstOrNull(File::isFile)?.let { file ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }

    private fun renderScene(
        name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, density: Float = 2f,
        content: @Composable () -> Unit,
    ) {
        runCatching {
            val scene = ImageComposeScene(width = (widthDp * density).toInt(), height = (heightDp * density).toInt(), density = Density(density)) {
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
        val WallpaperBrush = Brush.linearGradient(listOf(Color(0xFF243B55), Color(0xFF141E30), Color(0xFF3A1C4A)))
    }
}
