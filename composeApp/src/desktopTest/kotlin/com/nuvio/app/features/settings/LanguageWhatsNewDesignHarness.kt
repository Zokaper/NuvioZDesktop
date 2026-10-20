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
 * `PLAN-language-and-whats-new.md` stage 1, round 2 (2026-10-20), rendered before anything is built.
 *
 * - **Language:** the Settings page as plain rows, and the wizard step with a still per group. Both
 *   framed by title ("Titles in English" / "Titles in other languages"); subtitles a list - Off,
 *   Forced, Full, Deaf and hard of hearing (SDH) - each with its example line.
 * - **"New settings available" offer** over What's New: A, the plain dialog (picked).
 * - **What's New:** option A (picked), platform tags beside every entry, no "other devices" section.
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
        outputDir.listFiles()?.forEach { it.delete() }
        val failures = mutableListOf<String>()
        for ((w, h) in listOf(1280 to 820, 420 to 900)) {
            val wide = w >= 700
            renderScene("language-settings-$w", w, if (wide) 1750 else 2050, failures) { SettingsFrame(wide) { LanguageSettingsPage(wide) } }
            renderScene("language-wizard-$w", w, if (wide) 1450 else 2500, failures) { WizardFrame(wide) { WizardLanguageStep(wide) } }
            renderScene("offer-$w", w, h, failures) { OfferScene(wide) { OfferDialogA() } }
            renderScene("whatsnew-$w", w, if (wide) 2350 else 1950, failures) { WhatsNewFrame(wide, maxWidth = 680) { WhatsNewA(wide) } }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // --- Language: one model, two surfaces -----------------------------------------------------

    /**
     * The subtitle choices, in both groups, each with the line a viewer would see. Named for what they
     * are rather than as jargon; SDH keeps its abbreviation in brackets because that is what the
     * track lists in files and addons call it.
     */
    private data class SubtitleChoice(val name: String, val meaning: String)

    private val subtitleChoices = listOf(
        SubtitleChoice("Off", "No subtitles at all"),
        SubtitleChoice("Forced", "Only lines in a different language from the audio, and signs"),
        SubtitleChoice("Full", "Everything that's said"),
        SubtitleChoice("Deaf and hard of hearing (SDH)", "Everything that's said, plus sounds and who's speaking"),
    )

    /** Two moments of one scene, as column headers, and what each choice shows for them (in [subtitleChoices] order). */
    private class SubtitleScene(val first: String, val second: String, val lines: List<Pair<String?, String?>>)

    private val englishScene = SubtitleScene(
        "Said in English", "Said in Spanish",
        listOf(
            null to null,
            null to "Where's the money?",
            "Get in the car." to "Where's the money?",
            "[engine starts] Get in the car." to "[in Spanish] Where's the money?",
        ),
    )

    private val otherScene = SubtitleScene(
        "Said in Korean", "Said in Japanese",
        listOf(
            null to null,
            null to "We have to go.",
            "Do you want to see it?" to "We have to go.",
            "[fire crackling] Do you want to see it?" to "[in Japanese] We have to go.",
        ),
    )

    /** Settings: plain rows, no pictures (the maintainer's call, 2026-10-20). */
    @Composable
    private fun LanguageSettingsPage(wide: Boolean) {
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
                SettingsNavigationRow(title = "Subtitle language", description = "Same as my language (English)", isTablet = wide, onClick = {})
            }
        }
        SettingsSection(title = "Titles in English", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                SubtitleChoiceList(wide, englishScene, selected = 1)
            }
        }
        SettingsSection(title = "Titles in other languages", isTablet = wide) {
            SettingsGroup(isTablet = wide) {
                AudioChoiceList(wide, selected = 0)
                SettingsGroupDivider(isTablet = wide)
                SubtitleChoiceList(wide, otherScene, selected = 2)
            }
        }
        MoreSection(wide)
    }

    /** The wizard step: the same choices, each group with a still showing what you would get. */
    @Composable
    private fun WizardLanguageStep(wide: Boolean) {
        val tokens = MaterialTheme.nuvio
        Text("Step 4 of 9", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textMuted)
        Text("How do you watch?", style = MaterialTheme.typography.headlineMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "Pick your language, then how titles in it and in other languages should play. You can change all of this later in Settings → Language.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WizardValue("My language", "English")
            WizardValue("Subtitles in", "Same as my language")
        }
        val cardA: @Composable (Modifier) -> Unit = { m ->
            ScenarioCard(m, art = punisher, line = "Where's the money?", heading = "Titles in English", example = "The Punisher · English audio") {
                Label("Subtitles")
                SubtitleChoiceList(wide = false, scene = englishScene, selected = 1, compact = true)
            }
        }
        val cardB: @Composable (Modifier) -> Unit = { m ->
            ScenarioCard(m, art = burning, line = "Do you want to see it?", heading = "Titles in other languages", example = "Burning · Korean audio") {
                Label("Audio")
                AudioChoiceList(wide = false, selected = 0, compact = true)
                Label("Subtitles")
                SubtitleChoiceList(wide = false, scene = otherScene, selected = 2, compact = true)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                cardA(Modifier.weight(1f))
                cardB(Modifier.weight(1f))
            }
        } else {
            cardA(Modifier.fillMaxWidth())
            cardB(Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill("Back", filled = false)
            Pill("Next", filled = true)
        }
    }

    @Composable
    private fun WizardValue(label: String, value: String) {
        val tokens = MaterialTheme.nuvio
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(tokens.colors.surfaceCard).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ChevronRight, null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
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
    private fun ChoiceRow(
        wide: Boolean,
        title: String,
        options: List<String>,
        selected: Int,
        description: String? = null,
    ) {
        val tokens = MaterialTheme.nuvio
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (wide) 20.dp else 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary) }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.widthIn(max = 520.dp)) { Segmented(options, selected) }
        }
    }

    /**
     * Subtitles as a list rather than a segmented control: the honest names do not fit four across on
     * a phone. Every row shows the **same two moments** of one scene - a line in the audio's language,
     * then a line in another language - so Forced (only the second) and Full (both) differ visibly.
     */
    @Composable
    private fun SubtitleChoiceList(wide: Boolean, scene: SubtitleScene, selected: Int, compact: Boolean = false) {
        val tokens = MaterialTheme.nuvio
        val cellsBesideName = wide && !compact
        Column(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else if (wide) 12.dp else 8.dp, vertical = if (compact) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                if (!compact) {
                    Text("Subtitles", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                if (cellsBesideName) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColumnHeader(scene.first, Modifier.width(230.dp))
                        ColumnHeader(scene.second, Modifier.width(230.dp))
                    }
                }
            }
            if (!cellsBesideName) {
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColumnHeader(scene.first, Modifier.weight(1f))
                    ColumnHeader(scene.second, Modifier.weight(1f))
                }
            }
            subtitleChoices.forEachIndexed { i, choice ->
                val isSelected = i == selected
                val (first, second) = scene.lines[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) tokens.colors.overlaySelected else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Radio(isSelected)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(choice.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(choice.meaning, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                        if (!cellsBesideName) {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SubtitleCell(first, Modifier.weight(1f))
                                SubtitleCell(second, Modifier.weight(1f))
                            }
                        }
                    }
                    if (cellsBesideName) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubtitleCell(first, Modifier.width(230.dp))
                            SubtitleCell(second, Modifier.width(230.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ColumnHeader(text: String, modifier: Modifier) {
        Text(
            text, modifier = modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textMuted,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }

    /** One moment of the scene as the player would draw it: the subtitle, or nothing. */
    @Composable
    private fun SubtitleCell(line: String?, modifier: Modifier) {
        Box(
            modifier.height(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (line != null) {
                Text(line, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("no subtitle", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
            }
        }
    }

    /** Audio for titles in other languages: two plain choices, the Korean film named as an example. */
    @Composable
    private fun AudioChoiceList(wide: Boolean, selected: Int, compact: Boolean = false) {
        val tokens = MaterialTheme.nuvio
        val choices = listOf(
            "Original language" to "The language it was made in. For example, Korean for a Korean film.",
            "English" to "An English dub when the release has one, otherwise the original language.",
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else if (wide) 12.dp else 8.dp, vertical = if (compact) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!compact) {
                Text(
                    "Audio", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            choices.forEachIndexed { i, (name, meaning) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (i == selected) tokens.colors.overlaySelected else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Radio(i == selected)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(meaning, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                    }
                }
            }
            Text(
                "If a release doesn't have the original language, it plays in English and the Titles in English settings apply.",
                style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted,
                modifier = Modifier.padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }

    @Composable
    private fun Radio(selected: Boolean) {
        val tokens = MaterialTheme.nuvio
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) tokens.colors.accent else tokens.colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(tokens.colors.accent))
        }
    }

    @Composable
    private fun ScenarioCard(
        modifier: Modifier,
        art: String,
        line: String?,
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
                line?.let { SubtitleLine(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(heading, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(example, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                Spacer(Modifier.height(2.dp))
                controls()
            }
        }
    }

    @Composable
    private fun WizardFrame(wide: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 980.dp).fillMaxWidth().padding(horizontal = if (wide) 40.dp else 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
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

    // --- What's New -----------------------------------------------------------------------------

    private enum class P(val label: String) { Desktop("Desktop"), Android("Android"), Ios("iOS") }

    private val everywhere = listOf(P.Desktop, P.Android, P.Ios)

    private data class Feature(val title: String, val body: String, val visual: String, val platforms: List<P>)

    private val features = listOf(
        Feature("Social, rebuilt", "See what friends are watching right now, join them in one tap, and send each other recommendations.", "social", everywhere),
        Feature("Language and subtitles", "Original audio and full subtitles for foreign titles, your language and forced subtitles for the rest.", "language", everywhere),
        Feature("Pop-ups and the tray", "Nuvio Z can keep running in the tray and tell you when a friend starts watching.", "popup", listOf(P.Desktop)),
    )

    /** Improvements sit in their category with tags like everything else - never a greyed "other devices" list. */
    private val improvements = listOf(
        "Next episode's loading screen shows the right episode and a sharp backdrop" to everywhere,
        "A notice when you've been signed out, with a Sign in button" to everywhere,
        "Start with Windows" to listOf(P.Desktop),
        "Keeps itself signed on iPhone" to listOf(P.Ios),
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(f.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            PlatformTags(f.platforms)
                        }
                        Text(f.body, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        if (f.visual == "language") ReviewSettingsLink()
                    }
                }
            }
            if (!compactPreview) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Improvements", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    improvements.forEach { (line, platforms) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(tokens.colors.accent))
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
                            PlatformTags(platforms)
                        }
                    }
                }
                Folded("8 fixes")
                DoneRow()
            }
        }
    }

    /** One tag per platform; all three collapse to "All platforms" so the common case stays quiet. */
    @Composable
    private fun PlatformTags(platforms: List<P>) {
        val tokens = MaterialTheme.nuvio
        val labels = if (platforms.containsAll(everywhere)) listOf("All platforms") else platforms.map { it.label }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.clip(CircleShape).border(1.dp, tokens.colors.borderDefault, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, maxLines = 1,
                )
            }
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
        Text("October 2026", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary, fontWeight = FontWeight.SemiBold)
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

    /** Test-only illustrations shared with the production-layout render harness. */
    @Composable
    internal fun PreviewImage(key: String) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f)) { Visual(key) }
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
