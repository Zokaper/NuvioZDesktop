package com.nuvio.app.features.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The Watching Now join sheet (2026-10-18): **three candidates that put the title's art first**,
 * after the maintainer found the shipped sheet (a 124dp still beside the text, two full-width pills
 * under it) button-heavy with the art an afterthought.
 *
 * - **A, banner**: the backdrop full-bleed across the top of the sheet, live chip and progress on
 *   it; who, title and meta under the art.
 * - **B, art caption**: the same banner, with who, title and meta laid on the art under a scrim; the
 *   buttons sit side by side.
 * - **C, immersive**: the backdrop fills the sheet and fades into it; everything sits on the fade.
 *
 * Real backdrops from `build/social-design-art/` (see [SocialActivityRedesignHarness]).
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialJoinSheetHarness" --rerun
 * # PNGs land in composeApp/build/social-join-sheet/
 * ```
 *
 * No product code: the candidates use the real components, models and wording.
 */
class SocialJoinSheetHarness {

    private val outputDir = File("build/social-join-sheet")
    private val artDir = File("build/social-design-art")

    private val seraph = SocialProfileSummary(profileId = "p-seraph", handle = "seraph", displayName = "Seraph", avatarColorHex = "#8E6CF0", isFriend = true)
    private val ben = SocialProfileSummary(profileId = "p-ben", handle = "ben", displayName = "ben", avatarColorHex = "#E8833A", isFriend = true)

    private val punisher = WatchingNowItem(
        profile = seraph, contentId = "tt5675620", contentType = "series", videoId = "tt5675620:1:6",
        title = "The Punisher", season = 1, episode = 6, episodeTitle = "The Judas Goat", sessionId = "s-seraph",
        positionMs = 1_300_000, durationMs = 3_000_000, effectiveJoinPolicy = WatchJoinPolicy.direct,
        state = SocialPlaybackState.playing, heartbeatAt = "2026-10-18T12:00:00Z",
    )
    private val obsession = WatchingNowItem(
        profile = ben, contentId = "tt37287335", contentType = "movie", videoId = "tt37287335",
        title = "Obsession", sessionId = "s-ben", positionMs = 2_400_000, durationMs = 6_000_000,
        effectiveJoinPolicy = WatchJoinPolicy.approval, state = SocialPlaybackState.paused,
        heartbeatAt = "2026-10-18T12:00:00Z",
    )

    @Test
    fun renderJoinSheetCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for (variant in listOf("A", "B", "C")) {
            renderScene("desktop-$variant-join", 1280, 820, failures) {
                DesktopDialog { Sheet(variant, punisher, WatchingNowJoinAffordance.Join, wide = true) }
            }
            renderScene("desktop-$variant-ask", 1280, 820, failures) {
                DesktopDialog { Sheet(variant, obsession, WatchingNowJoinAffordance.AskToJoin, wide = true) }
            }
            renderScene("phone-$variant-join", 411, 914, failures) {
                PhoneSheet { Sheet(variant, punisher, WatchingNowJoinAffordance.Join, wide = false) }
            }
            renderScene("phone-$variant-ask", 360, 780, failures) {
                PhoneSheet { Sheet(variant, obsession, WatchingNowJoinAffordance.AskToJoin, wide = false) }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- in-player social card on the phone (2026-10-18: "mildly ugly") ---------------------------

    private class InPlayerCase(val name: String, val item: WatchingNowItem, val line: String, val actions: List<String>)

    private val inPlayerCases = listOf(
        InPlayerCase("watching", punisher, "started watching", emptyList()),
        InPlayerCase("request", obsession, "asked to join you", listOf("Accept", "Decline")),
    )

    @Test
    fun renderInPlayerCardCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for (variant in listOf("now", "A", "B")) {
            for (case in inPlayerCases) {
                renderScene("player-$variant-${case.name}-891x411", 891, 411, failures) { PlayerFrame(landscape = true) { InPlayerCard(variant, case, landscape = true) } }
                renderScene("player-$variant-${case.name}-411x914", 411, 914, failures) { PlayerFrame(landscape = false) { InPlayerCard(variant, case, landscape = false) } }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** A video frame (Burning's backdrop, so it is never the card's own art) and the card's real slot. */
    @Composable
    private fun PlayerFrame(landscape: Boolean, card: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val video = WatchingNowItem(
                profile = ben, contentId = "tt7282468", contentType = "movie", videoId = "tt7282468", title = "Burning",
                positionMs = 0, durationMs = 1, state = SocialPlaybackState.playing, heartbeatAt = "",
            )
            Art(video, if (landscape) Modifier.fillMaxSize() else Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f))
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 64.dp else 96.dp, start = 16.dp, end = 16.dp)
                    .width(if (landscape) 520.dp else 411.dp),
                contentAlignment = Alignment.TopCenter,
            ) { card() }
        }
    }

    @Composable
    private fun InPlayerCard(variant: String, case: InPlayerCase, landscape: Boolean) {
        when (variant) {
            "now" -> CurrentInPlayerCard(case)
            "A" -> BannerInPlayerCard(case, landscape)
            else -> HudInPlayerCard(case, landscape)
        }
    }

    /** As shipped in MobilePartyOverlays.SocialNotificationCard. */
    @Composable
    private fun CurrentInPlayerCard(case: InPlayerCase) {
        Surface(color = Color(0xFF2A2A2E).copy(alpha = 0.96f), contentColor = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (case.actions.isEmpty()) "${case.item.profile.displayName} is watching ${case.item.title}" + (case.item.season?.let { " · S$it E${case.item.episode}" } ?: "")
                    else "${case.item.profile.displayName} ${case.line}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                )
                if (case.actions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (case.actions + "Dismiss").forEachIndexed { i, label ->
                            Text(
                                label, color = if (i == 0) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == 0) Color.White else Color.White.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The phone's in-app banner, as the desktop's in-player card is its pop-up: art, face, two lines. */
    @Composable
    private fun BannerInPlayerCard(case: InPlayerCase, landscape: Boolean) {
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF17181C))) {
            Box(Modifier.fillMaxWidth().height(76.dp)) {
                Art(case.item, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.05f)))))
                Row(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SocialFaces(listOf(case.item.profile), 40.dp, liveRing = case.actions.isEmpty())
                    Column(Modifier.weight(1f)) {
                        Text("${case.item.profile.displayName} ${case.line}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(inPlayerTitle(case), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (landscape && case.actions.isNotEmpty()) InPlayerActions(case.actions)
                }
            }
            if (!landscape && case.actions.isNotEmpty()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { InPlayerActions(case.actions, fill = true) }
            }
        }
    }

    /** Player chrome rather than a card: the gesture pill's glass, a small still, a face, two lines. */
    @Composable
    private fun HudInPlayerCard(case: InPlayerCase, landscape: Boolean) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(18.dp, 8.dp, 8.dp, 18.dp))) { Art(case.item, Modifier.matchParentSize()) }
                SocialFaces(listOf(case.item.profile), 26.dp, liveRing = case.actions.isEmpty())
                Column(Modifier.weight(1f, fill = false).padding(end = if (landscape && case.actions.isNotEmpty()) 4.dp else 12.dp)) {
                    Text("${case.item.profile.displayName} ${case.line}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(inPlayerTitle(case), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (landscape && case.actions.isNotEmpty()) InPlayerActions(case.actions)
            }
            if (!landscape && case.actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { InPlayerActions(case.actions, fill = true) }
            }
        }
    }

    private fun inPlayerTitle(case: InPlayerCase): String =
        listOfNotNull(case.item.title, case.item.season?.let { "S$it E${case.item.episode}" }).joinToString(" · ")

    @Composable
    private fun RowScope.InPlayerActions(actions: List<String>, fill: Boolean = false) {
        actions.forEachIndexed { i, label ->
            Text(
                label,
                color = if (i == 0) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = (if (fill) Modifier.weight(1f) else Modifier).clip(CircleShape)
                    .background(if (i == 0) Color.White else Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    // --- the frames: the app's own dialog and bottom-sheet shapes ---------------------------------

    @Composable
    private fun DesktopDialog(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(
                modifier = Modifier.align(Alignment.Center).width(560.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
            }
        }
    }

    @Composable
    private fun PhoneSheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Box {
                        content()
                        // The drag handle sits over whatever the sheet starts with.
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape).background(Color.White.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
        }
    }

    // --- the candidates -------------------------------------------------------------------------

    @Composable
    private fun Sheet(variant: String, item: WatchingNowItem, affordance: WatchingNowJoinAffordance, wide: Boolean) {
        when (variant) {
            "A" -> Banner(item, affordance, wide)
            "B" -> ArtCaption(item, affordance, wide)
            else -> Immersive(item, affordance, wide)
        }
    }

    @Composable
    private fun Banner(item: WatchingNowItem, affordance: WatchingNowJoinAffordance, wide: Boolean) {
        val pad = if (wide) 24.dp else 20.dp
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Art(item, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
                LiveChip(item, Modifier.align(Alignment.TopStart).padding(start = pad, top = if (wide) 20.dp else 26.dp))
                Progress(item, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = pad, end = pad, top = 18.dp, bottom = pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Who(item, MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Meta(item, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Explain(item, affordance, MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons(affordance, sideBySide = false)
            }
        }
    }

    @Composable
    private fun ArtCaption(item: WatchingNowItem, affordance: WatchingNowJoinAffordance, wide: Boolean) {
        val pad = if (wide) 24.dp else 20.dp
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 4f / 3f)) {
                Art(item, Modifier.matchParentSize())
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
                    ),
                )
                LiveChip(item, Modifier.align(Alignment.TopStart).padding(start = pad, top = if (wide) 20.dp else 26.dp))
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = pad, end = pad, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Who(item, Color.White.copy(alpha = 0.85f))
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Meta(item, Color.White.copy(alpha = 0.72f))
                }
                Progress(item, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(start = pad, end = pad, top = 16.dp, bottom = pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Explain(item, affordance, MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons(affordance, sideBySide = true)
            }
        }
    }

    @Composable
    private fun Immersive(item: WatchingNowItem, affordance: WatchingNowJoinAffordance, wide: Boolean) {
        val pad = if (wide) 28.dp else 20.dp
        val surface = MaterialTheme.colorScheme.surface
        Box(Modifier.fillMaxWidth().height(if (wide) 520.dp else 560.dp)) {
            Art(item, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.2f), 0.3f to Color.Transparent, 0.62f to surface.copy(alpha = 0.92f), 1f to surface,
                    ),
                ),
            )
            LiveChip(item, Modifier.align(Alignment.TopStart).padding(start = pad, top = if (wide) 24.dp else 26.dp))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pad, end = pad, bottom = pad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Who(item, Color.White.copy(alpha = 0.85f))
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Meta(item, Color.White.copy(alpha = 0.72f))
                }
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)) { Progress(item, Modifier) }
                Explain(item, affordance, Color.White.copy(alpha = 0.72f))
                Buttons(affordance, sideBySide = true)
            }
        }
    }

    // --- shared pieces, in the shipped sheet's wording ---------------------------------------------

    @Composable
    private fun LiveChip(item: WatchingNowItem, modifier: Modifier) {
        val playing = item.state == SocialPlaybackState.playing
        Row(
            modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (playing) SocialLiveColor else Color(0xFFE0B04A)))
            Text(if (playing) "PLAYING" else "PAUSED", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Who(item: WatchingNowItem, color: Color) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SocialFaces(item.socialPeople(), 22.dp)
            Text(item.socialSentence(), color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun Meta(item: WatchingNowItem, color: Color) {
        val meta = item.socialMeta()
        if (meta.isNotBlank()) {
            Text(meta, color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun Explain(item: WatchingNowItem, affordance: WatchingNowJoinAffordance, color: Color) {
        val who = socialPeopleLabel(item.socialPeople())
        Text(
            when (affordance) {
                WatchingNowJoinAffordance.Join -> "$who lets friends join directly. You'll start in sync with them."
                WatchingNowJoinAffordance.AskToJoin -> "$who gets a request. You'll join as soon as they accept."
                else -> ""
            },
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    @Composable
    private fun Buttons(affordance: WatchingNowJoinAffordance, sideBySide: Boolean) {
        val primary = if (affordance == WatchingNowJoinAffordance.Join) "Join now" else "Ask to join"
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SocialPillButton(primary, {}, Modifier.weight(1f), primary = true, compact = false)
                SocialPillButton("Details", {}, Modifier.weight(1f), compact = false)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SocialPillButton(primary, {}, Modifier.fillMaxWidth(), primary = true, compact = false)
                SocialPillButton("View details", {}, Modifier.fillMaxWidth(), compact = false)
            }
        }
    }

    @Composable
    private fun Progress(item: WatchingNowItem, modifier: Modifier) {
        Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(item.progressFraction.coerceIn(0f, 1f)).height(3.dp).background(SocialLiveColor))
        }
    }

    @Composable
    private fun Art(item: WatchingNowItem, modifier: Modifier) {
        val bitmap = loadArt(item.contentId)
        if (bitmap != null) {
            Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
            return
        }
        val hue = ((item.title.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
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
