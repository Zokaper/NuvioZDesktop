package com.nuvio.app.features.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * Recommendations (2026-10-18): candidates for the three new surfaces, before any product code.
 *
 * - **Picker** (details page -> three-dot menu -> Recommend): A, a friend list with check circles; B, a grid
 *   of large faces you tap. Both: the title at the top, an optional message, "Sent" for friends who already
 *   have it, and one button that names how many.
 * - **"Recommended to you" card** (Social tab shelf): A, the backdrop tile with the note as a quote under it;
 *   B, the note laid on the art; C, a wide card, art left and the notes right. One card per title, every face.
 * - **Details-page indicator**: A, a slim line under the actions; B, a callout card with each note.
 *
 * Real backdrops from `build/social-design-art/` (see [SocialActivityRedesignHarness]).
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialRecommendHarness" --rerun
 * # PNGs land in composeApp/build/social-recommend/
 * ```
 */
class SocialRecommendHarness {

    private val outputDir = File("build/social-recommend")
    private val artDir = File("build/social-design-art")

    private fun person(name: String, hex: String) =
        SocialProfileSummary(profileId = "p-$name", handle = name.lowercase().substringBefore(' '), displayName = name, avatarColorHex = hex, isFriend = true)

    private val seraph = person("Seraph", "#8E6CF0")
    private val ben = person("ben", "#E8833A")
    private val faye = person("faye", "#E0527A")
    private val jules = person("jules renner", "#4FB0C6")
    private val mika = person("mika", "#C2A12B")
    private val ana = person("Ana", "#2BB38A")
    private val friends = listOf(seraph, ben, faye, jules, mika, ana)

    private class Rec(val id: String, val title: String, val meta: String, val from: List<Pair<SocialProfileSummary, String?>>)

    private val recs = listOf(
        Rec("tt5675620", "The Punisher", "Series · 2 seasons", listOf(seraph to "If you watched BB u gotta watch ts", ben to null)),
        Rec("tt2278388", "The Grand Budapest Hotel", "Movie · 2014", listOf(faye to "the most beautiful film ever made, no notes")),
        Rec("tt0149460", "Futurama", "Series · 10 seasons", listOf(jules to null)),
        Rec("tt7282468", "Burning", "Movie · 2018", listOf(mika to "slow but trust me", ana to "the ending!!", ben to null)),
    )

    @Test
    fun renderRecommendCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for (v in listOf("A", "B")) {
            renderScene("picker-$v-411x914", 411, 914, failures) { PhoneSheet { Picker(v, wide = false) } }
            renderScene("picker-$v-360x780", 360, 780, failures) { PhoneSheet { Picker(v, wide = false) } }
            renderScene("picker-$v-desktop", 1280, 820, failures) { DesktopDialog { Picker(v, wide = true) } }
        }
        for (v in listOf("A", "B", "C")) {
            renderScene("shelf-$v-411x914", 411, 914, failures) { ShelfScene(v, phone = true) }
            renderScene("shelf-$v-desktop", 1280, 820, failures) { ShelfScene(v, phone = false) }
        }
        for (v in listOf("A", "B")) {
            renderScene("details-$v-411x914", 411, 914, failures) { DetailsScene(v, phone = true) }
            renderScene("details-$v-desktop", 1280, 820, failures) { DetailsScene(v, phone = false) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- picker -----------------------------------------------------------------------------------

    private val picked = setOf(seraph.profileId, jules.profileId)
    private val alreadySent = setOf(ben.profileId)

    @Composable
    private fun Picker(variant: String, wide: Boolean) {
        val pad = if (wide) 24.dp else 20.dp
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(start = pad, end = pad, top = if (wide) 24.dp else 28.dp, bottom = pad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header: what you are recommending.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) { Art("tt0149460", Modifier.matchParentSize()) }
                Column {
                    Text("Recommend", style = MaterialTheme.typography.labelLarge, color = muted)
                    Text("Futurama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (variant == "A") FriendList() else FriendGrid(wide)
            // The message.
            Box(
                Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(14.dp),
            ) {
                Text("Add a message (optional)", color = muted, style = MaterialTheme.typography.bodyMedium)
                Text("0 / 280", Modifier.align(Alignment.BottomEnd), color = muted.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
            SocialPillButton("Recommend to 2 friends", {}, Modifier.fillMaxWidth(), primary = true, compact = false)
        }
    }

    @Composable
    private fun FriendList() {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column {
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Search friends", color = muted, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(6.dp))
            friends.take(5).forEach { friend ->
                val sent = friend.profileId in alreadySent
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.then(if (sent) Modifier.background(Color.Transparent) else Modifier)) {
                        SocialAvatar(friend.displayName, null, friend.avatarColorHex, 40.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(friend.displayName, fontWeight = FontWeight.SemiBold, color = if (sent) muted else MaterialTheme.colorScheme.onSurface)
                        Text("@${friend.handle}", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    if (sent) Text("Sent", style = MaterialTheme.typography.labelLarge, color = muted)
                    else CheckCircle(friend.profileId in picked)
                }
            }
        }
    }

    @Composable
    private fun FriendGrid(wide: Boolean) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val columns = if (wide) 6 else 4
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            friends.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { friend ->
                        val sent = friend.profileId in alreadySent
                        val on = friend.profileId in picked
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                Box(
                                    Modifier.size(64.dp).clip(CircleShape)
                                        .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) { SocialAvatar(friend.displayName, null, friend.avatarColorHex, if (on) 56.dp else 64.dp) }
                                if (on) Box(Modifier.align(Alignment.BottomEnd)) { CheckCircle(true, 22.dp) }
                            }
                            Text(
                                if (sent) "Sent" else friend.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sent) muted else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun CheckCircle(on: Boolean, size: Dp = 24.dp) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (on) Color.White else Color.Transparent)
                .then(if (on) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
    }

    // --- shelf --------------------------------------------------------------------------------------

    @Composable
    private fun ShelfScene(variant: String, phone: Boolean) {
        val pad = if (phone) 16.dp else 32.dp
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recommended to you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${recs.size}", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.5f))
            }
            val tileWidth = when {
                variant == "C" -> if (phone) 330.dp else 460.dp
                phone -> 260.dp
                else -> 320.dp
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = pad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                recs.forEach { rec ->
                    when (variant) {
                        "A" -> ShelfTileA(rec, tileWidth)
                        "B" -> ShelfTileB(rec, tileWidth)
                        else -> ShelfTileC(rec, tileWidth)
                    }
                }
            }
        }
    }

    private fun Rec.people() = from.map { it.first }
    private fun Rec.whoLine(): String = "${socialPeopleLabel(people())} recommend${if (from.size == 1) "s" else ""}"

    @Composable
    private fun ShelfTileA(rec: Rec, width: Dp) {
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                Art(rec.id, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocialFaces(rec.people(), 24.dp)
                    Column {
                        Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rec.whoLine(), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            rec.from.firstOrNull { it.second != null }?.let { (who, note) ->
                Text(
                    "“$note” — ${who.displayName}" + if (rec.from.count { it.second != null } > 1) "  +${rec.from.count { it.second != null } - 1}" else "",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun ShelfTileB(rec: Rec, width: Dp) {
        Box(Modifier.width(width).aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp))) {
            Art(rec.id, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rec.from.firstOrNull { it.second != null }?.let { (who, note) ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SocialAvatar(who.displayName, null, who.avatarColorHex, 22.dp)
                        Box(
                            Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                                .background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 6.dp),
                        ) { Text(note!!, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SocialFaces(rec.people(), 18.dp)
                    Text(rec.whoLine(), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun ShelfTileC(rec: Rec, width: Dp) {
        Row(
            Modifier.width(width).height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF17181C)),
        ) {
            Box(Modifier.width(width * 0.42f).fillMaxSize()) {
                Art(rec.id, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.6f to Color.Transparent, 1f to Color(0xFF17181C))))
            }
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rec.meta, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                rec.from.take(2).forEach { (who, note) ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SocialAvatar(who.displayName, null, who.avatarColorHex, 18.dp)
                        Text(
                            note?.let { "“$it”" } ?: "${who.displayName} recommends it",
                            color = Color.White.copy(alpha = if (note != null) 0.85f else 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rec.from.size > 2) Text("+${rec.from.size - 2} more", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // --- details indicator ----------------------------------------------------------------------------

    @Composable
    private fun DetailsScene(variant: String, phone: Boolean) {
        val rec = recs.first()
        val pad = if (phone) 16.dp else 48.dp
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
            Box(Modifier.fillMaxWidth().height(if (phone) 420.dp else 560.dp)) {
                Art(rec.id, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xFF0B0B0D))))
            }
            Column(
                Modifier.align(Alignment.TopStart).padding(start = pad, end = pad, top = if (phone) 300.dp else 330.dp)
                    .then(if (phone) Modifier.fillMaxWidth() else Modifier.width(620.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // A stand-in for the real details header: title, facts, the action row with its three-dot menu.
                Text(rec.title, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("2017 · Crime, Action · 2 seasons", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SocialPillButton("Play S1 E1", {}, Modifier.width(if (phone) 180.dp else 200.dp), primary = true, compact = false)
                    CircleIcon("+")
                    CircleIcon("⋯")
                }
                when (variant) {
                    "A" -> DetailsLine(rec)
                    "B" -> DetailsCallout(rec)
                    else -> DetailsMiddle(rec, tinted = variant == "M2")
                }
                Text(
                    "After the murder of his family, Marine veteran Frank Castle becomes the vigilante known as \"The Punisher\", with only one goal in mind...",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    @Composable
    private fun CircleIcon(glyph: String) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun DetailsLine(rec: Rec) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialFaces(rec.people(), 26.dp)
            Column {
                Text("Recommended to you by ${socialPeopleLabel(rec.people())}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                rec.from.firstOrNull { it.second != null }?.let { (_, note) ->
                    Text("“$note”", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun DetailsCallout(rec: Rec) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f)),
        ) {
            Box(Modifier.width(3.dp).height(if (rec.from.size > 1) 104.dp else 72.dp).background(SocialLiveColor))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recommended to you", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                rec.from.forEach { (who, note) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SocialAvatar(who.displayName, null, who.avatarColorHex, 22.dp)
                        Text(who.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        note?.let { Text("“$it”", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }

    // --- round 2 (2026-10-18): lighter C, and a details middle ground ---------------------------------

    @Test
    fun renderRecommendRound2() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for (v in listOf("C", "C1", "C2", "Aplus")) {
            renderScene("r2-shelf-$v-desktop", 1280, 420, failures) { ShelfScene2(v, phone = false) }
            renderScene("r2-shelf-$v-411", 411, 420, failures) { ShelfScene2(v, phone = true) }
        }
        for (v in listOf("M1", "M2")) {
            renderScene("r2-details-$v-411x914", 411, 914, failures) { DetailsScene(v, phone = true) }
            renderScene("r2-details-$v-desktop", 1280, 820, failures) { DetailsScene(v, phone = false) }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Composable
    private fun ShelfScene2(variant: String, phone: Boolean) {
        val pad = if (phone) 16.dp else 32.dp
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recommended to you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${recs.size}", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.5f))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = pad), horizontalArrangement = Arrangement.spacedBy(if (variant == "Aplus") 12.dp else 20.dp)) {
                recs.forEach { rec ->
                    when (variant) {
                        "C" -> ShelfTileC(rec, if (phone) 330.dp else 460.dp)
                        "C1" -> ShelfTileC1(rec, if (phone) 330.dp else 440.dp, compact = false)
                        "C2" -> ShelfTileC1(rec, if (phone) 310.dp else 400.dp, compact = true)
                        else -> ShelfTileAplus(rec, if (phone) 260.dp else 300.dp)
                    }
                }
            }
        }
    }

    /** C without the card: rounded art, and the text straight on the page. */
    @Composable
    private fun ShelfTileC1(rec: Rec, width: Dp, compact: Boolean) {
        val artWidth = if (compact) 150.dp else 176.dp
        Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(artWidth).aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp))) { Art(rec.id, Modifier.matchParentSize()) }
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rec.meta, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                val shown = if (compact) 1 else 2
                rec.from.sortedBy { it.second == null }.take(shown).forEach { (who, note) ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SocialAvatar(who.displayName, null, who.avatarColorHex, 16.dp)
                        Text(
                            note?.let { "“$it”" } ?: "${who.displayName} recommends it",
                            color = Color.White.copy(alpha = if (note != null) 0.82f else 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rec.from.size > shown) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SocialFaces(rec.from.drop(shown).map { it.first }, 14.dp)
                        Text("+${rec.from.size - shown} more", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    /** A's tile, with up to two notes and the facts line under it. */
    @Composable
    private fun ShelfTileAplus(rec: Rec, width: Dp) {
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                Art(rec.id, Modifier.matchParentSize())
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { SocialFaces(rec.people(), 22.dp) }
            }
            Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rec.meta + " · " + rec.whoLine(), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            rec.from.filter { it.second != null }.take(2).forEach { (who, note) ->
                Text("“$note” — ${who.displayName}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    /** The middle ground: A's lightness, B's one line per friend. */
    @Composable
    private fun DetailsMiddle(rec: Rec, tinted: Boolean) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .then(if (tinted) Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)) else Modifier),
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(SocialLiveColor.copy(alpha = 0.8f)))
            Column(Modifier.padding(start = 12.dp, top = if (tinted) 8.dp else 0.dp, bottom = if (tinted) 8.dp else 0.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocialFaces(rec.people(), 20.dp)
                    Text("Recommended to you by ${socialPeopleLabel(rec.people())}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                rec.from.filter { it.second != null }.forEach { (who, note) ->
                    Text(
                        "“$note” — ${who.displayName}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // --- the shipped components (not prototypes) --------------------------------------------------

    private val shipped = recs.map { r ->
        RecommendedTitle(
            contentType = if (r.meta.startsWith("Series")) "series" else "movie", contentId = r.id, title = r.title,
            poster = null, background = null,
            from = r.from.mapIndexed { i, (who, note) -> RecommendationFrom(who, note, "${r.id}-$i") },
        )
    }

    @Test
    fun renderShippedRecommendationComponents() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((name, w, wide) in listOf(Triple("phone", 411, false), Triple("narrow", 320, false), Triple("desktop", 1280, true))) {
            renderScene("shipped-shelf-$name", w, 420, failures) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(top = 16.dp)) {
                    SocialRecommendedShelfContent(shipped, horizontalPadding = 16.dp, wide = wide, onOpenContent = { _, _, _ -> }, onDismiss = {})
                }
            }
            renderScene("shipped-line-$name", w, 220, failures) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SocialRecommendedByContent(shipped.first())
                    SocialRecommendedByContent(shipped.last())
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- frames and art ---------------------------------------------------------------------------------

    @Composable
    private fun DesktopDialog(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(Modifier.align(Alignment.Center).width(560.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
            }
        }
    }

    @Composable
    private fun PhoneSheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Box {
                        content()
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)))
                    }
                }
            }
        }
    }

    @Composable
    private fun Art(contentId: String, modifier: Modifier) {
        val bitmap = loadArt(contentId)
        if (bitmap != null) {
            Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
            return
        }
        val hue = ((contentId.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
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
                    ) { Box(Modifier.fillMaxSize()) { content() } }
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
