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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedChoice
import com.nuvio.app.features.setup.AdvancedSetupPlatformArea
import com.nuvio.app.features.setup.SetupQuestion
import com.nuvio.app.features.setup.SetupToggleRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.advanced_setup_startup_summary_quit
import nuvio.composeapp.generated.resources.advanced_setup_startup_summary_starts
import nuvio.composeapp.generated.resources.advanced_setup_startup_summary_tray
import nuvio.composeapp.generated.resources.social_close_window
import nuvio.composeapp.generated.resources.social_hide_to_tray
import nuvio.composeapp.generated.resources.social_quit_nuvio
import nuvio.composeapp.generated.resources.social_start_windows
import nuvio.composeapp.generated.resources.social_start_windows_desc
import nuvio.composeapp.generated.resources.social_start_windows_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Advanced Setup's Startup & tray area (2026-10-22), registered through `AdvancedSetupExtensions`:
 * the same two answers as Settings → Advanced → Startup, over the close prompt's own picture.
 */
internal val DesktopStartupAdvancedSetup = AdvancedSetupPlatformArea(
    summary = {
        val prefs by DesktopTrayPreferences.state.collectAsState()
        val starts by DesktopStartWithWindows.enabled.collectAsState()
        listOfNotNull(
            when (prefs.closeAction) {
                DesktopCloseAction.HideToTray -> stringResource(Res.string.advanced_setup_startup_summary_tray)
                DesktopCloseAction.Quit -> stringResource(Res.string.advanced_setup_startup_summary_quit)
                DesktopCloseAction.Ask -> null
            },
            if (starts == true) stringResource(Res.string.advanced_setup_startup_summary_starts) else null,
        ).joinToString(" · ")
    },
    controls = { DesktopStartupControls() },
    preview = { desktop ->
        Box(Modifier.fillMaxSize().padding(if (desktop) 40.dp else 14.dp), contentAlignment = Alignment.Center) {
            ClosePromptPicture(Modifier.widthIn(max = if (desktop) 620.dp else 520.dp).fillMaxWidth().aspectRatio(if (desktop) 16f / 9f else 2.6f))
        }
    },
)

@Composable
private fun DesktopStartupControls() {
    val prefs by DesktopTrayPreferences.state.collectAsState()
    val startWithWindows by DesktopStartWithWindows.enabled.collectAsState()
    LaunchedEffect(Unit) { DesktopStartWithWindows.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SetupQuestion(title = stringResource(Res.string.social_close_window), detail = null) {
            NuvioSegmentedChoice(
                segments = listOf(
                    NuvioSegment(stringResource(Res.string.social_hide_to_tray), DesktopCloseAction.HideToTray),
                    NuvioSegment(stringResource(Res.string.social_quit_nuvio), DesktopCloseAction.Quit),
                ),
                // Ask (the first-close prompt not yet answered) lights neither, as in Settings.
                selected = prefs.closeAction.takeIf { it != DesktopCloseAction.Ask },
                onSelected = DesktopTrayPreferences::setCloseAction,
            )
        }
        SetupToggleRow(
            title = stringResource(Res.string.social_start_windows),
            description = if (DesktopStartWithWindows.isAvailable) {
                stringResource(Res.string.social_start_windows_desc)
            } else {
                stringResource(Res.string.social_start_windows_unavailable)
            },
            checked = startWithWindows == true,
            onCheckedChange = { if (DesktopStartWithWindows.isAvailable) DesktopStartWithWindows.setEnabled(it) },
        )
    }
}
