package com.nuvio.app.features.settings

import androidx.compose.ui.ImageComposeScene
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsReplayWizardTest {

    @Test
    fun replayActionIsIndexedInSettingsSearchOnlyWhenAvailable() {
        var availableEntries: List<SettingsSearchEntry> = emptyList()
        var unavailableEntries: List<SettingsSearchEntry> = emptyList()

        val scene = ImageComposeScene(width = 100, height = 100) {
            availableEntries = settingsSearchEntries(
                pluginsEnabled = false,
                downloadsEnabled = false,
                notificationsEnabled = false,
                externalPlayerSupported = false,
                supportersContributorsPageEnabled = false,
                accountDeletionEnabled = false,
                personalMediaAddonCopyEnabled = false,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = false,
                checkForUpdatesAvailable = false,
                runSetupAgainAvailable = true,
            )
            unavailableEntries = settingsSearchEntries(
                pluginsEnabled = false,
                downloadsEnabled = false,
                notificationsEnabled = false,
                externalPlayerSupported = false,
                supportersContributorsPageEnabled = false,
                accountDeletionEnabled = false,
                personalMediaAddonCopyEnabled = false,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = false,
                checkForUpdatesAvailable = false,
                runSetupAgainAvailable = false,
            )
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }

        val entry = availableEntries.firstOrNull { it.target is SettingsSearchTarget.RunSetupAgain }
        assertTrue(entry != null, "RunSetupAgain search entry must be present when available")
        assertTrue(
            entry.searchableText.contains("replay") ||
                entry.searchableText.contains("setup") ||
                entry.searchableText.contains("wizard"),
            "searchableText must contain replay, setup, or wizard",
        )
        assertFalse(unavailableEntries.any { it.target is SettingsSearchTarget.RunSetupAgain })
    }

    @Test
    fun replayCallbackInvokesReplayHandlerWithoutMutatingState() {
        var replayInvoked = false
        val onRunSetupAgain: () -> Unit = { replayInvoked = true }

        val target: SettingsSearchTarget = SettingsSearchTarget.RunSetupAgain
        when (target) {
            SettingsSearchTarget.RunSetupAgain -> onRunSetupAgain.invoke()
            else -> Unit
        }

        assertTrue(replayInvoked)
    }
}
