package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.whatsnew.ChangelogAction
import com.nuvio.app.features.whatsnew.ChangelogPlatform
import com.nuvio.app.features.whatsnew.NewSettingsOffer
import com.nuvio.app.features.whatsnew.WhatsNewCategory
import com.nuvio.app.features.whatsnew.WhatsNewEventUi
import com.nuvio.app.features.whatsnew.WhatsNewItem
import com.nuvio.app.features.whatsnew.WhatsNewScreen
import com.nuvio.app.features.whatsnew.WhatsNewSection
import com.nuvio.app.features.whatsnew.WhatsNewVersion
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The shipped language settings, "New settings available" offer and What's New (2026-10-20), drawn
 * through their production composables - the design candidates live in `LanguageWhatsNewDesignHarness`.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*LanguageSettingsRenderHarness" --rerun
 * # PNGs land in composeApp/build/language-settings-render/
 * ```
 *
 * The language sections read the active profile's stored preferences, so on a fresh test machine
 * they show the migrated defaults.
 */
class LanguageSettingsRenderHarness {

    private val outputDir = File("build/language-settings-render")

    private val all = listOf(ChangelogPlatform.DESKTOP, ChangelogPlatform.ANDROID, ChangelogPlatform.IOS)

    private val event = WhatsNewEventUi(
        seq = 5,
        date = "Oct 20, 2026",
        summary = null,
        versions = listOf(
            WhatsNewVersion(ChangelogPlatform.DESKTOP, "0.1.27-alpha-z1", isViewer = true),
            WhatsNewVersion(ChangelogPlatform.ANDROID, "0.5.5-z1", isViewer = false),
        ),
        sections = listOf(
            WhatsNewSection(
                WhatsNewCategory.NewFeatures,
                listOf(
                    WhatsNewItem("Language and subtitles", "Placeholder copy.", ChangelogAction.REVIEW_SETTINGS, all, image = "missing.webp"),
                    WhatsNewItem("Pop-ups and the tray", "Placeholder copy.", platforms = listOf(ChangelogPlatform.DESKTOP)),
                ),
            ),
            WhatsNewSection(
                WhatsNewCategory.Improvements,
                listOf(WhatsNewItem("Keeps itself signed on iPhone", "", platforms = listOf(ChangelogPlatform.IOS))),
            ),
            WhatsNewSection(
                WhatsNewCategory.BugFixes,
                listOf(WhatsNewItem("A fix", "", platforms = all), WhatsNewItem("Another fix", "", platforms = all)),
            ),
        ),
        otherDevices = emptyList(),
    )

    @Test
    fun renderShippedSurfaces() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()
        for ((w, h) in listOf(1280 to 1700, 420 to 2300)) {
            val wide = w >= 700
            render("language-$w", w, h, failures) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = if (wide) 32.dp else 12.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        LanguageSettingsSections(isTablet = wide)
                    }
                }
            }
        }
        for ((w, h) in listOf(1280 to 820, 420 to 900)) {
            render("offer-$w", w, h, failures) { NewSettingsOffer(onYes = {}, onNo = {}, onBackToWhatsNew = {}) }
            render("whatsnew-$w", w, if (w >= 700) 1000 else 1400, failures) {
                WhatsNewScreen(versionName = "0.1.27-alpha-z1", events = listOf(event), onContinue = {}, showHistory = false, onAction = {})
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun render(name: String, widthDp: Int, heightDp: Int, failures: MutableList<String>, content: @Composable () -> Unit) {
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
                scene.render(16_000_000L)
                val image = scene.render(500_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData returned null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "$name: ${error::class.simpleName}: ${error.message}" }
    }
}
