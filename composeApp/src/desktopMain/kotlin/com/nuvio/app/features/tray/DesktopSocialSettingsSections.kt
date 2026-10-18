package com.nuvio.app.features.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.settings.SettingsChoiceRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow

/**
 * Desktop's own Social settings: Pop-ups and In the background. Registered with
 * `SocialSettingsExtensions` at startup, so the shared page draws them between Privacy and Home.
 */
@Composable
internal fun DesktopSocialSettingsSections(isTablet: Boolean) {
    val prefs by DesktopTrayPreferences.state.collectAsState()
    val startWithWindows by DesktopStartWithWindows.enabled.collectAsState()
    LaunchedEffect(Unit) { DesktopStartWithWindows.refresh() }

    SettingsSection(title = "Pop-ups", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Show pop-ups",
                description = "Small cards in the corner of your screen, even while Nuvio is in the tray",
                checked = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupsEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Friend requests",
                checked = prefs.popupFriendRequests,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendRequests,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Party invites and join requests",
                checked = prefs.popupParties,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupParties,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Friends starting to watch",
                description = "Only friends you turned on, from their profile",
                checked = prefs.popupFriendWatching,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendWatching,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Recommendations",
                description = "Titles friends recommend, and when they start one you recommended",
                checked = prefs.popupRecommendations,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupRecommendations,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Over fullscreen apps and games",
                description = "Off: they wait in your Inbox",
                checked = prefs.popupsOverFullscreen,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupsOverFullscreen,
            )
        }
    }

    SettingsSection(title = "In the background", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            // Before the first-close prompt is answered neither chip is lit: nothing has been chosen yet.
            SettingsChoiceRow(
                title = "Closing the window",
                options = listOf(
                    DesktopCloseAction.HideToTray to "Hide to tray",
                    DesktopCloseAction.Quit to "Quit Nuvio",
                ),
                selected = prefs.closeAction,
                isTablet = isTablet,
                onSelect = DesktopTrayPreferences::setCloseAction,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Start with Windows",
                description = if (DesktopStartWithWindows.isAvailable) {
                    "Opens in the tray when you sign in, so pop-ups reach you without opening Nuvio"
                } else {
                    "Available in the installed app"
                },
                checked = startWithWindows == true,
                enabled = DesktopStartWithWindows.isAvailable && startWithWindows != null,
                isTablet = isTablet,
                onCheckedChange = DesktopStartWithWindows::setEnabled,
            )
        }
    }
}
