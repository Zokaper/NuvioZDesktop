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
import com.nuvio.app.features.whatsnew.WhatsNewContent
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

    private val illustrations = LanguageWhatsNewDesignHarness()

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
                    WhatsNewItem("Social, rebuilt", "See what friends are watching right now, join them in one tap, and send each other recommendations.", platforms = all, image = "social"),
                    WhatsNewItem("Language and subtitles", "Original audio and full subtitles for foreign titles, your language and forced subtitles for the rest.", ChangelogAction.REVIEW_SETTINGS, all, image = "language"),
                ),
            ),
            WhatsNewSection(
                WhatsNewCategory.Improvements,
                listOf(
                    // An illustrated improvement belongs with the picture cards too.
                    WhatsNewItem("Pop-ups and the tray", "Nuvio Z can keep running in the tray and tell you when a friend starts watching.", platforms = listOf(ChangelogPlatform.DESKTOP), image = "popup"),
                    WhatsNewItem("Next episode's loading screen shows the right episode and a sharp backdrop", "", platforms = all),
                    WhatsNewItem("A notice when you've been signed out, with a Sign in button", "", platforms = all),
                    WhatsNewItem("Start with Windows", "", platforms = listOf(ChangelogPlatform.DESKTOP)),
                    WhatsNewItem("Keeps itself signed on iPhone", "", platforms = listOf(ChangelogPlatform.IOS)),
                ),
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
            render("whatsnew-$w", w, h, failures) {
                ShippedWhatsNew()
            }
        }
        for ((w, h) in listOf(1280 to 2350, 420 to 1950)) {
            render("whatsnew-full-$w", w, h, failures) { ShippedWhatsNew(topAligned = true) }
        }
        // Check the real resource loader's graceful fallback without preview illustrations.
        render("whatsnew-missing-image", 420, 900, failures) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WhatsNewContent(
                    versionName = "Preview", onContinue = {}, showHistory = false,
                    events = listOf(event.copy(sections = listOf(WhatsNewSection(
                        WhatsNewCategory.NewFeatures,
                        listOf(WhatsNewItem("Missing artwork", "The entry remains readable.", platforms = all, image = "missing.webp")),
                    )))),
                )
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Composable
    private fun ShippedWhatsNew(topAligned: Boolean = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = if (topAligned) Alignment.TopCenter else Alignment.Center) {
            WhatsNewContent(
                versionName = "0.1.27-alpha-z1", events = listOf(event), onContinue = {},
                showHistory = false, onAction = {}, imageContent = { illustrations.PreviewImage(it) },
            )
        }
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
