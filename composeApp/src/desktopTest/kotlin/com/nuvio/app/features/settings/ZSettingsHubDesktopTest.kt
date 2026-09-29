package com.nuvio.app.features.settings

import androidx.compose.ui.ImageComposeScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reorganised Settings hub on desktop (setup + settings pass): search lands where the rows now
 * are. Desktop's own copy of `ZSettingsHubTest` - that one runs in the mobile repository's Android
 * host suite, which this repository cannot build - with desktop's navigation rows.
 */
class ZSettingsHubDesktopTest {

    private fun entries(runSetupAgain: Boolean = true): List<SettingsSearchEntry> {
        var result: List<SettingsSearchEntry> = emptyList()
        val scene = ImageComposeScene(width = 100, height = 100) {
            result = settingsSearchEntries(
                isTablet = true,
                pluginsEnabled = false,
                downloadsEnabled = true,
                notificationsEnabled = false,
                externalPlayerSupported = false,
                supportersContributorsPageEnabled = true,
                accountDeletionEnabled = false,
                personalMediaAddonCopyEnabled = false,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = true,
                checkForUpdatesAvailable = true,
                runSetupAgainAvailable = runSetupAgain,
            )
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return result
    }

    private fun List<SettingsSearchEntry>.byKey(key: String): SettingsSearchEntry =
        assertNotNull(firstOrNull { it.key == key }, "no search entry '$key'")

    @Test
    fun advancedSetupIsSearchable() {
        assertEquals(SettingsSearchTarget.AdvancedSetup, entries().byKey("advanced-setup").target)
    }

    @Test
    fun desktopNavigationRowsLandOnTheNavigationPage() {
        val all = entries()
        val navigation = SettingsSearchTarget.Page(SettingsPage.Navigation)
        assertEquals(navigation, all.byKey("navigation").target)
        assertEquals(navigation, all.byKey("desktop-navigation").target)
        assertEquals(navigation, all.byKey("nav-bar-style").target)
        // Android's glow and iOS's Liquid Glass would be dead ends here.
        assertFalse(all.any { it.key == "nav-bar-glow" || it.key == "liquid-glass" })
    }

    @Test
    fun movedAndRenamedRowsReadRight() {
        val all = entries()
        assertEquals("Run Initial Setup again", all.byKey("run-setup-again").title)
        assertEquals(SettingsSearchTarget.Page(SettingsPage.About), all.byKey("about").target)
        assertEquals("About", all.byKey("check-updates").page)
        assertEquals("Appearance", all.byKey("layout").title)
        assertFalse(all.any { it.page == "Layout" || it.page == "Content & Discovery" })
        assertFalse(entries(runSetupAgain = false).any { it.key == "run-setup-again" })
    }

    @Test
    fun trackingLivesUnderIntegrationsAndEveryPageIsReachable() {
        assertEquals(SettingsPage.Integrations, SettingsPage.TraktAuthentication.parentPage)
        entries().mapNotNull { (it.target as? SettingsSearchTarget.Page)?.page }.distinct().forEach { page ->
            var current: SettingsPage? = page
            var steps = 0
            while (current != null && current != SettingsPage.Root && steps < 8) {
                current = current.parentPage
                steps++
            }
            assertTrue(page == SettingsPage.Root || current == SettingsPage.Root, "$page is not under Root")
        }
        assertEquals(listOf(SettingsPage.Navigation, SettingsPage.About), SettingsPage.entries.takeLast(2))
    }
}
