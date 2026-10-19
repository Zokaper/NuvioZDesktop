package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Candidates for `PLAN-language-and-whats-new.md` stage 1, rendered before anything is built.
 *
 * - **Language page.** A: one settings page of rows, the rules as inline segmented choices. B: the
 *   same settings told as two situations, each with a still showing what you would get.
 * - **"New settings available" offer** over What's New. A: the plain dialog as specified. B: the same,
 *   naming what changed.
 * - **What's New.** A: feature cards with an illustration each, fixes folded. B: one highlight at a
 *   time on top, everything else a compact list. C: wide magazine rows (phone falls back to A).
 *
 * Copy here is placeholder: release notes are the maintainer's to write.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*LanguageWhatsNewDesignHarness" --rerun
 * # PNGs land in composeApp/build/language-whatsnew-design/
 * ```
 */
class LanguageWhatsNewDesignHarness {

    private val outputDir = File("build/language-whatsnew-design")
    private val artDir = File("build/social-design-art")
    private val artCache = mutableMapOf<String, ImageBitmap?>()

    private val punisher = "tt5675620"
    private val burning = "tt7282468"
    private val budapest = "tt2278388"

    @Test
    fun renderCandidates() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((w, h) in listOf(1280 to 820, 420 to 900)) {
            val wide = w >= 700
            renderScene("language-A-$w", w, if (wide) 1180 else 1560, failures) { SettingsFrame(wide) { LanguagePageA(wide) } }
            renderScene("language-B-$w", w, if (wide) 1180 else 1900, failures) { SettingsFrame(wide) { LanguagePageB(wide) } }
            renderScene("offer-A-$w", w, h, failures) { OfferScene(wide) { OfferDialogA() } }
            renderScene("offer-B-$w", w, h, failures) { OfferScene(wide) { OfferDialogB() } }
            renderScene("whatsnew-A-$w", w, if (wide) 2250 else 2900, failures) { WhatsNewFrame(wide, maxWidth = 680) { WhatsNewA(wide) } }
            renderScene("whatsnew-B-$w", w, if (wide) 1500 else 1900, failures) { WhatsNewFrame(wide, maxWidth = 680) { WhatsNewB(wide) } }
            if (wide) {
                renderScene("whatsnew-C-$w", w, 1700, failures) { WhatsNewFrame(wide, maxWidth = 980) { WhatsNewC() } }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- Language page --------------------------------------------------------------------------

    @Composable
    private fun LanguagePageA(wide: Boolean) {
        PageTitle("Language", "Audio and subtitles for every title.")
        SettingsSection(title = "Languages", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                SettingsNavigationRow(title = "My language", description = "English", isTablet = wide, onClick = {})
                SettingsGroupDivider(isTablet = wide)
                SettingsNavigationRow(
                    title = "Fallback language",
                    description = "None · used when nothing is available in English",
                    isTablet = wide, onClick = {},
                )
                SettingsGroupDivider(isTablet = wide)
                SettingsNavigationRow(
                    title = "Subtitle language",
                    description = "Same as my language (English)",
                    isTablet = wide, onClick = {},
                )
            }
        }
        SettingsSection(title = "Audio", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                ChoiceRow(
                    wide = wide,
                    title = "Titles in other languages",
                    description = "Shows and films that weren't made in English.",
                    options = listOf("Original audio", "English when available"),
                    selected = 0,
                    note = "Korean film → Korean audio. If a release has no original audio, English plays.",
                )
            }
        }
        SettingsSection(title = "Subtitles", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                ChoiceRow(
                    wide = wide,
                    title = "When the audio is English",
                    options = listOf("Off", "Forced only", "Full"),
                    selected = 1,
                    note = "Forced only: just signs and lines spoken in another language.",
                )
                SettingsGroupDivider(isTablet = wide)
                ChoiceRow(
                    wide = wide,
                    title = "When the audio is another language",
                    options = listOf("English subtitles", "Off"),
                    selected = 0,
                )
            }
        }
        MoreSection(wide)
    }

    @Composable
    private fun LanguagePageB(wide: Boolean) {
        PageTitle("Language", "Audio and subtitles for every title.")
        SettingsSection(title = "Languages", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                SettingsNavigationRow(title = "My language", description = "English", isTablet = wide, onClick = {})
                SettingsGroupDivider(isTablet = wide)
                SettingsNavigationRow(title = "Fallback language", description = "None", isTablet = wide, onClick = {})
                SettingsGroupDivider(isTablet = wide)
                SettingsNavigationRow(title = "Subtitle language", description = "Same as my language (English)", isTablet = wide, onClick = {})
            }
        }
        SettingsSection(title = "How titles play", isTablet = wide) {
            val cardA: @Composable (Modifier) -> Unit = { m ->
                ScenarioCard(
                    modifier = m,
                    art = punisher,
                    subtitle = "¿Dónde está el dinero?" to "Where's the money?",
                    heading = "When the audio is English",
                    example = "The Punisher · English audio · forced subtitles",
                ) {
                    Label("Subtitles")
                    Segmented(listOf("Off", "Forced only", "Full"), 1)
                    Hint("Forced only: just signs and lines spoken in another language.")
                }
            }
            val cardB: @Composable (Modifier) -> Unit = { m ->
                ScenarioCard(
                    modifier = m,
                    art = burning,
                    subtitle = null to "Do you want to see it?",
                    heading = "Titles in other languages",
                    example = "Burning · Korean audio · English subtitles",
                ) {
                    Label("Audio")
                    Segmented(listOf("Original", "English when available"), 0)
                    Spacer(Modifier.height(4.dp))
                    Label("Subtitles")
                    Segmented(listOf("English", "Off"), 0)
                    Hint("If a release only has English audio, the English-audio rule applies.")
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    cardA(Modifier.weight(1f))
                    cardB(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    cardA(Modifier.fillMaxWidth())
                    cardB(Modifier.fillMaxWidth())
                }
            }
        }
        MoreSection(wide)
    }

    @Composable
    private fun MoreSection(wide: Boolean) {
        SettingsSection(title = "More", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                SettingsSwitchRow(
                    title = "Show only my languages",
                    description = "Hide addon subtitles in other languages.",
                    checked = false, isTablet = wide, onCheckedChange = {},
                )
                SettingsGroupDivider(isTablet = wide)
                SettingsSwitchRow(
                    title = "Prefer built-in subtitles",
                    description = "Favour sources whose file includes English subtitles.",
                    checked = true, isTablet = wide, onCheckedChange = {},
                )
                SettingsGroupDivider(isTablet = wide)
                SettingsNavigationRow(title = "Addon subtitle loading", description = "All addon subtitles", isTablet = wide, onClick = {})
            }
        }
    }

    @Composable
    private fun ChoiceRow(wide: Boolean, title: String, options: List<String>, selected: Int, description: String? = null, note: String? = null) {
        val tokens = MaterialTheme.nuvio
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (wide) 20.dp else 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary) }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.widthIn(max = 460.dp)) { Segmented(options, selected) }
            note?.let { Hint(it) }
        }
    }

    @Composable
    private fun ScenarioCard(
        modifier: Modifier,
        art: String,
        subtitle: Pair<String?, String>?,
        heading: String,
        example: String,
        controls: @Composable ColumnScope.() -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        Column(
            modifier = modifier.clip(RoundedCornerShape(18.dp)).background(tokens.colors.surfaceCard),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Still(art)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                subtitle?.let { (_, line) -> SubtitleLine(line, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(heading, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(example, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                Spacer(Modifier.height(2.dp))
                controls()
            }
        }
    }

    // --- New settings offer ---------------------------------------------------------------------

    @Composable
    private fun OfferScene(wide: Boolean, dialog: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0E1118))))) {
            Box(Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.86f)) {
                DialogSurface { WhatsNewA(wide, compactPreview = true) }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Box(Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 460.dp).fillMaxWidth()) { dialog() }
        }
    }

    @Composable
    private fun OfferDialogA() {
        val tokens = MaterialTheme.nuvio
        DialogSurface {
            Box {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("New settings available", style = MaterialTheme.typography.headlineSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "This update includes new or changed settings. Would you like to go through them now?",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                    Text(
                        "You can review them any time from Settings → What's New.",
                        style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        Pill("No", filled = false)
                        Pill("Yes (Recommended)", filled = true)
                    }
                }
                CloseButton(Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
        }
    }

    @Composable
    private fun OfferDialogB() {
        val tokens = MaterialTheme.nuvio
        DialogSurface {
            Box {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tokens.colors.accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Tune, null, tint = tokens.colors.accent) }
                    Text("New settings available", style = MaterialTheme.typography.headlineSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "This update includes new or changed settings. Would you like to go through them now? It takes about a minute.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surfaceCard).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ChangedRow(Icons.Rounded.Language, "Language", "Separate rules for titles in other languages")
                        ChangedRow(Icons.Rounded.Subtitles, "Subtitles", "Off, forced only or full, depending on the audio")
                    }
                    Text(
                        "You can review them any time from Settings → What's New.",
                        style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        Pill("No", filled = false)
                        Pill("Yes (Recommended)", filled = true)
                    }
                }
                CloseButton(Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
        }
    }

    @Composable
    private fun ChangedRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        val tokens = MaterialTheme.nuvio
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
    }

    // --- What's New -----------------------------------------------------------------------------

    private data class Feature(val title: String, val body: String, val visual: String)

    private val features = listOf(
        Feature("Social, rebuilt", "See what friends are watching right now, join them in one tap, and send each other recommendations.", "social"),
        Feature("Language and subtitles", "Original audio and full subtitles for foreign titles, your language and forced subtitles for the rest.", "language"),
        Feature("Pop-ups and the tray", "Nuvio Z can keep running in the tray and tell you when a friend starts watching.", "popup"),
    )
    private val improvements = listOf(
        "Next episode's loading screen shows the right episode and a sharp backdrop",
        "A notice when you've been signed out, with a Sign in button",
        "Start with Windows",
    )
    private val fixes = listOf(
        "Invite button said \"Invite %d\"",
        "Notifications opened Social behind a details page",
        "Remembered subtitles could come back in another language",
        "Five more",
    )

    @Composable
    private fun WhatsNewA(wide: Boolean, compactPreview: Boolean = false) {
        val tokens = MaterialTheme.nuvio
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WhatsNewHeader()
            EventHeader()
            (if (compactPreview) features.take(1) else features).forEach { f ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.colors.surfaceCard),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f)) { Visual(f.visual) }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                        Text(f.body, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        if (f.visual == "language") ReviewSettingsLink()
                    }
                }
            }
            if (!compactPreview) {
                CompactList("Improvements", improvements)
                Folded("${fixes.size + 4} fixes")
                DoneRow()
            }
        }
    }

    @Composable
    private fun WhatsNewB(wide: Boolean) {
        val tokens = MaterialTheme.nuvio
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WhatsNewHeader()
            EventHeader()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))) {
                    Visual(features[0].visual)
                    Row(Modifier.align(Alignment.TopEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        features.indices.forEach { i ->
                            Box(Modifier.size(if (i == 0) 18.dp else 7.dp, 7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == 0) 0.95f else 0.45f)))
                        }
                    }
                }
                Text(features[0].title, style = MaterialTheme.typography.headlineSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(features[0].body, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            }
            CompactList("New", features.drop(1).map { it.title + " — " + it.body }, review = true)
            CompactList("Improvements", improvements)
            Folded("${fixes.size + 4} fixes")
            DoneRow()
        }
    }

    @Composable
    private fun WhatsNewC() {
        val tokens = MaterialTheme.nuvio
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            WhatsNewHeader()
            EventHeader()
            features.forEachIndexed { i, f ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val art: @Composable () -> Unit = {
                        Box(Modifier.width(420.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))) { Visual(f.visual) }
                    }
                    val text: @Composable () -> Unit = {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(f.title, style = MaterialTheme.typography.headlineSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                            Text(f.body, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary)
                            if (f.visual == "language") ReviewSettingsLink()
                        }
                    }
                    if (i % 2 == 0) { art(); text() } else { text(); art() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { CompactList("Improvements", improvements) }
                Box(Modifier.weight(1f)) { Folded("${fixes.size + 4} fixes") }
            }
            DoneRow()
        }
    }

    @Composable
    private fun WhatsNewHeader() {
        val tokens = MaterialTheme.nuvio
        Column {
            Text("What's New", style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text("Version 0.1.27-alpha-z1", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }

    @Composable
    private fun EventHeader() {
        val tokens = MaterialTheme.nuvio
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("October 2026", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("Desktop · Android · iOS", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        }
    }

    @Composable
    private fun ReviewSettingsLink() {
        val tokens = MaterialTheme.nuvio
        Row(
            Modifier.padding(top = 4.dp).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Tune, null, tint = tokens.colors.accent, modifier = Modifier.size(16.dp))
            Text("Review new settings", style = MaterialTheme.typography.labelLarge, color = tokens.colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun CompactList(title: String, lines: List<String>, review: Boolean = false) {
        val tokens = MaterialTheme.nuvio
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(tokens.colors.accent))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
            }
            if (review) ReviewSettingsLink()
        }
    }

    @Composable
    private fun Folded(label: String) {
        val tokens = MaterialTheme.nuvio
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surfaceCard).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.colors.textMuted)
        }
    }

    @Composable
    private fun DoneRow() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Pill("Done", filled = true) }
    }

    /** The illustration for an entry, drawn rather than shipped as an image. */
    @Composable
    private fun BoxScope.Visual(key: String) {
        when (key) {
            "social" -> {
                Still(punisher)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Column(Modifier.align(Alignment.CenterStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("● Watching now")
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        listOf(Color(0xFFE0527A) to "F", Color(0xFF4FB0C6) to "J", Color(0xFF8E6CF0) to "S").forEach { (c, l) ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.Black).padding(2.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                                Text(l, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    Text("faye, jules and Seraph", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Chip("Join")
                }
            }
            "language" -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Still(punisher)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                    Chip("English show", Modifier.align(Alignment.TopStart).padding(10.dp))
                    SubtitleLine("Where's the money?", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), small = true)
                }
                Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Still(burning)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                    Chip("Korean film", Modifier.align(Alignment.TopStart).padding(10.dp))
                    SubtitleLine("Do you want to see it?", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), small = true)
                }
            }
            "popup" -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF243B55), Color(0xFF141E30), Color(0xFF3A1C4A)))))
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 34.dp).width(250.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1F26)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                ) {
                    Box(Modifier.width(92.dp).aspectRatio(16f / 9f)) { Still(budapest) }
                    Column(Modifier.padding(10.dp)) {
                        Text("jules started watching", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text("The Grand Budapest Hotel", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.nuvio.colors.accent))
                }
            }
        }
    }

    // --- Shared bits ----------------------------------------------------------------------------

    @Composable
    private fun Segmented(options: List<String>, selected: Int) {
        val tokens = MaterialTheme.nuvio
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(tokens.colors.surfaceElevated).padding(3.dp)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(if (i == selected) tokens.colors.accent else Color.Transparent).padding(vertical = 8.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == selected) tokens.colors.onAccent else tokens.colors.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun Label(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary, fontWeight = FontWeight.SemiBold)
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
    }

    @Composable
    private fun Pill(text: String, filled: Boolean) {
        val tokens = MaterialTheme.nuvio
        Box(
            Modifier.clip(CircleShape).background(if (filled) tokens.colors.accent else tokens.colors.surfaceCard).padding(horizontal = 20.dp, vertical = 11.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (filled) tokens.colors.onAccent else tokens.colors.textPrimary)
        }
    }

    @Composable
    private fun CloseButton(modifier: Modifier) {
        val tokens = MaterialTheme.nuvio
        Box(modifier.size(36.dp).clip(CircleShape).background(tokens.colors.surfaceCard), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, null, tint = tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }

    @Composable
    private fun Chip(text: String, modifier: Modifier = Modifier) {
        Box(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun SubtitleLine(text: String, modifier: Modifier, small: Boolean = false) {
        Text(
            text, modifier = modifier.background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = if (small) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
    }

    @Composable
    private fun BoxScope.Still(contentId: String) {
        val art = loadArt(contentId)
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF3A4A63), Color(0xFF1A2130)))))
        }
    }

    @Composable
    private fun DialogSurface(content: @Composable () -> Unit) {
        val tokens = MaterialTheme.nuvio
        Surface(color = tokens.colors.surfaceDialog, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) { content() }
    }

    @Composable
    private fun PageTitle(title: String, subtitle: String) {
        val tokens = MaterialTheme.nuvio
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
    }

    @Composable
    private fun SettingsFrame(wide: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = if (wide) 32.dp else 12.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }

    @Composable
    private fun WhatsNewFrame(wide: Boolean, maxWidth: Int, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(16.dp).widthIn(max = maxWidth.dp).fillMaxWidth()) { DialogSurface(content) }
        }
    }

    private fun loadArt(contentId: String): ImageBitmap? = artCache.getOrPut(contentId) {
        listOf("jpg", "webp", "png").map { File(artDir, "$contentId.$it") }.firstOrNull(File::isFile)?.let { file ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }

    private fun renderScene(name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, content: @Composable () -> Unit) {
        val density = if (widthDp >= 1000) 1.5f else 2f
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
}
