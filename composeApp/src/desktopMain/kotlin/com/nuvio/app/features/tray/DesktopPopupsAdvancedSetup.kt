package com.nuvio.app.features.tray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.setup.AdvancedSetupPlatformArea
import com.nuvio.app.features.setup.SetupToggleRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.advanced_setup_off
import nuvio.composeapp.generated.resources.advanced_setup_on
import nuvio.composeapp.generated.resources.social_alert_friend_requests
import nuvio.composeapp.generated.resources.social_popups
import nuvio.composeapp.generated.resources.social_alert_parties
import nuvio.composeapp.generated.resources.social_alert_recs
import nuvio.composeapp.generated.resources.social_alert_watching
import nuvio.composeapp.generated.resources.social_popups_fullscreen
import nuvio.composeapp.generated.resources.social_popups_fullscreen_desc
import nuvio.composeapp.generated.resources.social_show_popups
import nuvio.composeapp.generated.resources.social_show_popups_desc
import org.jetbrains.compose.resources.stringResource

/**
 * Advanced Setup's Notifications area on desktop (2026-10-24), registered through
 * `AdvancedSetupExtensions.alerts`: the same switches as Social → Pop-ups, over the pop-up's own
 * picture. Device Setup's notifications step is this panel too, while the machine has not answered it.
 */
internal val DesktopPopupsAdvancedSetup = AdvancedSetupPlatformArea(
    summary = {
        val prefs by DesktopTrayPreferences.state.collectAsState()
        stringResource(Res.string.social_popups) + " " +
            stringResource(if (prefs.popupsEnabled) Res.string.advanced_setup_on else Res.string.advanced_setup_off).lowercase()
    },
    controls = { DesktopPopupsControls() },
    preview = { desktop ->
        Box(Modifier.fillMaxSize().padding(if (desktop) 40.dp else 14.dp), contentAlignment = Alignment.Center) {
            ClosePromptPicture(Modifier.widthIn(max = if (desktop) 620.dp else 520.dp).fillMaxWidth().aspectRatio(if (desktop) 16f / 9f else 2.6f))
        }
    },
)

@Composable
private fun DesktopPopupsControls() {
    val prefs by DesktopTrayPreferences.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupToggleRow(
            title = stringResource(Res.string.social_show_popups),
            description = stringResource(Res.string.social_show_popups_desc),
            checked = prefs.popupsEnabled,
            onCheckedChange = DesktopTrayPreferences::setPopupsEnabled,
        )
        // The kinds only mean something while pop-ups are on, so they follow the switch.
        if (prefs.popupsEnabled) {
            SetupToggleRow(
                title = stringResource(Res.string.social_alert_watching),
                checked = prefs.popupFriendWatching,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendWatching,
            )
            SetupToggleRow(
                title = stringResource(Res.string.social_alert_parties),
                checked = prefs.popupParties,
                onCheckedChange = DesktopTrayPreferences::setPopupParties,
            )
            SetupToggleRow(
                title = stringResource(Res.string.social_alert_friend_requests),
                checked = prefs.popupFriendRequests,
                onCheckedChange = DesktopTrayPreferences::setPopupFriendRequests,
            )
            SetupToggleRow(
                title = stringResource(Res.string.social_alert_recs),
                checked = prefs.popupRecommendations,
                onCheckedChange = DesktopTrayPreferences::setPopupRecommendations,
            )
            SetupToggleRow(
                title = stringResource(Res.string.social_popups_fullscreen),
                description = stringResource(Res.string.social_popups_fullscreen_desc),
                checked = prefs.popupsOverFullscreen,
                onCheckedChange = DesktopTrayPreferences::setPopupsOverFullscreen,
            )
        }
    }
}
