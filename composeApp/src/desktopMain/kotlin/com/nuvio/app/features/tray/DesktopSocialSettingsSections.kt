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
import nuvio.composeapp.generated.resources.social_alert_friend_requests
import nuvio.composeapp.generated.resources.social_alert_parties
import nuvio.composeapp.generated.resources.social_alert_recs
import nuvio.composeapp.generated.resources.social_alert_recs_desc
import nuvio.composeapp.generated.resources.social_alert_watching
import nuvio.composeapp.generated.resources.social_alert_watching_desc
import nuvio.composeapp.generated.resources.social_close_window
import nuvio.composeapp.generated.resources.social_hide_to_tray
import nuvio.composeapp.generated.resources.social_popups
import nuvio.composeapp.generated.resources.social_popups_fullscreen
import nuvio.composeapp.generated.resources.social_popups_fullscreen_desc
import nuvio.composeapp.generated.resources.social_quit_nuvio
import nuvio.composeapp.generated.resources.social_show_popups
import nuvio.composeapp.generated.resources.social_show_popups_desc
import nuvio.composeapp.generated.resources.social_start_windows
import nuvio.composeapp.generated.resources.social_start_windows_desc
import nuvio.composeapp.generated.resources.social_start_windows_unavailable
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop's own Social settings: Pop-ups. Registered with
 * `SocialSettingsExtensions` at startup, so the shared page draws them between Privacy and Home.
 */
@Composable
internal fun DesktopSocialSettingsSections(isTablet: Boolean) {
    val prefs by DesktopTrayPreferences.state.collectAsState()

    SettingsSection(title = stringResource(Res.string.social_popups), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.social_show_popups),
                description = stringResource(Res.string.social_show_popups_desc),
                checked = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupsEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_friend_requests),
                checked = prefs.popupFriendRequests,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendRequests,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_parties),
                checked = prefs.popupParties,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupParties,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_watching),
                description = stringResource(Res.string.social_alert_watching_desc),
                checked = prefs.popupFriendWatching,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendWatching,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_recs),
                description = stringResource(Res.string.social_alert_recs_desc),
                checked = prefs.popupRecommendations,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupRecommendations,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_popups_fullscreen),
                description = stringResource(Res.string.social_popups_fullscreen_desc),
                checked = prefs.popupsOverFullscreen,
                enabled = prefs.popupsEnabled,
                isTablet = isTablet,
                onCheckedChange = DesktopTrayPreferences::setPopupsOverFullscreen,
            )
        }
    }
}

/**
 * Closing the window and Start with Windows (2026-10-22), registered into Settings → Advanced → Startup
 * through `PlatformSettingsExtensions`. They were "In the background" under Social; they are about the
 * app itself, so they moved beside "Remember last profile at startup". Drawn inside that group, so
 * each row brings its own divider.
 */
@Composable
internal fun DesktopStartupSettingsRows(isTablet: Boolean) {
    val prefs by DesktopTrayPreferences.state.collectAsState()
    val startWithWindows by DesktopStartWithWindows.enabled.collectAsState()
    LaunchedEffect(Unit) { DesktopStartWithWindows.refresh() }
    SettingsGroupDivider(isTablet = isTablet)
    // Before the first-close prompt is answered neither chip is lit: nothing has been chosen yet.
    SettingsChoiceRow(
        title = stringResource(Res.string.social_close_window),
        options = listOf(
            DesktopCloseAction.HideToTray to stringResource(Res.string.social_hide_to_tray),
            DesktopCloseAction.Quit to stringResource(Res.string.social_quit_nuvio),
        ),
        selected = prefs.closeAction,
        isTablet = isTablet,
        onSelect = DesktopTrayPreferences::setCloseAction,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.social_start_windows),
        description = if (DesktopStartWithWindows.isAvailable) {
            stringResource(Res.string.social_start_windows_desc)
        } else {
            stringResource(Res.string.social_start_windows_unavailable)
        },
        checked = startWithWindows == true,
        enabled = DesktopStartWithWindows.isAvailable && startWithWindows != null,
        isTablet = isTablet,
        onCheckedChange = DesktopStartWithWindows::setEnabled,
    )
}
