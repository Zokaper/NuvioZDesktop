package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.PartyInviteButton
import com.nuvio.app.features.watchparty.PartyInvitePicker
import com.nuvio.app.features.watchparty.PartyInviteRow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Watch Together invite list (2026-10-19): candidates before any product code. The lobby drew every friend as a
 * chip in a wrapping cloud with no state, and the in-player panels list every friend with no order or search;
 * with twenty friends both run away. Each candidate is drawn as the lobby's Participants panel (desktop, phone)
 * and as the narrow in-player panel.
 *
 * - **A, inline list:** rows (face, name, what they are doing, the Invite button with its states), friends
 *   watching right now first, five rows then "Show all 23".
 * - **B, search + scroll box:** a search field and a fixed-height scrolling list, "Watching now" and "Friends"
 *   sections.
 * - **C, picker:** Invite opens a dialog / sheet with search and check circles; one button sends to everyone
 *   picked ("Invite 3 friends").
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*PartyInviteListHarness" --rerun
 * # PNGs land in composeApp/build/party-invite/
 * ```
 */
class PartyInviteListHarness {

    private val outputDir = File("build/party-invite")

    private data class Friend(val name: String, val hex: String, val watching: String? = null, val state: String = "invite")

    private val friends = listOf(
        Friend("Seraph", "#5E35B1", watching = "Futurama"),
        Friend("faisal", "#F4511E", watching = "The Bear · S3 E4"),
        Friend("jeremy renner", "#546E7A", state = "sent"),
        Friend("main debug", "#29B6F6"),
        Friend("debug", "#EC407A", state = "declined"),
        Friend("new guy", "#1E3A6E"),
        Friend("finalguy", "#EC407A"),
        Friend("VeyronV1", "#EC407A"),
        Friend("tvamet", "#8D6E63", state = "sending"),
        Friend("Ana Lucía Fernández-Ortega", "#43A047"),
        Friend("Ben", "#FB8C00"),
        Friend("Chidi", "#3949AB"),
        Friend("Dana", "#00897B"),
        Friend("Eli", "#C0CA33"),
        Friend("Farah", "#D81B60"),
        Friend("Gus", "#6D4C41"),
        Friend("Hana", "#7CB342"),
        Friend("Ivo", "#039BE5"),
        Friend("Jules", "#8E24AA"),
        Friend("Kai", "#F9A825"),
        Friend("Lena", "#00ACC1"),
        Friend("Milo", "#5D4037"),
        Friend("Noor", "#E53935"),
    )
    private val picked = setOf("main debug", "new guy", "Ben")

    @Test
    fun renderInviteCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for (v in listOf("A", "B", "C")) {
            renderScene("lobby-$v-desktop", 1280, 820, failures) { LobbyScene(v, phone = false) }
            renderScene("lobby-$v-411x914", 411, 914, failures) { LobbyScene(v, phone = true) }
            renderScene("player-$v-desktop", 1280, 820, failures) { PlayerScene(v) }
        }
        renderScene("picker-C-desktop", 1280, 820, failures) { PickerDialog() }
        renderScene("picker-C-411x914", 411, 914, failures) { PickerSheet() }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** The shipped picker (C was picked 2026-10-19), as the lobby, the phone player and the desktop dialog open it. */
    @Test
    fun renderShippedPicker() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        val rows = friends.map { friend ->
            PartyInviteRow(
                key = friend.name,
                name = friend.name,
                avatarUrl = null,
                colorHex = friend.hex,
                watching = friend.watching,
                button = PartyInviteButton.entries.first { it.wire == friend.state },
            )
        }
        renderScene("shipped-picker-desktop", 1280, 820, failures) {
            PartyInvitePicker(rows, slots = 7, wide = true, onDismiss = {}, onInvite = {}, onCancel = {})
        }
        renderScene("shipped-picker-891x411", 891, 411, failures) {
            PartyInvitePicker(rows, slots = 7, wide = true, onDismiss = {}, onInvite = {}, onCancel = {})
        }
        renderScene("shipped-picker-411x914", 411, 914, failures) {
            PartyInvitePicker(rows, slots = 7, wide = false, onDismiss = {}, onInvite = {}, onCancel = {})
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- scenes ---------------------------------------------------------------------------------------

    @Composable
    private fun LobbyScene(variant: String, phone: Boolean) {
        Box(Modifier.fillMaxSize().background(Color(0xFF15110C))) {
            Column(
                Modifier.padding(if (phone) 16.dp else 40.dp).then(if (phone) Modifier.fillMaxWidth() else Modifier.width(760.dp)),
            ) {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Participants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("0 of 1 ready", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            YouTile()
                        }
                        when (variant) {
                            "A" -> InlineList(rows = 5)
                            "B" -> SearchBox(boxHeight = if (phone) 300.dp else 280.dp)
                            else -> InviteButtonRow()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlayerScene(variant: String) {
        // The desktop player's Watch Together panel: a dark column on the right edge over the video.
        Box(Modifier.fillMaxSize().background(Color(0xFF202830))) {
            Surface(
                Modifier.align(Alignment.CenterEnd).padding(16.dp).width(360.dp).heightIn(max = 780.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xE6101114),
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Watch Together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("People", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SocialAvatar("You", null, "#FB8C00", 32.dp)
                            Text("You · host", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Invite friends", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                        when (variant) {
                            "A" -> InlineList(rows = 5, compact = true)
                            "B" -> SearchBox(boxHeight = 300.dp, compact = true)
                            else -> InviteButtonRow(compact = true)
                        }
                        Text("Invite code  ·  K7Q-2MX", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    @Composable
    private fun PickerDialog() {
        Box(Modifier.fillMaxSize().background(Color(0xFF15110C))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(Modifier.align(Alignment.Center).width(480.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { PickerBody(listHeight = 420.dp) }
            }
        }
    }

    @Composable
    private fun PickerSheet() {
        Box(Modifier.fillMaxSize().background(Color(0xFF15110C))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { PickerBody(listHeight = 520.dp) }
            }
        }
    }

    // --- candidates -----------------------------------------------------------------------------------

    private fun ordered() = friends.sortedBy { if (it.watching != null) 0 else 1 }

    @Composable
    private fun InlineList(rows: Int, compact: Boolean = false) {
        Column {
            ordered().take(rows).forEach { FriendRow(it, compact) }
            Text(
                "Show all ${friends.size} friends",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    @Composable
    private fun SearchBox(boxHeight: Dp, compact: Boolean = false) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField()
            Box(
                Modifier.fillMaxWidth().height(boxHeight).clip(RoundedCornerShape(14.dp))
                    .background(LocalContentColor.current.copy(alpha = 0.04f)).padding(horizontal = 10.dp),
            ) {
                Column {
                    Text("Watching now", Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                    ordered().filter { it.watching != null }.forEach { FriendRow(it, compact) }
                    Text("Friends", Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                    ordered().filter { it.watching == null }.forEach { FriendRow(it, compact) }
                }
                // The scrollbar the real box would draw.
                Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp).width(3.dp).height(boxHeight / 3).clip(CircleShape).background(muted))
            }
        }
    }

    @Composable
    private fun InviteButtonRow(compact: Boolean = false) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("+  Invite friends", color = Color.Black, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
            Text(if (compact) "1 sent · 1 sending" else "Sent to jeremy renner · sending to tvamet", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun PickerBody(listHeight: Dp) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Invite to Watch Together", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SearchField()
            Box(Modifier.fillMaxWidth().height(listHeight)) {
                Column {
                    Text("Watching now", Modifier.padding(top = 4.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                    ordered().filter { it.watching != null }.forEach { PickRow(it) }
                    Text("Friends", Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                    ordered().filter { it.watching == null }.forEach { PickRow(it) }
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Invite ${picked.size} friends", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }

    // --- rows -----------------------------------------------------------------------------------------

    @Composable
    private fun FriendRow(friend: Friend, compact: Boolean) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        Row(
            Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SocialAvatar(friend.name, null, friend.hex, if (compact) 32.dp else 36.dp)
            Column(Modifier.weight(1f)) {
                Text(friend.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                friend.watching?.let {
                    Text("Watching $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF66BB6A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            InviteButton(friend.state)
        }
    }

    @Composable
    private fun PickRow(friend: Friend) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        val unavailable = friend.state != "invite"
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SocialAvatar(friend.name, null, friend.hex, 38.dp)
            Column(Modifier.weight(1f)) {
                Text(friend.name, fontWeight = FontWeight.SemiBold, color = if (unavailable) muted else LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                friend.watching?.let {
                    Text("Watching $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF66BB6A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (friend.state) {
                "sent" -> Text("Sent", style = MaterialTheme.typography.labelLarge, color = muted)
                "sending" -> Text("Sending…", style = MaterialTheme.typography.labelLarge, color = muted)
                "declined" -> Text("Declined", style = MaterialTheme.typography.labelLarge, color = muted)
                else -> CheckCircle(friend.name in picked)
            }
        }
    }

    @Composable
    private fun InviteButton(state: String) {
        val content = LocalContentColor.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (label, filled) = when (state) {
                "sending" -> "Sending…" to false
                "sent" -> "Sent" to false
                "declined" -> "Declined" to false
                else -> "Invite" to true
            }
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (filled) content.copy(alpha = 0.14f) else Color.Transparent)
                    .then(if (filled) Modifier else Modifier.border(1.dp, content.copy(alpha = 0.2f), RoundedCornerShape(50)))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (filled) content else content.copy(alpha = 0.6f))
            }
            if (state == "sent") {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(content.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f)) }
            }
        }
    }

    @Composable
    private fun SearchField() {
        val content = LocalContentColor.current
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(content.copy(alpha = 0.07f)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text("Search friends", color = content.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium) }
    }

    @Composable
    private fun CheckCircle(on: Boolean) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (on) Color.White else Color.Transparent)
                .then(if (on) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
    }

    @Composable
    private fun YouTile() {
        Surface(Modifier.width(150.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SocialAvatar("You", null, "#FB8C00", 52.dp)
                Text("You", fontWeight = FontWeight.SemiBold)
                Text("IN LOBBY", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
            }
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
                    ) { Box(Modifier.fillMaxSize().background(Color(0xFF15110C))) { content() } }
                }
            }
            try {
                // Let the dialog's fade and the sheet's slide finish: one frame caught both mid-way.
                var nanos = 0L
                while (nanos < 1_500_000_000L) {
                    scene.render(nanos)
                    nanos += 16_000_000L
                }
                val image = scene.render(nanos)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }
}
